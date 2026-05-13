package com.imageshare.core.processing

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.heifwriter.AvifWriter
import androidx.heifwriter.HeifWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Suppress("TooManyFunctions")
class Encoder {
    suspend fun encode(
        bitmap: Bitmap,
        format: EncodeFormat,
        quality: Int,
        alphaPolicy: AlphaPolicy,
        enableAvifBeta: Boolean = false,
    ): EncodeResult = withContext(Dispatchers.IO) {
        if (quality !in MIN_QUALITY..MAX_QUALITY) {
            throw EncodeError.Invalid("quality out of range")
        }

        try {
            encodeBlocking(bitmap, format, quality, alphaPolicy, enableAvifBeta)
        } catch (error: IOException) {
            throw EncodeError.IoError(error)
        }
    }

    private fun encodeBlocking(
        bitmap: Bitmap,
        format: EncodeFormat,
        quality: Int,
        alphaPolicy: AlphaPolicy,
        enableAvifBeta: Boolean,
    ): EncodeResult {
        val preparedBitmap = bitmap.prepareForEncoding(format, alphaPolicy)
        val effectiveQuality = if (format.isLossless()) MAX_QUALITY else quality
        return when (format) {
            EncodeFormat.HEIF -> encodeHeif(bitmap, preparedBitmap, effectiveQuality)
            EncodeFormat.AVIF -> encodeAvif(bitmap, preparedBitmap, effectiveQuality, enableAvifBeta)
            EncodeFormat.JPEG,
            EncodeFormat.PNG,
            EncodeFormat.WEBP_LOSSY,
            EncodeFormat.WEBP_LOSSLESS,
            -> encodeWithBitmapCompress(bitmap, preparedBitmap, format, effectiveQuality)
        }
    }

