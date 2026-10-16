package dev.dumbtalk.telegram

import dev.dumbtalk.core.Attachment
import dev.dumbtalk.core.AttachmentKind
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.Direction
import dev.dumbtalk.core.Message
import dev.dumbtalk.core.Poll
import dev.dumbtalk.core.Quote
import dev.dumbtalk.core.Reaction
import dev.dumbtalk.core.Receipt
import dev.dumbtalk.core.ServiceId
import org.drinkless.tdlib.TdApi

/** Maps TDLib objects to the universal types. Callers hold the chat's lock. */
internal class TelegramMapper(private val service: TelegramService) {

    fun userName(user: TdApi.User): String =
        listOf(user.firstName, user.lastName).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { "Deleted account" }

    private fun senderName(sender: TdApi.MessageSender?): String? = when (sender) {
        is TdApi.MessageSenderUser -> service.users[sender.userId]?.let(::userName)
        is TdApi.MessageSenderChat -> service.chats[sender.chatId]?.title
        else -> null
    }

    private fun isMe(sender: TdApi.MessageSender?) =
        sender is TdApi.MessageSenderUser && sender.userId == service.myUserId()

    fun conversation(chat: TdApi.Chat): Conversation {
        val pinned = chat.positions.any { it.isPinned && (it.list is TdApi.ChatListMain || it.list is TdApi.ChatListArchive) }
        val archived = chat.positions.any { it.list is TdApi.ChatListArchive && it.order != 0L }
        val privateUser = (chat.type as? TdApi.ChatTypePrivate)?.userId
        return Conversation(
            id = "${ServiceId.TELEGRAM.key}:${chat.id}",
            serviceId = ServiceId.TELEGRAM,
            remoteId = chat.id.toString(),
            kind = TelegramService.kind(chat),
            title = chat.title.ifBlank { "Telegram chat" },
            isNoteToSelf = privateUser != null && privateUser == service.myUserId(),
            isArchived = archived,
            isFavourite = pinned,
            isMuted = chat.notificationSettings.muteFor > 0,
            unreadCount = chat.unreadCount,
            typingNames = service.typingNames(chat.id),
            lastMessage = chat.lastMessage?.let { message(chat, it) },
            expiration = chat.messageAutoDeleteTime,
        )
    }

    fun message(chat: TdApi.Chat, message: TdApi.Message, replies: Map<Long, TdApi.Message> = emptyMap()): Message {
        val content = describe(message.content)
        val outgoing = message.isOutgoing
        return Message(
            id = message.id.toString(),
            conversationId = "${ServiceId.TELEGRAM.key}:${chat.id}",
            sentAt = message.date * 1000L,
            direction = when {
                content.system -> Direction.SYSTEM
                outgoing -> Direction.OUTGOING
                else -> Direction.INCOMING
            },
            sender = if (outgoing) "You" else senderName(message.senderId) ?: chat.title,
            text = content.text,
            attachments = content.attachments,
            reactions = reactions(message.interactionInfo),
            receipt = if (outgoing) receipt(chat, message) else null,
            quote = quote(chat, message.replyTo, replies),
            edited = message.editDate > 0,
            pinned = message.isPinned,
            poll = content.poll,
            forwardedFrom = forwardedFrom(message.forwardInfo),
        )
    }

    private fun receipt(chat: TdApi.Chat, message: TdApi.Message): Receipt? = when (message.sendingState) {
        is TdApi.MessageSendingStatePending -> null
        is TdApi.MessageSendingStateFailed -> null
        // Telegram has no delivered state: a message is sent until read.
        else -> Receipt(if (message.id <= chat.lastReadOutboxMessageId) Receipt.State.READ else Receipt.State.SENT)
    }

    /** One universal reaction per reactor, so the UI can show counts. */
    private fun reactions(info: TdApi.MessageInteractionInfo?): List<Reaction> =
        info?.reactions?.reactions.orEmpty().flatMap { reaction ->
            val emoji = (reaction.type as? TdApi.ReactionTypeEmoji)?.emoji ?: return@flatMap emptyList()
            val named = reaction.recentSenderIds.filterNot(::isMe).mapNotNull(::senderName)
            val own = if (reaction.isChosen) listOf(Reaction(emoji, "You", isOwn = true)) else emptyList()
            val others = named.map { Reaction(emoji, it, isOwn = false) }
            val unnamed = (reaction.totalCount - own.size - others.size).coerceAtLeast(0)
            own + others + List(unnamed) { Reaction(emoji, "Someone", isOwn = false) }
        }

    private fun quote(chat: TdApi.Chat, replyTo: TdApi.MessageReplyTo?, replies: Map<Long, TdApi.Message>): Quote? {
        val reply = replyTo as? TdApi.MessageReplyToMessage ?: return null
        val original = replies[reply.messageId]
        val text = reply.quote?.text?.text?.takeIf { it.isNotBlank() }
            ?: original?.let { describe(it.content).summary() }
            ?: reply.content?.let { describe(it).summary() }
        val author = when {
            original == null -> chat.title
            original.isOutgoing -> "You"
            else -> senderName(original.senderId) ?: chat.title
        }
        return Quote(author, text, original?.date?.times(1000L))
    }

