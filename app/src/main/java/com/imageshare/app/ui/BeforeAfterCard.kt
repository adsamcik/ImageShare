@file:Suppress("MagicNumber", "LongMethod")

package com.imageshare.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.imageshare.app.R
import com.imageshare.app.processing.PresetPipeline
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.feature.preset.MetadataPolicy
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BeforeAfterCard(
    result: PresetPipeline.Result.Success,
    effectiveMetadata: MetadataPolicy,
    onExpandTapped: () -> Unit,
) {
    val context = LocalContext.current
    val unknownSize = stringResource(R.string.unknown_size)
    val beforeSize = formatSize(result.before.sizeBytes, unknown = unknownSize)
    val afterSize = formatSize(result.stored.sizeBytes, unknown = unknownSize)
    val beforeSizeA11y = formatSizeAccessible(result.before.sizeBytes, unknown = unknownSize)
    val afterSizeA11y = formatSizeAccessible(result.stored.sizeBytes, unknown = unknownSize)
    val beforeDims = dimensionsText(result.before.width, result.before.height)
    val afterDims = dimensionsText(result.finalWidth, result.finalHeight)
    val reduction = reductionPct(result.before.sizeBytes, result.stored.sizeBytes)
    val reductionText = reduction?.let { String.format(Locale.getDefault(), "%d%%", it) } ?: "—"
    val format = formatLabel(result.format)
    val metadata = metadataLabel(effectiveMetadata)
    val name = result.before.displayName ?: stringResource(R.string.unnamed_image)
    val description = stringResource(
        R.string.before_after_card_a11y,
        name,
        beforeSizeA11y,
        result.before.width ?: 0,
        result.before.height ?: 0,
        afterSizeA11y,
        result.finalWidth,
        result.finalHeight,
        reduction ?: 0,
        format,
        metadata,
    )
    val afterUri = Uri.fromFile(result.stored.file)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("before-after-card")
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Thumbnail(
                    label = stringResource(R.string.before_label),
                    uri = result.before.uri,
                    modifier = Modifier.weight(1f),
                )
                Thumbnail(
                    label = stringResource(R.string.after_label),
                    uri = afterUri,
                    modifier = Modifier.weight(1f),
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 3,
            ) {
                MetricCell(stringResource(R.string.before_label), "$beforeSize · $beforeDims")
                MetricCell(stringResource(R.string.after_label), "$afterSize · $afterDims")
                MetricCell(stringResource(R.string.reduction_label), reductionText, reduction?.let { it < 0 } == true)
                MetricCell(stringResource(R.string.output_format_label), format)
                MetricCell(stringResource(R.string.metadata_label), metadata)
            }
            if (reduction != null && reduction < 0) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text(stringResource(R.string.reduction_neg_warning)) } },
                    state = rememberTooltipState(),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_warning_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(R.string.reduction_neg_warning),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = onExpandTapped,
                modifier = Modifier.testTag("view-comparison-button"),
            ) {
                Text(stringResource(R.string.view_comparison))
            }
        }
    }
}

@Composable
private fun Thumbnail(label: String, uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(uri)
                .size(240, 240)
                .precision(Precision.INEXACT)
                .build(),
            contentDescription = label,
            modifier = Modifier
                .size(120.dp)
                .clip(MaterialTheme.shapes.medium),
        )
    }
}

@Composable
private fun MetricCell(label: String, value: String, isError: Boolean = false) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Box(modifier = Modifier.padding(end = 4.dp)) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}

internal fun reductionPct(before: Long?, after: Long): Int? {
    if (before == null || before == 0L) return null
    return (((before - after).toDouble() / before.toDouble()) * 100.0).roundToInt()
}

internal fun formatSize(bytes: Long?, locale: Locale = Locale.getDefault(), unknown: String = "—"): String {
    if (bytes == null) return unknown
    val kb = bytes / BYTES_PER_KIB.toDouble()
    return if (kb < BYTES_PER_KIB) {
        String.format(locale, "%.1f KB", kb)
    } else {
        String.format(locale, "%.1f MB", kb / BYTES_PER_KIB)
    }
}

internal fun formatSizeAccessible(
    bytes: Long?,
    locale: Locale = Locale.getDefault(),
    unknown: String = "unknown size",
): String {
    if (bytes == null) return unknown
    val kb = bytes / BYTES_PER_KIB.toDouble()
    return if (kb < BYTES_PER_KIB) {
        String.format(locale, "%.1f kilobytes", kb)
    } else {
        String.format(locale, "%.1f megabytes", kb / BYTES_PER_KIB)
    }
}

private fun dimensionsText(width: Int?, height: Int?): String {
    return if (width != null && height != null) {
        "$width × $height"
    } else {
        "—"
    }
}

@Composable
private fun formatLabel(format: EncodeFormat): String = when (format) {
    EncodeFormat.JPEG -> stringResource(R.string.output_format_jpeg)
    EncodeFormat.PNG -> stringResource(R.string.output_format_png)
    EncodeFormat.WEBP_LOSSY -> stringResource(R.string.output_format_webp_lossy)
    EncodeFormat.WEBP_LOSSLESS -> stringResource(R.string.output_format_webp_lossless)
}

@Composable
private fun metadataLabel(policy: MetadataPolicy): String = when (policy) {
    MetadataPolicy.StripAll -> stringResource(R.string.metadata_label_strip_all)
    MetadataPolicy.PreserveSafe -> stringResource(R.string.metadata_label_preserve_safe)
    MetadataPolicy.PreserveAll -> stringResource(R.string.metadata_label_preserve_all)
}

private const val BYTES_PER_KIB = 1024
