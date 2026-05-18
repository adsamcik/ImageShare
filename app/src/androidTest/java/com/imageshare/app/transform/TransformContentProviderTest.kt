package com.imageshare.app.transform

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.imageshare.app.BuildConfig
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransformContentProviderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver

    @Before fun resetRateLimiter() {
        TransformContentProvider.resetRateLimiterForTests()
        RevokingSourceProvider.reset()
        grantTestSourceRead(Uri.parse("content://com.imageshare.app.testsource/image/1"))
    }

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

    @Test fun callerWithoutGrantOnSourceThrowsGrantLost() {
        val unreachable = Uri.parse("content://com.example.no-such-authority/image.jpg")
        val transformUri = Uri.parse(
            "content://${BuildConfig.APPLICATION_ID}.transform/v1/jpeg/q80/original/stripall?source=" +
                Uri.encode(unreachable.toString()),
        )
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val ex = assertThrows(FileNotFoundException::class.java) {
            resolver.openFileDescriptor(transformUri, "r")
        }
        val msg = ex.message ?: ""
        assertTrue(
            "expected GRANT_LOST: $msg",
            msg.contains("GRANT_LOST"),
        )
        assertTrue(
            "expected the new checkUriPermission branch to fire, got: $msg",
            msg.contains("caller does not hold read grant on source"),
        )
    }

    @Test fun revokedGrantMidPipelineThrowsGrantLost() {
        val source = Uri.parse("content://com.imageshare.app.testsource/image/1")
        val uri = transformUri(source = source)
        val error = assertFileNotFound(uri)
        assertTrue(error.message.orEmpty().contains("GRANT_LOST"))
    }

    @Test fun oversizedSourceThrowsPixelBudgetExceeded() {
        val uri = transformUri(source = hugePngUri())
        val error = assertFileNotFound(uri)
        assertTrue(error.message.orEmpty().contains("PIXEL_BUDGET_EXCEEDED"))
    }

    @Test fun perUidRateLimitRejects101stTransform() {
        val uri = transformUri()
        repeat(BuildConfig.TRANSFORM_RATE_LIMIT_PER_UID_PER_MINUTE) {
            openBytes(uri)
        }
        val error = assertFileNotFound(uri)
        assertTrue(error.message.orEmpty().contains("RATE_LIMIT"))
    }

    @Test fun getTypeReturnsNullUnderRateLimit() {
        val uri = transformUri(source = Uri.parse("content://example/image.jpg"))
        repeat(BuildConfig.TRANSFORM_RATE_LIMIT_PER_UID_PER_MINUTE) {
            resolver.getType(uri)
        }
        assertNull(resolver.getType(uri))
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

    @Test fun cacheHitReturnsSameBytesOnSecondCall() {
        val uri = transformUri(format = "jpeg", source = sourceJpegUri())

        val first = openBytes(uri)
        val second = openBytes(uri)

        assertArrayEquals(first, second)
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

    private fun hugePngUri(): Uri {
        val file = File(sharedOutputDir(), "huge-20000x20000-valid.png")
        if (!file.isFile) {
            FileOutputStream(file).use { writeHugePng(it, 20_000, 20_000) }
        }
        return fileProviderUri(file)
    }

    private fun sourceJpegUri(): Uri {
        val file = File(sharedOutputDir(), "transform-source.jpg")
        if (!file.isFile) {
            val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
            try {
                for (y in 0 until bitmap.height) {
                    for (x in 0 until bitmap.width) {
                        bitmap.setPixel(x, y, Color.rgb(64, x * 255 / bitmap.width, y * 255 / bitmap.height))
                    }
                }
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            } finally {
                bitmap.recycle()
            }
        }
        return fileProviderUri(file)
    }

    private fun sharedOutputDir(): File = File(context.cacheDir, "shared-output").apply { mkdirs() }

    private fun fileProviderUri(file: File): Uri {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.shareprovider", file)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        listOf(context.packageName, instrumentation.context.packageName).distinct().forEach { packageName ->
            context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return uri
    }

    private fun writeHugePng(output: FileOutputStream, width: Int, height: Int) {
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArrayOutputStream()
        DataOutputStream(ihdr).use { data ->
            data.writeInt(width)
            data.writeInt(height)
            data.writeByte(8)
            data.writeByte(0)
            data.writeByte(0)
            data.writeByte(0)
            data.writeByte(0)
        }
        writePngChunk(output, "IHDR", ihdr.toByteArray())
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { deflater ->
            val row = ByteArray(width + 1)
            repeat(height) {
                deflater.write(row)
            }
        }
        writePngChunk(output, "IDAT", compressed.toByteArray())
        writePngChunk(output, "IEND", ByteArray(0))
    }

    private fun writePngChunk(output: FileOutputStream, type: String, data: ByteArray) {
        val chunkBytes = ByteArrayOutputStream()
        DataOutputStream(chunkBytes).use { chunk ->
            chunk.writeInt(data.size)
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            chunk.write(typeBytes)
            chunk.write(data)
            val crc = CRC32()
            crc.update(typeBytes)
            crc.update(data)
            chunk.writeInt(crc.value.toInt())
        }
        output.write(chunkBytes.toByteArray())
    }

    private fun grantTestSourceRead(uri: Uri) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packages = listOf(
            instrumentation.targetContext.packageName,
            instrumentation.context.packageName,
        ).distinct()
        packages.forEach { packageName ->
            instrumentation.context.grantUriPermission(
                packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private companion object {
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
