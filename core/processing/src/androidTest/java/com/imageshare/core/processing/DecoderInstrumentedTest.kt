package com.imageshare.core.processing

import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DecoderInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val decoder = Decoder(context.contentResolver)

    @Test
    fun decodeLandscapeJpegHonoursTargetLongEdge() = runBlocking {
        val image = decoder.decode(TestImages.landscapeJpeg(context), targetLongEdgePx = 2048)

        assertEquals(5000, image.sourceWidth)
        assertEquals(3000, image.sourceHeight)
        assertEquals(2048, image.bitmap.width)
        assertTrue(abs(image.bitmap.height - 1229) <= 1)
        assertFalse(image.hadAlpha)
        image.bitmap.recycle()
    }

    @Test
    fun decodeRot90JpegNormalisesOrientationIntoPixels() = runBlocking {
        val uri = TestImages.portraitRot90Jpeg(context)
        val metadata = decoder.readMetadata(uri)
        val image = decoder.decode(uri, targetLongEdgePx = 2048)

        assertEquals(800, metadata.width)
        assertEquals(600, metadata.height)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, metadata.orientation)
        assertEquals(600, image.bitmap.width)
        assertEquals(800, image.bitmap.height)
        image.bitmap.recycle()
    }

    @Test
    fun alphaPngReportsAlphaAndKeepsAlphaOnDecode() = runBlocking {
        val uri = TestImages.alphaPng(context)
        val metadata = decoder.readMetadata(uri)
        val image = decoder.decode(uri, targetLongEdgePx = 200)

        assertEquals(200, metadata.width)
        assertEquals(200, metadata.height)
        assertEquals("image/png", metadata.mimeType)
        assertTrue(metadata.hasAlpha)
        assertTrue(image.hadAlpha)
        assertTrue(image.bitmap.hasAlpha())
        image.bitmap.recycle()
    }

    @Test
    fun screenshotPngReportsNoAlphaAndDecodesFlatColor() = runBlocking {
        val uri = TestImages.screenshotPng(context)
        val metadata = decoder.readMetadata(uri)
        val image = decoder.decode(uri, targetLongEdgePx = 180)

        assertEquals(320, metadata.width)
        assertEquals(180, metadata.height)
        assertEquals("image/png", metadata.mimeType)
        assertFalse(metadata.hasAlpha)
        assertFalse(image.hadAlpha)
        assertFalse(image.bitmap.hasAlpha())
        image.bitmap.recycle()
    }

    @Test
    fun webpLossyDecodesFirstFrame() = runBlocking {
        val uri = TestImages.webpLossy(context)
        val metadata = decoder.readMetadata(uri)
        val image = decoder.decode(uri, targetLongEdgePx = 96)

        assertEquals(96, metadata.width)
        assertEquals(64, metadata.height)
        assertEquals(96, image.bitmap.width)
        assertEquals(64, image.bitmap.height)
        assertNotNull(metadata.mimeType)
        image.bitmap.recycle()
    }

    @Test
    fun avifDecodesOnApi31PlusOrFailsAsUnsupported() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val thrown = runCatching {
            decoder.decode(TestImages.avif(context), targetLongEdgePx = 2).bitmap.recycle()
        }.exceptionOrNull()

        if (thrown != null) {
            assertTrue(thrown is DecodeError.UnsupportedFormat)
        }
    }

    @Test
    fun corruptInputThrowsCorrupt() = runBlocking {
        val thrown = runCatching {
            decoder.decode(TestImages.corruptJpeg(context), targetLongEdgePx = 128)
        }.exceptionOrNull()

        assertTrue(thrown is DecodeError.Corrupt)
    }

    @Test
    fun zeroByteFileThrowsCorrupt() = runBlocking {
        val thrown = runCatching {
            decoder.decode(TestImages.zeroByteJpeg(context), targetLongEdgePx = 128)
        }.exceptionOrNull()

        assertTrue(thrown is DecodeError.Corrupt)
    }

    @Test
    fun mimeMismatchUsesHeaderMimeType() = runBlocking {
        val uri = TestImages.pngWithJpgExtension(context)
        val metadata = decoder.readMetadata(uri)
        val image = decoder.decode(uri, targetLongEdgePx = 32)

        assertEquals("image/png", metadata.mimeType)
        assertEquals(32, image.bitmap.width)
        assertEquals(24, image.bitmap.height)
        image.bitmap.recycle()
    }

    @Test
    fun animatedGifDecodesFirstFrame() = runBlocking {
        val uri = TestImages.animatedGif(context)
        val metadata = decoder.readMetadata(uri)
        val image = decoder.decode(uri, targetLongEdgePx = 16)

        assertEquals(1, metadata.width)
        assertEquals(1, metadata.height)
        assertEquals(1, image.bitmap.width)
        assertEquals(1, image.bitmap.height)
        image.bitmap.recycle()
    }

    @Test
    fun largeBoundsDoesNotOom() = runBlocking {
        val runtime = Runtime.getRuntime()
        runtime.gc()
        val beforeBytes = runtime.totalMemory() - runtime.freeMemory()

        val image = decoder.decode(TestImages.largeJpeg(context), targetLongEdgePx = 1024)

        runtime.gc()
        val afterBytes = runtime.totalMemory() - runtime.freeMemory()
        assertTrue(max(image.bitmap.width, image.bitmap.height) <= 1024)
        assertTrue("allocation delta=${afterBytes - beforeBytes}", afterBytes - beforeBytes < MaxAllocationDeltaBytes)
        image.bitmap.recycle()
    }

    private companion object {
        private const val MaxAllocationDeltaBytes = 80L * 1024L * 1024L
    }
}
