package com.example.core.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.core.admin.AdminRepository
import com.example.core.admin.AppUpdateConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed class AppUpdateState {
    data object Checking : AppUpdateState()
    data class UpdateAvailable(
        val config: AppUpdateConfig,
        val isMandatory: Boolean,
        val currentVersionCode: Int = BuildConfig.VERSION_CODE,
        val currentVersionName: String = BuildConfig.VERSION_NAME
    ) : AppUpdateState()
    data class UpToDate(
        val currentVersionCode: Int = BuildConfig.VERSION_CODE,
        val currentVersionName: String = BuildConfig.VERSION_NAME
    ) : AppUpdateState()
    data class Error(val message: String) : AppUpdateState()
}

sealed class InAppDownloadState {
    data object Idle : InAppDownloadState()
    data class Downloading(
        val progress: Float, // 0.0f to 1.0f
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : InAppDownloadState()
    data class ReadyToInstall(val apkFile: File) : InAppDownloadState()
    data class Error(val message: String) : InAppDownloadState()
}

class AppUpdateManager(
    private val adminRepository: AdminRepository,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val _downloadState = MutableStateFlow<InAppDownloadState>(InAppDownloadState.Idle)
    val downloadState: StateFlow<InAppDownloadState> = _downloadState.asStateFlow()

    private fun isNewerVersionName(remoteVersion: String, currentVersion: String): Boolean {
        if (remoteVersion.isBlank() || currentVersion.isBlank()) return false
        val rParts = remoteVersion.trim().removePrefix("v").removePrefix("V").split(".").mapNotNull { it.toIntOrNull() }
        val cParts = currentVersion.trim().removePrefix("v").removePrefix("V").split(".").mapNotNull { it.toIntOrNull() }
        if (rParts.isNotEmpty() && cParts.isNotEmpty()) {
            val maxLen = maxOf(rParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
        }
        return remoteVersion.trim() != currentVersion.trim()
    }

    suspend fun checkForUpdates(): AppUpdateState = withContext(Dispatchers.IO) {
        val currentVersionCode = BuildConfig.VERSION_CODE
        val currentVersionName = BuildConfig.VERSION_NAME

        val result = adminRepository.getAppUpdateConfig()
        if (result.isSuccess) {
            val config = result.getOrNull()
            if (config != null && config.apkUrl.isNotBlank()) {
                val isCodeNewer = config.versionCode > currentVersionCode
                val isNameNewer = isNewerVersionName(config.versionName, currentVersionName)
                if (isNameNewer || isCodeNewer) {
                    val isMandatory = config.isMandatory || (config.minSupportedVersionCode > 0 && currentVersionCode < config.minSupportedVersionCode)
                    return@withContext AppUpdateState.UpdateAvailable(
                        config = config,
                        isMandatory = isMandatory,
                        currentVersionCode = currentVersionCode,
                        currentVersionName = currentVersionName
                    )
                } else {
                    return@withContext AppUpdateState.UpToDate(
                        currentVersionCode = currentVersionCode,
                        currentVersionName = currentVersionName
                    )
                }
            } else {
                return@withContext AppUpdateState.UpToDate(
                    currentVersionCode = currentVersionCode,
                    currentVersionName = currentVersionName
                )
            }
        } else {
            return@withContext AppUpdateState.Error(
                result.exceptionOrNull()?.localizedMessage ?: "Failed to verify update status."
            )
        }
    }

    fun resetDownloadState() {
        _downloadState.value = InAppDownloadState.Idle
    }

    suspend fun downloadAndInstallApk(context: Context, apkUrl: String, versionName: String) = withContext(Dispatchers.IO) {
        if (apkUrl.isBlank()) {
            _downloadState.value = InAppDownloadState.Error("Update URL is empty.")
            return@withContext
        }

        try {
            _downloadState.value = InAppDownloadState.Downloading(
                progress = 0f,
                downloadedBytes = 0,
                totalBytes = -1
            )

            val apkDir = File(context.cacheDir, "apks").apply {
                if (!exists()) mkdirs()
            }
            // Clean up older downloaded apks
            apkDir.listFiles()?.forEach { file ->
                try { file.delete() } catch (_: Exception) {}
            }

            val targetFile = File(apkDir, "update_v${versionName.replace(" ", "_")}.apk")

            val request = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", "VaultInAppUpdater/3.0 (Android)")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                _downloadState.value = InAppDownloadState.Error("Server returned code ${response.code}: ${response.message}")
                return@withContext
            }

            val body = response.body
            if (body == null) {
                _downloadState.value = InAppDownloadState.Error("Empty download payload from server.")
                return@withContext
            }

            val contentLength = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    var lastReportedTime = System.currentTimeMillis()

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        val now = System.currentTimeMillis()
                        if (now - lastReportedTime > 100 || downloadedBytes == contentLength) {
                            val progress = if (contentLength > 0) {
                                (downloadedBytes.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                            } else {
                                -1f
                            }
                            _downloadState.value = InAppDownloadState.Downloading(
                                progress = progress,
                                downloadedBytes = downloadedBytes,
                                totalBytes = contentLength
                            )
                            lastReportedTime = now
                        }
                    }
                    output.flush()
                }
            }

            _downloadState.value = InAppDownloadState.ReadyToInstall(targetFile)

            // Trigger system installer on main thread
            withContext(Dispatchers.Main) {
                triggerApkInstallation(context, targetFile)
            }
        } catch (e: CancellationException) {
            _downloadState.value = InAppDownloadState.Idle
            throw e
        } catch (e: Exception) {
            _downloadState.value = InAppDownloadState.Error(
                e.localizedMessage ?: "Failed to download update."
            )
        }
    }

    fun triggerApkInstallation(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                _downloadState.value = InAppDownloadState.Error("Downloaded APK file not found or corrupted.")
                return
            }

            // On Android 8.0+ (API 26+), check if unknown apps permission is granted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    return
                }
            }

            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            _downloadState.value = InAppDownloadState.Error("Failed to launch package installer: ${e.localizedMessage}")
        }
    }
}
