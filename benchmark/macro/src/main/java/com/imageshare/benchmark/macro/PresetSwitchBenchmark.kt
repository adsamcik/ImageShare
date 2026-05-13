@file:Suppress("MagicNumber")

package com.imageshare.benchmark.macro

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

class PresetSwitchBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun tapToSelected() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        iterations = 3,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        PRESET_DESCRIPTIONS.forEach { description ->
            device.wait(Until.findObject(By.desc(description)), UI_TIMEOUT_MS)?.click()
            device.waitForIdle()
        }
    }
}
