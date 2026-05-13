@file:Suppress("MagicNumber")

package com.imageshare.benchmark.micro

import android.graphics.Bitmap
import android.graphics.Color
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import com.imageshare.core.processing.AlphaPolicy
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.core.processing.Encoder
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class EncoderBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var encoder: Encoder
    private lateinit var opaque: Bitmap
    private lateinit var alpha: Bitmap

    @Before
    fun setUp() {
        encoder = Encoder()
        opaque = BenchmarkFixtures.photoBitmap(1024, 768)
        alpha = BenchmarkFixtures.photoBitmap(1024, 768, alpha = true)
    }

    @Test
    fun encodeJpegQ70_1024x768() = benchmarkRule.measureRepeated {
        runBlocking { encoder.encode(opaque, EncodeFormat.JPEG, 70, AlphaPolicy.FillBackground(Color.WHITE)) }
    }

    @Test
    fun encodePngOpaque_1024x768() = benchmarkRule.measureRepeated {
        runBlocking { encoder.encode(opaque, EncodeFormat.PNG, 100, AlphaPolicy.Allow) }
    }

    @Test
    fun encodeWebpLossy85_1024x768() = benchmarkRule.measureRepeated {
        runBlocking { encoder.encode(opaque, EncodeFormat.WEBP_LOSSY, 85, AlphaPolicy.Allow) }
    }

    @Test
    fun flattenAlphaThenJpegQ70_1024x768() = benchmarkRule.measureRepeated {
        runBlocking { encoder.encode(alpha, EncodeFormat.JPEG, 70, AlphaPolicy.FillBackground(Color.WHITE)) }
    }
}
