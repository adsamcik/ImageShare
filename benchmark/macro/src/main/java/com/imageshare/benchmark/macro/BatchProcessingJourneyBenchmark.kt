@file:OptIn(androidx.benchmark.macro.ExperimentalMetricApi::class)
@file:Suppress("MagicNumber")

package com.imageshare.benchmark.macro

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
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
class BatchProcessingJourneyBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun shareIntentToBatchToNotification() {
        val fixtures = FixtureSeeder.seed(InstrumentationRegistry.getInstrumentation().context)
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(
                StartupTimingMetric(),
                TraceSectionMetric("BatchProcessWorker.doWork"),
            ),
            iterations = 5,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.Partial(),
            setupBlock = { pressHome() },
        ) {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                setPackage(TARGET_PACKAGE)
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, fixtures.batchImageUris(15))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityAndWait(intent)
            device.wait(Until.findObject(By.res("process-share-button")), UI_TIMEOUT_MS)?.click()
            device.wait(Until.hasObject(By.text("Processing images")), UI_TIMEOUT_MS)
        }
    }
}
