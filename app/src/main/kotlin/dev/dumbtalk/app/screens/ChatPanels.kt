package dev.dumbtalk.app.screens

import dev.dumbtalk.app.ui.forDisplay
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dumbtalk.app.ui.AppIcon
import dev.dumbtalk.app.ui.Square
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.app.ui.TextInput
import dev.dumbtalk.app.ui.focusCard
import dev.dumbtalk.app.ui.messageSummary
import dev.dumbtalk.core.Capabilities
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.ConversationKind
import dev.dumbtalk.core.ConversationUpdate
import dev.dumbtalk.core.Direction
import dev.dumbtalk.core.Message

// Full-screen panels over a chat: actions for one message, and chat options.

internal val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@Composable
internal fun MessageActions(
    conversation: Conversation,
    message: Message,
    capabilities: Capabilities,
    onClose: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onRetry: () -> Unit = {},
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    /** View, Play/Stop or Open, for messages with an attachment. */
    media: MenuItem? = null,
    quote: MenuItem? = null,
    pollAction: MenuItem? = null,
    /** Show or hide the message's spoilers. */
    spoiler: MenuItem? = null,
    onChatOptions: () -> Unit = {},
    onMoreReactions: () -> Unit = {},
    onForward: () -> Unit = {},
    focusedLabel: String? = null,
    onFocused: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Back", onClose))
    val outgoing = message.direction == Direction.OUTGOING
    val canReact = capabilities.reactions && !message.deleted
    Column(Modifier.fillMaxSize()) {
        Header("Message actions")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.fillMaxWidth().border(1.dp, Palette.line, Square).padding(7.dp)) {
                BasicText((if (outgoing) "You" else message.sender ?: conversation.title).forDisplay(), style = Type.label)
                BasicText(messageSummary(message).forDisplay(), style = Type.preview, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (canReact) {
                BasicText("QUICK REACTION", style = Type.section)
                QUICK_REACTIONS.chunked(3).forEachIndexed { row, emojis ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        emojis.forEachIndexed { column, emoji ->
                            val own = message.reactions.any { it.isOwn && it.emoji == emoji }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .focusCard(
                                        { onReact(emoji) }, Square, Palette.tile,
                                        null,
                                        idleBorder = if (own) Palette.focus.copy(alpha = 0.5f) else Palette.tileBorder,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) { BasicText(emoji.forDisplay(), style = Type.centered(Type.body.copy(fontSize = 21.sp, lineHeight = 21.sp))) }
                        }
                    }
                }
            }
            val actions = buildList {
                if (capabilities.retries && message.receipt?.state == dev.dumbtalk.core.Receipt.State.FAILED) add(MenuItem("Retry send", AppIcon.SEND, onRetry))
                if (media != null && !message.deleted) add(media)
                if (spoiler != null && !message.deleted) add(spoiler)
                if (quote != null && !message.deleted) add(quote)
                if (pollAction != null && !message.deleted) add(pollAction)
                if (!message.deleted && capabilities.replies) add(MenuItem("Reply", AppIcon.REPLY, onReply))
                if (!message.deleted && (message.text != null || message.attachments.isNotEmpty())) add(MenuItem("Forward", AppIcon.FORWARD, onForward))
                add(MenuItem("Chat options", AppIcon.MENU, onChatOptions))
                if (outgoing && capabilities.edits && !message.deleted && message.text != null) add(MenuItem("Edit", AppIcon.EDIT, onEdit))
                if (capabilities.pins && !message.deleted) add(MenuItem(if (message.pinned) "Unpin" else "Pin", AppIcon.PIN, onPin))
                if (message.text != null) add(MenuItem("Copy", AppIcon.COMPOSE) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("message", message.text))
                    onClose()
                })
                if (capabilities.deletes && outgoing && !message.deleted) add(MenuItem("Delete", AppIcon.DELETE, onDelete))
                // A normal tile, not a full-width button: D-pad focus into a two-column grid is predictable.
                if (canReact) add(MenuItem("More emoji", AppIcon.STICKER, onMoreReactions))
            }
            MenuGrid(actions, requester, focusedLabel, onFocused)
            val details = buildList {
                message.reactions.forEach { add("${it.author}: ${it.emoji}") }
                message.receipt?.let { add("Status: ${it.state.name.lowercase()}") }
                message.receipt?.readBy?.forEach { add("${it.name}: ${it.status.name.lowercase()}") }
            }
            if (details.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Palette.tileBorder, Square)
                        .padding(7.dp),
                ) {
                    BasicText("MESSAGE DETAILS", style = Type.section)
                    details.forEach { BasicText(it.forDisplay(), style = Type.small) }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
internal fun ChatOptions(
    conversation: Conversation,
    polls: Boolean,
    onClose: () -> Unit,
    onUpdate: (ConversationUpdate) -> Unit,
    onCreatePoll: () -> Unit,
    search: Boolean = false,
    pins: Boolean = false,
    onSearch: () -> Unit = {},
    onPins: () -> Unit = {},
    deleteThread: Boolean = false,
    onDeleteThread: () -> Unit = {},
    focusedLabel: String? = null,
    onFocused: (String) -> Unit = {},
    /** Further options: timer, blocking, group info. */
    extra: List<MenuItem> = emptyList(),
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Back", onClose))
    Column(Modifier.fillMaxSize()) {
        Header("Chat options")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
            BasicText(conversation.title.forDisplay(), style = Type.section, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
            MenuGrid(
                chatOptionItems(conversation, polls, onUpdate, onCreatePoll, search, pins, onSearch, onPins, deleteThread, onDeleteThread) + extra,
                requester, focusedLabel, onFocused,
            )
            if (conversation.kind == ConversationKind.GROUP && conversation.members.isNotEmpty()) {
                BasicText("MEMBERS", style = Type.section, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                conversation.members.forEach { BasicText(it.name.forDisplay(), style = Type.body, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
    }
}

internal fun chatOptionItems(
    conversation: Conversation,
    polls: Boolean,
    onUpdate: (ConversationUpdate) -> Unit,
    onCreatePoll: () -> Unit,
    search: Boolean = false,
    pins: Boolean = false,
    onSearch: () -> Unit = {},
    onPins: () -> Unit = {},
    deleteThread: Boolean = false,
    onDeleteThread: () -> Unit = {},
): List<MenuItem> = listOf(
    MenuItem(if (conversation.isFavourite) "Unstar" else "Star", AppIcon.STAR) { onUpdate(ConversationUpdate(favourite = !conversation.isFavourite)) },
    MenuItem(if (conversation.isMuted) "Unmute" else "Mute", AppIcon.MUTE) { onUpdate(ConversationUpdate(muted = !conversation.isMuted)) },
    MenuItem(if (conversation.isArchived) "Unarchive" else "Archive", AppIcon.ARCHIVE) { onUpdate(ConversationUpdate(archived = !conversation.isArchived)) },
) + buildList {
    if (polls) add(MenuItem("Create poll", AppIcon.POLL, onCreatePoll))
    if (search) add(MenuItem("Search chat", AppIcon.SEARCH, onSearch))
    if (pins) add(MenuItem("Pinned messages", AppIcon.PIN, onPins))
    if (deleteThread) add(MenuItem("Delete thread", AppIcon.DELETE, onDeleteThread))
}

@Composable
internal fun PollCreator(onCreate: (String, List<String>, Boolean) -> Unit, onClose: () -> Unit) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var multiple by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val questionFocus = remember { FocusRequester() }
    val optionFocus = remember(options.size) { List(options.size) { FocusRequester() } }
    fun submit() {
        val cleanOptions = options.map(String::trim).filter(String::isNotEmpty)
        when {
            question.isBlank() -> error = "Enter a question"
            cleanOptions.size < 2 -> error = "Add at least two options"
            cleanOptions.size > 10 -> error = "Use no more than ten options"
            else -> onCreate(question.trim(), cleanOptions, multiple)
        }
    }
    LaunchedEffect(Unit) { runCatching { questionFocus.requestFocus() } }
    Softkeys(center = SoftkeyAction("Create", ::submit), right = SoftkeyAction("Back", onClose))
    Column(Modifier.fillMaxSize()) {
        Header("Create poll")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText("QUESTION", style = Type.section)
            TextInput(question, { question = it; error = null }, placeholder = "Ask a question", focusRequester = questionFocus, modifier = Modifier.fillMaxWidth())
            BasicText("OPTIONS", style = Type.section)
            options.forEachIndexed { index, value ->
                TextInput(value, { options[index] = it; error = null }, placeholder = "Option ${index + 1}", focusRequester = optionFocus[index], modifier = Modifier.fillMaxWidth())
            }
            if (options.size < 10) {
                Box(Modifier.fillMaxWidth().height(36.dp).focusCard({ options.add("") }, Square, Palette.tile, idleBorder = Palette.tileBorder), contentAlignment = Alignment.Center) {
                    BasicText("+ Add option", style = Type.body)
                }
            }
            Box(Modifier.fillMaxWidth().height(36.dp).focusCard({ multiple = !multiple }, Square, Palette.tile, idleBorder = if (multiple) Palette.focus else Palette.tileBorder), contentAlignment = Alignment.Center) {
                BasicText(if (multiple) "✓ Allow multiple answers" else "Allow multiple answers", style = Type.body)
            }
            error?.let { BasicText(it, style = Type.small.copy(color = Palette.error)) }
            BasicText("Choose Create to send this poll.", style = Type.small.copy(color = Palette.muted))
        }
    }
}

@Composable
internal fun PollVotePanel(message: Message, onVote: (List<Int>) -> Unit, onClose: () -> Unit) {
    val poll = message.poll ?: return
    val selected = remember(message.id) { mutableStateListOf<Int>().apply { addAll(poll.options.filter { it.mine }.map { it.index }) } }
    val requester = remember { FocusRequester() }
    fun toggle(index: Int) {
        if (index in selected) selected.remove(index)
        else if (poll.multiple) selected.add(index)
        else { selected.clear(); selected.add(index) }
    }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Softkeys(
        center = SoftkeyAction("Vote", { if (selected.isNotEmpty()) onVote(selected.toList()) }),
        right = SoftkeyAction("Back", onClose),
    )
    Column(Modifier.fillMaxSize()) {
        Header("Vote")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            BasicText(poll.question.forDisplay(), style = Type.label)
            BasicText(if (poll.multiple) "Choose any number of options." else "Choose one option.", style = Type.small.copy(color = Palette.muted))
            poll.options.forEachIndexed { index, option ->
                val votes = option.votes.size.takeIf { it > 0 }?.let { " · $it votes" }.orEmpty()
                Box(
                    Modifier.fillMaxWidth().height(40.dp).focusCard(
                        { toggle(index) }, Square, Palette.tile,
                        focusRequester = if (index == 0) requester else null,
                        idleBorder = if (index in selected) Palette.focus else Palette.tileBorder,
                    ).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { BasicText("${if (index in selected) "✓" else "○"} ${option.text}$votes".forDisplay(), style = Type.body) }
            }
            if (selected.isEmpty()) BasicText("Select an option, then choose Vote.", style = Type.small.copy(color = Palette.muted))
        }
    }
}

@Composable
internal fun PollCloseConfirmation(message: Message, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Back", onCancel))
    Column(Modifier.fillMaxSize()) {
        Header("Close poll?")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
            BasicText("${message.poll?.question.orEmpty().forDisplay()} will stop accepting votes.", style = Type.body)
            MenuGrid(
                listOf(
                    MenuItem("Keep open", AppIcon.POLL, onCancel),
                    MenuItem("Close poll", AppIcon.CLOSE, onConfirm),
                ),
                requester,
            )
        }
    }
}
