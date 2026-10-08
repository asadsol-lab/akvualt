package com.example.feature.backup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic Background Sync Worker.
 * Ensures that whenever network connectivity is available (even after weeks or months offline),
 * all un-synced vault media items are discovered and enqueued for resumable chunked upload.
 */
class VaultSyncPeriodicWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            VaultChunkedUploadScheduler.scheduleAllPending(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e("VaultSyncWorker", "Periodic sync check failed: ${e.message}")
            Result.retry()
        }
    }
}
