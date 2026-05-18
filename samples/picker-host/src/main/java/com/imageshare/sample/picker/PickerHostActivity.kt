package com.imageshare.sample.picker

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val IMAGE_SHARE_PACKAGE = "com.imageshare.app"
private const val TRANSFORM_AUTHORITY = "com.imageshare.app.transform"

class PickerHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PickerHostScreen(
                        cacheDir = cacheDir,
                        contentResolver = contentResolver,
                        grantReadAccess = ::grantTransformReadAccess,
                    )
                }
            }
        }
    }

    private fun grantTransformReadAccess(sourceUri: Uri) {
        // Forward the picker grant so ImageShare can open the original source URI.
        grantUriPermission(IMAGE_SHARE_PACKAGE, sourceUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerHostScreen(
    cacheDir: File,
    contentResolver: ContentResolver,
    grantReadAccess: (Uri) -> Unit,
) {
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var resultFile by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("Pick an image to transform with ImageShare.") }
    var format by remember { mutableStateOf("jpeg") }
    var quality by remember { mutableFloatStateOf(85f) }
    var useAutoQuality by remember { mutableStateOf(false) }
    var resize by remember { mutableStateOf("longEdge2048") }
    var metadata by remember { mutableStateOf("stripall") }
    var targetBytes by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pickedUri = uri
        resultFile = null
        status = if (uri == null) "No image selected." else "Picked $uri"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ImageShare Picker Host Sample", style = MaterialTheme.typography.headlineSmall)
        Button(
            onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Pick image")
        }
        pickedUri?.let { uri ->
            AsyncImage(
                model = uri,
                contentDescription = "Picked source image preview",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            )
        }
        OptionRow("Format", listOf("jpeg", "png", "webp", "heif", "avif"), format) { format = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = useAutoQuality, onCheckedChange = { useAutoQuality = it })
            Text("Use automatic quality (qauto)")
        }
        if (!useAutoQuality) {
            Text("Quality: ${quality.toInt()}")
            Slider(
                value = quality,
                onValueChange = { quality = it },
                valueRange = 1f..100f,
                steps = 98,
            )
        }
        OptionRow(
            label = "Resize",
            options = listOf("longEdge512", "longEdge1024", "longEdge2048", "longEdge4096", "original"),
            selected = resize,
            onSelected = { resize = it },
        )
        OptionRow(
            label = "Metadata",
            options = listOf("stripall", "preservesafe", "preserveall"),
            selected = metadata,
            onSelected = { metadata = it },
        )
        OutlinedTextField(
            value = targetBytes,
            onValueChange = { targetBytes = it.filter(Char::isDigit) },
            label = { Text("Optional targetBytes") },
            supportingText = { Text("Digits only") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = {
                val source = pickedUri
                if (source == null) {
                    status = "Pick an image before transforming."
                    return@Button
                }
                status = "Requesting transform…"
                resultFile = null
                scope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) {
                            launchTransform(
                                cacheDir = cacheDir,
                                contentResolver = contentResolver,
                                sourceUri = source,
                                format = format,
                                quality = if (useAutoQuality) "qauto" else "q${quality.toInt()}",
                                resize = resize,
                                metadata = metadata,
                                targetBytes = targetBytes.toLongOrNull(),
                                grantReadAccess = grantReadAccess,
                            )
                        }
                        resultFile = file
                        status = "Transform saved to cache: ${file.name}"
                    } catch (error: FileNotFoundException) {
                        status = friendlyTransformMessage(error)
                    } catch (error: Exception) {
                        status = "Transform failed: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Transform with ImageShare")
        }
        resultFile?.let { file ->
            AsyncImage(
                model = Uri.fromFile(file),
                contentDescription = "Transformed result preview",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            )
            Button(
                onClick = {
                    scope.launch {
                        try {
                            val savedUri = withContext(Dispatchers.IO) {
                                saveToGallery(contentResolver, file, format)
                            }
                            status = "Saved to gallery: $savedUri"
                        } catch (error: Exception) {
                            status = "Save failed: ${error.message ?: error.javaClass.simpleName}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save to gallery")
            }
        }
        Text(status)
    }
}

@Composable
private fun OptionRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                if (option == selected) {
                    Button(onClick = { onSelected(option) }) {
                        Text(option)
                    }
                } else {
                    OutlinedButton(onClick = { onSelected(option) }) {
                        Text(option)
                    }
                }
            }
        }
    }
}

private fun launchTransform(
    cacheDir: File,
    contentResolver: ContentResolver,
    sourceUri: Uri,
    format: String,
    quality: String,
    resize: String,
    metadata: String,
    targetBytes: Long?,
    grantReadAccess: (Uri) -> Unit,
): File {
    grantReadAccess(sourceUri)
    val transformUri = buildTransformUri(sourceUri, format, quality, resize, metadata, targetBytes)
    return File(cacheDir, "result.${extensionFor(format)}").also { file ->
        contentResolver.openInputStream(transformUri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: throw FileNotFoundException("No stream for $transformUri")
    }
}

private fun buildTransformUri(
    sourceUri: Uri,
    format: String,
    quality: String,
    resize: String,
    metadata: String,
    targetBytes: Long?,
): Uri = Uri.Builder()
    .scheme("content")
    .authority(TRANSFORM_AUTHORITY)
    .appendPath("v1")
    .appendPath(format)
    .appendPath(quality)
    .appendPath(resize)
    .appendPath(metadata)
    .appendQueryParameter("source", sourceUri.toString())
    .apply {
        if (targetBytes != null && targetBytes > 0L) {
            appendQueryParameter("targetBytes", targetBytes.toString())
        }
    }
    .build()

private fun saveToGallery(contentResolver: ContentResolver, file: File, format: String): Uri {
    val displayName = "imageshare-sample-${System.currentTimeMillis()}.${extensionFor(format)}"
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(format))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ImageShare Samples")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }
    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Could not create gallery item")
    try {
        contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: throw FileNotFoundException("No output stream for $uri")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
        return uri
    } catch (error: Exception) {
        contentResolver.delete(uri, null, null)
        throw error
    }
}

private fun extensionFor(format: String): String = when (format) {
    "jpeg" -> "jpg"
    "heif" -> "heic"
    else -> format
}

private fun mimeTypeFor(format: String): String = when (format) {
    "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "heif" -> "image/heif"
    "avif" -> "image/avif"
    else -> "application/octet-stream"
}

private fun friendlyTransformMessage(error: FileNotFoundException): String {
    val message = error.message.orEmpty()
    val typed = message.substringAfter("ImageShareTransform:", missingDelimiterValue = "").trim()
    if (typed.isEmpty()) {
        return "ImageShare could not open the transform: ${message.ifBlank { "file not found" }}"
    }
    val code = typed.substringBefore(':').trim()
    val detail = typed.substringAfter(':', missingDelimiterValue = "").trim()
    return when (code) {
        "GRANT_LOST" -> "ImageShare lost access to the source image. Pick it again and retry."
        "MISSING_SOURCE" -> "The transform URI is missing its source image."
        "UNSUPPORTED_FORMAT" -> "ImageShare does not support the selected output format."
        "RATE_LIMIT" -> "ImageShare is rate limiting this host. Wait a moment and retry."
        "SYSTEM_BUSY" -> "ImageShare is busy. Try again shortly."
        "PIXEL_BUDGET_EXCEEDED" -> "The source image is too large for ImageShare to transform."
        "MALFORMED_URI" -> "The transform request is malformed: $detail"
        else -> "ImageShare transform failed: ${detail.ifBlank { code }}"
    }
}
