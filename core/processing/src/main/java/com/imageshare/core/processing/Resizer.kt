package com.imageshare.core.processing

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

class Resizer {
    fun toLongEdge(src: Bitmap, targetLongEdgePx: Int, recycleSrc: Boolean = false): Bitmap {
        require(targetLongEdgePx > 0) { "targetLongEdgePx must be positive" }

        val longEdge = max(src.width, src.height)
        if (longEdge <= targetLongEdgePx) {
            return src
        }

        val scale = targetLongEdgePx.toFloat() / longEdge.toFloat()
        val targetWidth = (src.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (src.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true).also { resized ->
            if (recycleSrc && resized !== src) {
                src.recycle()
            }
        }
    }

    fun toExact(
        src: Bitmap,
        width: Int,
        height: Int,
        mode: ExactMode,
        recycleSrc: Boolean = false,
    ): Bitmap {
        require(width > 0 && height > 0) { "target dimensions must be positive" }

        val resized = when (mode) {
            ExactMode.Stretch -> Bitmap.createScaledBitmap(src, width, height, true)
            ExactMode.CenterCrop -> centerCrop(src, width, height)
        }
        if (recycleSrc && resized !== src) {
            src.recycle()
        }
        return resized
    }

    private fun centerCrop(src: Bitmap, width: Int, height: Int): Bitmap {
        val sourceRatio = src.width.toFloat() / src.height.toFloat()
        val targetRatio = width.toFloat() / height.toFloat()
        val cropWidth: Int
        val cropHeight: Int

        if (sourceRatio > targetRatio) {
            cropHeight = src.height
            cropWidth = (src.height * targetRatio).roundToInt().coerceIn(1, src.width)
        } else {
            cropWidth = src.width
            cropHeight = (src.width / targetRatio).roundToInt().coerceIn(1, src.height)
        }

        val x = ((src.width - cropWidth) / 2).coerceAtLeast(0)
        val y = ((src.height - cropHeight) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(src, x, y, cropWidth, cropHeight)
        return Bitmap.createScaledBitmap(cropped, width, height, true).also { scaled ->
            if (scaled !== cropped) {
                cropped.recycle()
            }
        }
    }

    enum class ExactMode {
        Stretch,
        CenterCrop,
    }
}
