package dev.dumbtalk.app.background

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Asks, once each, for what background delivery needs: notification
 * permission (Android 13+) and an exemption from battery optimisation so the
 * connections survive Doze. Create during the activity's onCreate.
 */
class BackgroundPermissions(private val activity: ComponentActivity) {
    // Lazy: the activity has no context yet when this is created.
    private val prefs by lazy { activity.getSharedPreferences("setup", Context.MODE_PRIVATE) }
    private val notifications = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        askForBatteryExemption()
    }

    fun ask() {
        val granted = activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted && once("asked-notifications")) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askForBatteryExemption()
        }
    }

    private fun askForBatteryExemption() {
        val power = activity.getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(activity.packageName) || !once("asked-battery")) return
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")),
            )
        }
    }

    /** True the first time it's called for [key]. */
    private fun once(key: String): Boolean {
        if (prefs.getBoolean(key, false)) return false
        prefs.edit().putBoolean(key, true).apply()
        return true
    }
}
