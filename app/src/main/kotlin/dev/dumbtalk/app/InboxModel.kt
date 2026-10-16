package dev.dumbtalk.app

import dev.dumbtalk.core.ConversationPage
import dev.dumbtalk.core.ServiceEvent
import dev.dumbtalk.core.ServiceId
import dev.dumbtalk.core.ServiceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

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
