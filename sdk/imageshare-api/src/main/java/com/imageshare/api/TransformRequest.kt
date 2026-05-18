package com.imageshare.api

import android.net.Uri

public data class TransformRequest(
    public val source: Uri,
    public val format: Format,
    public val quality: Quality = Quality.Auto,
    public val resize: Resize = Resize.Original,
    public val metadata: Metadata = Metadata.StripAll,
    public val targetBytes: Long? = null,
    public val aspectLock: Boolean = true,
) {
    init {
        require(source.authority != ImageShareTransform.TRANSFORM_AUTHORITY) {
            "source must not reference the Transform API itself"
        }
        if (quality is Quality.Auto) {
            require(targetBytes != null && targetBytes > 0) {
                "Quality.Auto requires targetBytes > 0"
            }
        }
        if (targetBytes != null) {
            require(targetBytes in MIN_TARGET_BYTES..MAX_TARGET_BYTES) {
                "targetBytes must be in 1..104857600"
            }
        }
        require(aspectLock || resize is Resize.Exact) {
            "aspectLock=false is only valid with exact resize"
        }
    }

    public enum class Format(
        internal val token: String,
        public val mimeType: String,
        public val extension: String,
    ) {
        Jpeg("jpeg", "image/jpeg", "jpg"),
        Png("png", "image/png", "png"),
        WebpLossy("webp", "image/webp", "webp"),
        WebpLossless("webplossless", "image/webp", "webp"),
        Heif("heif", "image/heif", "heif"),
        Avif("avif", "image/avif", "avif"),
    }

    public sealed class Quality {
        public data object Auto : Quality()

        public data class Fixed(public val value: Int) : Quality() {
            init {
                require(value in MIN_QUALITY..MAX_QUALITY) {
                    "quality must be in 1..100"
                }
            }
        }
    }

    public sealed class Resize {
        public data object Original : Resize()

        public data class LongEdge(public val pixels: Int) : Resize() {
            init {
                require(pixels in MIN_DIMENSION..MAX_DIMENSION) {
                    "longEdge pixels must be in 1..32768"
                }
            }
        }

        public data class Exact(public val width: Int, public val height: Int) : Resize() {
            init {
                require(width in MIN_DIMENSION..MAX_DIMENSION) {
                    "width must be in 1..32768"
                }
                require(height in MIN_DIMENSION..MAX_DIMENSION) {
                    "height must be in 1..32768"
                }
                require(width.toLong() * height.toLong() <= MAX_PIXELS) {
                    "exact resize area must be <= 200000000 pixels"
                }
            }
        }

        public data class Percent(public val percent: Int) : Resize() {
            init {
                require(percent in MIN_PERCENT..MAX_PERCENT) {
                    "percent must be in 1..200"
                }
            }
        }
    }

    public enum class Metadata(internal val token: String) {
        StripAll("stripall"),
        PreserveSafe("preservesafe"),
        PreserveAll("preserveall"),
    }

    @Suppress("TooManyFunctions")
    public class Builder(public var source: Uri) {
        public var format: Format = Format.Jpeg
        public var quality: Quality = Quality.Fixed(DEFAULT_BUILDER_QUALITY)
        public var resize: Resize = Resize.Original
        public var metadata: Metadata = Metadata.StripAll
        public var targetBytes: Long? = null
        public var aspectLock: Boolean = true

        public fun source(source: Uri): Builder = apply {
            this.source = source
        }

        public fun format(format: Format): Builder = apply {
            this.format = format
        }

        public fun quality(quality: Quality): Builder = apply {
            this.quality = quality
        }

        public fun fixedQuality(value: Int): Builder = quality(Quality.Fixed(value))

        public fun autoQuality(targetBytes: Long): Builder = apply {
            this.quality = Quality.Auto
            this.targetBytes = targetBytes
        }

        public fun resize(resize: Resize): Builder = apply {
            this.resize = resize
        }

        public fun originalSize(): Builder = resize(Resize.Original)

        public fun longEdge(pixels: Int): Builder = resize(Resize.LongEdge(pixels))

        public fun exactSize(width: Int, height: Int): Builder = resize(Resize.Exact(width, height))

        public fun percent(percent: Int): Builder = resize(Resize.Percent(percent))

        public fun metadata(metadata: Metadata): Builder = apply {
            this.metadata = metadata
        }

        public fun targetBytes(targetBytes: Long?): Builder = apply {
            this.targetBytes = targetBytes
        }

        public fun aspectLock(aspectLock: Boolean): Builder = apply {
            this.aspectLock = aspectLock
        }

        public fun build(): TransformRequest = TransformRequest(
            source = source,
            format = format,
            quality = quality,
            resize = resize,
            metadata = metadata,
            targetBytes = targetBytes,
            aspectLock = aspectLock,
        )
    }

    public companion object {
        private const val MIN_QUALITY = 1
        private const val MAX_QUALITY = 100
        private const val MIN_DIMENSION = 1
        private const val MAX_DIMENSION = 32768
        private const val MAX_PIXELS = 200_000_000L
        private const val MIN_PERCENT = 1
        private const val MAX_PERCENT = 200
        private const val MIN_TARGET_BYTES = 1L
        private const val MAX_TARGET_BYTES = 104_857_600L
        private const val DEFAULT_BUILDER_QUALITY = 85

        public fun builder(source: Uri): Builder = Builder(source)
    }
}
