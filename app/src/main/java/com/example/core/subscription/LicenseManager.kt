package com.example.core.subscription

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.example.core.auth.GuestCloudIdentityManager
import com.example.feature.backup.AnonymousCloudBackupManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

enum class LicenseTier(val displayName: String, val durationDays: Int?) {
    MONTHLY("Monthly Premium (30 Days)", 30),
    YEARLY("Yearly Premium (365 Days)", 365),
    LIFETIME("Lifetime VIP (Permanent)", null)
}

data class LicenseInfo(
    val key: String = "",
    val tier: LicenseTier = LicenseTier.LIFETIME,
    val isActive: Boolean = false,
    val expiresAt: Long? = null,
    val customerNote: String = "",
    val redeemedAt: Long = 0L,
    val message: String = ""
)

/**
 * License & Voucher Management System.
 * Enables users to activate and restore Premium WITHOUT requiring a Google / Gmail account.
 * Handles:
 * - Direct redemption of Admin-issued activation keys (Monthly, Yearly, Lifetime).
 * - Hardware Device ID binding to protect against piracy.
 * - Automatic subscription restoration after app uninstallation.
 */
object LicenseManager {

    private const val TAG = "LicenseManager"
    private const val PREFS_NAME = "vault_license_prefs"
    private const val KEY_SAVED_LICENSE = "saved_active_license_key"
    private const val KEY_SAVED_TIER = "saved_license_tier"
    private const val KEY_SAVED_EXPIRY = "saved_license_expiry"

    private val firestore by lazy { FirebaseFirestore.getInstance() }

    private val _activeLicense = MutableStateFlow<LicenseInfo?>(null)
    val activeLicense: StateFlow<LicenseInfo?> = _activeLicense.asStateFlow()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Initializes license status from local cache and checks Firestore.
     */
    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        val prefs = getPrefs(context)
        val savedKey = prefs.getString(KEY_SAVED_LICENSE, null)
        val savedTierStr = prefs.getString(KEY_SAVED_TIER, null)
        val savedExpiry = prefs.getLong(KEY_SAVED_EXPIRY, -1L).takeIf { it > 0 }

        if (!savedKey.isNullOrBlank()) {
            val tier = try {
                savedTierStr?.let { LicenseTier.valueOf(it) } ?: LicenseTier.LIFETIME
            } catch (_: Exception) {
                LicenseTier.LIFETIME
            }

            // Check if expired
            val isExpired = savedExpiry != null && System.currentTimeMillis() > savedExpiry
            if (!isExpired) {
                _activeLicense.value = LicenseInfo(
                    key = savedKey,
                    tier = tier,
                    isActive = true,
                    expiresAt = savedExpiry,
                    message = "Active License: ${tier.displayName}"
                )
            }
        }

