package com.imageshare.app.ui

import android.content.ComponentName
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.imageshare.app.sharing.SharingTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SmartShareChooserTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsRecentTargetsAndAlphabeticalAppsAndSelectsTopTarget() {
        val alpha = component("alpha")
        val beta = component("beta")
        val gamma = component("gamma")
        val delta = component("delta")
        var selected: ComponentName? = null

        composeRule.setContent {
            SmartShareChooser(
                topTargets = listOf(
                    SharingTarget(beta, 12, 4L),
                    SharingTarget(gamma, 5, 3L),
                    SharingTarget(alpha, 2, 2L),
                    SharingTarget(delta, 1, 1L),
                ),
                allTargets = listOf(
                    ResolveInfoEntry(gamma, "Gamma Mail", null),
                    ResolveInfoEntry(alpha, "Alpha Chat", null),
                    ResolveInfoEntry(beta, "Beta Share", null),
                    ResolveInfoEntry(delta, "Delta Notes", null),
                ),
                onTargetSelected = { selected = it },
                onMoreClicked = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Share with").assertIsDisplayed()
        composeRule.onNodeWithText("12 shares").assertIsDisplayed()
        composeRule.onNodeWithText("5 shares").assertIsDisplayed()
        composeRule.onNodeWithText("2 shares").assertIsDisplayed()
        composeRule.onNodeWithText("All apps").assertIsDisplayed()
        composeRule.onAllNodesWithText("Alpha Chat").assertCountEquals(2)
        composeRule.onAllNodesWithText("Beta Share").assertCountEquals(2)

        composeRule.onNodeWithContentDescription("Share to Beta Share, 12 previous shares").performClick()

        assertEquals(beta, selected)
    }

    @Test
    fun hidesRecentTargetsThatAreNoLongerAvailable() {
        val installed = component("installed")
        val missing = component("missing")

        composeRule.setContent {
            SmartShareChooser(
                topTargets = listOf(
                    SharingTarget(missing, 12, 4L),
                    SharingTarget(installed, 5, 3L),
                ),
                allTargets = listOf(ResolveInfoEntry(installed, "Installed Share", null)),
                onTargetSelected = {},
                onMoreClicked = {},
                onDismiss = {},
            )
        }

        composeRule.onAllNodesWithText("Installed Share").assertCountEquals(2)
        composeRule.onAllNodesWithText("com.example.missing").assertCountEquals(0)
    }

    private fun component(name: String) = ComponentName("com.example.$name", "com.example.$name.ShareActivity")
}
