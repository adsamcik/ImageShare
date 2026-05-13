package com.imageshare.core.io

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SafLauncherTest {
    @Test
    fun rememberOpenDocumentLauncherCanBeRemembered() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        activity.setContent {
            rememberOpenDocumentLauncher(onResult = {})
        }
    }
}
