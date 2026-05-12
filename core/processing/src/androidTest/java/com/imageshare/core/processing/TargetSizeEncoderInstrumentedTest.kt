package com.imageshare.core.processing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSizeEncoderInstrumentedTest {
    private val encoder = TargetSizeEncoder()

    @Test
    fun realCodecJpegMeetsTargetForPhotoLikeBitmap() = runBlocking {
        val bitmap = photoLikeBitmap(2000, 1500)

        val result = encoder.encodeToTarget(
            bitmap,
            TargetSizeEncoder.Config(format = EncodeFormat.JPEG, targetBytes = 200_000),
        )

        assertTrue("achieved=${result.achievedBytes}", result.metTarget)
        assertTrue(result.width > 0)
        assertTrue(result.height > 0)
        assertTrue(max(result.width, result.height) <= 2000)
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test
    fun realCodecPngWithAlphaStepsDimensions() = runBlocking {
        val bitmap = alphaBitmap(800, 600)

        val result = encoder.encodeToTarget(
            bitmap,
            TargetSizeEncoder.Config(format = EncodeFormat.PNG, targetBytes = 50_000, maxIterations = 8),
        )

        assertTrue(result.width < 800 || result.height < 600)
        assertTrue(result.achievedBytes > 0)
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test
    fun stressLargePhotoTerminatesWithinIterationCap() = runBlocking {
        val bitmap = photoLikeBitmap(5000, 3000)
        val config = TargetSizeEncoder.Config(format = EncodeFormat.JPEG, targetBytes = 100_000, maxIterations = 12)

        val result = encoder.encodeToTarget(bitmap, config)

        assertTrue(result.attempts <= config.maxIterations)
        assertTrue(result.width > 0)
        assertTrue(result.height > 0)
        assertTrue(result.achievedBytes > 0)
        assertFalse(bitmap.isRecycled)
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

    private fun alphaBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val alpha = (x * 255 / width).coerceIn(0, 255)
                    setPixel(x, y, Color.argb(alpha, x * 255 / width, y * 255 / height, 180))
                }
            }
        }
}
