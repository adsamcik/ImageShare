@file:Suppress("ReturnCount", "TooGenericExceptionCaught", "MaxLineLength")

package com.imageshare.app.processing

import android.content.ContentResolver
import com.imageshare.core.io.OutputStore
import com.imageshare.core.io.SourceItem
import com.imageshare.core.processing.AlphaPolicy
import com.imageshare.core.processing.DecodedImage
import com.imageshare.core.processing.Decoder
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.core.processing.EncodeResult
import com.imageshare.core.processing.Encoder
import com.imageshare.core.processing.MetadataApplier
import com.imageshare.core.processing.MetadataMode
import com.imageshare.core.processing.MetadataSource
import com.imageshare.core.processing.Resizer
import com.imageshare.core.processing.TargetSizeEncoder
import com.imageshare.feature.preset.AlphaFallback
import com.imageshare.feature.preset.MetadataPolicy
import com.imageshare.feature.preset.OutputFormat
import com.imageshare.feature.preset.Preset
import com.imageshare.feature.preset.ResizeMode
import kotlin.math.max

class PresetPipeline(
    private val resolver: ContentResolver,
    private val outputStore: OutputStore,
) : PresetPipelineRunner {
    sealed class Step {
        data object Decoding : Step()
        data object Resizing : Step()
        data object Encoding : Step()
        data object ApplyingMetadata : Step()
        data object Storing : Step()
    }

    sealed class Result {
        data class Success(
            val stored: OutputStore.StoredItem,
            val before: SourceItem,
            val finalWidth: Int,
            val finalHeight: Int,
            val format: EncodeFormat,
        ) : Result()

        data class Failure(val before: SourceItem, val cause: Throwable, val step: Step) : Result()
    }

    override suspend fun run(
        source: SourceItem,
        preset: Preset,
        jobId: String,
        onProgress: (Step) -> Unit,
    ): Result {
        val decoded = decode(source, preset, onProgress) ?: return failedResult
        val bitmap = resize(source, preset.resize, decoded, onProgress) ?: return failedResult
        val encoded = encode(source, preset, decoded.hadAlpha, bitmap, onProgress) ?: return failedResult
        val finalBytes = applyMetadata(source, preset.metadata, encoded, onProgress) ?: return failedResult
        return store(source, jobId, encoded, finalBytes, onProgress) ?: return failedResult
    }

    private lateinit var failedResult: Result.Failure

    private suspend fun decode(
        source: SourceItem,
        preset: Preset,
        onProgress: (Step) -> Unit,
    ): DecodedImage? = runStep(source, Step.Decoding, onProgress) {
        Decoder(resolver).decode(source.uri, targetLongEdgePx = resolveLongEdge(preset.resize, source))
    }

    private fun resize(
        source: SourceItem,
        resize: ResizeMode,
        decoded: DecodedImage,
        onProgress: (Step) -> Unit,
    ) = runStep(source, Step.Resizing, onProgress) {
        when (resize) {
            is ResizeMode.Exact -> Resizer().toExact(
                decoded.bitmap,
                resize.width,
                resize.height,
                Resizer.ExactMode.Stretch,
                recycleSrc = true,
            )
            is ResizeMode.Percentage -> Resizer().toLongEdge(
                decoded.bitmap,
                resolveLongEdge(resize, source),
                recycleSrc = true,
            )
            is ResizeMode.LongEdge,
            ResizeMode.Original,
            -> decoded.bitmap
        }
    }

    private suspend fun encode(
        source: SourceItem,
        preset: Preset,
        hadAlpha: Boolean,
        bitmap: android.graphics.Bitmap,
        onProgress: (Step) -> Unit,
    ): EncodeResult? = runStep(source, Step.Encoding, onProgress) {
        val format = resolveEncodeFormat(preset.format, preset.alphaFallback, hadAlpha)
        val alphaPolicy = resolveAlphaPolicy(preset.alphaFallback, format)
        val targetSizeBytes = preset.targetSizeBytes
        if (targetSizeBytes == null) {
            Encoder().encode(
                bitmap = bitmap,
                format = format,
                quality = preset.quality,
                alphaPolicy = alphaPolicy,
            )
        } else {
            TargetSizeEncoder().encodeToTarget(
                bitmap = bitmap,
                config = TargetSizeEncoder.Config(
                    format = format,
                    targetBytes = targetSizeBytes,
                    qualityStart = preset.quality.coerceIn(TARGET_QUALITY_MIN, TARGET_QUALITY_MAX),
                    alphaPolicy = alphaPolicy,
                ),
            ).let { result ->
                EncodeResult(result.bytes, result.width, result.height, result.format, result.qualityUsed)
            }
        }
    }

    private suspend fun applyMetadata(
        source: SourceItem,
        metadata: MetadataPolicy,
        encoded: EncodeResult,
        onProgress: (Step) -> Unit,
    ): ByteArray? = runStep(source, Step.ApplyingMetadata, onProgress) {
        MetadataApplier(resolver).apply(
            encoded = encoded.bytes,
            format = encoded.format,
            mode = metadata.toMetadataMode(),
            source = MetadataSource(originalBytes = null, originalUri = source.uri),
        )
    }

    private suspend fun store(
        source: SourceItem,
        jobId: String,
        encoded: EncodeResult,
        bytes: ByteArray,
        onProgress: (Step) -> Unit,
    ): Result.Success? = runStep(source, Step.Storing, onProgress) {
        val stored = outputStore.store(
            jobId = jobId,
            filename = outputFilename(source.displayName, jobId, encoded.format),
            bytes = bytes,
            mimeType = mimeForFormat(encoded.format),
        )
        Result.Success(stored, source, encoded.width, encoded.height, encoded.format)
    }

    private inline fun <T> runStep(
        source: SourceItem,
        step: Step,
        onProgress: (Step) -> Unit,
        block: () -> T,
    ): T? = try {
        onProgress(step)
        block()
    } catch (throwable: Throwable) {
        failedResult = Result.Failure(source, throwable, step)
        null
    }
}

