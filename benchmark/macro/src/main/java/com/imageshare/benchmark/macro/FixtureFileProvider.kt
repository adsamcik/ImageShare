@file:Suppress("ThrowsCount")

package com.imageshare.benchmark.macro

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class FixtureFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("Fixture provider is read-only: $uri")
        }
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = fileFor(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> file.name
                        OpenableColumns.SIZE -> file.length()
                        else -> null
                    }
                },
            )
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun fileFor(uri: Uri): File {
        if (uri.authority != AUTHORITY) {
            throw FileNotFoundException("Unsupported fixture authority: $uri")
        }
        val segments = uri.pathSegments
        if (segments.size != 2 || segments.first() != FIXTURE_PATH || segments.last().contains("..")) {
            throw FileNotFoundException("Unsupported fixture path: $uri")
        }
        val fixturesDir = File(requireNotNull(context).cacheDir, FIXTURE_PATH).canonicalFile
        val file = File(fixturesDir, segments.last()).canonicalFile
        if (!file.path.startsWith(fixturesDir.path) || !file.isFile) {
            throw FileNotFoundException("Fixture not found: $uri")
        }
        return file
    }

    companion object {
        const val AUTHORITY = "com.imageshare.benchmark.macro.fixture"
        private const val FIXTURE_PATH = "fixtures"

        fun uriFor(fileName: String): Uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(AUTHORITY)
            .appendPath(FIXTURE_PATH)
            .appendPath(fileName)
            .build()
    }
}
