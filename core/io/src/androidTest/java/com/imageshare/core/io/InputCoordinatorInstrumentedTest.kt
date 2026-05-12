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
    @Test
    fun resolveGeneratedJpegContentUriPopulatesFields() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.parse("content://com.imageshare.core.io.test.generated/image.jpg")

        val item = InputCoordinator(context.contentResolver).resolve(uri)

        assertEquals(uri, item.uri)
        assertEquals("image/jpeg", item.mimeType)
        assertEquals("generated-input.jpg", item.displayName)
        assertEquals(GeneratedImageProvider.imageSizeBytes, item.sizeBytes)
        assertEquals(3, item.width)
        assertEquals(2, item.height)
    }
}
