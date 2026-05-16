package com.imageshare.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 DayNight theme for ImageShare.
 *
 * - On Android 12+ (API 31+): uses dynamic color sourced from the user's wallpaper
 *   for full Material You integration.
 * - On Android 10/11 (API 29/30): falls back to a built-in light/dark color scheme
 *   that honors the system Night mode setting.
 *
 * The function is intentionally minimal — the seed colors below are deliberate
 * defaults that match Material 3 baseline palettes; richer brand-specific seeds
 * can be substituted by a v1.1 design refresh without changing this entry point.
 */
@Composable
fun ImageShareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
