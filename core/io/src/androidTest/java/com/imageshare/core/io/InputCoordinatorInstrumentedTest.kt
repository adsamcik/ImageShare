package com.imageshare.core.io

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputCoordinatorInstrumentedTest {
    /**
     * Regression test for the production bug where InputCoordinator.readDimensions
     * conflated "openInputStream returned null" with "decodeStream returned null Bitmap".
     * `BitmapFactory.decodeStream(..., inJustDecodeBounds = true)` always returns null
     * (it only mutates options.outWidth/outHeight). The pre-fix code used
     * `openInputStream(uri)?.use { decodeStream(...) } ?: return null`, where the Elvis
     * matched on the use-block's null Bitmap return and caused every picked image to
     * silently lose its dimensions. This test runs against real platform BitmapFactory
     * (not Robolectric) which faithfully reproduces the bounds-decode behavior.
     */
    @Test
    fun resolveGeneratedJpegContentUriPopulatesFields() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.parse("content://com.imageshare.core.io.test.generated/image.jpg")

        val item = InputCoordinator(context.contentResolver).resolve(uri)

        assertEquals(uri, item.uri)
        assertEquals("image/jpeg", item.mimeType)
        assertEquals("generated-input.jpg", item.displayName)
        assertEquals(GeneratedImageProvider.imageSizeBytes, item.sizeBytes)
        assertEquals(GeneratedImageProvider.IMAGE_WIDTH, item.width)
        assertEquals(GeneratedImageProvider.IMAGE_HEIGHT, item.height)
    }
}
