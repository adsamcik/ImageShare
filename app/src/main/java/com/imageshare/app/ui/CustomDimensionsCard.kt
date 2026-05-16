@file:Suppress(
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
    "ReturnCount",
    "TooManyFunctions",
)

package com.imageshare.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.imageshare.app.MainViewModel
import com.imageshare.app.R
import com.imageshare.core.io.SourceItem
import com.imageshare.feature.preset.Preset
import com.imageshare.feature.preset.ResizeMode
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun CustomDimensionsCard(
    sources: List<SourceItem>,
    preset: Preset,
    customOverride: MainViewModel.CustomOverride?,
    onCustomOverride: (MainViewModel.CustomOverride?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val toggleLabel = stringResource(
                if (expanded) R.string.custom_dims_collapse_label else R.string.custom_dims_expand_label,
            )
            val expandedState = stringResource(if (expanded) R.string.expanded else R.string.collapsed)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClickLabel = toggleLabel,
                        onClick = { expanded = !expanded },
                    )
                    .semantics {
                        role = Role.Button
                        stateDescription = expandedState
                    }
                    .padding(vertical = 12.dp)
                    .testTag("custom-dimensions-toggle"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.custom_dimensions_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    label = "customDimensionsChevronRotation",
                )
                Icon(
                    imageVector = ExpandMoreIcon,
                    contentDescription = null,
                    modifier = Modifier.rotate(rotation),
                )
            }
            if (expanded) {
                CustomDimensionsContent(
                    sources = sources,
                    baseResize = preset.resize,
                    customOverride = customOverride,
                    onCustomOverride = onCustomOverride,
                )
            }
        }
    }
}

