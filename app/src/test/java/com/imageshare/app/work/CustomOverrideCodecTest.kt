package com.imageshare.app.work

import com.imageshare.app.MainViewModel
import com.imageshare.feature.preset.DefaultPresets
import com.imageshare.feature.preset.ResizeMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomOverrideCodecTest {
    @Test
    fun overrideRoundTripsThroughWorkerJson() {
        val base = DefaultPresets.SmallFile
        val cases = listOf(
            ResizeMode.Exact(320, 240),
            ResizeMode.LongEdge(2048),
            ResizeMode.Percentage(75),
            ResizeMode.Original,
        )

        cases.forEach { resize ->
            val json = MainViewModel.CustomOverride(resize, allowUpscale = false).toWorkerJson()

            assertEquals(resize, base.withWorkerOverride(json).resize)
        }
    }

    @Test
    fun malformedJsonReturnsOriginalPreset() {
        val base = DefaultPresets.SmallFile

        assertEquals(base, base.withWorkerOverride("""{"type":"Exact","width":320"""))
    }

    @Test
    fun knownTypeMissingRequiredValueReturnsOriginalPreset() {
        val base = DefaultPresets.SmallFile

        assertEquals(base, base.withWorkerOverride("""{"type":"Exact","width":320}"""))
    }

    @Test
    fun missingOrUnknownTypeDefaultsToOriginalResize() {
        val base = DefaultPresets.SmallFile

        assertEquals(ResizeMode.Original, base.withWorkerOverride("""{"displayName":"ignored"}""").resize)
        assertEquals(ResizeMode.Original, base.withWorkerOverride("""{"type":"Other"}""").resize)
    }

    @Test
    fun escapedExtraStringsDoNotConfuseDecode() {
        val json = """
            {
              "type":"LongEdge",
              "pixels":1600,
              "displayName":"alice's photo \"draft\"\\backslash\n日本語"
            }
        """.trimIndent()

        assertEquals(ResizeMode.LongEdge(1600), DefaultPresets.SmallFile.withWorkerOverride(json).resize)
    }
}
