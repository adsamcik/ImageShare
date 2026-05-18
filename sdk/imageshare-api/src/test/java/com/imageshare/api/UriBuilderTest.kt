package com.imageshare.api

import android.net.Uri
import java.io.FileNotFoundException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
public class UriBuilderTest {
    @Test
    public fun buildsMinimalJpegUri() {
        val uri = ImageShareTransform.buildUri(request())

        assertEquals("content", uri.scheme)
        assertEquals("com.imageshare.app.transform", uri.authority)
        assertEquals(listOf("v1", "jpeg", "q85", "original", "stripall"), uri.pathSegments)
        assertEquals(SOURCE.toString(), uri.getQueryParameter("source"))
        assertEquals(
            "content://com.imageshare.app.transform/v1/jpeg/q85/original/stripall" +
                "?source=content%3A%2F%2Fsource.app%2Fimage%2F1",
            uri.toString(),
        )
    }

    @Test
    public fun mapsAllFormats() {
        assertPathSegment(1, TransformRequest.Format.Jpeg, "jpeg")
        assertPathSegment(1, TransformRequest.Format.Png, "png")
        assertPathSegment(1, TransformRequest.Format.WebpLossy, "webp")
        assertPathSegment(1, TransformRequest.Format.WebpLossless, "webplossless")
        assertPathSegment(1, TransformRequest.Format.Heif, "heif")
        assertPathSegment(1, TransformRequest.Format.Avif, "avif")
    }

    @Test
    public fun exposesFormatMimeTypesAndExtensions() {
        assertEquals("image/jpeg", TransformRequest.Format.Jpeg.mimeType)
        assertEquals("jpg", TransformRequest.Format.Jpeg.extension)
        assertEquals("image/png", TransformRequest.Format.Png.mimeType)
        assertEquals("png", TransformRequest.Format.Png.extension)
        assertEquals("image/webp", TransformRequest.Format.WebpLossy.mimeType)
        assertEquals("webp", TransformRequest.Format.WebpLossy.extension)
        assertEquals("image/webp", TransformRequest.Format.WebpLossless.mimeType)
        assertEquals("webp", TransformRequest.Format.WebpLossless.extension)
        assertEquals("image/heif", TransformRequest.Format.Heif.mimeType)
        assertEquals("heif", TransformRequest.Format.Heif.extension)
        assertEquals("image/avif", TransformRequest.Format.Avif.mimeType)
        assertEquals("avif", TransformRequest.Format.Avif.extension)
    }

    @Test
    public fun mapsFixedQuality() {
        val uri = ImageShareTransform.buildUri(request(quality = TransformRequest.Quality.Fixed(1)))
        assertEquals("q1", uri.pathSegments[2])
    }

    @Test
    public fun mapsAutoQualityWithTargetBytes() {
        val uri = ImageShareTransform.buildUri(
            request(quality = TransformRequest.Quality.Auto, targetBytes = 1024),
        )

        assertEquals("qauto", uri.pathSegments[2])
        assertEquals("1024", uri.getQueryParameter("targetBytes"))
    }

    @Test
    public fun mapsAllResizeModes() {
        assertResizePath(TransformRequest.Resize.Original, "original")
        assertResizePath(TransformRequest.Resize.LongEdge(1600), "longEdge1600")
        assertResizePath(TransformRequest.Resize.Exact(640, 480), "exact640x480")
        assertResizePath(TransformRequest.Resize.Percent(50), "percent50")
    }

    @Test
    public fun mapsAllMetadataModes() {
        assertMetadataPath(TransformRequest.Metadata.StripAll, "stripall")
        assertMetadataPath(TransformRequest.Metadata.PreserveSafe, "preservesafe")
        assertMetadataPath(TransformRequest.Metadata.PreserveAll, "preserveall")
    }

    @Test
    public fun omitsAspectLockForExactDefaultTrue() {
        val uri = ImageShareTransform.buildUri(request(resize = TransformRequest.Resize.Exact(100, 100)))
        assertEquals(null, uri.getQueryParameter("aspectLock"))
    }

