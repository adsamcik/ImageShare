package com.imageshare.core.processing

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SourceMetadata(
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val orientation: Int,
    val hasAlpha: Boolean,
)

data class DecodedImage(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val hadAlpha: Boolean,
)

sealed class DecodeError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data object UnsupportedFormat : DecodeError("Unsupported image format")
    data object Corrupt : DecodeError("Corrupt image data")
    data class IoError(override val cause: Throwable) : DecodeError("Unable to read image", cause)
}

class Decoder(private val resolver: ContentResolver) {
    fun readMetadata(uri: Uri): SourceMetadata = mapDecodeErrors {
        val bounds = readBounds(uri)
        SourceMetadata(
            width = bounds.width,
            height = bounds.height,
            mimeType = bounds.mimeType,
            orientation = readOrientation(uri),
            hasAlpha = readHasAlphaHint(uri, bounds.mimeType),
        )
    }

    suspend fun decode(
        uri: Uri,
        targetLongEdgePx: Int,
        preferRgb565: Boolean = false,
    ): DecodedImage = withContext(Dispatchers.IO) {
        decodeBlocking(uri, targetLongEdgePx, preferRgb565)
    }

    private fun decodeBlocking(
        uri: Uri,
        targetLongEdgePx: Int,
        preferRgb565: Boolean,
    ): DecodedImage = mapDecodeErrors {
        require(targetLongEdgePx > 0) { "targetLongEdgePx must be positive" }

        val bounds = readBounds(uri)
        val orientation = readOrientation(uri)
        val hadAlpha = readHasAlphaHint(uri, bounds.mimeType)
        val sampleSize = calculateInSampleSize(bounds.width, bounds.height, targetLongEdgePx)
        val source = ImageDecoder.createSource(resolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.setTargetSampleSize(sampleSize)
            if (preferRgb565) {
                decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
            }
        }
        val scaled = scaleToTarget(decoded, targetLongEdgePx)
        val oriented = applyOrientation(scaled, orientation)

        DecodedImage(
            bitmap = oriented,
            sourceWidth = bounds.width,
            sourceHeight = bounds.height,
            hadAlpha = bounds.hasAlphaHint || hadAlpha,
        )
    }

    private fun readBounds(uri: Uri): ImageBounds {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        try {
            val stream = resolver.openInputStream(uri) ?: return missingBounds(uri)
            stream.use {
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (error: IOException) {
            throw DecodeError.IoError(error)
        } catch (error: SecurityException) {
            throw DecodeError.IoError(error)
        }

        return options.toImageBounds()
    }

    private fun missingBounds(uri: Uri): ImageBounds {
        throw DecodeError.IoError(FileNotFoundException(uri.toString()))
    }

    private fun readOrientation(uri: Uri): Int = try {
        resolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    } catch (_: IllegalArgumentException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun readHasAlphaHint(uri: Uri, mimeType: String?): Boolean {
        if (mimeType != "image/png" && mimeType != "image/webp") {
            return false
        }

        return try {
            resolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(PngHeaderSize)
                val bytesRead = stream.read(header)
                when {
                    bytesRead >= PngHeaderSize && header.isPngHeader() ->
                        header[PngColorTypeOffset].toInt() in AlphaPngColorTypes
                    mimeType == "image/webp" -> false
                    else -> false
                }
            } ?: false
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private companion object {
        private const val PngHeaderSize = 26
        private const val PngColorTypeOffset = 25
        private val AlphaPngColorTypes = setOf(4, 6)
    }
}

internal fun calculateInSampleSize(sourceWidth: Int, sourceHeight: Int, targetLongEdgePx: Int): Int {
    require(sourceWidth > 0 && sourceHeight > 0) { "source dimensions must be positive" }
    require(targetLongEdgePx > 0) { "targetLongEdgePx must be positive" }

    val sourceLongEdge = max(sourceWidth, sourceHeight)
    var sampleSize = 1
    while (sourceLongEdge / (sampleSize * 2) >= targetLongEdgePx) {
        sampleSize *= 2
    }
    return sampleSize
}

internal fun orientationMatrixFor(orientation: Int): Matrix = Matrix().apply {
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(FLIP_AXIS, KEEP_AXIS)
        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(HALF_TURN_DEGREES)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            setRotate(HALF_TURN_DEGREES)
            postScale(FLIP_AXIS, KEEP_AXIS)
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            setRotate(QUARTER_TURN_DEGREES)
            postScale(FLIP_AXIS, KEEP_AXIS)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(QUARTER_TURN_DEGREES)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            setRotate(NEGATIVE_QUARTER_TURN_DEGREES)
            postScale(FLIP_AXIS, KEEP_AXIS)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(NEGATIVE_QUARTER_TURN_DEGREES)
        else -> Unit
    }
}

private fun scaleToTarget(bitmap: Bitmap, targetLongEdgePx: Int): Bitmap {
    val longEdge = max(bitmap.width, bitmap.height)
    if (longEdge <= targetLongEdgePx) {
        return bitmap
    }

    val scale = targetLongEdgePx.toFloat() / longEdge.toFloat()
    val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true).also { scaled ->
        if (scaled !== bitmap) {
            bitmap.recycle()
        }
    }
}

private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = orientationMatrixFor(orientation)
    if (matrix.isIdentity) {
        return bitmap
    }

    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { oriented ->
        if (oriented !== bitmap) {
            bitmap.recycle()
        }
    }
}

@Suppress("SwallowedException")
private inline fun <T> mapDecodeErrors(block: () -> T): T = try {
    block()
} catch (error: DecodeError) {
    throw error
} catch (error: ImageDecoder.DecodeException) {
    throw DecodeError.Corrupt
} catch (error: IOException) {
    throw DecodeError.IoError(error)
} catch (error: SecurityException) {
    throw DecodeError.IoError(error)
} catch (error: IllegalArgumentException) {
    throw DecodeError.Corrupt
}

private fun ByteArray.isPngHeader(): Boolean =
    PngSignature.indices.all { index -> this[index] == PngSignature[index] }

private fun BitmapFactory.Options.toImageBounds(): ImageBounds {
    if (outWidth <= 0 || outHeight <= 0) {
        throw DecodeError.Corrupt
    }

    return ImageBounds(
        width = outWidth,
        height = outHeight,
        mimeType = outMimeType,
        hasAlphaHint = outConfig?.let { it == Bitmap.Config.ARGB_8888 || it == Bitmap.Config.RGBA_F16 }
            ?: false,
    )
}

private const val HALF_TURN_DEGREES = 180f
private const val QUARTER_TURN_DEGREES = 90f
private const val NEGATIVE_QUARTER_TURN_DEGREES = -90f
private const val FLIP_AXIS = -1f
private const val KEEP_AXIS = 1f

private val PngSignature = byteArrayOf(
    0x89.toByte(),
    0x50.toByte(),
    0x4E.toByte(),
    0x47.toByte(),
    0x0D.toByte(),
    0x0A.toByte(),
    0x1A.toByte(),
    0x0A.toByte(),
)

private data class ImageBounds(
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val hasAlphaHint: Boolean,
)