    private fun forwardedFrom(info: TdApi.MessageForwardInfo?): String? = when (val origin = info?.origin) {
        is TdApi.MessageOriginUser -> service.users[origin.senderUserId]?.let(::userName) ?: "a user"
        is TdApi.MessageOriginHiddenUser -> origin.senderName
        is TdApi.MessageOriginChat -> service.chats[origin.senderChatId]?.title ?: "a group"
        is TdApi.MessageOriginChannel -> service.chats[origin.chatId]?.title ?: "a channel"
        else -> null
    }

    private class Described(
        val text: String?,
        val attachments: List<Attachment> = emptyList(),
        val poll: Poll? = null,
        val system: Boolean = false,
    ) {
        fun summary(): String = text ?: when (attachments.firstOrNull()?.kind) {
            AttachmentKind.IMAGE -> "Photo"
            AttachmentKind.VIDEO -> "Video"
            AttachmentKind.AUDIO -> "Voice note"
            AttachmentKind.FILE -> "File"
            null -> poll?.let { "Poll: ${it.question}" } ?: "Message"
        }
    }

    private fun caption(text: TdApi.FormattedText?) = text?.text?.takeIf { it.isNotBlank() }

    private fun attachment(file: TdApi.File, kind: AttachmentKind, mime: String? = null, name: String? = null, width: Int? = null, height: Int? = null) =
        Attachment(
            id = file.id.toString(),
            kind = kind,
            localPath = file.local.path.takeIf { file.local.isDownloadingCompleted && it.isNotBlank() },
            contentType = mime,
            filename = name?.takeIf { it.isNotBlank() },
            size = file.size.takeIf { it > 0 } ?: file.expectedSize,
            width = width,
            height = height,
        )

    private fun describe(content: TdApi.MessageContent): Described = when (content) {
        is TdApi.MessageText -> Described(content.text.text)
        is TdApi.MessagePhoto -> {
            val size = content.photo.sizes.maxByOrNull { it.width * it.height }
            Described(caption(content.caption), listOfNotNull(size?.let { attachment(it.photo, AttachmentKind.IMAGE, "image/jpeg", width = it.width, height = it.height) }))
        }
        is TdApi.MessageVideo -> Described(caption(content.caption), listOf(attachment(content.video.video, AttachmentKind.VIDEO, content.video.mimeType, content.video.fileName, content.video.width, content.video.height)))
        is TdApi.MessageAnimation -> Described(caption(content.caption), listOf(attachment(content.animation.animation, AttachmentKind.VIDEO, content.animation.mimeType, content.animation.fileName)))
        is TdApi.MessageVoiceNote -> Described(caption(content.caption), listOf(attachment(content.voiceNote.voice, AttachmentKind.AUDIO, content.voiceNote.mimeType)))
        is TdApi.MessageAudio -> Described(caption(content.caption) ?: listOf(content.audio.performer, content.audio.title).filter { it.isNotBlank() }.joinToString(" – ").ifBlank { null }, listOf(attachment(content.audio.audio, AttachmentKind.AUDIO, content.audio.mimeType, content.audio.fileName)))
        is TdApi.MessageDocument -> Described(caption(content.caption) ?: content.document.fileName.ifBlank { null }, listOf(attachment(content.document.document, AttachmentKind.FILE, content.document.mimeType, content.document.fileName)))
        is TdApi.MessageSticker -> Described("${content.sticker.emoji} Sticker".trim())
        is TdApi.MessagePoll -> Described(null, poll = Poll(
            question = content.poll.question.text,
            options = content.poll.options.mapIndexed { index, option -> Poll.Option(index, option.text.text, List(option.voterCount) { "" }) },
            multiple = content.poll.allowsMultipleAnswers,
            closed = content.poll.isClosed,
        ))
        is TdApi.MessageContact -> Described("Contact: ${listOf(content.contact.firstName, content.contact.lastName).filter { it.isNotBlank() }.joinToString(" ")} ${content.contact.phoneNumber}".trim())
        is TdApi.MessageLocation -> Described("📍 Location")
        is TdApi.MessageVenue -> Described("📍 ${content.venue.title}")
        is TdApi.MessageCall -> Described(if (content.isVideo) "📹 Video call" else "📞 Call")
        is TdApi.MessageDice -> Described(content.emoji)
        is TdApi.MessageChatAddMembers -> Described(content.memberUserIds.mapNotNull { service.users[it]?.let(::userName) }.joinToString().ifEmpty { "Someone" } + " joined", system = true)
        is TdApi.MessageChatJoinByLink -> Described("Joined by invite link", system = true)
        is TdApi.MessageChatJoinByRequest -> Described("Joined the group", system = true)
        is TdApi.MessageChatDeleteMember -> Described((service.users[content.userId]?.let(::userName) ?: "Someone") + " left", system = true)
        is TdApi.MessageChatChangeTitle -> Described("Group renamed to “${content.title}”", system = true)
        is TdApi.MessageChatChangePhoto -> Described("Group photo changed", system = true)
        is TdApi.MessagePinMessage -> Described("Pinned a message", system = true)
        is TdApi.MessageBasicGroupChatCreate -> Described("Group “${content.title}” created", system = true)
        is TdApi.MessageSupergroupChatCreate -> Described("Group “${content.title}” created", system = true)
        is TdApi.MessageContactRegistered -> Described("Joined Telegram", system = true)
        is TdApi.MessageChatSetMessageAutoDeleteTime -> Described(
            if (content.messageAutoDeleteTime == 0) "Disappearing messages off" else "Disappearing messages on",
            system = true,
        )
        else -> Described("Unsupported message — open Telegram to view")
    }
}
