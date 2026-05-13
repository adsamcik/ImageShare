package com.imageshare.core.processing

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Runtime probe for HEIF encode capability. Cached after first call.
 * Returns true ONLY if the device has an actual HEIF encoder available.
 */
object HeifAvailability {
    private var cachedSupport: Boolean? = null

    fun isWriteSupported(): Boolean {
        cachedSupport?.let { return it }
        val supported = probeHeifEncoder()
        cachedSupport = supported
        return supported
    }

    private fun probeHeifEncoder(): Boolean =
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val format = MediaFormat.createVideoFormat(HEIF_MIME_TYPE, PROBE_WIDTH, PROBE_HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT_YUV_420_FLEXIBLE)
                setInteger(MediaFormat.KEY_BIT_RATE, PROBE_BIT_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, PROBE_I_FRAME_INTERVAL)
                setInteger(MediaFormat.KEY_FRAME_RATE, PROBE_FRAME_RATE)
            }
            codecList.findEncoderForFormat(format) != null
        } catch (_: Throwable) {
            false
        }

    /** Visible-for-testing: reset the cache. */
    internal fun resetCache() {
        cachedSupport = null
    }

    private const val HEIF_MIME_TYPE = "image/vnd.android.heic"
    private const val COLOR_FORMAT_YUV_420_FLEXIBLE = 0x7F420888
    private const val PROBE_WIDTH = 640
    private const val PROBE_HEIGHT = 480
    private const val PROBE_BIT_RATE = 1_000_000
    private const val PROBE_I_FRAME_INTERVAL = 1
    private const val PROBE_FRAME_RATE = 30
}
