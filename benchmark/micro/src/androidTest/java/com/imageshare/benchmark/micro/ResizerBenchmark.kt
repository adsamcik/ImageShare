@file:Suppress("MagicNumber")

package com.imageshare.benchmark.micro

import android.graphics.Bitmap
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import com.imageshare.core.processing.Resizer
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ResizerBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var resizer: Resizer
    private lateinit var src: Bitmap

    @Before
    fun setUp() {
        resizer = Resizer()
        src = BenchmarkFixtures.photoBitmap(2000, 1500)
    }

    @Test
    fun toLongEdge_2000x1500_to_1024() = benchmarkRule.measureRepeated {
        val resized = resizer.toLongEdge(src, 1024)
        runWithTimingDisabled { resized.recycle() }
    }

    @Test
    fun toExactCenterCrop_2000x1500_to_1024x1024() = benchmarkRule.measureRepeated {
        val resized = resizer.toExact(src, 1024, 1024, Resizer.ExactMode.CenterCrop)
        runWithTimingDisabled { resized.recycle() }
    }
}
