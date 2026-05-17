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
import com.imageshare.feature.preset.DefaultPresets
import com.imageshare.feature.preset.Preset
import com.imageshare.feature.preset.ResizeMode
import java.io.File
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChaosPipelineInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val pipeline = PresetPipeline(context.contentResolver, OutputStore(context.cacheDir))

    @Test
    fun seededRandomPresetSourceFormatDimensionCombinationsDoNotCrash() = runBlocking {
        val random = Random(42)
        repeat(50) { index ->
            val sourceFormat = randomSourceFormat(random)
            assumeTrue("WEBP source generation requires API 30+", sourceFormat != SourceFormat.WEBP || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            val dims = randomDims(random)
            val file = writeSource("chaos-$index.${sourceFormat.extension}", dims.first, dims.second, sourceFormat.compressFormat)
            val source = sourceItem(file)
            val preset = randomPreset(random, index, source)
            val requestedMax = requestedMaxEdge(preset.resize, source)

            val result = pipeline.run(source, preset, "chaos-$index")

            assertTrue("case=$index result=$result", result is PresetPipeline.Result.Success)
            val success = result as PresetPipeline.Result.Success
            assertTrue("case=$index output empty", success.stored.file.length() > 0L)
            assertEquals("case=$index mime", mimeForFormat(success.format), success.stored.mimeType)
            assertTrue("case=$index width=${success.finalWidth} requested=$requestedMax", success.finalWidth <= requestedMax)
            assertTrue("case=$index height=${success.finalHeight} requested=$requestedMax", success.finalHeight <= requestedMax)
            assertTrue("case=$index decoded output", BitmapFactory.decodeFile(success.stored.file.absolutePath) != null)
        }
    }

    private fun randomSourceFormat(random: Random): SourceFormat = SourceFormat.values()[random.nextInt(SourceFormat.values().size)]

    private fun randomDims(random: Random): Pair<Int, Int> {
        val choices = listOf(32 to 32, 640 to 480, 1200 to 900, 2048 to 1536, 3000 to 500, 500 to 3000)
        return choices[random.nextInt(choices.size)]
    }

    private fun randomPreset(random: Random, index: Int, source: SourceItem): Preset {
        val base = DefaultPresets.ALL[random.nextInt(DefaultPresets.ALL.size)]
        val resize = if (random.nextBoolean()) base.resize else when (random.nextInt(4)) {
            0 -> ResizeMode.LongEdge(listOf(64, 256, 800, 1600)[random.nextInt(4)])
            1 -> ResizeMode.Exact(listOf(32, 128, 640)[random.nextInt(3)], listOf(32, 240, 480)[random.nextInt(3)])
            2 -> ResizeMode.Percentage(listOf(10, 25, 50, 75, 100)[random.nextInt(5)])
            else -> ResizeMode.Original
        }
        return base.copy(id = "${base.id}-chaos-$index", resize = resize)
    }

    private fun requestedMaxEdge(resize: ResizeMode, source: SourceItem): Int = when (resize) {
        is ResizeMode.LongEdge -> resize.pixels
        is ResizeMode.Exact -> max(resize.width, resize.height)
        is ResizeMode.Percentage -> max(source.width ?: 1, source.height ?: 1) * resize.pct / 100
        ResizeMode.Original -> max(source.width ?: 1, source.height ?: 1)
    }.coerceAtLeast(1)

    private fun sourceItem(file: File): SourceItem {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return SourceItem(Uri.fromFile(file), bounds.outMimeType, file.name, file.length(), bounds.outWidth, bounds.outHeight)
    }

    private fun writeSource(name: String, width: Int, height: Int, format: Bitmap.CompressFormat): File {
        val file = File(context.cacheDir, name)
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).useBitmap { bitmap ->
            bitmap.setHasAlpha(false)
            Canvas(bitmap).drawColor(Color.rgb((width * 17) and 0xff, (height * 31) and 0xff, 180))
            file.outputStream().use { output -> check(bitmap.compress(format, 90, output)) }
        }
        return file
    }

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try { block(this) } finally { recycle() }
    }

    private enum class SourceFormat(val extension: String, val compressFormat: Bitmap.CompressFormat) {
        JPEG("jpg", Bitmap.CompressFormat.JPEG),
        PNG("png", Bitmap.CompressFormat.PNG),
        WEBP("webp", Bitmap.CompressFormat.WEBP_LOSSY),
    }
}
