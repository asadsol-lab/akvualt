package com.example.core.ads

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.example.core.subscription.LicenseKeyManager
import com.example.feature.recording.RecordingSessionController
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import java.util.Date

/**
 * Enterprise-grade AdMob Manager for App Open Ads.
 * Strictly adheres to Google AdMob Policies:
 * - Free users: Shows ad at most once every 4 to 6 hours (frequency capped).
 * - Premium users: NEVER shows or loads any ads.
 * - Safety: Never interrupts active video recording or sensitive capture flows.
 * - Ad freshness: Ad caches expire after 4 hours per AdMob recommendations.
 */
object AdMobManager {

    private const val TAG = "AdMobManager"

    // User-provided AdMob App Open Ad unit ID
    const val AD_UNIT_ID = "ca-app-pub-6805305075660963/2291973996"

    private const val PREFS_NAME = "admob_vault_prefs"
    private const val KEY_LAST_AD_SHOWN = "last_admob_shown_timestamp"

    // 4 hours minimum interval between ads (4 * 60 * 60 * 1000 ms)
    private const val MIN_AD_INTERVAL_MS = 4L * 60L * 60L * 1000L

    private var appOpenAd: AppOpenAd? = null
    private var isLoadingAd = false
    private var isShowingAd = false
    private var loadTime: Long = 0L

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Initializes Google Mobile Ads SDK on app startup.
     */
    fun init(context: Context) {
        try {
            MobileAds.initialize(context) { status ->
                Log.d(TAG, "AdMob SDK Initialized successfully: $status")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MobileAds: ${e.message}")
        }
    }

    /**
     * Determines whether the ad interval has elapsed (at least 4 hours since last impression).
     */
    fun canShowAdNow(context: Context): Boolean {
        val lastShown = getPrefs(context).getLong(KEY_LAST_AD_SHOWN, 0L)
        val now = System.currentTimeMillis()
        val elapsed = now - lastShown
        val isIntervalPassed = elapsed >= MIN_AD_INTERVAL_MS
        Log.d(TAG, "Ad interval check: elapsed ${elapsed / 1000 / 60} mins, allowed: $isIntervalPassed")
        return isIntervalPassed
    }

    /**
     * Records the current timestamp as the latest ad impression.
     */
    private fun recordAdShown(context: Context) {
        getPrefs(context).edit().putLong(KEY_LAST_AD_SHOWN, System.currentTimeMillis()).apply()
    }

    /**
     * Verifies if an ad is currently loaded and still fresh (under 4 hours old).
     */
    private fun isAdAvailable(): Boolean {
        val isFresh = (System.currentTimeMillis() - loadTime) < (4 * 3600000L)
        return appOpenAd != null && isFresh
    }

    /**
     * Preloads an App Open Ad if the user is Free and eligible.
     */
    fun loadAppOpenAd(context: Context) {
        // Do not load ads for Premium users
        if (LicenseKeyManager.isCachedPremium(context)) {
            Log.d(TAG, "User is Premium, skipping ad preloading.")
            return
        }

        if (isLoadingAd || isAdAvailable()) {
            return
        }

        isLoadingAd = true
        val request = AdRequest.Builder().build()

        AppOpenAd.load(
            context,
            AD_UNIT_ID,
            request,
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    isLoadingAd = false
                    loadTime = System.currentTimeMillis()
                    Log.d(TAG, "App Open Ad loaded successfully.")
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    isLoadingAd = false
                    appOpenAd = null
                    Log.w(TAG, "App Open Ad failed to load: ${loadAdError.message}")
                }
            }
        )
    }

    /**
     * Shows the App Open Ad if available and interval policy allows.
     * Guaranteed safe:
     * - Checks if user is Premium -> if so, NEVER shows.
     * - Checks if recording is active -> if so, NEVER shows.
     * - Checks if minimum 4 hours elapsed -> if not, NEVER shows.
     */
    fun showAppOpenAdIfAvailable(
        activity: Activity,
        isPremiumUser: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        // 1. Check Premium Status (Double-check both parameter and local cached license)
        if (isPremiumUser || LicenseKeyManager.isCachedPremium(activity)) {
            Log.d(TAG, "Skipping ad: User has Premium access.")
            onComplete?.invoke()
            return
        }

        // 2. Policy Safety: Never interrupt active camera recording
        if (RecordingSessionController.uiState.value.isRecording) {
            Log.d(TAG, "Skipping ad: Stealth recording is in progress.")
            onComplete?.invoke()
            return
        }

        // 3. Frequency Capping: Verify 4 to 6 hours have passed since last ad
        if (!canShowAdNow(activity)) {
            Log.d(TAG, "Skipping ad: Minimum 4-hour frequency cap active.")
            onComplete?.invoke()
            return
        }

        // 4. Concurrency Guard
        if (isShowingAd) {
            Log.d(TAG, "Skipping ad: Another ad is already showing.")
            onComplete?.invoke()
            return
        }

        // 5. If ad is not ready, start loading for next time and proceed smoothly
        if (!isAdAvailable()) {
            Log.d(TAG, "Ad not ready yet, loading for future display.")
            loadAppOpenAd(activity)
            onComplete?.invoke()
            return
        }

        val ad = appOpenAd ?: run {
            onComplete?.invoke()
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "App Open Ad dismissed by user.")
                appOpenAd = null
                isShowingAd = false
                recordAdShown(activity)
                onComplete?.invoke()
                // Preload next ad in background
                loadAppOpenAd(activity)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.w(TAG, "App Open Ad failed to show: ${adError.message}")
                appOpenAd = null
                isShowingAd = false
                onComplete?.invoke()
                loadAppOpenAd(activity)
            }

            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "App Open Ad showed fullscreen.")
                isShowingAd = true
            }
        }

        isShowingAd = true
        ad.show(activity)
    }
}
