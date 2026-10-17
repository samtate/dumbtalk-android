package dev.dumbtalk.app.background

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** What background delivery needs from Android, and what's missing. */
data class BackgroundStatus(
    /** Message notifications may be shown (Android 13+ asks). */
    val notifications: Boolean,
    /** Exempt from battery optimisation, so the connections survive Doze. */
    val unoptimised: Boolean,
    /** Set to "Restricted" in the app's battery settings, which stops background work outright. */
    val restricted: Boolean,
) {
    val ok get() = notifications && unoptimised && !restricted

    /** One line for Settings: fine, or the first thing to fix. */
    val summary get() = when {
        restricted -> "Battery use is Restricted: messages stop in the background"
        !unoptimised -> "Battery optimised: messages may be late"
        !notifications -> "Notifications are off"
        else -> "On: connected in the background"
    }

    companion object {
        fun of(context: Context): BackgroundStatus = BackgroundStatus(
            notifications = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            unoptimised = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            restricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
        )

        /** Opens the system screen that fixes the first problem in [status]. */
        fun fix(context: Context, status: BackgroundStatus = of(context)) {
            val app = Uri.parse("package:${context.packageName}")
            val intent = when {
                status.restricted -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)
                !status.unoptimised -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app)
                !status.notifications -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                else -> return
            }
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                // Some builds lack the direct screens; the app's own page has every setting.
                .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
    }
}

/**
 * Asks at launch for what background delivery needs: notification permission
 * (once; after that it's in Settings) and an exemption from battery
 * optimisation, asked again at most daily until granted. Create during the
 * activity's onCreate.
 */
class BackgroundPermissions(private val activity: ComponentActivity) {
    // Lazy: the activity has no context yet when this is created.
    private val prefs by lazy { activity.getSharedPreferences("setup", Context.MODE_PRIVATE) }
    private val notifications = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        askForBatteryExemption()
    }

    fun ask() {
        val status = BackgroundStatus.of(activity)
        if (!status.notifications && !prefs.getBoolean(ASKED_NOTIFICATIONS, false)) {
            prefs.edit().putBoolean(ASKED_NOTIFICATIONS, true).apply()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askForBatteryExemption()
        }
    }

    private fun askForBatteryExemption() {
        val status = BackgroundStatus.of(activity)
        if (status.unoptimised && !status.restricted) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(ASKED_BATTERY_AT, 0) < DAY) return
        prefs.edit().putLong(ASKED_BATTERY_AT, now).apply()
        BackgroundStatus.fix(activity, status.copy(notifications = true))
    }

    private companion object {
        const val ASKED_NOTIFICATIONS = "asked-notifications"
        const val ASKED_BATTERY_AT = "asked-battery-at"
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
