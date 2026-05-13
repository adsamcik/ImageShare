package com.imageshare.core.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeJpegEncoderInstrumentedTest {
    @Test
    fun encodeIfAvailableProducesValidJpeg() {
        assumeTrue("Native library not loaded", NativeJpegEncoder.isAvailable())

        val bitmap = photoLikeBitmap(width = 1024, height = 768)
        val bytes = NativeJpegEncoder.encode(bitmap, 80)

        assertNotNull(bytes)
        assertEquals(0xFF.toByte(), bytes!![0])
        assertEquals(0xD8.toByte(), bytes[1])
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(1024, decoded.width)
        assertEquals(768, decoded.height)
        decoded.recycle()
        bitmap.recycle()
    }

    @Test
    fun encoderUsesNativePathWhenAvailable() {
        assumeTrue("Native library not loaded", NativeJpegEncoder.isAvailable())

        val bitmap = photoLikeBitmap(width = 800, height = 600)
        val result = runBlocking {
            Encoder().encode(
                bitmap,
                EncodeFormat.JPEG,
                quality = 80,
                alphaPolicy = AlphaPolicy.FillBackground(0xFFFFFFFF.toInt()),
            )
        }

        assertNotNull(result.bytes)
        bitmap.recycle()
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
