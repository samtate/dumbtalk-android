package dev.dumbtalk.core

import kotlinx.coroutines.flow.Flow

// Universal messaging types. The UI only uses these; each service maps its
// own network objects into them and keeps network-specific ids internally.
// Ported from dumbtalk's src/client/services/contracts.ts.

enum class ServiceId(val key: String, val label: String) {
    SIGNAL("signal", "Signal"),
    TELEGRAM("telegram", "Telegram"),
    WHATSAPP("whatsapp", "WhatsApp");

    companion object {
        fun fromKey(key: String): ServiceId? = entries.firstOrNull { it.key == key }
    }
}

data class ServiceStatus(
    val id: ServiceId,
    val connected: Boolean,
    val ready: Boolean,
    val accountLabel: String? = null,
)

sealed interface SetupStep {
    val title: String
    val instructions: String

    data class Choice(
        val token: String,
        override val title: String,
        override val instructions: String,
        val choices: List<Option>,
    ) : SetupStep {
        data class Option(val value: String, val label: String, val description: String? = null)
    }

    /** [payload] is the text to encode as a QR code; the UI renders it. */
    data class Qr(
        val token: String,
        override val title: String,
        override val instructions: String,
        val payload: String,
    ) : SetupStep

    data class PairCode(
        val token: String,
        override val title: String,
        override val instructions: String,
        val code: String,
        val phone: String,
    ) : SetupStep

    data class Input(
        val token: String,
        override val title: String,
        override val instructions: String,
        val field: Field,
        val placeholder: String? = null,
        val hint: String? = null,
    ) : SetupStep {
        enum class Field { PHONE, CODE, PASSWORD, API_ID, API_HASH }
    }

    data class Complete(
        override val title: String,
        override val instructions: String,
    ) : SetupStep
}

enum class AttachmentKind { IMAGE, VIDEO, AUDIO, FILE }

