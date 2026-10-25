package dev.dumbtalk.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/** Favourites first, then most recent activity. */
fun List<Conversation>.sortedForInbox(): List<Conversation> =
    sortedWith(
        compareByDescending<Conversation> { it.isFavourite }
            .thenByDescending { it.lastMessage?.sentAt ?: 0L },
    )

/** Holds the installed services and merges connected ones into one inbox. */
class ServiceRegistry(val installed: List<MessagingService>) {
    val events: Flow<ServiceEvent> = installed.map { it.events }.merge()

    fun byId(id: ServiceId): MessagingService =
        installed.firstOrNull { it.id == id } ?: error("Service ${id.label} is not installed")

    fun forConversation(conversation: Conversation): MessagingService = byId(conversation.serviceId)

    suspend fun connected(): List<MessagingService> = coroutineScope {
        installed.map { service -> async { service to runCatching { service.status() }.getOrNull() } }
            .awaitAll()
            .filter { (_, status) -> status?.connected == true }
            .map { (service, _) -> service }
    }

    /** Asks every service to reconnect now if it's down; see [MessagingService.reconnect]. */
    suspend fun reconnectAll(networkChanged: Boolean) = coroutineScope {
        installed.map { service -> async { runCatching { service.reconnect(networkChanged) } } }.awaitAll()
    }

    /** See [MessagingService.setForeground]. */
    fun setForeground(foreground: Boolean) = installed.forEach { service -> runCatching { service.setForeground(foreground) } }

    /** One service failing must not blank the whole inbox. */
    suspend fun inbox(archived: Boolean): ConversationPage = coroutineScope {
        val pages = connected()
            .map { service -> async { runCatching { service.listConversations(archived) }.getOrNull() } }
            .awaitAll()
            .filterNotNull()
        ConversationPage(
            conversations = pages.flatMap { it.conversations }.sortedForInbox(),
            archivedCount = pages.sumOf { it.archivedCount },
        )
    }
}
