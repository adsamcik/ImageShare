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
import androidx.test.platform.app.InstrumentationRegistry
import com.imageshare.api.ImageShareTransform
import com.imageshare.api.TransformException
import com.imageshare.api.TransformRequest
import com.imageshare.api.TransformResult
import com.imageshare.core.processing.AvifAvailability
import com.imageshare.core.processing.HeifAvailability
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SdkTransformInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun resetRateLimiter() {
        TransformContentProvider.resetRateLimiterForTests()
    }

    @Test fun sdkTransformReturnsBytes() {
        val bytes = ImageShareTransform.transform(context, request(format = TransformRequest.Format.Jpeg))
        assertJpeg(bytes)
    }

    @Test fun sdkTransformAsyncWorks() = runBlocking {
        val bytes = ImageShareTransform.transformAsync(context, request(format = TransformRequest.Format.Png))
        assertPng(bytes)
    }

    @Test fun sdkTransformResultErrorPath() {
        val result = ImageShareTransform.transformResult(
            context,
            request(source = Uri.parse("content://missing.source.provider/image/1")),
        )

        assertTrue(result is TransformResult.Error)
        assertEquals(TransformResult.ErrorCode.GrantLost, (result as TransformResult.Error).code)
    }

    @Test fun sdkIsAvailableReturnsTrueWhenInstalled() {
        assertTrue(ImageShareTransform.isAvailable(context))
    }

    @Test fun sdkPropagatesGrantLost() {
        try {
            ImageShareTransform.transform(
                context,
                request(source = Uri.parse("content://missing.source.provider/image/1")),
            )
            fail("Expected TransformException")
        } catch (error: TransformException) {
            assertEquals(TransformResult.ErrorCode.GrantLost, error.code)
        }
    }

    @Test fun sdkRoundTripsJpegPngWebp() {
        assertJpeg(ImageShareTransform.transform(context, request(format = TransformRequest.Format.Jpeg)))
        assertPng(ImageShareTransform.transform(context, request(format = TransformRequest.Format.Png)))
        assertWebp(ImageShareTransform.transform(context, request(format = TransformRequest.Format.WebpLossy)))
    }

    @Test fun sdkRoundTripsHeifWhenAvailable() {
        assumeTrue("Device under test has no HEIF encoder", HeifAvailability.isWriteSupported())
        assertIsoBrand(
            ImageShareTransform.transform(context, request(format = TransformRequest.Format.Heif)),
            setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1"),
        )
    }

    @Test fun sdkRoundTripsAvifWhenAvailable() {
        assumeTrue("Device under test has no AVIF encoder", AvifAvailability.isAnyWriteSupported())
        assertIsoBrand(
            ImageShareTransform.transform(context, request(format = TransformRequest.Format.Avif)),
            setOf("avif", "avis", "mif1", "msf1"),
        )
    }

    private fun request(
        source: Uri = sourceJpegUri(),
        format: TransformRequest.Format = TransformRequest.Format.Jpeg,
    ): TransformRequest = TransformRequest(
        source = source,
        format = format,
        quality = TransformRequest.Quality.Fixed(85),
        resize = TransformRequest.Resize.Original,
        metadata = TransformRequest.Metadata.StripAll,
    )

    private fun sourceJpegUri(): Uri {
        val file = File(sharedOutputDir(), "sdk-transform-source.jpg")
        if (!file.isFile) {
            val bitmap = Bitmap.createBitmap(32, 20, Bitmap.Config.ARGB_8888)
            try {
                for (y in 0 until bitmap.height) {
                    for (x in 0 until bitmap.width) {
                        bitmap.setPixel(x, y, Color.rgb(x * 255 / bitmap.width, 64, y * 255 / bitmap.height))
                    }
                }
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            } finally {
                bitmap.recycle()
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.shareprovider", file)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        listOf(context.packageName, instrumentation.context.packageName, ImageShareTransform.IMAGESHARE_PACKAGE)
            .distinct()
            .forEach { packageName ->
                context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return uri
    }

    private fun sharedOutputDir(): File = File(context.cacheDir, "shared-output").apply { mkdirs() }

    private fun assertJpeg(bytes: ByteArray) {
        assertTrue(bytes.size >= 3)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        assertEquals(0xFF, bytes[2].toInt() and 0xFF)
        assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun assertPng(bytes: ByteArray) {
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue(bytes.take(magic.size).toByteArray().contentEquals(magic))
    }

    private fun assertWebp(bytes: ByteArray) {
        assertTrue(bytes.size >= 12)
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WEBP", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
    }

    private fun assertIsoBrand(bytes: ByteArray, expectedBrands: Set<String>) {
        assertTrue(bytes.size >= 16)
        assertEquals("ftyp", bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII))
        val header = bytes.copyOfRange(8, minOf(bytes.size, 64)).toString(Charsets.ISO_8859_1)
        assertTrue("Expected one of $expectedBrands in ISO BMFF header", expectedBrands.any { header.contains(it) })
    }
}
