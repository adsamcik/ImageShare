package com.imageshare.app.ui

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.imageshare.app.R
import com.imageshare.core.io.SourceItem
import java.text.NumberFormat
import java.util.Locale

@Composable
fun dimensionsText(source: SourceItem): String = dimensionsText(source.width, source.height)

@Composable
fun accessibleDimensionsText(source: SourceItem): String = accessibleDimensionsText(source.width, source.height)

@Composable
fun dimensionsText(width: Int?, height: Int?): String {
    return if (width != null && height != null) {
        val integerFormat = NumberFormat.getIntegerInstance(Locale.getDefault())
        stringResource(R.string.dimensions_text, integerFormat.format(width), integerFormat.format(height))
    } else {
        stringResource(R.string.unknown_dimensions)
    }
}

@Composable
fun accessibleDimensionsText(width: Int?, height: Int?): String {
    return if (width != null && height != null) {
        val integerFormat = NumberFormat.getIntegerInstance(Locale.getDefault())
        stringResource(R.string.dimensions_accessible, integerFormat.format(width), integerFormat.format(height))
    } else {
        stringResource(R.string.unknown_dimensions)
    }
}

@Composable
fun fileSizeText(bytes: Long?): String = fileSizeText(bytes, accessible = false)

@Composable
fun accessibleFileSizeText(bytes: Long?): String = fileSizeText(bytes, accessible = true)

@Composable
private fun fileSizeText(bytes: Long?, accessible: Boolean): String {
    if (bytes == null) return stringResource(R.string.unknown_size)
    return fileSizeText(bytes, accessible, LocalContext.current.resources.fileSizeLabels())
}

fun fileSizeText(context: Context, bytes: Long): String {
    return fileSizeText(bytes, accessible = false, context.resources.fileSizeLabels())
}

private data class FileSizeLabels(
    val unknown: String,
    val kilobytes: (String) -> String,
    val megabytes: (String) -> String,
    val kilobytesAccessible: (String) -> String,
    val megabytesAccessible: (String) -> String,
)

private fun Resources.fileSizeLabels(): FileSizeLabels = FileSizeLabels(
    unknown = getString(R.string.unknown_size),
    kilobytes = { amount -> getString(R.string.kilobytes_text, amount) },
    megabytes = { amount -> getString(R.string.megabytes_text, amount) },
    kilobytesAccessible = { amount -> getString(R.string.kilobytes_accessible, amount) },
    megabytesAccessible = { amount -> getString(R.string.megabytes_accessible, amount) },
)

private fun fileSizeText(
    bytes: Long?,
    accessible: Boolean,
    labels: FileSizeLabels,
): String {
    if (bytes == null) return labels.unknown
    val kb = bytes / BYTES_PER_KIB.toDouble()
    val numberFormat = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        maximumFractionDigits = 1
        minimumFractionDigits = 1
    }
    val isKilobytes = kb < BYTES_PER_KIB
    val amount = if (isKilobytes) {
        numberFormat.format(kb)
    } else {
        numberFormat.format(kb / BYTES_PER_KIB)
    }
    return when {
        accessible && isKilobytes -> labels.kilobytesAccessible(amount)
        accessible -> labels.megabytesAccessible(amount)
        isKilobytes -> labels.kilobytes(amount)
        else -> labels.megabytes(amount)
    }
}

private const val BYTES_PER_KIB = 1024
