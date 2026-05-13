@file:Suppress("TooManyFunctions", "ReturnCount")

package com.imageshare.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.imageshare.app.AppContainer
import com.imageshare.app.R
import com.imageshare.app.data.BatchManifestEntity
import com.imageshare.app.processing.BatchOrchestrator
import com.imageshare.app.processing.PresetPipeline
import com.imageshare.core.io.sourceItemFromPersistedUriString
import com.imageshare.feature.preset.Preset
import kotlinx.coroutines.CancellationException

class BatchProcessWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val presetId = inputData.getString(KEY_PRESET_ID) ?: return Result.failure()
        val preset = resolvePreset(presetId) ?: return Result.failure()
        val manifest = AppContainer.batchManifestDao.forJob(jobId)
        if (manifest.isEmpty()) return Result.failure()

        createChannel()
        setForeground(makeForegroundInfo(completed = 0, total = manifest.size))

        val remaining = manifest.filter { it.state == STATE_PENDING }
        if (remaining.isEmpty()) return Result.success()

        return runCatching {
            val workerPreset = preset.withWorkerOverride(inputData.getString(KEY_CUSTOM_OVERRIDE_JSON))
            runBatch(jobId, remaining, workerPreset, manifest.size)
            Result.success()
        }.getOrElse { error ->
            if (error is CancellationException) {
                markPendingCancelled(jobId)
                throw error
            }
            Result.failure()
        }
    }

    private suspend fun runBatch(
        jobId: String,
        remaining: List<BatchManifestEntity>,
        preset: Preset,
        total: Int,
    ) {
        val sources = remaining.map { sourceItemFromPersistedUriString(it.sourceUriString) }
        AppContainer.activeBatchOrchestrator.run(jobId, sources, preset).collect { progress ->
            progress.items.forEachIndexed { index, item ->
                val updated = remaining[index].withItemState(item.state)
                if (updated.state != STATE_PENDING) AppContainer.batchManifestDao.update(updated)
            }
            val completed = AppContainer.batchManifestDao.forJob(jobId).count { it.state != STATE_PENDING }
            setProgress(workDataOf(KEY_PROGRESS_COMPLETED to completed, KEY_PROGRESS_TOTAL to total))
            setForeground(makeForegroundInfo(completed, total))
        }
    }

    private suspend fun markPendingCancelled(jobId: String) {
        AppContainer.batchManifestDao.forJob(jobId)
            .filter { it.state == STATE_PENDING }
            .forEach { AppContainer.batchManifestDao.update(it.copy(state = STATE_CANCELLED, updatedAt = now())) }
    }

    private suspend fun resolvePreset(presetId: String): Preset? =
        AppContainer.activePresetRepository.getPreset(presetId)

    private fun makeForegroundInfo(completed: Int, total: Int): ForegroundInfo {
        val title = applicationContext.getStringOrFallback(
            R.string.batch_notification_title,
            "Processing images",
        )
        val text = applicationContext.getStringOrFallback(
            R.string.batch_notification_text,
            "Processed $completed of $total images",
            completed,
            total,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, completed, total == 0)
            .addAction(
                R.drawable.ic_notification,
                applicationContext.getStringOrFallback(R.string.cancel_batch, "Cancel"),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getStringOrFallback(R.string.batch_channel_name, "Batch processing"),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = applicationContext.getStringOrFallback(
                R.string.batch_channel_description,
                "Progress for image processing batches.",
            )
        }
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    companion object {
        const val KEY_JOB_ID = "jobId"
        const val KEY_PRESET_ID = "presetId"
        const val KEY_CUSTOM_OVERRIDE_JSON = "customOverrideJson"
        const val KEY_PROGRESS_COMPLETED = "completed"
        const val KEY_PROGRESS_TOTAL = "total"
        const val CHANNEL_ID = "batch_processing"
        const val NOTIFICATION_ID = 1001

        const val STATE_PENDING = "Pending"
        const val STATE_DONE = "Done"
        const val STATE_FAILED = "Failed"
        const val STATE_CANCELLED = "Cancelled"

        fun uniqueWorkName(jobId: String): String = "batch_$jobId"
    }
}

private fun BatchManifestEntity.withItemState(state: BatchOrchestrator.ItemState): BatchManifestEntity = when (state) {
    is BatchOrchestrator.ItemState.Done -> when (val result = state.result) {
        is PresetPipeline.Result.Success -> copy(
            state = BatchProcessWorker.STATE_DONE,
            storedFilePath = result.stored.file.absolutePath,
            outputMimeType = result.stored.mimeType,
            errorMessage = null,
            updatedAt = now(),
        )
        is PresetPipeline.Result.Failure -> copy(
            state = BatchProcessWorker.STATE_FAILED,
            errorMessage = result.cause.message,
            updatedAt = now(),
        )
    }
    BatchOrchestrator.ItemState.Cancelled -> copy(state = BatchProcessWorker.STATE_CANCELLED, updatedAt = now())
    BatchOrchestrator.ItemState.Pending,
    is BatchOrchestrator.ItemState.Running,
    -> this
}

private fun now(): Long = System.currentTimeMillis()

private fun Context.getStringOrFallback(resId: Int, fallback: String, vararg formatArgs: Any): String =
    runCatching {
        if (formatArgs.isEmpty()) getString(resId) else getString(resId, *formatArgs)
    }.getOrDefault(fallback)
