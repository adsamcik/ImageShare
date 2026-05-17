package com.imageshare.core.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
class BoundaryDimensionsInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val decoder = Decoder(context.contentResolver)
    private val encoder = Encoder()

    @Test
    fun oneByOneRoundTripsAtOneByOne() = runBlocking {
        assertDecodeEncodeRoundTrip(width = 1, height = 1)
    }

    @Test
    fun tallThinImagePreservesDimensions() = runBlocking {
        assertDecodeEncodeRoundTrip(width = 1, height = 2_000)
    }

    @Test
    fun wideThinImagePreservesDimensions() = runBlocking {
        assertDecodeEncodeRoundTrip(width = 2_000, height = 1)
    }

    @Test
    fun extremeWideAspectRatioSucceeds() = runBlocking {
        assertDecodeEncodeRoundTrip(width = 10_000, height = 100)
    }

    @Test
    fun extremeTallAspectRatioSucceeds() = runBlocking {
        assertDecodeEncodeRoundTrip(width = 100, height = 10_000)
    }

    private suspend fun assertDecodeEncodeRoundTrip(width: Int, height: Int) {
        val uri = writeBitmap("boundary-${width}x$height.png", width, height)
        val decoded = decoder.decode(uri, targetLongEdgePx = max(width, height))
        assertEquals(width, decoded.bitmap.width)
        assertEquals(height, decoded.bitmap.height)

        val encoded = encoder.encode(
            bitmap = decoded.bitmap,
            format = EncodeFormat.PNG,
            quality = 100,
            alphaPolicy = AlphaPolicy.Allow,
        )
        assertTrue(encoded.bytes.isNotEmpty())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size, bounds)
        assertEquals(width, bounds.outWidth)
        assertEquals(height, bounds.outHeight)
    }

    private fun writeBitmap(name: String, width: Int, height: Int): Uri {
        val file = File(context.cacheDir, name)
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).useBitmap { bitmap ->
            bitmap.setHasAlpha(false)
            Canvas(bitmap).drawColor(Color.rgb(80, 40, 200))
            file.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        }
        return Uri.fromFile(file)
    }

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try { block(this) } finally { recycle() }
    }
}
