@file:Suppress("MagicNumber")

package com.imageshare.benchmark.micro

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import com.imageshare.core.processing.Decoder
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DecoderBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var decoder: Decoder

    @Before
    fun setUp() {
        decoder = Decoder(BenchmarkFixtures.context.contentResolver)
    }

    @Test
    fun decodeJpeg2000x1500_target1024() = benchmarkRule.measureRepeated {
        val fixture = runWithTimingDisabled { BenchmarkFixtures.uriFor(BenchmarkFixtures.jpegBytes(2000, 1500), "decode-jpeg", ".jpg") }
        try {
            runBlocking { decoder.decode(fixture.first, targetLongEdgePx = 1024) }
        } finally {
            runWithTimingDisabled { fixture.second.delete() }
        }
    }

    @Test
    fun decodePng800x600WithAlpha_target800() = benchmarkRule.measureRepeated {
        val fixture = runWithTimingDisabled { BenchmarkFixtures.uriFor(BenchmarkFixtures.pngBytes(800, 600, alpha = true), "decode-png", ".png") }
        try {
            runBlocking { decoder.decode(fixture.first, targetLongEdgePx = 800) }
        } finally {
            runWithTimingDisabled { fixture.second.delete() }
        }
    }

    @Test
    fun decodeWebp1024x768_target1024() = benchmarkRule.measureRepeated {
        val fixture = runWithTimingDisabled { BenchmarkFixtures.uriFor(BenchmarkFixtures.webpBytes(1024, 768), "decode-webp", ".webp") }
        try {
            runBlocking { decoder.decode(fixture.first, targetLongEdgePx = 1024) }
        } finally {
            runWithTimingDisabled { fixture.second.delete() }
        }
    }
}
