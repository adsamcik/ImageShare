package com.imageshare.core.processing

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DecoderMathTest {
    @Test
    fun calculateInSampleSizeUsesLargestPowerOfTwoThatStillMeetsTarget() {
        val cases = listOf(
            SampleCase(sourceWidth = 5000, sourceHeight = 3000, target = 2048, expected = 2),
            SampleCase(sourceWidth = 8000, sourceHeight = 6000, target = 1024, expected = 4),
            SampleCase(sourceWidth = 2048, sourceHeight = 1229, target = 2048, expected = 1),
            SampleCase(sourceWidth = 4096, sourceHeight = 4096, target = 2048, expected = 2),
            SampleCase(sourceWidth = 4095, sourceHeight = 2000, target = 2048, expected = 1),
            SampleCase(sourceWidth = 1000, sourceHeight = 500, target = 2048, expected = 1),
        )

        cases.forEach { case ->
            assertEquals(
                case.toString(),
                case.expected,
                calculateInSampleSize(case.sourceWidth, case.sourceHeight, case.target),
            )
        }
    }

    @Test
    fun alphaHeaderProbeOnlyRunsForNonPngAlphaCapableFormats() {
        mapOf(
            "image/png" to false,
            "image/jpeg" to false,
            "image/webp" to true,
            "image/heif" to true,
            "image/avif" to true,
        ).forEach { (mimeType, expected) ->
            assertEquals("mimeType=$mimeType", expected, shouldProbeAlphaHeader(mimeType))
        }
    }

    @Test
    fun orientationMatrixMatchesExifConstants() {
        mapOf(
            ExifInterface.ORIENTATION_UNDEFINED to floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            ExifInterface.ORIENTATION_NORMAL to floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            ExifInterface.ORIENTATION_ROTATE_180 to floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f),
            ExifInterface.ORIENTATION_TRANSPOSE to floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            ExifInterface.ORIENTATION_ROTATE_90 to floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            ExifInterface.ORIENTATION_TRANSVERSE to floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f),
            ExifInterface.ORIENTATION_ROTATE_270 to floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f),
        ).forEach { (orientation, expected) ->
            val actual = FloatArray(9)
            orientationMatrixFor(orientation).getValues(actual)
            assertArrayEquals("orientation=$orientation", expected, actual, MatrixTolerance)
        }
    }

    private data class SampleCase(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val target: Int,
        val expected: Int,
    )

    private companion object {
        private const val MatrixTolerance = 0.0001f
    }
}
