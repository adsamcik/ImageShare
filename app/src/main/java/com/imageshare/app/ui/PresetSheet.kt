@file:Suppress("TooManyFunctions", "LongParameterList", "MaxLineLength", "ReturnCount", "MagicNumber", "SpreadOperator", "LongMethod")

package com.imageshare.app.ui

import android.content.ActivityNotFoundException
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.imageshare.app.AlphaConflictStrategy
import com.imageshare.app.MainViewModel
import com.imageshare.app.ProcessingState
import com.imageshare.app.R
import com.imageshare.app.SaveStatus
import com.imageshare.app.processing.BatchOrchestrator
import com.imageshare.app.processing.PresetPipeline
import com.imageshare.core.io.SourceItem
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.core.io.rememberPhotoPickerLauncher
import com.imageshare.feature.preset.MetadataPolicy
import com.imageshare.feature.preset.OutputFormat
import com.imageshare.feature.preset.Preset
import com.imageshare.feature.preset.ResizeMode
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val sources by viewModel.sources.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val processingState by viewModel.processingState.collectAsState()
    val selectedPreset by viewModel.selectedPreset.collectAsState()
    val effectivePreset by viewModel.effectivePreset.collectAsState()
    val customOverride by viewModel.customOverride.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val picker = rememberPhotoPickerLauncher(
        onResult = viewModel::onPickerResult,
        maxItems = Int.MAX_VALUE,
    )
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onSaveDocumentResult(
            if (result.resultCode == Activity.RESULT_OK) result.data?.data else null,
        )
    }

    LaunchedEffect(viewModel) {
        viewModel.shareEvents.collect { shareIntent ->
            runCatching {
                context.startActivity(Intent.createChooser(shareIntent, null))
            }.onFailure { error ->
                if (error is ActivityNotFoundException) {
                    snackbarHostState.showSnackbar(context.getString(R.string.share_failed_no_target))
                } else {
                    throw error
                }
            }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.saveDocumentEvents.collect { intent ->
            runCatching {
                saveDocumentLauncher.launch(intent)
            }.onFailure {
                snackbarHostState.showSnackbar(context.getString(R.string.save_status_failed))
                viewModel.onSaveDocumentResult(null)
            }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.saveStatus.collect { status ->
            val message = when (status) {
                SaveStatus.Idle -> null
                SaveStatus.Cancelled -> context.getString(R.string.save_status_cancelled)
                is SaveStatus.SingleDone -> context.getString(
                    R.string.save_status_single_done,
                    fileSizeText(context, status.bytes),
                )
                is SaveStatus.BatchDone -> {
                    val count = status.result.succeeded.size
                    if (count > 0) {
                        context.resources.getQuantityString(R.plurals.save_status_batch_done, count, count)
                    } else {
                        context.getString(R.string.save_status_failed)
                    }
                }
                is SaveStatus.Failed -> context.getString(R.string.save_status_failed)
            }
            if (message != null) {
                snackbarHostState.showSnackbar(message)
                viewModel.onSaveStatusShown()
            }
        }
    }
    LaunchedEffect(processingState) {
        val cancelled = processingState as? ProcessingState.Cancelled ?: return@LaunchedEffect
        val count = cancelled.partial.size
        snackbarHostState.showSnackbar(
            context.resources.getQuantityString(R.plurals.batch_cancelled_partial, count, count),
        )
    }

    PresetSheet(
        sources = sources,
        presets = presets,
        selectedPreset = selectedPreset,
        effectivePreset = effectivePreset,
        customOverride = customOverride,
        onCustomOverride = viewModel::onCustomOverride,
        processingState = processingState,
        onPresetSelected = viewModel::onPresetSelected,
        onPickFromGallery = {
            viewModel.onPickFromGallery()
            picker.launchMultiple()
        },
        onProcessAndShare = viewModel::onProcessAndShare,
        onCancelBatch = viewModel::onCancelBatch,
        onSaveCopy = viewModel::onSaveCopy,
        onAlphaConflictStrategy = viewModel::resolveAlphaConflicts,
        snackbarHostState = snackbarHostState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetSheet(
    sources: List<SourceItem>,
    presets: List<Preset>,
    selectedPreset: Preset?,
    effectivePreset: Preset? = selectedPreset,
    customOverride: MainViewModel.CustomOverride? = null,
    onCustomOverride: (MainViewModel.CustomOverride?) -> Unit = {},
    processingState: ProcessingState,
    onPresetSelected: (String) -> Unit,
    onPickFromGallery: () -> Unit,
    onProcessAndShare: () -> Unit,
    onCancelBatch: () -> Unit,
    onSaveCopy: () -> Unit,
    onAlphaConflictStrategy: (AlphaConflictStrategy) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val isProcessing = processingState is ProcessingState.Running
    val hasSuccessfulResult = (processingState as? ProcessingState.Done)
        ?.results
        ?.any { it is PresetPipeline.Result.Success } ?: false
    val upscaleBlocked = customOverride != null &&
        !customOverride.allowUpscale &&
        sources.any { source -> wouldUpscale(source, customOverride.resize) }
    val coroutineScope = rememberCoroutineScope()

    MaterialTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            Scaffold(
                topBar = { TopAppBar(title = { Text(stringResource(R.string.top_bar_title)) }) },
                snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        effectivePreset?.let { PresetSummary(it) }
                        selectedPreset?.let {
                            Text(stringResource(R.string.resize_section_title), style = MaterialTheme.typography.titleMedium)
                            CustomDimensionsCard(
                                sources = sources,
                                preset = it,
                                customOverride = customOverride,
                                onCustomOverride = onCustomOverride,
                            )
                        }
                        if (upscaleBlocked) {
                            Text(
                                text = stringResource(R.string.upscale_blocked_warning),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("upscale-warning"),
                            )
                        }
                        StatusLine(processingState)
                        ResultSummary(processingState)
                    }
                        ProcessButtons(
                            processEnabled = sources.isNotEmpty() && !isProcessing && !upscaleBlocked,
                            isProcessing = isProcessing,
                            saveEnabled = hasSuccessfulResult,
                            onProcessAndShare = onProcessAndShare,
                            onCancelBatch = onCancelBatch,
                            onSaveCopy = onSaveCopy,
                        )
                }
            }
        }
    }

    val done = processingState as? ProcessingState.Done
    if (done != null && done.alphaConflictCount > 0) {
        val skippedMessage = quantityStringResource(
            R.plurals.alpha_skipped_count,
            done.alphaConflictCount,
            done.alphaConflictCount,
        )
        val onSkip = {
            onAlphaConflictStrategy(AlphaConflictStrategy.Skip)
            coroutineScope.launch { snackbarHostState.showSnackbar(skippedMessage) }
            Unit
        }
        AlphaConflictDialog(
            count = done.alphaConflictCount,
            onUseWhite = { onAlphaConflictStrategy(AlphaConflictStrategy.UseWhiteBackground) },
            onSwitchToPng = { onAlphaConflictStrategy(AlphaConflictStrategy.SwitchToPng) },
            onSkip = onSkip,
        )
    }
}

