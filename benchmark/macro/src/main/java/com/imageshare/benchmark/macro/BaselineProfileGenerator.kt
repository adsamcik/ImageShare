package com.imageshare.benchmark.macro

import android.os.Build
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        val pickerPackage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PhotoPickerPackageResolver.requirePhotoPickerPackage(
                InstrumentationRegistry.getInstrumentation().context,
            )
        } else {
            null
        }
        rule.collect(
            packageName = "com.imageshare.app",
            includeInStartupProfile = true,
            stableIterations = 3,
        ) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()
            if (pickerPackage != null) {
                device.wait(Until.findObject(By.desc("Pick from gallery")), UI_TIMEOUT_MS)?.click()
                device.wait(Until.hasObject(By.pkg(pickerPackage)), UI_TIMEOUT_MS)
                device.pressBack()
            }
            // Preset-tap coverage is deferred until the app exposes a test-only source-preload path.
        }
    }
}
