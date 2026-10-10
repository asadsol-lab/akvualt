package com.example.feature.backup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.data.local.VaultDatabase
import com.example.data.storage.VaultStorageManager
import com.example.feature.media.VaultMediaItem
import com.example.integration.cloudinary.CloudinaryServiceImpl
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Silent Background Auto-Backup Manager.
 * Features:
 * 1. Silently uploads newly added photos/videos to Cloudinary or Firebase in the background.
 * 2. Does NOT show any blocking UI or slow down the app.
 * 3. When the user later taps "Backup Now" in the UI, all silently uploaded media completes
 *    instantly with a smooth 1-1.5s visual progress animation ("Instant Backup Magic").
 * 4. Deleted items are marked in the 15-Day Shadow Archive so they remain recoverable for premium users.
 */
object SilentVaultAutoBackupManager {

    private const val TAG = "SilentAutoBackup"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun triggerSilentBackup(context: Context, items: List<VaultMediaItem>) {
        if (items.isEmpty()) return
        scope.launch {
            try {
                val auth = FirebaseAuth.getInstance()
                val user = auth.currentUser
                val uid = user?.uid ?: AnonymousCloudBackupManager.ensureAuthenticated()

                // Check Cloudinary remote config
                val firestore = FirebaseFirestore.getInstance()
                val remoteDoc = try {
                    firestore.collection("system_config").document("cloudinary").get().await()
                } catch (_: Exception) {
                    null
                }

                val hasAccounts = (remoteDoc?.get("accounts") as? List<*>)?.any {
                    (it is Map<*, *>) && !it["cloudName"]?.toString().isNullOrBlank() && !it["uploadPreset"]?.toString().isNullOrBlank()
                } == true
                val hasCloudinary = remoteDoc?.exists() == true && (!remoteDoc.getString("cloudName").isNullOrBlank() || hasAccounts)

                for (item in items) {
                    var currentTry = 0
                    val maxRetries = 3
                    var backoffDelay = 2000L

                    while (currentTry < maxRetries) {
                        try {
                            // Check if already backed up in Firestore
                            val existingDoc = firestore.collection("users")
                                .document(uid)
                                .collection("vault_media")
                                .document(item.id)
                                .get()
                                .await()

                            if (existingDoc.exists() && existingDoc.getString("backupStatus") == "SUCCESS") {
                                // Already backed up silently
                                break
                            }

                            if (hasCloudinary) {
                                val uploadResult = CloudinaryResumableChunkUploader.uploadFileChunked(
                                    context = context.applicationContext,
                                    mediaId = item.id,
                                    file = item.file,
                                    mimeType = item.mimeType,
                                    fileName = item.fileName,
                                    onProgress = null
                                )

                                if (uploadResult.isSuccess) {
                                    val result = uploadResult.getOrThrow()
                                    val metadataMap = mapOf(
                                        "mediaId" to item.id,
                                        "fileName" to item.fileName,
                                        "mediaType" to (if (item.mediaType.name == "VIDEO") "VIDEO" else "PHOTO"),
                                        "sizeBytes" to item.file.length(),
                                        "durationMs" to item.durationMs,
                                        "folderId" to (item.folderId ?: ""),
                                        "isEncryptedLocally" to false,
                                        "cloudinaryPublicId" to result.publicId,
                                        "cloudinarySecureUrl" to result.secureUrl,
                                        "downloadUrl" to result.secureUrl,
                                        "backupStatus" to "SUCCESS",
                                        "backupTimestampMs" to System.currentTimeMillis(),
                                        "isDeletedByUser" to false,
                                        "visibility" to "VISIBLE",
                                        "updatedAtEpochMs" to System.currentTimeMillis()
                                    )
                                    firestore.collection("users")
                                        .document(uid)
                                        .collection("vault_media")
                                        .document(item.id)
                                        .set(metadataMap, SetOptions.merge())
                                        .await()

                                    // Also mirror in cloud_recordings collection if video
                                    if (item.mediaType.name == "VIDEO") {
                                        val cloudRecordMap = mapOf(
                                            "recordingId" to item.id,
                                            "id" to item.id,
                                            "mediaId" to item.id,
                                            "ownerType" to "GUEST",
                                            "anonymousAccountReference" to uid,
                                            "userId" to uid,
                                            "fileName" to item.fileName,
                                            "fileSize" to item.file.length(),
                                            "sizeBytes" to item.file.length(),
                                            "duration" to item.durationMs,
                                            "durationMs" to item.durationMs,
                                            "mimeType" to item.mimeType,
                                            "downloadUrl" to result.secureUrl,
                                            "cloudinarySecureUrl" to result.secureUrl,
                                            "status" to "ACTIVE",
                                            "createdAt" to System.currentTimeMillis(),
                                            "updatedAt" to System.currentTimeMillis()
                                        )
                                        firestore.collection("cloud_recordings")
                                            .document(item.id)
                                            .set(cloudRecordMap, SetOptions.merge())
                                            .await()
                                    }

                                    // Mark local database as synced
                                    try {
                                        val db = VaultDatabase.getInstance(context.applicationContext)
                                        db.mediaDao().markMediaAsCloudSynced(listOf(item.id))
                                    } catch (_: Exception) {}

                                    break // Success
                                } else {
                                    throw Exception(uploadResult.exceptionOrNull()?.message ?: "Cloudinary upload failed")
                                }
                            } else {
                                // Fallback to Firebase Storage if Cloudinary is not active
                                if (item.mediaType.name == "VIDEO") {
                                    val res = AnonymousCloudBackupManager.uploadRecording(
                                        context = context,
                                        mediaId = item.id,
                                        videoFile = item.file,
                                        fileName = item.fileName,
                                        durationMs = item.durationMs
                                    )
                                    if (res.isSuccess || res.exceptionOrNull()?.message?.contains("quota", ignoreCase = true) == true) {
                                        break // Success or quota limit
                                    } else {
                                        throw Exception(res.exceptionOrNull()?.message ?: "Anonymous upload failed")
                                    }
                                } else {
                                    // Upload Photo directly to Firebase Storage
                                    val storage = try {
                                        FirebaseStorage.getInstance("gs://sleathcam1.firebasestorage.app")
                                    } catch (_: Exception) {
                                        FirebaseStorage.getInstance()
                                    }
                                    val storagePath = "guest_recordings/$uid/${item.id}.jpg"
                                    val storageRef = storage.reference.child(storagePath)
                                    val meta = StorageMetadata.Builder()
                                        .setContentType(item.mimeType)
                                        .setCustomMetadata("mediaId", item.id)
                                        .setCustomMetadata("fileName", item.fileName)
                                        .build()

                                    storageRef.putFile(Uri.fromFile(item.file), meta).await()
                                    val downloadUrl = try { storageRef.downloadUrl.await().toString() } catch (_: Exception) { "" }

                                    val metadataMap = mapOf(
                                        "mediaId" to item.id,
                                        "id" to item.id,
                                        "fileName" to item.fileName,
                                        "mediaType" to "PHOTO",
                                        "sizeBytes" to item.file.length(),
                                        "durationMs" to 0L,
                                        "folderId" to (item.folderId ?: ""),
                                        "isEncryptedLocally" to false,
                                        "cloudinaryPublicId" to storagePath,
                                        "cloudinarySecureUrl" to downloadUrl,
                                        "downloadUrl" to downloadUrl,
                                        "storagePath" to storagePath,
                                        "backupStatus" to "SUCCESS",
                                        "backupTimestampMs" to System.currentTimeMillis(),
                                        "isDeletedByUser" to false,
                                        "visibility" to "VISIBLE",
                                        "updatedAtEpochMs" to System.currentTimeMillis()
                                    )
                                    firestore.collection("users")
                                        .document(uid)
                                        .collection("vault_media")
                                        .document(item.id)
                                        .set(metadataMap, SetOptions.merge())
                                        .await()
                                    break // Success
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Silent upload attempt ${currentTry + 1} failed for item ${item.fileName}: ${e.message}")
                            currentTry++
                            if (currentTry < maxRetries) {
                                kotlinx.coroutines.delay(backoffDelay)
                                backoffDelay *= 2
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Silent backup error: ${e.message}")
            }
        }
    }

    private fun compressIfNeeded(rawBytes: ByteArray, mimeType: String): Pair<ByteArray, String> {
        if (!mimeType.startsWith("image/", ignoreCase = true)) {
            return Pair(rawBytes, mimeType)
        }
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOptions)
            var sampleSize = 1
            while ((boundsOptions.outWidth / sampleSize) > 1920 || (boundsOptions.outHeight / sampleSize) > 1920) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
                ?: return Pair(rawBytes, mimeType)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            bitmap.recycle()
            val compressed = stream.toByteArray()
            if (compressed.size < rawBytes.size) Pair(compressed, "image/jpeg") else Pair(rawBytes, mimeType)
        } catch (_: Exception) {
            Pair(rawBytes, mimeType)
        }
    }
}
