package com.imageshare.core.processing

import android.content.ContentResolver
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.Modifier
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MetadataApplier(private val resolver: ContentResolver? = null) {
    /**
     * Applies [mode] to already-[encoded] image bytes and returns a new byte array.
     *
     * Behaviour matrix:
     *
     * JPEG and WebP:
     * - StripAll strips all EXIF tags.
     * - PreserveSafe strips all tags, writes the whitelist, and sets Orientation=1.
     * - PreserveAll copies all standard tags and sets Orientation=1.
     *
     * PNG StripAll removes text, EXIF, and timestamp chunks while retaining
     * chunks that affect image rendering. PreserveSafe and PreserveAll pass PNG
     * chunks through unchanged; selective PNG preservation is intentionally not
     * part of the v1.0 contract.
     *
     * For EXIF-backed formats, preserve modes without source bytes or a resolvable
     * source Uri fall back to [MetadataMode.StripAll].
     */
    suspend fun apply(
        encoded: ByteArray,
        format: EncodeFormat,
        mode: MetadataMode,
        source: MetadataSource = MetadataSource.NONE,
    ): ByteArray = withContext(Dispatchers.IO) {
        when (format) {
            EncodeFormat.PNG -> when (mode) {
                MetadataMode.StripAll -> stripPngMetadata(encoded)
                MetadataMode.PreserveSafe,
                MetadataMode.PreserveAll -> encoded.copyOf()
            }
            EncodeFormat.JPEG,
            EncodeFormat.HEIF,
            EncodeFormat.AVIF,
            EncodeFormat.WEBP_LOSSY,
            EncodeFormat.WEBP_LOSSLESS,
            -> applyExifMode(encoded, mode, source)
        }
    }

    private fun stripPngMetadata(bytes: ByteArray): ByteArray {
        if (!bytes.hasPngSignature()) {
            return bytes.copyOf()
        }

        val output = ByteArrayOutputStream(bytes.size)
        output.write(bytes, 0, PNG_SIGNATURE.size)
        var offset = PNG_SIGNATURE.size
        var sawIhdr = false
        while (offset < bytes.size) {
            if (bytes.size - offset < PNG_CHUNK_OVERHEAD) {
                return bytes.copyOf()
            }

            val length = bytes.readPngUnsignedInt(offset)
            val typeStart = offset + PNG_CHUNK_LENGTH_SIZE
            val dataStart = typeStart + PNG_CHUNK_TYPE_SIZE
            val chunkEnd = dataStart.toLong() + length + PNG_CHUNK_CRC_SIZE
            if (chunkEnd > bytes.size.toLong()) {
                return bytes.copyOf()
            }

            val type = bytes.decodeToString(typeStart, dataStart)
            if (!sawIhdr) {
                if (type != PNG_IHDR || length != PNG_IHDR_DATA_LENGTH) {
                    return bytes.copyOf()
                }
                sawIhdr = true
            }

            if (type !in PNG_STRIPPED_METADATA_CHUNKS) {
                output.write(bytes, offset, chunkEnd.toInt() - offset)
            }
            offset = chunkEnd.toInt()
            if (type == PNG_IEND) {
                return if (length == PNG_IEND_DATA_LENGTH && offset == bytes.size) {
                    output.toByteArray()
                } else {
                    bytes.copyOf()
                }
            }
        }
        return bytes.copyOf()
    }

    private fun ByteArray.hasPngSignature(): Boolean =
        size >= PNG_SIGNATURE.size &&
            PNG_SIGNATURE.indices.all { index -> this[index] == PNG_SIGNATURE[index] }

    private fun ByteArray.readPngUnsignedInt(offset: Int): Long =
        ((this[offset].toLong() and BYTE_MASK) shl PNG_BYTE_3_SHIFT) or
            ((this[offset + PNG_BYTE_1_OFFSET].toLong() and BYTE_MASK) shl PNG_BYTE_2_SHIFT) or
            ((this[offset + PNG_BYTE_2_OFFSET].toLong() and BYTE_MASK) shl PNG_BYTE_1_SHIFT) or
            (this[offset + PNG_BYTE_3_OFFSET].toLong() and BYTE_MASK)

    private fun applyExifMode(
        encoded: ByteArray,
        mode: MetadataMode,
        source: MetadataSource,
    ): ByteArray {
        val sourceAttributes = when (mode) {
            MetadataMode.StripAll -> emptyMap()
            MetadataMode.PreserveSafe -> readSourceAttributes(source, SAFE_EXIF_TAGS)
            MetadataMode.PreserveAll -> readSourceAttributes(source, STANDARD_EXIF_TAGS)
        }
        val effectiveMode = if (mode == MetadataMode.StripAll || sourceAttributes == null) {
            MetadataMode.StripAll
        } else {
            mode
        }

        return rewriteEncoded(encoded) { exif ->
            stripAttributes(exif)
            if (effectiveMode != MetadataMode.StripAll && sourceAttributes != null) {
                sourceAttributes.forEach { (tag, value) -> exif.setAttribute(tag, value) }
                exif.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString(),
                )
            }
        }
    }

    private fun rewriteEncoded(
        encoded: ByteArray,
        mutate: (ExifInterface) -> Unit,
    ): ByteArray {
        val file = createTempFile()
        try {
            try {
                file.writeBytes(encoded)
                val exif = ExifInterface(file)
                mutate(exif)
                exif.saveAttributes()
                return file.readBytes()
            } catch (error: IOException) {
                throw MetadataError.WriteFailed(error)
            }
        } finally {
            file.delete()
        }
    }

    private fun readSourceAttributes(
        source: MetadataSource,
        tags: Set<String>,
    ): Map<String, String?>? {
        val exif = source.openExifOrNull() ?: return null

        return tags.mapNotNull { tag ->
            exif.getAttribute(tag)?.let { value -> tag to value }
        }.toMap()
    }

    private fun MetadataSource.openExifOrNull(): ExifInterface? =
        try {
            when {
                originalBytes != null -> ExifInterface(ByteArrayInputStream(originalBytes))
                originalUri != null && resolver != null -> openUriExif(originalUri)
                else -> null
            }
        } catch (error: IOException) {
            throw MetadataError.ReadFailed(error)
        }

    private fun openUriExif(uri: android.net.Uri): ExifInterface? =
        resolver?.openInputStream(uri)?.use { stream -> ExifInterface(stream) }

    private fun stripAttributes(exif: ExifInterface) {
        STANDARD_EXIF_TAGS.forEach { tag -> exif.setAttribute(tag, null) }
    }

    private fun createTempFile(): File =
        Files.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX).toFile()

    companion object {
        val SAFE_EXIF_TAGS: Set<String> = setOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_BITS_PER_SAMPLE,
        )

        val STANDARD_EXIF_TAGS: Set<String> = ExifInterface::class.java.fields
            .asSequence()
            .filter { field ->
                Modifier.isStatic(field.modifiers) &&
                    field.name.startsWith("TAG_") &&
                    field.type == String::class.java
            }
            .map { field -> field.get(null) as String }
            .toSet()

        private const val TEMP_FILE_PREFIX = "imageshare-meta-"
        private const val TEMP_FILE_SUFFIX = ".bin"
        private const val BYTE_MASK = 0xffL
        private const val PNG_BYTE_3_SHIFT = 24
        private const val PNG_BYTE_2_SHIFT = 16
        private const val PNG_BYTE_1_SHIFT = 8
        private const val PNG_BYTE_1_OFFSET = 1
        private const val PNG_BYTE_2_OFFSET = 2
        private const val PNG_BYTE_3_OFFSET = 3
        private const val PNG_CHUNK_LENGTH_SIZE = 4
        private const val PNG_CHUNK_TYPE_SIZE = 4
        private const val PNG_CHUNK_CRC_SIZE = 4
        private const val PNG_CHUNK_HEADER_SIZE = PNG_CHUNK_LENGTH_SIZE + PNG_CHUNK_TYPE_SIZE
        private const val PNG_CHUNK_OVERHEAD = PNG_CHUNK_HEADER_SIZE + PNG_CHUNK_CRC_SIZE
        private const val PNG_IHDR = "IHDR"
        private const val PNG_IEND = "IEND"
        private const val PNG_IHDR_DATA_LENGTH = 13L
        private const val PNG_IEND_DATA_LENGTH = 0L
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        private val PNG_STRIPPED_METADATA_CHUNKS = setOf(
            "tEXt",
            "zTXt",
            "iTXt",
            "eXIf",
            "tIME",
        )
    }
}
