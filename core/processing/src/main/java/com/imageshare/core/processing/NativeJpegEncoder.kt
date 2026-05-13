package com.imageshare.core.processing

import android.graphics.Bitmap
import android.util.Log

object NativeJpegEncoder {
    private const val TAG = "NativeJpegEncoder"

    private val available: Boolean by lazy {
        if (!BuildConfig.ENABLE_NATIVE_JPEG) {
            false
        } else {
            loadLibrarySafely()
        }
    }

    fun isAvailable(): Boolean = available

    @Suppress("TooGenericExceptionCaught")
    fun encode(bitmap: Bitmap, quality: Int): ByteArray? {
        require(quality in MIN_QUALITY..MAX_QUALITY) { "quality must be 1..100" }
        if (!isAvailable() || bitmap.config != Bitmap.Config.ARGB_8888) return null
        return try {
            nativeEncode(bitmap, quality)
        } catch (error: Throwable) {
            Log.w(TAG, "native encode failed; caller will fall back", error)
            null
        }
    }

    private fun loadLibrarySafely(): Boolean =
        try {
            System.loadLibrary("imageshare-jpeg")
            true
        } catch (error: UnsatisfiedLinkError) {
            Log.w(TAG, "libimageshare-jpeg not loaded; falling back to platform JPEG encoder", error)
            false
        } catch (error: SecurityException) {
            Log.w(TAG, "library load denied; falling back to platform JPEG encoder", error)
            false
        }

    @JvmStatic
    private external fun nativeEncode(bitmap: Bitmap, quality: Int): ByteArray?

    private const val MIN_QUALITY = 1
    private const val MAX_QUALITY = 100
}
