package com.imageshare.core.io

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.Composable

/**
 * Remembers image-only photo picker launchers.
 *
 * When [maxItems] is [Int.MAX_VALUE] (default) the system uses its own cap
 * (`MediaStore.getPickImagesMaxLimit()` on Android 13+, or 1 image on devices without the modern
 * picker via the SAF fallback). When a smaller cap is desired, pass it here; once bound to the
 * remembered launcher, it applies to every subsequent multi-pick invocation.
 */
@Composable
fun rememberPhotoPickerLauncher(
    onResult: (List<Uri>) -> Unit,
    maxItems: Int = Int.MAX_VALUE,
): PhotoPickerLauncher {
    require(maxItems >= 2 || maxItems == Int.MAX_VALUE) {
        "maxItems must be >= 2 (or Int.MAX_VALUE for default)"
    }
    val singleLauncher = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        onResult(uri?.let(::listOf).orEmpty())
    }
    val multipleContract = if (maxItems == Int.MAX_VALUE) {
        PickMultipleVisualMedia()
    } else {
        PickMultipleVisualMedia(maxItems)
    }
    val multipleLauncher = rememberLauncherForActivityResult(multipleContract) { uris ->
        onResult(uris)
    }

    return PhotoPickerLauncher(singleLauncher, multipleLauncher)
}

class PhotoPickerLauncher internal constructor(
    private val singleLauncher: ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?>,
    private val multipleLauncher: ManagedActivityResultLauncher<PickVisualMediaRequest, List<Uri>>,
) {
    fun launchSingle() {
        singleLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
    }

    fun launchMultiple() {
        multipleLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
    }
}