@Composable
private fun CustomDimensionsContent(
    sources: List<SourceItem>,
    baseResize: ResizeMode,
    customOverride: MainViewModel.CustomOverride?,
    onCustomOverride: (MainViewModel.CustomOverride?) -> Unit,
) {
    val activeResize = customOverride?.resize ?: baseResize
    var mode by remember { mutableStateOf(activeResize.toEditMode()) }
    var longEdgeText by remember { mutableStateOf((activeResize as? ResizeMode.LongEdge)?.pixels?.toString() ?: "1600") }
    var widthText by remember { mutableStateOf((activeResize as? ResizeMode.Exact)?.width?.toString() ?: "800") }
    var heightText by remember { mutableStateOf((activeResize as? ResizeMode.Exact)?.height?.toString() ?: "600") }
    var percentage by remember { mutableIntStateOf((activeResize as? ResizeMode.Percentage)?.pct ?: 100) }
    var lockAspect by remember { mutableStateOf(true) }
    var allowUpscale by remember { mutableStateOf(customOverride?.allowUpscale ?: false) }
    var dirty by remember { mutableStateOf(customOverride != null) }
    val aspectSource = sources.firstOrNull { it.width != null && it.height != null }

    LaunchedEffect(baseResize, customOverride) {
        if (customOverride == null && !dirty) {
            mode = baseResize.toEditMode()
            longEdgeText = (baseResize as? ResizeMode.LongEdge)?.pixels?.toString() ?: longEdgeText
            widthText = (baseResize as? ResizeMode.Exact)?.width?.toString() ?: widthText
            heightText = (baseResize as? ResizeMode.Exact)?.height?.toString() ?: heightText
            percentage = (baseResize as? ResizeMode.Percentage)?.pct ?: percentage
            allowUpscale = false
        }
    }

    LaunchedEffect(mode, longEdgeText, widthText, heightText, percentage, allowUpscale, dirty) {
        if (!dirty) return@LaunchedEffect
        delay(OVERRIDE_DEBOUNCE_MS)
        validatedResize(mode, longEdgeText, widthText, heightText, percentage)?.let { resize ->
            onCustomOverride(MainViewModel.CustomOverride(resize, allowUpscale))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ResizeModeSelector(mode) {
            mode = it
            dirty = true
        }
        when (mode) {
            ResizeEditMode.LongEdge -> LongEdgeInputs(
                sources = sources,
                text = longEdgeText,
                onTextChange = {
                    longEdgeText = it.digitsOnly()
                    dirty = true
                },
            )
            ResizeEditMode.Exact -> ExactInputs(
                widthText = widthText,
                heightText = heightText,
                lockAspect = lockAspect,
                onLockAspectChange = {
                    lockAspect = it
                    dirty = true
                },
                onWidthChange = { value ->
                    widthText = value.digitsOnly()
                    if (lockAspect) {
                        lockedHeightForWidth(widthText.toIntOrNull(), aspectSource?.width, aspectSource?.height)
                            ?.let { heightText = it.toString() }
                    }
                    dirty = true
                },
                onHeightChange = { value ->
                    heightText = value.digitsOnly()
                    if (lockAspect) {
                        lockedWidthForHeight(heightText.toIntOrNull(), aspectSource?.width, aspectSource?.height)
                            ?.let { widthText = it.toString() }
                    }
                    dirty = true
                },
            )
            ResizeEditMode.Percentage -> PercentageInputs(percentage) {
                percentage = it
                dirty = true
            }
            ResizeEditMode.Original -> Text(stringResource(R.string.original_dimensions_description))
        }
        AllowUpscaleSwitch(allowUpscale) {
            allowUpscale = it
            dirty = true
        }
        TextButton(
            onClick = {
                dirty = false
                mode = baseResize.toEditMode()
                longEdgeText = (baseResize as? ResizeMode.LongEdge)?.pixels?.toString() ?: longEdgeText
                widthText = (baseResize as? ResizeMode.Exact)?.width?.toString() ?: widthText
                heightText = (baseResize as? ResizeMode.Exact)?.height?.toString() ?: heightText
                percentage = (baseResize as? ResizeMode.Percentage)?.pct ?: percentage
                allowUpscale = false
                onCustomOverride(null)
            },
            modifier = Modifier.testTag("reset-dimensions-button"),
        ) {
            Text(stringResource(R.string.reset_dimensions))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResizeModeSelector(
    selected: ResizeEditMode,
    onSelected: (ResizeEditMode) -> Unit,
) {
    val modes = ResizeEditMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelected(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = { Text(mode.label()) },
                modifier = Modifier.testTag(mode.testTag),
            )
        }
    }
}

@Composable
private fun LongEdgeInputs(
    sources: List<SourceItem>,
    text: String,
    onTextChange: (String) -> Unit,
) {
    val pixels = text.toIntOrNull()
    val error = pixels == null || pixels < MIN_DIMENSION_PX
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        label = { Text(stringResource(R.string.long_edge_input_label)) },
        singleLine = true,
        isError = error,
        supportingText = {
            if (error) {
                Text(stringResource(R.string.long_edge_input_error))
            } else {
                LongEdgePreview(sources, pixels)
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("long-edge-field"),
    )
}

@Composable
private fun LongEdgePreview(sources: List<SourceItem>, pixels: Int?) {
    val source = sources.smallestKnownSource()
    val sourceWidth = source?.width
    val sourceHeight = source?.height
    val preview = if (sourceWidth != null && sourceHeight != null && pixels != null) {
        scaledSizeForLongEdge(sourceWidth, sourceHeight, pixels)
    } else {
        null
    }
    Text(
        if (preview == null) {
            stringResource(R.string.output_preview_unknown)
        } else {
            stringResource(R.string.output_preview, preview.first, preview.second)
        },
    )
}

@Composable
private fun ExactInputs(
    widthText: String,
    heightText: String,
    lockAspect: Boolean,
    onLockAspectChange: (Boolean) -> Unit,
    onWidthChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DimensionField(
            value = widthText,
            onValueChange = onWidthChange,
            label = stringResource(R.string.width_input_label),
            error = stringResource(R.string.width_input_error),
            modifier = Modifier
                .weight(1f)
                .testTag("exact-width-field"),
        )
        DimensionField(
            value = heightText,
            onValueChange = onHeightChange,
            label = stringResource(R.string.height_input_label),
            error = stringResource(R.string.height_input_error),
            modifier = Modifier
                .weight(1f)
                .testTag("exact-height-field"),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = lockAspect,
                role = Role.Switch,
                onValueChange = onLockAspectChange,
            )
            .semantics(mergeDescendants = true) { }
            .padding(vertical = 8.dp)
            .testTag("aspect-lock-switch"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.lock_aspect_ratio),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(
            checked = lockAspect,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun DimensionField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String,
    modifier: Modifier,
) {
    val invalid = value.toIntOrNull()?.let { it < MIN_DIMENSION_PX } ?: true
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = invalid,
        supportingText = { if (invalid) Text(error) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun PercentageInputs(
    percentage: Int,
    onPercentageChange: (Int) -> Unit,
) {
    Text(stringResource(R.string.percentage_input_value, percentage))
    Slider(
        value = percentage.toFloat(),
        onValueChange = { value ->
            onPercentageChange(((value / PERCENT_STEP).roundToInt() * PERCENT_STEP).coerceIn(MIN_PERCENT, MAX_PERCENT))
        },
        valueRange = MIN_PERCENT.toFloat()..MAX_PERCENT.toFloat(),
        steps = PERCENT_SLIDER_STEPS,
        modifier = Modifier.testTag("percentage-slider"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllowUpscaleSwitch(
    allowUpscale: Boolean,
    onAllowUpscaleChange: (Boolean) -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(stringResource(R.string.allow_upscale_tooltip)) } },
        state = rememberTooltipState(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = allowUpscale,
                    role = Role.Switch,
                    onValueChange = onAllowUpscaleChange,
                )
                .semantics(mergeDescendants = true) { }
                .padding(vertical = 8.dp)
                .testTag("allow-upscale-switch"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.allow_upscaling),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                checked = allowUpscale,
                onCheckedChange = null,
            )
        }
    }
}

@Composable
private fun ResizeEditMode.label(): String = when (this) {
    ResizeEditMode.LongEdge -> stringResource(R.string.resize_mode_long_edge)
    ResizeEditMode.Exact -> stringResource(R.string.resize_mode_exact)
    ResizeEditMode.Percentage -> stringResource(R.string.resize_mode_percentage)
    ResizeEditMode.Original -> stringResource(R.string.resize_mode_original)
}

internal fun wouldUpscale(source: SourceItem, resize: ResizeMode): Boolean {
    val width = source.width ?: return false
    val height = source.height ?: return false
    return when (resize) {
        is ResizeMode.LongEdge -> resize.pixels > max(width, height)
        is ResizeMode.Exact -> resize.width > width || resize.height > height
        is ResizeMode.Percentage -> false
        ResizeMode.Original -> false
    }
}

internal fun lockedHeightForWidth(width: Int?, sourceWidth: Int?, sourceHeight: Int?): Int? {
    if (width == null || sourceWidth == null || sourceHeight == null || sourceWidth <= 0) return null
    return (width * sourceHeight.toFloat() / sourceWidth.toFloat()).roundToInt().coerceAtLeast(MIN_DIMENSION_PX)
}

internal fun lockedWidthForHeight(height: Int?, sourceWidth: Int?, sourceHeight: Int?): Int? {
    if (height == null || sourceWidth == null || sourceHeight == null || sourceHeight <= 0) return null
    return (height * sourceWidth.toFloat() / sourceHeight.toFloat()).roundToInt().coerceAtLeast(MIN_DIMENSION_PX)
}

private fun validatedResize(
    mode: ResizeEditMode,
    longEdgeText: String,
    widthText: String,
    heightText: String,
    percentage: Int,
): ResizeMode? = when (mode) {
    ResizeEditMode.LongEdge -> longEdgeText.toIntOrNull()
        ?.takeIf { it >= MIN_DIMENSION_PX }
        ?.let(ResizeMode::LongEdge)
    ResizeEditMode.Exact -> {
        val width = widthText.toIntOrNull()
        val height = heightText.toIntOrNull()
        if (width != null && height != null && width >= MIN_DIMENSION_PX && height >= MIN_DIMENSION_PX) {
            ResizeMode.Exact(width, height)
        } else {
            null
        }
    }
    ResizeEditMode.Percentage -> ResizeMode.Percentage(percentage)
    ResizeEditMode.Original -> ResizeMode.Original
}

private fun ResizeMode.toEditMode(): ResizeEditMode = when (this) {
    is ResizeMode.LongEdge -> ResizeEditMode.LongEdge
    is ResizeMode.Exact -> ResizeEditMode.Exact
    is ResizeMode.Percentage -> ResizeEditMode.Percentage
    ResizeMode.Original -> ResizeEditMode.Original
}

private fun String.digitsOnly(): String = filter(Char::isDigit)

private fun List<SourceItem>.smallestKnownSource(): SourceItem? = filter { it.width != null && it.height != null }
    .minByOrNull { (it.width ?: Int.MAX_VALUE) * (it.height ?: Int.MAX_VALUE) }

private fun scaledSizeForLongEdge(width: Int, height: Int, target: Int): Pair<Int, Int> {
    val scale = target.toFloat() / max(width, height).toFloat()
    return (width * scale).roundToInt() to (height * scale).roundToInt()
}

private enum class ResizeEditMode(val testTag: String) {
    LongEdge("resize-mode-long-edge"),
    Exact("resize-mode-exact"),
    Percentage("resize-mode-percentage"),
    Original("resize-mode-original"),
}

private const val MIN_DIMENSION_PX = 16
private const val MIN_PERCENT = 10
private const val MAX_PERCENT = 100
private const val PERCENT_STEP = 5
private const val PERCENT_SLIDER_STEPS = 17
private const val OVERRIDE_DEBOUNCE_MS = 250L

private val ExpandMoreIcon: ImageVector = ImageVector.Builder(
    name = "ExpandMore",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(7.41f, 8.59f)
        lineTo(12f, 13.17f)
        lineTo(16.59f, 8.59f)
        lineTo(18f, 10f)
        lineTo(12f, 16f)
        lineTo(6f, 10f)
        close()
    }
}.build()
