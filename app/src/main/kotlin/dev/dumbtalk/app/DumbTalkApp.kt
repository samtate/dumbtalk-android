package dev.dumbtalk.app

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import dev.dumbtalk.app.background.ConnectionService
import dev.dumbtalk.app.background.MessageNotifier
import dev.dumbtalk.app.media.MediaFiles
import dev.dumbtalk.app.sms.SmsService
import dev.dumbtalk.app.ui.Fonts
import dev.dumbtalk.core.MessagingService
import dev.dumbtalk.core.ServiceEvent
import dev.dumbtalk.core.ServiceId
import dev.dumbtalk.core.ServiceRegistry
import dev.dumbtalk.core.demo.DemoService
import dev.dumbtalk.signal.SignalService
import dev.dumbtalk.telegram.TelegramConfig
import dev.dumbtalk.telegram.TelegramService
import dev.dumbtalk.whatsapp.WhatsAppService
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

open class DumbTalkApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var registry: ServiceRegistry
        private set
    lateinit var inbox: InboxModel
        private set
    lateinit var notifier: MessageNotifier
        private set

    /** The SMS service, unless tests left it out. */
    val sms: SmsService? get() = registry.installed.firstNotNullOfOrNull { it as? SmsService }

    /** Unsent text per conversation id, kept while the app runs. */
    val drafts = ConcurrentHashMap<String, String>()

    /** Robolectric can't build custom font fallback chains. */
    protected open val usePixelFallbackFonts = true

    /** The first-run welcome and the inbox's background warning; tests turn them off to start in the inbox. */
    open val guidesUser = true

    private val prefs by lazy { getSharedPreferences("setup", MODE_PRIVATE) }

    /** The welcome has been seen. */
    var welcomed: Boolean
        get() = prefs.getBoolean(WELCOMED, false)
        set(value) = prefs.edit().putBoolean(WELCOMED, value).apply()

    /** Demo chats stand in for the real services, so people can try the keys without linking anything. */
    val demo: Boolean get() = BuildConfig.DEMO || prefs.getBoolean(DEMO, false)

    /** Turns the demo on or off; the services are only built at startup, so this restarts the app. */
    fun restartWithDemo(activity: Activity, demo: Boolean) {
        prefs.edit().putBoolean(DEMO, demo).commit()
        restart(activity)
    }

    enum class Maintenance { BACKUP, RESTORE }

    /**
     * Backing up or restoring happens in a restart where no service runs, so
     * none of their databases is open. The marker lives in no_backup/, which
     * backups leave out, so restoring one can't bring it back.
     */
    val maintenance: Maintenance? by lazy {
        runCatching { Maintenance.valueOf(maintenanceMarker.readText()) }.getOrNull()
    }
    private val maintenanceMarker get() = File(noBackupFilesDir, "maintenance")

    /** Restarts into [maintenance], or back to normal with null. */
    fun restartFor(activity: Activity, maintenance: Maintenance?) {
        if (maintenance == null) maintenanceMarker.delete() else maintenanceMarker.writeText(maintenance.name)
        restart(activity)
    }

    /** Starts again from scratch; tests record it instead of ending their JVM. */
    protected open fun restart(activity: Activity) {
        activity.startActivity(Intent.makeRestartActivityTask(ComponentName(this, MainActivity::class.java)))
        exitProcess(0)
    }

    /** The messaging services, in inbox order. Tests swap in demo services. */
    protected open fun createServices(): List<MessagingService> {
        if (demo) return ServiceId.entries.map { DemoService(it, scope) }
        val device = "DumbTalk (${Build.MODEL})"
        return listOf(
            SignalService(this, File(filesDir, "signal"), device, scope),
            TelegramService(
                TelegramConfig(
                    dataDir = File(filesDir, "telegram"),
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    systemVersion = "Android ${Build.VERSION.RELEASE}",
                    appVersion = BuildConfig.VERSION_NAME,
                    language = Locale.getDefault().language.ifBlank { "en" },
                ),
                scope,
            ),
            WhatsAppService(File(filesDir, "whatsapp"), device, scope),
            SmsService(this).also { it.refresh() },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Android launches this lightweight process for SMS delivery even when
        // the UI is gone. Never initialize fonts/native bridges on that path.
        if (Application.getProcessName().endsWith(":sms")) return
        Fonts.init(this, pixelFallback = usePixelFallbackFonts)
        MediaFiles.cleanOutgoing(this)
        registry = ServiceRegistry(if (maintenance == null) createServices() else emptyList())
        inbox = InboxModel(registry, scope)

        notifier = MessageNotifier(this)
        scope.launch {
            // Unbounded so no notification is lost while one is being posted.
            registry.events.filterIsInstance<ServiceEvent.IncomingMessage>().buffer(Channel.UNLIMITED).collect { event ->
                runCatching { notifier.show(event.conversation, event.message) }
            }
        }
        // A chat read elsewhere (on the main phone, say) takes its notification with it.
        scope.launch { inbox.active.collect { page -> page?.let { runCatching { notifier.clearRead(it.conversations) } } } }
        if (maintenance == null) ConnectionService.start(this)
    }

    private companion object {
        const val WELCOMED = "welcomed"
        const val DEMO = "demo"
    }
}
