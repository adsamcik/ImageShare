package com.imageshare.app

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.imageshare.core.io.InputCoordinator
import com.imageshare.core.io.SharedIntakeStager
import com.imageshare.core.io.SourceItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: SharedIntakeViewModel by lazy {
        ViewModelProvider(this)[SharedIntakeViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val sources by viewModel.sources.collectAsState()
            ImageShareApp(sources)
        }
        if (savedInstanceState == null) {
            handleShareIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        val sharedUris = intent.extractImageShareUris()
        if (sharedUris.isEmpty()) {
            return
        }

        val repository = AndroidSharedIntakeRepository(contentResolver, sharedIntakeCacheRoot())
        val jobId = "share-${System.currentTimeMillis()}-${(0 until SHARE_RANDOM_BOUND).random()}"
        lifecycleScope.launch {
            viewModel.stageSharedUris(jobId, sharedUris, repository)
        }
    }

    private fun sharedIntakeCacheRoot(): File =
        File(cacheDir, SHARED_INTAKE_CACHE_DIR).apply { mkdirs() }
}

@Composable
fun ImageShareApp(sources: List<StagedSource> = emptyList()) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (sources.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = "Hello ImageShare", modifier = Modifier.padding(top = 32.dp))
                    Text(text = "Share an image to this app to begin.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().testTag("staged-sources")) {
                    items(sources) { source ->
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = source.displayName ?: "Unnamed image")
                            Text(text = "Size: ${source.sizeBytes ?: "unknown"} bytes")
                            Text(text = source.cachedUri.toString())
                        }
                    }
                }
            }
        }
    }
}

data class StagedSource(
    val cachedUri: Uri,
    val displayName: String?,
    val sizeBytes: Long?,
    val sourceItem: SourceItem? = null,
)

class SharedIntakeViewModel : ViewModel() {
    private val mutableSources = MutableStateFlow<List<StagedSource>>(emptyList())
    val sources: StateFlow<List<StagedSource>> = mutableSources.asStateFlow()

    suspend fun stageSharedUris(
        jobId: String,
        uris: List<Uri>,
        repository: SharedIntakeRepository,
    ) {
        mutableSources.value = repository.stage(jobId, uris)
        repository.sweep()
    }
}

interface SharedIntakeRepository {
    suspend fun stage(jobId: String, uris: List<Uri>): List<StagedSource>
    suspend fun sweep()
}

private class AndroidSharedIntakeRepository(
    private val resolver: ContentResolver,
    private val cacheRoot: java.io.File,
) : SharedIntakeRepository {
    override suspend fun stage(jobId: String, uris: List<Uri>): List<StagedSource> {
        val stager = SharedIntakeStager(resolver, cacheRoot)
        val coordinator = InputCoordinator(resolver)
        return stager.stage(jobId, uris).map { staged ->
            val sourceItem = runCatching { coordinator.resolve(staged.cachedUri) }.getOrNull()
            StagedSource(
                cachedUri = staged.cachedUri,
                displayName = sourceItem?.displayName ?: staged.displayName,
                sizeBytes = sourceItem?.sizeBytes ?: staged.sizeBytes ?: staged.cachedFile.length(),
                sourceItem = sourceItem,
            )
        }
    }

    override suspend fun sweep() {
        SharedIntakeStager(resolver, cacheRoot).sweep()
    }
}

private fun Intent?.extractImageShareUris(): List<Uri> {
    if (this == null) {
        return emptyList()
    }

    return when (action) {
        Intent.ACTION_SEND -> listOfNotNull(getParcelableExtraCompat(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> getParcelableArrayListExtraCompat(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }
}

private fun Intent.getParcelableExtraCompat(name: String): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }

private fun Intent.getParcelableArrayListExtraCompat(name: String): ArrayList<Uri>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(name, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra(name)
    }

private const val SHARE_RANDOM_BOUND = 10_000
private const val SHARED_INTAKE_CACHE_DIR = "shared-intake"
