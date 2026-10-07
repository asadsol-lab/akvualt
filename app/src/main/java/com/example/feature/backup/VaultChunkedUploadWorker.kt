package com.example.feature.backup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.VaultDatabase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * 100% Silent Background Upload Worker.
 * Executes quietly in background without displaying any notifications in the Android notification shade.
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

        try {
            // Upload chunks completely silently with zero user-facing notification
            val uploadResult = CloudinaryResumableChunkUploader.uploadFileChunked(
                context = applicationContext,
                mediaId = mediaId,
                file = file,
                mimeType = mimeType,
                fileName = fileName,
                onProgress = null
            )

            if (uploadResult.isSuccess) {
                val result = uploadResult.getOrThrow()
                val auth = FirebaseAuth.getInstance()
                val uid = auth.currentUser?.uid ?: "anonymous_user"
                val isVideo = mimeType.startsWith("video/")

                // Sync metadata to Firestore
                try {
                    val firestore = FirebaseFirestore.getInstance()
                    val metadataMap = mapOf(
                        "mediaId" to mediaId,
                        "fileName" to fileName,
                        "mediaType" to (if (isVideo) "VIDEO" else "PHOTO"),
                        "sizeBytes" to file.length(),
                        "durationMs" to durationMs,
                        "isEncryptedLocally" to false,
                        "cloudinaryPublicId" to result.publicId,
                        "cloudinarySecureUrl" to result.secureUrl,
                        "backupStatus" to "SUCCESS",
                        "backupTimestampMs" to System.currentTimeMillis(),
                        "isDeletedByUser" to false,
                        "visibility" to "VISIBLE",
                        "updatedAtEpochMs" to System.currentTimeMillis()
                    )
                    firestore.collection("users")
                        .document(uid)
                        .collection("vault_media")
                        .document(mediaId)
                        .set(metadataMap, SetOptions.merge())
                        .await()
                } catch (fe: Exception) {
                    Log.w(TAG, "Failed to write metadata to Firestore: ${fe.message}")
                }

                // Note: Silent background uploads do NOT mark isCloudSynced on local thumbnails,
                // keeping the local vault completely discrete.
                return Result.success()
            } else {
                val error = uploadResult.exceptionOrNull()
                Log.e(TAG, "Resumable upload failed for $fileName: ${error?.message}")
                return Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Worker exception for $fileName: ${e.message}", e)
            return Result.retry()
        }
    }
}
