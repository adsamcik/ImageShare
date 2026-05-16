package com.imageshare.app

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.hasText
import com.imageshare.app.ComparisonState
import com.imageshare.app.MainViewModel.CustomOverride
import com.imageshare.app.processing.PresetPipeline
import com.imageshare.app.ui.PresetSheet
import com.imageshare.core.io.OutputStore
import com.imageshare.core.io.RecentUriEntry
import com.imageshare.core.io.SourceItem
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.feature.preset.DefaultPresets
import com.imageshare.feature.preset.ResizeMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class PresetSheetAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sourcePresetAndCtaExposeTalkBackLabels() {
        val source = SourceItem(
            uri = Uri.parse("content://images/photo"),
            mimeType = "image/jpeg",
            displayName = "photo.jpg",
            sizeBytes = 2_458L,
            width = 64,
            height = 48,
        )

        composeRule.setContent {
            PresetSheet(
                sources = listOf(source),
                presets = DefaultPresets.ALL,
                selectedPreset = DefaultPresets.SmallFile,
                processingState = ProcessingState.Idle,
                onPresetSelected = {},
                onPickFromGallery = {},
                onProcessAndShare = {},
                onCancelBatch = {},
                onSaveCopy = {},
                onAlphaConflictStrategy = {},
            )
        }

        composeRule.onNodeWithContentDescription("photo.jpg, 64 by 48 pixels, 2.4 kilobytes").assertIsDisplayed()
        composeRule.onNode(
            hasStateDescription("selected") and hasText("Small file"),
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Process and share", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Save copy. Process images first.", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Save copy").assertIsDisplayed()
    }

    @Test
    fun customDimensionsEditEmitGuardAndReset() {
        val source = SourceItem(
            uri = Uri.parse("content://images/photo"),
            mimeType = "image/jpeg",
            displayName = "photo.jpg",
            sizeBytes = 2_458L,
            width = 1000,
            height = 500,
        )
        var latestOverride: CustomOverride? = null

        composeRule.setContent {
            var override by remember { mutableStateOf<CustomOverride?>(null) }
            PresetSheet(
                sources = listOf(source),
                presets = DefaultPresets.ALL,
                selectedPreset = DefaultPresets.SmallFile,
                customOverride = override,
                onCustomOverride = {
                    override = it
                    latestOverride = it
                },
                processingState = ProcessingState.Idle,
                onPresetSelected = {},
                onPickFromGallery = {},
                onProcessAndShare = {},
                onCancelBatch = {},
                onSaveCopy = {},
                onAlphaConflictStrategy = {},
            )
        }

        composeRule.onNodeWithTag("custom-dimensions-toggle").performClick()
        composeRule.onNode(
            hasTestTag("custom-dimensions-toggle") and hasStateDescription("expanded"),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("resize-mode-exact").performClick()
        composeRule.onNodeWithTag("aspect-lock-switch").performClick()
        composeRule.onNodeWithTag("exact-width-field").performTextClearance()
        composeRule.onNodeWithTag("exact-width-field").performTextInput("400")
        composeRule.onNodeWithTag("exact-height-field").performTextClearance()
        composeRule.onNodeWithTag("exact-height-field").performTextInput("300")
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.waitForIdle()
        assertEquals(ResizeMode.Exact(400, 300), latestOverride?.resize)

        composeRule.onNodeWithTag("aspect-lock-switch").performClick()
        composeRule.onNodeWithTag("exact-width-field").performTextClearance()
        composeRule.onNodeWithTag("exact-width-field").performTextInput("500")
        composeRule.onNodeWithTag("exact-height-field").assertTextContains("250")

        composeRule.onNodeWithTag("resize-mode-long-edge").performClick()
        composeRule.onNodeWithTag("long-edge-field").performTextClearance()
        composeRule.onNodeWithTag("long-edge-field").performTextInput("1200")
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("process-share-button").assertIsNotEnabled()
        composeRule.onNodeWithText("Some sources would be enlarged. Toggle “Allow upscaling” or reduce the size.")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithTag("reset-dimensions-button").performClick()
        composeRule.waitForIdle()
        assertNull(latestOverride)
    }

    @Test
    fun resultCardsExposeComparisonOverlayAndMergedDescription() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = SourceItem(
            uri = Uri.parse("content://images/photo"),
            mimeType = "image/jpeg",
            displayName = "photo.jpg",
            sizeBytes = 2_458L,
            width = 64,
            height = 48,
        )
        val stored = OutputStore.StoredItem(
            jobId = "job",
            file = File(context.cacheDir, "photo-result.jpg").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            },
            filename = "photo-result.jpg",
            sizeBytes = 1_024L,
            mimeType = "image/jpeg",
        )
        val result = PresetPipeline.Result.Success(stored, source, 32, 24, EncodeFormat.JPEG)

        composeRule.setContent {
            var comparison by remember { mutableStateOf<ComparisonState?>(null) }
            PresetSheet(
                sources = listOf(source),
                presets = DefaultPresets.ALL,
                selectedPreset = DefaultPresets.SmallFile,
                processingState = ProcessingState.Done(listOf(result)),
                shownComparison = comparison,
                onPresetSelected = {},
                onPickFromGallery = {},
                onProcessAndShare = {},
                onCancelBatch = {},
                onSaveCopy = {},
                onExpandResult = { comparison = ComparisonState(it.before.uri, Uri.fromFile(it.stored.file)) },
                onCloseComparison = { comparison = null },
                onAlphaConflictStrategy = {},
            )
        }

        composeRule.onAllNodesWithTag("before-after-card").assertCountEquals(1)
        composeRule.onNode(
            SemanticsMatcher("before/after card has merged spoken metrics") { node ->
                val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                descriptions.any {
                    it.contains("before", ignoreCase = true) &&
                        it.contains("kilobytes", ignoreCase = true) &&
                        it.contains("after", ignoreCase = true)
                }
            },
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("view-comparison-button", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("comparison-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("comparison-close", useUnmergedTree = true).performClick()
        composeRule.onAllNodes(hasTestTag("comparison-screen")).assertCountEquals(0)
    }

    @Test
    fun recentsRowTapRestagesSource() {
        val recentUri = Uri.parse("content://images/recent")
        val recentSource = SourceItem(
            uri = recentUri,
            mimeType = "image/jpeg",
            displayName = "recent-photo.jpg",
            sizeBytes = 4_096L,
            width = 320,
            height = 240,
        )
        var sources by mutableStateOf(emptyList<SourceItem>())

        composeRule.setContent {
            PresetSheet(
                sources = sources,
                presets = DefaultPresets.ALL,
                selectedPreset = DefaultPresets.SmallFile,
                processingState = ProcessingState.Idle,
                recentsUris = listOf(RecentUriEntry(recentUri, "recent-photo.jpg")),
                onRecentSelected = { sources = listOf(recentSource) },
                onPresetSelected = {},
                onPickFromGallery = {},
                onProcessAndShare = {},
                onCancelBatch = {},
                onSaveCopy = {},
                onAlphaConflictStrategy = {},
            )
        }

        composeRule.onNodeWithContentDescription("Open recent-photo.jpg").performClick()

        composeRule.onNodeWithText("recent-photo.jpg").assertIsDisplayed()
    }
}

private fun hasStateDescription(expected: String): SemanticsMatcher =
    SemanticsMatcher("state description is $expected") { node ->
        node.config.getOrNull(SemanticsProperties.StateDescription) == expected
    }
