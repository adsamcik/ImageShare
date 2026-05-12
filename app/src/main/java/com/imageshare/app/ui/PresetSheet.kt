@file:Suppress("TooManyFunctions", "LongParameterList", "MaxLineLength", "ReturnCount", "MagicNumber")

package com.imageshare.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.imageshare.app.AlphaConflictStrategy
import com.imageshare.app.MainViewModel
import com.imageshare.app.ProcessingState
import com.imageshare.app.processing.PresetPipeline
import com.imageshare.core.io.SourceItem
import com.imageshare.core.io.rememberPhotoPickerLauncher
import com.imageshare.feature.preset.MetadataPolicy
import com.imageshare.feature.preset.OutputFormat
import com.imageshare.feature.preset.Preset
import com.imageshare.feature.preset.ResizeMode
import java.util.Locale

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val sources by viewModel.sources.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val selectedPresetId by viewModel.selectedPresetId.collectAsState()
    val processingState by viewModel.processingState.collectAsState()
    val selectedPreset = presets.firstOrNull { it.id == selectedPresetId } ?: presets.firstOrNull()
    val picker = rememberPhotoPickerLauncher(
        onResult = viewModel::onPickerResult,
        maxItems = Int.MAX_VALUE,
    )

    LaunchedEffect(viewModel) {
        viewModel.shareEvents.collect { shareIntent ->
            context.startActivity(android.content.Intent.createChooser(shareIntent, null))
        }
    }

    PresetSheet(
        sources = sources,
        presets = presets,
        selectedPreset = selectedPreset,
        processingState = processingState,
        onPresetSelected = viewModel::onPresetSelected,
        onPickFromGallery = {
            viewModel.onPickFromGallery()
            picker.launchMultiple()
        },
        onProcessAndShare = viewModel::onProcessAndShare,
        onAlphaConflictStrategy = viewModel::resolveAlphaConflicts,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetSheet(
    sources: List<SourceItem>,
    presets: List<Preset>,
    selectedPreset: Preset?,
    processingState: ProcessingState,
    onPresetSelected: (String) -> Unit,
    onPickFromGallery: () -> Unit,
    onProcessAndShare: () -> Unit,
    onAlphaConflictStrategy: (AlphaConflictStrategy) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isProcessing = processingState is ProcessingState.Running

    MaterialTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            Scaffold(
                topBar = { TopAppBar(title = { Text("← ImageShare") }) },
            ) { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (sources.isEmpty()) {
                        EmptyState(onPickFromGallery)
                    } else {
                        SourcesSection(sources)
                        PresetsSection(presets, selectedPreset, onPresetSelected)
                        selectedPreset?.let { PresetSummary(it) }
                        StatusLine(processingState)
                        ResultSummary(processingState)
                    }
                    ProcessButtons(
                        enabled = sources.isNotEmpty() && !isProcessing,
                        onProcessAndShare = onProcessAndShare,
                    )
                }
            }
        }
    }

    val done = processingState as? ProcessingState.Done
    if (done != null && done.alphaConflictCount > 0) {
        AlphaConflictDialog(done.alphaConflictCount, onAlphaConflictStrategy)
    }
}

@Composable
private fun EmptyState(onPickFromGallery: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Hello ImageShare. Share an image or pick from gallery." },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Hello ImageShare")
        Text("Share an image or pick from gallery.")
        Button(
            onClick = onPickFromGallery,
            modifier = Modifier.semantics { contentDescription = "Pick from gallery" },
        ) {
            Text("Pick from gallery")
        }
    }
}

@Composable
private fun SourcesSection(sources: List<SourceItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sources", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .testTag("source-list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sources) { source ->
                SourceRow(source)
            }
        }
    }
}

@Composable
private fun SourceRow(source: SourceItem) {
    val name = source.displayName ?: "Unnamed image"
    val description = "$name, ${dimensionsText(source, accessible = true)}, ${fileSizeText(source.sizeBytes, accessible = true)}"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Text(name)
        Text("${dimensionsText(source)}  ${fileSizeText(source.sizeBytes)}")
    }
}

@Composable
private fun PresetsSection(
    presets: List<Preset>,
    selectedPreset: Preset?,
    onPresetSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Preset", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .testTag("preset-chips"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { preset ->
                FilterChip(
                    selected = preset.id == selectedPreset?.id,
                    onClick = { onPresetSelected(preset.id) },
                    label = { Text(preset.displayName) },
                    modifier = Modifier.semantics {
                        contentDescription = "Preset ${preset.displayName}"
                    },
                )
            }
        }
    }
}

@Composable
private fun PresetSummary(preset: Preset) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Selected preset ${preset.displayName}, ${presetSummaryText(preset, accessible = true)}"
                },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(presetSummaryText(preset))
            Text("Metadata: ${preset.metadata.displayText()}")
        }
    }
}

@Composable
private fun StatusLine(processingState: ProcessingState) {
    val text = when (processingState) {
        ProcessingState.Idle -> "Status: idle"
        is ProcessingState.Running -> "Status: processing ${processingState.currentIndex}/${processingState.total} — ${processingState.step.displayText()}"
        is ProcessingState.Done -> "Status: done — ${processingState.results.count { it is PresetPipeline.Result.Success }} succeeded, ${processingState.results.count { it is PresetPipeline.Result.Failure }} failed"
    }
    Text(
        text = text,
        modifier = Modifier.semantics { contentDescription = text.replace("/", " of ") },
    )
}

