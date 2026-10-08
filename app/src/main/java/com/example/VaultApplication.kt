package com.example

import android.app.Application
import android.util.Log
import com.example.feature.backup.VaultChunkedUploadScheduler
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VaultApplication : Application() {

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        initFirebase()
        initBackgroundSync()
    }

    private fun initBackgroundSync() {
        try {
            // Register periodic background sync in WorkManager (survives reboots & long offline periods)
            VaultChunkedUploadScheduler.schedulePeriodicSync(this)
            // Schedule any pending un-synced media
            appScope.launch {
                VaultChunkedUploadScheduler.scheduleAllPending(this@VaultApplication)
            }
        } catch (e: Exception) {
            Log.e("VaultApplication", "Failed to schedule background upload sync: ${e.message}")
        }
    }

    private fun initFirebase() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApiKey("AIzaSyCbLH3RoebFWxQRccwo7e3Z0jDE712SMdA")
                    .setApplicationId("1:50868501006:android:671f5d82b38fcb99831a5f")
                    .setProjectId("sleathcam1")
                    .setStorageBucket("sleathcam1.firebasestorage.app")
                    .setGcmSenderId("50868501006")
                    .build()
                FirebaseApp.initializeApp(this, options)
                Log.d("VaultApplication", "Firebase initialized programmatically with fallback options.")
            } else {
                Log.d("VaultApplication", "Firebase already initialized by provider.")
            }
        } catch (e: Exception) {
            Log.e("VaultApplication", "Failed to initialize Firebase: ${e.message}", e)
        }
    }
}
