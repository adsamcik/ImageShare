package com.imageshare.core.processing

import android.content.ContentResolver
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
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
     * PNG is pass-through for every mode. PNG metadata preservation and tEXt/iTXt
     * removal are out of Phase 1 MVP scope.
     *
     * PNG tEXt/iTXt chunks are not inspected or rewritten in Phase 1.
     * Preserve modes without source bytes or a resolvable source Uri fall back to
     * [MetadataMode.StripAll].
     */
    suspend fun apply(
        encoded: ByteArray,
        format: EncodeFormat,
        mode: MetadataMode,
        source: MetadataSource = MetadataSource.NONE,
    ): ByteArray = withContext(Dispatchers.IO) {
        when (format) {
            EncodeFormat.PNG -> encoded.copyOf()
            EncodeFormat.JPEG,
            EncodeFormat.HEIF,
            EncodeFormat.WEBP_LOSSY,
            EncodeFormat.WEBP_LOSSLESS,
            -> applyExifMode(encoded, mode, source)
        }
    }

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
    }
}
