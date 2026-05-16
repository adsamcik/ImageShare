package com.imageshare.core.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncoderInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val encoder = Encoder()
    private val decoder = Decoder(context.contentResolver)
    private val resizer = Resizer()

    @Test
    fun jpegQualityMonotonicityUsesRealCodec() = runBlocking {
        val bitmap = photoLikeBitmap(200, 200)
        val q20 = encoder.encode(bitmap, EncodeFormat.JPEG, 20, AlphaPolicy.Error)
        val q90 = encoder.encode(bitmap, EncodeFormat.JPEG, 90, AlphaPolicy.Error)

        assertTrue("q20=${q20.bytes.size}, q90=${q90.bytes.size}", q90.bytes.size > q20.bytes.size * 1.10)
        bitmap.recycle()
    }

    @Test
    fun decodeResizeEncodeEndToEnd(): Unit = runBlocking {
        val source = photoLikeBitmap(2000, 1500)
        val sourceBytes = encoder.encode(source, EncodeFormat.JPEG, 92, AlphaPolicy.Error).bytes
        source.recycle()
        val input = writeCacheFile("processing-e2e-input.jpg", sourceBytes)

        val decoded = decoder.decode(Uri.fromFile(input), targetLongEdgePx = 2000)
        val resized = resizer.toLongEdge(decoded.bitmap, targetLongEdgePx = 500, recycleSrc = true)
        val encoded = encoder.encode(resized, EncodeFormat.JPEG, 85, AlphaPolicy.Error)
        resized.recycle()

        val roundTrip = BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size)
        assertEquals(500, roundTrip.width)
        assertTrue(abs(roundTrip.height - 375) <= 1)
        roundTrip.recycle()
        input.delete()
    }

    private fun writeCacheFile(name: String, bytes: ByteArray): File =
        File(context.cacheDir, name).apply {
            outputStream().use { stream -> stream.write(bytes) }
        }

    private fun photoLikeBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val noise = (x * 37 + y * 91 + x * y) and 0xFF
                    setPixel(x, y, Color.rgb((x * 255 / width) xor noise, (y * 255 / height) xor noise, noise))
                }
            }
            setHasAlpha(false)
        }
}
