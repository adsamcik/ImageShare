package com.imageshare.app

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SmartShareEndToEndTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun processedShareShowsSmartChooserAndDirectTargetLaunches() {
        var launchedComponent: ComponentName? = null
        MainActivity.smartShareLaunchInterceptor = { componentName ->
            launchedComponent = componentName
            true
        }
        try {
            ActivityScenario.launch<MainActivity>(shareIntentFor("smart-share-e2e.jpg")).use {
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithText("smart-share-e2e.jpg").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithText("Small file").assertIsDisplayed()
                composeRule.onNodeWithText("Process & share").performClick()
                composeRule.waitUntil(timeoutMillis = 30_000L) {
                    composeRule.onAllNodesWithText("Share with").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithText("Share with").assertIsDisplayed()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("smart-share-all-app-row").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onAllNodes(hasTestTag("smart-share-all-app-row"))[0].performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) { launchedComponent != null }
                assertTrue("direct target component should be captured", launchedComponent != null)
            }
        } finally {
            MainActivity.smartShareLaunchInterceptor = null
        }
    }

    private fun shareIntentFor(name: String): Intent {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val imageFile = File(File(targetContext.cacheDir, "shared-output/test-fixtures"), name).apply {
            parentFile?.mkdirs()
            val bitmap = Bitmap.createBitmap(3_000, 2_000, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(Color.BLUE)
            outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output) }
            bitmap.recycle()
        }
        val uri = FileProvider.getUriForFile(
            targetContext,
            "${targetContext.packageName}.shareprovider",
            imageFile,
        )
        return Intent(Intent.ACTION_SEND)
            .setClassName("com.imageshare.app", "com.imageshare.app.MainActivity")
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
