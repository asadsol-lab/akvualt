package com.example.feature.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Modes supported by the Floating Camouflage Button:
 * 1. LIGHT_AND_RECORD: 1-Click toggles both Torch Light AND Background Video+Mic Recording.
 * 2. RECORD_ONLY: 1-Click starts/stops Stealth Video+Mic Recording.
 * 3. TORCH_ONLY: 1-Click toggles hardware Flashlight Torch ON / OFF.
 */
enum class FloatingButtonMode(
    val key: String,
    val title: String,
    val shortName: String,
    val description: String
) {
    LIGHT_AND_RECORD(
        "light_and_record",
        "Torch + Record (Dual)",
        "Light & Record",
        "1-Click activates Flashlight Torch AND stealth Video + Mic recording together"
    ),
    RECORD_ONLY(
        "record_only",
        "Secret Record Only",
        "Record Only",
        "1-Click stealthily starts or stops background video with microphone"
    ),
    TORCH_ONLY(
        "torch_only",
        "Flashlight Torch Only",
        "Torch Only",
        "1-Click turns hardware flashlight On / Off (Classic camouflage disguise)"
    );

    companion object {
        fun fromKey(key: String?): FloatingButtonMode {
            return values().firstOrNull { it.key == key } ?: TORCH_ONLY
        }
    }
}

/**
 * Manages the Floating Camouflage Button overlay on the device screen.
 *
 * Features:
 * 1. Multi-Action Modes (Torch only, Record only, Torch + Record dual).
 * 2. Distinct, crisp icon appearance for each mode in both Idle and Active states.
 * 3. Drop-to-Remove Zone at bottom of screen when dragging.
 * 4. Long-press to remove/hide floating icon with haptic feedback.
 * 5. Snap to left/right edge on release.
 * 6. Hide & Lock via Volume DOWN long-press; Restore on Vault Master PIN entry.
 */
object FlashlightFloatingOverlayManager {
    private const val TAG = "FlashlightOverlay"
    private const val PREFS_NAME = "flashlight_floating_overlay_prefs"
    private const val KEY_FLOATING_ENABLED = "floating_flashlight_button_enabled"
    private const val KEY_CONTROLS_LOCKED = "floating_controls_locked_until_vault_opened"
    private const val KEY_BUTTON_MODE = "floating_button_action_mode"
    private const val KEY_POS_X = "floating_pos_x"
    private const val KEY_POS_Y = "floating_pos_y"

    @SuppressLint("StaticFieldLeak")
    private var floatingView: FrameLayout? = null
    @SuppressLint("StaticFieldLeak")
    private var removeZoneView: FrameLayout? = null

