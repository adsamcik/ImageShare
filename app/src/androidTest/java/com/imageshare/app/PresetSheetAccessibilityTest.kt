package com.imageshare.app

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.imageshare.app.ui.PresetSheet
import com.imageshare.core.io.SourceItem
import com.imageshare.feature.preset.DefaultPresets
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
        composeRule.onNodeWithContentDescription("Preset Small file", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Process and share", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Save copy", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Save copy").assertIsDisplayed()
    }
}
