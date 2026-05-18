package com.imageshare.app.transform

import android.net.Uri
import com.imageshare.app.BuildConfig
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.core.processing.MetadataMode

internal object TransformUriParser {
    fun parse(uri: Uri, expectedAuthority: String = TRANSFORM_AUTHORITY): Result<TransformParams> {
        if (uri.scheme != "content" || uri.authority != expectedAuthority) {
            return Result.failure(UnsupportedOperationException("Unsupported transform authority"))
        }

        val segments = uri.pathSegments
        if (segments.firstOrNull() != "v1") {
            return Result.failure(TransformError.UnsupportedVersion("Only v1 transform URIs are supported"))
        }
        if (segments.size != EXPECTED_SEGMENT_COUNT) {
            return Result.failure(TransformError.MalformedUri("Expected /v1/{format}/{quality}/{resize}/{metadata}"))
        }

        val format = parseFormat(segments[FORMAT_INDEX]).getOrElse { return Result.failure(it) }
        val quality = parseQuality(segments[QUALITY_INDEX]).getOrElse { return Result.failure(it) }
        val resize = parseResize(segments[RESIZE_INDEX]).getOrElse { return Result.failure(it) }
        val metadata = parseMetadata(segments[METADATA_INDEX]).getOrElse { return Result.failure(it) }

        val targetBytes = parseTargetBytes(uri.getQueryParameter("targetBytes")).getOrElse { return Result.failure(it) }
        val aspectLockRaw = uri.getQueryParameter("aspectLock")
        if (quality == null && targetBytes == null) {
            return Result.failure(TransformError.MalformedUri("qauto requires targetBytes > 0"))
        }
        if (aspectLockRaw != null && resize !is TransformParams.Resize.Exact) {
            return Result.failure(TransformError.MalformedUri("aspectLock is only valid with exact resize"))
        }
        val aspectLock = parseAspectLock(aspectLockRaw, resize).getOrElse { return Result.failure(it) }

        val checkedQuality = quality?.takeIf { it in MIN_QUALITY..MAX_QUALITY }
            ?: if (quality == null) null else return Result.failure(
                TransformError.MalformedUri("quality must be in 1..100"),
            )
        validateResizeRange(resize).exceptionOrNull()?.let { return Result.failure(it) }

        val sourceRaw = uri.getQueryParameter("source")
            ?: return Result.failure(TransformError.MissingSource("source query parameter is required"))
        val source = Uri.parse(sourceRaw)
        if (source.scheme != "content") {
            return Result.failure(TransformError.MissingSource("source must be a content:// URI"))
        }
        if (source.authority == expectedAuthority) {
            return Result.failure(TransformError.MalformedUri("source must not reference the transform provider"))
        }

        return Result.success(
            TransformParams(
                source = source,
                formatToken = segments[FORMAT_INDEX],
                format = format.encodeFormat,
                mimeType = format.mimeType,
                extension = format.extension,
                quality = checkedQuality,
                targetBytes = targetBytes,
                resize = resize,
                metadata = metadata,
                aspectLock = aspectLock,
            ),
        )
    }

    private fun parseFormat(token: String): Result<FormatSpec> = when (token) {
        "jpeg" -> Result.success(FormatSpec(EncodeFormat.JPEG, "image/jpeg", "jpg"))
        "png" -> Result.success(FormatSpec(EncodeFormat.PNG, "image/png", "png"))
        "webp" -> Result.success(FormatSpec(EncodeFormat.WEBP_LOSSY, "image/webp", "webp"))
        "webplossless" -> Result.success(FormatSpec(EncodeFormat.WEBP_LOSSLESS, "image/webp", "webp"))
        "heif" -> Result.success(FormatSpec(EncodeFormat.HEIF, "image/heif", "heif"))
        "avif" -> Result.success(FormatSpec(EncodeFormat.AVIF, "image/avif", "avif"))
        else -> Result.failure(TransformError.MalformedUri("Unsupported format token: $token"))
    }

