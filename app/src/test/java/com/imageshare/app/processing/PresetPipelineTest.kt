package com.imageshare.app.processing

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.imageshare.core.io.OutputStore
import com.imageshare.core.io.SourceItem
import com.imageshare.core.processing.AlphaPolicy
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.core.processing.MetadataMode
import com.imageshare.feature.preset.AlphaFallback
import com.imageshare.feature.preset.DefaultPresets
import com.imageshare.feature.preset.MetadataPolicy
import com.imageshare.feature.preset.OutputFormat
import com.imageshare.feature.preset.ResizeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PresetPipelineTest {
    @Test
    fun mapsEveryDefaultPresetFormatToProcessingFormat() {
        DefaultPresets.ALL.forEach { preset ->
            assertEquals(preset.format.expectedEncodeFormat(), preset.format.toEncodeFormat())
        }
    }

    @Test
    fun mapsMetadataPolicyToProcessingMode() {
        assertEquals(MetadataMode.StripAll, MetadataPolicy.StripAll.toMetadataMode())
        assertEquals(MetadataMode.PreserveSafe, MetadataPolicy.PreserveSafe.toMetadataMode())
        assertEquals(MetadataMode.PreserveAll, MetadataPolicy.PreserveAll.toMetadataMode())
    }

    @Test
    fun mapsAlphaFallbackToProcessingPolicy() {
        assertEquals(AlphaPolicy.Error, resolveAlphaPolicy(AlphaFallback.Error, EncodeFormat.JPEG))
        assertEquals(AlphaPolicy.FillBackground(-0x1), resolveAlphaPolicy(AlphaFallback.FillWhite, EncodeFormat.JPEG))
        assertEquals(AlphaPolicy.Allow, resolveAlphaPolicy(AlphaFallback.SwitchToPng, EncodeFormat.PNG))
        assertEquals(AlphaPolicy.Allow, resolveAlphaPolicy(AlphaFallback.SwitchToPng, EncodeFormat.WEBP_LOSSY))
        assertEquals(
            AlphaPolicy.FillBackground(-0x1),
            resolveAlphaPolicy(AlphaFallback.SwitchToPng, EncodeFormat.JPEG),
        )
    }

    @Test
    fun switchToPngOnlyChangesAlphaSourceForNonPngPreset() {
        assertEquals(
            EncodeFormat.PNG,
            resolveEncodeFormat(OutputFormat.JPEG, AlphaFallback.SwitchToPng, hadAlpha = true),
        )
        assertEquals(
            EncodeFormat.JPEG,
            resolveEncodeFormat(OutputFormat.JPEG, AlphaFallback.SwitchToPng, hadAlpha = false),
        )
    }

    @Test
    fun resizeModesResolveDecodeLongEdge() {
        val source = source(width = 4_000, height = 3_000)

        assertEquals(1_600, resolveLongEdge(ResizeMode.LongEdge(1_600), source))
        assertEquals(1_200, resolveLongEdge(ResizeMode.Exact(800, 1_200), source))
        assertEquals(2_000, resolveLongEdge(ResizeMode.Percentage(50), source))
        assertEquals(4_000, resolveLongEdge(ResizeMode.Original, source))
    }

    @Test
    fun filenameReplacesExtensionAndFallsBackToJobId() {
        assertEquals("photo.webp", outputFilename("photo.jpg", "job-1", EncodeFormat.WEBP_LOSSY))
        assertEquals("image-job-1.png", outputFilename(null, "job-1", EncodeFormat.PNG))
        assertTrue(mimeForFormat(EncodeFormat.WEBP_LOSSLESS).contains("webp"))
    }

    @Test
    fun cancellationFromProgressIsRethrown() {
        val error = assertThrows(CancellationException::class.java) {
            runBlocking {
                pipeline().run(source(width = 100, height = 80), DefaultPresets.ALL.first(), "job") {
                    throw CancellationException("cancelled by caller")
                }
            }
        }

        assertEquals("cancelled by caller", error.message)
    }

    @Test
    fun failuresRemainScopedToTheirSourceRun(): Unit = runBlocking {
        val pipeline = pipeline()
        val first = source(uri = "content://missing/first", width = 100, height = 80)
        val second = source(uri = "content://missing/second", width = 200, height = 160)

        val firstResult = pipeline.run(first, DefaultPresets.ALL.first(), "job") as PresetPipeline.Result.Failure
        val secondResult = pipeline.run(second, DefaultPresets.ALL.first(), "job") as PresetPipeline.Result.Failure

        assertEquals(first.uri, firstResult.before.uri)
        assertEquals(second.uri, secondResult.before.uri)
    }

    private fun OutputFormat.expectedEncodeFormat(): EncodeFormat = when (this) {
        OutputFormat.JPEG -> EncodeFormat.JPEG
        OutputFormat.PNG -> EncodeFormat.PNG
        OutputFormat.WEBP_LOSSY -> EncodeFormat.WEBP_LOSSY
        OutputFormat.WEBP_LOSSLESS -> EncodeFormat.WEBP_LOSSLESS
    }

    private fun pipeline(): PresetPipeline {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return PresetPipeline(context.contentResolver, OutputStore(context.cacheDir))
    }

    private fun source(
        uri: String = "content://images/source",
        width: Int?,
        height: Int?,
    ) = SourceItem(
        uri = Uri.parse(uri),
        mimeType = "image/jpeg",
        displayName = "source.jpg",
        sizeBytes = 100L,
        width = width,
        height = height,
    )
}