@Composable
private fun EmptyState(onPickFromGallery: () -> Unit) {
    val description = stringResource(R.string.empty_state_description)
    val pickLabel = stringResource(R.string.pick_from_gallery)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.empty_state_title))
        Text(stringResource(R.string.empty_state_hint))
        Button(
            onClick = onPickFromGallery,
            modifier = Modifier.semantics { contentDescription = pickLabel },
        ) {
            Text(pickLabel)
        }
    }
}

@Composable
private fun SourcesSection(sources: List<SourceItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sources_title), style = MaterialTheme.typography.titleMedium)
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
    val name = source.displayName ?: stringResource(R.string.unnamed_image)
    val description = "$name, ${dimensionsText(source, accessible = true)}, ${fileSizeText(source.sizeBytes, accessible = true)}"
    val details = stringResource(R.string.source_detail_summary, dimensionsText(source), fileSizeText(source.sizeBytes))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Text(name)
        Text(details)
    }
}

@Composable
private fun PresetsSection(
    presets: List<Preset>,
    selectedPreset: Preset?,
    onPresetSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.preset_title), style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .testTag("preset-chips"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { preset ->
                val chipDescription = stringResource(R.string.preset_content_description, preset.displayName)
                FilterChip(
                    selected = preset.id == selectedPreset?.id,
                    onClick = { onPresetSelected(preset.id) },
                    label = { Text(preset.displayName) },
                    modifier = Modifier.semantics {
                        contentDescription = chipDescription
                    },
                )
            }
        }
    }
}

