package com.example.feature.backup

import android.content.Context
import android.util.Log
import com.example.integration.cloudinary.CloudinaryAccount
import com.example.integration.cloudinary.CloudinaryConfig
import com.example.integration.cloudinary.CloudinaryConfigManager
import com.example.integration.cloudinary.CloudinaryUploadResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

object CloudinaryResumableChunkUploader {
    private const val TAG = "ResumableChunkUploader"
    private const val PREFS_NAME = "vault_resumable_upload_offsets"
    // Cloudinary requires minimum 5 MB (5,242,880 bytes) per chunk for video/upload_large
    const val CHUNK_SIZE = 5 * 1024 * 1024L 

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun getSavedOffset(context: Context, mediaId: String): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong("offset_$mediaId", 0L)
    }

    private fun saveOffset(context: Context, mediaId: String, offset: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong("offset_$mediaId", offset).apply()
    }

    fun clearSavedOffset(context: Context, mediaId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove("offset_$mediaId").apply()
    }

    suspend fun uploadFileChunked(
        context: Context,
        mediaId: String,
        file: File,
        mimeType: String,
        fileName: String,
        onProgress: ((uploadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Result<CloudinaryUploadResult> = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= 0) {
            return@withContext Result.failure(IOException("File not found or empty: ${file.absolutePath}"))
        }

        val totalLength = file.length()
        val auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid ?: "anonymous_user"
        val folder = "users/$uid/vault_media"
        val isVideo = mimeType.startsWith("video/")
        val resourceType = if (isVideo) "video" else "auto"

        // 1. Fetch live pool from Firestore or cached config
        val firestore = FirebaseFirestore.getInstance()
        var config = CloudinaryConfig()
        try {
            val remoteDoc = firestore.collection("system_config").document("cloudinary").get().await()
            if (remoteDoc.exists()) {
                val rawAccounts = remoteDoc.get("accounts") as? List<*>
                val parsedAccounts = mutableListOf<CloudinaryAccount>()
                if (rawAccounts != null) {
                    for (item in rawAccounts) {
                        if (item is Map<*, *>) {
                            val cName = item["cloudName"]?.toString() ?: ""
                            val preset = item["uploadPreset"]?.toString() ?: ""
                            val label = item["label"]?.toString() ?: ""
                            val enabled = item["enabled"] as? Boolean ?: true
                            if (cName.isNotBlank() && preset.isNotBlank()) {
                                parsedAccounts.add(CloudinaryAccount(cName, preset, label, enabled))
                            }
                        }
                    }
                }
                config = CloudinaryConfig(
                    cloudName = remoteDoc.getString("cloudName") ?: "",
                    uploadPreset = remoteDoc.getString("uploadPreset") ?: "",
                    apiKey = remoteDoc.getString("apiKey") ?: "",
                    apiSecret = remoteDoc.getString("apiSecret") ?: "",
                    accounts = parsedAccounts
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load live remote cloudinary config, falling back to local: ${e.message}")
            config = CloudinaryConfigManager.getConfig(context)
        }

        val pool = config.getActiveAccountsPool()
        if (pool.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No active Cloudinary accounts/presets configured in Admin Panel."))
        }

        // Unique identifier for Cloudinary to assemble chunks for this media
        val uniqueUploadId = "vlt_${mediaId.replace("-", "").take(24)}"

        var lastError: Exception? = null

        // Try accounts in pool (Auto Failover if quota is exceeded)
        for (account in pool) {
            try {
                Log.d(TAG, "Attempting resumable chunked upload to Cloudinary [${account.cloudName}] for media $mediaId (Total: $totalLength bytes)")

                var currentOffset = getSavedOffset(context, mediaId)
                if (currentOffset >= totalLength) {
                    // Stale or already completed offset, reset
                    currentOffset = 0L
                    saveOffset(context, mediaId, 0L)
                }

                var raf: RandomAccessFile? = null
                try {
                    raf = RandomAccessFile(file, "r")
                    var finalResult: CloudinaryUploadResult? = null

                    while (currentOffset < totalLength) {
                        val endOffset = minOf(currentOffset + CHUNK_SIZE - 1, totalLength - 1)
                        val chunkLength = (endOffset - currentOffset + 1).toInt()

                        val chunkBytes = ByteArray(chunkLength)
                        raf.seek(currentOffset)
                        raf.readFully(chunkBytes)

                        val isFinalChunk = (endOffset == totalLength - 1)
                        val url = "https://api.cloudinary.com/v1_1/${account.cloudName}/$resourceType/upload"

                        val requestBodyBuilder = MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart(
                                "file",
                                fileName,
                                chunkBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                            )
                            .addFormDataPart("upload_preset", account.uploadPreset)
                            .addFormDataPart("folder", folder)
                            .addFormDataPart("public_id", mediaId)

                        val request = Request.Builder()
                            .url(url)
                            .addHeader("X-Unique-Upload-Id", uniqueUploadId)
                            .addHeader("Content-Range", "bytes $currentOffset-$endOffset/$totalLength")
                            .post(requestBodyBuilder.build())
                            .build()

                        val response = okHttpClient.newCall(request).execute()
                        val responseBody = response.body?.string() ?: ""

                        if (!response.isSuccessful) {
                            val errorMsg = try {
                                val errObj = JSONObject(responseBody)
                                errObj.optJSONObject("error")?.optString("message") ?: responseBody
                            } catch (_: Exception) {
                                responseBody
                            }
                            Log.e(TAG, "Chunk upload failed on [${account.cloudName}]: HTTP ${response.code} - $errorMsg")
                            
                            // If quota or client error, throw to trigger failover to next account in pool
                            if (response.code in 400..499 && (errorMsg.contains("quota", ignoreCase = true) || errorMsg.contains("disabled", ignoreCase = true))) {
                                throw IOException("Account quota/limit reached: $errorMsg")
                            } else {
                                throw IOException("Upload failed ($currentOffset-$endOffset): $errorMsg")
                            }
                        }

                        // Chunk uploaded successfully! Save progress
                        currentOffset = endOffset + 1
                        saveOffset(context, mediaId, currentOffset)
                        onProgress?.invoke(currentOffset, totalLength)

                        if (isFinalChunk) {
                            val json = JSONObject(responseBody)
                            val resPublicId = json.optString("public_id", "users/$uid/vault_media/$mediaId")
                            val secureUrl = json.optString("secure_url", "")
                            val assetType = json.optString("resource_type", resourceType)
                            val bytes = json.optLong("bytes", totalLength)

                            finalResult = CloudinaryUploadResult(
                                publicId = resPublicId,
                                secureUrl = secureUrl,
                                assetType = assetType,
                                bytes = bytes
                            )
                            break
                        }
                    }

                    if (finalResult != null) {
                        clearSavedOffset(context, mediaId)
                        Log.i(TAG, "Successfully uploaded media $mediaId to Cloudinary [${account.cloudName}]. Secure URL: ${finalResult.secureUrl}")
                        return@withContext Result.success(finalResult)
                    }
                } finally {
                    try { raf?.close() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed upload with account ${account.cloudName}: ${e.message}")
                lastError = e
                // Continue to next account in pool
            }
        }

        Result.failure(lastError ?: IOException("All Cloudinary upload presets in pool failed."))
    }
}
