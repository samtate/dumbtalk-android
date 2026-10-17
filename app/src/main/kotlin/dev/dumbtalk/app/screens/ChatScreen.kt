package dev.dumbtalk.app.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import dev.dumbtalk.app.ui.LocalControlsEnabled
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dumbtalk.app.DumbTalkApp
import dev.dumbtalk.app.Navigator
import dev.dumbtalk.app.Screen
import dev.dumbtalk.app.ChatTool
import dev.dumbtalk.core.SearchResult
import androidx.compose.runtime.saveable.rememberSaveable
import dev.dumbtalk.app.media.AudioPlayer
import dev.dumbtalk.app.ui.AppIcon
import dev.dumbtalk.app.ui.CenteredMessage
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.Icon
import dev.dumbtalk.app.ui.focusCard
import dev.dumbtalk.app.ui.LocalSoftkeys
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.ServiceIcon
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.app.ui.messageSummary
import dev.dumbtalk.core.AttachmentKind
import dev.dumbtalk.core.Capabilities
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.Message
import dev.dumbtalk.core.ServiceEvent
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(app: DumbTalkApp, navigator: Navigator, screen: Screen.Chat) {
    val service = app.registry.forConversation(screen.initial)
    val scope = rememberCoroutineScope()
    val softkeys = LocalSoftkeys.current
    val context = LocalContext.current

    // Follow live conversation changes (mute, typing, …) from the inbox model.
    app.inbox.active.collectAsStateWithLifecycle().value
    app.inbox.archived.collectAsStateWithLifecycle().value
    val conversation = app.inbox.find(screen.conversationId) ?: screen.initial
    val avatarPath by produceState(conversation.avatarPath, conversation.id) {
        value = runCatching { service.conversationAvatar(conversation) }.getOrNull() ?: conversation.avatarPath
    }
    val latest by rememberUpdatedState(conversation)
    val model = remember(screen.conversationId) {
        ChatModel(service, { latest }, scope, initialDraft = app.drafts[screen.conversationId].orEmpty())
    }
    DisposableEffect(model) {
        onDispose {
            val text = model.draft
            if (text.isBlank()) app.drafts.remove(screen.conversationId) else app.drafts[screen.conversationId] = text
        }
    }
    val capabilities by produceState(Capabilities(), service) {
        value = runCatching { service.capabilities() }.getOrDefault(Capabilities())
    }
    val media = rememberMediaController(model, capabilities.voiceNoteMime)
    LaunchedEffect(capabilities) { model.mentionsEnabled = capabilities.mentions }

    /** The message under the cursor; null while focus is in the composer (so Options shows). */
    var selectedId by remember { mutableStateOf<String?>(null) }
    /** The last message focused, where focus returns to when leaving the composer. */
    var lastMessageId by remember { mutableStateOf<String?>(null) }
    var composeControl by remember { mutableStateOf<ComposeControl?>(null) }
    var actionMessageId by remember { mutableStateOf<String?>(null) }
    var showOptions by remember(screen.conversationId, screen.showOptions) { mutableStateOf(screen.showOptions) }
    var showPollCreator by remember { mutableStateOf(false) }
    var votingPoll by remember { mutableStateOf<Message?>(null) }
    var tool by remember { mutableStateOf<ChatTool?>(null) }
    var returnTool by remember { mutableStateOf<ChatTool?>(null) }
    var toolQuery by rememberSaveable { mutableStateOf("") }
    var toolSelection by remember { mutableStateOf<String?>(null) }
    var pendingJump by remember { mutableStateOf(screen.target) }
    var findingMessage by remember { mutableStateOf(screen.target != null) }
    var showDeleteThread by remember { mutableStateOf(false) }
    var deletingMessage by remember { mutableStateOf<Message?>(null) }
    var closingPoll by remember { mutableStateOf<Message?>(null) }
    var emojiFor by remember { mutableStateOf<EmojiUse?>(null) }
    var forwarding by remember { mutableStateOf<Message?>(null) }
    var showTimer by remember { mutableStateOf(false) }
    var groupPanel by remember { mutableStateOf<GroupPanel?>(null) }
    var confirmBlock by remember { mutableStateOf(false) }
    /** Focus is on the request or blocked bar in place of the composer. */
    var barFocused by remember { mutableStateOf(false) }
    /** Messages whose spoilers are shown. */
    val revealed = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val mentionFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val messageFocus = remember { FocusRequester() }
    val messageFocusById = remember { mutableStateMapOf<String, FocusRequester>() }
    val inputFocus = remember { FocusRequester() }
    val bottomFocus = remember { FocusRequester() }
    var bottomRequested by remember { mutableStateOf(false) }
    var bottomFocused by remember { mutableStateOf(false) }
    val controls = remember { ComposeControl.entries.associateWith { FocusRequester() } }
    val anchors = remember { mutableStateMapOf<ComposeControl, Rect>() }
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    val popover = when {
        emojiFor != null -> ComposeControl.EMOJI
        media.overlay == MediaOverlay.Attach -> ComposeControl.ATTACH
        media.overlay == MediaOverlay.Voice -> ComposeControl.VOICE
        else -> null
    }
    var returnControl by remember { mutableStateOf<ComposeControl?>(null) }
    var optionsOriginFocus by remember { mutableStateOf<FocusRequester?>(null) }
    var returnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    fun closePopover() {
        if (media.overlay == MediaOverlay.Voice) media.cancelVoice() else media.closeOverlay()
        emojiFor = null
    }
    var loaded by remember { mutableStateOf(false) }
    /** The newest message is off screen by at least a screen's height (estimated from the rows in view). */
    val farFromBottom by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val visible = layout.visibleItemsInfo
            val last = visible.lastOrNull() ?: return@derivedStateOf false
            if (last.index >= layout.totalItemsCount - 1) return@derivedStateOf false
            val viewport = layout.viewportEndOffset - layout.viewportStartOffset
            val average = visible.sumOf { it.size } / visible.size
            val below = (last.offset + last.size - layout.viewportEndOffset).coerceAtLeast(0) +
                (layout.totalItemsCount - 1 - last.index) * average
            below >= viewport
        }
    }

    fun focusInput() = scope.launch { withFrameNanos { }; runCatching { inputFocus.requestFocus() } }
    fun send() = model.send { listState.animateScrollToItem(model.lastIndex) }
    fun returnToBottom() {
        scope.launch {
            lastMessageId = model.messages?.lastOrNull { it.direction != dev.dumbtalk.core.Direction.SYSTEM }?.id
            listState.scrollToItem(model.lastIndex)
            withFrameNanos { }
            // A tall final photo/video can exceed the viewport. Scroll to its
            // end too, rather than only bringing its top into view.
            val layout = listState.layoutInfo
            layout.visibleItemsInfo.lastOrNull()?.let { last ->
                val remaining = last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
                if (remaining > 0) listState.scrollBy(remaining.toFloat())
            }
            inputFocus.requestFocus()
            bottomRequested = false
        }
    }
    fun openChatOptions() {
        when {
            bottomFocused -> { returnControl = null; returnFocus = bottomFocus }
            softkeys.typing -> {
                returnControl = null
                returnFocus = inputFocus
                optionsOriginFocus = inputFocus
                composeControl = ComposeControl.CHAT_OPTIONS
            }
            composeControl == ComposeControl.CHAT_OPTIONS -> {
                returnControl = null
                returnFocus = optionsOriginFocus
            }
            composeControl != null -> {
                returnControl = null
                returnFocus = controls[composeControl]
            }
            else -> { returnControl = null; returnFocus = null }
        }
        showOptions = true
    }

    DisposableEffect(screen.conversationId) {
        if (!screen.showOptions) scope.launch { runCatching { service.openConversation(conversation) } }
        onDispose { if (!screen.showOptions) app.scope.launch { runCatching { service.closeConversation(conversation) } } }
    }

    // Unread messages are read once the newest is on screen.
    LaunchedEffect(model) {
        snapshotFlow { loaded && !listState.canScrollForward }
            .collect { model.viewingNewest = it }
    }

    LaunchedEffect(screen.conversationId) {
        // Opening chat options from the inbox must not open/mark-read the conversation.
        if (screen.showOptions) return@LaunchedEffect
        val unread = conversation.unreadCount
        model.load()
        // With unread messages, open at the last one read, the unread ones below it.
        val firstUnread = if (pendingJump == null) model.firstUnread(unread) else null
        val shown = model.messages.orEmpty()
        val unreadIndex = firstUnread?.let { target -> shown.indexOfFirst { it.id == target.id } } ?: -1
        val lastRead = if (unreadIndex >= 0) shown.subList(0, unreadIndex).lastOrNull { it.direction != dev.dumbtalk.core.Direction.SYSTEM } else null
        selectedId = (lastRead ?: firstUnread ?: shown.lastOrNull())?.id
        lastMessageId = selectedId
        if (pendingJump == null) {
            if (unreadIndex >= 0) listState.scrollToItem((unreadIndex - 1).coerceAtLeast(0) + if (model.hasMore) 1 else 0)
            else listState.scrollToItem(model.lastIndex)
        }
        loaded = true
        if (selectedId == null) focusInput() // an empty chat: straight to the message box
        service.events
            .filter { it is ServiceEvent.MessagesChanged && it.conversationId == screen.conversationId }
            // Keep up with busy services (TDLib is chatty) and merge bursts into one reload.
            .conflate()
            .collect {
                val atBottom = !listState.canScrollForward
                model.load()
                if (atBottom) listState.animateScrollToItem(model.lastIndex)
            }
    }

    // Focus returns to the selected message when a panel closes.
    LaunchedEffect(loaded, actionMessageId, showOptions, showPollCreator, votingPoll, closingPoll, media.overlay, emojiFor, forwarding, tool, pendingJump, showDeleteThread, deletingMessage, showTimer, groupPanel, confirmBlock) {
        if (!loaded || showDeleteThread || deletingMessage != null || tool != null || pendingJump != null || actionMessageId != null || showOptions || showPollCreator || votingPoll != null || closingPoll != null || media.overlay != null || emojiFor != null || forwarding != null || showTimer || groupPanel != null || confirmBlock) return@LaunchedEffect
        withFrameNanos { }
        val control = returnControl
        returnControl = null
        val focus = returnFocus
        returnFocus = null
        when {
            focus != null -> runCatching { focus.requestFocus() }
            control != null -> runCatching { controls.getValue(control).requestFocus() }
            lastMessageId != null -> runCatching { messageFocus.requestFocus() }
        }
    }

    LaunchedEffect(loaded, pendingJump) {
        val target = pendingJump ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        findingMessage = true
        try {
            model.revealMessage(target.id, target.sentAt)
            val targetIndex = model.messages.orEmpty().indexOfFirst { it.id == target.id }
            selectedId = target.id
            lastMessageId = target.id
            actionMessageId = null
            showOptions = false
            returnTool = tool
            tool = null
            returnFocus = null
            returnControl = null
            findingMessage = false
            withFrameNanos { }
            listState.requestScrollToItem(targetIndex + if (model.hasMore) 1 else 0)
            val requester = snapshotFlow {
                if (listState.layoutInfo.visibleItemsInfo.any { it.key == target.id }) messageFocusById[target.id] else null
            }.first { it != null }!!
            withFrameNanos { }
            requester.requestFocus()
            pendingJump = null
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            findingMessage = false
            pendingJump = null
            Toast.makeText(context, failure.message ?: "Could not find this message", Toast.LENGTH_LONG).show()
        }
    }

    val messages = model.messages.orEmpty()
    val actionMessage = messages.firstOrNull { it.id == actionMessageId }
    var actionFocusLabel by remember(actionMessageId) { mutableStateOf<String?>(null) }
    var optionFocusLabel by remember(showOptions) { mutableStateOf<String?>(null) }

    // The centre key does what the focused message is for.
    fun quoteTarget(message: Message) = message.quote?.messageId?.takeIf { id -> messages.any { it.id == id } }
    fun centerLabel(message: Message): String {
        val kind = message.attachments.firstOrNull()?.kind
        return when {
            message.deleted -> "Actions"
            message.poll?.let { !it.closed } == true -> if (message.direction == dev.dumbtalk.core.Direction.OUTGOING) "Actions" else "Vote"
            message.poll != null -> "Actions"
            kind == AttachmentKind.AUDIO -> if (AudioPlayer.playing == message.id) "Stop" else "Play"
            message.visuals().size > 1 -> "View photos"
            kind == AttachmentKind.IMAGE -> "View photo"
            kind == AttachmentKind.VIDEO -> "Play video"
            kind == AttachmentKind.FILE -> "Open"
            quoteTarget(message) != null -> "Goto Quote"
            else -> "Actions"
        }
    }
    fun jumpTo(id: String) {
        val index = messages.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        selectedId = id
        lastMessageId = id
        scope.launch {
            listState.scrollToItem(index + if (model.hasMore) 1 else 0)
            withFrameNanos { }
            withFrameNanos { }
            runCatching { messageFocus.requestFocus() }
        }
    }
    fun activate(message: Message) {
        val kind = message.attachments.firstOrNull()?.kind
        when {
            message.deleted -> actionMessageId = message.id
            message.poll?.let { !it.closed } == true -> {
                if (message.direction == dev.dumbtalk.core.Direction.OUTGOING) actionMessageId = message.id
                else votingPoll = message
            }
            message.poll != null -> actionMessageId = message.id
            kind == AttachmentKind.AUDIO -> media.togglePlay(message)
            kind == AttachmentKind.IMAGE || kind == AttachmentKind.VIDEO -> media.open(message)
            kind == AttachmentKind.FILE -> media.openExternally(message)
            quoteTarget(message) != null -> jumpTo(quoteTarget(message)!!)
            else -> actionMessageId = message.id
        }
    }

    fun mediaAction(message: Message): MenuItem? = when (message.attachments.firstOrNull()?.kind) {
        AttachmentKind.IMAGE -> MenuItem("View", AppIcon.SEARCH) { media.open(message) }
        AttachmentKind.VIDEO -> MenuItem(if (message.visuals().size > 1) "View" else "Play", AppIcon.MIC) { media.open(message) }
        AttachmentKind.AUDIO -> MenuItem(if (AudioPlayer.playing == message.id) "Stop" else "Play", AppIcon.MIC) { media.togglePlay(message) }
        AttachmentKind.FILE -> MenuItem("Open", AppIcon.ATTACH) { media.openExternally(message) }
        null -> null
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { screenOrigin = it.boundsInRoot().topLeft }) {
    when {
        showDeleteThread -> DeleteConfirmation("Delete SMS thread?", "Delete all SMS messages with ${conversation.title} from this phone? This cannot be undone.",
            onDelete = { service.deleteConversation(conversation) }, onDeleted = { navigator.popToRoot() }, onCancel = { showDeleteThread = false })
        deletingMessage != null -> DeleteConfirmation("Delete message?", "Delete this SMS from this phone? This cannot be undone.",
            onDelete = { service.deleteMessage(conversation, deletingMessage!!) },
            onDeleted = {
                val deletedId = deletingMessage!!.id
                val current = model.messages.orEmpty()
                val deletedIndex = current.indexOfFirst { it.id == deletedId }
                val neighbor = current.getOrNull(deletedIndex + 1) ?: current.getOrNull(deletedIndex - 1)
                deletingMessage = null
                actionMessageId = null
                selectedId = neighbor?.id
                lastMessageId = neighbor?.id
                scope.launch { model.load() }
            },
            onCancel = { deletingMessage = null })
        findingMessage -> {
            Softkeys(right = SoftkeyAction("Cancel") {
                pendingJump = null
                findingMessage = false
                if (screen.target != null) navigator.pop()
            })
            Column(Modifier.fillMaxSize()) { Header(conversation.title); CenteredMessage("Finding message…") }
        }
        tool != null -> ChatToolsPanel(app, conversation, tool!!, toolQuery,
            onQueryChange = { toolQuery = it; toolSelection = null }, selectedResult = toolSelection,
            onResultFocus = { toolSelection = it },
            onPick = { toolSelection = it.id; pendingJump = it; findingMessage = true },
            onClose = { tool = null; showOptions = true; returnTool = null },
        )
        forwarding != null -> ForwardPanel(
            app, conversation,
            onPick = { target ->
                val message = forwarding!!
                forwarding = null
                actionMessageId = null
                model.forward(message, target, app.registry.forConversation(target)) { problem ->
                    Toast.makeText(context, problem?.let { "Not forwarded: $it" } ?: "Forwarded to ${target.title}", Toast.LENGTH_SHORT).show()
                }
            },
            onClose = { forwarding = null },
        )

        media.overlay is MediaOverlay.Photo || media.overlay is MediaOverlay.Video -> MediaOverlays(media, caption = model.draft)

        showTimer -> TimerPicker(
            conversation.expiration, capabilities.disappearingDurations,
            onPick = { seconds -> model.update(dev.dumbtalk.core.ConversationUpdate(expiration = seconds)); showTimer = false; showOptions = false },
            onClose = { showTimer = false },
        )

        confirmBlock -> Confirmation(
            "Block ${conversation.title}?", "They won't be able to message or call you here, and won't be told.",
            keep = "Cancel", act = "Block", icon = AppIcon.BLOCK,
            onConfirm = { model.setBlocked(true); confirmBlock = false; showOptions = false },
            onCancel = { confirmBlock = false },
        )

        groupPanel != null -> when (val panel = groupPanel!!) {
            GroupPanel.Info -> GroupInfoPanel(
                conversation, model,
                onRename = { groupPanel = GroupPanel.Rename },
                onAdd = { groupPanel = GroupPanel.Add },
                onRemove = { groupPanel = GroupPanel.Remove(it) },
                onLeave = { groupPanel = GroupPanel.Leave },
                onClose = { groupPanel = null; showOptions = true },
            )
            GroupPanel.Rename -> NameInput("Rename group", conversation.title, "Save",
                onSave = { model.renameGroup(it); groupPanel = GroupPanel.Info }, onClose = { groupPanel = GroupPanel.Info })
            GroupPanel.Add -> ContactPicker("Add people", service, "Add",
                onDone = { picked -> model.addMembers(picked.map { it.id }); groupPanel = GroupPanel.Info },
                onClose = { groupPanel = GroupPanel.Info },
                // Signal's contacts are chats ("c:<id>"), its members bare ids.
                exclude = model.members.orEmpty().flatMap { listOf(it.id, "c:${it.id}") }.toSet())
            is GroupPanel.Remove -> Confirmation(
                "Remove ${panel.member.name}?", "${panel.member.name} will be removed from ${conversation.title}.",
                keep = "Keep", act = "Remove", icon = AppIcon.DELETE,
                onConfirm = { model.removeMember(panel.member); groupPanel = GroupPanel.Info },
                onCancel = { groupPanel = GroupPanel.Info },
            )
            GroupPanel.Leave -> Confirmation(
                "Leave group?", "You'll stop getting messages from ${conversation.title}. The chat moves to Archived.",
                keep = "Stay", act = "Leave", icon = AppIcon.CLOSE,
                onConfirm = { model.leaveGroup { navigator.popToRoot() }; groupPanel = null },
                onCancel = { groupPanel = GroupPanel.Info },
            )
        }

        actionMessage != null && !showOptions && closingPoll == null -> MessageActions(
            conversation, actionMessage, capabilities,
            focusedLabel = actionFocusLabel,
            onFocused = { actionFocusLabel = it },
            onClose = { actionMessageId = null },
            onRetry = { model.retry(actionMessage); actionMessageId = null },
            onReact = { emoji -> model.toggleReaction(actionMessage, emoji); actionMessageId = null },
            onReply = { model.startReply(actionMessage); returnFocus = inputFocus; actionMessageId = null },
            onEdit = { model.startEdit(actionMessage); returnFocus = inputFocus; actionMessageId = null },
            onDelete = { if (conversation.serviceId == dev.dumbtalk.core.ServiceId.SMS) deletingMessage = actionMessage else { model.delete(actionMessage); actionMessageId = null } },
            onPin = { model.togglePin(actionMessage); actionMessageId = null },
            onMoreReactions = { emojiFor = EmojiUse.React(actionMessage); actionMessageId = null },
            onForward = { forwarding = actionMessage },
            onChatOptions = { openChatOptions() },
            quote = quoteTarget(actionMessage)?.let { id -> MenuItem("Goto Quote", AppIcon.REPLY) { actionMessageId = null; jumpTo(id) } },
            media = mediaAction(actionMessage)?.let { item -> MenuItem(item.label, item.icon) { actionMessageId = null; item.onClick() } },
            spoiler = if (dev.dumbtalk.app.ui.hasSpoilers(actionMessage)) {
                if (actionMessage.id in revealed) MenuItem("Hide spoiler", AppIcon.CLOSE) { revealed.remove(actionMessage.id); actionMessageId = null }
                else MenuItem("Show spoiler", AppIcon.SEARCH) { revealed.add(actionMessage.id); actionMessageId = null }
            } else null,
            pollAction = actionMessage.poll?.takeIf { capabilities.polls && !it.closed }?.let {
                if (actionMessage.direction == dev.dumbtalk.core.Direction.OUTGOING && capabilities.closingPolls) {
                    MenuItem("Close poll", AppIcon.POLL) { closingPoll = actionMessage }
                } else {
                    MenuItem("Vote", AppIcon.POLL) { votingPoll = actionMessage; actionMessageId = null }
                }
            },
        )

        showPollCreator -> PollCreator(
            onCreate = { question, options, multiple ->
                model.createPoll(question, options, multiple)
                showPollCreator = false
                showOptions = false
                if (screen.showOptions) navigator.pop()
            },
            onClose = { showPollCreator = false },
        )

        votingPoll != null -> PollVotePanel(
            votingPoll!!,
            onVote = { options -> model.votePoll(votingPoll!!, options); votingPoll = null },
            onClose = { votingPoll = null },
        )

        closingPoll != null -> PollCloseConfirmation(
            closingPoll!!,
            onConfirm = { model.closePoll(closingPoll!!); closingPoll = null; actionMessageId = null },
            onCancel = { closingPoll = null },
        )

        showOptions -> ChatOptions(
            conversation,
            focusedLabel = optionFocusLabel,
            onFocused = { optionFocusLabel = it },
            polls = capabilities.polls && (capabilities.directPolls || conversation.kind == dev.dumbtalk.core.ConversationKind.GROUP || conversation.isNoteToSelf),
            onClose = { if (screen.showOptions) navigator.pop() else showOptions = false },
            onUpdate = { update ->
                model.update(update)
                showOptions = false
                if (screen.showOptions) {
                    navigator.pop()
                    if (update.archived == true) navigator.pop()
                } else if (update.archived == true) navigator.pop()
            },
            onCreatePoll = { showPollCreator = true },
            search = capabilities.search, pins = capabilities.pins,
            onSearch = { tool = ChatTool.SEARCH },
            onPins = { tool = ChatTool.PINS },
            deleteThread = capabilities.deleteConversations, onDeleteThread = { showDeleteThread = true },
            extra = buildList {
                val group = conversation.kind == dev.dumbtalk.core.ConversationKind.GROUP
                if (capabilities.disappearingMessages && !conversation.isMessageRequest) {
                    add(MenuItem("Disappearing: ${dev.dumbtalk.core.timerLabel(conversation.expiration)}", AppIcon.TIMER) { showTimer = true })
                }
                if (group && capabilities.groups) add(MenuItem("Group info", AppIcon.GROUP) { groupPanel = GroupPanel.Info })
                if (!group && !conversation.isNoteToSelf && capabilities.blocking) {
                    if (conversation.isBlocked) add(MenuItem("Unblock", AppIcon.BLOCK) { model.setBlocked(false); showOptions = false })
                    else add(MenuItem("Block", AppIcon.BLOCK) { confirmBlock = true })
                }
            },
        )

        else -> {
            val selected = messages.firstOrNull { it.id == selectedId }
            if (popover == null) Softkeys(
                left = when {
                    composeControl == null && !softkeys.typing && selectedId != null ->
                        SoftkeyAction("Actions") { actionMessageId = selectedId }
                    else -> SoftkeyAction(
                        label = "Chat",
                        onPress = ::openChatOptions,
                        focusRequester = controls.getValue(ComposeControl.CHAT_OPTIONS),
                        focusable = softkeys.typing || composeControl != null,
                        upFocusRequester = optionsOriginFocus ?: inputFocus,
                    ).apply {
                        onFocused = {
                            if (composeControl != ComposeControl.CHAT_OPTIONS) {
                                optionsOriginFocus = when (composeControl) {
                                    null -> inputFocus
                                    else -> controls[composeControl]
                                }
                            }
                            composeControl = ComposeControl.CHAT_OPTIONS
                        }
                    }
                },
                center = when {
                    bottomFocused -> SoftkeyAction("Bottom")
                    barFocused -> SoftkeyAction("Select")
                    softkeys.typing && model.canSend -> SoftkeyAction(if (model.editing != null) "Save" else "Send") { send() }
                    softkeys.typing -> SoftkeyAction("Type")
                    // A focused composer button handles the key itself; this is its label.
                    composeControl == ComposeControl.SEND -> SoftkeyAction(if (model.editing != null) "Save" else "Send")
                    composeControl != null -> SoftkeyAction(composeControl!!.label)
                    selected != null -> SoftkeyAction(centerLabel(selected)) { activate(selected) }
                    else -> SoftkeyAction("Type") { runCatching { inputFocus.requestFocus() } }
                },
                    right = SoftkeyAction(
                        if (model.replyTo != null || model.editing != null) "Cancel" else if ((softkeys.typing || composeControl != null || bottomFocused) && lastMessageId != null) "Messages" else "Back",
                    ) {
                    when {
                        model.cancelCompose() -> Unit
                        (softkeys.typing || composeControl != null || bottomFocused) && lastMessageId != null -> runCatching { messageFocus.requestFocus() }
                        returnTool != null -> { tool = returnTool; returnTool = null }
                        else -> navigator.pop()
                    }
                },
            )
            CompositionLocalProvider(LocalControlsEnabled provides (popover == null)) {
            Column(Modifier.fillMaxSize()) {
                Header(conversation.title, leading = { dev.dumbtalk.app.ui.Avatar(conversation.title, size = 24.dp, noteToSelf = conversation.isNoteToSelf, imagePath = avatarPath) }, trailing = {
                    // A timer shows the chat's messages disappear.
                    if (conversation.expiration > 0) Icon(AppIcon.TIMER, size = 14.dp, color = Palette.muted, modifier = Modifier.padding(end = 5.dp))
                    ServiceIcon(conversation.serviceId, 18.dp)
                })
                Box(Modifier.weight(1f)) {
                    val shown = model.messages
                    when {
                        shown == null -> CenteredMessage(model.loadError ?: "Loading…", color = if (model.loadError != null) Palette.error else Palette.muted)
                        shown.isEmpty() -> CenteredMessage("No messages yet.")
                        else -> MessageList(
                            conversation, shown, model.hasMore, listState, lastMessageId, messageFocus,
                            onSelect = { selectedId = it.id; lastMessageId = it.id; composeControl = null; bottomRequested = false },
                            onOpen = { actionMessageId = it.id },
                            onLoadOlder = {
                                scope.launch {
                                    val target = model.load(older = true) ?: model.messages?.firstOrNull() ?: return@launch
                                    selectedId = target.id
                                    lastMessageId = target.id
                                    val targetIndex = model.messages.orEmpty().indexOfFirst { it.id == target.id }
                                    if (targetIndex < 0) return@launch
                                    val lazyIndex = targetIndex + if (model.hasMore) 1 else 0
                                    // Prepended rows change LazyColumn's indices. A scrollToItem
                                    // here can run against the old layout; its keyed anchor then
                                    // restores the old position on the next measure. Request the
                                    // position for that measure instead.
                                    listState.requestScrollToItem(lazyIndex)

                                    // The target must be both measured and attached before focus
                                    // can move to it. Waiting a fixed number of frames can finish
                                    // before a large page has been composed.
                                    val requester = snapshotFlow {
                                        if (listState.layoutInfo.visibleItemsInfo.any { it.key == target.id })
                                            messageFocusById[target.id]
                                        else null
                                    }.first { it != null } ?: return@launch
                                    withFrameNanos { }
                                    repeat(3) {
                                        if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@launch
                                        withFrameNanos { }
                                    }
                                }
                            },
                            loadAttachment = { message, attachment -> model.attachmentPath(message, attachment) },
                            loadThumbnail = model::thumbnailPath,
                            belowLast = inputFocus,
                            focusByMessageId = messageFocusById,
                            onReply = { message -> if (capabilities.replies) model.startReply(message); focusInput() },
                            onRight = {
                                bottomRequested = true
                                scope.launch { withFrameNanos { }; bottomFocus.requestFocus() }
                            },
                            revealed = revealed.toSet(),
                            loadAvatar = service::personAvatar,
                        )
                    }
                    if (!model.messages.isNullOrEmpty() && (farFromBottom || bottomRequested)) {
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(8.dp).size(32.dp)
                                .onFocusChanged {
                                    bottomFocused = it.isFocused
                                    if (it.isFocused) { selectedId = null; composeControl = null }
                                }
                                .focusProperties { left = messageFocus; up = messageFocus; down = inputFocus }
                                .focusCard(
                                    ::returnToBottom, CircleShape,
                                    SolidColor(if (bottomFocused) Palette.focus else Palette.panel),
                                    focusRequester = bottomFocus, idleBorder = Palette.muted,
                                )
                                .semantics { contentDescription = "Return to bottom" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(AppIcon.BOTTOM, size = 18.dp, color = if (bottomFocused) Palette.background else Palette.text)
                        }
                    }
                }
                ComposerNotes(conversation, model)
                when {
                    conversation.isMessageRequest -> RequestBar(
                        conversation, firstFocus = inputFocus, upTo = messageFocus, blocking = capabilities.blocking,
                        onAccept = { model.respondToRequest(true) },
                        onBlock = { model.setBlocked(true) { navigator.pop() } },
                        onDelete = { model.respondToRequest(false) { navigator.popToRoot() } },
                        onFocus = { focused -> barFocused = focused; if (focused) { selectedId = null; composeControl = null } },
                    )
                    conversation.isBlocked -> BlockedBar(conversation, firstFocus = inputFocus, upTo = messageFocus, onUnblock = { model.setBlocked(false) },
                        onFocus = { focused -> barFocused = focused; if (focused) { selectedId = null; composeControl = null } })
                    else -> {
                MentionSuggestions(model, nearest = mentionFocus, upTo = messageFocus, inputFocus = inputFocus)
                if (model.attachments.isNotEmpty()) {
                    dev.dumbtalk.app.ui.ListButton("${model.attachments.size} attached · edit / remove", { returnControl = ComposeControl.ATTACH; media.openAttach() }, leading = { dev.dumbtalk.app.ui.Icon(AppIcon.ATTACH) })
                }
                ComposerBar(
                    model, capabilities, inputFocus, upTo = if (model.mentionSuggestions.isNotEmpty()) mentionFocus else messageFocus,
                    // Leaving the messages clears the selection, so Options shows on the left.
                    onFocus = { composeControl = it; selectedId = null; bottomRequested = false },
                    onVoice = { returnControl = ComposeControl.VOICE; media.openVoice() },
                    onAttach = { returnControl = ComposeControl.ATTACH; media.openAttach() },
                    onEmoji = { returnControl = ComposeControl.EMOJI; emojiFor = EmojiUse.Compose },
                    onSend = ::send,
                    controls = controls,
                    optionsFocus = controls.getValue(ComposeControl.CHAT_OPTIONS),
                    onBounds = { control, bounds ->
                        anchors[control] = Rect(bounds.left - screenOrigin.x, bounds.top - screenOrigin.y, bounds.right - screenOrigin.x, bounds.bottom - screenOrigin.y)
                    },
                )
                    }
                }
            }
            }
        }
    }
    if (popover != null) {
        ComposerPopover(popover.label, anchors[popover], ::closePopover) {
            when {
                emojiFor != null -> EmojiPanel(
                    title = if (emojiFor is EmojiUse.React) "React" else "Emoji",
                    onPick = { emoji ->
                        when (val use = emojiFor) {
                            is EmojiUse.React -> { model.toggleReaction(use.message, emoji); actionMessageId = null }
                            EmojiUse.Compose -> model.append(emoji)
                            null -> Unit
                        }
                        emojiFor = null
                    },
                    onClose = { emojiFor = null },
                )
                media.overlay == MediaOverlay.Attach -> AttachPanel(media, model.draft) { media.closeOverlay(); focusInput() }
                media.overlay == MediaOverlay.Voice -> VoicePanel(media)
            }
        }
    }
    }
}

/** Typing notice, send error and reply/edit banner above the composer. */
@Composable
private fun ComposerNotes(conversation: Conversation, model: ChatModel) {
    if (conversation.typingNames.isNotEmpty()) {
        BasicText(
            conversation.typingNames.joinToString() + " typing…",
            style = Type.small.copy(color = Palette.sender),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
        )
    }
    model.sendError?.let {
        BasicText(
            it,
            style = Type.small.copy(color = Palette.error),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 2.dp),
        )
    }
    val banner = model.editing?.let { "Editing message" }
        ?: model.replyTo?.let { "Replying to ${it.sender ?: conversation.title}: ${messageSummary(it)}" }
    if (banner != null) {
        BasicText(
            banner,
            style = Type.small.copy(color = Palette.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().background(Palette.panel).padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

/** Group panels over a chat, one at a time. */
internal sealed interface GroupPanel {
    data object Info : GroupPanel
    data object Rename : GroupPanel
    data object Add : GroupPanel
    data class Remove(val member: dev.dumbtalk.core.Member) : GroupPanel
    data object Leave : GroupPanel
}
