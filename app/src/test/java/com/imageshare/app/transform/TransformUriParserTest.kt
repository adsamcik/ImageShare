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

    @Test fun qualityBoundaryQ1Accepted() = assertEquals(1, parseOk(quality = "q1").quality)
    @Test fun qualityBoundaryQ100Accepted() = assertEquals(100, parseOk(quality = "q100").quality)
    @Test fun qualityZeroRejected() = assertFailure<TransformError.MalformedUri>(uri(quality = "q0"))
    @Test fun qualityNegativeRejected() = assertFailure<TransformError.MalformedUri>(uri(quality = "q-5"))
    @Test fun qualityOverflowRejected() = assertFailure<TransformError.MalformedUri>(uri(quality = "q9999999999"))

    @Test fun longEdgeOneAccepted() = assertEquals(TransformParams.Resize.LongEdge(1), parseOk(resize = "longEdge1").resize)
    @Test fun longEdgeMaxAccepted() = assertEquals(TransformParams.Resize.LongEdge(32768), parseOk(resize = "longEdge32768").resize)
    @Test fun longEdgeOverMaxRejected() = assertFailure<TransformError.MalformedUri>(uri(resize = "longEdge32769"))
    @Test fun exactOneXOneAccepted() = assertEquals(TransformParams.Resize.Exact(1, 1), parseOk(resize = "exact1x1").resize)
    @Test fun exactZeroXOneRejected() = assertFailure<TransformError.MalformedUri>(uri(resize = "exact0x1"))
    @Test fun exactOverPixelBudgetRejected() = assertFailure<TransformError.MalformedUri>(uri(resize = "exact20000x20000"))
    @Test fun percentOneAccepted() = assertEquals(TransformParams.Resize.Percent(1), parseOk(resize = "percent1").resize)
    @Test fun percentMaxAccepted() = assertEquals(TransformParams.Resize.Percent(200), parseOk(resize = "percent200").resize)
    @Test fun percentOverMaxRejected() = assertFailure<TransformError.MalformedUri>(uri(resize = "percent201"))

    @Test fun targetBytesOneAccepted() = assertEquals(1L, parseOk(query = mapOf("targetBytes" to "1")).targetBytes)
    @Test fun targetBytesUpperBoundAccepted() =
        assertEquals(BuildConfig.TRANSFORM_MAX_TARGET_BYTES, parseOk(query = mapOf("targetBytes" to BuildConfig.TRANSFORM_MAX_TARGET_BYTES.toString())).targetBytes)
    @Test fun targetBytesOverUpperBoundRejected() =
        assertFailure<TransformError.MalformedUri>(uri(query = mapOf("targetBytes" to (BuildConfig.TRANSFORM_MAX_TARGET_BYTES + 1).toString())))
    @Test fun targetBytesZeroRejected() = assertFailure<TransformError.MalformedUri>(uri(query = mapOf("targetBytes" to "0")))
    @Test fun targetBytesNegativeRejected() = assertFailure<TransformError.MalformedUri>(uri(query = mapOf("targetBytes" to "-1")))
    @Test fun targetBytesNonNumericRejected() = assertFailure<TransformError.MalformedUri>(uri(query = mapOf("targetBytes" to "abc")))

    @Test fun aspectLockTrueWithLongEdgeRejected() =
        assertFailure<TransformError.MalformedUri>(uri(resize = "longEdge100", query = mapOf("aspectLock" to "true")))
    @Test fun aspectLockFalseWithExactAccepted() = assertFalse(parseOk(resize = "exact8x4", query = mapOf("aspectLock" to "false")).aspectLock)
    @Test fun aspectLockTrueWithExactAccepted() = assertTrue(parseOk(resize = "exact8x4", query = mapOf("aspectLock" to "true")).aspectLock)
    @Test fun aspectLockMalformedRejected() =
        assertFailure<TransformError.MalformedUri>(uri(resize = "exact8x4", query = mapOf("aspectLock" to "maybe")))

    @Test fun sourceMissingRejected() = assertFailure<TransformError.MissingSource>(uri(includeSource = false))
    @Test fun sourceEmptyRejected() = assertFailure<TransformError.MissingSource>(uri(source = ""))
    @Test fun sourceNonContentSchemeRejected() = assertFailure<TransformError.MissingSource>(uri(source = "https://example.test/image.jpg"))
    @Test fun sourceSelfAuthorityRejected() =
        assertFailure<TransformError.MalformedUri>(uri(source = "content://com.imageshare.app.transform/v1/jpeg/q85/original/stripall"))
    @Test fun sourceWithFragmentAndQueryPreserved() {
        val source = "content://source.app/images/1?token=a%2Fb&name=summer#frag"
        assertEquals(Uri.parse(source), parseOk(source = source).source)
    }

    @Test fun unsupportedVersionV0Rejected() = assertFailure<TransformError.UnsupportedVersion>(uri(version = "v0"))
    @Test fun unsupportedVersionV2Rejected() = assertFailure<TransformError.UnsupportedVersion>(uri(version = "v2"))
    @Test fun missingPathSegmentsRejected() = assertFailure<TransformError.MalformedUri>(uri(dropMetadata = true))
    @Test fun tooManyPathSegmentsRejected() = assertFailure<TransformError.MalformedUri>(uri(extraPathSegments = listOf("extra")))

    @Test fun qautoWithoutTargetBytesRejected() = assertFailure<TransformError.MalformedUri>(uri(quality = "qauto"))
    @Test fun qautoWithTargetBytesAccepted() {
        val params = parseOk(quality = "qauto", query = mapOf("targetBytes" to "4096"))
        assertNull(params.quality)
        assertEquals(4096L, params.targetBytes)
    }
    @Test fun qNumericIgnoresTargetBytes() {
        val params = parseOk(quality = "q85", query = mapOf("targetBytes" to "4096"))
        assertEquals(85, params.quality)
        assertEquals(4096L, params.targetBytes)
    }

    private fun parseOk(
        format: String = "jpeg",
        quality: String = "q85",
        resize: String = "original",
        metadata: String = "stripall",
        source: String = "content://source.app/image/1",
        query: Map<String, String> = emptyMap(),
    ): TransformParams = TransformUriParser.parse(uri(format = format, quality = quality, resize = resize, metadata = metadata, source = source, query = query))
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
        extraPathSegments: List<String> = emptyList(),
        query: Map<String, String> = emptyMap(),
    ): Uri {
        val builder = Uri.Builder().scheme(scheme).authority(authority)
            .appendPath(version)
            .appendPath(format)
            .appendPath(quality)
            .appendPath(resize)
        if (!dropMetadata) builder.appendPath(metadata)
        extraPathSegments.forEach { builder.appendPath(it) }
        if (includeSource) builder.appendQueryParameter("source", source)
        query.forEach { (key, value) -> builder.appendQueryParameter(key, value) }
        return builder.build()
    }
}