    @Test
    public fun appendsAspectLockFalseOnlyForExact() {
        val uri = ImageShareTransform.buildUri(
            request(resize = TransformRequest.Resize.Exact(100, 100), aspectLock = false),
        )
        assertEquals("false", uri.getQueryParameter("aspectLock"))
    }

    @Test
    public fun builderUsesRequestedDefaults() {
        val built = TransformRequest.builder(SOURCE).build()

        assertEquals(SOURCE, built.source)
        assertEquals(TransformRequest.Format.Jpeg, built.format)
        assertEquals(TransformRequest.Quality.Fixed(85), built.quality)
        assertEquals(TransformRequest.Resize.Original, built.resize)
        assertEquals(TransformRequest.Metadata.StripAll, built.metadata)
        assertEquals(null, built.targetBytes)
        assertTrue(built.aspectLock)
    }

    @Test
    public fun builderCanSetAllProperties() {
        val built = TransformRequest.builder(SOURCE)
            .format(TransformRequest.Format.Avif)
            .autoQuality(4096)
            .exactSize(320, 200)
            .metadata(TransformRequest.Metadata.PreserveSafe)
            .aspectLock(false)
            .build()

        assertEquals(TransformRequest.Format.Avif, built.format)
        assertEquals(TransformRequest.Quality.Auto, built.quality)
        assertEquals(4096L, built.targetBytes)
        assertEquals(TransformRequest.Resize.Exact(320, 200), built.resize)
        assertEquals(TransformRequest.Metadata.PreserveSafe, built.metadata)
        assertFalse(built.aspectLock)
    }

    @Test
    public fun validatesQualityRange() {
        assertThrows(IllegalArgumentException::class.java) { TransformRequest.Quality.Fixed(0) }
        assertThrows(IllegalArgumentException::class.java) { TransformRequest.Quality.Fixed(101) }
    }

    @Test
    public fun validatesAutoQualityRequiresTargetBytes() {
        assertThrows(IllegalArgumentException::class.java) {
            request(quality = TransformRequest.Quality.Auto)
        }
    }

    @Test
    public fun rejectsTransformApiSourceSelfReference() {
        assertThrows(IllegalArgumentException::class.java) {
            request(source = Uri.parse("content://com.imageshare.app.transform/v1/jpeg/q85/original/stripall"))
        }
    }

