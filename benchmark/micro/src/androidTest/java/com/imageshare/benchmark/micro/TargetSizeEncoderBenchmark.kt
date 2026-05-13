@file:Suppress("MagicNumber")

package com.imageshare.benchmark.micro

import android.graphics.Bitmap
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.core.processing.TargetSizeEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TargetSizeEncoderBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var encoder: TargetSizeEncoder
    private lateinit var photo: Bitmap

    @Before
    fun setUp() {
        encoder = TargetSizeEncoder()
        photo = BenchmarkFixtures.photoBitmap(2000, 1500)
    }

    @Test
    fun encodeToTarget_jpeg_1MB() = benchmarkRule.measureRepeated {
        runBlocking {
            encoder.encodeToTarget(
                photo,
                TargetSizeEncoder.Config(format = EncodeFormat.JPEG, targetBytes = 1L * 1024L * 1024L),
            )
        }
    }

    @Test
    fun encodeToTarget_jpeg_200KB() = benchmarkRule.measureRepeated {
        runBlocking {
            encoder.encodeToTarget(
                photo,
                TargetSizeEncoder.Config(format = EncodeFormat.JPEG, targetBytes = 200L * 1024L),
            )
        }
    }
}
