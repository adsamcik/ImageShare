package com.imageshare.core.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeifEncoderInstrumentedTest {
    private val encoder = Encoder()

    @After
    fun tearDown() {
        HeifAvailability.resetCache()
    }

    @Test
    fun heifAvailabilityProbeFindsDeviceEncoder() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)

        assertTrue(HeifAvailability.isWriteSupported())
    }

    @Test
    fun heifEncodeWritesHeifContainerAndRoundTripsDimensions() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        assumeTrue(HeifAvailability.isWriteSupported())

        val result = encoder.encode(
            photoLikeBitmap(1024, 768),
            EncodeFormat.HEIF,
            80,
            AlphaPolicy.FillBackground(Color.WHITE),
        )

        assertEquals(EncodeFormat.HEIF, result.format)
        assertEquals(80, result.quality)
        assertTrue(result.bytes.hasHeifBrand())

        val decoded = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
        assertEquals(1024, decoded.width)
        assertEquals(768, decoded.height)
        decoded.recycle()
    }

    @Test
    fun heifWithAlphaAndErrorPolicyThrowsAlphaConflict() = runBlocking {
        val thrown = runCatching {
            encoder.encode(alphaBitmap(), EncodeFormat.HEIF, 80, AlphaPolicy.Error)
        }.exceptionOrNull()

        assertEquals(EncodeError.AlphaConflict(EncodeFormat.HEIF), thrown)
    }

    @Test
    fun heifUnavailableThrowsTypedError() = runBlocking {
        setCachedHeifSupport(false)

        val thrown = runCatching {
            encoder.encode(photoLikeBitmap(32, 32), EncodeFormat.HEIF, 80, AlphaPolicy.FillBackground(Color.WHITE))
        }.exceptionOrNull()

        assertTrue(thrown is EncodeError.HeifUnavailable)
    }

    private fun setCachedHeifSupport(supported: Boolean?) {
        val field = HeifAvailability::class.java.getDeclaredField("cachedSupport")
        field.isAccessible = true
        field.set(HeifAvailability, supported)
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

    private fun ByteArray.hasHeifBrand(): Boolean =
        size >= HEIF_BRAND_OFFSET + HEIF_BRAND_SIZE &&
            String(
                copyOfRange(HEIF_BRAND_OFFSET, HEIF_BRAND_OFFSET + HEIF_BRAND_SIZE),
                Charsets.US_ASCII,
            ) in HEIF_BRANDS

    private companion object {
        private const val HEIF_BRAND_OFFSET = 4
        private const val HEIF_BRAND_SIZE = 8
        private val HEIF_BRANDS = setOf("ftypheic", "ftypmif1")
    }
}
