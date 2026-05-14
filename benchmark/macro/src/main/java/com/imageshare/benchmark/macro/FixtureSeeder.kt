package com.imageshare.benchmark.macro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import java.io.File

object FixtureSeeder {
    fun seed(context: Context, batchImageCount: Int = DEFAULT_BATCH_IMAGE_COUNT): SeededFixtures {
        val fixtureDir = File(context.cacheDir, "fixtures").apply { mkdirs() }
        val batchNames = (1..batchImageCount).map { index -> "image-$index.jpg" }
        val allNames = (batchNames + SINGLE_IMAGE_NAME).distinct()
        allNames.forEachIndexed { index, name ->
            val file = File(fixtureDir, name)
            if (!file.isFile || file.length() == 0L) {
                writeJpeg(file, index)
            }
        }
        return SeededFixtures(
            singleImageUri = FixtureFileProvider.uriFor(SINGLE_IMAGE_NAME),
            batchImageUris = batchNames.map(FixtureFileProvider::uriFor),
        )
    }

    private fun writeJpeg(file: File, seed: Int) {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (y in 0 until HEIGHT step STRIPE_HEIGHT) {
            val red = (y * 255 / HEIGHT + seed * 17) % 256
            val green = (seed * 41 + y * 127 / HEIGHT) % 256
            val blue = (255 - y * 255 / HEIGHT + seed * 29) % 256
            paint.color = Color.rgb(red, green, blue)
            canvas.drawRect(0f, y.toFloat(), WIDTH.toFloat(), (y + STRIPE_HEIGHT).toFloat(), paint)
        }
        paint.alpha = 180
        repeat(24) { index ->
            paint.color = Color.rgb((seed * 53 + index * 19) % 256, (index * 37) % 256, 180)
            canvas.drawCircle(
                ((index * 83 + seed * 47) % WIDTH).toFloat(),
                ((index * 61 + seed * 31) % HEIGHT).toFloat(),
                (40 + index * 3).toFloat(),
                paint,
            )
        }
        file.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                "Failed to write benchmark fixture ${file.name}"
            }
        }
        bitmap.recycle()
    }

    data class SeededFixtures(
        val singleImageUri: Uri,
        private val batchImageUris: List<Uri>,
    ) {
        fun batchImageUris(count: Int): ArrayList<Uri> = ArrayList(batchImageUris.take(count))
    }

    private const val DEFAULT_BATCH_IMAGE_COUNT = 15
    private const val SINGLE_IMAGE_NAME = "image.jpg"
    private const val WIDTH = 1920
    private const val HEIGHT = 1080
    private const val STRIPE_HEIGHT = 12
    private const val JPEG_QUALITY = 85
}
