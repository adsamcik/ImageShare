package com.imageshare.core.processing

/**
 * Controls how alpha is handled during encoding. [FillBackground] blends alpha
 * onto an opaque background only for JPEG; for PNG and WebP it is treated the
 * same as [Allow] so transparency is preserved.
 */
sealed class AlphaPolicy {
    data object Error : AlphaPolicy()
    data class FillBackground(val argb: Int) : AlphaPolicy()
    data object Allow : AlphaPolicy()
}
