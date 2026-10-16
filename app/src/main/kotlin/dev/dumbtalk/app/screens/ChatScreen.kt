package dev.dumbtalk.app.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dumbtalk.app.DumbTalkApp
import dev.dumbtalk.app.Navigator
import dev.dumbtalk.app.Screen
import dev.dumbtalk.app.ui.AppIcon
import dev.dumbtalk.app.ui.CenteredMessage
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.Icon
import dev.dumbtalk.app.ui.LocalSoftkeys
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.Pill
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.TextInput
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.app.ui.focusCard
import dev.dumbtalk.core.Capabilities
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.ConversationKind
import dev.dumbtalk.core.ConversationUpdate
import dev.dumbtalk.core.Direction
import dev.dumbtalk.core.Message
import dev.dumbtalk.core.Receipt
import dev.dumbtalk.core.ServiceEvent
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@Composable
fun ChatScreen(app: DumbTalkApp, navigator: Navigator, screen: Screen.Chat) {
    val service = app.registry.forConversation(screen.initial)
    val scope = rememberCoroutineScope()
    val softkeys = LocalSoftkeys.current

    // Follow live conversation changes (mute, typing, …) from the inbox model.
    app.inbox.active.collectAsStateWithLifecycle().value
    app.inbox.archived.collectAsStateWithLifecycle().value
    val conversation = app.inbox.find(screen.conversationId) ?: screen.initial

    val capabilities by produceState(Capabilities(), service) { value = runCatching { service.capabilities() }.getOrDefault(Capabilities()) }
    var messages by remember { mutableStateOf<List<Message>?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var draft by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var actionMessageId by remember { mutableStateOf<String?>(null) }
    var showOptions by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val messageFocus = remember { FocusRequester() }
    val inputFocus = remember { FocusRequester() }
    var refocus by remember { mutableStateOf(0) }

    // The list holds an optional "load older" row before the messages.
    fun lastIndex() = (messages.orEmpty().size - 1 + if (hasMore) 1 else 0).coerceAtLeast(0)

    suspend fun load(older: Boolean = false) {
        runCatching {
            val before = if (older) messages?.firstOrNull() else null
            service.listMessages(conversation, before)
        }.onSuccess { page ->
            if (older) {
                val known = messages.orEmpty().map { it.id }.toSet()
                messages = page.messages.filterNot { it.id in known } + messages.orEmpty()
                hasMore = page.hasMore
            } else {
                // Keep older pages already loaded; replace the newest window.
                val oldest = page.messages.firstOrNull()?.sentAt
                val earlier = if (oldest == null) emptyList() else messages.orEmpty().filter { it.sentAt < oldest }
                messages = earlier + page.messages
                if (earlier.isEmpty()) hasMore = page.hasMore
            }
            error = null
            if (page.messages.any { it.direction == Direction.INCOMING }) runCatching { service.markRead(conversation) }
        }.onFailure { error = it.message ?: "Could not load messages" }
    }

    DisposableEffect(screen.conversationId) {
        scope.launch { runCatching { service.openConversation(conversation) } }
        onDispose { app.scope.launch { runCatching { service.closeConversation(conversation) } } }
    }

    LaunchedEffect(screen.conversationId) {
        load()
        messages?.lastOrNull()?.let { selectedId = it.id }
        listState.scrollToItem(lastIndex())
        refocus++
        service.events
            .filter { it is ServiceEvent.MessagesChanged && it.conversationId == screen.conversationId }
            .collect {
                val atBottom = !listState.canScrollForward
                load()
                if (atBottom) listState.animateScrollToItem(lastIndex())
            }
    }

    LaunchedEffect(refocus, actionMessageId, showOptions) {
        if (actionMessageId != null || showOptions || refocus == 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { messageFocus.requestFocus() }
    }

    val actionMessage = messages?.firstOrNull { it.id == actionMessageId }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        val target = editing
        val quoted = replyTo
        draft = ""
        replyTo = null
        editing = null
        scope.launch {
            runCatching {
                if (target != null) service.editMessage(conversation, target, text)
                else service.sendText(conversation, text, quoted)
                service.setTyping(conversation, false)
            }.onFailure { error = it.message }
            load()
            listState.animateScrollToItem(lastIndex())
        }
    }

    when {
        actionMessage != null -> MessageActions(
            conversation, actionMessage, capabilities,
            onClose = { actionMessageId = null },
            onReact = { emoji ->
                val own = actionMessage.reactions.any { it.isOwn && it.emoji == emoji }
                scope.launch { runCatching { service.react(conversation, actionMessage, emoji, remove = own) } }
                actionMessageId = null
            },
            onReply = {
                replyTo = actionMessage
                editing = null
                actionMessageId = null
                scope.launch { withFrameNanos { }; runCatching { inputFocus.requestFocus() } }
            },
            onEdit = {
                editing = actionMessage
                replyTo = null
                draft = actionMessage.text.orEmpty()
                actionMessageId = null
                scope.launch { withFrameNanos { }; runCatching { inputFocus.requestFocus() } }
            },
            onDelete = {
                scope.launch { runCatching { service.deleteMessage(conversation, actionMessage) } }
                actionMessageId = null
            },
            onPin = {
                scope.launch { runCatching { service.pinMessage(conversation, actionMessage, !actionMessage.pinned) } }
                actionMessageId = null
            },
        )

        showOptions -> ChatOptions(
            conversation,
            onClose = { showOptions = false },
            onUpdate = { update ->
                scope.launch { runCatching { service.updateConversation(conversation, update) } }
                showOptions = false
                if (update.archived == true) navigator.pop()
            },
        )

        else -> {
            Softkeys(
                left = if (softkeys.typing) null else if (selectedId != null) {
                    SoftkeyAction("Message") { actionMessageId = selectedId }
                } else {
                    SoftkeyAction("Options") { showOptions = true }
                },
                center = when {
                    softkeys.typing && draft.isNotBlank() -> SoftkeyAction(if (editing != null) "Save" else "Send") { send() }
                    softkeys.typing -> SoftkeyAction("")
                    else -> SoftkeyAction("Type") { runCatching { inputFocus.requestFocus() } }
                },
                right = SoftkeyAction(if (replyTo != null || editing != null) "Cancel" else "Back") {
                    when {
                        replyTo != null -> replyTo = null
                        editing != null -> { editing = null; draft = "" }
                        softkeys.typing -> runCatching { messageFocus.requestFocus() }
                        else -> navigator.pop()
                    }
                },
            )
            Column(Modifier.fillMaxSize()) {
                Header(conversation.title, trailing = { Pill(conversation.serviceId.label) })
                Box(Modifier.weight(1f)) {
                    when {
                        messages == null && error != null -> CenteredMessage(error!!, color = Palette.error)
                        messages == null -> CenteredMessage("Loading…")
                        messages!!.isEmpty() -> CenteredMessage("No messages yet. Press Type to say hello.")
                        else -> MessageList(
                            conversation, messages!!, hasMore, listState, selectedId, messageFocus,
                            onSelect = { selectedId = it.id },
                            onOpen = { actionMessageId = it.id },
                            onLoadOlder = { scope.launch { load(older = true) } },
                        )
                    }
                }
                if (conversation.typingNames.isNotEmpty()) {
                    BasicText(
                        conversation.typingNames.joinToString() + " typing…",
                        style = Type.small.copy(color = Palette.sender),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
                    )
                }
                val banner = editing?.let { "Editing message" } ?: replyTo?.let { "Replying to ${it.sender ?: conversation.title}: ${messageSummary(it)}" }
                if (banner != null) {
                    BasicText(
                        banner,
                        style = Type.small.copy(color = Palette.muted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().background(Palette.panel).padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextInput(
                        draft,
                        onValueChange = {
                            if (draft.isEmpty() && it.isNotEmpty()) scope.launch { runCatching { service.setTyping(conversation, true) } }
                            draft = it
                        },
                        placeholder = "Message",
                        focusRequester = inputFocus,
                        modifier = Modifier.weight(1f),
                        onDone = ::send,
                    )
                    Box(
                        Modifier
                            .size(32.dp)
                            .focusCard(::send, RoundedCornerShape(5.dp), SolidColor(Palette.blue)),
                        contentAlignment = Alignment.Center,
                    ) { BasicText("➤", style = Type.body.copy(fontSize = 14.sp)) }
                }
            }
        }
    }
}

@Composable
private fun MessageList(
    conversation: Conversation,
    messages: List<Message>,
    hasMore: Boolean,
    listState: LazyListState,
    selectedId: String?,
    selectedFocus: FocusRequester,
    onSelect: (Message) -> Unit,
    onOpen: (Message) -> Unit,
    onLoadOlder: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val time = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val day = remember { DateTimeFormatter.ofPattern("EEE, MMM d") }
    val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.88f).dp
    LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp), modifier = Modifier.fillMaxSize()) {
        if (hasMore) {
            item(key = "older") {
                Box(Modifier.fillMaxWidth().padding(4.dp), contentAlignment = Alignment.Center) {
                    BasicText(
                        "Load older messages",
                        style = Type.small.copy(fontSize = 11.sp),
                        modifier = Modifier
                            .focusCard(onLoadOlder, RoundedCornerShape(12.dp), SolidColor(Palette.panel), idleBorder = Palette.line)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
            val previous = messages.getOrNull(index - 1)
            val date = Instant.ofEpochMilli(message.sentAt).atZone(zone).toLocalDate()
            val previousDate = previous?.let { Instant.ofEpochMilli(it.sentAt).atZone(zone).toLocalDate() }
            Column {
                if (date != previousDate) DayDivider(if (date == LocalDate.now(zone)) "Today" else day.format(date))
                val showSender = conversation.kind == ConversationKind.GROUP &&
                    message.direction == Direction.INCOMING &&
                    (previous?.sender != message.sender || date != previousDate)
                if (showSender) BasicText(message.sender.orEmpty(), style = Type.small.copy(fontSize = 11.sp, color = Palette.sender), modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                Bubble(
                    message,
                    time.format(Instant.ofEpochMilli(message.sentAt).atZone(zone)),
                    maxBubble,
                    focusRequester = if (message.id == selectedId) selectedFocus else null,
                    onFocus = { onSelect(message) },
                    onOpen = { onOpen(message) },
                )
            }
        }
    }
}

@Composable
private fun DayDivider(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Palette.line))
        BasicText(
            label,
            style = Type.small.copy(color = Palette.muted, fontSize = 11.sp),
            modifier = Modifier.padding(horizontal = 6.dp).background(Palette.panel, RoundedCornerShape(9.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(Palette.line))
    }
}

@Composable
private fun Bubble(
    message: Message,
    time: String,
    maxWidth: androidx.compose.ui.unit.Dp,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onOpen: () -> Unit,
) {
    if (message.direction == Direction.SYSTEM) {
        CenteredMessage(message.text.orEmpty(), Modifier.padding(vertical = 2.dp))
        return
    }
    val outgoing = message.direction == Direction.OUTGOING
    Box(Modifier.fillMaxWidth(), contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .padding(vertical = 2.dp)
                .widthIn(max = maxWidth)
                .focusCard(onOpen, RoundedCornerShape(8.dp), SolidColor(if (outgoing) Palette.outgoing else Palette.incoming), focusRequester, onFocus)
                .padding(horizontal = 7.dp, vertical = 5.dp),
        ) {
            message.forwardedFrom?.let { BasicText("Forwarded from $it", style = Type.small.copy(color = Palette.muted)) }
            message.quote?.let { quote ->
                Column(
                    Modifier
                        .padding(bottom = 3.dp)
                        .background(Color(0x33000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    BasicText(quote.author, style = Type.small.copy(color = Palette.sender, fontWeight = FontWeight.Bold))
                    BasicText(quote.text ?: "Message", style = Type.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            val italic = message.deleted
            BasicText(
                if (message.deleted) "This message was deleted" else messageSummary(message),
                style = Type.body.copy(
                    color = if (italic) Palette.muted else Palette.text,
                    fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                ),
            )
            if (message.reactions.isNotEmpty()) {
                BasicText(
                    message.reactions.groupBy { it.emoji }.entries.joinToString(" ") { (emoji, list) -> if (list.size > 1) "$emoji${list.size}" else emoji },
                    style = Type.body,
                    modifier = Modifier.padding(top = 2.dp).background(Color(0x33000000), RoundedCornerShape(9.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            val meta = buildString {
                if (message.pinned) append("📌 ")
                if (message.edited && !message.deleted) append("edited · ")
                append(time)
                if (outgoing) append(receiptMark(message.receipt))
            }
            BasicText(meta, style = Type.small.copy(color = Palette.time, textAlign = TextAlign.End), modifier = Modifier.align(Alignment.End))
        }
    }
}

private fun receiptMark(receipt: Receipt?) = when (receipt?.state) {
    null -> " ⏱"
    Receipt.State.SENT -> " ✓"
    Receipt.State.DELIVERED -> " ✓✓"
    Receipt.State.READ -> " ✓✓ read"
}

@Composable
private fun MessageActions(
    conversation: Conversation,
    message: Message,
    capabilities: Capabilities,
    onClose: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
) {
    val context = LocalContext.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Back", onClose))
    val outgoing = message.direction == Direction.OUTGOING
    val canReact = capabilities.reactions && !message.deleted
    Column(Modifier.fillMaxSize()) {
        Header("Message")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(6.dp)).padding(7.dp)) {
                BasicText(if (outgoing) "You" else message.sender ?: conversation.title, style = Type.body.copy(fontWeight = FontWeight.Bold))
                BasicText(messageSummary(message), style = Type.small.copy(fontSize = 11.sp, color = Palette.time), maxLines = 3, overflow = TextOverflow.Ellipsis)
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
                                        { onReact(emoji) }, RoundedCornerShape(6.dp), Palette.tile,
                                        if (row == 0 && column == 0) requester else null,
                                        idleBorder = if (own) Palette.focus.copy(alpha = 0.5f) else Palette.tileBorder,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) { BasicText(emoji, style = Type.body.copy(fontSize = 18.sp)) }
                        }
                    }
                }
            }
            val actions = buildList {
                if (!message.deleted) add(MenuItem("Reply", AppIcon.REPLY, onReply))
                if (outgoing && capabilities.edits && !message.deleted && message.text != null) add(MenuItem("Edit", AppIcon.EDIT, onEdit))
                if (capabilities.pins && !message.deleted) add(MenuItem(if (message.pinned) "Unpin" else "Pin", AppIcon.PIN, onPin))
                if (message.text != null) add(MenuItem("Copy", AppIcon.COMPOSE) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("message", message.text))
                    onClose()
                })
                if (capabilities.deletes && outgoing && !message.deleted) add(MenuItem("Delete", AppIcon.DELETE, onDelete))
            }
            MenuGrid(actions, if (canReact) null else requester)
            val details = buildList {
                message.reactions.forEach { add("${it.author}: ${it.emoji}") }
                message.receipt?.let { add("Status: ${it.state.name.lowercase()}") }
                message.receipt?.readBy?.forEach { add("${it.name}: ${it.status.name.lowercase()}") }
            }
            if (details.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Palette.tileBorder, RoundedCornerShape(7.dp))
                        .padding(7.dp),
                ) {
                    BasicText("Message details", style = Type.body.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp))
                    details.forEach { BasicText(it, style = Type.small.copy(fontSize = 11.sp)) }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ChatOptions(conversation: Conversation, onClose: () -> Unit, onUpdate: (ConversationUpdate) -> Unit) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Back", onClose))
    Column(Modifier.fillMaxSize()) {
        Header(conversation.title)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
            MenuGrid(
                listOf(
                    MenuItem(if (conversation.isFavourite) "Unstar" else "Star", AppIcon.STAR) { onUpdate(ConversationUpdate(favourite = !conversation.isFavourite)) },
                    MenuItem(if (conversation.isMuted) "Unmute" else "Mute", AppIcon.MUTE) { onUpdate(ConversationUpdate(muted = !conversation.isMuted)) },
                    MenuItem(if (conversation.isArchived) "Unarchive" else "Archive", AppIcon.ARCHIVE) { onUpdate(ConversationUpdate(archived = !conversation.isArchived)) },
                ),
                requester,
            )
            if (conversation.kind == ConversationKind.GROUP && conversation.members.isNotEmpty()) {
                BasicText("MEMBERS", style = Type.section, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                conversation.members.forEach { BasicText(it.name, style = Type.body, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
    }
}
