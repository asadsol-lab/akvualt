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
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

object CloudinaryResumableChunkUploader {
    private const val TAG = "ResumableChunkUploader"

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Resilient upload function:
     * 1. Loads remote Cloudinary accounts pool from Firestore (system_config/cloudinary).
     * 2. Iterates across all active accounts with auto-failover.
     * 3. For each account, first attempts direct streaming upload with folder and public_id.
     *    If rejected by unsigned preset restrictions (e.g. HTTP 400 with "not allowed"),
     *    immediately retries with ONLY file and upload_preset so ANY admin preset works 100%.
     * 4. For very large files (>70 MB), uses chunked video upload.
     */
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
        val uid = auth.currentUser?.uid ?: AnonymousCloudBackupManager.getDeviceId(context)
        val folder = "users/$uid/vault_media"
        val isVideo = mimeType.startsWith("video/")
        val actualFileName = if (fileName.isNotBlank()) fileName else "media_$mediaId.${if (isVideo) "mp4" else "jpg"}"

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
                            val cName = item["cloudName"]?.toString()?.trim() ?: ""
                            val preset = item["uploadPreset"]?.toString()?.trim() ?: ""
                            val label = item["label"]?.toString()?.trim() ?: ""
                            val enabled = item["enabled"] as? Boolean ?: true
                            if (cName.isNotBlank() && preset.isNotBlank()) {
                                parsedAccounts.add(CloudinaryAccount(cName, preset, label, enabled))
                            }
                        }
                    }
                }
                config = CloudinaryConfig(
                    cloudName = remoteDoc.getString("cloudName")?.trim() ?: "",
                    uploadPreset = remoteDoc.getString("uploadPreset")?.trim() ?: "",
                    apiKey = remoteDoc.getString("apiKey")?.trim() ?: "",
                    apiSecret = remoteDoc.getString("apiSecret")?.trim() ?: "",
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

        var lastError: Exception? = null

        // Try accounts in pool (Auto Failover if quota is exceeded or error occurs)
        for (account in pool) {
            val cloudName = account.cloudName.trim()
            val uploadPreset = account.uploadPreset.trim()
            if (cloudName.isBlank() || uploadPreset.isBlank()) continue

            Log.d(TAG, "Attempting upload to Cloudinary [$cloudName] with preset [$uploadPreset] for $mediaId ($totalLength bytes)")

            // --- Attempt 1: Direct Streaming Upload with folder & public_id ---
            try {
                val directResult = uploadDirect(
                    cloudName = cloudName,
                    uploadPreset = uploadPreset,
                    file = file,
                    mimeType = mimeType,
                    fileName = actualFileName,
                    folder = folder,
                    publicId = mediaId,
                    includeFolderAndId = true
                )
                if (directResult != null) {
                    Log.i(TAG, "Successfully uploaded to Cloudinary [$cloudName] with folder! URL: ${directResult.secureUrl}")
                    return@withContext Result.success(directResult)
                }
            } catch (e: UnsignedPresetRestrictionException) {
                // Preset restricts folder or public_id! Retry with ONLY file and upload_preset:
                Log.w(TAG, "Preset [$uploadPreset] on [$cloudName] restricts custom folder/id. Retrying with pure preset...")
                try {
                    val fallbackResult = uploadDirect(
                        cloudName = cloudName,
                        uploadPreset = uploadPreset,
                        file = file,
                        mimeType = mimeType,
                        fileName = actualFileName,
                        folder = null,
                        publicId = null,
                        includeFolderAndId = false
                    )
                    if (fallbackResult != null) {
                        Log.i(TAG, "Successfully uploaded to Cloudinary [$cloudName] with pure preset! URL: ${fallbackResult.secureUrl}")
                        return@withContext Result.success(fallbackResult)
                    }
                } catch (ex2: Exception) {
                    Log.w(TAG, "Pure preset upload failed on [$cloudName]: ${ex2.message}")
                    lastError = ex2
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct upload failed on [$cloudName]: ${e.message}")
                lastError = e
                // If it's a huge video and direct upload returned 413 or payload too large, try chunked upload:
                if (isVideo && totalLength > 70 * 1024 * 1024L) {
                    try {
                        val chunkedRes = uploadLargeVideoChunked(
                            account = account,
                            file = file,
                            totalLength = totalLength,
                            mediaId = mediaId,
                            fileName = actualFileName
                        )
                        if (chunkedRes != null) {
                            return@withContext Result.success(chunkedRes)
                        }
                    } catch (chunkEx: Exception) {
                        Log.w(TAG, "Chunked upload also failed on [$cloudName]: ${chunkEx.message}")
                        lastError = chunkEx
                    }
                }
            }
        }

        Result.failure(lastError ?: IOException("All Cloudinary upload presets in pool failed."))
    }

    private class UnsignedPresetRestrictionException(message: String) : Exception(message)

    private fun uploadDirect(
        cloudName: String,
        uploadPreset: String,
        file: File,
        mimeType: String,
        fileName: String,
        folder: String?,
        publicId: String?,
        includeFolderAndId: Boolean
    ): CloudinaryUploadResult? {
        val url = "https://api.cloudinary.com/v1_1/$cloudName/auto/upload"
        val requestBodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("upload_preset", uploadPreset)
            .addFormDataPart(
                "file",
                fileName,
                file.asRequestBody(mimeType.toMediaTypeOrNull())
            )

        if (includeFolderAndId) {
            folder?.let { requestBodyBuilder.addFormDataPart("folder", it) }
            publicId?.let { requestBodyBuilder.addFormDataPart("public_id", it) }
        }

        val request = Request.Builder()
            .url(url)
            .post(requestBodyBuilder.build())
            .build()

        val response = okHttpClient.newCall(request).execute()
        val responseBodyString = response.body?.string() ?: ""

        if (response.isSuccessful) {
            val json = JSONObject(responseBodyString)
            return CloudinaryUploadResult(
                publicId = json.optString("public_id", publicId ?: file.nameWithoutExtension),
                secureUrl = json.optString("secure_url", json.optString("url", "")),
                assetType = json.optString("resource_type", "auto"),
                bytes = json.optLong("bytes", file.length())
            )
        } else {
            val errorMsg = try {
                val errObj = JSONObject(responseBodyString)
                errObj.optJSONObject("error")?.optString("message") ?: responseBodyString
            } catch (_: Exception) {
                responseBodyString
            }

            if (includeFolderAndId && (
                errorMsg.contains("folder", ignoreCase = true) ||
                errorMsg.contains("public_id", ignoreCase = true) ||
                errorMsg.contains("not allowed", ignoreCase = true) ||
                errorMsg.contains("parameter", ignoreCase = true) ||
                response.code == 400
            )) {
                throw UnsignedPresetRestrictionException("Preset error ($errorMsg)")
            }

            throw IOException("HTTP ${response.code}: $errorMsg")
        }
    }

    private fun uploadLargeVideoChunked(
        account: CloudinaryAccount,
        file: File,
        totalLength: Long,
        mediaId: String,
        fileName: String
    ): CloudinaryUploadResult? {
        val chunkSize = 10 * 1024 * 1024L // 10MB chunks
        val uniqueUploadId = "vlt_${mediaId.replace("-", "").take(20)}"
        var currentOffset = 0L
        val raf = RandomAccessFile(file, "r")
        try {
            while (currentOffset < totalLength) {
                val endOffset = minOf(currentOffset + chunkSize - 1, totalLength - 1)
                val chunkLength = (endOffset - currentOffset + 1).toInt()
                val chunkBytes = ByteArray(chunkLength)
                raf.seek(currentOffset)
                raf.readFully(chunkBytes)

                val isFinalChunk = (endOffset == totalLength - 1)
                val url = "https://api.cloudinary.com/v1_1/${account.cloudName}/video/upload"

                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("upload_preset", account.uploadPreset)
                    .addFormDataPart(
                        "file",
                        fileName,
                        chunkBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                    )
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .addHeader("X-Unique-Upload-Id", uniqueUploadId)
                    .addHeader("Content-Range", "bytes $currentOffset-$endOffset/$totalLength")
                    .post(body)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    throw IOException("Chunk failed ($currentOffset-$endOffset): HTTP ${response.code} $responseBody")
                }

                currentOffset = endOffset + 1
                if (isFinalChunk) {
                    val json = JSONObject(responseBody)
                    return CloudinaryUploadResult(
                        publicId = json.optString("public_id", mediaId),
                        secureUrl = json.optString("secure_url", json.optString("url", "")),
                        assetType = "video",
                        bytes = json.optLong("bytes", totalLength)
                    )
                }
            }
        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
        return null
    }
}
