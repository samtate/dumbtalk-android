package dev.dumbtalk.core.bridge

import dev.dumbtalk.core.Contact
import dev.dumbtalk.core.Attachment
import dev.dumbtalk.core.Capabilities
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.ConversationPage
import dev.dumbtalk.core.ConversationUpdate
import dev.dumbtalk.core.Direction
import dev.dumbtalk.core.Message
import dev.dumbtalk.core.MessagePage
import dev.dumbtalk.core.MessagingService
import dev.dumbtalk.core.SearchResult
import dev.dumbtalk.core.ServiceEvent
import dev.dumbtalk.core.ServiceId
import dev.dumbtalk.core.ServiceStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * What the native bridges (WhatsApp in Go, Signal in Rust) expose, adapted
 * from their generated bindings. Calls block on disk or network; structured
 * results are JSON in the shape [BridgeJson] reads.
 */
interface NativeBridge {
    fun isLinked(): Boolean
    fun isOnline(): Boolean
    /** {"phone": …} of the linked account. */
    fun accountJson(): String
    fun logout()

    fun chatsJson(archived: Boolean): String
    fun chatJson(chat: String): String
    fun messagesJson(chat: String, beforeTs: Long, beforeId: String, limit: Long): String
    fun searchJson(query: String): String

    fun sendText(chat: String, text: String, quoteId: String): String
    fun react(chat: String, id: String, emoji: String, remove: Boolean)
    fun edit(chat: String, id: String, text: String)
    fun delete(chat: String, id: String)
    fun markRead(chat: String)
    fun setTyping(chat: String, active: Boolean)
    fun openChat(chat: String) {}
    fun closeChat() {}
    fun setArchived(chat: String, value: Boolean)
    fun setPinned(chat: String, value: Boolean)
    fun setMuted(chat: String, value: Boolean)
    /** Chat id for a phone number on this network. */
    fun resolvePhone(phone: String): String
    /** Saved contacts as [{"id": chat id, "name", "detail"?}], sorted by name. */
    fun contactsJson(): String

    /** Downloads (once) a message's attachment; returns its local path. */
    fun downloadMedia(chat: String, id: String): String
    /** Sends a file (photo, video, audio or document by MIME type); returns the message JSON. */
    fun sendMedia(chat: String, path: String, mime: String, caption: String, voiceNote: Boolean): String
}

/** A failure reported by a native bridge, with a message fit to show. */
class BridgeException(message: String) : Exception(message)

/**
 * The shared half of the WhatsApp and Signal services: both bridges store
 * their own chats and messages and report changes as events, so everything
 * except account linking works the same way. Subclasses create the bridge
 * (via [start]) and implement setup.
 *
 * Events from the bridge: "status", "chats", "messages" {chat}, "incoming"
 * {chat,id}, "typing" {chat,sender,active} and "log" {message}; anything
 * else (linking progress) is queued on [linkEvents].
 */
