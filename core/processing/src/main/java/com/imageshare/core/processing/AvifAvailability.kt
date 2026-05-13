package com.imageshare.core.processing

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Runtime probe for AVIF encode capability. Cached after first call.
 */
object AvifAvailability {
    private var cachedPlatformSupport: Boolean? = null

    /**
     * Returns true if the platform has an AVIF encoder. Android 14+ devices
     * should generally expose one; some Android 13 vendor builds may as well.
     */
    fun isPlatformWriteSupported(): Boolean {
        cachedPlatformSupport?.let { return it }
        val supported = probePlatformAvifEncoder()
        cachedPlatformSupport = supported
        return supported
    }

    /**
     * Returns true if either the platform encoder or the beta native scaffold
     * is available.
     */
    fun isAnyWriteSupported(beta: Boolean = false): Boolean =
        isPlatformWriteSupported() || (beta && NativeAvifEncoder.isAvailable())

    private fun probePlatformAvifEncoder(): Boolean =
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val format = MediaFormat.createVideoFormat(MIME_AVIF, PROBE_WIDTH, PROBE_HEIGHT).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, PROBE_BIT_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, PROBE_I_FRAME_INTERVAL)
                setInteger(MediaFormat.KEY_FRAME_RATE, PROBE_FRAME_RATE)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT_YUV_420_FLEXIBLE)
            }
            codecList.findEncoderForFormat(format) != null
        } catch (_: Throwable) {
            false
        }

    /** Visible-for-testing: reset the cache. */
    internal fun resetCache() {
        cachedPlatformSupport = null
    }

    private const val MIME_AVIF = "image/avif"
    private const val COLOR_FORMAT_YUV_420_FLEXIBLE = 0x7F420888
    private const val PROBE_WIDTH = 640
    private const val PROBE_HEIGHT = 480
    private const val PROBE_BIT_RATE = 1_000_000
    private const val PROBE_I_FRAME_INTERVAL = 1
    private const val PROBE_FRAME_RATE = 30
}
