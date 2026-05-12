package com.imageshare.core.processing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TargetSizeEncoderTest {
    @Test
    fun configValidationRejectsOutOfRangeValues() {
        val invalidConfigs = listOf<() -> TargetSizeEncoder.Config>(
            { baseConfig(targetBytes = 0) },
            { baseConfig(qualityMin = 0) },
            { baseConfig(qualityMax = 101) },
            { baseConfig(qualityMin = 80, qualityMax = 80, qualityStart = 80) },
            { baseConfig(qualityMin = 30, qualityMax = 95, qualityStart = 20) },
            { baseConfig(qualityTolerance = 0) },
            { baseConfig(dimensionStepFactor = 0.49) },
            { baseConfig(dimensionStepFactor = 1.0) },
            { baseConfig(minLongEdgePx = 63) },
            { baseConfig(maxIterations = 2) },
            { baseConfig(sizeOvershootRatio = 0.99) },
        )

        invalidConfigs.forEach { invalidConfig ->
            assertThrows(IllegalArgumentException::class.java) { invalidConfig() }
        }
    }

    @Ignore("Robolectric native JPEG can overshoot this target; covered by TargetSizeEncoderInstrumentedTest.")
    @Test
    fun jpegHappyPathMeetsTargetForPhotoLikeBitmap() = runBlocking {
        val bitmap = photoLikeBitmap(2000, 1500)
        val result = TargetSizeEncoder().encodeToTarget(
            bitmap,
            TargetSizeEncoder.Config(format = EncodeFormat.JPEG, targetBytes = 200L * 1024L),
        )

        assertTrue("achieved=${result.achievedBytes}", result.metTarget)
        assertTrue(result.achievedBytes <= 200L * 1024L)
        assertTrue(result.attempts < TargetSizeEncoder.Config(EncodeFormat.JPEG, 1).maxIterations)
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test
    fun qualityBinarySearchConvergesNearOptimum() = runBlocking {
        val bitmap = solidBitmap(1000, 700)
        val targetBytes = 65_000L
        val encoder = fakeEncoder { _, _, quality, _ -> 10_000 + quality * 1_000 }

        val result = encoder.encodeToTarget(
            bitmap,
            baseConfig(targetBytes = targetBytes, qualityTolerance = 2, maxIterations = 12),
        )

        assertTrue(result.metTarget)
        assertTrue("quality=${result.qualityUsed}", abs(result.qualityUsed - 55) <= 2)
        bitmap.recycle()
    }

    @Test
    fun dimensionStepDownRunsWhenQualityCannotMeetTarget() = runBlocking {
        val bitmap = solidBitmap(1000, 750)
        val encoder = fakeEncoder { currentBitmap, _, _, _ -> max(currentBitmap.width, currentBitmap.height) * 1_000 }

        val result = encoder.encodeToTarget(
            bitmap,
            baseConfig(targetBytes = 650_000, minLongEdgePx = 320, maxIterations = 12),
        )

        assertTrue(result.width < 1000 || result.height < 750)
        assertTrue(max(result.width, result.height) >= 320)
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test
    fun tinyInputIsNeverUpscaled() = runBlocking {
        val bitmap = solidBitmap(100, 100)
        val encoder = fakeEncoder { _, _, _, _ -> 1_000 }

        val result = encoder.encodeToTarget(
            bitmap,
            baseConfig(targetBytes = 10L * 1024L * 1024L, minLongEdgePx = 320),
        )

        assertTrue(result.metTarget)
        assertEquals(100, result.width)
        assertEquals(100, result.height)
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test
    fun pngSkipsQualitySearchAndOnlyStepsDimensions() = runBlocking {
        val qualities = mutableListOf<Int>()
        val widths = mutableListOf<Int>()
        val bitmap = solidBitmap(1000, 500)
        val encoder = fakeEncoder { currentBitmap, _, quality, _ ->
            qualities += quality
            widths += currentBitmap.width
            max(currentBitmap.width, currentBitmap.height) * 100
        }

        val result = encoder.encodeToTarget(
            bitmap,
            baseConfig(format = EncodeFormat.PNG, targetBytes = 1, minLongEdgePx = 200, maxIterations = 5),
        )

        assertFalse(result.metTarget)
        assertEquals(result.attempts, qualities.size)
        assertTrue(qualities.all { it == 95 })
        assertTrue(widths.zipWithNext().all { (before, after) -> after < before })
        bitmap.recycle()
    }

    @Test
    fun webpLosslessSkipsQualitySearchAndOnlyStepsDimensions() = runBlocking {
        val qualities = mutableListOf<Int>()
        val bitmap = solidBitmap(1000, 500)
        val encoder = fakeEncoder { currentBitmap, _, quality, _ ->
            qualities += quality
            max(currentBitmap.width, currentBitmap.height) * 100
        }

        val result = encoder.encodeToTarget(
            bitmap,
            baseConfig(format = EncodeFormat.WEBP_LOSSLESS, targetBytes = 1, minLongEdgePx = 200, maxIterations = 5),
        )

        assertFalse(result.metTarget)
        assertEquals(result.attempts, qualities.size)
        assertTrue(qualities.all { it == 95 })
        bitmap.recycle()
    }

    @Test
    fun callerBitmapIsNotRecycled() = runBlocking {
        val bitmap = solidBitmap(500, 300)
        val encoder = fakeEncoder { _, _, _, _ -> 1_000 }

        encoder.encodeToTarget(bitmap, baseConfig(targetBytes = 2_000))

        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test
    fun fallbackReturnsSmallestResultWhenTargetCannotBeMet() = runBlocking {
        val bitmap = solidBitmap(1000, 500)
        val encoder = fakeEncoder { currentBitmap, _, _, _ -> max(currentBitmap.width, currentBitmap.height) * 100 }

        val result = encoder.encodeToTarget(
            bitmap,
            baseConfig(format = EncodeFormat.PNG, targetBytes = 1, minLongEdgePx = 200, maxIterations = 5),
        )

        assertFalse(result.metTarget)
        assertEquals(521, max(result.width, result.height))
        assertEquals(52_100L, result.achievedBytes)
        bitmap.recycle()
    }

    private fun fakeEncoder(
        sizeFor: (Bitmap, EncodeFormat, Int, AlphaPolicy) -> Int,
    ): TargetSizeEncoder = TargetSizeEncoder(
        encode = { bitmap, format, quality, alphaPolicy ->
            EncodeResult(
                bytes = ByteArray(sizeFor(bitmap, format, quality, alphaPolicy)),
                width = bitmap.width,
                height = bitmap.height,
                format = format,
                quality = quality,
            )
        },
    )

    @Suppress("LongParameterList")
    private fun baseConfig(
        format: EncodeFormat = EncodeFormat.JPEG,
        targetBytes: Long = 100_000,
        qualityMin: Int = 30,
        qualityMax: Int = 95,
        qualityStart: Int = 80,
        qualityTolerance: Int = 2,
        dimensionStepFactor: Double = 0.85,
        minLongEdgePx: Int = 320,
        maxIterations: Int = 12,
        sizeOvershootRatio: Double = 1.0,
    ): TargetSizeEncoder.Config = TargetSizeEncoder.Config(
        format = format,
        targetBytes = targetBytes,
        qualityMin = qualityMin,
        qualityMax = qualityMax,
        qualityStart = qualityStart,
        qualityTolerance = qualityTolerance,
        dimensionStepFactor = dimensionStepFactor,
        minLongEdgePx = minLongEdgePx,
        maxIterations = maxIterations,
        sizeOvershootRatio = sizeOvershootRatio,
    )

    private fun solidBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(80, 120, 160))
            setHasAlpha(false)
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
