package dev.dumbtalk.core.demo

import dev.dumbtalk.core.Capabilities
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.ConversationKind
import dev.dumbtalk.core.ConversationPage
import dev.dumbtalk.core.ConversationUpdate
import dev.dumbtalk.core.Direction
import dev.dumbtalk.core.Member
import dev.dumbtalk.core.Message
import dev.dumbtalk.core.MessagePage
import dev.dumbtalk.core.MessagingService
import dev.dumbtalk.core.Reaction
import dev.dumbtalk.core.Receipt
import dev.dumbtalk.core.SearchResult
import dev.dumbtalk.core.ServiceEvent
import dev.dumbtalk.core.ServiceId
import dev.dumbtalk.core.ServiceStatus
import dev.dumbtalk.core.SetupStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory stand-in for a real network, used by debug builds and tests.
 * Setup steps mimic each real service so the setup screens get exercised.
 */
class DemoService(
    override val id: ServiceId,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val replyDelayMillis: Long = 2_500,
) : MessagingService {
    private val lock = Mutex()
    private val mutableEvents = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 1024)
    override val events: SharedFlow<ServiceEvent> = mutableEvents

    private var connected = true
    private val conversations = linkedMapOf<String, Conversation>()
    private val messages = mutableMapOf<String, MutableList<Message>>()
    private var nextId = 1

    private val account = when (id) {
        ServiceId.SIGNAL -> "+15550100000"
        ServiceId.TELEGRAM -> "Demo User"
        ServiceId.WHATSAPP -> "+15550100999"
    }

    init {
        seed()
    }

    override suspend fun status() = ServiceStatus(id, connected = connected, ready = true, accountLabel = account.takeIf { connected })

    override suspend fun capabilities() = Capabilities(
        reactions = true,
        edits = true,
        deletes = true,
        pins = id != ServiceId.SIGNAL,
        polls = true,
        voiceNotes = true,
        groups = true,
        identities = id == ServiceId.SIGNAL,
        search = true,
        compose = true,
        attachments = true,
        forwarding = true,
        muting = true,
        disappearingMessages = id != ServiceId.TELEGRAM,
        disappearingDurations = listOf(0, 86_400, 604_800),
    )

    override suspend fun beginSetup(): SetupStep = when (id) {
        ServiceId.SIGNAL -> qrStep()
        ServiceId.TELEGRAM -> SetupStep.Input("api-id", "Telegram API ID", "Create an app at my.telegram.org and enter its API ID.", SetupStep.Input.Field.API_ID, placeholder = "12345678")
        ServiceId.WHATSAPP -> methodChoice()
    }

    override suspend fun advanceSetup(step: SetupStep, value: String?): SetupStep {
        // Code steps wait as if for a scan; the others act like a quick request.
        delay(if (step is SetupStep.Qr || step is SetupStep.PairCode) 6_000 else 300)
        return when (step) {
            is SetupStep.Input -> when (step.field) {
                SetupStep.Input.Field.API_ID -> SetupStep.Input("api-hash", "Telegram API hash", "Enter the 32-character API hash.", SetupStep.Input.Field.API_HASH)
                SetupStep.Input.Field.API_HASH -> methodChoice()
                SetupStep.Input.Field.PHONE -> if (id == ServiceId.WHATSAPP) {
                    SetupStep.PairCode("pair", "Pairing code", "In WhatsApp open Linked devices → Link with phone number, then enter this code.", "DEMO-1234", value.orEmpty())
                } else {
                    SetupStep.Input("code", "Login code", "Enter the code Telegram sent you.", SetupStep.Input.Field.CODE)
                }
                else -> connect()
            }
            is SetupStep.Choice -> if (value == "phone") {
                SetupStep.Input("phone", "Phone number", "Enter the account's number with country code.", SetupStep.Input.Field.PHONE, placeholder = "+44…")
            } else {
                qrStep()
            }
            is SetupStep.Qr, is SetupStep.PairCode -> connect()
            is SetupStep.Complete -> step
        }
    }

    private fun methodChoice() = SetupStep.Choice(
        "method", "Link ${id.label}", "Choose how to link this device.",
        listOf(
            SetupStep.Choice.Option("qr", "QR code", "Scan from your main phone"),
            SetupStep.Choice.Option("phone", "Phone number", "Enter a code instead"),
        ),
    )

    private fun qrStep() = SetupStep.Qr(
        "qr", "Scan QR code",
        "Open ${id.label} on your main phone → Linked devices, then scan this code.",
        "dumbtalk-demo://${id.key}/${clock()}",
    )

    private suspend fun connect(): SetupStep {
        lock.withLock {
            connected = true
            seed()
        }
        mutableEvents.emit(ServiceEvent.StatusChanged(id))
        mutableEvents.emit(ServiceEvent.ConversationsChanged(id))
        return SetupStep.Complete("${id.label} connected", "Your ${id.label} chats now appear in the inbox.")
    }

    override suspend fun disconnect() {
        lock.withLock {
            connected = false
            conversations.clear()
            messages.clear()
        }
        mutableEvents.emit(ServiceEvent.StatusChanged(id))
        mutableEvents.emit(ServiceEvent.ConversationsChanged(id))
    }

    override suspend fun listConversations(archived: Boolean): ConversationPage = lock.withLock {
        val all = conversations.values.map { it.copy(lastMessage = messages[it.id]?.lastOrNull()) }
        ConversationPage(all.filter { it.isArchived == archived }, all.count { it.isArchived })
    }

    override suspend fun listMessages(conversation: Conversation, before: Message?): MessagePage = lock.withLock {
        val all = messages[conversation.id].orEmpty().filter { before == null || it.sentAt < before.sentAt }
        MessagePage(
            messages = all.takeLast(PAGE),
            hasMore = all.size > PAGE,
            typingNames = conversations[conversation.id]?.typingNames.orEmpty(),
        )
    }

    override suspend fun markRead(conversation: Conversation) {
        update(conversation.id) { it.copy(unreadCount = 0) }
    }

    override suspend fun sendText(conversation: Conversation, text: String, replyTo: Message?): Message {
        val message = lock.withLock {
            Message(
                id = "${id.key}-${nextId++}",
                conversationId = conversation.id,
                sentAt = clock(),
                direction = Direction.OUTGOING,
                sender = "You",
                text = text,
                receipt = Receipt(Receipt.State.SENT),
                quote = replyTo?.let { dev.dumbtalk.core.Quote(it.sender ?: conversation.title, it.text, it.sentAt) },
            ).also { messages.getOrPut(conversation.id) { mutableListOf() } += it }
        }
        mutableEvents.emit(ServiceEvent.MessagesChanged(id, conversation.id))
        mutableEvents.emit(ServiceEvent.ConversationsChanged(id))
        scope.launch { simulateReply(conversation, message) }
        return message
    }

    private suspend fun simulateReply(conversation: Conversation, sent: Message) {
        delay(replyDelayMillis / 3)
        editMessage(conversation.id, sent.id) { it.copy(receipt = Receipt(Receipt.State.DELIVERED)) }
        val responder = conversation.members.firstOrNull()?.name ?: conversation.title
        update(conversation.id) { it.copy(typingNames = listOf(responder)) }
        delay(replyDelayMillis)
        editMessage(conversation.id, sent.id) { it.copy(receipt = Receipt(Receipt.State.READ)) }
        val reply = lock.withLock {
            conversations[conversation.id] = conversations.getValue(conversation.id).let {
                it.copy(typingNames = emptyList(), unreadCount = it.unreadCount + 1)
            }
            Message(
                id = "${id.key}-${nextId++}",
                conversationId = conversation.id,
                sentAt = clock(),
                direction = Direction.INCOMING,
                sender = responder,
                text = REPLIES[nextId % REPLIES.size],
            ).also { messages.getOrPut(conversation.id) { mutableListOf() } += it }
        }
        mutableEvents.emit(ServiceEvent.MessagesChanged(id, conversation.id))
        mutableEvents.emit(ServiceEvent.ConversationsChanged(id))
        mutableEvents.emit(ServiceEvent.IncomingMessage(id, conversations.getValue(conversation.id), reply))
    }

    override suspend fun react(conversation: Conversation, message: Message, emoji: String, remove: Boolean) {
        editMessage(conversation.id, message.id) { current ->
            val others = current.reactions.filterNot { it.isOwn }
            current.copy(reactions = if (remove) others else others + Reaction(emoji, "You", isOwn = true))
        }
    }

    override suspend fun editMessage(conversation: Conversation, message: Message, text: String) {
        editMessage(conversation.id, message.id) { it.copy(text = text, edited = true) }
    }

    override suspend fun deleteMessage(conversation: Conversation, message: Message) {
        editMessage(conversation.id, message.id) { it.copy(text = null, attachments = emptyList(), deleted = true) }
    }

    override suspend fun pinMessage(conversation: Conversation, message: Message, pinned: Boolean) {
        editMessage(conversation.id, message.id) { it.copy(pinned = pinned) }
    }

    override suspend fun updateConversation(conversation: Conversation, update: ConversationUpdate) {
        update(conversation.id) {
            it.copy(
                isArchived = update.archived ?: it.isArchived,
                isFavourite = update.favourite ?: it.isFavourite,
                isMuted = update.muted ?: it.isMuted,
                expiration = update.expiration ?: it.expiration,
            )
        }
    }

    override suspend fun searchMessages(query: String, conversation: Conversation?): List<SearchResult> = lock.withLock {
        messages.values.flatten()
            .filter { conversation == null || it.conversationId == conversation.id }
            .filter { it.text?.contains(query, ignoreCase = true) == true }
            .map { SearchResult(it.id, it.conversationId, it.sender ?: "", it.text.orEmpty(), it.sentAt) }
    }

    override suspend fun createDirect(address: String, title: String?): Conversation = lock.withLock {
        val conversation = Conversation(
            id = "${id.key}:$address",
            serviceId = id,
            remoteId = address,
            kind = ConversationKind.DIRECT,
            title = title ?: address,
        )
        conversations.getOrPut(conversation.id) { conversation }
    }

    private suspend fun update(conversationId: String, change: (Conversation) -> Conversation) {
        lock.withLock { conversations[conversationId]?.let { conversations[conversationId] = change(it) } }
        mutableEvents.emit(ServiceEvent.ConversationsChanged(id))
        mutableEvents.emit(ServiceEvent.MessagesChanged(id, conversationId))
    }

    private suspend fun editMessage(conversationId: String, messageId: String, change: (Message) -> Message) {
        lock.withLock {
            val list = messages[conversationId] ?: return@withLock
            val index = list.indexOfFirst { it.id == messageId }
            if (index >= 0) list[index] = change(list[index])
        }
        mutableEvents.emit(ServiceEvent.MessagesChanged(id, conversationId))
        mutableEvents.emit(ServiceEvent.ConversationsChanged(id))
    }

    private fun seed() {
        conversations.clear()
        messages.clear()
        val now = clock()
        fun minutesAgo(minutes: Int) = now - minutes * 60_000L
        fun chat(remote: String, title: String, kind: ConversationKind, unread: Int = 0, favourite: Boolean = false, muted: Boolean = false, members: List<String> = emptyList(), vararg lines: Triple<Int, String?, String>) {
            val conversationId = "${id.key}:$remote"
            conversations[conversationId] = Conversation(
                id = conversationId,
                serviceId = id,
                remoteId = remote,
                kind = kind,
                title = title,
                unreadCount = unread,
                isFavourite = favourite,
                isMuted = muted,
                members = members.mapIndexed { index, name -> Member("$remote-$index", name) },
            )
            messages[conversationId] = lines.map { (age, sender, text) ->
                Message(
                    id = "${id.key}-${nextId++}",
                    conversationId = conversationId,
                    sentAt = minutesAgo(age),
                    direction = if (sender == null) Direction.OUTGOING else Direction.INCOMING,
                    sender = sender ?: "You",
                    text = text,
                    receipt = if (sender == null) Receipt(Receipt.State.READ) else null,
                )
            }.toMutableList()
        }
        when (id) {
            ServiceId.SIGNAL -> {
                chat("maya", "Maya Chen", ConversationKind.DIRECT, unread = 2, favourite = true, lines = arrayOf(
                    Triple(40, "Maya Chen", "Are we still on for tomorrow?"),
                    Triple(38, null, "Yes! 10am?"),
                    Triple(12, "Maya Chen", "Perfect! Meet at the station at 10."),
                ))
                chat("hikers", "Sunday Hikers", ConversationKind.GROUP, members = listOf("Alex", "Sam", "Priya"), lines = arrayOf(
                    Triple(300, "Alex", "Weather looks good for Sunday"),
                    Triple(290, "Priya", "Bringing snacks"),
                    Triple(280, null, "See everyone at 8!"),
                ))
            }
            ServiceId.TELEGRAM -> {
                chat("indie", "Indie Dev Chat", ConversationKind.GROUP, unread = 4, muted = true, members = listOf("Noor", "Ben"), lines = arrayOf(
                    Triple(90, "Ben", "Shipped the keypad build"),
                    Triple(30, "Noor", "Would love to see a video of it working on a real phone"),
                ))
                chat("jamie", "Jamie Patel", ConversationKind.DIRECT, unread = 1, lines = arrayOf(
                    Triple(120, null, "Got the draft?"),
                    Triple(60, "Jamie Patel", "Nice! Send me the link when it's live"),
                ))
            }
            ServiceId.WHATSAPP -> {
                chat("family", "Family", ConversationKind.GROUP, unread = 3, favourite = true, members = listOf("Chris", "Mum"), lines = arrayOf(
                    Triple(25, "Chris", "Lunch at ours on Sunday?"),
                    Triple(21, "Mum", "Works for me. I'll bring dessert."),
                    Triple(15, null, "Count me in!"),
                ))
                chat("mum", "Mum", ConversationKind.DIRECT, lines = arrayOf(
                    Triple(500, null, "How are you?"),
                    Triple(480, "Mum", "Good 😊"),
                ))
            }
        }
    }

    private companion object {
        const val PAGE = 60
        val REPLIES = listOf("Sounds good!", "👍", "Haha, yes", "On my way", "Talk later?")
    }
}
