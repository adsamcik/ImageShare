package com.imageshare.core.io

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhotoPickerLauncherTest {
    @Test
    fun rememberPhotoPickerLauncherRejectsSingleItemMultiPickLimit() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        assertThrows(IllegalArgumentException::class.java) {
            activity.setContent {
                rememberPhotoPickerLauncher(maxItems = 1, onResult = {})
            }
        }
    }
}
