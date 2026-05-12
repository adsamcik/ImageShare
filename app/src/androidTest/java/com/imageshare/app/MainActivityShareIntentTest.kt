package com.imageshare.app

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
    fun actionSendDisplaysStagedContent() {
        val appCacheDir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        File(appCacheDir, "shared-intake").deleteRecursively()
        val siblingCacheDir = File(appCacheDir, "image_cache").apply {
            deleteRecursively()
            mkdirs()
            File(this, "coil-entry").writeText("keep")
            setLastModified(System.currentTimeMillis() - 48L * 60L * 60L * 1_000L)
        }
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val imageFile = File(testContext.cacheDir, "shared-fixture.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val uri = FileProvider.getUriForFile(
            testContext,
            "com.imageshare.app.test.fileprovider",
            imageFile,
        )
        val intent = Intent(Intent.ACTION_SEND)
            .setClassName("com.imageshare.app", "com.imageshare.app.MainActivity")
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        ActivityScenario.launch<MainActivity>(intent).use {
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("shared-fixture.jpg")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeRule.onNodeWithText("shared-fixture.jpg").assertIsDisplayed()
            composeRule.onNodeWithText("shared-intake", substring = true).assertIsDisplayed()
            assertTrue("unrelated app cache directory must not be swept", siblingCacheDir.exists())
        }
    }

    @Test
    fun recreateDoesNotReprocessInitialShareIntent() {
        val appCacheDir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        val sharedIntakeDir = File(appCacheDir, "shared-intake").apply {
            deleteRecursively()
            mkdirs()
        }
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val imageFile = File(testContext.cacheDir, "shared-recreate-fixture.jpg").apply {
            writeBytes(byteArrayOf(5, 6, 7, 8))
        }
        val uri = FileProvider.getUriForFile(
            testContext,
            "com.imageshare.app.test.fileprovider",
            imageFile,
        )
        val intent = Intent(Intent.ACTION_SEND)
            .setClassName("com.imageshare.app", "com.imageshare.app.MainActivity")
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

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
}
