package dev.dumbtalk.app

import android.app.Application
import dev.dumbtalk.core.ConversationPage
import dev.dumbtalk.core.ServiceEvent
import dev.dumbtalk.core.ServiceId
import dev.dumbtalk.core.ServiceRegistry
import dev.dumbtalk.core.demo.DemoService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

class DumbTalkApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var registry: ServiceRegistry
        private set
    lateinit var inbox: InboxModel
        private set

    override fun onCreate() {
        super.onCreate()
        // Real services replace these one by one; debug builds keep the demo
        // data so the UI can be worked on without linking accounts.
        registry = ServiceRegistry(ServiceId.entries.map { DemoService(it, scope) })
        inbox = InboxModel(registry, scope)
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
