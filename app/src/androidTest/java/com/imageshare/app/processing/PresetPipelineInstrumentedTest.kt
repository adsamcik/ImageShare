package com.imageshare.app.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imageshare.core.io.OutputStore
import com.imageshare.core.io.SourceItem
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.feature.preset.DefaultPresets
import com.imageshare.feature.preset.ResizeMode
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresetPipelineInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val pipeline = PresetPipeline(context.contentResolver, OutputStore(context.cacheDir))

    @Test
    fun webpSourceFullPipelineRoundTrip() = runBlocking {
        assumeTrue("WEBP_LOSSY requires API 30+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val source = sourceItem(writeFixture("pipeline-source.webp", 640, 480, Bitmap.CompressFormat.WEBP_LOSSY))

        val result = pipeline.run(source, DefaultPresets.SmallFile, "webp-round-trip") as PresetPipeline.Result.Success

        assertEquals(EncodeFormat.JPEG, result.format)
        assertEquals("image/jpeg", result.stored.mimeType)
        assertTrue(result.stored.file.length() > 0L)
        assertJpegHeader(result.stored.file.readBytes())
        assertBitmapSize(result.stored.file, 640, 480)
    }

    @Test
    fun socialUploadPresetProducesWebp() = runBlocking {
        val source = sourceItem(writeFixture("social-source.jpg", 1200, 900, Bitmap.CompressFormat.JPEG))

        val result = pipeline.run(source, DefaultPresets.SocialUpload, "social-webp") as PresetPipeline.Result.Success

        val bytes = result.stored.file.readBytes()
        assertEquals(EncodeFormat.WEBP_LOSSY, result.format)
        assertEquals("image/webp", result.stored.mimeType)
        assertTrue(bytes.isNotEmpty())
        assertTrue(hasWebpHeader(bytes))
    }

    @Test
    fun emailPresetHitsTargetSizeForFiveMegapixelJpeg() = runBlocking {
        val source = sourceItem(writePhotoLikeJpeg("email-5mp.jpg", 2560, 1920))
        // Use an artificial Email variant: DefaultPresets.Email (q=60, 1280px) is already
        // far below 250KB with plain Encoder, so it would not catch targetSize routing regressions.
        val targetForcingPreset = DefaultPresets.Email.copy(
            quality = 95,
            resize = ResizeMode.LongEdge(1600),
        )

        val result = pipeline.run(source, targetForcingPreset, "email-target") as PresetPipeline.Result.Success

        assertEquals(EncodeFormat.JPEG, result.format)
        assertTrue("size=${result.stored.file.length()}", result.stored.file.length() <= 250_000L)
        assertTrue("size=${result.stored.file.length()}", result.stored.file.length() > 50_000L)
        assertTrue(max(result.finalWidth, result.finalHeight) <= 1600)
    }

    @Test
    fun customPresetWithExactDimensionsProducesRequestedSize() = runBlocking {
        val source = sourceItem(writeFixture("custom-source.jpg", 1600, 900, Bitmap.CompressFormat.JPEG))
        val preset = DefaultPresets.Custom.copy(resize = ResizeMode.Exact(800, 600))

        val result = pipeline.run(source, preset, "custom-exact") as PresetPipeline.Result.Success

        assertEquals(800, result.finalWidth)
        assertEquals(600, result.finalHeight)
        assertBitmapSize(result.stored.file, 800, 600)
    }

    private fun sourceItem(file: File): SourceItem {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return SourceItem(
            uri = Uri.fromFile(file),
            mimeType = bounds.outMimeType,
            displayName = file.name,
            sizeBytes = file.length(),
            width = bounds.outWidth,
            height = bounds.outHeight,
        )
    }

    private fun writeFixture(name: String, width: Int, height: Int, format: Bitmap.CompressFormat): File {
        val file = File(context.cacheDir, name)
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).useBitmap { bitmap ->
            Canvas(bitmap).drawColor(Color.rgb(40, 110, 210))
            file.outputStream().use { output -> check(bitmap.compress(format, 90, output)) }
        }
        return file
    }

    private fun writePhotoLikeJpeg(name: String, width: Int, height: Int): File {
        val file = File(context.cacheDir, name)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val noise = (x * 37 + y * 91 + x * y) and 0xff
                pixels[y * width + x] = Color.rgb((x * 255 / width) xor noise, (y * 255 / height) xor noise, noise)
            }
        }
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).useBitmap { bitmap ->
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.setHasAlpha(false)
            file.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) }
        }
        return file
    }

    private fun assertBitmapSize(file: File, width: Int, height: Int) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        try {
            assertEquals(width, bitmap.width)
            assertEquals(height, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertJpegHeader(bytes: ByteArray) {
        assertTrue(bytes.size > 2)
        assertEquals(0xff.toByte(), bytes[0])
        assertEquals(0xd8.toByte(), bytes[1])
    }

    private fun hasWebpHeader(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP"

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try {
            block(this)
        } finally {
            recycle()
        }
    }
}
