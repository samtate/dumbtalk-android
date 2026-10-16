package dev.dumbtalk.signal

import android.content.Context
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
import uniffi.sigbridge.BridgeException
import uniffi.sigbridge.Listener
import uniffi.sigbridge.SignalClient
import java.io.File

class SignalException(message: String) : Exception(message)

/**
 * Signal as a linked device, through the Rust bridge (presage). Like the
 * WhatsApp bridge, it keeps its own chats and messages and returns JSON.
 */
class SignalService(
    context: Context,
    private val dataDir: File,
    private val deviceName: String,
    private val scope: CoroutineScope,
) : MessagingService {
    override val id = ServiceId.SIGNAL

    private val mutableEvents = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<ServiceEvent> = mutableEvents

    private val clientReady = CompletableDeferred<SignalClient>()
    private var linkEvents = Channel<Pair<String, JSONObject>>(Channel.UNLIMITED)
    private val typing = TypingTracker()
    private val json = BridgeJson(id, typing::names)

    private val listener = object : Listener {
        override fun onEvent(kind: String, payload: String) = handle(kind, JSONObject(payload))
    }

    init {
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            runCatching {
                SignalNative.init(appContext)
                SignalClient(dataDir.absolutePath, deviceName, listener)
            }.onSuccess { client ->
                clientReady.complete(client)
                if (client.isLinked()) runCatching { client.connect() }.onFailure { Log.w(TAG, "connect failed", it) }
            }.onFailure {
                Log.e(TAG, "Signal bridge failed to start", it)
                clientReady.completeExceptionally(it)
            }
        }
    }

    private suspend fun <T> bridge(block: SignalClient.() -> T): T {
        val client = clientReady.await()
        return withContext(Dispatchers.IO) {
            try {
                client.block()
            } catch (error: BridgeException) {
                throw SignalException(error.message?.ifBlank { null } ?: "Signal request failed")
            }
        }
    }

    private fun handle(kind: String, payload: JSONObject) {
        when (kind) {
            "status" -> mutableEvents.tryEmit(ServiceEvent.StatusChanged(id))
            "chats" -> mutableEvents.tryEmit(ServiceEvent.ConversationsChanged(id))
            "messages" -> payload.optString("chat").takeIf { it.isNotEmpty() }?.let {
                mutableEvents.tryEmit(ServiceEvent.MessagesChanged(id, json.conversationId(it)))
            }
            "incoming" -> scope.launch { announceIncoming(payload.getString("chat"), payload.getString("id")) }
            "typing" -> {
                val chat = payload.getString("chat")
                typing.update(chat, payload.optString("sender"), payload.optBoolean("active"))
                mutableEvents.tryEmit(ServiceEvent.ConversationsChanged(id))
                mutableEvents.tryEmit(ServiceEvent.MessagesChanged(id, json.conversationId(chat)))
                scope.launch {
                    delay(typing.lapse + 100)
                    mutableEvents.emit(ServiceEvent.ConversationsChanged(id))
                }
            }
            "qr", "paired", "link-error" -> linkEvents.trySend(kind to payload)
            "log" -> Log.w(TAG, payload.optString("message"))
        }
    }

    private suspend fun announceIncoming(chat: String, messageId: String) {
        val conversation = runCatching { json.conversation(bridge { chatJson(chat) }) }.getOrNull() ?: return
        val message = conversation.lastMessage?.takeIf { it.id == messageId } ?: return
        mutableEvents.emit(ServiceEvent.IncomingMessage(id, conversation, message))
    }

    // region Status and setup

    override suspend fun status(): ServiceStatus {
        val client = runCatching { clientReady.await() }.getOrNull()
            ?: return ServiceStatus(id, connected = false, ready = false)
        val linked = withContext(Dispatchers.IO) { client.isLinked() }
        val account = JSONObject(withContext(Dispatchers.IO) { client.accountJson() })
        return ServiceStatus(
            id,
            connected = linked,
            ready = account.optBoolean("online"),
            accountLabel = account.optString("phone").ifBlank { null },
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
        if (bridge { isLinked() }) return complete()
        linkEvents.close()
        linkEvents = Channel(Channel.UNLIMITED)
        bridge { startLink() }
        return nextLinkStep()
    }

    override suspend fun advanceSetup(step: SetupStep, value: String?): SetupStep = when (step) {
        is SetupStep.Qr -> nextLinkStep()
        else -> step
    }

    private suspend fun nextLinkStep(): SetupStep {
        val (kind, payload) = withTimeout(LINK_TIMEOUT_MILLIS) { linkEvents.receive() }
        return when (kind) {
            "qr" -> SetupStep.Qr(
                "qr", "Scan QR code",
                "On your main phone open Signal → Settings → Linked devices → Link new device, then scan this code. " +
                    "Signal only links by scanning, so if Signal is on this phone, show this code on another screen.",
                payload.getString("code"),
            )
            "paired" -> complete()
            else -> {
                val error = payload.optString("error", "Linking failed")
                // The link connection lapses if nobody scans for a while: start again.
                if ("no provisioning message" in error) restartLink() else throw SignalException(error)
            }
        }
    }

    private suspend fun restartLink(): SetupStep {
        linkEvents.close()
        linkEvents = Channel(Channel.UNLIMITED)
        bridge { startLink() }
        return nextLinkStep()
    }

    private fun complete() = SetupStep.Complete(
        "Signal connected",
        "Signal doesn't share old messages with new devices, so chats start from now. Contacts sync over the next minute.",
    )

    override suspend fun disconnect() {
        bridge { logout() }
    }

    // endregion

    // region Conversations and messages

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
        bridge { react(conversation.remoteId, message.id, emoji, remove) }
    }

    override suspend fun editMessage(conversation: Conversation, message: Message, text: String) {
        bridge { edit(conversation.remoteId, message.id, text) }
    }

    override suspend fun deleteMessage(conversation: Conversation, message: Message) {
        bridge { delete(conversation.remoteId, message.id) }
    }

    override suspend fun updateConversation(conversation: Conversation, update: ConversationUpdate) {
        update.archived?.let { value -> bridge { setArchived(conversation.remoteId, value) } }
        update.favourite?.let { value -> bridge { setPinned(conversation.remoteId, value) } }
        update.muted?.let { value -> bridge { setMuted(conversation.remoteId, value) } }
    }

    override suspend fun searchMessages(query: String, conversation: Conversation?): List<SearchResult> =
        json.searchResults(bridge { searchJson(query) }, conversation)

    override suspend fun createDirect(address: String, title: String?): Conversation {
        val chat = bridge { resolvePhone(address) }
        return json.conversation(bridge { chatJson(chat) })
    }

    // endregion

    private companion object {
        const val TAG = "Signal"
        const val PAGE = 50L
        const val LINK_TIMEOUT_MILLIS = 5 * 60_000L
    }
}
