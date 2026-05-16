@file:Suppress("MaxLineLength", "MagicNumber")

package com.imageshare.app

import android.content.Intent
import android.content.IntentFilter
import android.app.Instrumentation.ActivityMonitor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MainActivityShareIntentTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun actionSendDisplaysStagedContentAndSharesProcessedOutput() {
        val appCacheDir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        File(appCacheDir, "shared-intake").deleteRecursively()
        File(appCacheDir, "shared-output").deleteRecursively()
        val siblingCacheDir = File(appCacheDir, "image_cache").apply {
            deleteRecursively()
            mkdirs()
            File(this, "coil-entry").writeText("keep")
            setLastModified(System.currentTimeMillis() - 48L * 60L * 60L * 1_000L)
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = shareIntentFor("shared-fixture.jpg")
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CHOOSER), null, true)

        ActivityScenario.launch<MainActivity>(intent).use {
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("shared-fixture.jpg").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("shared-fixture.jpg").assertIsDisplayed()
            composeRule.onNodeWithText("Small file").assertIsDisplayed()
            composeRule.onNodeWithText("Best quality").assertIsDisplayed()
            assertTrue("unrelated app cache directory must not be swept", siblingCacheDir.exists())

            composeRule.onNodeWithText("Process & share").performClick()
            assertChooserLaunched(instrumentation, monitor, "chooser intent should be launched")
        }
        instrumentation.removeMonitor(monitor)
    }


    @Test
    fun socialUploadPresetSharesWebpOutput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CHOOSER), null, true)

        ActivityScenario.launch<MainActivity>(shareIntentFor("webp-source.jpg")).use {
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("webp-source.jpg").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Social upload").performClick()
            composeRule.onNodeWithText("Process & share").performClick()

            assertChooserLaunched(instrumentation, monitor, "chooser intent should be launched for WebP preset")
        }
        instrumentation.removeMonitor(monitor)
    }

    @Test
    fun transparentPngConflictCanSwitchToPngAndShare() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CHOOSER), null, true)

        ActivityScenario.launch<MainActivity>(shareIntentFor("transparent-source.png", png = true)).use {
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("transparent-source.png").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Best quality").performClick()
            composeRule.onNodeWithText("Process & share").performClick()
            composeRule.waitUntil(timeoutMillis = 30_000L) {
                composeRule.onAllNodesWithText("Transparency detected").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Switch to PNG for those").performClick()

            assertChooserLaunched(
                instrumentation,
                monitor,
                "chooser intent should be launched after PNG conflict resolution",
            )
        }
        instrumentation.removeMonitor(monitor)
    }

    @Test
    fun alphaConflictUseWhiteButtonResumesBatchWithFlatWhiteJpeg() {
        val appCacheDir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        File(appCacheDir, "shared-output").deleteRecursively()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CHOOSER), null, true)

        try {
            ActivityScenario.launch<MainActivity>(shareIntentFor("alpha-use-white.png", png = true)).use {
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithText("alpha-use-white.png").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithText("Best quality").performClick()
                composeRule.onNodeWithText("Process & share").performClick()
                composeRule.waitUntil(timeoutMillis = 30_000L) {
                    composeRule.onAllNodesWithText("Transparency detected").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithText("Use white background").performClick()
                val output = waitForSharedOutputFile(appCacheDir, ".jpg")
                assertChooserLaunched(instrumentation, monitor, "chooser intent should be launched after white fallback")
                assertFlatWhiteJpeg(output)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun alphaConflictSkipButtonShowsSkippedSnackbarAndNoSuccessfulItems() {
        val appCacheDir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        File(appCacheDir, "shared-output").deleteRecursively()

        ActivityScenario.launch<MainActivity>(shareIntentFor("alpha-skip.png", png = true)).use {
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("alpha-skip.png").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Best quality").performClick()
            composeRule.onNodeWithText("Process & share").performClick()
            composeRule.waitUntil(timeoutMillis = 30_000L) {
                composeRule.onAllNodesWithText("Transparency detected").fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithText("Skip").performClick()

            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("1 image skipped.").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(sharedOutputFiles(appCacheDir).isEmpty())
        }
    }

    @Test
    fun recreateDoesNotReprocessInitialShareIntent() {
        val appCacheDir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        val sharedIntakeDir = File(appCacheDir, "shared-intake").apply {
            deleteRecursively()
            mkdirs()
        }
        val intent = shareIntentFor("shared-recreate-fixture.jpg")

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                sharedIntakeDir.listFiles()?.count { it.isDirectory } == 1
            }

            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(500L)

            assertEquals(1, sharedIntakeDir.listFiles()?.count { it.isDirectory })
        }
    }

    @Test
    fun nonUriExtraStreamDoesNotCrashOrStageContent() {
        val appCacheDir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        val sharedIntakeDir = File(appCacheDir, "shared-intake").apply { deleteRecursively() }
        val intent = Intent(Intent.ACTION_SEND)
            .setClassName("com.imageshare.app", "com.imageshare.app.MainActivity")
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, Bundle())

        ActivityScenario.launch<MainActivity>(intent).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(500L)
            assertFalse(sharedIntakeDir.exists())
        }
    }

    private fun shareIntentFor(name: String, png: Boolean = false): Intent {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val imageFile = File(File(targetContext.cacheDir, "shared-output/test-fixtures"), name).apply {
            if (png) writePngFixture(this) else writeJpegFixture(this)
        }
        val uri = FileProvider.getUriForFile(
            targetContext,
            "${targetContext.packageName}.shareprovider",
            imageFile,
        )
        return Intent(Intent.ACTION_SEND)
            .setClassName("com.imageshare.app", "com.imageshare.app.MainActivity")
            .setType(if (png) "image/png" else "image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun writeJpegFixture(file: File) {
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(3_000, 2_000, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.BLUE)
        file.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output) }
        bitmap.recycle()
    }

    private fun writePngFixture(file: File) {
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(3_000, 2_000, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        file.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
        bitmap.recycle()
    }

    private fun assertChooserLaunched(
        instrumentation: android.app.Instrumentation,
        monitor: ActivityMonitor,
        message: String,
    ) {
        instrumentation.waitForMonitorWithTimeout(monitor, 30_000L)
        assertTrue(message, monitor.hits > 0)
    }

    private fun waitForSharedOutputFile(appCacheDir: File, extension: String): File {
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            sharedOutputFiles(appCacheDir).any { it.name.endsWith(extension) }
        }
        return sharedOutputFiles(appCacheDir).first { it.name.endsWith(extension) }
    }

    private fun sharedOutputFiles(appCacheDir: File): List<File> =
        File(appCacheDir, "shared-output")
            .walkTopDown()
            .filter { it.isFile && !it.relativeTo(File(appCacheDir, "shared-output")).path.startsWith("test-fixtures") }
            .toList()

    private fun assertFlatWhiteJpeg(file: File) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        assertNotNull(bitmap)
        try {
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertTrue(Color.red(pixel) >= 245)
            assertTrue(Color.green(pixel) >= 245)
            assertTrue(Color.blue(pixel) >= 245)
        } finally {
            bitmap.recycle()
        }
    }
}
