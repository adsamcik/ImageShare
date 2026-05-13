package com.imageshare.core.processing

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.heifwriter.HeifWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Encoder {
    suspend fun encode(
        bitmap: Bitmap,
        format: EncodeFormat,
        quality: Int,
        alphaPolicy: AlphaPolicy,
    ): EncodeResult = withContext(Dispatchers.IO) {
        if (quality !in MIN_QUALITY..MAX_QUALITY) {
            throw EncodeError.Invalid("quality out of range")
        }

        try {
            encodeBlocking(bitmap, format, quality, alphaPolicy)
        } catch (error: IOException) {
            throw EncodeError.IoError(error)
        }
    }

    private fun encodeBlocking(
        bitmap: Bitmap,
        format: EncodeFormat,
        quality: Int,
        alphaPolicy: AlphaPolicy,
    ): EncodeResult {
        val bitmapToEncode = bitmap.prepareForEncoding(format, alphaPolicy)
        val effectiveQuality = if (format.isLossless()) MAX_QUALITY else quality
        if (format == EncodeFormat.HEIF) {
            return encodeHeif(bitmap, bitmapToEncode, effectiveQuality)
        }
        val output = ByteArrayOutputStream()

        try {
            val success = bitmapToEncode.compress(format.toCompressFormat(), effectiveQuality, output)
            if (!success) {
                throw IOException("Bitmap.compress returned false")
            }
            return EncodeResult(
                bytes = output.toByteArray(),
                width = bitmapToEncode.width,
                height = bitmapToEncode.height,
                format = format,
                quality = effectiveQuality,
            )
        } finally {
            if (bitmapToEncode !== bitmap) {
                bitmapToEncode.recycle()
            }
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

            return EncodeResult(
                bytes = outputFile.readBytes(),
                width = bitmapToEncode.width,
                height = bitmapToEncode.height,
                format = EncodeFormat.HEIF,
                quality = quality,
            )
        } finally {
            try {
                writer?.close()
            } finally {
                if (bitmapToEncode !== originalBitmap) {
                    bitmapToEncode.recycle()
                }
                outputFile.delete()
            }
        }
    }

    private fun Bitmap.prepareForEncoding(format: EncodeFormat, alphaPolicy: AlphaPolicy): Bitmap {
        if (!hasAlpha() || (format != EncodeFormat.JPEG && format != EncodeFormat.HEIF)) {
            return this
        }

        val alphaFormatName = when (format) {
            EncodeFormat.JPEG -> "JPEG"
            EncodeFormat.HEIF -> "HEIF"
            EncodeFormat.PNG,
            EncodeFormat.WEBP_LOSSY,
            EncodeFormat.WEBP_LOSSLESS,
            -> error("Alpha policy is only enforced for JPEG and HEIF")
        }

        return when (alphaPolicy) {
            AlphaPolicy.Error -> throw EncodeError.AlphaConflict(format)
            is AlphaPolicy.FillBackground -> flattenAlpha(alphaPolicy.argb)
            AlphaPolicy.Allow -> throw EncodeError.Invalid(
                "alpha not allowed for $alphaFormatName; choose FillBackground or different format",
            )
        }
    }

    private fun Bitmap.flattenAlpha(backgroundArgb: Int): Bitmap {
        val flattened = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                flattened.setPixel(x, y, getPixel(x, y).over(backgroundArgb))
            }
        }
        flattened.setHasAlpha(false)
        return flattened
    }

    private fun Int.over(backgroundArgb: Int): Int {
        val alpha = Color.alpha(this)
        val inverseAlpha = MAX_ALPHA - alpha
        val red = (Color.red(this) * alpha + Color.red(backgroundArgb) * inverseAlpha) / MAX_ALPHA
        val green = (Color.green(this) * alpha + Color.green(backgroundArgb) * inverseAlpha) / MAX_ALPHA
        val blue = (Color.blue(this) * alpha + Color.blue(backgroundArgb) * inverseAlpha) / MAX_ALPHA
        return Color.rgb(red, green, blue)
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
    }

    private fun createHeifTempFile(): File =
        Files.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX).toFile()

    private companion object {
        private const val MIN_QUALITY = 1
        private const val MAX_QUALITY = 100
        private const val MAX_ALPHA = 255
        private const val HEIF_STOP_TIMEOUT_MS = 0L
        private const val TEMP_FILE_PREFIX = "heif-encode-"
        private const val TEMP_FILE_SUFFIX = ".heic"
    }
}
