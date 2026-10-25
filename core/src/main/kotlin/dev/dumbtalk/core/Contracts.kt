package dev.dumbtalk.core

import kotlinx.coroutines.flow.Flow

// Universal messaging types. The UI only uses these; each service maps its
// own network objects into them and keeps network-specific ids internally.
// Ported from dumbtalk's src/client/services/contracts.ts.

enum class ServiceId(val key: String, val label: String) {
    SIGNAL("signal", "Signal"),
    TELEGRAM("telegram", "Telegram"),
    WHATSAPP("whatsapp", "WhatsApp"),
    SMS("sms", "SMS");

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
    /** A sticker: shown as a bare image, summarised as "Sticker". */
    val sticker: Boolean = false,
    val durationMillis: Long? = null,
    /** Normalised amplitude samples, when the service supplies them. */
    val waveform: List<Int> = emptyList(),
    val thumbnailId: String? = null,
    val thumbnailBase64: String? = null,
)

/** [messageId] is the quoted message's id (same form as [Message.id]), so the UI can jump to it. */
data class OutgoingAttachment(val path: String, val mimeType: String)

data class Quote(val author: String, val text: String? = null, val sentAt: Long? = null, val messageId: String? = null)

/**
 * Styling or a mention over part of a message's text. [start] and [length]
 * count UTF-16 units, as Kotlin strings do. For a mention, [target] is the
 * mentioned [Member.id] when known.
 */
data class TextSpan(val start: Int, val length: Int, val style: Style, val target: String? = null) {
    enum class Style { BOLD, ITALIC, STRIKE, MONO, SPOILER, UNDERLINE, MENTION }

    val end get() = start + length
}

data class LinkPreview(val title: String, val description: String? = null, val url: String? = null)

data class Poll(
    val question: String,
    val options: List<Option>,
    val multiple: Boolean,
    val closed: Boolean,
) {
    /** [votes] names each voter where known (blank when only counted); [mine] is our own choice. */
    data class Option(val index: Int, val text: String, val votes: List<String>, val mine: Boolean = false)
}

data class Capabilities(
    val replies: Boolean = true,
    val retries: Boolean = false,
    val deleteConversations: Boolean = false,
    val reactions: Boolean = false,
    val edits: Boolean = false,
    val deletes: Boolean = false,
    val pins: Boolean = false,
    val polls: Boolean = false,
    /** Polls can go to one-to-one chats, not only groups. */
    val directPolls: Boolean = false,
    /** A poll's sender can close it to further votes. */
    val closingPolls: Boolean = false,
    /** Sent text can @mention chat members, who get notified. */
    val mentions: Boolean = false,
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
    /** Disappearing-message timers on offer, in seconds (Off is always offered). */
    val disappearingDurations: List<Int> = emptyList(),
    /** What recorded voice notes should be: "audio/ogg" (Opus) or "audio/aac". */
    val voiceNoteMime: String = "audio/ogg",
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
    /** FAILED: the service gave up sending it (shown as a red !). */
    enum class State { SENT, DELIVERED, READ, FAILED }
    /** One recipient of our message and when it reached them (ms); null for a step not reported. */
    data class Reader(val name: String, val deliveredAt: Long? = null, val readAt: Long? = null, val playedAt: Long? = null)
}

/**
 * Whether someone is online, or when they last were ([lastSeen], ms). Some
 * networks only say roughly when ([approximately]), or nothing at all.
 */
data class Presence(val online: Boolean, val lastSeen: Long? = null, val approximately: Approximately? = null) {
    enum class Approximately { RECENTLY, WITHIN_A_WEEK, WITHIN_A_MONTH }
}

data class Reaction(val emoji: String, val author: String, val isOwn: Boolean, val authorId: String? = null, val avatarPath: String? = null)

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
    /** Bold, italic, spoilers, mentions and so on over [text]. */
    val spans: List<TextSpan> = emptyList(),
    /** Messages sharing this were sent together as an album (Telegram, WhatsApp); see [mergeAlbums]. */
    val albumId: String? = null,
)

/** Someone in a chat. [isPending]: invited but not yet joined. */
data class Member(val id: String, val name: String, val isAdmin: Boolean = false, val isPending: Boolean = false)

