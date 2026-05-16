package com.imageshare.core.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.random.Random

object TestImages {
    fun landscapeJpeg(context: Context): Uri = jpeg(
        context = context,
        name = "landscape_5000x3000.jpg",
        width = 5000,
        height = 3000,
    )

    fun largeJpeg(context: Context): Uri = jpeg(
        context = context,
        name = "large_8000x6000.jpg",
        width = 8000,
        height = 6000,
    )

    fun portraitRot90Jpeg(context: Context): Uri {
        val file = writeBitmap(
            context = context,
            name = "portrait_rot90.jpg",
            width = 800,
            height = 600,
            format = Bitmap.CompressFormat.JPEG,
        )
        ExifInterface(file).apply {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString(),
            )
            saveAttributes()
        }
        return Uri.fromFile(file)
    }

    fun alphaPng(context: Context): Uri {
        val file = File(context.cacheDir, "alpha.png")
        Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).useBitmap { bitmap ->
            Canvas(bitmap).drawColor(Color.argb(80, 255, 0, 0))
            file.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        }
        return Uri.fromFile(file)
    }

    fun screenshotPng(context: Context): Uri {
        val file = File(context.cacheDir, "screenshot_flat.png")
        Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).useBitmap { bitmap ->
            bitmap.setHasAlpha(false)
            Canvas(bitmap).drawColor(Color.rgb(28, 28, 30))
            file.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        }
        return Uri.fromFile(file)
    }

    fun webpLossy(context: Context): Uri = writeBitmap(
        context = context,
        name = "webp_lossy.webp",
        width = 96,
        height = 64,
        format = Bitmap.CompressFormat.WEBP_LOSSY,
    ).let(Uri::fromFile)

    fun corruptJpeg(context: Context): Uri {
        val file = File(context.cacheDir, "corrupt.jpg")
        file.writeBytes(Random(1).nextBytes(512))
        return Uri.fromFile(file)
    }

    fun zeroByteJpeg(context: Context): Uri {
        val file = File(context.cacheDir, "zero.jpg")
        file.writeBytes(ByteArray(0))
        return Uri.fromFile(file)
    }

    fun pngWithJpgExtension(context: Context): Uri = writeBitmap(
        context = context,
        name = "mime_mismatch.jpg",
        width = 32,
        height = 24,
        format = Bitmap.CompressFormat.PNG,
    ).let(Uri::fromFile)

    fun animatedGif(context: Context): Uri {
        val file = File(context.cacheDir, "animated.gif")
        file.writeBytes(AnimatedGifBytes)
        return Uri.fromFile(file)
    }

    fun avif(context: Context): Uri {
        val file = File(context.cacheDir, "tiny.avif")
        file.writeBytes(Base64.decode(TinyAvifBase64, Base64.DEFAULT))
        return Uri.fromFile(file)
    }

    private fun jpeg(context: Context, name: String, width: Int, height: Int): Uri =
        writeBitmap(
            context = context,
            name = name,
            width = width,
            height = height,
            format = Bitmap.CompressFormat.JPEG,
        ).let(Uri::fromFile)

    private fun writeBitmap(
        context: Context,
        name: String,
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat,
    ): File {
        val file = File(context.cacheDir, name)
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).useBitmap { bitmap ->
            Canvas(bitmap).drawColor(Color.rgb(24, 120, 216))
            file.outputStream().use { output ->
                check(bitmap.compress(format, 90, output))
            }
        }
        return file
    }

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try {
            block(this)
        } finally {
            recycle()
        }
    }

    private val AnimatedGifBytes = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x81.toByte(), 0x00,
        0x00, 0xff.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x21, 0xff.toByte(), 0x0b, 0x4e, 0x45, 0x54, 0x53, 0x43, 0x41, 0x50, 0x45,
        0x32, 0x2e, 0x30, 0x03, 0x01, 0x00, 0x00, 0x00, 0x21, 0xf9.toByte(), 0x04, 0x00,
        0x0a, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00,
        0x00, 0x08, 0x04, 0x00, 0x01, 0x04, 0x04, 0x00, 0x21, 0xf9.toByte(), 0x04, 0x01,
        0x0a, 0x00, 0x01, 0x00, 0x2c, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00,
        0x81.toByte(), 0x00, 0x00, 0xff.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x08, 0x04, 0x00, 0x01, 0x04, 0x04, 0x00, 0x3b,
    )

    private const val TinyAvifBase64 =
        "AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUIAAADrbWV0YQAAAAAAAAAhaGRscgAAAAAA" +
            "AAAAcGljdAAAAAAAAAAAAAAAAAAAAAAOcGl0bQAAAAAAAQAAAB5pbG9jAAAAAEQAAAEAAQAAAAEA" +
            "AAETAAAAHAAAAChpaW5mAAAAAAABAAAAGmluZmUCAAAAAAEAAGF2MDFDb2xvcgAAAABqaXBycAAA" +
            "AEtpcGNvAAAAFGlzcGUAAAAAAAAAAgAAAAIAAAAQcGl4aQAAAAADCAgIAAAADGF2MUOBAAwAAAAA" +
            "E2NvbHJuY2x4AAEADQAGgAAAABdpcG1hAAAAAAAAAAEAAQQBAoMEAAAAJG1kYXQSAAoFGAA2BCAy" +
            "ERTABBBBBAAAeUzeoX5If/DI"
}
