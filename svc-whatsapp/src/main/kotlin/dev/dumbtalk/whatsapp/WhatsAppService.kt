package dev.dumbtalk.whatsapp

import android.util.Log
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
import dev.dumbtalk.core.SetupStep
import dev.dumbtalk.core.bridge.BridgeJson
import dev.dumbtalk.core.bridge.TypingTracker
import dev.dumbtalk.whatsapp.go.wabridge.Client
import dev.dumbtalk.whatsapp.go.wabridge.Listener
import dev.dumbtalk.whatsapp.go.wabridge.Wabridge
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
import java.io.File

class WhatsAppException(message: String) : Exception(message)

/**
 * WhatsApp as a linked device, through the Go bridge (whatsmeow). The bridge
 * stores chats and messages itself; this class maps its JSON to the contract.
 */
class WhatsAppService(
    private val dataDir: File,
    private val deviceName: String,
    private val scope: CoroutineScope,
) : MessagingService {
    override val id = ServiceId.WHATSAPP

    private val mutableEvents = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 1024)
    override val events: SharedFlow<ServiceEvent> = mutableEvents

    private val clientReady = CompletableDeferred<Client>()
    private var linkEvents = Channel<Pair<String, JSONObject>>(Channel.UNLIMITED)
    private val typing = TypingTracker()
    private val json = BridgeJson(id, typing::names)

    private val listener = object : Listener {
        override fun onEvent(kind: String, payload: String) = handle(kind, JSONObject(payload))
    }

    init {
        scope.launch(Dispatchers.IO) {
            runCatching { Wabridge.newClient(dataDir.absolutePath, deviceName, listener) }
                .onSuccess { client ->
                    clientReady.complete(client)
                    if (client.isLinked) runCatching { client.connect() }.onFailure { Log.w(TAG, "connect failed", it) }
                }
                .onFailure {
                    Log.e(TAG, "WhatsApp bridge failed to start", it)
                    clientReady.completeExceptionally(it)
                }
        }
    }

    /** Bridge calls block on the network or disk, so they run on IO. */
    private suspend fun <T> bridge(block: Client.() -> T): T {
        val client = clientReady.await()
        return withContext(Dispatchers.IO) {
            try {
                client.block()
            } catch (error: Exception) {
                throw WhatsAppException(error.message?.substringAfterLast("go: ")?.ifBlank { null } ?: "WhatsApp request failed")
            }
        }
    }

    private fun handle(kind: String, payload: JSONObject) {
        when (kind) {
            "status" -> mutableEvents.tryEmit(ServiceEvent.StatusChanged(id))
            "chats" -> mutableEvents.tryEmit(ServiceEvent.ConversationsChanged(id))
            "messages" -> {
                val chat = payload.optString("chat")
                if (chat.isNotEmpty() && chat != "*") mutableEvents.tryEmit(ServiceEvent.MessagesChanged(id, json.conversationId(chat)))
            }
            "incoming" -> scope.launch { announceIncoming(payload.getString("chat"), payload.getString("id")) }
            "typing" -> onTyping(payload)
            "qr", "pair-code", "paired", "link-error" -> linkEvents.trySend(kind to payload)
            "log" -> Log.w(TAG, payload.optString("message"))
        }
    }

    private suspend fun announceIncoming(chat: String, messageId: String) {
        val conversation = runCatching { json.conversation(bridge { chatJSON(chat) }) }.getOrNull() ?: return
        val message = conversation.lastMessage?.takeIf { it.id == messageId } ?: return
        mutableEvents.emit(ServiceEvent.IncomingMessage(id, conversation, message))
    }

    private fun onTyping(payload: JSONObject) {
        val chat = payload.getString("chat")
        typing.update(chat, payload.optString("sender"), payload.optBoolean("active"))
        mutableEvents.tryEmit(ServiceEvent.ConversationsChanged(id))
        mutableEvents.tryEmit(ServiceEvent.MessagesChanged(id, json.conversationId(chat)))
        scope.launch {
            delay(typing.lapse + 100)
            mutableEvents.emit(ServiceEvent.ConversationsChanged(id))
        }
    }

    // region Status and setup

    override suspend fun status(): ServiceStatus {
        val client = runCatching { clientReady.await() }.getOrNull()
            ?: return ServiceStatus(id, connected = false, ready = false)
        // A linked account stays "connected" offline so its stored chats still show.
        val linked = withContext(Dispatchers.IO) { client.isLinked }
        val account = JSONObject(withContext(Dispatchers.IO) { client.accountJSON() })
        return ServiceStatus(
            id,
            connected = linked,
            ready = withContext(Dispatchers.IO) { client.isConnected },
            accountLabel = account.optString("phone").takeIf { it.isNotEmpty() }?.let { "+$it" },
        )
    }

    override suspend fun capabilities() = Capabilities(
        reactions = true,
        edits = true,
        deletes = true,
        search = true,
        compose = true,
        muting = true,
    )

    override suspend fun beginSetup(): SetupStep {
        if (bridge { isLinked }) return complete()
        return SetupStep.Choice(
            "method", "Link WhatsApp", "Choose how to link this phone.",
            listOf(
                SetupStep.Choice.Option("qr", "QR code", "Scan from WhatsApp on your main phone"),
                SetupStep.Choice.Option("phone", "Phone number", "Type a pairing code instead"),
            ),
        )
    }

    override suspend fun advanceSetup(step: SetupStep, value: String?): SetupStep = when (step) {
        is SetupStep.Choice -> if (value == "phone") {
            SetupStep.Input(
                "phone", "Phone number", "Enter your WhatsApp number with its country code.",
                SetupStep.Input.Field.PHONE, placeholder = "+44…",
            )
        } else {
            startLink("")
            nextLinkStep(phone = "")
        }
        is SetupStep.Input -> {
            val phone = value.orEmpty().filter { it.isDigit() }
            if (phone.length < 7) throw WhatsAppException("Enter the full number with its country code")
            startLink(phone)
            nextLinkStep(phone)
        }
        is SetupStep.Qr -> nextLinkStep(phone = "")
        is SetupStep.PairCode -> nextLinkStep(step.phone)
        is SetupStep.Complete -> step
    }

    private suspend fun startLink(phone: String) {
        linkEvents.close()
        linkEvents = Channel(Channel.UNLIMITED)
        bridge { startLink(phone) }
    }

    /** Waits for the next linking event; QR codes refresh roughly every 20 seconds. */
    private suspend fun nextLinkStep(phone: String): SetupStep {
        val (kind, payload) = withTimeout(LINK_TIMEOUT_MILLIS) { linkEvents.receive() }
        return when (kind) {
            "qr" -> SetupStep.Qr(
                "qr", "Scan QR code",
                "On your main phone open WhatsApp → Settings → Linked devices → Link a device, then scan this code.",
                payload.getString("code"),
            )
            "pair-code" -> SetupStep.PairCode(
                "pair", "Pairing code",
                "On your main phone open WhatsApp → Settings → Linked devices → Link a device → Link with phone number instead, then enter this code.",
                payload.getString("code").chunked(4).joinToString("-"),
                phone,
            )
            "paired" -> complete()
            else -> throw WhatsAppException(payload.optString("error", "Linking failed"))
        }
    }

    private fun complete() = SetupStep.Complete(
        "WhatsApp connected",
        "Recent chats arrive from your main phone over the next few minutes.",
    )

    override suspend fun disconnect() {
        bridge { logout() }
    }

    // endregion

    // region Conversations and messages

    override suspend fun listConversations(archived: Boolean): ConversationPage {
        if (!bridge { isLinked }) return ConversationPage(emptyList(), 0)
        return json.page(bridge { chatsJSON(archived) })
    }

    override suspend fun listMessages(conversation: Conversation, before: Message?): MessagePage {
        val messages = json.messages(bridge { messagesJSON(conversation.remoteId, before?.sentAt ?: 0L, before?.id.orEmpty(), PAGE.toLong()) })
        return MessagePage(messages, hasMore = messages.size >= PAGE, typingNames = typing.names(conversation.remoteId))
    }

    override suspend fun markRead(conversation: Conversation) {
        if (conversation.unreadCount == 0 && conversation.lastMessage?.direction != Direction.INCOMING) return
        bridge { markRead(conversation.remoteId) }
        mutableEvents.emit(ServiceEvent.ConversationsChanged(id))
    }

    override suspend fun openConversation(conversation: Conversation) {
        bridge { openChat(conversation.remoteId) }
    }

    override suspend fun closeConversation(conversation: Conversation) {
        bridge { closeChat() }
    }

    override suspend fun sendText(conversation: Conversation, text: String, replyTo: Message?): Message {
        val sent = json.message(bridge { sendText(conversation.remoteId, text, replyTo?.id.orEmpty()) })
        mutableEvents.emit(ServiceEvent.MessagesChanged(id, conversation.id))
        return sent
    }

    override suspend fun setTyping(conversation: Conversation, active: Boolean) {
        bridge { setTyping(conversation.remoteId, active) }
    }

    override suspend fun react(conversation: Conversation, message: Message, emoji: String, remove: Boolean) {
        bridge { react(conversation.remoteId, message.id, if (remove) "" else emoji) }
        mutableEvents.emit(ServiceEvent.MessagesChanged(id, conversation.id))
    }

    override suspend fun editMessage(conversation: Conversation, message: Message, text: String) {
        bridge { edit(conversation.remoteId, message.id, text) }
        mutableEvents.emit(ServiceEvent.MessagesChanged(id, conversation.id))
    }

    override suspend fun deleteMessage(conversation: Conversation, message: Message) {
        bridge { revoke(conversation.remoteId, message.id) }
        mutableEvents.emit(ServiceEvent.MessagesChanged(id, conversation.id))
    }

    override suspend fun updateConversation(conversation: Conversation, update: ConversationUpdate) {
        update.archived?.let { archived -> bridge { setArchived(conversation.remoteId, archived) } }
        update.favourite?.let { pinned -> bridge { setPinned(conversation.remoteId, pinned) } }
        update.muted?.let { muted -> bridge { setMuted(conversation.remoteId, muted) } }
        mutableEvents.emit(ServiceEvent.ConversationsChanged(id))
    }

    override suspend fun searchMessages(query: String, conversation: Conversation?): List<SearchResult> =
        json.searchResults(bridge { searchJSON(query) }, conversation)

    override suspend fun createDirect(address: String, title: String?): Conversation {
        val jid = bridge { resolvePhone(address) }
        return json.conversation(bridge { chatJSON(jid) })
    }

    // endregion

    private companion object {
        const val TAG = "WhatsApp"
        const val PAGE = 50
        const val LINK_TIMEOUT_MILLIS = 5 * 60_000L
    }
}
