package com.imageshare.app.saving

import android.content.Intent
import android.content.ContentUris
import android.provider.MediaStore
import com.imageshare.core.io.MediaStoreSaver
import com.imageshare.core.io.OutputStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PersistentSaverTest {
    private val context = RuntimeEnvironment.getApplication()
    private val saver = PersistentSaver(MediaStoreSaver(context.contentResolver), context.contentResolver)

    @Test(expected = IllegalArgumentException::class)
    fun planSaveThrowsForEmptyResults() = runTest {
        saver.planSave(emptyList())
    }

    @Test
    fun planSaveSingleBuildsCreateDocumentIntent() = runTest {
        val item = storedItem("one.jpg", "image/jpeg")

        val outcome = saver.planSave(listOf(item)) as PersistentSaver.Outcome.SingleStarted

        assertEquals(item.file, outcome.pendingFile)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, outcome.intent.action)
        assertTrue(outcome.intent.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
        assertEquals("image/jpeg", outcome.intent.type)
        assertEquals("one.jpg", outcome.intent.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(outcome.intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue(outcome.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun planSaveBatchWritesViaMediaStore() = runTest {
        val first = storedItem("a.jpg", "image/jpeg")
        val second = storedItem("b.jpg", "image/jpeg")
        registerMediaOutputs(200, 10)

        val outcome = saver.planSave(listOf(first, second)) as PersistentSaver.Outcome.BatchCompleted

        assertEquals(2, outcome.result.succeeded.size)
        assertTrue(outcome.result.failed.isEmpty())
    }

    private fun storedItem(filename: String, mimeType: String): OutputStore.StoredItem {
        val file = File(context.cacheDir, "persistent-$filename-${System.nanoTime()}").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        return OutputStore.StoredItem("job", file, filename, file.length(), mimeType)
    }

    private fun registerMediaOutputs(startId: Int, count: Int) {
        val shadowResolver = shadowOf(context.contentResolver)
        shadowResolver.setNextDatabaseIdForInserts(startId)
        repeat(count) { offset ->
            shadowResolver.registerOutputStream(
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, (startId + offset).toLong()),
                ByteArrayOutputStream(),
            )
        }
    }
}