    private fun encodeWithBitmapCompress(
        originalBitmap: Bitmap,
        bitmapToEncode: Bitmap,
        format: EncodeFormat,
        quality: Int,
    ): EncodeResult {
        val output = ByteArrayOutputStream()
        try {
            val encoded = if (format == EncodeFormat.JPEG) {
                NativeJpegEncoder.encode(bitmapToEncode, quality)
                    ?: encodeWithPlatform(bitmapToEncode, format, quality, output)
            } else {
                encodeWithPlatform(bitmapToEncode, format, quality, output)
            }
            return EncodeResult(encoded, bitmapToEncode.width, bitmapToEncode.height, format, quality)
        } finally {
            recycleIfNeeded(bitmapToEncode, originalBitmap)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun encodeAvif(
        originalBitmap: Bitmap,
        bitmapToEncode: Bitmap,
        quality: Int,
        enableAvifBeta: Boolean,
    ): EncodeResult {
        if (!AvifAvailability.isPlatformWriteSupported()) {
            if (enableAvifBeta && NativeAvifEncoder.isAvailable()) {
                NativeAvifEncoder.encode(bitmapToEncode, quality)?.let { encoded ->
                    val width = bitmapToEncode.width
                    val height = bitmapToEncode.height
                    recycleIfNeeded(bitmapToEncode, originalBitmap)
                    return EncodeResult(
                        bytes = encoded,
                        width = width,
                        height = height,
                        format = EncodeFormat.AVIF,
                        quality = quality,
                    )
                }
                recycleIfNeeded(bitmapToEncode, originalBitmap)
                throw EncodeError.AvifUnavailable("Native AVIF encode failed")
            }
            recycleIfNeeded(bitmapToEncode, originalBitmap)
            throw EncodeError.AvifUnavailable("No AVIF encoder available on this device")
        }

        try {
            val outputFile = createAvifTempFile()
            var writer: AvifWriter? = null
            try {
                writer = AvifWriter.Builder(
                    outputFile.absolutePath,
                    bitmapToEncode.width,
                    bitmapToEncode.height,
                    AvifWriter.INPUT_MODE_BITMAP,
                ).setQuality(quality).build()
                writer.start()
                writer.addBitmap(bitmapToEncode)
                writer.stop(AVIF_STOP_TIMEOUT_MS)
                writer.close()
                writer = null

                return EncodeResult(
                    bytes = outputFile.readBytes(),
                    width = bitmapToEncode.width,
                    height = bitmapToEncode.height,
                    format = EncodeFormat.AVIF,
                    quality = quality,
                )
            } finally {
                runCatching { writer?.close() }
                runCatching { outputFile.delete() }
            }
        } finally {
            recycleIfNeeded(bitmapToEncode, originalBitmap)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun encodeHeif(originalBitmap: Bitmap, bitmapToEncode: Bitmap, quality: Int): EncodeResult {
        if (!HeifAvailability.isWriteSupported()) {
            if (bitmapToEncode !== originalBitmap) {
                bitmapToEncode.recycle()
            }
            throw EncodeError.HeifUnavailable()
        }

        try {
            val outputFile = createHeifTempFile()
            var writer: HeifWriter? = null
            try {
                writer = HeifWriter.Builder(
                    outputFile.absolutePath,
                    bitmapToEncode.width,
                    bitmapToEncode.height,
                    HeifWriter.INPUT_MODE_BITMAP,
                ).setQuality(quality).build()
                writer.start()
                writer.addBitmap(bitmapToEncode)
                writer.stop(HEIF_STOP_TIMEOUT_MS)
                writer.close()
                writer = null

                return EncodeResult(
                    bytes = outputFile.readBytes(),
                    width = bitmapToEncode.width,
                    height = bitmapToEncode.height,
                    format = EncodeFormat.HEIF,
                    quality = quality,
                )
            } finally {
                runCatching { writer?.close() }
                runCatching { outputFile.delete() }
            }
        } finally {
            recycleIfNeeded(bitmapToEncode, originalBitmap)
        }
    }

    private fun Bitmap.prepareForEncoding(format: EncodeFormat, alphaPolicy: AlphaPolicy): Bitmap {
        val enforcesAlphaPolicy = format == EncodeFormat.JPEG ||
            format == EncodeFormat.HEIF ||
            format == EncodeFormat.AVIF
        if (!hasAlpha() || !enforcesAlphaPolicy) {
            return this
        }

        val alphaFormatName = when (format) {
            EncodeFormat.JPEG -> "JPEG"
            EncodeFormat.HEIF -> "HEIF"
            EncodeFormat.AVIF -> "AVIF"
            EncodeFormat.PNG,
            EncodeFormat.WEBP_LOSSY,
            EncodeFormat.WEBP_LOSSLESS,
            -> error("Alpha policy is only enforced for JPEG, HEIF, and AVIF")
        }

        return when (alphaPolicy) {
            AlphaPolicy.Error -> throw EncodeError.AlphaConflict(format)
            is AlphaPolicy.FillBackground -> flattenAlpha(alphaPolicy.argb)
            AlphaPolicy.Allow -> if (format == EncodeFormat.AVIF) {
                this
            } else {
                throw EncodeError.Invalid(
                    "alpha not allowed for $alphaFormatName; choose FillBackground or different format",
                )
            }
        }
    }

    private fun Bitmap.flattenAlpha(backgroundArgb: Int): Bitmap {
        val flattened = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(flattened).run {
            drawColor(backgroundArgb)
            drawBitmap(this@flattenAlpha, 0f, 0f, null)
        }
        flattened.setHasAlpha(false)
        return flattened
    }

    private fun recycleIfNeeded(bitmap: Bitmap, originalBitmap: Bitmap) {
        if (bitmap !== originalBitmap) {
            bitmap.recycle()
        }
    }

    private fun EncodeFormat.isLossless(): Boolean =
        this == EncodeFormat.PNG || this == EncodeFormat.WEBP_LOSSLESS

    @Suppress("DEPRECATION")
    private fun EncodeFormat.toCompressFormat(): Bitmap.CompressFormat = when (this) {
        EncodeFormat.JPEG -> Bitmap.CompressFormat.JPEG
        EncodeFormat.PNG -> Bitmap.CompressFormat.PNG
        EncodeFormat.WEBP_LOSSY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        EncodeFormat.WEBP_LOSSLESS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            Bitmap.CompressFormat.WEBP
        }
        EncodeFormat.HEIF -> throw EncodeError.Invalid("HEIF requires HeifWriter")
        EncodeFormat.AVIF -> throw EncodeError.Invalid("AVIF requires AvifWriter or native libavif")
    }

    private fun createHeifTempFile(): File =
        Files.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX).toFile()

    private fun createAvifTempFile(): File =
        Files.createTempFile(AVIF_TEMP_FILE_PREFIX, AVIF_TEMP_FILE_SUFFIX).toFile()

    private fun encodeWithPlatform(
        bitmap: Bitmap,
        format: EncodeFormat,
        quality: Int,
        output: ByteArrayOutputStream,
    ): ByteArray {
        val success = bitmap.compress(format.toCompressFormat(), quality, output)
        if (!success) {
            throw IOException("Bitmap.compress returned false")
        }
        return output.toByteArray()
    }

    private companion object {
        private const val MIN_QUALITY = 1
        private const val MAX_QUALITY = 100
        private const val HEIF_STOP_TIMEOUT_MS = 10_000L
        private const val AVIF_STOP_TIMEOUT_MS = 10_000L
        private const val TEMP_FILE_PREFIX = "heif-encode-"
        private const val TEMP_FILE_SUFFIX = ".heic"
        private const val AVIF_TEMP_FILE_PREFIX = "avif-encode-"
        private const val AVIF_TEMP_FILE_SUFFIX = ".avif"
    }
}