    private var windowManager: WindowManager? = null
    private var activeContext: Context? = null
    private var iconImageView: ImageView? = null
    private var backgroundDrawable: GradientDrawable? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var stateObserverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isFloatingButtonEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_FLOATING_ENABLED, true)
    }

    fun setFloatingButtonEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FLOATING_ENABLED, enabled).apply()
        if (enabled) {
            show(context)
        } else {
            hide()
        }
    }

    fun getButtonMode(context: Context): FloatingButtonMode {
        val key = getPrefs(context).getString(KEY_BUTTON_MODE, FloatingButtonMode.TORCH_ONLY.key)
        return FloatingButtonMode.fromKey(key)
    }

    fun setButtonMode(context: Context, mode: FloatingButtonMode) {
        getPrefs(context).edit().putString(KEY_BUTTON_MODE, mode.key).apply()
        updateVisual()
    }

    fun isControlsLocked(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CONTROLS_LOCKED, false)
    }

    private fun setControlsLocked(context: Context, locked: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CONTROLS_LOCKED, locked).apply()
    }

    /**
     * Called when the user long-presses Volume DOWN.
     * Removes the floating camouflage icon and locks button controls until vault is opened.
     */
    fun hideAndLock(context: Context) {
        mainHandler.post {
            FlashlightManager.setTorch(context, false)
            stopRecordingIfActive(context)
            hide()
            setControlsLocked(context, true)
            Log.d(TAG, "Floating button hidden and controls locked via Volume DOWN.")
        }
    }

    /**
     * Called when the user unlocks the Vault (enters Master PIN).
     * Clears the lock and restores the floating button if enabled.
     */
    fun unlockAndRestore(context: Context) {
        mainHandler.post {
            setControlsLocked(context, false)
            Log.d(TAG, "Controls unlocked via Vault Master PIN. Restoring floating button.")
            if (isFloatingButtonEnabled(context)) {
                show(context)
            }
        }
    }

    /**
     * Shows the floating button on screen.
     */
    fun show(context: Context) {
        mainHandler.post {
            if (floatingView != null) {
                updateVisual()
                return@post
            }

            if (isControlsLocked(context)) {
                Log.d(TAG, "Controls are locked. Button will not show until Vault is opened.")
                return@post
            }

            if (!isFloatingButtonEnabled(context)) {
                return@post
            }

            val appContext = context.applicationContext
            activeContext = appContext

            val wm = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?: (appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?: return@post
            windowManager = wm

            try {
                createFloatingView(context, wm)
                startObservingState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create floating view: ${e.message}", e)
            }
        }
    }

    /**
     * Hides and removes the floating button and remove zone.
     */
    fun hide() {
        mainHandler.post {
            stopObservingState()
            hideRemoveZone()
            try {
                floatingView?.let { view ->
                    windowManager?.removeView(view)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing floating view: ${e.message}")
            } finally {
                floatingView = null
                iconImageView = null
                backgroundDrawable = null
            }
        }
    }

    private fun startObservingState() {
        stateObserverJob?.cancel()
        stateObserverJob = scope.launch {
            RecordingSessionController.uiState.collectLatest {
                updateVisual()
            }
        }
    }

    private fun stopObservingState() {
        stateObserverJob?.cancel()
        stateObserverJob = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingView(context: Context, wm: WindowManager) {
        val density = context.resources.displayMetrics.density
        val buttonSize = (54 * density).toInt()
        val iconSize = (32 * density).toInt()

        // 1. Root frame layout
        val root = FrameLayout(context).apply {
            clipToOutline = false
            elevation = 16 * density
        }

        // 2. Circular background drawable
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }
        backgroundDrawable = bg
        root.background = bg

        // 3. Icon Image View
        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        iconImageView = iv

        val ivParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
            gravity = Gravity.CENTER
        }
        root.addView(iv, ivParams)

        // 4. Determine Window Type
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (context is android.accessibilityservice.AccessibilityService) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else if (android.provider.Settings.canDrawOverlays(context)) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            }
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        val prefs = getPrefs(context)
        val defaultX = screenWidth - buttonSize - (12 * density).toInt()
        val defaultY = screenHeight / 2 - buttonSize / 2

        val initialX = prefs.getInt(KEY_POS_X, defaultX)
        val initialY = prefs.getInt(KEY_POS_Y, defaultY)

        val params = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        // 5. Drag, Drop-to-Remove, Long-press & Tap handling
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var initialTouchX = 0f
        var initialTouchY = 0f
        var startX = 0
        var startY = 0
        var isDragging = false
        var downTimeMs = 0L
        var isLongPressTriggered = false
        var isCurrentlyOverRemoveZone = false

        // Long press runnable: Removes icon if held for 650ms without movement
        val longPressRunnable = Runnable {
            if (!isDragging && floatingView != null) {
                isLongPressTriggered = true
                vibrate(context, 70L)
                hideRemoveZone()
                setFloatingButtonEnabled(context, false)
                Toast.makeText(context, "Camouflage button removed (Long-press)", Toast.LENGTH_SHORT).show()
            }
        }

        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    startX = params.x
                    startY = params.y
                    isDragging = false
                    isLongPressTriggered = false
                    isCurrentlyOverRemoveZone = false
                    downTimeMs = System.currentTimeMillis()

                    // Schedule long-press detection
                    mainHandler.postDelayed(longPressRunnable, 650L)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isLongPressTriggered) return@setOnTouchListener true

                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()

                    if (!isDragging && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                        isDragging = true
                        mainHandler.removeCallbacks(longPressRunnable) // cancel long press
                        showRemoveZone(context, wm, layoutType)
                    }

                    if (isDragging) {
                        params.x = startX + deltaX
                        params.y = startY + deltaY
                        try {
                            wm.updateViewLayout(root, params)
                        } catch (_: Exception) {}

                        // Check if floating icon is hovering over the Drop to Remove zone
                        val isOver = checkHoverOverRemoveZone(event.rawX, event.rawY, screenWidth, screenHeight, density)
                        if (isOver != isCurrentlyOverRemoveZone) {
                            isCurrentlyOverRemoveZone = isOver
                            highlightRemoveZone(isOver)
                            if (isOver) {
                                vibrate(context, 25L)
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)

                    if (isLongPressTriggered) {
                        hideRemoveZone()
                        return@setOnTouchListener true
                    }

                    val duration = System.currentTimeMillis() - downTimeMs

                    if (isDragging && isCurrentlyOverRemoveZone) {
                        // User dropped into Remove Zone!
                        vibrate(context, 60L)
                        hideRemoveZone()
                        setFloatingButtonEnabled(context, false)
                        Toast.makeText(
                            context,
                            "Camouflage button removed. Re-enable in Vault Settings.",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnTouchListener true
                    }

                    hideRemoveZone()

                    if (!isDragging && duration < 500) {
                        // Single Click -> Trigger configured action mode!
                        root.performClick()
                        handleSingleClickAction(context)
                    } else if (isDragging) {
                        // Snap smoothly to nearest screen edge (Left or Right)
                        val midX = screenWidth / 2
                        val edgeMargin = (10 * density).toInt()
                        params.x = if (params.x + buttonSize / 2 < midX) {
                            edgeMargin
                        } else {
                            screenWidth - buttonSize - edgeMargin
                        }
                        val minY = (50 * density).toInt()
                        val maxY = screenHeight - buttonSize - (50 * density).toInt()
                        params.y = params.y.coerceIn(minY, maxY)

                        try {
                            wm.updateViewLayout(root, params)
                            prefs.edit()
                                .putInt(KEY_POS_X, params.x)
                                .putInt(KEY_POS_Y, params.y)
                                .apply()
                        } catch (_: Exception) {}
                    }
                    true
                }
                else -> false
            }
        }

        floatingView = root
        wm.addView(root, params)

        updateVisual()
    }

    /**
     * Checks if current touch coordinates are inside the bottom Drop-to-Remove target.
     */
    private fun checkHoverOverRemoveZone(
        rawX: Float,
        rawY: Float,
        screenWidth: Int,
        screenHeight: Int,
        density: Float
    ): Boolean {
        val targetZoneHeight = 150 * density
        val targetCenterX = screenWidth / 2f
        val targetToleranceX = 90 * density

        val inBottomZone = rawY >= (screenHeight - targetZoneHeight)
        val inHorizontalCenter = abs(rawX - targetCenterX) <= targetToleranceX
        return inBottomZone && inHorizontalCenter
    }

    /**
     * Shows the "Drop here to remove" target at the bottom center of the screen during drag.
     */
    @SuppressLint("SetTextI18n")
    private fun showRemoveZone(context: Context, wm: WindowManager, layoutType: Int) {
        if (removeZoneView != null) return

        val density = context.resources.displayMetrics.density
        val zoneWidth = (160 * density).toInt()
        val zoneHeight = (64 * density).toInt()

        val root = FrameLayout(context).apply {
            elevation = 20 * density
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            tag = "remove_container"

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 32 * density
                setColor(Color.parseColor("#CC18181B")) // 80% dark obsidian pill
                setStroke((2 * density).toInt(), Color.parseColor("#66EF4444")) // Red translucent border
            }
            background = bg
        }

        val icon = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_remove_zone_trash))
            setColorFilter(Color.parseColor("#FCA5A5"))
            val iconSize = (24 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = (8 * density).toInt()
            }
        }
        container.addView(icon)

        val text = TextView(context).apply {
            this.text = "Drop to Remove"
            setTextColor(Color.parseColor("#FEE2E2"))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            tag = "remove_text"
        }
        container.addView(text)

        val paramsContainer = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        root.addView(container, paramsContainer)

        val params = WindowManager.LayoutParams(
            zoneWidth,
            zoneHeight,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (36 * density).toInt() // Margin from bottom
        }

        try {
            wm.addView(root, params)
            removeZoneView = root
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add remove zone view: ${e.message}")
        }
    }

    /**
     * Highlights the Drop-to-Remove zone when the floating icon is hovering over it.
     */
    private fun highlightRemoveZone(isHovering: Boolean) {
        val root = removeZoneView ?: return
        val container = root.findViewWithTag<LinearLayout>("remove_container") ?: return
        val text = root.findViewWithTag<TextView>("remove_text") ?: return
        val density = root.context.resources.displayMetrics.density

        if (isHovering) {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 32 * density
                setColor(Color.parseColor("#EEEF4444")) // Vivid Solid Crimson Red
                setStroke((3 * density).toInt(), Color.parseColor("#FFFFFF"))
            }
            container.background = bg
            container.scaleX = 1.12f
            container.scaleY = 1.12f
            text.text = "Release to Remove"
        } else {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 32 * density
                setColor(Color.parseColor("#CC18181B"))
                setStroke((2 * density).toInt(), Color.parseColor("#66EF4444"))
            }
            container.background = bg
            container.scaleX = 1.0f
            container.scaleY = 1.0f
            text.text = "Drop to Remove"
        }
    }

    /**
     * Removes and hides the Drop-to-Remove target.
     */
    private fun hideRemoveZone() {
        try {
            removeZoneView?.let { view ->
                windowManager?.removeView(view)
            }
        } catch (_: Exception) {
        } finally {
            removeZoneView = null
        }
    }

    /**
     * Handles single tap on the floating button based on the user's selected mode.
     */
    private fun handleSingleClickAction(context: Context) {
        val mode = getButtonMode(context)
        when (mode) {
            FloatingButtonMode.TORCH_ONLY -> {
                vibrate(context, 40L)
                FlashlightManager.toggleTorch(context)
                updateVisual()
            }

            FloatingButtonMode.RECORD_ONLY -> {
                toggleRecordingOnly(context)
            }

            FloatingButtonMode.LIGHT_AND_RECORD -> {
                toggleLightAndRecord(context)
            }
        }
    }

    private fun isRecordingActive(): Boolean {
        return RecordingSessionController.uiState.value.isRecording || RecordingForegroundService.isServiceRunning
    }

    private fun toggleRecordingOnly(context: Context) {
        val isRecording = isRecordingActive()
        if (!isRecording) {
            // Start Stealth Recording
            if (!hasCameraAndAudioPermission(context)) {
                Toast.makeText(context, "Camera & Mic permissions required for recording", Toast.LENGTH_SHORT).show()
                return
            }
            vibrate(context, 65L)
            startStealthRecording(context)
            Toast.makeText(context, "● Stealth recording started", Toast.LENGTH_SHORT).show()
        } else {
            // Stop Recording
            vibrate(context, 100L)
            stopRecordingIfActive(context)
            Toast.makeText(context, "■ Recording saved to Vault", Toast.LENGTH_SHORT).show()
        }
        updateVisual()
    }

    private fun toggleLightAndRecord(context: Context) {
        val isTorchOn = FlashlightManager.isTorchOn
        val isRecording = isRecordingActive()

        if (isTorchOn || isRecording) {
            // Stop Both
            vibrate(context, 100L)
            if (isTorchOn) {
                FlashlightManager.setTorch(context, false)
            }
            if (isRecording) {
                stopRecordingIfActive(context)
            }
            Toast.makeText(context, "■ Torch OFF & Recording stopped", Toast.LENGTH_SHORT).show()
        } else {
            // Start Both
            if (!hasCameraAndAudioPermission(context)) {
                Toast.makeText(context, "Camera & Mic permissions required for recording", Toast.LENGTH_SHORT).show()
                return
            }
            vibrate(context, 70L)
            FlashlightManager.setTorch(context, true)
            startStealthRecording(context)
            Toast.makeText(context, "● Torch ON & Stealth recording started", Toast.LENGTH_SHORT).show()
        }
        updateVisual()
    }

    private fun hasCameraAndAudioPermission(context: Context): Boolean {
        val camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return camera && audio
    }

    private fun startStealthRecording(context: Context) {
        val onScreen = VolumeButtonRecordingManager.onScreenStartRecording
        if (onScreen != null) {
            onScreen.invoke()
        } else {
            val serviceIntent = Intent(context, RecordingForegroundService::class.java).apply {
                action = RecordingForegroundService.ACTION_START_HEADLESS_RECORDING
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start headless recording service: ${e.message}", e)
            }
        }
    }

    private fun stopRecordingIfActive(context: Context) {
        val onScreen = VolumeButtonRecordingManager.onScreenStopRecording
        if (onScreen != null) {
            onScreen.invoke()
        } else {
            RecordingSessionController.stop()
        }
        val serviceIntent = Intent(context, RecordingForegroundService::class.java).apply {
            action = RecordingForegroundService.ACTION_STOP
            putExtra(RecordingForegroundService.EXTRA_STOPPED_BY_VOLUME, true)
        }
        try {
            context.startService(serviceIntent)
        } catch (_: Exception) {}
    }

    /**
     * Updates the floating button visual appearance:
     * - Distinct icon for each of the 3 modes.
     * - Distinct visual design in both Active and Inactive/Idle states.
     */
    fun updateTorchVisual(isTorchOn: Boolean = false) {
        updateVisual()
    }

    fun updateVisual() {
        mainHandler.post {
            val ctx = activeContext ?: return@post
            val iv = iconImageView ?: return@post
            val bg = backgroundDrawable ?: return@post

            val mode = getButtonMode(ctx)
            val isTorchOn = FlashlightManager.isTorchOn
            val isRecording = isRecordingActive()
            val density = ctx.resources.displayMetrics.density

            when (mode) {
                FloatingButtonMode.TORCH_ONLY -> {
                    if (isTorchOn) {
                        // Torch ON: Amber glowing circle with bright yellow border & active torch icon
                        bg.setColor(Color.parseColor("#FFF59E0B"))
                        bg.setStroke((3 * density).toInt(), Color.parseColor("#FEF08A"))
                        iv.setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_flashlight_on))
                        iv.setColorFilter(Color.parseColor("#1E1E24"))
                    } else {
                        // Torch OFF: Obsidian dark stealth circle with metallic flashlight icon
                        bg.setColor(Color.parseColor("#E61A1D24"))
                        bg.setStroke((2 * density).toInt(), Color.parseColor("#44FFFFFF"))
                        iv.setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_flashlight_off))
                        iv.setColorFilter(Color.parseColor("#E2E8F0"))
                    }
                }

                FloatingButtonMode.RECORD_ONLY -> {
                    if (isRecording) {
                        // Recording Active: Pulsing Crimson Red disc with bright white recording icon
                        bg.setColor(Color.parseColor("#FFDC2626"))
                        bg.setStroke((3 * density).toInt(), Color.parseColor("#FCA5A5"))
                        iv.setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_floating_record_on))
                        iv.clearColorFilter()
                    } else {
                        // Recording Idle: Stealth obsidian disc with clean video camera icon
                        bg.setColor(Color.parseColor("#E618181B"))
                        bg.setStroke((2 * density).toInt(), Color.parseColor("#55EF4444")) // Subtle red tinted rim
                        iv.setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_floating_record_off))
                        iv.setColorFilter(Color.parseColor("#F43F5E"))
                    }
                }

                FloatingButtonMode.LIGHT_AND_RECORD -> {
                    val isActive = isTorchOn || isRecording
                    if (isActive) {
                        // Dual Active: Intense Amber-Red circle with dual active icon
                        bg.setColor(Color.parseColor("#FFD97706")) // Amber-Gold
                        bg.setStroke((3 * density).toInt(), Color.parseColor("#EF4444")) // Red rim
                        iv.setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_floating_dual_on))
                        iv.clearColorFilter()
                    } else {
                        // Dual Idle: Deep obsidian disc with dual badge icon
                        bg.setColor(Color.parseColor("#E61A1D24"))
                        bg.setStroke((2 * density).toInt(), Color.parseColor("#66F59E0B")) // Amber rim
                        iv.setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_floating_dual_off))
                        iv.clearColorFilter()
                    }
                }
            }
        }
    }

    /**
     * Light haptic feedback helper.
     */
    private fun vibrate(context: Context, durationMs: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }
        } catch (_: Exception) {}
    }
}
