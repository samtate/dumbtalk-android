package dev.dumbtalk.core.bridge

import dev.dumbtalk.core.Contact
import dev.dumbtalk.core.Attachment
import dev.dumbtalk.core.Capabilities
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.ConversationPage
import dev.dumbtalk.core.ConversationUpdate
import dev.dumbtalk.core.Direction
import dev.dumbtalk.core.Member
import dev.dumbtalk.core.Message
import dev.dumbtalk.core.TextSpan
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
import org.json.JSONArray
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
    /** Connects (again) now if linked and not connected. */
    fun reconnect()

    fun chatsJson(archived: Boolean): String
    fun chatJson(chat: String): String
    fun messagesJson(chat: String, beforeTs: Long, beforeId: String, limit: Long): String
    fun pinnedMessagesJson(chat: String): String = "[]"
    fun pinMessage(chat: String, id: String, pinned: Boolean) { throw BridgeException("Message pins are unavailable") }
    /** [optionsJson] is a JSON array of option names; returns the sent message. */
    fun createPoll(chat: String, question: String, optionsJson: String, multiple: Boolean): String = throw BridgeException("Polls are unavailable")
    /** [optionsJson] is a JSON array of option indexes; empty withdraws the vote. */
    fun votePoll(chat: String, id: String, optionsJson: String) { throw BridgeException("Polls are unavailable") }
    fun closePoll(chat: String, id: String) { throw BridgeException("Closing polls is unavailable") }
    fun searchJson(query: String): String

    /** [spansJson]: mentions [{start, length, style: "mention", id}] over [text], or "". */
    fun sendText(chat: String, text: String, quoteId: String, spansJson: String): String
    /** The chat's other people: [{"id", "name", "admin"?, "pending"?}]. */
    fun membersJson(chat: String): String = "[]"
    fun setExpireTimer(chat: String, seconds: Int) { throw BridgeException("Disappearing messages are unavailable") }
    /** "accept", "block", "unblock" or "delete" a message request. */
    fun respondRequest(chat: String, action: String) { throw BridgeException("Message requests are unavailable") }
    fun setBlocked(chat: String, blocked: Boolean) = respondRequest(chat, if (blocked) "block" else "unblock")
    /** [membersJson]: a JSON array of chat ids; returns the new group's chat id. */
    fun createGroup(name: String, membersJson: String): String = throw BridgeException("Groups are unavailable")
    fun renameGroup(chat: String, name: String) { throw BridgeException("Groups are unavailable") }
    fun addMembers(chat: String, membersJson: String) { throw BridgeException("Groups are unavailable") }
    fun removeMember(chat: String, member: String) { throw BridgeException("Groups are unavailable") }
    fun leaveGroup(chat: String) { throw BridgeException("Groups are unavailable") }
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
    fun downloadMediaAt(chat: String, id: String, index: Int): String = downloadMedia(chat, id)
    fun conversationAvatar(chat: String): String = ""
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

    override suspend fun reconnect(networkChanged: Boolean) = bridge { if (isLinked()) reconnect() }

    override suspend fun listConversations(archived: Boolean): ConversationPage {
        if (!bridge { isLinked() }) return ConversationPage(emptyList(), 0)
        return json.page(bridge { chatsJson(archived) })
    }

    override suspend fun conversationAvatar(conversation: Conversation): String? =
        bridge { conversationAvatar(conversation.remoteId) }.ifBlank { conversation.avatarPath }

    /** The bridge chat id of a one-to-one chat with [personId]. */
    protected open fun personChat(personId: String): String = personId

    override suspend fun personAvatar(personId: String): String? =
        bridge { conversationAvatar(personChat(personId)) }.ifBlank { null }

    override suspend fun listMessages(conversation: Conversation, before: Message?): MessagePage {
        val messages = json.messages(bridge { messagesJson(conversation.remoteId, before?.sentAt ?: 0L, before?.id.orEmpty(), PAGE) })
        return MessagePage(messages, hasMore = messages.size >= PAGE, typingNames = typing.names(conversation.remoteId))
    }

    override suspend fun listPinnedMessages(conversation: Conversation): List<Message> =
        json.messages(bridge { pinnedMessagesJson(conversation.remoteId) })

    override suspend fun pinMessage(conversation: Conversation, message: Message, pinned: Boolean) =
        changeMessages(conversation) { pinMessage(conversation.remoteId, message.id, pinned) }

    override suspend fun createPoll(conversation: Conversation, question: String, options: List<String>, multiple: Boolean) {
        require(question.isNotBlank()) { "A poll needs a question" }
        require(options.count { it.isNotBlank() } >= 2) { "A poll needs at least two options" }
        changeMessages(conversation) {
            createPoll(conversation.remoteId, question.trim(), JSONArray(options.map { it.trim() }.filter { it.isNotEmpty() }).toString(), multiple)
        }
    }

    override suspend fun votePoll(conversation: Conversation, message: Message, options: List<Int>) =
        changeMessages(conversation) { votePoll(conversation.remoteId, message.id, JSONArray(options).toString()) }

    override suspend fun closePoll(conversation: Conversation, message: Message) =
        changeMessages(conversation) { closePoll(conversation.remoteId, message.id) }

    override suspend fun markRead(conversation: Conversation) {
        if (conversation.unreadCount == 0 && conversation.lastMessage?.direction != Direction.INCOMING) return
        bridge { markRead(conversation.remoteId) }
        emit(ServiceEvent.ConversationsChanged(id))
    }

    override suspend fun openConversation(conversation: Conversation) = bridge { openChat(conversation.remoteId) }

    override suspend fun closeConversation(conversation: Conversation) = bridge { closeChat() }

    override suspend fun sendText(conversation: Conversation, text: String, replyTo: Message?): Message =
        sendText(conversation, text, replyTo, emptyList())

    override suspend fun sendText(conversation: Conversation, text: String, replyTo: Message?, mentions: List<TextSpan>): Message {
        val spans = mentions.filter { it.style == TextSpan.Style.MENTION && it.target != null }
        val encoded = if (spans.isEmpty()) "" else JSONArray(spans.map {
            JSONObject().put("start", it.start).put("length", it.length).put("style", "mention").put("id", it.target)
        }).toString()
        return json.message(changeMessages(conversation) { sendText(conversation.remoteId, text, replyTo?.id.orEmpty(), encoded) })
    }

    override suspend fun listMembers(conversation: Conversation): List<Member> =
        json.members(bridge { membersJson(conversation.remoteId) })

    override suspend fun setBlocked(conversation: Conversation, blocked: Boolean) {
        bridge { setBlocked(conversation.remoteId, blocked) }
        emit(ServiceEvent.ConversationsChanged(id))
    }

    override suspend fun respondToMessageRequest(conversation: Conversation, accept: Boolean) =
        changeChat(conversation) { respondRequest(it, if (accept) "accept" else "delete") }

    override suspend fun createGroup(name: String, members: List<String>): Conversation {
        val chat = bridge { createGroup(name, JSONArray(members).toString()) }
        emit(ServiceEvent.ConversationsChanged(id))
        return json.conversation(bridge { chatJson(chat) })
    }

    override suspend fun renameGroup(conversation: Conversation, name: String) = changeChat(conversation) { renameGroup(it, name) }

    override suspend fun addGroupMembers(conversation: Conversation, members: List<String>) =
        changeChat(conversation) { addMembers(it, JSONArray(members).toString()) }

    override suspend fun removeGroupMember(conversation: Conversation, member: Member) = changeChat(conversation) { removeMember(it, member.id) }

    override suspend fun leaveGroup(conversation: Conversation) = changeChat(conversation) { leaveGroup(it) }

    /**
     * Runs a bridge call that changes [conversation]'s messages, then tells an
     * open chat to reload; not every bridge reports its own changes.
     */
    private suspend fun <T> changeMessages(conversation: Conversation, change: NativeBridge.() -> T): T =
        bridge(change).also { emit(ServiceEvent.MessagesChanged(id, conversation.id)) }

    /** Like [changeMessages] for a change to the chat itself, given its remote id. */
    private suspend fun changeChat(conversation: Conversation, change: NativeBridge.(String) -> Unit) {
        changeMessages(conversation) { change(conversation.remoteId) }
        emit(ServiceEvent.ConversationsChanged(id))
    }

    override suspend fun setTyping(conversation: Conversation, active: Boolean) = bridge { setTyping(conversation.remoteId, active) }

    override suspend fun react(conversation: Conversation, message: Message, emoji: String, remove: Boolean) =
        changeMessages(conversation) { react(conversation.remoteId, message.id, emoji, remove) }

    override suspend fun editMessage(conversation: Conversation, message: Message, text: String) =
        changeMessages(conversation) { edit(conversation.remoteId, message.id, text) }

    override suspend fun deleteMessage(conversation: Conversation, message: Message) =
        changeMessages(conversation) { delete(conversation.remoteId, message.id) }

    override suspend fun updateConversation(conversation: Conversation, update: ConversationUpdate) {
        update.archived?.let { value -> bridge { setArchived(conversation.remoteId, value) } }
        update.favourite?.let { value -> bridge { setPinned(conversation.remoteId, value) } }
        update.muted?.let { value -> bridge { setMuted(conversation.remoteId, value) } }
        update.expiration?.let { value -> bridge { setExpireTimer(conversation.remoteId, value) } }
        emit(ServiceEvent.ConversationsChanged(id))
        if (update.expiration != null) emit(ServiceEvent.MessagesChanged(id, conversation.id))
    }

    override suspend fun searchMessages(query: String, conversation: Conversation?): List<SearchResult> =
        json.searchResults(bridge { searchJson(query) }, conversation)

    override suspend fun downloadAttachment(conversation: Conversation, message: Message, attachment: Attachment): String =
        bridge { downloadMediaAt(conversation.remoteId, message.id, message.attachments.indexOf(attachment).coerceAtLeast(0)) }

    override suspend fun sendAttachment(conversation: Conversation, path: String, mimeType: String, caption: String?) {
        changeMessages(conversation) { sendMedia(conversation.remoteId, path, mimeType, caption.orEmpty(), false) }
    }

    override suspend fun sendVoiceNote(conversation: Conversation, path: String, mimeType: String, durationMillis: Long) {
        changeMessages(conversation) { sendMedia(conversation.remoteId, path, mimeType, "", true) }
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
