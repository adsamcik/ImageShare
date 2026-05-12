package com.imageshare.core.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EncoderResizerTest {
    private val encoder = Encoder()
    private val resizer = Resizer()

    @Test
    fun encodeRejectsQualityBelowRange() = runBlocking {
        val thrown = runCatching {
            encoder.encode(opaqueBitmap(10, 10), EncodeFormat.JPEG, 0, AlphaPolicy.Error)
        }.exceptionOrNull()

        assertTrue(thrown is EncodeError.Invalid)
        assertEquals("quality out of range", thrown?.message)
    }

    @Test
    fun encodeRejectsQualityAboveRange() = runBlocking {
        val thrown = runCatching {
            encoder.encode(opaqueBitmap(10, 10), EncodeFormat.JPEG, 101, AlphaPolicy.Error)
        }.exceptionOrNull()

        assertTrue(thrown is EncodeError.Invalid)
        assertEquals("quality out of range", thrown?.message)
    }

    @Test
    fun toLongEdgeShrinksLandscapeBitmap() {
        val result = resizer.toLongEdge(opaqueBitmap(800, 600), 400)

        assertEquals(400, result.width)
        assertTrue(abs(result.height - 300) <= 1)
        result.recycle()
    }

    @Test
    fun toLongEdgeDoesNotUpscale() {
        val source = opaqueBitmap(200, 100)
        val result = resizer.toLongEdge(source, 400)

        assertSame(source, result)
        assertEquals(200, result.width)
        assertEquals(100, result.height)
        result.recycle()
    }

    @Test
    fun toExactCenterCropCropsCentrally() {
        val source = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        for (x in 0 until source.width) {
            val color = when {
                x < 50 -> Color.RED
                x < 150 -> Color.GREEN
                else -> Color.BLUE
            }
            for (y in 0 until source.height) {
                source.setPixel(x, y, color)
            }
        }

        val result = resizer.toExact(source, 100, 100, Resizer.ExactMode.CenterCrop)

        assertEquals(100, result.width)
        assertEquals(100, result.height)
        assertEquals(Color.GREEN, result.getPixel(0, 50))
        assertEquals(Color.GREEN, result.getPixel(50, 50))
        assertEquals(Color.GREEN, result.getPixel(99, 50))
        source.recycle()
        result.recycle()
    }

    @Test
    fun jpegWithAlphaAndErrorPolicyThrowsAlphaConflict() = runBlocking {
        val thrown = runCatching {
            encoder.encode(alphaBitmap(), EncodeFormat.JPEG, 90, AlphaPolicy.Error)
        }.exceptionOrNull()

        assertEquals(EncodeError.AlphaConflict(EncodeFormat.JPEG), thrown)
    }

    @Test
    fun jpegWithAlphaAndFillBackgroundFlattensTransparency() = runBlocking {
        val result = encoder.encode(
            alphaBitmap(),
            EncodeFormat.JPEG,
            95,
            AlphaPolicy.FillBackground(Color.WHITE),
        )

        val decoded = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
        val corner = decoded.getPixel(0, 0)
        assertEquals(255, Color.alpha(corner))
        assertTrue(Color.red(corner) > 240)
        assertTrue(Color.green(corner) > 240)
        assertTrue(Color.blue(corner) > 240)
        decoded.recycle()
    }

    @Test
    fun pngEncodeStartsWithPngSignature() = runBlocking {
        val result = encoder.encode(opaqueBitmap(16, 16), EncodeFormat.PNG, 80, AlphaPolicy.Error)

        assertEquals(100, result.quality)
        assertTrue(result.bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE))
    }

    @Test
    fun webpLossyEncodeWritesRiffWebpContainer() = runBlocking {
        val result = encoder.encode(photoLikeBitmap(64, 64), EncodeFormat.WEBP_LOSSY, 80, AlphaPolicy.Allow)

        assertEquals("RIFF", result.bytes.copyOfRange(0, 4).toAsciiString())
        assertEquals("WEBP", result.bytes.copyOfRange(8, 12).toAsciiString())
    }

    @Test
    fun webpLosslessEncodeSucceeds() = runBlocking {
        val result = encoder.encode(photoLikeBitmap(64, 64), EncodeFormat.WEBP_LOSSLESS, 80, AlphaPolicy.Allow)

        // Robolectric may run API 29 shadows, where WEBP_LOSSLESS falls back to legacy lossy WebP at quality 100.
        assertEquals(100, result.quality)
        assertTrue(result.bytes.isNotEmpty())
    }

    @Ignore("Robolectric's shadow JPEG encoder may not model platform quality-size behaviour reliably.")
    @Test
    fun jpegQualityMonotonicity() = runBlocking {
        val bitmap = photoLikeBitmap(200, 200)
        val q20 = encoder.encode(bitmap, EncodeFormat.JPEG, 20, AlphaPolicy.Error)
        val q90 = encoder.encode(bitmap, EncodeFormat.JPEG, 90, AlphaPolicy.Error)

        assertTrue(q90.bytes.size > q20.bytes.size * MIN_HIGH_QUALITY_SIZE_RATIO)
    }

    private fun opaqueBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(80, 120, 160))
            setHasAlpha(false)
        }

    private fun alphaBitmap(): Bitmap =
        Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            for (x in 5 until width) {
                for (y in 5 until height) {
                    setPixel(x, y, Color.argb(128, 255, 0, 0))
                }
            }
        }

    private fun photoLikeBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val noise = (x * 37 + y * 91 + x * y) and 0xFF
                    setPixel(x, y, Color.rgb((x * 255 / width) xor noise, (y * 255 / height) xor noise, noise))
                }
            }
            setHasAlpha(false)
        }

    private fun ByteArray.toAsciiString(): String = String(this, Charsets.US_ASCII)

    private companion object {
        private const val MIN_HIGH_QUALITY_SIZE_RATIO = 1.10
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50.toByte(),
            0x4E.toByte(),
            0x47.toByte(),
            0x0D.toByte(),
            0x0A.toByte(),
            0x1A.toByte(),
            0x0A.toByte(),
        )
    }
}
