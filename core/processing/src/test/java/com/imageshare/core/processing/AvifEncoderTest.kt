package com.imageshare.core.processing

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvifEncoderTest {
    private val encoder = Encoder()

    @After
    fun tearDown() {
        AvifAvailability.resetCache()
    }

    @Test
    fun avifWithAlphaAndErrorPolicyThrowsAlphaConflict() = runBlocking {
        val thrown = runCatching {
            encoder.encode(alphaBitmap(), EncodeFormat.AVIF, 80, AlphaPolicy.Error)
        }.exceptionOrNull()

        assertEquals(EncodeError.AlphaConflict(EncodeFormat.AVIF), thrown)
    }

    @Test
    fun avifUnavailableThrowsTypedError() = runBlocking {
        setCachedPlatformAvifSupport(false)

        val thrown = runCatching {
            encoder.encode(photoLikeBitmap(32, 32), EncodeFormat.AVIF, 80, AlphaPolicy.Allow)
        }.exceptionOrNull()

        assertTrue(thrown is EncodeError.AvifUnavailable)
    }

    private fun setCachedPlatformAvifSupport(supported: Boolean?) {
        val field = AvifAvailability::class.java.getDeclaredField("cachedPlatformSupport")
        field.isAccessible = true
        field.set(AvifAvailability, supported)
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
}
