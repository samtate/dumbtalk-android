package dev.dumbtalk.whatsapp

import android.util.Log
import dev.dumbtalk.core.Attachment
import dev.dumbtalk.core.AttachmentKind
import dev.dumbtalk.core.Capabilities
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.ConversationKind
import dev.dumbtalk.core.ConversationPage
import dev.dumbtalk.core.ConversationUpdate
import dev.dumbtalk.core.Direction
import dev.dumbtalk.core.Message
import dev.dumbtalk.core.MessagePage
import dev.dumbtalk.core.MessagingService
import dev.dumbtalk.core.Poll
import dev.dumbtalk.core.Quote
import dev.dumbtalk.core.Reaction
import dev.dumbtalk.core.Receipt
import dev.dumbtalk.core.SearchResult
import dev.dumbtalk.core.ServiceEvent
import dev.dumbtalk.core.ServiceId
import dev.dumbtalk.core.ServiceStatus
import dev.dumbtalk.core.SetupStep
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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

    private val mutableEvents = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<ServiceEvent> = mutableEvents

    private val clientReady = CompletableDeferred<Client>()
    private var linkEvents = Channel<Pair<String, JSONObject>>(Channel.UNLIMITED)
    /** chat JID → typing name → when the notice lapses (ms). */
    private val typing = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

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
                if (chat.isNotEmpty() && chat != "*") mutableEvents.tryEmit(ServiceEvent.MessagesChanged(id, conversationId(chat)))
            }
            "incoming" -> scope.launch { announceIncoming(payload.getString("chat"), payload.getString("id")) }
            "typing" -> onTyping(payload)
            "qr", "pair-code", "paired", "link-error" -> linkEvents.trySend(kind to payload)
            "log" -> Log.w(TAG, payload.optString("message"))
        }
    }

    private suspend fun announceIncoming(chat: String, messageId: String) {
        val conversation = runCatching { conversation(JSONObject(bridge { chatJSON(chat) })) }.getOrNull() ?: return
        val message = conversation.lastMessage?.takeIf { it.id == messageId } ?: return
        mutableEvents.emit(ServiceEvent.IncomingMessage(id, conversation, message))
    }

    private fun onTyping(payload: JSONObject) {
        val chat = payload.getString("chat")
        val names = typing.getOrPut(chat) { ConcurrentHashMap() }
        val name = payload.optString("sender")
        if (payload.optBoolean("active")) names[name] = System.currentTimeMillis() + TYPING_MILLIS else names.remove(name)
        mutableEvents.tryEmit(ServiceEvent.ConversationsChanged(id))
        mutableEvents.tryEmit(ServiceEvent.MessagesChanged(id, conversationId(chat)))
        scope.launch {
            delay(TYPING_MILLIS + 100)
            mutableEvents.emit(ServiceEvent.ConversationsChanged(id))
        }
    }

    private fun typingNames(chat: String): List<String> {
        val now = System.currentTimeMillis()
        return typing[chat].orEmpty().filterValues { it > now }.keys.toList()
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

    // region Mapping

    private fun conversationId(jid: String) = "${id.key}:$jid"

    private fun conversation(chat: JSONObject): Conversation {
        val jid = chat.getString("jid")
        return Conversation(
            id = conversationId(jid),
            serviceId = id,
            remoteId = jid,
            kind = if (chat.optBoolean("group")) ConversationKind.GROUP else ConversationKind.DIRECT,
            title = chat.optString("name").ifBlank { jid.substringBefore('@') },
            isNoteToSelf = chat.optBoolean("noteToSelf"),
            isArchived = chat.optBoolean("archived"),
            isFavourite = chat.optBoolean("pinned"),
            isMuted = chat.optBoolean("muted"),
            unreadCount = chat.optInt("unread"),
            typingNames = typingNames(jid),
            lastMessage = chat.optJSONObject("last")?.let(::message),
        )
    }

    private fun message(json: JSONObject): Message {
        val kind = json.optString("kind")
        val fromMe = json.optBoolean("fromMe")
        val text = json.optString("text").ifBlank { null }
        val attachmentKind = when (kind) {
            "image" -> AttachmentKind.IMAGE
            "video" -> AttachmentKind.VIDEO
            "audio" -> AttachmentKind.AUDIO
            "document" -> AttachmentKind.FILE
            else -> null
        }
        return Message(
            id = json.getString("id"),
            conversationId = conversationId(json.getString("chat")),
            sentAt = json.getLong("ts"),
            direction = when {
                kind == "system" -> Direction.SYSTEM
                fromMe -> Direction.OUTGOING
                else -> Direction.INCOMING
            },
            sender = if (fromMe) "You" else json.optString("senderName").ifBlank { null },
            text = if (kind == "sticker") "Sticker" else text,
            attachments = listOfNotNull(attachmentKind?.let {
                Attachment(
                    id = json.getString("id"),
                    kind = it,
                    contentType = json.optString("mime").ifBlank { null },
                    filename = json.optString("filename").ifBlank { null },
                    size = json.optLong("size").takeIf { size -> size > 0 },
                )
            }),
            reactions = json.optJSONArray("reactions").objects().map {
                Reaction(it.getString("emoji"), if (it.optBoolean("fromMe")) "You" else it.optString("name"), it.optBoolean("fromMe"))
            },
            receipt = if (fromMe) receipt(json.optString("status")) else null,
            quote = json.optString("quoteId").takeIf { it.isNotEmpty() }?.let {
                Quote(json.optString("quoteSender").ifBlank { "Message" }, json.optString("quoteText").ifBlank { null })
            },
            edited = json.optBoolean("edited"),
            deleted = json.optBoolean("deleted"),
            poll = json.optString("poll").takeIf { it.isNotEmpty() }?.let(::poll),
            forwardedFrom = if (json.optBoolean("forwarded")) "another chat" else null,
        )
    }

    private fun receipt(status: String) = when (status) {
        "sent" -> Receipt(Receipt.State.SENT)
        "delivered" -> Receipt(Receipt.State.DELIVERED)
        "read" -> Receipt(Receipt.State.READ)
        else -> null // pending
    }

    private fun poll(encoded: String): Poll {
        val json = JSONObject(encoded)
        val options = json.optJSONArray("options") ?: JSONArray()
        return Poll(
            question = json.optString("question"),
            options = (0 until options.length()).map { Poll.Option(it, options.getString(it), emptyList()) },
            multiple = json.optBoolean("multiple"),
            closed = false,
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    // endregion

    // region Conversations and messages

    override suspend fun listConversations(archived: Boolean): ConversationPage {
        if (!bridge { isLinked }) return ConversationPage(emptyList(), 0)
        val page = JSONObject(bridge { chatsJSON(archived) })
        return ConversationPage(
            conversations = page.optJSONArray("chats").objects().map(::conversation),
            archivedCount = page.optInt("archivedCount"),
        )
    }

    override suspend fun listMessages(conversation: Conversation, before: Message?): MessagePage {
        val messages = JSONArray(bridge { messagesJSON(conversation.remoteId, before?.sentAt ?: 0L, before?.id.orEmpty(), PAGE.toLong()) })
            .objects().map(::message)
        return MessagePage(messages, hasMore = messages.size >= PAGE, typingNames = typingNames(conversation.remoteId))
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
        val sent = message(JSONObject(bridge { sendText(conversation.remoteId, text, replyTo?.id.orEmpty()) }))
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
        JSONArray(bridge { searchJSON(query) }).objects()
            .map(::message)
            .filter { conversation == null || it.conversationId == conversation.id }
            .map { SearchResult(it.id, it.conversationId, it.sender.orEmpty(), it.text.orEmpty(), it.sentAt) }

    override suspend fun createDirect(address: String, title: String?): Conversation {
        val jid = bridge { resolvePhone(address) }
        return conversation(JSONObject(bridge { chatJSON(jid) }))
    }

    // endregion

    private companion object {
        const val TAG = "WhatsApp"
        const val PAGE = 50
        const val TYPING_MILLIS = 6_000L
        const val LINK_TIMEOUT_MILLIS = 5 * 60_000L
    }
}
