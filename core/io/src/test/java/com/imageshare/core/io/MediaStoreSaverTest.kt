package com.imageshare.core.io

import android.content.ContentUris
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
class MediaStoreSaverTest {
    private val context = RuntimeEnvironment.getApplication()
    private val resolver = context.contentResolver
    private val saver = MediaStoreSaver(resolver)

    @Test
    fun saveInsertsExpectedMediaStoreRow() = runBlocking {
        val source = sourceFile("expected", byteArrayOf(1, 2, 3))
        registerMediaOutput(100)

        val saved = saver.save(source, "photo.jpg", "image/jpeg")

        assertNotNull(saved.mediaStoreUri)
        val insertValues = shadowOf(resolver).getInsertStatements().last().contentValues
        assertEquals("photo.jpg", insertValues.getAsString(MediaStore.Images.Media.DISPLAY_NAME))
        assertEquals("image/jpeg", insertValues.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals("Pictures/ImageShare", insertValues.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals(1, insertValues.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        val updateValues = shadowOf(resolver).getUpdateStatements().last().contentValues
        assertEquals(0, updateValues.getAsInteger(MediaStore.Images.Media.IS_PENDING))
    }

    @Test
    fun saveSanitizesUnsafeDisplayName() = runBlocking {
        val source = sourceFile("sanitize", byteArrayOf(4, 5, 6))
        registerMediaOutput(101)

        val saved = saver.save(source, "../shady/name.png", "image/png")

        assertFalse(saved.displayName.contains(".."))
        assertFalse(saved.displayName.contains("/"))
        assertFalse(saved.displayName.contains("\\"))
        val insertValues = shadowOf(resolver).getInsertStatements().last().contentValues
        assertEquals(saved.displayName, insertValues.getAsString(MediaStore.Images.Media.DISPLAY_NAME))
    }

    @Test
    fun saveAllCollectsPartialFailures() = runBlocking {
        val good = sourceFile("good", byteArrayOf(7))
        val missing = File(context.cacheDir, "missing-${System.nanoTime()}.jpg")
        registerMediaOutputs(102, 2)

        val result = saver.saveAll(
            listOf(
                Triple(good, "good.jpg", "image/jpeg"),
                Triple(missing, "missing.jpg", "image/jpeg"),
            ),
        )

        assertEquals(1, result.succeeded.size)
        assertEquals(1, result.failed.size)
        assertEquals(good, result.succeeded.single().sourceFile)
        assertEquals(missing, result.failed.single().first)
    }

    @Test
    fun saveDeletesOrphanRowOnCopyFailure() = runBlocking {
        val source = sourceFile("copy-failure", byteArrayOf(8, 9))
        val id = 103
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toLong())
        shadowOf(resolver).setNextDatabaseIdForInserts(id)
        shadowOf(resolver).registerOutputStream(uri, ThrowingOutputStream())

        val failure = runCatching { saver.save(source, "broken.jpg", "image/jpeg") }.exceptionOrNull()

        assertTrue(failure is MediaStoreSaver.SaveError.CopyFailed)
        assertTrue(shadowOf(resolver).deleteStatements.isNotEmpty())
    }

    private fun sourceFile(name: String, bytes: ByteArray): File =
        File(context.cacheDir, "media-store-$name-${System.nanoTime()}.jpg").apply {
            writeBytes(bytes)
        }

    private fun registerMediaOutput(id: Int) {
        registerMediaOutputs(id, 10)
    }

    private fun registerMediaOutputs(startId: Int, count: Int) {
        val shadowResolver = shadowOf(resolver)
        shadowResolver.setNextDatabaseIdForInserts(startId)
        repeat(count) { offset ->
            shadowResolver.registerOutputStream(
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, (startId + offset).toLong()),
                ByteArrayOutputStream(),
            )
        }
    }

    private class ThrowingOutputStream : OutputStream() {
        override fun write(b: Int) {
            throw IOException("copy failed")
        }
    }
}
