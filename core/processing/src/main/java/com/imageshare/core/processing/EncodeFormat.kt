package com.imageshare.core.processing

enum class EncodeFormat {
    JPEG,
    PNG,
    WEBP_LOSSY,

    /**
     * Encodes as WebP lossless on API 30+. On API 29 Android only exposes the
     * legacy WebP encoder, so this falls back to closest-available WebP at
     * quality 100, which may still be lossy.
     */
    WEBP_LOSSLESS,

    /**
     * HEIF output via AndroidX HeifWriter; requires a device HEIF encoder.
     * Use [HeifAvailability.isWriteSupported] to probe at runtime.
     */
    HEIF,

    /**
     * AVIF output via AndroidX AvifWriter on Android 14+ or native libavif
     * scaffold on older devices when the AVIF beta flag is enabled.
     * Use [AvifAvailability.isAnyWriteSupported] to probe at runtime.
     */
    AVIF,
}