    @Test
    public fun validatesTargetBytesRange() {
        assertThrows(IllegalArgumentException::class.java) { request(targetBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { request(targetBytes = 104_857_601) }
    }

    @Test
    public fun validatesLongEdgeRange() {
        assertThrows(IllegalArgumentException::class.java) { TransformRequest.Resize.LongEdge(0) }
        assertThrows(IllegalArgumentException::class.java) { TransformRequest.Resize.LongEdge(32769) }
    }

    @Test
    public fun validatesExactDimensionsAndArea() {
        assertThrows(IllegalArgumentException::class.java) { TransformRequest.Resize.Exact(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { TransformRequest.Resize.Exact(1, 32769) }
        assertThrows(IllegalArgumentException::class.java) { TransformRequest.Resize.Exact(20000, 20000) }
    }

    @Test
    public fun validatesPercentRange() {
        assertThrows(IllegalArgumentException::class.java) { TransformRequest.Resize.Percent(0) }
        assertThrows(IllegalArgumentException::class.java) { TransformRequest.Resize.Percent(201) }
    }

    @Test
    public fun allowsAspectLockTrueForAnyResize() {
        request(resize = TransformRequest.Resize.Original, aspectLock = true)
        request(resize = TransformRequest.Resize.LongEdge(1200), aspectLock = true)
        request(resize = TransformRequest.Resize.Percent(50), aspectLock = true)
    }

    @Test
    public fun rejectsAspectLockFalseForNonExactResize() {
        assertThrows(IllegalArgumentException::class.java) {
            request(resize = TransformRequest.Resize.Original, aspectLock = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(resize = TransformRequest.Resize.LongEdge(1200), aspectLock = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(resize = TransformRequest.Resize.Percent(50), aspectLock = false)
        }
    }

    @Test
    public fun mapsKnownErrorCodes() {
        assertErrorCode("GRANT_LOST", TransformResult.ErrorCode.GrantLost)
        assertErrorCode("RATE_LIMIT", TransformResult.ErrorCode.RateLimit)
        assertErrorCode("PROCESSING_FAILED", TransformResult.ErrorCode.ProcessingFailed)
        assertErrorCode("MALFORMED_URI", TransformResult.ErrorCode.MalformedUri)
        assertErrorCode("UNSUPPORTED_VERSION", TransformResult.ErrorCode.UnsupportedVersion)
        assertErrorCode("MISSING_SOURCE", TransformResult.ErrorCode.MissingSource)
        assertErrorCode("UNSUPPORTED_FORMAT", TransformResult.ErrorCode.UnsupportedFormat)
        assertErrorCode("SYSTEM_BUSY", TransformResult.ErrorCode.SystemBusy)
        assertErrorCode("PIXEL_BUDGET_EXCEEDED", TransformResult.ErrorCode.PixelBudgetExceeded)
    }

    @Test
    public fun mapsUnknownErrorCodeToUnknown() {
        assertErrorCode("NEW_CODE", TransformResult.ErrorCode.Unknown)
    }

    @Test
    public fun mapsMissingPrefixToProcessingFailed() {
        val cause = FileNotFoundException("plain failure")
        val error = ImageShareTransform.mapFileNotFoundToException(cause)

        assertEquals(TransformResult.ErrorCode.ProcessingFailed, error.code)
        assertEquals("plain failure", error.message)
        assertSame(cause, error.cause)
    }

    @Test
    public fun successUsesByteArrayContentEquality() {
        val left = TransformResult.Success(byteArrayOf(1, 2, 3), "image/jpeg", 3)
        val right = TransformResult.Success(byteArrayOf(1, 2, 3), "image/jpeg", 3)
        val different = TransformResult.Success(byteArrayOf(1, 2, 4), "image/jpeg", 3)

        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
        assertNotEquals(left, different)
        assertArrayEquals(byteArrayOf(1, 2, 3), left.bytes)
    }

    private fun assertPathSegment(
        index: Int,
        format: TransformRequest.Format,
        expected: String,
    ) {
        val uri = ImageShareTransform.buildUri(request(format = format))
        assertEquals(expected, uri.pathSegments[index])
    }

    private fun assertResizePath(resize: TransformRequest.Resize, expected: String) {
        val uri = ImageShareTransform.buildUri(request(resize = resize))
        assertEquals(expected, uri.pathSegments[3])
    }

    private fun assertMetadataPath(metadata: TransformRequest.Metadata, expected: String) {
        val uri = ImageShareTransform.buildUri(request(metadata = metadata))
        assertEquals(expected, uri.pathSegments[4])
    }

    private fun assertErrorCode(code: String, expected: TransformResult.ErrorCode) {
        val cause = FileNotFoundException("ImageShareTransform: $code: readable message")
        val error = ImageShareTransform.mapFileNotFoundToException(cause)

        assertEquals(expected, error.code)
        assertEquals("readable message", error.message)
        assertSame(cause, error.cause)
    }

    @Suppress("LongParameterList")
    private fun request(
        source: Uri = SOURCE,
        format: TransformRequest.Format = TransformRequest.Format.Jpeg,
        quality: TransformRequest.Quality = TransformRequest.Quality.Fixed(85),
        resize: TransformRequest.Resize = TransformRequest.Resize.Original,
        metadata: TransformRequest.Metadata = TransformRequest.Metadata.StripAll,
        targetBytes: Long? = null,
        aspectLock: Boolean = true,
    ): TransformRequest = TransformRequest(
        source = source,
        format = format,
        quality = quality,
        resize = resize,
        metadata = metadata,
        targetBytes = targetBytes,
        aspectLock = aspectLock,
    )

    private companion object {
        private val SOURCE: Uri = Uri.parse("content://source.app/image/1")
    }
}
