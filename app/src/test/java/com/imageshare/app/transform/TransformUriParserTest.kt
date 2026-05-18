package com.imageshare.app.transform

import android.net.Uri
import com.imageshare.app.BuildConfig
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.core.processing.MetadataMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransformUriParserTest {
    @Test fun parsesMinimalJpeg() {
        val params = parseOk()
        assertEquals(EncodeFormat.JPEG, params.format)
        assertEquals("image/jpeg", params.mimeType)
        assertEquals(85, params.quality)
        assertEquals(MetadataMode.StripAll, params.metadata)
        assertFalse(params.aspectLock)
    }

    @Test fun parsesPng() = assertEquals(EncodeFormat.PNG, parseOk(format = "png").format)
    @Test fun parsesWebpLossy() = assertEquals(EncodeFormat.WEBP_LOSSY, parseOk(format = "webp").format)
    @Test fun parsesWebpLossless() = assertEquals(EncodeFormat.WEBP_LOSSLESS, parseOk(format = "webplossless").format)
    @Test fun parsesHeif() = assertEquals(EncodeFormat.HEIF, parseOk(format = "heif").format)
    @Test fun parsesAvif() = assertEquals(EncodeFormat.AVIF, parseOk(format = "avif").format)
    @Test fun parsesQautoWithTargetBytes() {
        val params = parseOk(quality = "qauto", query = mapOf("targetBytes" to "1024"))
        assertNull(params.quality)
        assertEquals(1024L, params.targetBytes)
    }
    @Test fun parsesTargetBytesUpperBound() {
        val params = parseOk(quality = "qauto", query = mapOf("targetBytes" to BuildConfig.TRANSFORM_MAX_TARGET_BYTES.toString()))
        assertEquals(BuildConfig.TRANSFORM_MAX_TARGET_BYTES, params.targetBytes)
        assertFailure<TransformError.MalformedUri>(
            uri(quality = "qauto", query = mapOf("targetBytes" to (BuildConfig.TRANSFORM_MAX_TARGET_BYTES + 1).toString())),
        )
    }
    @Test fun parsesLongEdgeResize() = assertEquals(TransformParams.Resize.LongEdge(1600), parseOk(resize = "longEdge1600").resize)
    @Test fun parsesExactResizeDefaultAspectLock() {
        val params = parseOk(resize = "exact640x480")
        assertEquals(TransformParams.Resize.Exact(640, 480), params.resize)
        assertTrue(params.aspectLock)
    }
    @Test fun parsesExactResizeStretch() = assertFalse(parseOk(resize = "exact640x480", query = mapOf("aspectLock" to "false")).aspectLock)
    @Test fun parsesPercentResize() = assertEquals(TransformParams.Resize.Percent(50), parseOk(resize = "percent50").resize)
    @Test fun nonExactResizeDefaultsAspectLockFalse() = assertFalse(parseOk(resize = "longEdge1600").aspectLock)
    @Test fun parsesPreserveSafe() = assertEquals(MetadataMode.PreserveSafe, parseOk(metadata = "preservesafe").metadata)
    @Test fun parsesPreserveAll() = assertEquals(MetadataMode.PreserveAll, parseOk(metadata = "preserveall").metadata)

    @Test fun wrongAuthorityIsUnsupportedOperation() = assertFailure<UnsupportedOperationException>(uri(authority = "other.authority"))
    @Test fun wrongSchemeIsUnsupportedOperation() = assertFailure<UnsupportedOperationException>(uri(scheme = "https"))
    @Test fun unsupportedVersionWinsBeforePathArity() = assertFailure<TransformError.UnsupportedVersion>(uri(version = "v2", dropMetadata = true))
    @Test fun pathArityWinsBeforeTokenGrammar() = assertFailure<TransformError.MalformedUri>(uri(dropMetadata = true, format = "bad"))
    @Test fun badFormatIsMalformed() = assertFailure<TransformError.MalformedUri>(uri(format = "gif"))
    @Test fun badQualityGrammarIsMalformed() = assertFailure<TransformError.MalformedUri>(uri(quality = "85"))
    @Test fun qautoWithoutTargetBytesIsMalformedBeforeSourceCheck() = assertFailure<TransformError.MalformedUri>(uri(quality = "qauto", includeSource = false))
    @Test fun aspectLockWithoutExactIsMalformed() = assertFailure<TransformError.MalformedUri>(uri(query = mapOf("aspectLock" to "false")))
    @Test fun invalidAspectLockValueIsMalformed() = assertFailure<TransformError.MalformedUri>(uri(resize = "exact1x1", query = mapOf("aspectLock" to "maybe")))
    @Test fun qualityZeroIsMalformedRange() = assertFailure<TransformError.MalformedUri>(uri(quality = "q0"))
    @Test fun qualityAboveHundredIsMalformedRange() = assertFailure<TransformError.MalformedUri>(uri(quality = "q101"))
    @Test fun qualityOverflowIsMalformedRange() = assertFailure<TransformError.MalformedUri>(uri(quality = "q999999999999999999999"))
    @Test fun longEdgeAboveLimitIsMalformedRange() = assertFailure<TransformError.MalformedUri>(uri(resize = "longEdge32769"))
    @Test fun exactAbovePixelLimitIsMalformedRange() = assertFailure<TransformError.MalformedUri>(uri(resize = "exact200000001x1"))
    @Test fun percentZeroIsMalformedRange() = assertFailure<TransformError.MalformedUri>(uri(resize = "percent0"))
    @Test fun percentAboveTwoHundredIsMalformedRange() = assertFailure<TransformError.MalformedUri>(uri(resize = "percent201"))
    @Test fun missingSourceAfterRangeChecks() = assertFailure<TransformError.MissingSource>(uri(includeSource = false))
    @Test fun fileSourceIsMissingSource() = assertFailure<TransformError.MissingSource>(uri(source = "file:///sdcard/a.jpg"))
    @Test fun selfReferenceSourceIsMalformed() = assertFailure<TransformError.MalformedUri>(uri(source = "content://com.imageshare.app.transform/v1/jpeg/q80/original/stripall"))

    private fun parseOk(
        format: String = "jpeg",
        quality: String = "q85",
        resize: String = "original",
        metadata: String = "stripall",
        query: Map<String, String> = emptyMap(),
    ): TransformParams = TransformUriParser.parse(uri(format = format, quality = quality, resize = resize, metadata = metadata, query = query))
        .getOrThrow()

    private inline fun <reified T : Throwable> assertFailure(uri: Uri) {
        val error = TransformUriParser.parse(uri).exceptionOrNull()
        assertTrue("Expected ${T::class.java.name}, got $error", error is T)
    }

    private fun uri(
        scheme: String = "content",
        authority: String = "com.imageshare.app.transform",
        version: String = "v1",
        format: String = "jpeg",
        quality: String = "q85",
        resize: String = "original",
        metadata: String = "stripall",
        source: String = "content://source.app/image/1",
        includeSource: Boolean = true,
        dropMetadata: Boolean = false,
        query: Map<String, String> = emptyMap(),
    ): Uri {
        val builder = Uri.Builder().scheme(scheme).authority(authority)
            .appendPath(version)
            .appendPath(format)
            .appendPath(quality)
            .appendPath(resize)
        if (!dropMetadata) builder.appendPath(metadata)
        if (includeSource) builder.appendQueryParameter("source", source)
        query.forEach { (key, value) -> builder.appendQueryParameter(key, value) }
        return builder.build()
    }
}
