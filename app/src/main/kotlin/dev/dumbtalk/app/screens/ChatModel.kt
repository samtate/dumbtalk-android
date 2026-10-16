package dev.dumbtalk.app.screens

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.ConversationUpdate
import dev.dumbtalk.core.Direction
import dev.dumbtalk.core.Message
import dev.dumbtalk.core.MessagingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The state of one open chat — its loaded messages and the composer — and
 * the actions on it. [ChatScreen] renders it and owns focus and panels.
 */
class ChatModel(
    private val service: MessagingService,
    private val conversation: () -> Conversation,
    private val scope: CoroutineScope,
    initialDraft: String = "",
) {
    var messages by mutableStateOf<List<Message>?>(null)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    /** Kept apart from [loadError], which every reload clears. */
    var sendError by mutableStateOf<String?>(null)
        private set

    var draft by mutableStateOf(initialDraft)
        private set
    var replyTo by mutableStateOf<Message?>(null)
        private set
    var editing by mutableStateOf<Message?>(null)
        private set

    /** Index of the newest message; the list has a "load older" row first when [hasMore]. */
    val lastIndex: Int get() = (messages.orEmpty().size - 1 + if (hasMore) 1 else 0).coerceAtLeast(0)

    /** Loads the newest page (keeping older pages already shown) or, with [older], the page before. */
    suspend fun load(older: Boolean = false) {
        val current = messages.orEmpty()
        runCatching { service.listMessages(conversation(), if (older) current.firstOrNull() else null) }
            .onSuccess { page ->
                if (older) {
                    val known = current.map { it.id }.toSet()
                    messages = page.messages.filterNot { it.id in known } + current
                    hasMore = page.hasMore
                } else {
                    val oldest = page.messages.firstOrNull()?.sentAt
                    val earlier = if (oldest == null) emptyList() else current.filter { it.sentAt < oldest }
                    messages = earlier + page.messages
                    if (earlier.isEmpty()) hasMore = page.hasMore
                }
                loadError = null
                if (page.messages.any { it.direction == Direction.INCOMING }) {
                    runCatching { service.markRead(conversation()) }
                }
            }
            .onFailure { loadError = it.message ?: "Could not load messages" }
    }

    fun type(text: String) {
        if (draft.isEmpty() && text.isNotEmpty()) launch { service.setTyping(conversation(), true) }
        draft = text
    }

    /** Adds text (an emoji, say) to the end of the draft. */
    fun append(text: String) {
        draft += text
    }

    fun startReply(message: Message) {
        replyTo = message
        editing = null
    }

    fun startEdit(message: Message) {
        editing = message
        replyTo = null
        draft = message.text.orEmpty()
    }

    /** Cancels a reply or edit in progress; false if there was none. */
    fun cancelCompose(): Boolean = when {
        replyTo != null -> { replyTo = null; true }
        editing != null -> { editing = null; draft = ""; true }
        else -> false
    }

    /**
     * Sends the draft (or saves an edit). On failure the text, reply and
     * edit come back so nothing typed is lost. [then] runs either way.
     */
    fun send(then: suspend () -> Unit = {}) {
        val text = draft.trim()
        if (text.isEmpty()) return
        val target = editing
        val quoted = replyTo
        draft = ""
        replyTo = null
        editing = null
        scope.launch {
            runCatching {
                if (target != null) service.editMessage(conversation(), target, text)
                else service.sendText(conversation(), text, quoted)
            }.onSuccess {
                sendError = null
            }.onFailure { failure ->
                Log.w("Chat", "send failed", failure)
                sendError = "Not sent: ${failure.message ?: "unknown error"}"
                if (draft.isEmpty()) draft = text
                replyTo = quoted
                editing = target
            }
            runCatching { service.setTyping(conversation(), false) }
            load()
            then()
        }
    }

    private val downloaded = mutableMapOf<String, String>()

    /** Local path of a message's attachment, downloading it the first time. */
    suspend fun attachmentPath(message: Message): String =
        downloaded[message.id] ?: service.downloadAttachment(conversation(), message, message.attachments.first())
            .also { downloaded[message.id] = it }

    fun sendFile(path: String, mime: String) = sendMedia { service.sendAttachment(conversation(), path, mime) }

    fun sendVoiceNote(path: String, mime: String, durationMillis: Long) =
        sendMedia { service.sendVoiceNote(conversation(), path, mime, durationMillis) }

    private fun sendMedia(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onSuccess { sendError = null }
                .onFailure { failure ->
                    Log.w("Chat", "media send failed", failure)
                    sendError = "Not sent: ${failure.message ?: "unknown error"}"
                }
            load()
        }
    }

    /**
     * Sends [message] to [target] on any service: attachments are downloaded
     * and re-sent with their caption, text goes with an attribution line.
     * [done] gets null on success or a reason.
     */
    fun forward(message: Message, target: Conversation, targetService: MessagingService, done: (String?) -> Unit) {
        scope.launch {
            val result = runCatching {
                val attachment = message.attachments.firstOrNull()
                if (attachment != null) {
                    val path = attachmentPath(message)
                    targetService.sendAttachment(target, path, attachment.contentType ?: "application/octet-stream", message.text)
                } else {
                    val text = forwardedText(conversation(), message) ?: error("Nothing to forward")
                    targetService.sendText(target, text)
                }
            }
            done(result.exceptionOrNull()?.let { it.message ?: "failed" })
        }
    }

    fun toggleReaction(message: Message, emoji: String) {
        val own = message.reactions.any { it.isOwn && it.emoji == emoji }
        launch { service.react(conversation(), message, emoji, remove = own) }
    }

    fun delete(message: Message) = launch { service.deleteMessage(conversation(), message) }

    fun togglePin(message: Message) = launch { service.pinMessage(conversation(), message, !message.pinned) }

    fun update(change: ConversationUpdate) = launch { service.updateConversation(conversation(), change) }

    /** Fire-and-forget service calls; their results arrive as events. */
    private fun launch(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { Log.w("Chat", "action failed", it) } }
    }
}
