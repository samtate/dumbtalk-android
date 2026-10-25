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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dumbtalk.app.ChatTool
import dev.dumbtalk.app.DumbTalkApp
import dev.dumbtalk.app.Navigator
import dev.dumbtalk.app.Screen
import dev.dumbtalk.app.media.AudioPlayer
import dev.dumbtalk.app.ui.AppIcon
import dev.dumbtalk.app.ui.Avatar
import dev.dumbtalk.app.ui.CenteredMessage
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.Icon
import dev.dumbtalk.app.ui.ListButton
import dev.dumbtalk.app.ui.LocalControlsEnabled
import dev.dumbtalk.app.ui.LocalSoftkeys
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.ServiceIcon
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.app.ui.describePresence
import dev.dumbtalk.app.ui.focusAfterFrame
import dev.dumbtalk.app.ui.focusCard
import dev.dumbtalk.app.ui.moveFocusOn
import dev.dumbtalk.app.ui.hasSpoilers
import dev.dumbtalk.app.ui.messageSummary
import dev.dumbtalk.core.AttachmentKind
import dev.dumbtalk.core.Capabilities
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.ConversationKind
import dev.dumbtalk.core.ConversationUpdate
import dev.dumbtalk.core.Direction
import dev.dumbtalk.core.Member
import dev.dumbtalk.core.Message
import dev.dumbtalk.core.SearchResult
import dev.dumbtalk.core.ServiceEvent
import dev.dumbtalk.core.ServiceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(app: DumbTalkApp, navigator: Navigator, screen: Screen.Chat) {
    val service = app.registry.forConversation(screen.initial)
    val offline by app.inbox.offline.collectAsStateWithLifecycle()
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
    val revealed = remember { mutableStateListOf<String>() }
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

    fun focusInput() = scope.launch { inputFocus.focusAfterFrame() }

    /**
     * Selects a message, scrolls it into view and focuses it. Rows added
     * above the list change its indices, so the scroll is requested for the
     * next layout, and focus waits until the message is laid out and attached.
     */
    suspend fun scrollToAndFocus(id: String) {
        val index = model.messages.orEmpty().indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        selectedId = id
        lastMessageId = id
        listState.requestScrollToItem(model.listIndex(index))
        val requester = snapshotFlow {
            if (listState.layoutInfo.visibleItemsInfo.any { it.key == id }) messageFocusById[id] else null
        }.first { it != null } ?: return
        withFrameNanos { }
        repeat(3) {
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) return
            withFrameNanos { }
        }
    }
    fun send() = model.send { listState.animateScrollToItem(model.lastIndex) }
    fun returnToBottom() {
        scope.launch {
            lastMessageId = model.messages?.lastOrNull { it.direction != Direction.SYSTEM }?.id
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
            softkeys.typing -> { returnControl = null; returnFocus = inputFocus }
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
        val lastRead = if (unreadIndex >= 0) shown.subList(0, unreadIndex).lastOrNull { it.direction != Direction.SYSTEM } else null
        selectedId = (lastRead ?: firstUnread ?: shown.lastOrNull())?.id
        lastMessageId = selectedId
        if (pendingJump == null) {
            if (unreadIndex >= 0) listState.scrollToItem(model.listIndex((unreadIndex - 1).coerceAtLeast(0)))
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

    // Online / last seen: asked on opening, when members load, and whenever it changes.
    LaunchedEffect(model.members) { model.refreshPresence() }
    LaunchedEffect(Unit) {
        service.events.filter { it is ServiceEvent.PresenceChanged }.conflate().collect { model.refreshPresence() }
    }

    /** Something covers the chat: a panel, a picker, a viewer or a confirmation. */
    val covered = showDeleteThread || deletingMessage != null || tool != null || pendingJump != null || actionMessageId != null ||
        showOptions || showPollCreator || votingPoll != null || closingPoll != null || media.overlay != null || emojiFor != null ||
        forwarding != null || showTimer || groupPanel != null || confirmBlock

    // Focus returns to the selected message when a panel closes.
    LaunchedEffect(loaded, covered) {
        if (!loaded || covered) return@LaunchedEffect
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
            actionMessageId = null
            showOptions = false
            returnTool = tool
            tool = null
            returnFocus = null
            returnControl = null
            findingMessage = false
            withFrameNanos { }
            scrollToAndFocus(target.id)
            pendingJump = null
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            findingMessage = false
            pendingJump = null
            Toast.makeText(context, failure.message ?: "Could not find this message", Toast.LENGTH_LONG).show()
        }
    }

    val messages = model.messages.orEmpty()
    val actionMessage = messages.firstOrNull { it.id == actionMessageId }
    var actionFocusLabel by remember(actionMessageId) { mutableStateOf<String?>(null) }
    var optionFocusLabel by remember(showOptions) { mutableStateOf<String?>(null) }

    /** The request or blocked bar standing in for the composer took or lost focus. */
    fun onBarFocus(focused: Boolean) {
        barFocused = focused
        if (focused) { selectedId = null; composeControl = null }
    }

    fun quoteTarget(message: Message) = message.quote?.messageId?.takeIf { id -> messages.any { it.id == id } }
    fun jumpTo(id: String) {
        scope.launch { scrollToAndFocus(id) }
    }
    /** The centre key does what the focused message is for: play it, view it, vote, follow its quote, or else its actions. */
    fun centreAction(message: Message): SoftkeyAction {
        val kind = message.attachments.firstOrNull()?.kind
        val actions = SoftkeyAction("Actions") { actionMessageId = message.id }
        val quote = quoteTarget(message)
        return when {
            message.deleted -> actions
            message.poll?.let { !it.closed } == true ->
                if (message.direction == Direction.OUTGOING) actions else SoftkeyAction("Vote") { votingPoll = message }
            message.poll != null -> actions
            kind == AttachmentKind.AUDIO -> SoftkeyAction(if (AudioPlayer.playing == message.id) "Stop" else "Play") { media.togglePlay(message) }
            kind == AttachmentKind.IMAGE || kind == AttachmentKind.VIDEO -> SoftkeyAction(
                when { message.visuals().size > 1 -> "View photos"; kind == AttachmentKind.IMAGE -> "View photo"; else -> "Play video" },
            ) { media.open(message) }
            kind == AttachmentKind.FILE -> SoftkeyAction("Open") { media.openExternally(message) }
            quote != null -> SoftkeyAction("Goto Quote") { jumpTo(quote) }
            // A quick 👍; Left opens the actions with the other reactions.
            capabilities.reactions -> SoftkeyAction("React") { model.toggleReaction(message, "👍") }
            else -> actions
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
            onPick = { seconds -> model.update(ConversationUpdate(expiration = seconds)); showTimer = false; showOptions = false },
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
            loadDetails = model::details,
            focusedLabel = actionFocusLabel,
            onFocused = { actionFocusLabel = it },
            onClose = { actionMessageId = null },
            onRetry = { model.retry(actionMessage); actionMessageId = null },
            onReact = { emoji -> model.toggleReaction(actionMessage, emoji); actionMessageId = null },
            onReply = { model.startReply(actionMessage); returnFocus = inputFocus; actionMessageId = null },
            onEdit = { model.startEdit(actionMessage); returnFocus = inputFocus; actionMessageId = null },
            onDelete = { if (conversation.serviceId == ServiceId.SMS) deletingMessage = actionMessage else { model.delete(actionMessage); actionMessageId = null } },
            onPin = { model.togglePin(actionMessage); actionMessageId = null },
            onMoreReactions = { emojiFor = EmojiUse.React(actionMessage); actionMessageId = null },
            onForward = { forwarding = actionMessage },
            onChatOptions = { openChatOptions() },
            quote = quoteTarget(actionMessage)?.let { id -> MenuItem("Goto Quote", AppIcon.REPLY) { actionMessageId = null; jumpTo(id) } },
            media = mediaAction(actionMessage)?.let { item -> MenuItem(item.label, item.icon) { actionMessageId = null; item.onClick() } },
            spoiler = if (hasSpoilers(actionMessage)) {
                if (actionMessage.id in revealed) MenuItem("Hide spoiler", AppIcon.CLOSE) { revealed.remove(actionMessage.id); actionMessageId = null }
                else MenuItem("Show spoiler", AppIcon.SEARCH) { revealed.add(actionMessage.id); actionMessageId = null }
            } else null,
            pollAction = actionMessage.poll?.takeIf { capabilities.polls && !it.closed }?.let {
                if (actionMessage.direction == Direction.OUTGOING && capabilities.closingPolls) {
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
            onClose = { if (screen.showOptions) navigator.pop() else showOptions = false },
            items = chatOptionItems(
                conversation,
                polls = capabilities.polls && (capabilities.directPolls || conversation.kind == ConversationKind.GROUP || conversation.isNoteToSelf),
                onUpdate = { update ->
                    model.update(update)
                    showOptions = false
                    if (screen.showOptions) navigator.pop()
                    // Archiving leaves the chat, too.
                    if (update.archived == true) navigator.pop()
                },
                onCreatePoll = { showPollCreator = true },
                search = capabilities.search, pins = capabilities.pins,
                onSearch = { tool = ChatTool.SEARCH },
                onPins = { tool = ChatTool.PINS },
                deleteThread = capabilities.deleteConversations, onDeleteThread = { showDeleteThread = true },
            ) + buildList {
                val group = conversation.kind == ConversationKind.GROUP
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
                // Only a selected message has a left softkey; in the composer Left moves along the row.
                left = if (composeControl == null && !softkeys.typing && !bottomFocused && selectedId != null) {
                    SoftkeyAction("Actions") { actionMessageId = selectedId }
                } else null,
                center = when {
                    bottomFocused -> SoftkeyAction("Bottom")
                    barFocused -> SoftkeyAction("Select")
                    softkeys.typing && model.canSend -> SoftkeyAction(if (model.editing != null) "Save" else "Send") { send() }
                    softkeys.typing -> SoftkeyAction("Type")
                    // A focused composer button handles the key itself; this is its label.
                    composeControl == ComposeControl.SEND -> SoftkeyAction(if (model.editing != null) "Save" else "Send")
                    composeControl != null -> SoftkeyAction(composeControl!!.label)
                    selected != null -> centreAction(selected)
                    else -> SoftkeyAction("Type") { runCatching { inputFocus.requestFocus() } }
                },
                    right = SoftkeyAction(
                        // From the composer Back returns to the messages; from them, to the inbox.
                        if (model.replyTo != null || model.editing != null) "Cancel" else "Back",
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
                Header(conversation.title, leading = { Avatar(conversation.title, size = 24.dp, noteToSelf = conversation.isNoteToSelf, imagePath = avatarPath) }, trailing = {
                    // A timer shows the chat's messages disappear.
                    if (conversation.expiration > 0) Icon(AppIcon.TIMER, size = 14.dp, color = Palette.muted, modifier = Modifier.padding(end = 5.dp))
                    ServiceIcon(conversation.serviceId, 18.dp)
                })
                if (conversation.serviceId in offline) BasicText(
                    "Offline · messages send when ${conversation.serviceId.label} reconnects",
                    style = Type.small.copy(color = Palette.error),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 3.dp),
                ) else describePresence(model.presence)?.let {
                    BasicText(it, style = Type.small, modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 3.dp))
                }
                Box(Modifier.weight(1f)) {
                    val shown = model.messages
                    when {
                        shown == null -> CenteredMessage(model.loadError ?: "Loading…", color = if (model.loadError != null) Palette.error else Palette.muted)
                        shown.isEmpty() -> CenteredMessage("No messages yet.")
                        else -> MessageList(
                            conversation, shown, model.hasMore, listState, lastMessageId, messageFocus,
                            onSelect = {
                                selectedId = it.id; lastMessageId = it.id; composeControl = null; bottomRequested = false
                                model.reached(it)
                            },
                            onOpen = { actionMessageId = it.id },
                            onLoadOlder = {
                                scope.launch {
                                    // The newest of the older messages, where reading carries on.
                                    val target = model.load(older = true) ?: model.messages?.firstOrNull() ?: return@launch
                                    scrollToAndFocus(target.id)
                                }
                            },
                            loadAttachment = { message, attachment -> model.attachmentPath(message, attachment) },
                            loadThumbnail = model::thumbnailPath,
                            belowLast = inputFocus,
                            focusByMessageId = messageFocusById,
                            onRight = {
                                bottomRequested = true
                                scope.launch { bottomFocus.focusAfterFrame() }
                            },
                            revealed = revealed.toSet(),
                            loadAvatar = service::personAvatar,
                        )
                    }
                    if (!model.messages.isNullOrEmpty() && (farFromBottom || bottomRequested)) {
                        JumpToBottomButton(
                            focused = bottomFocused, focusRequester = bottomFocus, messages = messageFocus, input = inputFocus,
                            onFocus = { focused ->
                                bottomFocused = focused
                                if (focused) { selectedId = null; composeControl = null }
                            },
                            onPress = ::returnToBottom,
                            modifier = Modifier.align(Alignment.BottomEnd),
                        )
                    }
                }
                ComposerNotes(conversation, model)
                when {
                    conversation.isMessageRequest -> RequestBar(
                        conversation, firstFocus = inputFocus, upTo = messageFocus, blocking = capabilities.blocking,
                        onAccept = { model.respondToRequest(true) },
                        onBlock = { model.setBlocked(true) { navigator.pop() } },
                        onDelete = { model.respondToRequest(false) { navigator.popToRoot() } },
                        onFocus = ::onBarFocus,
                    )
                    conversation.isBlocked -> BlockedBar(conversation, firstFocus = inputFocus, upTo = messageFocus, onUnblock = { model.setBlocked(false) },
                        onFocus = ::onBarFocus)
                    else -> {
                MentionSuggestions(model, nearest = mentionFocus, upTo = messageFocus, inputFocus = inputFocus)
                if (model.attachments.isNotEmpty()) {
                    ListButton("${model.attachments.size} attached · edit / remove", { returnControl = ComposeControl.ATTACH; media.openAttach() }, leading = { Icon(AppIcon.ATTACH) })
                }
                ComposerBar(
                    model, capabilities, inputFocus, upTo = if (model.mentionSuggestions.isNotEmpty()) mentionFocus else messageFocus,
                    // Leaving the messages clears the selection, so Options shows on the left.
                    onFocus = { composeControl = it; selectedId = null; bottomRequested = false },
                    onVoice = { returnControl = ComposeControl.VOICE; media.openVoice() },
                    onAttach = { returnControl = ComposeControl.ATTACH; media.openAttach() },
                    onEmoji = { returnControl = ComposeControl.EMOJI; emojiFor = EmojiUse.Compose },
                    onChatOptions = ::openChatOptions,
                    onSend = ::send,
                    controls = controls,
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

/** The round button over the newest messages that returns to them. */
@Composable
private fun JumpToBottomButton(
    focused: Boolean, focusRequester: FocusRequester, messages: FocusRequester, input: FocusRequester,
    onFocus: (Boolean) -> Unit, onPress: () -> Unit, modifier: Modifier = Modifier,
) {
    Box(
        modifier.padding(8.dp).size(32.dp)
            .onFocusChanged { onFocus(it.isFocused) }
            .moveFocusOn(Key.DirectionLeft, messages)
            .moveFocusOn(Key.DirectionUp, messages)
            .focusProperties { down = input }
            .focusCard(
                onPress, CircleShape, SolidColor(if (focused) Palette.focus else Palette.panel),
                focusRequester = focusRequester, idleBorder = Palette.muted,
            )
            .semantics { contentDescription = "Return to bottom" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcon.BOTTOM, size = 18.dp, color = if (focused) Palette.background else Palette.text)
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
    data class Remove(val member: Member) : GroupPanel
    data object Leave : GroupPanel
}
