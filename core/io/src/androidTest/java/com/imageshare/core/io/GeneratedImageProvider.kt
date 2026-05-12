package com.imageshare.core.io

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

class GeneratedImageProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
        addRow(arrayOf("generated-input.jpg", imageSizeBytes))
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("Unsupported mode $mode")
        }

        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                output.write(imageBytes)
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

    companion object {
        val imageBytes: ByteArray by lazy {
            val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
            try {
                ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }

        val imageSizeBytes: Long
            get() = imageBytes.size.toLong()
    }
}
