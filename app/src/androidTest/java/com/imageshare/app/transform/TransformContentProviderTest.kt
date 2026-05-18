package com.imageshare.app.transform

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.imageshare.app.BuildConfig
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val uri = transformUri(source = Uri.parse("content://com.example.no-such-authority/image.jpg"))
        val error = assertFileNotFound(uri)
        assertTrue(
            "expected GRANT_LOST, got: ${error.message}",
            error.message.orEmpty().contains("GRANT_LOST"),
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
        return Uri.parse("content://com.imageshare.app.testsource/huge/image.png")
    }

    private fun sourceJpegUri(): Uri {
        return Uri.parse("content://com.imageshare.app.testsource/stable/image.jpg")
    }

    private companion object {
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
