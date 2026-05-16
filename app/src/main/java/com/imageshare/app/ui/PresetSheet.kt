@file:Suppress(
    "TooManyFunctions",
    "LongParameterList",
    "MaxLineLength",
    "ReturnCount",
    "MagicNumber",
    "SpreadOperator",
    "LongMethod",
    "CyclomaticComplexMethod",
)

package com.imageshare.app.ui

import android.content.ActivityNotFoundException
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.verticalScroll
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.imageshare.app.AlphaConflictStrategy
import com.imageshare.app.ComparisonState
import com.imageshare.app.MainViewModel
import com.imageshare.app.ProcessingState
import com.imageshare.app.R
import com.imageshare.app.SaveStatus
import com.imageshare.app.processing.BatchOrchestrator
import com.imageshare.app.processing.PresetPipeline
import com.imageshare.core.io.SourceItem
import com.imageshare.core.io.RecentUriEntry
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.core.io.rememberOpenDocumentLauncher
import com.imageshare.core.io.rememberPhotoPickerLauncher
import com.imageshare.feature.preset.MetadataPolicy
import com.imageshare.feature.preset.OutputFormat
import com.imageshare.feature.preset.Preset
import com.imageshare.feature.preset.ResizeMode
import kotlinx.coroutines.launch

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val sources by viewModel.sources.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val processingState by viewModel.processingState.collectAsState()
    val selectedPreset by viewModel.selectedPreset.collectAsState()
    val effectivePreset by viewModel.effectivePreset.collectAsState()
    val customOverride by viewModel.customOverride.collectAsState()
    val runInBackground by viewModel.runInBackground.collectAsState()
    val recentsUris by viewModel.recentsUris.collectAsState()
    val shownComparison by viewModel.shownComparison.collectAsState()
    var showLicenses by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val picker = rememberPhotoPickerLauncher(
        onResult = viewModel::onPickerResult,
        maxItems = Int.MAX_VALUE,
    )
    val safLauncher = rememberOpenDocumentLauncher(
        onResult = viewModel::onOpenDocumentResult,
    )
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onSaveDocumentResult(
            if (result.resultCode == Activity.RESULT_OK) result.data?.data else null,
        )
    }
    val postNotificationsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onPostNotificationsPermissionResult(granted)
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

    if (showLicenses) {
        LicensesScreen(onBack = { showLicenses = false })
    } else {
        PresetSheet(
            sources = sources,
            presets = presets,
            selectedPreset = selectedPreset,
            effectivePreset = effectivePreset,
            customOverride = customOverride,
            onCustomOverride = viewModel::onCustomOverride,
            runInBackground = runInBackground,
            onRunInBackgroundChanged = viewModel::onRunInBackgroundChanged,
            processingState = processingState,
            shownComparison = shownComparison,
            onPresetSelected = viewModel::onPresetSelected,
            onPickFromGallery = {
                viewModel.onPickFromGallery()
                picker.launchMultiple()
            },
            onOpenDocuments = { safLauncher.launchMultiple() },
            recentsUris = recentsUris,
            onRecentSelected = viewModel::onRecentSelected,
            onRecentRemoved = viewModel::onRecentRemoved,
            onProcessAndShare = {
                viewModel.onProcessAndShareRequestingNotificationsIfNeeded(
                    postNotificationsGranted = context.hasPostNotificationsPermission(),
                    requestPostNotifications = {
                        runCatching {
                            postNotificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }.onFailure {
                            viewModel.onPostNotificationsPermissionResult(false)
                        }
                    },
                )
            },
            onCancelBatch = viewModel::onCancelBatch,
            onSaveCopy = viewModel::onSaveCopy,
            onExpandResult = viewModel::onExpandResult,
            onCloseComparison = viewModel::onCloseComparison,
            onAlphaConflictStrategy = viewModel::resolveAlphaConflicts,
            onOpenLicenses = { showLicenses = true },
            snackbarHostState = snackbarHostState,
        )
    }
}

