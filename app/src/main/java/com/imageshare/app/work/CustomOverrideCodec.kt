package com.imageshare.app.work

import com.imageshare.app.MainViewModel
import com.imageshare.feature.preset.Preset
import com.imageshare.feature.preset.ResizeMode

internal fun MainViewModel.CustomOverride.toWorkerJson(): String = when (val resize = resize) {
    is ResizeMode.Exact -> """{"type":"Exact","width":${resize.width},"height":${resize.height}}"""
    is ResizeMode.LongEdge -> """{"type":"LongEdge","pixels":${resize.pixels}}"""
    is ResizeMode.Percentage -> """{"type":"Percentage","pct":${resize.pct}}"""
    ResizeMode.Original -> """{"type":"Original"}"""
}

internal fun Preset.withWorkerOverride(json: String?): Preset {
    if (json.isNullOrBlank()) return this
    return runCatching { copy(resize = json.toResizeMode()) }.getOrDefault(this)
}

private fun String.toResizeMode(): ResizeMode = when (stringValue("type")) {
    "Exact" -> ResizeMode.Exact(intValue("width"), intValue("height"))
    "LongEdge" -> ResizeMode.LongEdge(intValue("pixels"))
    "Percentage" -> ResizeMode.Percentage(intValue("pct"))
    "Original" -> ResizeMode.Original
    else -> ResizeMode.Original
}

private fun String.stringValue(key: String): String? =
    Regex(""""$key"\s*:\s*"([^"]+)"""").find(this)?.groupValues?.get(1)

private fun String.intValue(key: String): Int =
    Regex(""""$key"\s*:\s*(\d+)""").find(this)?.groupValues?.get(1)?.toInt()
        ?: error("Missing $key")
