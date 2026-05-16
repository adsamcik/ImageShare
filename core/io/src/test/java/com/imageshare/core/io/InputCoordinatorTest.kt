package com.imageshare.core.io

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowBitmapFactory
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.NamedStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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

    /**
     * Regression test for the readDimensions Elvis-on-use bug.
     *
     * Before the fix, `readDimensions` used
     * `openInputStream(uri)?.use { decodeStream(...) } ?: return null`. The Elvis matched on
     * the `.use {}` block's return value (a null Bitmap, since `inJustDecodeBounds = true`
     * always returns null Bitmap) and caused every successful decode to short-circuit to
     * null dimensions. Robolectric's BitmapFactory shadow didn't set outWidth/outHeight, so
     * prior Robolectric tests asserted `assertNull(item.width)` as a side-effect of the
     * stub. The bug shipped because no Robolectric test exercised the
     * openInputStream-returns-non-null success path.
     *
     * Robolectric's BitmapFactory shadow does not always derive bounds from bytes, so this
     * test provides explicit hints and also checks the stream was reopened for EXIF after
     * bounds decode. That makes the test fail if `.use { decodeStream(...) } ?: return null`
     * is reintroduced: the first stream is consumed, but dimensions remain null and EXIF is
     * never reached.
     */
    @Test
    fun resolveDoesNotShortCircuitDimensionsWhenOpenStreamSucceeds() = runBlocking {
        ShadowBitmapFactory.reset()
        val jpegBytes = generateJpegBytes(width = 32, height = 24)
        val provider = TestContentProvider(
            displayName = "fixture.jpg",
            sizeBytes = jpegBytes.size.toLong(),
            mimeType = "image/jpeg",
        )
        val uri = registerProvider(
            authority = "decodable",
            provider = provider,
        )
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        var openInputStreamCount = 0
        shadowOf(resolver).registerInputStreamSupplier(uri) {
            openInputStreamCount += 1
            NamedByteArrayInputStream(jpegBytes, uri.toString())
        }
        ShadowBitmapFactory.provideWidthAndHeightHints(uri, 32, 24)

        val item = InputCoordinator(resolver).resolve(uri)

        assertEquals("image/jpeg", item.mimeType)
        assertEquals("fixture.jpg", item.displayName)
        assertEquals(jpegBytes.size.toLong(), item.sizeBytes)
        assertEquals(32, item.width)
        assertEquals(24, item.height)
        assertEquals(2, openInputStreamCount)
    }

    private fun generateJpegBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun registerProvider(authority: String, provider: ContentProvider): Uri {
        ShadowContentResolver.registerProviderInternal(authority, provider)
        return Uri.parse("content://$authority/image")
    }
}

private class NamedByteArrayInputStream(
    bytes: ByteArray,
    private val name: String,
) : ByteArrayInputStream(bytes), NamedStream {
    override fun toString(): String = "stream for $name"
}

private class TestContentProvider(
    private val displayName: String? = null,
    private val sizeBytes: Long? = null,
    private val mimeType: String? = null,
    private val queryFailure: RuntimeException? = null,
    private val typeFailure: RuntimeException? = null,
    private val openFailure: Exception? = null,
    private val openFileReturn: File? = null,
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
        openFileReturn?.let { file ->
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
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