private fun android.content.Context.hasPostNotificationsPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetSheet(
    sources: List<SourceItem>,
    presets: List<Preset>,
    selectedPreset: Preset?,
    effectivePreset: Preset? = selectedPreset,
    customOverride: MainViewModel.CustomOverride? = null,
    onCustomOverride: (MainViewModel.CustomOverride?) -> Unit = {},
    runInBackground: Boolean = false,
    onRunInBackgroundChanged: (Boolean) -> Unit = {},
    processingState: ProcessingState,
    shownComparison: ComparisonState? = null,
    onPresetSelected: (String) -> Unit,
    onPickFromGallery: () -> Unit,
    onOpenDocuments: () -> Unit = {},
    recentsUris: List<RecentUriEntry> = emptyList(),
    onRecentSelected: (Uri) -> Unit = {},
    onRecentRemoved: (Uri) -> Unit = {},
    onProcessAndShare: () -> Unit,
    onCancelBatch: () -> Unit,
    onSaveCopy: () -> Unit,
    onExpandResult: (PresetPipeline.Result.Success) -> Unit = {},
    onCloseComparison: () -> Unit = {},
    onAlphaConflictStrategy: (AlphaConflictStrategy) -> Unit,
    onOpenLicenses: () -> Unit = {},
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
    var overflowMenuExpanded by remember { mutableStateOf(false) }

    ImageShareTheme {
        Surface(
            modifier = modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true },
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.top_bar_title)) },
                        actions = {
                            IconButton(onClick = { overflowMenuExpanded = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.licenses_overflow_menu_a11y),
                                )
                            }
                            DropdownMenu(
                                expanded = overflowMenuExpanded,
                                onDismissRequest = { overflowMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.licenses_screen_title)) },
                                    onClick = {
                                        overflowMenuExpanded = false
                                        onOpenLicenses()
                                    },
                                )
                            }
                        },
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    Surface(
                        tonalElevation = 3.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ProcessButtons(
                            processEnabled = sources.isNotEmpty() && !isProcessing && !upscaleBlocked,
                            isProcessing = isProcessing,
                            saveEnabled = hasSuccessfulResult,
                            onProcessAndShare = onProcessAndShare,
                            onCancelBatch = onCancelBatch,
                            onSaveCopy = onSaveCopy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .navigationBarsPadding(),
                        )
                    }
                },
            ) { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (sources.isEmpty()) {
                        EmptyState(
                            onPickFromGallery = onPickFromGallery,
                            onOpenDocuments = onOpenDocuments,
                            recentsUris = recentsUris,
                            onRecentSelected = onRecentSelected,
                            onRecentRemoved = onRecentRemoved,
                        )
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
                        AdvancedSection(runInBackground, onRunInBackgroundChanged)
                        StatusLine(processingState)
                        ResultSummary(
                            processingState = processingState,
                            effectiveMetadata = effectivePreset?.metadata ?: selectedPreset?.metadata ?: MetadataPolicy.StripAll,
                            onExpandResult = onExpandResult,
                        )
                    }
                }
            }
        }
    }

    if (shownComparison != null) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f),
        ) {
            ComparisonScreen(
                before = shownComparison.before,
                after = shownComparison.after,
                onClose = onCloseComparison,
            )
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
private fun AdvancedSection(
    runInBackground: Boolean,
    onRunInBackgroundChanged: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.advanced_section_title), style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = runInBackground,
                    role = Role.Switch,
                    onValueChange = onRunInBackgroundChanged,
                )
                .semantics(mergeDescendants = true) { }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.run_in_background), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.run_in_background_description),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = runInBackground, onCheckedChange = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyState(
    onPickFromGallery: () -> Unit,
    onOpenDocuments: () -> Unit,
    recentsUris: List<RecentUriEntry>,
    onRecentSelected: (Uri) -> Unit,
    onRecentRemoved: (Uri) -> Unit,
) {
    val pickLabel = stringResource(R.string.pick_from_gallery)
    val openDocumentsLabel = stringResource(R.string.open_documents_button)
    val openDocumentsTooltip = stringResource(R.string.open_documents_tooltip)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.empty_state_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(stringResource(R.string.empty_state_hint))
        Button(
            onClick = onPickFromGallery,
            modifier = Modifier.semantics { contentDescription = pickLabel },
        ) {
            Text(pickLabel)
        }
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(openDocumentsTooltip) } },
            state = rememberTooltipState(),
        ) {
            Box {
                OutlinedButton(
                    onClick = onOpenDocuments,
                    modifier = Modifier
                        .testTag("open-documents-button"),
                ) {
                    Text(openDocumentsLabel)
                }
            }
        }
        if (recentsUris.isNotEmpty()) {
            RecentsSection(
                recentsUris = recentsUris,
                onRecentSelected = onRecentSelected,
                onRecentRemoved = onRecentRemoved,
            )
        }
    }
}

