@file:OptIn(androidx.benchmark.macro.ExperimentalMetricApi::class)
@file:Suppress("MagicNumber")

package com.imageshare.benchmark.macro

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

class ShareIntentToSharesheetBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun intentToChooser() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            TraceSectionMetric("ImageShareShareIntentToChooser"),
            FrameTimingMetric(),
        ),
        compilationMode = CompilationMode.DEFAULT,
        iterations = 3,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait(shareIntent())
        device.wait(Until.hasObject(By.desc("Process and share")), UI_TIMEOUT_MS)
        device.findObject(By.desc("Process and share"))?.click()
        device.wait(Until.hasObject(By.textContains("Share")), UI_TIMEOUT_MS)
    }

    private fun shareIntent(): Intent = Intent(Intent.ACTION_SEND).apply {
        setPackage(TARGET_PACKAGE)
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, Uri.parse("content://com.imageshare.benchmark.macro.fixture/image.jpg"))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

