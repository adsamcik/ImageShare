@file:Suppress("MagicNumber", "TooManyFunctions")

package com.imageshare.benchmark.micro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File

internal object BenchmarkFixtures {
    val context: Context
        get() = ApplicationProvider.getApplicationContext()

    fun photoBitmap(width: Int, height: Int, alpha: Boolean = false): Bitmap {
        val config = Bitmap.Config.ARGB_8888
        val bitmap = Bitmap.createBitmap(width, height, config)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                intArrayOf(Color.rgb(34, 87, 122), Color.rgb(255, 196, 61), Color.rgb(217, 64, 64)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        repeat(24) { index ->
            paint.color = Color.argb(
                if (alpha) 96 + (index * 5) % 128 else 255,
                (index * 37) % 255,
                (80 + index * 19) % 255,
                (160 + index * 11) % 255,
            )
            canvas.drawCircle(
                ((index + 1) * width / 25f),
                ((index % 8) + 1) * height / 9f,
                (width.coerceAtMost(height) / 18f),
                paint,
            )
        }
        bitmap.setHasAlpha(alpha)
        return bitmap
    }

    fun jpegBytes(width: Int, height: Int, quality: Int = 90): ByteArray =
        compressedBytes(photoBitmap(width, height), Bitmap.CompressFormat.JPEG, quality)

    fun pngBytes(width: Int, height: Int, alpha: Boolean): ByteArray =
        compressedBytes(photoBitmap(width, height, alpha), Bitmap.CompressFormat.PNG, 100)

    @Suppress("DEPRECATION")
    fun webpBytes(width: Int, height: Int, quality: Int = 90): ByteArray = compressedBytes(
        photoBitmap(width, height),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        },
        quality,
    )

    fun fullExifJpeg1024(): ByteArray {
        val file = writeTempFile(jpegBytes(1024, 768), "full-exif", ".jpg")
        try {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_MAKE, "ImageShareBench")
                setAttribute(ExifInterface.TAG_MODEL, "SyntheticCamera")
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:01:02 03:04:05")
                setAttribute(ExifInterface.TAG_F_NUMBER, "2.8")
                setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "1/125")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "37/1,25/1,1951/100")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "122/1,5/1,669/100")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    fun writeTempFile(bytes: ByteArray, prefix: String, suffix: String): File =
        File.createTempFile(prefix, suffix, context.cacheDir).apply { writeBytes(bytes) }

    fun uriFor(bytes: ByteArray, prefix: String, suffix: String): Pair<Uri, File> {
        val file = writeTempFile(bytes, prefix, suffix)
        return Uri.fromFile(file) to file
    }

    private fun compressedBytes(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray =
        try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(format, quality, output)) { "Bitmap.compress failed" }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
}
