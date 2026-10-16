package dev.dumbtalk.app

import android.app.Application
import android.os.Build
import dev.dumbtalk.app.background.ConnectionService
import dev.dumbtalk.app.background.MessageNotifier
import dev.dumbtalk.core.ConversationPage
import dev.dumbtalk.core.ServiceEvent
import dev.dumbtalk.core.MessagingService
import dev.dumbtalk.core.ServiceId
import dev.dumbtalk.core.ServiceRegistry
import dev.dumbtalk.signal.SignalService
import dev.dumbtalk.telegram.TelegramConfig
import dev.dumbtalk.telegram.TelegramService
import dev.dumbtalk.whatsapp.WhatsAppService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

open class DumbTalkApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var registry: ServiceRegistry
        private set
    lateinit var inbox: InboxModel
        private set
    lateinit var notifier: MessageNotifier
        private set

    /** The messaging services, in inbox order. Tests swap in demo services. */
    protected open fun createServices(): List<MessagingService> {
        val device = "DumbTalk (${Build.MODEL})"
        return listOf(
            SignalService(this, File(filesDir, "signal"), device, scope),
            TelegramService(
                TelegramConfig(
                    dataDir = File(filesDir, "telegram"),
                    apiId = BuildConfig.TELEGRAM_API_ID,
                    apiHash = BuildConfig.TELEGRAM_API_HASH,
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    systemVersion = "Android ${Build.VERSION.RELEASE}",
                    appVersion = BuildConfig.VERSION_NAME,
                    language = Locale.getDefault().language.ifBlank { "en" },
                ),
                scope,
            ),
            WhatsAppService(File(filesDir, "whatsapp"), device, scope),
        )
    }

    override fun onCreate() {
        super.onCreate()
        registry = ServiceRegistry(createServices())
        inbox = InboxModel(registry, scope)

        notifier = MessageNotifier(this)
        scope.launch {
            // Unbounded so no notification is lost while one is being posted.
            registry.events.filterIsInstance<ServiceEvent.IncomingMessage>().buffer(Channel.UNLIMITED).collect { event ->
                runCatching { notifier.show(event.conversation, event.message) }
            }
        }
        ConnectionService.start(this)
    }
}

/** The merged inbox, kept warm so returning to it never flashes empty. */
class InboxModel(private val registry: ServiceRegistry, scope: CoroutineScope) {
    private val mutableActive = MutableStateFlow<ConversationPage?>(null)
    private val mutableArchived = MutableStateFlow<ConversationPage?>(null)
    private val mutableConnected = MutableStateFlow<Set<ServiceId>?>(null)
    val active: StateFlow<ConversationPage?> = mutableActive
    val archived: StateFlow<ConversationPage?> = mutableArchived
    val connected: StateFlow<Set<ServiceId>?> = mutableConnected

    init {
        scope.launch {
            refresh()
            registry.events
                .filter { it is ServiceEvent.ConversationsChanged || it is ServiceEvent.StatusChanged }
                .conflate()
                .collect {
                    refresh()
                    delay(150) // coalesce bursts of updates
                }
        }
    }

    suspend fun refresh() {
        mutableConnected.value = registry.connected().map { it.id }.toSet()
        mutableActive.value = registry.inbox(archived = false)
        mutableArchived.value = registry.inbox(archived = true)
    }

    fun find(conversationId: String) =
        (active.value?.conversations.orEmpty() + archived.value?.conversations.orEmpty())
            .firstOrNull { it.id == conversationId }
}
