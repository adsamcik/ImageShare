@file:Suppress("MaxLineLength")

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
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PermissionRevocationTest {
    @Test
    fun grantRevokedAfterQueryBeforeOpenInputStreamThrowsTypedQueryFailure() {
        val authority = "revoked${System.nanoTime()}"
        val provider = RevokingProvider()
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val uri = Uri.parse("content://$authority/image.jpg")
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication().contentResolver)
            .registerInputStreamSupplier(uri) {
                throw SecurityException("Grant revoked after query")
            }

        val error = assertThrows(IntakeError.QueryFailed::class.java) {
            runBlocking { InputCoordinator(RuntimeEnvironment.getApplication().contentResolver).resolve(uri) }
        }

        assertEquals(uri, error.uri)
        assertEquals(IntakeError.GrantLost(uri), error.cause)
    }
}

private class RevokingProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
        addRow(arrayOf<Any?>("revoked.jpg", 123L))
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        throw SecurityException("Grant revoked after query")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
