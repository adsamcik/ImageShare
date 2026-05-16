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
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BeforeAfterCard(
    result: PresetPipeline.Result.Success,
    effectiveMetadata: MetadataPolicy,
    onExpandTapped: () -> Unit,
) {
    val beforeSize = fileSizeText(result.before.sizeBytes)
    val afterSize = fileSizeText(result.stored.sizeBytes)
    val beforeSizeA11y = accessibleFileSizeText(result.before.sizeBytes)
    val afterSizeA11y = accessibleFileSizeText(result.stored.sizeBytes)
    val beforeDims = dimensionsText(result.before.width, result.before.height)
    val afterDims = dimensionsText(result.finalWidth, result.finalHeight)
    val beforeDimsA11y = accessibleDimensionsText(result.before.width, result.before.height)
    val afterDimsA11y = accessibleDimensionsText(result.finalWidth, result.finalHeight)
    val reduction = reductionPct(result.before.sizeBytes, result.stored.sizeBytes)
    val reductionText = reduction?.let { stringResource(R.string.reduction_percent_fmt, it) }
        ?: stringResource(R.string.dash_placeholder)
    val format = formatLabel(result.format)
    val accessibleFormat = formatLabel(result.format, accessible = true)
    val metadata = metadataLabel(effectiveMetadata)
    val name = result.before.displayName ?: stringResource(R.string.unnamed_image)
    val description = if (reduction != null) {
        stringResource(
            R.string.before_after_card_a11y,
            name,
            beforeSizeA11y,
            beforeDimsA11y,
            afterSizeA11y,
            afterDimsA11y,
            reduction,
            accessibleFormat,
            metadata,
        )
    } else {
        stringResource(
            R.string.before_after_card_a11y_no_reduction,
            name,
            beforeSizeA11y,
            beforeDimsA11y,
            afterSizeA11y,
            afterDimsA11y,
            accessibleFormat,
            metadata,
        )
    }
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
private fun metadataLabel(policy: MetadataPolicy): String = when (policy) {
    MetadataPolicy.StripAll -> stringResource(R.string.metadata_strip_all)
    MetadataPolicy.PreserveSafe -> stringResource(R.string.metadata_preserve_safe)
    MetadataPolicy.PreserveAll -> stringResource(R.string.metadata_preserve_all)
}
