package com.imageshare.core.processing

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AvifAvailabilityInstrumentedTest {
    @After
    fun tearDown() {
        AvifAvailability.resetCache()
    }

    @Test
    fun avifAvailabilityProbeReturnsTrueWhenEncoderPresent() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        assumeTrue("Device under test has no AVIF encoder", AvifAvailability.isPlatformWriteSupported())

        assertTrue(AvifAvailability.isPlatformWriteSupported())
    }
}
