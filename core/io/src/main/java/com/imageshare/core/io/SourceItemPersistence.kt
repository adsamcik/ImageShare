package com.imageshare.core.io

import android.net.Uri

fun SourceItem.toPersistedUriString(): String = uri.toString()

fun sourceItemFromPersistedUriString(uriString: String): SourceItem = SourceItem(
    uri = Uri.parse(uriString),
    mimeType = null,
    displayName = null,
    sizeBytes = null,
    width = null,
    height = null,
)
