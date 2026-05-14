@file:Suppress("MagicNumber")

package com.imageshare.benchmark.macro

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

class PickerToPreviewBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun tapToPicker() {
        val pickerPackage = PhotoPickerPackageResolver.requirePhotoPickerPackage(
            InstrumentationRegistry.getInstrumentation().context,
        )
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.DEFAULT,
            iterations = 3,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            val pickButton = device.wait(Until.findObject(By.desc("Pick from gallery")), UI_TIMEOUT_MS)
            pickButton?.click()
            device.wait(Until.hasObject(By.pkg(pickerPackage)), UI_TIMEOUT_MS)
            device.pressBack()
        }
    }
}