data class Attachment(
    val id: String,
    val kind: AttachmentKind,
    /** Local file path once downloaded; null until then. */
    val localPath: String? = null,
    val contentType: String? = null,
    val filename: String? = null,
    val size: Long? = null,
    val caption: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

data class Quote(val author: String, val text: String? = null, val sentAt: Long? = null)

data class LinkPreview(val title: String, val description: String? = null, val url: String? = null)

data class Poll(
    val question: String,
    val options: List<Option>,
    val multiple: Boolean,
    val closed: Boolean,
) {
    data class Option(val index: Int, val text: String, val votes: List<String>)
}

data class Capabilities(
    val reactions: Boolean = false,
    val edits: Boolean = false,
    val deletes: Boolean = false,
    val pins: Boolean = false,
    val polls: Boolean = false,
    val voiceNotes: Boolean = false,
    val viewOnce: Boolean = false,
    val groups: Boolean = false,
    val identities: Boolean = false,
    val blocking: Boolean = false,
    val messageRequests: Boolean = false,
    val disappearingMessages: Boolean = false,
    val search: Boolean = false,
    val compose: Boolean = false,
    val settings: Boolean = false,
    val attachments: Boolean = false,
    val forwarding: Boolean = false,
    val stickers: Boolean = false,
    val muting: Boolean = false,
    val calls: Boolean = false,
    val disappearingDurations: List<Int> = emptyList(),
)

data class ServiceSettings(
    val sendReadReceipts: Boolean = true,
    val sendTypingIndicators: Boolean = true,
    val linkPreviews: Boolean = true,
    val defaultExpiration: Int = 0,
)

data class SearchResult(
    val id: String,
    val conversationId: String,
    val sender: String,
    val text: String,
    val sentAt: Long,
)

data class Sticker(
    val id: String,
    val packId: String,
    val stickerId: String,
    val emoji: String? = null,
    val packTitle: String? = null,
    val localPath: String,
)

data class Receipt(
    val state: State,
    val updatedAt: Long? = null,
    val readBy: List<Reader> = emptyList(),
) {
    enum class State { SENT, DELIVERED, READ }
    data class Reader(val name: String, val status: Status, val at: Long? = null) {
        enum class Status { DELIVERED, READ, VIEWED }
    }
}

data class Reaction(val emoji: String, val author: String, val isOwn: Boolean)

enum class Direction { INCOMING, OUTGOING, SYSTEM }

data class Message(
    val id: String,
    val conversationId: String,
    val sentAt: Long,
    val direction: Direction,
    val sender: String? = null,
    val text: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val reactions: List<Reaction> = emptyList(),
    val receipt: Receipt? = null,
    val quote: Quote? = null,
    val edited: Boolean = false,
    val deleted: Boolean = false,
    val pinned: Boolean = false,
    val poll: Poll? = null,
    val viewOnceOpened: Boolean? = null,
    val previews: List<LinkPreview> = emptyList(),
    val stickerPath: String? = null,
    val forwardedFrom: String? = null,
)

data class Member(val id: String, val name: String)

enum class ConversationKind { DIRECT, GROUP }

data class Conversation(
    /** Globally unique: "<service key>:<remote id>". */
    val id: String,
    val serviceId: ServiceId,
    val remoteId: String,
    val kind: ConversationKind,
    val title: String,
    val isNoteToSelf: Boolean = false,
    val isArchived: Boolean = false,
    val isFavourite: Boolean = false,
    val isMuted: Boolean = false,
    val unreadCount: Int = 0,
    val typingNames: List<String> = emptyList(),
    val avatarPath: String? = null,
    val lastMessage: Message? = null,
    val expiration: Int = 0,
    val isBlocked: Boolean = false,
    val isMessageRequest: Boolean = false,
    val isIdentityChanged: Boolean = false,
    val isInvited: Boolean = false,
    val description: String? = null,
    val members: List<Member> = emptyList(),
    val adminIds: List<String> = emptyList(),
    val inviteLink: String? = null,
    val permissions: Map<String, String> = emptyMap(),
)

data class ConversationPage(val conversations: List<Conversation>, val archivedCount: Int)

data class MessagePage(
    val messages: List<Message>,
    val hasMore: Boolean,
    val readThrough: Long = 0,
    val typingNames: List<String> = emptyList(),
    val allowedReactions: List<String>? = null,
)

data class ConversationUpdate(
    val archived: Boolean? = null,
    val favourite: Boolean? = null,
    val muted: Boolean? = null,
    val expiration: Int? = null,
)

/** Pushed by a service so screens refresh without polling. */
sealed interface ServiceEvent {
    val serviceId: ServiceId

    data class StatusChanged(override val serviceId: ServiceId) : ServiceEvent
    data class ConversationsChanged(override val serviceId: ServiceId) : ServiceEvent
    data class MessagesChanged(override val serviceId: ServiceId, val conversationId: String) : ServiceEvent
    data class IncomingMessage(
        override val serviceId: ServiceId,
        val conversation: Conversation,
        val message: Message,
    ) : ServiceEvent
}

class UnsupportedOperation(operation: String, service: ServiceId) :
    UnsupportedOperationException("${service.label} does not support $operation")

/**
 * One messaging network running on the device. Optional operations default
 * to throwing [UnsupportedOperation]; the matching [Capabilities] flag hides
 * them from menus so the UI never calls them.
 */
interface MessagingService {
    val id: ServiceId
    val label: String get() = id.label
    val events: Flow<ServiceEvent>

    suspend fun status(): ServiceStatus
    suspend fun capabilities(): Capabilities

    suspend fun beginSetup(): SetupStep

    /**
     * Moves setup on with the user's [value] for an input or choice step. For
     * a [SetupStep.Qr] or [SetupStep.PairCode] step the UI calls this straight
     * away with no value; it suspends until the main phone links (returning
     * the next step) or the code expires (returning a fresh code step).
     */
    suspend fun advanceSetup(step: SetupStep, value: String? = null): SetupStep
    suspend fun disconnect()

    suspend fun listConversations(archived: Boolean): ConversationPage
    /** Newest page when [before] is null, else the page older than [before]. */
    suspend fun listMessages(conversation: Conversation, before: Message? = null): MessagePage
    suspend fun markRead(conversation: Conversation)

    /** Called while a chat is on screen; networks use it for live updates and read state. */
    suspend fun openConversation(conversation: Conversation) {}
    suspend fun closeConversation(conversation: Conversation) {}
    suspend fun sendText(conversation: Conversation, text: String, replyTo: Message? = null): Message
    suspend fun setTyping(conversation: Conversation, active: Boolean) {}
    suspend fun messageDetails(conversation: Conversation, message: Message): Message = message

    suspend fun react(conversation: Conversation, message: Message, emoji: String, remove: Boolean = false): Unit =
        unsupported("reactions")
    suspend fun updateConversation(conversation: Conversation, update: ConversationUpdate): Unit =
        unsupported("conversation changes")
    suspend fun searchMessages(query: String, conversation: Conversation? = null): List<SearchResult> =
        unsupported("search")
    /** Finds or starts a direct chat with a phone number or username. */
    suspend fun createDirect(address: String, title: String? = null): Conversation = unsupported("new messages")
    suspend fun createGroup(name: String, members: List<String>): Unit = unsupported("groups")
    suspend fun settings(): ServiceSettings = ServiceSettings()
    suspend fun updateSettings(settings: ServiceSettings): ServiceSettings = unsupported("settings")
    suspend fun sendAttachment(conversation: Conversation, path: String, mimeType: String, caption: String? = null): Unit =
        unsupported("attachments")
    suspend fun forwardMessage(message: Message, target: Conversation): Unit = unsupported("forwarding")
    suspend fun listStickers(): List<Sticker> = emptyList()
    suspend fun sendSticker(conversation: Conversation, sticker: Sticker): Unit = unsupported("stickers")
    suspend fun editMessage(conversation: Conversation, message: Message, text: String): Unit = unsupported("edits")
    suspend fun deleteMessage(conversation: Conversation, message: Message): Unit = unsupported("deletes")
    suspend fun pinMessage(conversation: Conversation, message: Message, pinned: Boolean): Unit = unsupported("pins")
    suspend fun listPinnedMessages(conversation: Conversation): List<Message> = emptyList()
    suspend fun sendVoiceNote(conversation: Conversation, path: String): Unit = unsupported("voice notes")
    suspend fun createPoll(conversation: Conversation, question: String, options: List<String>, multiple: Boolean): Unit =
        unsupported("polls")
    suspend fun votePoll(conversation: Conversation, message: Message, options: List<Int>): Unit = unsupported("polls")
    suspend fun closePoll(conversation: Conversation, message: Message): Unit = unsupported("polls")
    suspend fun setBlocked(conversation: Conversation, blocked: Boolean): Unit = unsupported("blocking")
    suspend fun respondToMessageRequest(conversation: Conversation, accept: Boolean): Unit =
        unsupported("message requests")
    suspend fun updateGroup(conversation: Conversation, changes: Map<String, Any?>): Unit = unsupported("groups")
    suspend fun leaveGroup(conversation: Conversation): Unit = unsupported("groups")
    suspend fun openViewOnce(message: Message): String = unsupported("view-once media")
    suspend fun safetyNumber(conversation: Conversation): String = unsupported("safety numbers")
    suspend fun trustSafetyNumber(conversation: Conversation, safetyNumber: String): Unit =
        unsupported("safety numbers")

    private fun unsupported(operation: String): Nothing = throw UnsupportedOperation(operation, id)
}
