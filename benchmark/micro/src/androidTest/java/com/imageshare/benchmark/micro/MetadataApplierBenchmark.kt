@file:Suppress("MagicNumber")

package com.imageshare.benchmark.micro

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.core.processing.MetadataApplier
import com.imageshare.core.processing.MetadataMode
import com.imageshare.core.processing.MetadataSource
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MetadataApplierBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var applier: MetadataApplier
    private lateinit var fullExifJpeg: ByteArray

    @Before
    fun setUp() {
        applier = MetadataApplier()
        fullExifJpeg = BenchmarkFixtures.fullExifJpeg1024()
    }

    @Test
    fun stripAllJpeg1024() = benchmarkRule.measureRepeated {
        runBlocking { applier.apply(fullExifJpeg, EncodeFormat.JPEG, MetadataMode.StripAll) }
    }

    @Test
    fun preserveSafeJpeg1024() = benchmarkRule.measureRepeated {
        runBlocking {
            applier.apply(
                encoded = fullExifJpeg,
                format = EncodeFormat.JPEG,
                mode = MetadataMode.PreserveSafe,
                source = MetadataSource(originalBytes = fullExifJpeg, originalUri = null),
            )
        }
    }
}
