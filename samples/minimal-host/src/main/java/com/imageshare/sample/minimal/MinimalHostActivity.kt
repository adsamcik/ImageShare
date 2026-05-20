package com.imageshare.sample.minimal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val IMAGE_SHARE_PACKAGE = "com.imageshare.app"
private const val TRANSFORM_AUTHORITY = "com.imageshare.app.transform"
private const val DEFAULT_SOURCE = "content://media/external/images/media/1"

class MinimalHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MinimalHostScreen(cacheDir, ::grantTransformReadAccess, contentResolver::openInputStream)
                }
            }
        }
    }

    private fun grantTransformReadAccess(sourceUri: Uri) {
        // Forward the host app's read grant so ImageShare can open the original source URI.
        grantUriPermission(IMAGE_SHARE_PACKAGE, sourceUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

@Composable
private fun MinimalHostScreen(
    cacheDir: File,
    grantReadAccess: (Uri) -> Unit,
    openInputStream: (Uri) -> java.io.InputStream?,
) {
    var source by remember { mutableStateOf(DEFAULT_SOURCE) }
    var format by remember { mutableStateOf("jpeg") }
    var status by remember { mutableStateOf("Enter a source URI, choose a format, then transform.") }
    var resultFile by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()
    val formats = listOf("jpeg", "png", "webp")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ImageShare Minimal Host Sample", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = source,
            onValueChange = { source = it },
            label = { Text("Source content URI") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            formats.forEach { option ->
                val selected = option == format
                if (selected) {
                    Button(onClick = { format = option }) {
                        Text(option)
                    }
                } else {
                    OutlinedButton(onClick = { format = option }) {
                        Text(option)
                    }
                }
            }
        }
        Button(
            onClick = {
                val sourceUri = Uri.parse(source)
                val transformUri = buildMinimalTransformUri(source, format)
                status = "Requesting transform…"
                resultFile = null
                scope.launch {
                    try {
                        grantReadAccess(sourceUri)
                        val file = withContext(Dispatchers.IO) {
                            openInputStream(transformUri)?.use { stream ->
                                File(cacheDir, "minimal-result.${extensionFor(format)}").also { file ->
                                    file.outputStream().use { output -> stream.copyTo(output) }
                                }
                            } ?: throw FileNotFoundException("No stream for $transformUri")
                        }
                        resultFile = file
                        status = "Transform cached: ${file.name}"
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
        Text(status)
        resultFile?.let { file ->
            AsyncImage(
                model = file,
                contentDescription = "Transformed image preview",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
            )
        }
    }
}

private fun buildMinimalTransformUri(source: String, format: String): Uri = Uri.parse(
    "content://$TRANSFORM_AUTHORITY/v1/$format/q85/longEdge2048/stripall" +
        "?source=${Uri.encode(source)}",
)

private fun extensionFor(format: String): String = when (format) {
    "jpeg" -> "jpg"
    else -> format
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
        "UNSUPPORTED_FORMAT" -> {
            val reason = detail.ifBlank { "the selected output format is unavailable on this device" }
            "ImageShare cannot create that output here: $reason"
        }
        "RATE_LIMIT" -> "ImageShare is rate limiting this host. Wait a moment and retry."
        "SYSTEM_BUSY" -> "ImageShare is busy. Try again shortly."
        "PIXEL_BUDGET_EXCEEDED" -> "The source image is too large for ImageShare to transform."
        "MALFORMED_URI" -> "The transform request is malformed: $detail"
        else -> "ImageShare transform failed: ${detail.ifBlank { code }}"
    }
}