@Composable
private fun PresetSummary(preset: Preset) {
    val description = stringResource(
        R.string.selected_preset_content_description,
        preset.displayName,
        presetSummaryText(preset, accessible = true),
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(presetSummaryText(preset))
            Text(stringResource(R.string.metadata_summary, preset.metadata.displayText()))
        }
    }
}

@Composable
private fun StatusLine(processingState: ProcessingState) {
    when (processingState) {
        ProcessingState.Idle -> {
            val text = stringResource(R.string.status_idle)
            Text(text = text, modifier = Modifier.semantics { contentDescription = text })
        }
        is ProcessingState.Running -> BatchProgressStatus(processingState.progress)
        is ProcessingState.Done -> {
            val text = stringResource(
                R.string.status_done,
                processingState.results.count { it is PresetPipeline.Result.Success },
                processingState.results.count { it is PresetPipeline.Result.Failure },
            )
            Text(text = text, modifier = Modifier.semantics { contentDescription = text })
        }
        is ProcessingState.Cancelled -> {
            val text = quantityStringResource(
                R.plurals.batch_cancelled_partial,
                processingState.partial.size,
                processingState.partial.size,
            )
            Text(text = text, modifier = Modifier.semantics { contentDescription = text })
        }
    }
}

@Composable
private fun BatchProgressStatus(progress: BatchOrchestrator.BatchProgress) {
    val fraction = if (progress.total == 0) 1f else progress.completed.toFloat() / progress.total.toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .testTag("batch-progress-list"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(progress.items) { item ->
                BatchProgressRow(item)
            }
        }
    }
}

@Composable
private fun BatchProgressRow(item: BatchOrchestrator.BatchProgress.Item) {
    val name = item.source.displayName ?: stringResource(R.string.unnamed_image)
    val status = itemStatusText(item.state)
    val color = when (item.state) {
        BatchOrchestrator.ItemState.Pending,
        BatchOrchestrator.ItemState.Cancelled,
        -> MaterialTheme.colorScheme.outline
        is BatchOrchestrator.ItemState.Running -> MaterialTheme.colorScheme.primary
        is BatchOrchestrator.ItemState.Done -> MaterialTheme.colorScheme.secondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$name, $status" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(name, modifier = Modifier.weight(1f))
        Text(
            text = status,
            color = color,
            fontStyle = if (item.state == BatchOrchestrator.ItemState.Cancelled) FontStyle.Italic else FontStyle.Normal,
        )
    }
}

@Composable
private fun itemStatusText(state: BatchOrchestrator.ItemState): String = when (state) {
    BatchOrchestrator.ItemState.Pending -> stringResource(R.string.batch_item_queued)
    is BatchOrchestrator.ItemState.Running -> stringResource(R.string.batch_item_running, stringForStep(state.step))
    is BatchOrchestrator.ItemState.Done -> stringResource(R.string.batch_item_done)
    BatchOrchestrator.ItemState.Cancelled -> stringResource(R.string.batch_item_cancelled)
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
    val name = result.before.displayName ?: stringResource(R.string.unnamed_image)
    val text = stringResource(
        R.string.success_result_summary,
        name,
        fileSizeText(result.before.sizeBytes),
        fileSizeText(result.stored.sizeBytes),
        result.finalWidth,
        result.finalHeight,
        formatLabel(result.format),
    )
    val description = stringResource(
        R.string.success_result_description,
        name,
        fileSizeText(result.before.sizeBytes),
        fileSizeText(result.stored.sizeBytes),
        result.finalWidth,
        result.finalHeight,
        formatLabel(result.format, accessible = true),
    )
    Column(
        modifier = Modifier
            .padding(12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
            },
    ) {
        Text(text)
    }
}