/** Someone in a service's address book, who can be messaged without typing a number. */
data class Contact(val id: String, val serviceId: ServiceId, val name: String, val detail: String? = null)

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
    /** We're an admin of this group, so may change who is in it. */
    val isAdmin: Boolean = false,
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
    /** Someone's online status changed; screens showing it ask again. */
    data class PresenceChanged(override val serviceId: ServiceId) : ServiceEvent
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
    /**
     * Reconnects now if the connection is down, instead of waiting out a
     * retry delay. [networkChanged]: the phone's network just came back or
     * switched, so even a connection that looks up may be dead.
     */
    suspend fun reconnect(networkChanged: Boolean) {}

    /** Whether the app is on screen; called on the main thread, so it mustn't block. */
    fun setForeground(foreground: Boolean) {}

    suspend fun listConversations(archived: Boolean): ConversationPage
    /** Fetches a service-provided conversation photo into local storage when available. */
    suspend fun conversationAvatar(conversation: Conversation): String? = conversation.avatarPath
    /** A person's profile photo by their [Reaction.authorId], fetched into local storage when available. */
    suspend fun personAvatar(personId: String): String? = null
    /** Newest page when [before] is null, else the page older than [before]. */
    suspend fun listMessages(conversation: Conversation, before: Message? = null): MessagePage
    /** Reads the chat up to and including [upTo] (sending read receipts for those), or all of it when null. */
    suspend fun markRead(conversation: Conversation, upTo: Message? = null)

    /** Called while a chat is on screen; networks use it for live updates and read state. */
    suspend fun openConversation(conversation: Conversation) {}
    suspend fun closeConversation(conversation: Conversation) {}
    suspend fun sendText(conversation: Conversation, text: String, replyTo: Message? = null): Message

    /** Sends text with [mentions] (MENTION spans over "@Name" in [text]). */
    suspend fun sendText(conversation: Conversation, text: String, replyTo: Message?, mentions: List<TextSpan>): Message =
        sendText(conversation, text, replyTo)

    /** The chat's other people, for mentions and group info. */
    suspend fun listMembers(conversation: Conversation): List<Member> = conversation.members
    suspend fun retryMessage(conversation: Conversation, message: Message): Unit = unsupported("retrying messages")
    suspend fun setTyping(conversation: Conversation, active: Boolean) {}
    /** [message] with what the network knows of who has it: [Receipt.readBy], per recipient. */
    suspend fun messageDetails(conversation: Conversation, message: Message): Message = message

    /** A one-to-one chat's other person, online or last seen; null where the network doesn't say. */
    suspend fun presence(conversation: Conversation): Presence? = null

    /** The same for a group's [members], by [Member.id]; only those the network reports. */
    suspend fun memberPresence(conversation: Conversation, members: List<Member>): Map<String, Presence> = emptyMap()

    suspend fun react(conversation: Conversation, message: Message, emoji: String, remove: Boolean = false): Unit =
        unsupported("reactions")
    suspend fun updateConversation(conversation: Conversation, update: ConversationUpdate): Unit =
        unsupported("conversation changes")
    suspend fun deleteConversation(conversation: Conversation): Unit = unsupported("deleting conversations")
    suspend fun searchMessages(query: String, conversation: Conversation? = null): List<SearchResult> =
        unsupported("search")
    /** Saved contacts on this network, sorted by name. */
    suspend fun listContacts(): List<Contact> = emptyList()

    /** Opens (starting it if need be) the direct chat with [contact]. */
    suspend fun startChat(contact: Contact): Conversation = unsupported("contacts")

    /** Finds or starts a direct chat with a phone number or username. */
    suspend fun createDirect(address: String, title: String? = null): Conversation = unsupported("new messages")
    /** Creates a group with us and [members] ([Contact.id]s); returns its chat. */
    suspend fun createGroup(name: String, members: List<String>): Conversation = unsupported("groups")
    suspend fun settings(): ServiceSettings = ServiceSettings()
    suspend fun updateSettings(settings: ServiceSettings): ServiceSettings = unsupported("settings")
    /** Downloads (once) and returns the local path of a received attachment. */
    suspend fun downloadAttachment(conversation: Conversation, message: Message, attachment: Attachment): String =
        unsupported("attachments")
    /** Downloads only a small server-side preview, without fetching the full video. */
    suspend fun downloadThumbnail(conversation: Conversation, message: Message, attachment: Attachment): String? = null
    suspend fun sendAttachment(conversation: Conversation, path: String, mimeType: String, caption: String? = null): Unit =
        unsupported("attachments")
    /** Services with albums override this; others send an ordered set with one caption. */
    suspend fun sendAttachments(conversation: Conversation, files: List<OutgoingAttachment>, caption: String? = null, replyTo: Message? = null) {
        files.forEachIndexed { index, file ->
            sendAttachment(conversation, file.path, file.mimeType, if (index == 0) caption else null)
        }
    }
    suspend fun forwardMessage(message: Message, target: Conversation): Unit = unsupported("forwarding")
    suspend fun listStickers(): List<Sticker> = emptyList()
    suspend fun sendSticker(conversation: Conversation, sticker: Sticker): Unit = unsupported("stickers")
    suspend fun editMessage(conversation: Conversation, message: Message, text: String): Unit = unsupported("edits")
    suspend fun deleteMessage(conversation: Conversation, message: Message): Unit = unsupported("deletes")
    suspend fun pinMessage(conversation: Conversation, message: Message, pinned: Boolean): Unit = unsupported("pins")
    suspend fun listPinnedMessages(conversation: Conversation): List<Message> = emptyList()
    suspend fun sendVoiceNote(conversation: Conversation, path: String, mimeType: String, durationMillis: Long): Unit =
        unsupported("voice notes")
    suspend fun createPoll(conversation: Conversation, question: String, options: List<String>, multiple: Boolean): Unit =
        unsupported("polls")
    suspend fun votePoll(conversation: Conversation, message: Message, options: List<Int>): Unit = unsupported("polls")
    suspend fun closePoll(conversation: Conversation, message: Message): Unit = unsupported("polls")
    suspend fun setBlocked(conversation: Conversation, blocked: Boolean): Unit = unsupported("blocking")
    /** Accepts a message request, or (with [accept] false) deletes the chat. */
    suspend fun respondToMessageRequest(conversation: Conversation, accept: Boolean): Unit =
        unsupported("message requests")
    suspend fun renameGroup(conversation: Conversation, name: String): Unit = unsupported("groups")
    /** Adds people ([Contact.id]s) to a group. */
    suspend fun addGroupMembers(conversation: Conversation, members: List<String>): Unit = unsupported("groups")
    suspend fun removeGroupMember(conversation: Conversation, member: Member): Unit = unsupported("groups")
    suspend fun leaveGroup(conversation: Conversation): Unit = unsupported("groups")
    suspend fun openViewOnce(message: Message): String = unsupported("view-once media")
    suspend fun safetyNumber(conversation: Conversation): String = unsupported("safety numbers")
    suspend fun trustSafetyNumber(conversation: Conversation, safetyNumber: String): Unit =
        unsupported("safety numbers")

    private fun unsupported(operation: String): Nothing = throw UnsupportedOperation(operation, id)
}
