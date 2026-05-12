package com.imageshare.core.processing

import android.graphics.Bitmap
import java.util.concurrent.CancellationException
import kotlin.math.floor
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TargetSizeEncoder(
    encoder: Encoder = Encoder(),
    private val resizer: Resizer = Resizer(),
) {
    private val encode: suspend (Bitmap, EncodeFormat, Int, AlphaPolicy) -> EncodeResult = encoder::encode

    internal constructor(
        resizer: Resizer = Resizer(),
        encode: suspend (Bitmap, EncodeFormat, Int, AlphaPolicy) -> EncodeResult,
    ) : this(Encoder(), resizer) {
        this.encodeForTesting = encode
    }

    private var encodeForTesting: (suspend (Bitmap, EncodeFormat, Int, AlphaPolicy) -> EncodeResult)? = null

    @Suppress("MagicNumber")
    data class Config(
        val format: EncodeFormat,
        val targetBytes: Long,
        val qualityMin: Int = 30,
        val qualityMax: Int = 95,
        val qualityStart: Int = 80,
        val qualityTolerance: Int = 2,
        val dimensionStepFactor: Double = 0.85,
        val minLongEdgePx: Int = 320,
        val maxIterations: Int = 12,
        val sizeOvershootRatio: Double = 1.0,
        val alphaPolicy: AlphaPolicy = AlphaPolicy.FillBackground(0xFFFFFFFF.toInt()),
    ) {
        init {
            require(targetBytes > 0) { "targetBytes must be > 0" }
            require(qualityMin in 1..100) { "qualityMin must be in 1..100" }
            require(qualityMax in 1..100) { "qualityMax must be in 1..100" }
            require(qualityMin < qualityMax) { "qualityMin must be < qualityMax" }
            require(qualityStart in qualityMin..qualityMax) { "qualityStart must be in [qualityMin, qualityMax]" }
            require(qualityTolerance in 1..50) { "qualityTolerance must be in 1..50" }
            require(dimensionStepFactor in 0.5..0.99) { "dimensionStepFactor must be in [0.5, 0.99]" }
            require(minLongEdgePx >= 64) { "minLongEdgePx must be >= 64" }
            require(maxIterations in 3..50) { "maxIterations must be in [3, 50]" }
            require(sizeOvershootRatio >= 1.0) { "sizeOvershootRatio must be >= 1.0" }
        }
    }

    data class Result(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val format: EncodeFormat,
        val qualityUsed: Int,
        val achievedBytes: Long,
        val targetBytes: Long,
        val attempts: Int,
        val metTarget: Boolean,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Result) return false

            return bytes.contentEquals(other.bytes) &&
                width == other.width &&
                height == other.height &&
                format == other.format &&
                qualityUsed == other.qualityUsed &&
                achievedBytes == other.achievedBytes &&
                targetBytes == other.targetBytes &&
                attempts == other.attempts &&
                metTarget == other.metTarget
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = HASH_MULTIPLIER * result + width
            result = HASH_MULTIPLIER * result + height
            result = HASH_MULTIPLIER * result + format.hashCode()
            result = HASH_MULTIPLIER * result + qualityUsed
            result = HASH_MULTIPLIER * result + achievedBytes.hashCode()
            result = HASH_MULTIPLIER * result + targetBytes.hashCode()
            result = HASH_MULTIPLIER * result + attempts
            result = HASH_MULTIPLIER * result + metTarget.hashCode()
            return result
        }

        private companion object {
            private const val HASH_MULTIPLIER = 31
        }
    }

    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "TooGenericExceptionCaught")
    suspend fun encodeToTarget(bitmap: Bitmap, config: Config): Result = withContext(Dispatchers.IO) {
        var currentBitmap = bitmap
        var attempts = 0
        var smallestResult: EncodedCandidate? = null
        var bestMetResult: EncodedCandidate? = null

        try {
            while (attempts < config.maxIterations) {
                if (config.format.isQualityIgnored()) {
                    val candidate = encodeCandidate(currentBitmap, config, config.qualityMax)
                    attempts += 1
                    smallestResult = smallerOf(smallestResult, candidate)
                    if (candidate.meetsTarget(config)) {
                        bestMetResult = betterMetResult(bestMetResult, candidate)
                        break
                    }
                } else {
                    val search = searchQuality(currentBitmap, config, attempts)
                    attempts = search.attempts
                    smallestResult = smallerOf(smallestResult, search.smallestResult)
                    bestMetResult = betterMetResult(bestMetResult, search.bestMetResult)
                    if (search.bestMetResult != null) {
                        break
                    }
                }

                if (attempts >= config.maxIterations || !canStepDimensions(currentBitmap, config)) {
                    break
                }

                val newLongEdge = nextLongEdge(currentBitmap, config)
                if (newLongEdge == null) {
                    break
                }
                currentBitmap = resizer.toLongEdge(
                    currentBitmap,
                    newLongEdge,
                    recycleSrc = currentBitmap !== bitmap,
                )
            }

            val finalCandidate = bestMetResult ?: smallestResult ?: encodeCandidate(
                currentBitmap,
                config,
                config.qualityStart,
            ).also { attempts += 1 }
            finalCandidate.toResult(config, attempts)
        } catch (error: CancellationException) {
            throw error
        } catch (error: TargetSizeError) {
            throw error
        } catch (error: Exception) {
            throw TargetSizeError.EncodeFailed(error)
        } finally {
            if (currentBitmap !== bitmap && !currentBitmap.isRecycled) {
                currentBitmap.recycle()
            }
        }
    }

    private suspend fun searchQuality(
        bitmap: Bitmap,
        config: Config,
        startingAttempts: Int,
    ): SearchResult {
        var attempts = startingAttempts
        var low = config.qualityMin
        var high = config.qualityMax
        var smallestResult: EncodedCandidate? = null
        var bestMetResult: EncodedCandidate? = null
        val triedQualities = mutableSetOf<Int>()

        fun nextQuality(): Int = if (triedQualities.isEmpty()) {
            config.qualityStart
        } else {
            (low + high + 1) / 2
        }

        while (attempts < config.maxIterations && high - low > config.qualityTolerance) {
            val quality = nextQuality().coerceIn(low, high)
            if (!triedQualities.add(quality)) {
                break
            }

            val candidate = encodeCandidate(bitmap, config, quality)
            attempts += 1
            smallestResult = smallerOf(smallestResult, candidate)
            if (candidate.meetsTarget(config)) {
                bestMetResult = betterMetResult(bestMetResult, candidate)
                low = quality
            } else {
                high = quality - 1
            }
        }

        if (bestMetResult == null && attempts < config.maxIterations && triedQualities.add(config.qualityMin)) {
            val candidate = encodeCandidate(bitmap, config, config.qualityMin)
            attempts += 1
            smallestResult = smallerOf(smallestResult, candidate)
            if (candidate.meetsTarget(config)) {
                bestMetResult = betterMetResult(bestMetResult, candidate)
            }
        }

        return SearchResult(attempts, smallestResult, bestMetResult)
    }

    private suspend fun encodeCandidate(bitmap: Bitmap, config: Config, quality: Int): EncodedCandidate {
        val encoder = encodeForTesting ?: encode
        val result = encoder(bitmap, config.format, quality, config.alphaPolicy)
        return EncodedCandidate(result)
    }

    private fun canStepDimensions(bitmap: Bitmap, config: Config): Boolean =
        max(bitmap.width, bitmap.height) > config.minLongEdgePx

    private fun nextLongEdge(bitmap: Bitmap, config: Config): Int? {
        val currentLongEdge = max(bitmap.width, bitmap.height)
        val steppedLongEdge = floor(currentLongEdge * config.dimensionStepFactor)
            .toInt()
            .coerceAtLeast(MIN_STEP_LONG_EDGE)
        return steppedLongEdge.takeIf { it >= config.minLongEdgePx && it < currentLongEdge }
    }

    private fun smallerOf(current: EncodedCandidate?, candidate: EncodedCandidate?): EncodedCandidate? = when {
        candidate == null -> current
        current == null -> candidate
        candidate.achievedBytes < current.achievedBytes -> candidate
        else -> current
    }

    private fun betterMetResult(current: EncodedCandidate?, candidate: EncodedCandidate?): EncodedCandidate? = when {
        candidate == null -> current
        current == null -> candidate
        candidate.result.quality > current.result.quality -> candidate
        candidate.result.quality == current.result.quality &&
            candidate.achievedBytes < current.achievedBytes -> candidate
        else -> current
    }

    private fun EncodedCandidate.meetsTarget(config: Config): Boolean =
        achievedBytes.toDouble() <= config.targetBytes.toDouble() * config.sizeOvershootRatio

    private fun EncodedCandidate.toResult(config: Config, attempts: Int): Result = Result(
        bytes = result.bytes,
        width = result.width,
        height = result.height,
        format = result.format,
        qualityUsed = result.quality,
        achievedBytes = achievedBytes,
        targetBytes = config.targetBytes,
        attempts = attempts,
        metTarget = meetsTarget(config),
    )

    private fun EncodeFormat.isQualityIgnored(): Boolean =
        this == EncodeFormat.PNG || this == EncodeFormat.WEBP_LOSSLESS

    private data class EncodedCandidate(val result: EncodeResult) {
        val achievedBytes: Long = result.bytes.size.toLong()
    }

    private data class SearchResult(
        val attempts: Int,
        val smallestResult: EncodedCandidate?,
        val bestMetResult: EncodedCandidate?,
    )

    private companion object {
        private const val MIN_STEP_LONG_EDGE = 16
    }
}

sealed class TargetSizeError(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    data class InvalidConfig(override val message: String) : TargetSizeError(message)
    data class EncodeFailed(override val cause: Throwable) : TargetSizeError("Unable to encode to target size", cause)
}