        // Try restoring license from Firestore via Device ID
        restoreLicenseByDeviceId(context)
    }

    /**
     * Redeems an Admin-generated License Key without requiring Gmail.
     */
    suspend fun redeemLicenseKey(context: Context, keyInput: String): Result<LicenseInfo> = withContext(Dispatchers.IO) {
        val cleanKey = keyInput.trim().uppercase()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid license activation key."))
        }

        try {
            val uid = GuestCloudIdentityManager.getOrCreateGuestIdentity(context)
            val deviceId = AnonymousCloudBackupManager.getDeviceId(context)
            val docRef = firestore.collection("license_keys").document(cleanKey)
            val snap = docRef.get().await()

            if (!snap.exists()) {
                return@withContext Result.failure(Exception("Invalid License Key. Please check the code and try again."))
            }

            val status = snap.getString("status") ?: "UNUSED"
            if (status.equals("REVOKED", ignoreCase = true)) {
                return@withContext Result.failure(Exception("This license key has been revoked by administration."))
            }

            val tierStr = snap.getString("tier") ?: "LIFETIME"
            val tier = try {
                LicenseTier.valueOf(tierStr.uppercase())
            } catch (_: Exception) {
                LicenseTier.LIFETIME
            }

            val redeemedByDeviceId = snap.getString("redeemedByDeviceId")
            val redeemedByUid = snap.getString("redeemedByUid")
            val now = System.currentTimeMillis()

            var expiresAt = snap.getLong("expiresAt")
            if (expiresAt == null || expiresAt <= 0) {
                expiresAt = tier.durationDays?.let { days ->
                    now + (days.toLong() * 24L * 3600L * 1000L)
                }
            }

            // If already redeemed, verify it belongs to this device or UID
            if (status.equals("REDEEMED", ignoreCase = true)) {
                val isOwnDevice = redeemedByDeviceId == deviceId || redeemedByUid == uid
                if (!isOwnDevice) {
                    return@withContext Result.failure(Exception("This license key has already been activated on another device."))
                }

                // Check expiry
                if (expiresAt != null && now > expiresAt) {
                    return@withContext Result.failure(Exception("This license key has expired."))
                }
            } else {
                // First-time redemption: bind to this device & UID
                val updates = mapOf(
                    "status" to "REDEEMED",
                    "redeemedByDeviceId" to deviceId,
                    "redeemedByUid" to uid,
                    "redeemedAt" to now,
                    "expiresAt" to expiresAt,
                    "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "updatedAt" to now
                )
                docRef.set(updates, SetOptions.merge()).await()
            }

            // Sync user profile in Firestore
            try {
                firestore.collection("users").document(uid).set(
                    mapOf(
                        "isPremium" to true,
                        "premiumTier" to tier.name,
                        "activeLicenseKey" to cleanKey,
                        "expiresAt" to expiresAt,
                        "updatedAt" to now
                    ),
                    SetOptions.merge()
                ).await()
            } catch (_: Exception) {}

            // Save to local device preferences
            getPrefs(context).edit()
                .putString(KEY_SAVED_LICENSE, cleanKey)
                .putString(KEY_SAVED_TIER, tier.name)
                .putLong(KEY_SAVED_EXPIRY, expiresAt ?: -1L)
                .apply()

            val info = LicenseInfo(
                key = cleanKey,
                tier = tier,
                isActive = true,
                expiresAt = expiresAt,
                redeemedAt = now,
                message = "Premium activated successfully! (${tier.displayName})"
            )
            _activeLicense.value = info
            Log.d(TAG, "Successfully activated license $cleanKey for device $deviceId")
            return@withContext Result.success(info)
        } catch (e: Exception) {
            Log.e(TAG, "Redeem failed: ${e.message}", e)
            return@withContext Result.failure(e)
        }
    }

    /**
     * Automatically restores a subscription by searching for this device's Hardware ID in Firestore.
     * Works even if the app was uninstalled!
     */
    suspend fun restoreLicenseByDeviceId(context: Context): Result<LicenseInfo?> = withContext(Dispatchers.IO) {
        try {
            val deviceId = AnonymousCloudBackupManager.getDeviceId(context)
            val uid = GuestCloudIdentityManager.getOrCreateGuestIdentity(context)

            // Query license_keys where redeemedByDeviceId == deviceId
            val querySnap = try {
                firestore.collection("license_keys")
                    .whereEqualTo("redeemedByDeviceId", deviceId)
                    .whereEqualTo("status", "REDEEMED")
                    .get()
                    .await()
            } catch (_: Exception) {
                null
            }

            val matchingDoc = querySnap?.documents?.firstOrNull() ?: run {
                // Fallback query by UID
                try {
                    firestore.collection("license_keys")
                        .whereEqualTo("redeemedByUid", uid)
                        .whereEqualTo("status", "REDEEMED")
                        .get()
                        .await()
                        .documents
                        .firstOrNull()
                } catch (_: Exception) {
                    null
                }
            }

            if (matchingDoc != null) {
                val key = matchingDoc.id
                val tierStr = matchingDoc.getString("tier") ?: "LIFETIME"
                val tier = try {
                    LicenseTier.valueOf(tierStr.uppercase())
                } catch (_: Exception) {
                    LicenseTier.LIFETIME
                }
                val expiresAt = matchingDoc.getLong("expiresAt")
                val now = System.currentTimeMillis()

                if (expiresAt != null && now > expiresAt) {
                    return@withContext Result.failure(Exception("Your previous license subscription has expired."))
                }

                // Restore locally
                getPrefs(context).edit()
                    .putString(KEY_SAVED_LICENSE, key)
                    .putString(KEY_SAVED_TIER, tier.name)
                    .putLong(KEY_SAVED_EXPIRY, expiresAt ?: -1L)
                    .apply()

                val info = LicenseInfo(
                    key = key,
                    tier = tier,
                    isActive = true,
                    expiresAt = expiresAt,
                    message = "Restored active subscription: ${tier.displayName}"
                )
                _activeLicense.value = info
                return@withContext Result.success(info)
            }

            return@withContext Result.success(null)
        } catch (e: Exception) {
            Log.w(TAG, "Restore check: ${e.message}")
            return@withContext Result.failure(e)
        }
    }

    /**
     * Checks if local license state is currently valid & active.
     */
    fun isLicenseActive(): Boolean {
        val current = _activeLicense.value ?: return false
        if (!current.isActive) return false
        val exp = current.expiresAt ?: return true
        return System.currentTimeMillis() < exp
    }
}