@Composable
private fun ResultSummary(processingState: ProcessingState) {
    val done = processingState as? ProcessingState.Done ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        done.results.forEach { result ->
            Card(modifier = Modifier.fillMaxWidth()) {
                when (result) {
                    is PresetPipeline.Result.Success -> SuccessResult(result)
                    is PresetPipeline.Result.Failure -> FailureResult(result)
                }
            }
        }
    }
}

@Composable
private fun SuccessResult(result: PresetPipeline.Result.Success) {
    val text = "${result.before.displayName ?: "Unnamed image"}: ${fileSizeText(result.before.sizeBytes)} to ${fileSizeText(result.stored.sizeBytes)}, ${result.finalWidth}×${result.finalHeight}, ${result.format.name}"
    Column(
        modifier = Modifier
            .padding(12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = text
                    .replace("×", " by ")
                    .replace("JPEG", "J P E G")
            },
    ) {
        Text(text)
    }
}

@Composable
private fun FailureResult(result: PresetPipeline.Result.Failure) {
    val message = "Error processing ${result.before.displayName ?: "image"}: ${result.cause.message ?: result.cause::class.java.simpleName}"
    Row(
        modifier = Modifier
            .padding(12.dp)
            .semantics(mergeDescendants = true) { contentDescription = message },
    ) {
        Text("⚠", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Text(message, color = MaterialTheme.colorScheme.error)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessButtons(enabled: Boolean, onProcessAndShare: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onProcessAndShare,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("process-share-button")
                .semantics { contentDescription = "Process and share" },
        ) {
            Text("Process & share")
        }
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Coming in v0.2") } },
            state = rememberTooltipState(),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(0.5f)
                        .semantics { contentDescription = "Save copy, coming in version 0.2" },
                ) {
                    Text("Save copy (v0.2)")
                }
            }
        }
    }
}

@Composable
private fun AlphaConflictDialog(
    count: Int,
    onAlphaConflictStrategy: (AlphaConflictStrategy) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAlphaConflictStrategy(AlphaConflictStrategy.Skip) },
        title = { Text("Transparency detected") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$count images have transparency. Choose:")
                TextButton(onClick = { onAlphaConflictStrategy(AlphaConflictStrategy.SwitchToPng) }) {
                    Text("Switch to PNG for those")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAlphaConflictStrategy(AlphaConflictStrategy.UseWhiteBackground) }) {
                Text("Use white background")
            }
        },
        dismissButton = {
            TextButton(onClick = { onAlphaConflictStrategy(AlphaConflictStrategy.Skip) }) {
                Text("Skip")
            }
        },
    )
}

private fun PresetPipeline.Step.displayText(): String = when (this) {
    PresetPipeline.Step.Decoding -> "decoding"
    PresetPipeline.Step.Resizing -> "resizing"
    PresetPipeline.Step.Encoding -> "encoding"
    PresetPipeline.Step.ApplyingMetadata -> "applying metadata"
    PresetPipeline.Step.Storing -> "storing"
}

private fun presetSummaryText(preset: Preset, accessible: Boolean = false): String {
    val format = when (preset.format) {
        OutputFormat.JPEG -> if (accessible) "J P E G" else "JPEG"
        OutputFormat.PNG -> if (accessible) "P N G" else "PNG"
        OutputFormat.WEBP_LOSSY -> "WebP lossy"
        OutputFormat.WEBP_LOSSLESS -> "WebP lossless"
    }
    return "Format: $format    ${resizeText(preset.resize, accessible)}"
}

private fun resizeText(resize: ResizeMode, accessible: Boolean = false): String = when (resize) {
    is ResizeMode.LongEdge -> "Long edge: ${resize.pixels} ${if (accessible) "pixels" else "px"}"
    is ResizeMode.Exact -> "Exact: ${resize.width} ${if (accessible) "by" else "×"} ${resize.height} pixels"
    is ResizeMode.Percentage -> "Resize: ${resize.pct} percent"
    ResizeMode.Original -> "Original size"
}

private fun MetadataPolicy.displayText(): String = when (this) {
    MetadataPolicy.StripAll -> "Strip all"
    MetadataPolicy.PreserveSafe -> "Preserve safe"
    MetadataPolicy.PreserveAll -> "Preserve all"
}

private fun dimensionsText(source: SourceItem, accessible: Boolean = false): String {
    val width = source.width
    val height = source.height
    return if (width != null && height != null) {
        if (accessible) "$width by $height pixels" else "${width}×${height}"
    } else {
        "unknown dimensions"
    }
}

private fun fileSizeText(bytes: Long?, accessible: Boolean = false): String {
    if (bytes == null) return "unknown size"
    val kb = bytes / BYTES_PER_KIB.toDouble()
    if (kb < BYTES_PER_KIB) {
        return if (accessible) String.format(Locale.US, "%.1f kilobytes", kb) else String.format(Locale.US, "%.1f KB", kb)
    }
    val mb = kb / BYTES_PER_KIB
    return if (accessible) String.format(Locale.US, "%.1f megabytes", mb) else String.format(Locale.US, "%.1f MB", mb)
}

private const val BYTES_PER_KIB = 1024
