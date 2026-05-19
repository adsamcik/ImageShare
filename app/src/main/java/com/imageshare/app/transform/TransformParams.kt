/*
 * ImageShare
 * Copyright (C) 2024-2026 adsamcik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.imageshare.app.transform

import android.net.Uri
import com.imageshare.app.BuildConfig
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.core.processing.MetadataMode

internal val TRANSFORM_AUTHORITY: String = "${BuildConfig.APPLICATION_ID}.transform"

internal data class TransformParams(
    val source: Uri,
    val formatToken: String,
    val format: EncodeFormat,
    val mimeType: String,
    val extension: String,
    val quality: Int?,
    val targetBytes: Long?,
    val resize: Resize,
    val metadata: MetadataMode,
    val aspectLock: Boolean,
) {
    sealed interface Resize {
        data object Original : Resize
        data class LongEdge(val pixels: Int) : Resize
        data class Exact(val width: Int, val height: Int) : Resize
        data class Percent(val percent: Int) : Resize
    }
}