@Composable
private fun FailureResult(result: PresetPipeline.Result.Failure) {
    val message = stringResource(
        R.string.failure_result_summary,
        result.before.displayName ?: stringResource(R.string.image_fallback),
        result.cause.message ?: result.cause::class.java.simpleName,
    )
    Row(
        modifier = Modifier
            .padding(12.dp)
            .semantics(mergeDescendants = true) { contentDescription = message },
    ) {
        Text(stringResource(R.string.warning_indicator), color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Text(message, color = MaterialTheme.colorScheme.error)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessButtons(
    processEnabled: Boolean,
    isProcessing: Boolean,
    saveEnabled: Boolean,
    onProcessAndShare: () -> Unit,
    onCancelBatch: () -> Unit,
    onSaveCopy: () -> Unit,
) {
    val processDescription = stringResource(R.string.process_and_share_description)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onProcessAndShare,
                enabled = processEnabled,
                modifier = Modifier
                    .weight(1f)
                    .testTag("process-share-button")
                    .semantics { contentDescription = processDescription },
            ) {
                Text(stringResource(R.string.process_and_share))
            }
            if (isProcessing) {
                val cancelDescription = stringResource(R.string.cancel_batch_description)
                OutlinedButton(
                    onClick = onCancelBatch,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cancel-batch-button")
                        .semantics { contentDescription = cancelDescription },
                ) {
                    Text(stringResource(R.string.cancel_batch))
                }
            }
        }
        val saveCopyDescription = stringResource(R.string.save_copy_description)
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = {
                PlainTooltip {
                    Text(
                        if (saveEnabled) {
                            saveCopyDescription
                        } else {
                            stringResource(R.string.save_copy_disabled_no_results)
                        },
                    )
                }
            },
            state = rememberTooltipState(),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onSaveCopy,
                    enabled = saveEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (saveEnabled) 1f else 0.5f)
                        .semantics { contentDescription = saveCopyDescription },
                ) {
                    Text(stringResource(R.string.save_copy))
                }
            }
        }
    }
}

@Composable
private fun AlphaConflictDialog(
    count: Int,
    onUseWhite: () -> Unit,
    onSwitchToPng: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.alpha_conflict_title)) },
        text = { Text(quantityStringResource(R.plurals.alpha_conflict_count, count, count)) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onUseWhite) {
                    Text(stringResource(R.string.alpha_use_white))
                }
                TextButton(onClick = onSwitchToPng) {
                    Text(stringResource(R.string.alpha_switch_png))
                }
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.alpha_skip))
                }
            }
        },
    )
}

@Composable
private fun stringForStep(step: PresetPipeline.Step): String = when (step) {
    PresetPipeline.Step.Decoding -> stringResource(R.string.step_decoding)
    PresetPipeline.Step.Resizing -> stringResource(R.string.step_resizing)
    PresetPipeline.Step.Encoding -> stringResource(R.string.step_encoding)
    PresetPipeline.Step.ApplyingMetadata -> stringResource(R.string.step_applying_metadata)
    PresetPipeline.Step.Storing -> stringResource(R.string.step_storing)
}

@Composable
private fun presetSummaryText(preset: Preset, accessible: Boolean = false): String {
    return stringResource(
        R.string.preset_summary,
        stringResource(R.string.format_summary, formatLabel(preset.format, accessible)),
        resizeText(preset.resize, accessible),
    )
}

@Composable
private fun resizeText(resize: ResizeMode, accessible: Boolean = false): String = when (resize) {
    is ResizeMode.LongEdge -> if (accessible) {
        stringResource(R.string.long_edge_summary_accessible, resize.pixels)
    } else {
        stringResource(R.string.long_edge_summary, resize.pixels)
    }
    is ResizeMode.Exact -> if (accessible) {
        stringResource(R.string.exact_resize_summary_accessible, resize.width, resize.height)
    } else {
        stringResource(R.string.exact_resize_summary, resize.width, resize.height)
    }
    is ResizeMode.Percentage -> stringResource(R.string.percentage_resize_summary, resize.pct)
    ResizeMode.Original -> stringResource(R.string.original_size_summary)
}