interface PresetPipelineRunner {
    suspend fun run(
        source: SourceItem,
        preset: Preset,
        jobId: String,
        onProgress: (PresetPipeline.Step) -> Unit = {},
    ): PresetPipeline.Result
}

internal fun resolveLongEdge(resize: ResizeMode, source: SourceItem): Int {
    val fallbackLongEdge = max(source.width ?: DEFAULT_LONG_EDGE_PX, source.height ?: DEFAULT_LONG_EDGE_PX)
    val requested = when (resize) {
        is ResizeMode.LongEdge -> resize.pixels
        is ResizeMode.Exact -> max(resize.width, resize.height)
        is ResizeMode.Percentage -> (fallbackLongEdge.toLong() * resize.pct / PERCENT_DENOMINATOR).toInt()
        ResizeMode.Original -> fallbackLongEdge
    }
    return requested.coerceAtLeast(MIN_LONG_EDGE_PX)
}

internal fun resolveEncodeFormat(
    format: OutputFormat,
    alphaFallback: AlphaFallback,
    hadAlpha: Boolean,
): EncodeFormat = if (hadAlpha && alphaFallback == AlphaFallback.SwitchToPng && format != OutputFormat.PNG) {
    EncodeFormat.PNG
} else {
    format.toEncodeFormat()
}

internal fun resolveAlphaPolicy(alphaFallback: AlphaFallback, format: EncodeFormat): AlphaPolicy = when (alphaFallback) {
    AlphaFallback.Error -> AlphaPolicy.Error
    AlphaFallback.FillWhite -> AlphaPolicy.FillBackground(WHITE_ARGB)
    AlphaFallback.SwitchToPng -> when (format) {
        EncodeFormat.PNG,
        EncodeFormat.WEBP_LOSSY,
        EncodeFormat.WEBP_LOSSLESS,
        EncodeFormat.HEIF,
        EncodeFormat.AVIF,
        -> AlphaPolicy.Allow
        EncodeFormat.JPEG -> AlphaPolicy.FillBackground(WHITE_ARGB)
    }
}

internal fun OutputFormat.toEncodeFormat(): EncodeFormat = when (this) {
    OutputFormat.JPEG -> EncodeFormat.JPEG
    OutputFormat.PNG -> EncodeFormat.PNG
    OutputFormat.WEBP_LOSSY -> EncodeFormat.WEBP_LOSSY
    OutputFormat.WEBP_LOSSLESS -> EncodeFormat.WEBP_LOSSLESS
}

internal fun MetadataPolicy.toMetadataMode(): MetadataMode = when (this) {
    MetadataPolicy.StripAll -> MetadataMode.StripAll
    MetadataPolicy.PreserveSafe -> MetadataMode.PreserveSafe
    MetadataPolicy.PreserveAll -> MetadataMode.PreserveAll
}

internal fun mimeForFormat(format: EncodeFormat): String = when (format) {
    EncodeFormat.JPEG -> "image/jpeg"
    EncodeFormat.PNG -> "image/png"
    EncodeFormat.WEBP_LOSSY,
    EncodeFormat.WEBP_LOSSLESS,
    -> "image/webp"
    EncodeFormat.HEIF -> "image/heif"
    EncodeFormat.AVIF -> "image/avif"
}

internal fun extensionForFormat(format: EncodeFormat): String = when (format) {
    EncodeFormat.JPEG -> "jpg"
    EncodeFormat.PNG -> "png"
    EncodeFormat.WEBP_LOSSY,
    EncodeFormat.WEBP_LOSSLESS,
    -> "webp"
    EncodeFormat.HEIF -> "heif"
    EncodeFormat.AVIF -> "avif"
}

internal fun outputFilename(displayName: String?, jobId: String, format: EncodeFormat): String {
    val extension = extensionForFormat(format)
    val base = displayName?.trim().orEmpty().ifBlank { "image-$jobId" }
    val dotIndex = base.lastIndexOf('.').takeIf { it > 0 }
    val withoutExtension = dotIndex?.let { base.substring(0, it) } ?: base
    return "$withoutExtension.$extension"
}

private const val DEFAULT_LONG_EDGE_PX = 4096
private const val MIN_LONG_EDGE_PX = 16
private const val PERCENT_DENOMINATOR = 100
private const val WHITE_ARGB = -0x1
private const val TARGET_QUALITY_MIN = 30
private const val TARGET_QUALITY_MAX = 95
