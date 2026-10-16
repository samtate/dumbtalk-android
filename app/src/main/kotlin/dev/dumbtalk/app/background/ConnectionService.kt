package dev.dumbtalk.app.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import dev.dumbtalk.app.DumbTalkApp
import dev.dumbtalk.app.MainActivity
import dev.dumbtalk.app.R
import dev.dumbtalk.core.ConversationPage
import dev.dumbtalk.core.ServiceId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app process — and with it the Signal, Telegram and WhatsApp
 * connections — alive in the background. None of the three will push to a
 * linked device without a server, so a held-open connection is how messages
 * arrive. Its ongoing notification is silent and shows what's connected.
 */
class ConnectionService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while DumbTalk stays connected to receive messages."
                setShowBadge(false)
            },
        )
        startForeground(ID, notification("Connecting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val app = application as DumbTalkApp
        watcher = app.scope.launch {
            combine(app.inbox.connected, app.inbox.active) { connected: Set<ServiceId>?, page: ConversationPage? ->
                val services = connected.orEmpty().sortedBy { it.ordinal }.joinToString(", ") { it.label }
                val unread = page?.conversations.orEmpty().sumOf { it.unreadCount }
                when {
                    services.isEmpty() -> "No services linked"
                    unread > 0 -> "$services · $unread unread"
                    else -> services
                }
            }.collect { text -> manager.notify(ID, notification(text)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        watcher?.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DumbTalk")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL = "dumbtalk-connection"
        private const val ID = 1

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, ConnectionService::class.java)) }
                .onFailure { Log.w("ConnectionService", "could not start", it) }
        }
    }
}

/** Restarts the connection after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ConnectionService.start(context)
        }
    }
}
