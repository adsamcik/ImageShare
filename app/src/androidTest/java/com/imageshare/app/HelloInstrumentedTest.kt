package com.imageshare.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HelloInstrumentedTest {
    @Test
    fun appContextHasExpectedPackageName() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("com.imageshare.app", context.packageName)
    }
}
