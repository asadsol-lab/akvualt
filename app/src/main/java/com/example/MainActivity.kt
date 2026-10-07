package com.example

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import coil.Coil
import coil.ImageLoader
import com.example.core.coil.EncryptedVaultFileFetcher
import com.example.feature.recording.VolumeButtonRecordingManager
import com.example.navigation.AppDestinations
import com.example.navigation.AppNavigation
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.setValue
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private lateinit var appContainer: VaultAppContainer
    private val openRecorderTrigger = MutableStateFlow(false)
    private val shouldLockVaultTrigger = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Privacy protection: Prevent task previews in Recent Apps & screenshots
        val sharedPrefs = getSharedPreferences("vault_security_storage", android.content.Context.MODE_PRIVATE)
        val isScreenshotProtection = sharedPrefs.getBoolean("screenshot_protection", true)
        try {
            if (isScreenshotProtection) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        } catch (_: Exception) {}

        enableEdgeToEdge()

        if (intent?.action == "ACTION_OPEN_RECORDER") {
            openRecorderTrigger.value = true
        }

        appContainer = VaultAppContainer(applicationContext)

        val imageLoader = ImageLoader.Builder(applicationContext)
            .components {
                add(EncryptedVaultFileFetcher.Factory(appContainer.fileEncryptor))
            }
            .build()
        Coil.setImageLoader(imageLoader)

        setContent {
            MyApplicationTheme {
                val navController = rememberNavController()
                val triggerOpenRecorder by openRecorderTrigger.collectAsStateWithLifecycle()
                val triggerAutoLock by shouldLockVaultTrigger.collectAsStateWithLifecycle()

                // Auto-lock vault on app exit / Home button press
                LaunchedEffect(triggerAutoLock) {
                    if (triggerAutoLock) {
                        navController.navigate(AppDestinations.CALCULATOR) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                        shouldLockVaultTrigger.value = false
                    }
                }

                LaunchedEffect(triggerOpenRecorder) {
                    if (triggerOpenRecorder) {
                        navController.navigate(AppDestinations.RECORDING) {
                            launchSingleTop = true
                        }
                        openRecorderTrigger.value = false
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(
                        appContainer = appContainer,
                        navController = navController
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Immediate Auto-Lock: When user presses Home, locks phone, or switches apps,
        // lock the vault so on next open it ALWAYS presents the Calculator PIN screen.
        shouldLockVaultTrigger.value = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == "ACTION_OPEN_RECORDER") {
            openRecorderTrigger.value = true
        }
    }

    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (VolumeButtonRecordingManager.handleKeyEvent(this, event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}


