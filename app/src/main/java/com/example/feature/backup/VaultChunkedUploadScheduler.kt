package com.example.feature.backup

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.local.VaultDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object VaultChunkedUploadScheduler {
    private const val TAG = "VaultUploadScheduler"

    /**
     * Enqueue a reliable background upload for a single media item.
     * Guaranteed to survive app closure, device reboot, and network reconnection.
     */
    fun scheduleUpload(
        context: Context,
        mediaId: String,
        file: File,
        fileName: String,
        mimeType: String,
        durationMs: Long = 0L
    ) {
        if (!file.exists() || file.length() <= 0L) {
            Log.w(TAG, "Cannot schedule upload for missing/empty file: ${file.absolutePath}")
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = Data.Builder()
            .putString(VaultChunkedUploadWorker.KEY_MEDIA_ID, mediaId)
            .putString(VaultChunkedUploadWorker.KEY_FILE_PATH, file.absolutePath)
            .putString(VaultChunkedUploadWorker.KEY_FILE_NAME, fileName)
            .putString(VaultChunkedUploadWorker.KEY_MIME_TYPE, mimeType)
            .putLong(VaultChunkedUploadWorker.KEY_DURATION_MS, durationMs)
            .build()

        val uploadRequest = OneTimeWorkRequestBuilder<VaultChunkedUploadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                15,
                TimeUnit.SECONDS
            )
            .addTag("vault_upload")
            .addTag("media_$mediaId")
            .build()

        // Keep existing work if already queued/running to avoid duplicate uploads
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "upload_$mediaId",
            ExistingWorkPolicy.KEEP,
            uploadRequest
        )

        Log.i(TAG, "Enqueued WorkManager background upload for $fileName ($mediaId), size: ${file.length()} bytes")
    }

    /**
     * Finds any un-synced items in the local database and enqueues them for background upload.
     */
    suspend fun scheduleAllPending(context: Context) = withContext(Dispatchers.IO) {
        try {
            val db = VaultDatabase.getInstance(context.applicationContext)
            val allMedia = db.mediaDao().getAllActiveMedia().firstOrNull() ?: emptyList()
            val unSynced = allMedia.filter { !it.isCloudSynced }
            val storageManager = com.example.data.storage.VaultStorageManager(context.applicationContext)

            for (item in unSynced) {
                val file = storageManager.getMediaFile(item.relativePath)
                if (file.exists() && file.length() > 0L) {
                    scheduleUpload(
                        context = context,
                        mediaId = item.id,
                        file = file,
                        fileName = item.fileName,
                        mimeType = item.mimeType,
                        durationMs = item.durationMs
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule all pending uploads: ${e.message}", e)
        }
    }

    /**
     * Enqueues a periodic background check that guarantees even if the device was offline
     * for weeks or months, all pending media files are automatically picked up and uploaded
     * as soon as internet connectivity returns.
     */
    fun schedulePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<VaultSyncPeriodicWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag("vault_periodic_sync")
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            "vault_periodic_sync_work",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
        Log.i(TAG, "Registered periodic background upload sync in WorkManager")
    }
}
