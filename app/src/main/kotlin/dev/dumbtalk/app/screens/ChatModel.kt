package dev.dumbtalk.app.screens

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dumbtalk.core.OutgoingAttachment
import dev.dumbtalk.core.Attachment
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.ConversationUpdate
import dev.dumbtalk.core.Direction
import dev.dumbtalk.core.Member
import dev.dumbtalk.core.Message
import dev.dumbtalk.core.mergeAlbums
import dev.dumbtalk.core.MessagingService
import dev.dumbtalk.core.TextSpan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    var attachments by mutableStateOf<List<OutgoingAttachment>>(emptyList())
        private set
    var sending by mutableStateOf(false)
        private set
    val canSend get() = !sending && (draft.isNotBlank() || attachments.isNotEmpty())

    fun attach(files: List<OutgoingAttachment>) { attachments = (attachments + files).take(10) }
    fun removeAttachment(index: Int) { attachments = attachments.filterIndexed { i, _ -> i != index } }

    var draft by mutableStateOf(initialDraft)
        private set
    var replyTo by mutableStateOf<Message?>(null)
        private set
    var editing by mutableStateOf<Message?>(null)
        private set

    /** Index of the newest message; the list has a "load older" row first when [hasMore]. */
    val lastIndex: Int get() = listIndex(messages.orEmpty().size - 1).coerceAtLeast(0)

    /** The list row of the [messageIndex]th message, after the "load older" row when there is one. */
    fun listIndex(messageIndex: Int) = messageIndex + if (hasMore) 1 else 0

    private val loadLock = Mutex()
    /** Messages as the service gave them; [messages] has albums joined. */
    private var raw: List<Message> = emptyList()

    /** Loads the newest page (keeping older pages already shown) or, with [older], the page before. */
    suspend fun load(older: Boolean = false): Message? = loadLock.withLock {
        val current = raw
        var newestOlderMessage: Message? = null
        runCatching { service.listMessages(conversation(), if (older) current.firstOrNull() else null) }
            .onSuccess { page ->
                if (older) {
                    val known = current.map { it.id }.toSet()
                    val newlyLoaded = page.messages.filterNot { it.id in known }
                    raw = newlyLoaded + current
                    messages = mergeAlbums(raw)
                    // The newest of the older page, as shown (an album's first part).
                    newestOlderMessage = newlyLoaded.lastOrNull()?.let { newest ->
                        messages.orEmpty().lastOrNull { it.sentAt <= newest.sentAt && it.direction == newest.direction }
                    }
                    hasMore = page.hasMore
                } else {
                    val oldest = page.messages.firstOrNull()?.sentAt
                    val earlier = if (oldest == null) emptyList() else current.filter { it.sentAt < oldest }
                    raw = earlier + page.messages
                    messages = mergeAlbums(raw)
                    if (earlier.isEmpty()) hasMore = page.hasMore
                }
                loadError = null
                if (viewingNewest) markSeen()
            }
            .onFailure { if (it is CancellationException) throw it; loadError = it.message ?: "Could not load messages" }
        newestOlderMessage
    }

    /** Whether the newest message is on screen; only then are messages marked read. */
    var viewingNewest = false
        set(value) {
            field = value
            if (value) launch { markSeen() }
        }
    /** The newest incoming message already marked read, so reaching the bottom again sends nothing. */
    private var seenId: String? = null

    private suspend fun markSeen() {
        val newest = messages.orEmpty().lastOrNull { it.direction == Direction.INCOMING } ?: return
        if (newest.id == seenId) return
        seenId = newest.id
        runCatching { service.markRead(conversation()) }
    }

    /**
     * The oldest of the [unread] newest incoming messages, loading older pages
     * (up to ten) until it is in the list. Null when nothing is unread. Anything
     * before our own newest message counts as read: replying reads a chat.
     */
    suspend fun firstUnread(unread: Int): Message? {
        if (unread <= 0) return null
        repeat(10) {
            val shown = messages.orEmpty()
            val incoming = shown.drop(shown.indexOfLast { it.direction == Direction.OUTGOING } + 1)
                .filter { it.direction == Direction.INCOMING }
            val replied = shown.any { it.direction == Direction.OUTGOING }
            if (incoming.size >= unread || replied || !hasMore) return incoming.getOrNull(incoming.size - unread) ?: incoming.firstOrNull()
            load(older = true)
        }
        return messages.orEmpty().firstOrNull { it.direction == Direction.INCOMING }
    }

    /** Keep history contiguous when revealing an older search result or pin. */
    suspend fun revealMessage(id: String, timestamp: Long) {
        while (messages.orEmpty().none { it.id == id }) {
            val first = messages.orEmpty().firstOrNull()
            check(hasMore && first != null && first.sentAt >= timestamp) { "This message is no longer available in the chat" }
            val previousId = first.id
            load(older = true)
            loadError?.let { error(it) }
            check(messages.orEmpty().firstOrNull()?.id != previousId) { "This message could not be found in the available history" }
        }
    }

    fun type(text: String) {
        if (draft.isEmpty() && text.isNotEmpty()) launch { service.setTyping(conversation(), true) }
        draft = text
    }

    // region Mentions

    /** Whether this chat's service sends real mentions; set by the screen. */
    var mentionsEnabled by mutableStateOf(false)

    /** Loads the people once an "@" is being typed. */
    fun prepareMentions() {
        if (mentionsEnabled && mentionStart() != null) loadMembers()
    }

    /** The chat's people, loaded the first time a mention is typed. */
    var members by mutableStateOf<List<Member>?>(null)
        private set
    private var membersLoading = false
    /** People picked from the suggestions, matched back to "@Name" on send. */
    private val mentioned = mutableListOf<Member>()

    fun loadMembers() {
        if (members != null || membersLoading) return
        membersLoading = true
        scope.launch {
            members = runCatching { service.listMembers(conversation()) }.getOrDefault(emptyList())
            membersLoading = false
        }
    }

    /** Where the "@" being typed at the end of the draft starts, if any. */
    private fun mentionStart(): Int? {
        val at = draft.lastIndexOf('@').takeIf { it >= 0 } ?: return null
        if (at > 0 && !draft[at - 1].isWhitespace()) return null
        val query = draft.substring(at + 1)
        // A name of up to two words, not yet finished with a space after a pick.
        if (query.length > 24 || query.count { it == ' ' } > 1 || '\n' in query) return null
        if (mentioned.any { query == it.name || query.startsWith(it.name + " ") }) return null
        return at
    }

    /** Up to four people matching the "@…" being typed. */
    val mentionSuggestions: List<Member>
        get() {
            if (!mentionsEnabled) return emptyList()
            val at = mentionStart() ?: return emptyList()
            val query = draft.substring(at + 1).trim().lowercase()
            return members.orEmpty().filter { member ->
                !member.isPending && (query.isEmpty() || member.name.lowercase().startsWith(query) ||
                    member.name.lowercase().split(' ').any { it.startsWith(query) })
            }.take(4)
        }

    /** Replaces the "@…" being typed with "@Name ". */
    fun pickMention(member: Member) {
        val at = mentionStart() ?: return
        draft = draft.substring(0, at) + "@" + member.name + " "
        if (mentioned.none { it.id == member.id }) mentioned += member
    }

    /** Mention spans for each picked person's "@Name" still in [text]. */
    private fun mentionSpans(text: String): List<TextSpan> {
        val spans = mutableListOf<TextSpan>()
        // Longer names first, so "@Ann Lee" isn't taken as "@Ann".
        mentioned.sortedByDescending { it.name.length }.forEach { member ->
            val tag = "@" + member.name
            var from = text.indexOf(tag)
            while (from >= 0) {
                val end = from + tag.length
                if (spans.none { from < it.end && end > it.start }) spans += TextSpan(from, tag.length, TextSpan.Style.MENTION, member.id)
                from = text.indexOf(tag, end)
            }
        }
        return spans.sortedBy { it.start }
    }

    // endregion

    /** The draft as a caption (null if empty), clearing it. */
    fun takeDraft(): String? = draft.trim().takeIf { it.isNotEmpty() }.also { draft = "" }

    fun clearDraft() {
        draft = ""
        mentioned.clear()
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
        if (!canSend) return
        val text = draft.trim()
        val files = attachments
        val target = editing
        val quoted = replyTo
        val mentions = mentionSpans(text)
        val picked = mentioned.toList()
        mentioned.clear()
        draft = ""
        replyTo = null
        editing = null
        attachments = emptyList()
        sending = true
        scope.launch {
            runCatching {
                if (target != null) service.editMessage(conversation(), target, text)
                else if (files.isNotEmpty()) service.sendAttachments(conversation(), files, text.takeIf { it.isNotBlank() }, quoted)
                else service.sendText(conversation(), text, quoted, mentions)
            }.onSuccess {
                sendError = null
            }.onFailure { failure ->
                Log.w("Chat", "send failed", failure)
                sendError = "Not sent: ${failure.message ?: "unknown error"}"
                if (draft.isEmpty()) { draft = text; mentioned += picked }
                attachments = files + attachments
                replyTo = quoted
                editing = target
            }
            sending = false
            runCatching { service.setTyping(conversation(), false) }
            load()
            then()
        }
    }

    private val downloaded = mutableMapOf<String, String>()

    /** Local path of a message's attachment, downloading it the first time. */
    suspend fun attachmentPath(message: Message): String = attachmentPath(message, message.attachments.first())

    suspend fun attachmentPath(message: Message, attachment: Attachment): String {
        attachment.localPath?.let { return it }
        val key = "${message.id}:${attachment.id}"
        return downloaded[key] ?: service.downloadAttachment(conversation(), message, attachment)
            .also { downloaded[key] = it }
    }

    private val thumbnails = mutableMapOf<String, String>()
    suspend fun thumbnailPath(message: Message, attachment: Attachment): String? {
        val key = "${message.id}:${attachment.id}"
        return thumbnails[key] ?: service.downloadThumbnail(conversation(), message, attachment)?.also { thumbnails[key] = it }
    }

    fun sendFile(path: String, mime: String, caption: String? = null) =
        sendMedia { service.sendAttachment(conversation(), path, mime, caption) }

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
                val media = message.attachments
                if (media.isNotEmpty()) {
                    // Some services represent an album as several attachments on
                    // one message. Download and send the whole ordered set so the
                    // destination can preserve it as an album where supported.
                    val files = media.map { attachment ->
                        OutgoingAttachment(
                            path = attachmentPath(message, attachment),
                            mimeType = attachment.contentType ?: "application/octet-stream",
                        )
                    }
                    targetService.sendAttachments(target, files, message.text)
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

    fun createPoll(question: String, options: List<String>, multiple: Boolean) = launch {
        service.createPoll(conversation(), question, options, multiple)
        load()
    }

    fun votePoll(message: Message, options: List<Int>) = launch {
        service.votePoll(conversation(), message, options)
        load()
    }

    fun closePoll(message: Message) = launch {
        service.closePoll(conversation(), message)
        load()
    }

    fun retry(message: Message) = launch { service.retryMessage(conversation(), message); load() }

    fun update(change: ConversationUpdate) = act("Couldn't change that") { service.updateConversation(conversation(), change) }

    fun respondToRequest(accept: Boolean, then: () -> Unit = {}) = act("Couldn't answer the request") {
        service.respondToMessageRequest(conversation(), accept)
        then()
    }

    fun setBlocked(blocked: Boolean, then: () -> Unit = {}) = act(if (blocked) "Couldn't block" else "Couldn't unblock") {
        service.setBlocked(conversation(), blocked)
        then()
    }

    fun renameGroup(name: String) = act("Couldn't rename the group") { service.renameGroup(conversation(), name) }

    fun addMembers(ids: List<String>) = act("Couldn't add them") { service.addGroupMembers(conversation(), ids); members = null }

    fun removeMember(member: Member) = act("Couldn't remove ${member.name}") { service.removeGroupMember(conversation(), member); members = null }

    fun leaveGroup(then: () -> Unit) = act("Couldn't leave the group") { service.leaveGroup(conversation()); then() }

    /** Runs a chat change, showing why it failed above the composer. */
    private fun act(failure: String, block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onSuccess { sendError = null }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.w("Chat", failure, it)
                    sendError = "$failure: ${it.message ?: "unknown error"}"
                }
        }
    }

    /** Fire-and-forget service calls; their results arrive as events. */
    private fun launch(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { Log.w("Chat", "action failed", it) } }
    }
}