@Composable
private fun MetadataPolicy.displayText(): String = when (this) {
    MetadataPolicy.StripAll -> stringResource(R.string.metadata_strip_all)
    MetadataPolicy.PreserveSafe -> stringResource(R.string.metadata_preserve_safe)
    MetadataPolicy.PreserveAll -> stringResource(R.string.metadata_preserve_all)
}

@Composable
private fun dimensionsText(source: SourceItem, accessible: Boolean = false): String {
    val width = source.width
    val height = source.height
    return if (width != null && height != null) {
        if (accessible) {
            stringResource(R.string.dimensions_accessible, width, height)
        } else {
            stringResource(R.string.dimensions_text, width, height)
        }
    } else {
        stringResource(R.string.unknown_dimensions)
    }
}

@Composable
private fun fileSizeText(bytes: Long?, accessible: Boolean = false): String {
    if (bytes == null) return stringResource(R.string.unknown_size)
    val kb = bytes / BYTES_PER_KIB.toDouble()
    val locale = Locale.getDefault()
    if (kb < BYTES_PER_KIB) {
        val amount = String.format(locale, "%.1f", kb)
        return if (accessible) {
            stringResource(R.string.kilobytes_accessible, amount)
        } else {
            stringResource(R.string.kilobytes_text, amount)
        }
    }
    val mb = kb / BYTES_PER_KIB
    val amount = String.format(locale, "%.1f", mb)
    return if (accessible) {
        stringResource(R.string.megabytes_accessible, amount)
    } else {
        stringResource(R.string.megabytes_text, amount)
    }
}

private fun fileSizeText(context: android.content.Context, bytes: Long): String {
    val kb = bytes / BYTES_PER_KIB.toDouble()
    val locale = Locale.getDefault()
    if (kb < BYTES_PER_KIB) {
        return context.getString(R.string.kilobytes_text, String.format(locale, "%.1f", kb))
    }
    val mb = kb / BYTES_PER_KIB
    return context.getString(R.string.megabytes_text, String.format(locale, "%.1f", mb))
}

@Composable
private fun formatLabel(format: OutputFormat, accessible: Boolean = false): String = when (format) {
    OutputFormat.JPEG -> stringResource(if (accessible) R.string.output_format_jpeg_accessible else R.string.output_format_jpeg)
    OutputFormat.PNG -> stringResource(if (accessible) R.string.output_format_png_accessible else R.string.output_format_png)
    OutputFormat.WEBP_LOSSY -> {
        stringResource(if (accessible) R.string.output_format_webp_lossy_accessible else R.string.output_format_webp_lossy)
    }
    OutputFormat.WEBP_LOSSLESS -> {
        stringResource(if (accessible) R.string.output_format_webp_lossless_accessible else R.string.output_format_webp_lossless)
    }
}

@Composable
private fun formatLabel(format: EncodeFormat, accessible: Boolean = false): String = when (format) {
    EncodeFormat.JPEG -> stringResource(if (accessible) R.string.output_format_jpeg_accessible else R.string.output_format_jpeg)
    EncodeFormat.PNG -> stringResource(if (accessible) R.string.output_format_png_accessible else R.string.output_format_png)
    EncodeFormat.WEBP_LOSSY -> {
        stringResource(if (accessible) R.string.output_format_webp_lossy_accessible else R.string.output_format_webp_lossy)
    }
    EncodeFormat.WEBP_LOSSLESS -> {
        stringResource(if (accessible) R.string.output_format_webp_lossless_accessible else R.string.output_format_webp_lossless)
    }
}

@Composable
private fun quantityStringResource(id: Int, quantity: Int, vararg formatArgs: Any): String {
    val resources = LocalContext.current.resources
    return resources.getQuantityString(id, quantity, *formatArgs)
}

private const val BYTES_PER_KIB = 1024
