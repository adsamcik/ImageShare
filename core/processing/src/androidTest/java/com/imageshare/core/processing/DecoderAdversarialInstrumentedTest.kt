@file:Suppress("MaxLineLength", "LargeClass", "TooManyFunctions")

package com.imageshare.core.processing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DecoderAdversarialInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val decoder = Decoder(context.contentResolver)

    @Test
    fun zeroByteSourceThrowsCorrupt() = runBlocking {
        assertDecodeError<DecodeError.Corrupt>(writeBytes("zero-byte.jpg", ByteArray(0)))
    }

    @Test
    fun oneByteSourceThrowsCorrupt() = runBlocking {
        assertDecodeError<DecodeError.Corrupt>(writeBytes("one-byte.jpg", byteArrayOf(0xff.toByte())))
    }

    @Test
    fun truncatedJpegThrowsCorrupt() = runBlocking {
        val full = jpegBytes(512, 512)
        assertDecodeError<DecodeError.Corrupt>(writeBytes("truncated.jpg", full.copyOf(200)))
    }

    @Test
    fun jpgExtensionWithPngBytesDecodesUsingHeaderMime() = runBlocking {
        val uri = writeBitmap("lying.jpg", 40, 30, Bitmap.CompressFormat.PNG)

        val metadata = decoder.readMetadata(uri)
        val image = decoder.decode(uri, targetLongEdgePx = 40)

        assertEquals("image/png", metadata.mimeType)
        assertEquals(40, image.bitmap.width)
        assertEquals(30, image.bitmap.height)
        image.bitmap.recycle()
    }

    @Test
    fun pngExtensionWithCorruptBytesThrowsCorrupt() = runBlocking {
        assertDecodeError<DecodeError.Corrupt>(writeBytes("corrupt.png", Random(42).nextBytes(256)))
    }

    @Test
    fun massiveSourceSucceedsOrThrowsTypedOom() = runBlocking {
        val uri = writeBytes("massive.png", giantSolidPng(width = 10_000, height = 10_000))
        val outcome = runCatching { decoder.decode(uri, targetLongEdgePx = 256) }
        val image = outcome.getOrNull()
        if (image != null) {
            assertTrue(max(image.bitmap.width, image.bitmap.height) <= 256)
            image.bitmap.recycle()
        } else {
            assertTrue(outcome.exceptionOrNull() is DecodeError.OOM)
        }
    }

    @Test
    fun animatedWebpDecodesFirstFrameOrTypedError() = runBlocking {
        assertDecodesOrTypedError(writeFixture("animated.webp", ANIMATED_WEBP_BASE64), expectedMax = 8)
    }

    @Test
    fun progressiveJpegDecodes() = runBlocking {
        val image = decoder.decode(writeFixture("progressive.jpg", PROGRESSIVE_JPEG_BASE64), targetLongEdgePx = 64)
        assertEquals(64, image.bitmap.width)
        assertEquals(48, image.bitmap.height)
        image.bitmap.recycle()
    }

    @Test
    fun cmykJpegDecodes() = runBlocking {
        val image = decoder.decode(writeFixture("cmyk.jpg", CMYK_JPEG_BASE64), targetLongEdgePx = 32)
        assertEquals(32, image.bitmap.width)
        assertEquals(24, image.bitmap.height)
        image.bitmap.recycle()
    }

    @Test
    fun sixteenBitPngDecodesOrThrowsTypedError() = runBlocking {
        assertDecodesOrTypedError(writeFixture("high-depth.png", PNG_16BIT_BASE64), expectedMax = 16)
    }

    @Test
    fun emptyContentUriWithSuccessfulQueryThrowsTypedCorrupt() = runBlocking {
        val uri = Uri.parse("content://com.imageshare.core.processing.test.empty/empty.jpg")
        assertDecodeError<DecodeError.Corrupt>(uri)
    }

    private suspend inline fun <reified T : DecodeError> assertDecodeError(uri: Uri) {
        val thrown = runCatching { decoder.decode(uri, targetLongEdgePx = 128) }.exceptionOrNull()
        assertTrue("Expected ${T::class.java.simpleName}, got $thrown", thrown is T)
    }

    private suspend fun assertDecodesOrTypedError(uri: Uri, expectedMax: Int) {
        val outcome = runCatching { decoder.decode(uri, targetLongEdgePx = expectedMax) }
        val image = outcome.getOrNull()
        if (image != null) {
            assertTrue(max(image.bitmap.width, image.bitmap.height) <= expectedMax)
            image.bitmap.recycle()
        } else {
            assertTrue(outcome.exceptionOrNull() is DecodeError)
        }
    }

    private fun writeFixture(name: String, base64: String): Uri = writeBytes(name, Base64.decode(base64, Base64.DEFAULT))

    private fun writeBytes(name: String, bytes: ByteArray): Uri {
        val file = File(context.cacheDir, name).apply { writeBytes(bytes) }
        return Uri.fromFile(file)
    }

    private fun writeBitmap(name: String, width: Int, height: Int, format: Bitmap.CompressFormat): Uri {
        val file = File(context.cacheDir, name)
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).useBitmap { bitmap ->
            bitmap.setHasAlpha(false)
            Canvas(bitmap).drawColor(Color.rgb(30, 140, 220))
            file.outputStream().use { output -> check(bitmap.compress(format, 90, output)) }
        }
        return Uri.fromFile(file)
    }

    private fun jpegBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(bitmap).drawColor(Color.rgb(10, 20, 30))
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun giantSolidPng(width: Int, height: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw).use { deflater ->
            val row = ByteArray(1 + width * 3)
            repeat(height) { deflater.write(row) }
        }
        return ByteArrayOutputStream().apply {
            write(PNG_SIGNATURE)
            writeChunk("IHDR", ByteArrayOutputStream().apply {
                writeInt(width)
                writeInt(height)
                write(byteArrayOf(8, 2, 0, 0, 0))
            }.toByteArray())
            writeChunk("IDAT", raw.toByteArray())
            writeChunk("IEND", ByteArray(0))
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeChunk(type: String, data: ByteArray) {
        writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        write(typeBytes)
        write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        writeInt(crc.value.toInt())
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try { block(this) } finally { recycle() }
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        const val PROGRESSIVE_JPEG_BASE64 = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAUDBAQEAwUEBAQFBQUGBwwIBwcHBw8LCwkMEQ8SEhEPERETFhwXExQaFRERGCEYGh0dHx8fExciJCIeJBweHx7/2wBDAQUFBQcGBw4ICA4eFBEUHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh7/wgARCAAwAEADASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAX/xAAVAQEBAAAAAAAAAAAAAAAAAAAABv/aAAwDAQACEAMQAAABkCxkQAAAAAAAAAAP/8QAFBABAAAAAAAAAAAAAAAAAAAAUP/aAAgBAQABBQJD/8QAFBEBAAAAAAAAAAAAAAAAAAAAMP/aAAgBAwEBPwFP/8QAFBEBAAAAAAAAAAAAAAAAAAAAMP/aAAgBAgEBPwFP/8QAFBABAAAAAAAAAAAAAAAAAAAAUP/aAAgBAQAGPwJD/8QAFBABAAAAAAAAAAAAAAAAAAAAUP/aAAgBAQABPyFD/9oADAMBAAIAAwAAABD/AP8A/wD/AP8A/wD/AP8A/wD/xAAUEQEAAAAAAAAAAAAAAAAAAAAw/9oACAEDAQE/EE//xAAUEQEAAAAAAAAAAAAAAAAAAAAw/9oACAECAQE/EE//xAAUEAEAAAAAAAAAAAAAAAAAAABQ/9oACAEBAAE/EEP/2Q=="
        const val CMYK_JPEG_BASE64 = "/9j/7gAOQWRvYmUAZAAAAAAA/9sAQwAFAwQEBAMFBAQEBQUFBgcMCAcHBwcPCwsJDBEPEhIRDxERExYcFxMUGhURERghGBodHR8fHxMXIiQiHiQcHh8e/8AAFAgAGAAgBEMRAE0RAFkRAEsRAP/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/aAA4EQwBNAFkASwAAPwD7LptNr7Loooooooooooooooooooooooooooooooooooooooooooor/9k="
        const val PNG_16BIT_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAABAAAAAMEAAAAAAeHL4eAAAAGElEQVR4nGNkYODAC5nwS3OMKmCgVjgAAK8ABhBXDHTDAAAAAElFTkSuQmCC"
        const val ANIMATED_WEBP_BASE64 = "UklGRsQAAABXRUJQVlA4WAoAAAACAAAABwAABwAAQU5JTQYAAAAAAAAAAABBTk1GSgAAAAAAAAAAAAcAAAcAAGQAAAJWUDggMgAAADABAJ0BKggACAABQCYloAADcAD+8ut///mwP/bz/wR6Af//0uD//pcH//S4P/SkAAAAQU5NRkYAAAAAAAAAAAAHAAAHAABkAAAAVlA4IC4AAAA0AQCdASoIAAgAAAAmJaAAA3AA/vtV4///S4P/+lwf/9Lg/9Lg//rV5Vesq6AA"
    }
}

class EmptyImageProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any?>("empty.jpg", 0L))
        }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("No context")
        val file = File(ctx.cacheDir, "empty-provider.jpg").apply { writeBytes(ByteArray(0)) }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
