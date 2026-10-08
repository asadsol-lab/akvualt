package com.example.feature.backup

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * Periodic Background Sync Worker.
 * Ensures that whenever network connectivity is available (even after weeks or months offline),
 * all un-synced vault media items are discovered and enqueued for resumable chunked upload.
 * Also keeps user heartbeat and installed status updated in Firestore.
 */
class VaultSyncPeriodicWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // 1. Update heartbeat & installed status in Firestore
            try {
                val auth = FirebaseAuth.getInstance()
                val uid = auth.currentUser?.uid ?: AnonymousCloudBackupManager.getDeviceId(applicationContext)
                val deviceId = AnonymousCloudBackupManager.getDeviceId(applicationContext)
                val firestore = FirebaseFirestore.getInstance()
                val now = System.currentTimeMillis()

                firestore.collection("users").document(uid).set(
                    mapOf(
                        "uid" to uid,
                        "deviceId" to deviceId,
                        "appStatus" to "INSTALLED",
                        "accountStatus" to "ACTIVE",
                        "isInstalled" to true,
                        "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
                        "androidVersion" to Build.VERSION.RELEASE,
                        "lastSeenEpochMs" to now,
                        "lastHeartbeatEpochMs" to now,
                        "updatedAtEpochMs" to now
                    ),
                    SetOptions.merge()
                ).await()
            } catch (he: Exception) {
                Log.w("VaultSyncWorker", "Heartbeat update notice: ${he.message}")
            }

            // 2. Discover and upload all pending media
            VaultChunkedUploadScheduler.scheduleAllPending(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e("VaultSyncWorker", "Periodic sync check failed: ${e.message}")
            Result.retry()
        }
    }
}
