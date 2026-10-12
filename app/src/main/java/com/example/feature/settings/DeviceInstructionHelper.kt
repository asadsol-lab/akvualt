package com.example.feature.settings

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

enum class DeviceBrand(val displayName: String, val osSkinName: String) {
    XIAOMI("Xiaomi / Redmi / POCO", "MIUI / HyperOS"),
    SAMSUNG("Samsung Galaxy", "One UI"),
    VIVO("Vivo / iQOO", "Funtouch OS / OriginOS"),
    OPPO("Oppo / Realme / OnePlus", "ColorOS / OxygenOS / Realme UI"),
    HUAWEI("Huawei / Honor", "EMUI / MagicOS"),
    PIXEL("Google Pixel / Pure Android", "Stock Android"),
    OTHER("Android Device", "Standard Android")
}

data class BrandGuide(
    val brand: DeviceBrand,
    val deviceModelString: String,
    val androidVersionString: String,
    val highlightColorHex: String,
    val autostartHeader: String,
    val autostartDetail: String,
    val batteryHeader: String,
    val batteryDetail: String,
    val overlayHeader: String,
    val overlayDetail: String,
    val recentAppsLockTip: String,
    val extraNote: String?
)

object DeviceInstructionHelper {

    fun detectBrand(): DeviceBrand {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") || b.contains("xiaomi") || b.contains("redmi") || b.contains("poco") -> DeviceBrand.XIAOMI
            m.contains("samsung") || b.contains("samsung") -> DeviceBrand.SAMSUNG
            m.contains("vivo") || m.contains("iqoo") || b.contains("vivo") || b.contains("iqoo") -> DeviceBrand.VIVO
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") || b.contains("oppo") || b.contains("realme") || b.contains("oneplus") -> DeviceBrand.OPPO
            m.contains("huawei") || m.contains("honor") || b.contains("huawei") || b.contains("honor") -> DeviceBrand.HUAWEI
            m.contains("google") || b.contains("google") -> DeviceBrand.PIXEL
            else -> DeviceBrand.OTHER
        }
    }

    fun getDeviceModelString(): String {
        val brand = Build.BRAND.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val model = Build.MODEL
        return if (model.startsWith(brand, ignoreCase = true)) model else "$brand $model"
    }

    fun getAndroidVersionString(): String {
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    fun getBrandGuide(context: Context): BrandGuide {
        val detected = detectBrand()
        val modelStr = getDeviceModelString()
        val versionStr = getAndroidVersionString()

        return when (detected) {
            DeviceBrand.XIAOMI -> BrandGuide(
                brand = detected,
                deviceModelString = modelStr,
                androidVersionString = versionStr,
                highlightColorHex = "#FF6900", // Xiaomi Orange
                autostartHeader = "1. Enable Autostart (Crucial for Xiaomi)",
                autostartDetail = "• Open Security App → Permissions → Autostart.\n• Turn toggle ON for 'Calculator Vault'.\n• This allows volume buttons and camouflage overlay to remain responsive when screen is off.",
                batteryHeader = "2. Battery Saver → No Restrictions",
                batteryDetail = "• Open Security App → Battery → Battery saver.\n• Find Calculator Vault and choose 'No restrictions' (or Don't optimize).\n• This prevents MIUI / HyperOS from killing background recording.",
                overlayHeader = "3. Other Permissions (Display Pop-up Windows)",
                overlayDetail = "• Settings → Apps → Manage apps → Calculator Vault → Other permissions.\n• Allow 'Display pop-up windows while running in the background'.\n• Allow 'Show on Lock screen'.",
                recentAppsLockTip = "Open Recent Apps (Recents screen), long-press Calculator Vault card, and tap the Padlock icon so Xiaomi never clears it from RAM.",
                extraNote = "Xiaomi / POCO devices strictly throttle background services unless 'Autostart' and 'No restrictions' are allowed."
            )

            DeviceBrand.SAMSUNG -> BrandGuide(
                brand = detected,
                deviceModelString = modelStr,
                androidVersionString = versionStr,
                highlightColorHex = "#0D6EFD", // Samsung Blue
                autostartHeader = "1. Background Usage Limits (Never Sleeping)",
                autostartDetail = "• Settings → Battery & device care → Battery → Background usage limits.\n• Tap 'Never sleeping apps' → '+' → Add 'Calculator Vault'.",
                batteryHeader = "2. Set App Battery to 'Unrestricted'",
                batteryDetail = "• Settings → Apps → Calculator Vault → Battery.\n• Choose 'Unrestricted' instead of 'Optimized'.",
                overlayHeader = "3. Appear on Top (Floating Camouflage)",
                overlayDetail = "• Settings → Apps → Special app access (top right 3 dots) → 'Appear on top'.\n• Turn ON for Calculator Vault.",
                recentAppsLockTip = "In Recent Apps view, tap the Calculator icon at top of the app preview card and select 'Keep open' / 'Lock this app'.",
                extraNote = "Samsung One UI will pause volume button listeners if the app is placed into 'Sleeping apps'."
            )

            DeviceBrand.VIVO -> BrandGuide(
                brand = detected,
                deviceModelString = modelStr,
                androidVersionString = versionStr,
                highlightColorHex = "#0055FE", // Vivo Blue
                autostartHeader = "1. Enable Autostart in iManager",
                autostartDetail = "• Open iManager → App manager → Autostart management.\n• Enable 'Calculator Vault'.",
                batteryHeader = "2. High Background Power Consumption",
                batteryDetail = "• Settings → Battery → Background power consumption management.\n• Set Calculator Vault to 'Allow high background power consumption'.",
                overlayHeader = "3. Floating Window Permission",
                overlayDetail = "• Settings → Applications and Permissions → Permission management → Floating window.\n• Turn ON for Calculator Vault.",
                recentAppsLockTip = "In Recent Apps screen, pull down on Calculator Vault card and tap the Lock icon.",
                extraNote = "Vivo Funtouch / OriginOS requires 'High Background Power' enabled to keep services alive."
            )

            DeviceBrand.OPPO -> BrandGuide(
                brand = detected,
                deviceModelString = modelStr,
                androidVersionString = versionStr,
                highlightColorHex = "#008B45", // Oppo Green
                autostartHeader = "1. Enable Auto-Launch & Secondary Launch",
                autostartDetail = "• Settings → Apps → Auto-launch.\n• Enable 'Calculator Vault' for both Auto-launch and Allow background activities.",
                batteryHeader = "2. App Battery Management",
                batteryDetail = "• Settings → Battery → More battery settings → App battery management → Calculator Vault.\n• Enable 'Allow foreground activity' & 'Allow background activity'.",
                overlayHeader = "3. Display Over Other Apps",
                overlayDetail = "• Settings → Special app access → Display over other apps.\n• Turn toggle ON for Calculator Vault.",
                recentAppsLockTip = "In Recent Apps screen, tap the 2 dots or 3 dots at top of Calculator card and select 'Lock'.",
                extraNote = "ColorOS / OxygenOS terminates un-locked background apps during system RAM cleaning."
            )

            DeviceBrand.HUAWEI -> BrandGuide(
                brand = detected,
                deviceModelString = modelStr,
                androidVersionString = versionStr,
                highlightColorHex = "#CF0A2C", // Huawei Red
                autostartHeader = "1. App Launch: Manage Manually",
                autostartDetail = "• Settings → Battery → App launch.\n• Find Calculator Vault, turn off 'Manage automatically'.\n• Turn ON 'Auto-launch', 'Secondary launch', and 'Run in background'.",
                batteryHeader = "2. Ignore Battery Optimization",
                batteryDetail = "• Settings → Apps → Special access → Battery optimization.\n• Switch dropdown to 'All apps' → Calculator Vault → Select 'Don't allow'.",
                overlayHeader = "3. Draw Over Other Apps",
                overlayDetail = "• Settings → Apps → Special access → Draw over other apps → Allow.",
                recentAppsLockTip = "In Recent Apps, swipe down on Calculator Vault card to lock it.",
                extraNote = "Huawei EMUI aggressively kills background processes unless manual launch is configured."
            )

            DeviceBrand.PIXEL, DeviceBrand.OTHER -> BrandGuide(
                brand = detected,
                deviceModelString = modelStr,
                androidVersionString = versionStr,
                highlightColorHex = "#34A853", // Google Green
                autostartHeader = "1. Accessibility Service (Primary Trigger)",
                autostartDetail = "• Tap 'Open Accessibility' below → 'Installed apps' / 'Downloaded services'.\n• Turn ON 'Stealth Volume Recording' to intercept hardware buttons.",
                batteryHeader = "2. Battery Usage: Unrestricted",
                batteryDetail = "• Tap 'Open Battery Settings' below.\n• Choose 'Unrestricted' background battery usage so Android doesn't put service to sleep.",
                overlayHeader = "3. Display Over Other Apps",
                overlayDetail = "• Settings → Apps → Special app access → Display over other apps.\n• Turn toggle ON for Calculator Vault.",
                recentAppsLockTip = "Keep the Calculator in your Recent Apps multitasking drawer so volume triggers remain instantaneous.",
                extraNote = "Stock Android provides seamless operation once Accessibility and Unrestricted Battery are enabled."
            )
        }
    }

    fun openOemAutostartSettings(context: Context) {
        val intents = listOf(
            // Xiaomi
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            // Vivo
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")),
            // Oppo / Realme
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
            // Huawei
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.bootstart.BootStartActivity")),
            // Samsung
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"))
        )

        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }

        // Fallback to Application Details Settings
        openAppDetailsSettings(context)
    }

    @SuppressLint("BatteryLife")
    fun openBatterySettings(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val isIgnoring = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                if (!isIgnoring) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    return
                }
            }
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppDetailsSettings(context)
        }
    }

    fun openOverlaySettings(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return
            }
        } catch (_: Exception) {}
        openAppDetailsSettings(context)
    }

    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Could not open Accessibility settings", Toast.LENGTH_SHORT).show()
        }
    }

    fun openAppDetailsSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Could not open App settings", Toast.LENGTH_SHORT).show()
        }
    }
}
