@file:Suppress("TooManyFunctions", "ReturnCount")

package com.imageshare.core.processing

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.BufferedInputStream
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
        probeSource(uri).toMetadata()
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

        val probe = probeSource(uri)
        val sampleSize = calculateInSampleSize(probe.width, probe.height, targetLongEdgePx)
        val source = ImageDecoder.createSource(resolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.setTargetSampleSize(sampleSize)
            if (preferRgb565) {
                decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
            }
        }
        val scaled = scaleToTarget(decoded, targetLongEdgePx)
        val sourceDimensions = probe.logicalDimensions()

        DecodedImage(
            bitmap = scaled,
            sourceWidth = sourceDimensions.width,
            sourceHeight = sourceDimensions.height,
            hadAlpha = probe.hasAlpha,
        )
    }

    private fun probeSource(uri: Uri): SourceProbe {
        val stream = resolver.openInputStream(uri) ?: missingBounds(uri)
        return stream.use { input ->
            val buffered = input.bufferedForProbe()
            buffered.mark(PROBE_MARK_LIMIT_BYTES)

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(buffered, null, options)
            val bounds = options.toImageBounds()

            val headerAlpha = readHasAlphaHint(uri, buffered, bounds.mimeType)
            val orientation = readOrientation(uri, buffered)
            SourceProbe(
                width = bounds.width,
                height = bounds.height,
                mimeType = bounds.mimeType,
                orientation = orientation,
                hasAlpha = bounds.mimeType in AlphaCapableMimeTypes && (bounds.hasAlphaHint || headerAlpha),
            )
        }
    }

    private fun missingBounds(uri: Uri): Nothing {
        throw DecodeError.IoError(FileNotFoundException(uri.toString()))
    }

    private fun readOrientation(uri: Uri, buffered: BufferedInputStream): Int {
        val stream = if (buffered.resetForProbe()) {
            buffered
        } else {
            // Some ContentResolver streams cannot honor mark/reset after BitmapFactory probing.
            // Fall back to one extra metadata stream rather than failing the decode.
            resolver.openInputStream(uri) ?: return ExifInterface.ORIENTATION_NORMAL
        }

        return try {
            stream.useIfFallback(buffered) {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        } catch (_: IllegalArgumentException) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun readHasAlphaHint(uri: Uri, buffered: BufferedInputStream, mimeType: String?): Boolean {
        if (!shouldProbeAlphaHeader(mimeType)) {
            return false
        }

        val stream = if (buffered.resetForProbe()) {
            buffered
        } else {
            // Mark/reset is not guaranteed for all resolver streams; reopen only for this fallback.
            resolver.openInputStream(uri) ?: return false
        }

        return try {
            stream.useIfFallback(buffered) {
                val header = ByteArray(PngHeaderSize)
                val bytesRead = it.read(header)
                bytesRead >= PngHeaderSize &&
                    header.isPngHeader() &&
                    header[PngColorTypeOffset].toInt() in AlphaPngColorTypes
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun SourceProbe.toMetadata(): SourceMetadata = SourceMetadata(
        width = width,
        height = height,
        mimeType = mimeType,
        orientation = orientation,
        hasAlpha = hasAlpha,
    )

    private fun java.io.InputStream.bufferedForProbe(): BufferedInputStream =
        this as? BufferedInputStream ?: BufferedInputStream(this)

    private fun BufferedInputStream.resetForProbe(): Boolean = try {
        reset()
        true
    } catch (_: IOException) {
        false
    }

    private inline fun <T> java.io.InputStream.useIfFallback(
        probeStream: BufferedInputStream,
        block: (java.io.InputStream) -> T,
    ): T = if (this === probeStream) block(this) else use(block)

    private companion object {
        private const val PROBE_MARK_LIMIT_BYTES = 64 * 1024
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

private fun SourceProbe.logicalDimensions(): SourceDimensions =
    if (orientationSwapsAxes(orientation)) {
        SourceDimensions(width = height, height = width)
    } else {
        SourceDimensions(width = width, height = height)
    }

private fun orientationSwapsAxes(orientation: Int): Boolean = when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90,
    ExifInterface.ORIENTATION_ROTATE_270,
    ExifInterface.ORIENTATION_TRANSPOSE,
    ExifInterface.ORIENTATION_TRANSVERSE -> true
    else -> false
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

private val AlphaCapableMimeTypes = setOf(
    "image/png",
    "image/webp",
    "image/heif",
    "image/avif",
)

internal fun shouldProbeAlphaHeader(mimeType: String?): Boolean =
    mimeType in AlphaCapableMimeTypes && mimeType != "image/png"

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

private data class SourceProbe(
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val orientation: Int,
    val hasAlpha: Boolean,
)

private data class ImageBounds(
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val hasAlphaHint: Boolean,
)

private data class SourceDimensions(
    val width: Int,
    val height: Int,
)
