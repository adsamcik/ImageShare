package com.imageshare.core.processing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeAvifEncoderInstrumentedTest {
    @Test
    fun encodeIfAvailableProducesValidAvif() {
        assumeTrue("Native library not loaded", NativeAvifEncoder.isAvailable())

        val bitmap = photoLikeBitmap(width = 1024, height = 768)
        val bytes = NativeAvifEncoder.encode(bitmap, 80)

        assertNotNull(bytes)
        assertTrue(bytes!!.hasAvifBrand())
        bitmap.recycle()
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

    private fun ByteArray.hasAvifBrand(): Boolean =
        size >= AVIF_BRAND_OFFSET + AVIF_BRAND_SIZE &&
            String(
                copyOfRange(AVIF_BRAND_OFFSET, AVIF_BRAND_OFFSET + AVIF_BRAND_SIZE),
                Charsets.US_ASCII,
            ) == AVIF_BRAND

    private companion object {
        private const val AVIF_BRAND_OFFSET = 4
        private const val AVIF_BRAND_SIZE = 8
        private const val AVIF_BRAND = "ftypavif"
    }
}
