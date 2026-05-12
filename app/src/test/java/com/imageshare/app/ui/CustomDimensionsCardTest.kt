package com.imageshare.app.ui

import android.net.Uri
import com.imageshare.core.io.SourceItem
import com.imageshare.feature.preset.ResizeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomDimensionsCardTest {
    @Test
    fun longEdgeAboveSourceWouldUpscale() {
        assertTrue(wouldUpscale(source(800, 600), ResizeMode.LongEdge(1200)))
    }

    @Test
    fun longEdgeBelowSourceDoesNotUpscale() {
        assertFalse(wouldUpscale(source(800, 600), ResizeMode.LongEdge(500)))
    }

    @Test
    fun unknownDimensionsDoNotBlockUpscale() {
        assertFalse(wouldUpscale(source(null, null), ResizeMode.LongEdge(1200)))
    }

    @Test
    fun aspectLockRecomputesHeightFromWidth() {
        assertEquals(200, lockedHeightForWidth(400, sourceWidth = 1000, sourceHeight = 500))
    }

    @Test
    fun exactRejectsTooSmallDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            ResizeMode.Exact(15, 15)
        }
    }

    @Test
    fun percentageRejectsTooSmallValue() {
        assertThrows(IllegalArgumentException::class.java) {
            ResizeMode.Percentage(5)
        }
    }

    private fun source(width: Int?, height: Int?) = SourceItem(
        uri = Uri.parse("content://images/test"),
        mimeType = "image/jpeg",
        displayName = "test.jpg",
        sizeBytes = 10L,
        width = width,
        height = height,
    )
}
