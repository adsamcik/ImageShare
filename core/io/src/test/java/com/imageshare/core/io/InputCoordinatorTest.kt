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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
class InputCoordinatorTest {
    @Test
    fun sourceItemUsesDataClassEquality() {
        val uri = Uri.parse("content://example/image.jpg")

        assertEquals(
            SourceItem(uri, "image/jpeg", "image.jpg", 123L, 10, 20),
            SourceItem(uri, "image/jpeg", "image.jpg", 123L, 10, 20),
        )
    }

    @Test
    fun resolveReturnsKnownMetadata() = runBlocking {
        val uri = registerProvider(
            authority = "metadata",
            provider = TestContentProvider(
                displayName = "picked.jpg",
                sizeBytes = 42L,
                mimeType = "image/jpeg",
                openFailure = FileNotFoundException("No image bytes"),
            ),
        )

        val item = InputCoordinator(RuntimeEnvironment.getApplication().contentResolver).resolve(uri)

        assertEquals(uri, item.uri)
        assertEquals("image/jpeg", item.mimeType)
        assertEquals("picked.jpg", item.displayName)
        assertEquals(42L, item.sizeBytes)
        assertNull(item.width)
        assertNull(item.height)
    }

    @Test
    fun resolveKeepsPartialDataWhenInputStreamGrantIsLost() = runBlocking {
        val uri = registerProvider(
            authority = "partial",
            provider = TestContentProvider(
                displayName = "picked.jpg",
                sizeBytes = 42L,
                mimeType = "image/jpeg",
                openFailure = SecurityException("Grant lost"),
            ),
        )

        val item = InputCoordinator(RuntimeEnvironment.getApplication().contentResolver).resolve(uri)

        assertEquals("image/jpeg", item.mimeType)
        assertEquals("picked.jpg", item.displayName)
        assertEquals(42L, item.sizeBytes)
        assertNull(item.width)
        assertNull(item.height)
    }

    @Test
    fun resolveThrowsQueryFailedWhenNothingCanBeRead() {
        val uri = registerProvider(
            authority = "failing",
            provider = TestContentProvider(
                queryFailure = SecurityException("Grant lost"),
                typeFailure = SecurityException("Grant lost"),
                openFailure = SecurityException("Grant lost"),
            ),
        )

        val error = assertThrows(IntakeError.QueryFailed::class.java) {
            runBlocking {
                InputCoordinator(RuntimeEnvironment.getApplication().contentResolver).resolve(uri)
            }
        }

        assertEquals(uri, error.uri)
        assertEquals(IntakeError.GrantLost(uri), error.cause)
    }

    private fun registerProvider(authority: String, provider: ContentProvider): Uri {
        ShadowContentResolver.registerProviderInternal(authority, provider)
        return Uri.parse("content://$authority/image")
    }
}

private class TestContentProvider(
    private val displayName: String? = null,
    private val sizeBytes: Long? = null,
    private val mimeType: String? = null,
    private val queryFailure: RuntimeException? = null,
    private val typeFailure: RuntimeException? = null,
    private val openFailure: Exception? = null,
) : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        queryFailure?.let { throw it }
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf(displayName, sizeBytes))
        }
    }

    override fun getType(uri: Uri): String? {
        typeFailure?.let { throw it }
        return mimeType
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        openFailure?.let { throw it }
        throw FileNotFoundException("No file for $uri")
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
