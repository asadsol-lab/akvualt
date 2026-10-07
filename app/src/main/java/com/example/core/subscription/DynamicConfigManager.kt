package com.example.core.subscription

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Dynamic Quota Rules configured by Super Admin via Admin Panel.
 * Default Free limits: 5 recordings and 500 MB storage.
 */
data class DynamicQuotaRules(
    val freeMaxRecordings: Int = 5,
    val freeMaxStorageMb: Long = 500L,
    val premiumMaxRecordings: Int = 500,
    val premiumMaxStorageMb: Long = 10240L, // 10 GB
    val lastUpdated: Long = 0L
) {
    val freeMaxStorageBytes: Long
        get() = freeMaxStorageMb * 1024L * 1024L

    val premiumMaxStorageBytes: Long
        get() = premiumMaxStorageMb * 1024L * 1024L
}

/**
 * Dynamic Cloudinary Configuration loaded from Firestore.
 */
data class DynamicCloudinaryConfig(
    val cloudName: String = "sleathcam1",
    val apiKey: String = "",
    val apiSecret: String = "",
    val uploadPreset: String = "stealth_vault_preset",
    val targetResolution: String = "720p", // 720p or 480p to conserve free bandwidth
    val videoBitrateKbps: Int = 1500,     // 1.5 Mbps for optimal small file size
    val isCloudinaryEnabled: Boolean = false
)

object DynamicConfigManager {

    private const val TAG = "DynamicConfigManager"
    private const val COLLECTION_CONFIG = "system_config"
    private const val DOC_QUOTA = "quota_rules"
    private const val DOC_CLOUDINARY = "cloudinary_config"

    private val firestore by lazy { FirebaseFirestore.getInstance() }

    private val _quotaRules = MutableStateFlow(DynamicQuotaRules())
    val quotaRules: StateFlow<DynamicQuotaRules> = _quotaRules.asStateFlow()

    private val _cloudinaryConfig = MutableStateFlow(DynamicCloudinaryConfig())
    val cloudinaryConfig: StateFlow<DynamicCloudinaryConfig> = _cloudinaryConfig.asStateFlow()

    /**
     * Initializes realtime listeners to sync Admin quota and Cloudinary changes instantly.
     */
    fun startListening() {
        try {
            firestore.collection(COLLECTION_CONFIG).document(DOC_QUOTA)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Quota rules listen failed: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        val freeRecs = snapshot.getLong("freeMaxRecordings")?.toInt() ?: 5
                        val freeMb = snapshot.getLong("freeMaxStorageMb") ?: 500L
                        val premRecs = snapshot.getLong("premiumMaxRecordings")?.toInt() ?: 500
                        val premMb = snapshot.getLong("premiumMaxStorageMb") ?: 10240L
                        val updated = snapshot.getLong("lastUpdated") ?: System.currentTimeMillis()

                        _quotaRules.value = DynamicQuotaRules(
                            freeMaxRecordings = freeRecs,
                            freeMaxStorageMb = freeMb,
                            premiumMaxRecordings = premRecs,
                            premiumMaxStorageMb = premMb,
                            lastUpdated = updated
                        )
                        Log.d(TAG, "Synced dynamic quota: Free=${freeRecs} recs / ${freeMb}MB")
                    }
                }

            firestore.collection(COLLECTION_CONFIG).document(DOC_CLOUDINARY)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Cloudinary config listen failed: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        _cloudinaryConfig.value = DynamicCloudinaryConfig(
                            cloudName = snapshot.getString("cloudName") ?: "sleathcam1",
                            apiKey = snapshot.getString("apiKey") ?: "",
                            apiSecret = snapshot.getString("apiSecret") ?: "",
                            uploadPreset = snapshot.getString("uploadPreset") ?: "stealth_vault_preset",
                            targetResolution = snapshot.getString("targetResolution") ?: "720p",
                            videoBitrateKbps = snapshot.getLong("videoBitrateKbps")?.toInt() ?: 1500,
                            isCloudinaryEnabled = snapshot.getBoolean("isCloudinaryEnabled") ?: false
                        )
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Error starting config listeners: ${e.message}")
        }
    }

    /**
     * Updates quota rules in Firestore directly from Admin mode.
     */
    suspend fun saveQuotaRules(rules: DynamicQuotaRules): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val data = mapOf(
                "freeMaxRecordings" to rules.freeMaxRecordings,
                "freeMaxStorageMb" to rules.freeMaxStorageMb,
                "premiumMaxRecordings" to rules.premiumMaxRecordings,
                "premiumMaxStorageMb" to rules.premiumMaxStorageMb,
                "lastUpdated" to System.currentTimeMillis()
            )
            firestore.collection(COLLECTION_CONFIG).document(DOC_QUOTA)
                .set(data, SetOptions.merge())
                .await()
            _quotaRules.value = rules
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
