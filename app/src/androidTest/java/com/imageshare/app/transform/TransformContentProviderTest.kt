package com.imageshare.app.transform

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransformContentProviderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver

    @Test fun jpegTransformHasJpegMagic() {
        val bytes = openBytes(transformUri())
        assertTrue(bytes.size > 2)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
    }

    @Test fun malformedUriThrowsStablePrefix() {
        val uri = Uri.parse("content://${context.packageName}.transform/v1/jpeg/q85/original")
        val error = assertFileNotFound(uri)
        assertTrue(error.message.orEmpty().startsWith("ImageShareTransform: MALFORMED_URI:"))
    }

    @Test fun selfReferenceSourceThrowsMalformed() {
        val self = transformUri(source = Uri.parse("content://${context.packageName}.transform/v1/jpeg/q85/original/stripall"))
        val error = assertFileNotFound(self)
        assertTrue(error.message.orEmpty().startsWith("ImageShareTransform: MALFORMED_URI:"))
    }

    @Test fun unsupportedVersionThrowsStablePrefix() {
        val uri = transformUri(version = "v2")
        val error = assertFileNotFound(uri)
        assertTrue(error.message.orEmpty().startsWith("ImageShareTransform: UNSUPPORTED_VERSION:"))
    }

    @Test fun qautoWithoutTargetBytesThrowsMalformed() {
        val uri = transformUri(quality = "qauto")
        val error = assertFileNotFound(uri)
        assertTrue(error.message.orEmpty().startsWith("ImageShareTransform: MALFORMED_URI:"))
    }

    @Test fun qautoWithTargetBytesProducesJpegMagic() {
        val bytes = openBytes(transformUri(quality = "qauto", targetBytes = 2048))
        assertTrue(bytes.size > 2)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
    }

    @Test fun unreadableSourceThrowsGrantLost() {
        val uri = transformUri(source = Uri.parse("content://missing.source.provider/image/1"))
        val error = assertFileNotFound(uri)
        assertTrue(error.message.orEmpty().startsWith("ImageShareTransform: GRANT_LOST:"))
    }

    @Test fun pngFromJpegSourceHasPngMagicAndMimeType() {
        val uri = transformUri(format = "png")
        assertEquals("image/png", resolver.getType(uri))
        val bytes = openBytes(uri)
        assertTrue(bytes.take(PNG_MAGIC.size).toByteArray().contentEquals(PNG_MAGIC))
    }

    @Test fun fileProviderContentUriSmokePathDecodesResult() {
        val uri = transformUri(resize = "longEdge16")
        val bytes = openBytes(uri)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(decoded)
        assertEquals(16, maxOf(decoded.width, decoded.height))
        decoded.recycle()
    }

    private fun openBytes(uri: Uri): ByteArray = resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw AssertionError("Expected transform stream for $uri")

    private fun assertFileNotFound(uri: Uri): FileNotFoundException = try {
        resolver.openInputStream(uri)?.close()
        throw AssertionError("Expected FileNotFoundException for $uri")
    } catch (error: FileNotFoundException) {
        error
    }

    private fun transformUri(
        version: String = "v1",
        format: String = "jpeg",
        quality: String = "q85",
        resize: String = "original",
        metadata: String = "stripall",
        source: Uri = sourceJpegUri(),
        targetBytes: Long? = null,
    ): Uri = Uri.Builder()
        .scheme("content")
        .authority("${context.packageName}.transform")
        .appendPath(version)
        .appendPath(format)
        .appendPath(quality)
        .appendPath(resize)
        .appendPath(metadata)
        .appendQueryParameter("source", source.toString())
        .apply { targetBytes?.let { appendQueryParameter("targetBytes", it.toString()) } }
        .build()

    private fun sourceJpegUri(): Uri {
        val dir = File(context.cacheDir, "shared-output").apply { mkdirs() }
        val file = File(dir, "transform-source.jpg")
        if (!file.isFile) {
            val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
            try {
                for (y in 0 until bitmap.height) {
                    for (x in 0 until bitmap.width) {
                        bitmap.setPixel(x, y, Color.rgb(x * 255 / bitmap.width, y * 255 / bitmap.height, 128))
                    }
                }
                file.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output) }
            } finally {
                bitmap.recycle()
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.shareprovider", file)
        context.grantUriPermission(context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return uri
    }

    private companion object {
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
