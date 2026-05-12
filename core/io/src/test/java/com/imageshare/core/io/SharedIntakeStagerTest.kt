package com.imageshare.core.io

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
class SharedIntakeStagerTest {
    @Test
    fun stageCopiesThreeFakeResolverStreams() = runBlocking {
        val bytes = listOf("one".toByteArray(), "two".toByteArray(), "three".toByteArray())
        val uris = bytes.mapIndexed { index, data ->
            registerProvider("stage$index", StagingProvider(bytes = data, displayName = "image$index.jpg"))
        }
        val stager = stager()

        val staged = stager.stage("job-copy", uris)

        assertEquals(3, staged.size)
        staged.forEachIndexed { index, item ->
            assertEquals(bytes[index].toList(), item.cachedFile.readBytes().toList())
            assertEquals(Uri.fromFile(item.cachedFile), item.cachedUri)
        }
    }

    @Test
    fun stageContinuesAfterFailingResolver() = runBlocking {
        val goodOne = registerProvider("good-one", StagingProvider(bytes = byteArrayOf(1)))
        val failing = registerProvider("bad-one", StagingProvider(openFailure = FileNotFoundException("missing")))
        val goodTwo = registerProvider("good-two", StagingProvider(bytes = byteArrayOf(2)))

        val staged = stager().stage("job-partial", listOf(goodOne, failing, goodTwo))

        assertEquals(2, staged.size)
        assertEquals(byteArrayOf(1).toList(), staged[0].cachedFile.readBytes().toList())
        assertEquals(byteArrayOf(2).toList(), staged[1].cachedFile.readBytes().toList())
    }

    @Test
    fun stageSanitizesUnsafeAndFallbackNames() = runBlocking {
        val unsafe = registerProvider(
            "unsafe",
            StagingProvider(bytes = byteArrayOf(1), displayName = "../a/b\u0000c.jpg"),
        )
        val empty = registerProvider("empty", StagingProvider(bytes = byteArrayOf(2), displayName = ""))
        val missing = registerProvider("missing-name", StagingProvider(bytes = byteArrayOf(3), displayName = null))

        val staged = stager().stage("job-names", listOf(unsafe, empty, missing))

        assertEquals("0-.abc.jpg", staged[0].cachedFile.name)
        assertFalse(staged[0].cachedFile.name.contains("/"))
        assertFalse(staged[0].cachedFile.name.contains("\\"))
        assertEquals("1-image-1.bin", staged[1].cachedFile.name)
        assertEquals("2-image-2.bin", staged[2].cachedFile.name)
    }

    @Test
    fun sweepDeletesOnlyOldChildDirectories() = runBlocking {
        val cacheRoot = File(RuntimeEnvironment.getApplication().cacheDir, "sweep-test").apply { mkdirs() }
        val oldDir = File(cacheRoot, "old").apply {
            mkdirs()
            File(this, "item").writeText("old")
            setLastModified(System.currentTimeMillis() - 10_000L)
        }
        val newDir = File(cacheRoot, "new").apply {
            mkdirs()
            File(this, "item").writeText("new")
        }
        val directFile = File(cacheRoot, "file").apply { writeText("keep") }

        SharedIntakeStager(RuntimeEnvironment.getApplication().contentResolver, cacheRoot)
            .sweep(olderThanMillis = 1_000L)

        assertFalse(oldDir.exists())
        assertTrue(newDir.exists())
        assertTrue(directFile.exists())
    }

    private fun stager(): SharedIntakeStager {
        val root = File(RuntimeEnvironment.getApplication().cacheDir, "shared-stager-test").apply { mkdirs() }
        return SharedIntakeStager(RuntimeEnvironment.getApplication().contentResolver, root)
    }

    private fun registerProvider(authority: String, provider: ContentProvider): Uri {
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val uri = Uri.parse("content://$authority/image")
        if (provider is StagingProvider && provider.openFailure == null) {
            shadowOf(RuntimeEnvironment.getApplication().contentResolver)
                .registerInputStream(uri, ByteArrayInputStream(provider.bytes))
        }
        return uri
    }
}

private class StagingProvider(
    val bytes: ByteArray = byteArrayOf(),
    private val displayName: String? = "shared.jpg",
    val openFailure: Exception? = null,
) : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
        addRow(arrayOf(displayName, bytes.size.toLong()))
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        openFailure?.let { throw it }
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                output.write(bytes)
            }
        }.start()
        return readSide
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