@Composable
private fun RecentsSection(
    recentsUris: List<RecentUriEntry>,
    onRecentSelected: (Uri) -> Unit,
    onRecentRemoved: (Uri) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.recents_section_title), style = MaterialTheme.typography.titleMedium)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .testTag("recents-list"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(recentsUris) { index, entry ->
                RecentUriChip(
                    entry = entry,
                    index = index + 1,
                    onSelected = { onRecentSelected(entry.uri) },
                    onRemoved = { onRecentRemoved(entry.uri) },
                )
            }
        }
    }
}

@Composable
private fun RecentUriChip(
    entry: RecentUriEntry,
    index: Int,
    onSelected: () -> Unit,
    onRemoved: () -> Unit,
) {
    val context = LocalContext.current
    val imageRequest = remember(entry.uri) {
        ImageRequest.Builder(context)
            .data(entry.uri)
            .size(120, 120)
            .precision(Precision.INEXACT)
            .crossfade(true)
            .build()
    }
    val chipDescription = entry.displayName?.let { displayName ->
        stringResource(R.string.recents_chip_a11y, displayName)
    } ?: stringResource(R.string.recents_chip_a11y_indexed, index)
    val chipActionLabel = stringResource(R.string.recents_chip_action_label)
    val removeDescription = stringResource(R.string.recents_remove_a11y)

    Box(
        modifier = Modifier
            .width(88.dp)
            .heightIn(min = 72.dp),
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = chipDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(72.dp)
                .clip(MaterialTheme.shapes.medium)
                .clickable(
                    onClickLabel = chipActionLabel,
                    onClick = { onSelected() },
                )
                .semantics { role = Role.Button },
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 6.dp, y = (-6).dp)
                .size(32.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                shape = CircleShape,
                modifier = Modifier
                    .size(32.dp)
                    .align(Alignment.Center),
            ) {}
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .clickable(
                        onClickLabel = removeDescription,
                        onClick = onRemoved,
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription = removeDescription
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
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
                // CRITICAL: this cap is load-bearing because PresetSheet's parent Column
                // is vertically scrollable and gives nested LazyColumn infinite max height.
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
    val description = "$name, ${accessibleDimensionsText(source)}, ${accessibleFileSizeText(source.sizeBytes)}"
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
                val isSelected = preset.id == selectedPreset?.id
                val selectedState = stringResource(R.string.chip_state_selected)
                val notSelectedState = stringResource(R.string.chip_state_not_selected)
                FilterChip(
                    selected = isSelected,
                    onClick = { onPresetSelected(preset.id) },
                    label = { Text(preset.displayName) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics(mergeDescendants = true) {
                            stateDescription = if (isSelected) selectedState else notSelectedState
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
        ProcessingState.Idle -> return
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
                // CRITICAL: this cap is load-bearing because PresetSheet's parent Column
                // is vertically scrollable and gives nested LazyColumn infinite max height.
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
private fun ResultSummary(
    processingState: ProcessingState,
    effectiveMetadata: MetadataPolicy,
    onExpandResult: (PresetPipeline.Result.Success) -> Unit,
) {
    val done = processingState as? ProcessingState.Done ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        done.results.forEach { result ->
            when (result) {
                is PresetPipeline.Result.Success -> BeforeAfterCard(
                    result = result,
                    effectiveMetadata = effectiveMetadata,
                    onExpandTapped = { onExpandResult(result) },
                )
                is PresetPipeline.Result.Failure -> Card(modifier = Modifier.fillMaxWidth()) {
                    FailureResult(result)
                }
            }
        }
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
    modifier: Modifier = Modifier,
) {
    val processDescription = stringResource(R.string.process_and_share_description)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        val saveCopyDisabledHint = stringResource(R.string.save_copy_disabled_no_results)
        val saveCopyA11yDescription = if (saveEnabled) {
            saveCopyDescription
        } else {
            "$saveCopyDescription. $saveCopyDisabledHint."
        }
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = {
                PlainTooltip {
                    Text(
                        if (saveEnabled) {
                            saveCopyDescription
                        } else {
                            saveCopyDisabledHint
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
                        .semantics { contentDescription = saveCopyA11yDescription },
                ) {
                    Text(stringResource(R.string.save_copy))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
    EncodeFormat.HEIF -> stringResource(if (accessible) R.string.output_format_heif_accessible else R.string.output_format_heif)
    EncodeFormat.AVIF -> stringResource(if (accessible) R.string.output_format_avif_accessible else R.string.output_format_avif)
}

@Composable
private fun quantityStringResource(id: Int, quantity: Int, vararg formatArgs: Any): String {
    val resources = LocalContext.current.resources
    return resources.getQuantityString(id, quantity, *formatArgs)
}