abstract class BridgeService(final override val id: ServiceId, protected val scope: CoroutineScope) : MessagingService {
    private val mutableEvents = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 1024)
    final override val events: SharedFlow<ServiceEvent> = mutableEvents

    private val ready = CompletableDeferred<NativeBridge>()
    private val typing = TypingTracker()
    protected val json = BridgeJson(id, typing::names)
    protected var linkEvents = Channel<Pair<String, JSONObject>>(Channel.UNLIMITED)
        private set

    /** Creates the bridge off the main thread, then connects if linked. */
    protected fun start(create: () -> NativeBridge, connect: NativeBridge.() -> Unit) {
        scope.launch(Dispatchers.IO) {
            runCatching(create)
                .onSuccess { bridge ->
                    ready.complete(bridge)
                    if (bridge.isLinked()) runCatching { bridge.connect() }.onFailure { log("connect failed: ${it.message}") }
                }
                .onFailure {
                    log("bridge failed to start: ${it.message}")
                    ready.completeExceptionally(it)
                }
        }
    }

    protected abstract fun log(message: String)

    /** Runs a blocking bridge call on IO, turning its errors into [BridgeException]. */
    protected suspend fun <T> bridge(block: NativeBridge.() -> T): T {
        val bridge = ready.await()
        return withContext(Dispatchers.IO) {
            try {
                bridge.block()
            } catch (error: BridgeException) {
                throw error
            } catch (error: Exception) {
                throw BridgeException(error.message?.ifBlank { null } ?: "${id.label} request failed")
            }
        }
    }

    /** Called by the bridge's listener, on whatever thread it uses. */
    protected fun onBridgeEvent(kind: String, payload: String) {
        val data = JSONObject(payload)
        when (kind) {
            "status" -> emit(ServiceEvent.StatusChanged(id))
            "chats" -> emit(ServiceEvent.ConversationsChanged(id))
            "messages" -> data.optString("chat").takeIf { it.isNotEmpty() && it != "*" }
                ?.let { emit(ServiceEvent.MessagesChanged(id, json.conversationId(it))) }
            "incoming" -> scope.launch { announceIncoming(data.getString("chat"), data.getString("id")) }
            "typing" -> onTyping(data.getString("chat"), data.optString("sender"), data.optBoolean("active"))
            "log" -> log(data.optString("message"))
            else -> linkEvents.trySend(kind to data)
        }
    }

    private fun emit(event: ServiceEvent) {
        mutableEvents.tryEmit(event)
    }

    private suspend fun announceIncoming(chat: String, messageId: String) {
        val conversation = runCatching { json.conversation(bridge { chatJson(chat) }) }.getOrNull() ?: return
        val message = conversation.lastMessage?.takeIf { it.id == messageId } ?: return
        mutableEvents.emit(ServiceEvent.IncomingMessage(id, conversation, message))
    }

    private fun onTyping(chat: String, sender: String, active: Boolean) {
        typing.update(chat, sender, active)
        emit(ServiceEvent.ConversationsChanged(id))
        emit(ServiceEvent.MessagesChanged(id, json.conversationId(chat)))
        scope.launch {
            delay(typing.lapse + 100) // drop the notice once it lapses
            emit(ServiceEvent.ConversationsChanged(id))
        }
    }

    /** Clears linking events from an earlier attempt before starting a new one. */
    protected fun resetLinkEvents() {
        linkEvents.close()
        linkEvents = Channel(Channel.UNLIMITED)
    }

    protected suspend fun nextLinkEvent(timeoutMillis: Long = 5 * 60_000L): Pair<String, JSONObject> =
        withTimeout(timeoutMillis) { linkEvents.receive() }

    // region MessagingService

    override suspend fun status(): ServiceStatus {
        val bridge = runCatching { ready.await() }.getOrNull() ?: return ServiceStatus(id, connected = false, ready = false)
        return withContext(Dispatchers.IO) {
            // A linked account stays "connected" offline so its stored chats still show.
            ServiceStatus(
                id,
                connected = bridge.isLinked(),
                ready = bridge.isOnline(),
                accountLabel = JSONObject(bridge.accountJson()).optString("phone").ifBlank { null }?.let {
                    if (it.startsWith("+")) it else "+$it"
                },
            )
        }
    }

    override suspend fun capabilities() = Capabilities(
        reactions = true,
        edits = true,
        deletes = true,
        search = true,
        compose = true,
        muting = true,
        attachments = true,
        voiceNotes = true,
    )

    override suspend fun disconnect() = bridge { logout() }

    override suspend fun listConversations(archived: Boolean): ConversationPage {
        if (!bridge { isLinked() }) return ConversationPage(emptyList(), 0)
        return json.page(bridge { chatsJson(archived) })
    }

    override suspend fun listMessages(conversation: Conversation, before: Message?): MessagePage {
        val messages = json.messages(bridge { messagesJson(conversation.remoteId, before?.sentAt ?: 0L, before?.id.orEmpty(), PAGE) })
        return MessagePage(messages, hasMore = messages.size >= PAGE, typingNames = typing.names(conversation.remoteId))
    }

    override suspend fun markRead(conversation: Conversation) {
        if (conversation.unreadCount == 0 && conversation.lastMessage?.direction != Direction.INCOMING) return
        bridge { markRead(conversation.remoteId) }
        emit(ServiceEvent.ConversationsChanged(id))
    }

    override suspend fun openConversation(conversation: Conversation) = bridge { openChat(conversation.remoteId) }

    override suspend fun closeConversation(conversation: Conversation) = bridge { closeChat() }

    override suspend fun sendText(conversation: Conversation, text: String, replyTo: Message?): Message {
        val sent = json.message(bridge { sendText(conversation.remoteId, text, replyTo?.id.orEmpty()) })
        emit(ServiceEvent.MessagesChanged(id, conversation.id))
        return sent
    }

    override suspend fun setTyping(conversation: Conversation, active: Boolean) = bridge { setTyping(conversation.remoteId, active) }

    override suspend fun react(conversation: Conversation, message: Message, emoji: String, remove: Boolean) {
        bridge { react(conversation.remoteId, message.id, emoji, remove) }
        emit(ServiceEvent.MessagesChanged(id, conversation.id))
    }

    override suspend fun editMessage(conversation: Conversation, message: Message, text: String) {
        bridge { edit(conversation.remoteId, message.id, text) }
        emit(ServiceEvent.MessagesChanged(id, conversation.id))
    }

    override suspend fun deleteMessage(conversation: Conversation, message: Message) {
        bridge { delete(conversation.remoteId, message.id) }
        emit(ServiceEvent.MessagesChanged(id, conversation.id))
    }

    override suspend fun updateConversation(conversation: Conversation, update: ConversationUpdate) {
        update.archived?.let { value -> bridge { setArchived(conversation.remoteId, value) } }
        update.favourite?.let { value -> bridge { setPinned(conversation.remoteId, value) } }
        update.muted?.let { value -> bridge { setMuted(conversation.remoteId, value) } }
        emit(ServiceEvent.ConversationsChanged(id))
    }

    override suspend fun searchMessages(query: String, conversation: Conversation?): List<SearchResult> =
        json.searchResults(bridge { searchJson(query) }, conversation)

    override suspend fun downloadAttachment(conversation: Conversation, message: Message, attachment: Attachment): String =
        bridge { downloadMedia(conversation.remoteId, message.id) }

    override suspend fun sendAttachment(conversation: Conversation, path: String, mimeType: String, caption: String?) {
        bridge { sendMedia(conversation.remoteId, path, mimeType, caption.orEmpty(), false) }
        emit(ServiceEvent.MessagesChanged(id, conversation.id))
    }

    override suspend fun sendVoiceNote(conversation: Conversation, path: String, mimeType: String, durationMillis: Long) {
        bridge { sendMedia(conversation.remoteId, path, mimeType, "", true) }
        emit(ServiceEvent.MessagesChanged(id, conversation.id))
    }

    override suspend fun listContacts(): List<Contact> = json.contacts(bridge { contactsJson() })

    override suspend fun startChat(contact: Contact): Conversation = json.conversation(bridge { chatJson(contact.id) })

    override suspend fun createDirect(address: String, title: String?): Conversation {
        val chat = bridge { resolvePhone(address) }
        return json.conversation(bridge { chatJson(chat) })
    }

    // endregion

    private companion object {
        const val PAGE = 50L
    }
}