    private fun parseQuality(token: String): Result<Int?> = when {
        token == "qauto" -> Result.success(null)
        !token.matches(QualityRegex) -> Result.failure(TransformError.MalformedUri("Malformed quality token: $token"))
        else -> token.drop(1).toIntOrNull()?.let { Result.success(it) }
            ?: Result.failure(TransformError.MalformedUri("quality is out of representable range"))
    }

    private fun parseResize(token: String): Result<TransformParams.Resize> = when {
        token == "original" -> Result.success(TransformParams.Resize.Original)
        token.matches(LongEdgeRegex) -> Result.success(TransformParams.Resize.LongEdge(token.removePrefix("longEdge").toInt()))
        token.matches(ExactRegex) -> {
            val parts = token.removePrefix("exact").split('x')
            Result.success(TransformParams.Resize.Exact(parts[0].toInt(), parts[1].toInt()))
        }
        token.matches(PercentRegex) -> Result.success(TransformParams.Resize.Percent(token.removePrefix("percent").toInt()))
        else -> Result.failure(TransformError.MalformedUri("Malformed resize token: $token"))
    }

    private fun parseMetadata(token: String): Result<MetadataMode> = when (token) {
        "stripall" -> Result.success(MetadataMode.StripAll)
        "preservesafe" -> Result.success(MetadataMode.PreserveSafe)
        "preserveall" -> Result.success(MetadataMode.PreserveAll)
        else -> Result.failure(TransformError.MalformedUri("Malformed metadata token: $token"))
    }

    private fun parseTargetBytes(raw: String?): Result<Long?> {
        if (raw == null) return Result.success(null)
        val value = raw.toLongOrNull()
            ?: return Result.failure(TransformError.MalformedUri("targetBytes must be a positive integer"))
        return when {
            value <= 0 -> Result.failure(TransformError.MalformedUri("targetBytes must be > 0"))
            value > BuildConfig.TRANSFORM_MAX_TARGET_BYTES -> Result.failure(
                TransformError.MalformedUri("targetBytes must be <= ${BuildConfig.TRANSFORM_MAX_TARGET_BYTES}"),
            )
            else -> Result.success(value)
        }
    }

    private fun parseAspectLock(raw: String?, resize: TransformParams.Resize): Result<Boolean> = when (raw) {
        null -> Result.success(resize is TransformParams.Resize.Exact)
        "true" -> Result.success(true)
        "false" -> Result.success(false)
        else -> Result.failure(TransformError.MalformedUri("aspectLock must be true or false"))
    }

    private fun validateResizeRange(resize: TransformParams.Resize): Result<Unit> = when (resize) {
        TransformParams.Resize.Original -> Result.success(Unit)
        is TransformParams.Resize.LongEdge -> if (resize.pixels in 1..MAX_LONG_EDGE) {
            Result.success(Unit)
        } else {
            Result.failure(TransformError.MalformedUri("longEdge must be in 1..32768"))
        }
        is TransformParams.Resize.Exact -> if (
            resize.width > 0 && resize.height > 0 && resize.width.toLong() * resize.height.toLong() <= BuildConfig.TRANSFORM_MAX_PIXELS
        ) {
            Result.success(Unit)
        } else {
            Result.failure(TransformError.MalformedUri("exact dimensions must be positive and <= 200000000 pixels"))
        }
        is TransformParams.Resize.Percent -> if (resize.percent in 1..MAX_PERCENT) {
            Result.success(Unit)
        } else {
            Result.failure(TransformError.MalformedUri("percent must be in 1..200"))
        }
    }

    private data class FormatSpec(val encodeFormat: EncodeFormat, val mimeType: String, val extension: String)

    private const val EXPECTED_SEGMENT_COUNT = 5
    private const val FORMAT_INDEX = 1
    private const val QUALITY_INDEX = 2
    private const val RESIZE_INDEX = 3
    private const val METADATA_INDEX = 4
    private const val MIN_QUALITY = 1
    private const val MAX_QUALITY = 100
    private const val MAX_LONG_EDGE = 32768
    private const val MAX_PERCENT = 200
    private val QualityRegex = Regex("q\\d+")
    private val LongEdgeRegex = Regex("longEdge\\d+")
    private val ExactRegex = Regex("exact\\d+x\\d+")
    private val PercentRegex = Regex("percent\\d+")
}
