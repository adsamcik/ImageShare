package com.imageshare.core.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryPressureInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val decoder = Decoder(context.contentResolver)
    private val encoder = Encoder()

    @Test
    fun batchOfFiveLargeImagesDoesNotLeakOrOom() = runBlocking {
        val uris = (0 until 5).map { index -> writeLargeJpeg("memory-pressure-$index.jpg") }
        val runtime = Runtime.getRuntime()
        runtime.gc()
        val beforeBytes = runtime.totalMemory() - runtime.freeMemory()
        var processed = 0

        uris.forEach { uri ->
            val decoded = decoder.decode(uri, targetLongEdgePx = 1024, preferRgb565 = true)
            try {
                assertTrue(max(decoded.bitmap.width, decoded.bitmap.height) <= 1024)
                val encoded = encoder.encode(decoded.bitmap, EncodeFormat.JPEG, quality = 75, alphaPolicy = AlphaPolicy.FillBackground(Color.WHITE))
                assertTrue(encoded.bytes.isNotEmpty())
                processed += 1
            } catch (oom: OutOfMemoryError) {
                throw AssertionError("OOM should be converted or avoided during large batch processing", oom)
            } finally {
                if (!decoded.bitmap.isRecycled) {
                    decoded.bitmap.recycle()
                }
            }
        }

        runtime.gc()
        val afterBytes = runtime.totalMemory() - runtime.freeMemory()
        assertEquals(5, processed)
        assertTrue("allocation delta=${afterBytes - beforeBytes}", afterBytes - beforeBytes < 120L * 1024L * 1024L)
    }

    private fun writeLargeJpeg(name: String): Uri {
        val file = File(context.cacheDir, name)
        Bitmap.createBitmap(5_000, 3_000, Bitmap.Config.ARGB_8888).useBitmap { bitmap ->
            bitmap.setHasAlpha(false)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(35, 110, 190))
            file.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 86, output)) }
        }
        return Uri.fromFile(file)
    }

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try { block(this) } finally { recycle() }
    }
}
