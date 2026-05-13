package com.imageshare.core.processing

import android.graphics.Bitmap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativeAvifEncoderTest {
    @Test
    fun isAvailableReturnsFalseWhenLibraryNotLoadedInUnitTestJvm() {
        assertFalse(NativeAvifEncoder.isAvailable())
    }

    @Test
    fun encodeReturnsNullWhenNotAvailable() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

        assertNull(NativeAvifEncoder.encode(bitmap, 80))
        bitmap.recycle()
    }

    @Test
    fun qualityValidationRejectsOutOfRange() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

        assertTrue(runCatching { NativeAvifEncoder.encode(bitmap, 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { NativeAvifEncoder.encode(bitmap, 101) }.exceptionOrNull() is IllegalArgumentException)
        bitmap.recycle()
    }
}
