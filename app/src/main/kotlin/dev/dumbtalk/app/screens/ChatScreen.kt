package dev.dumbtalk.app.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dumbtalk.app.DumbTalkApp
import dev.dumbtalk.app.Navigator
import dev.dumbtalk.app.Screen
import dev.dumbtalk.app.media.AudioPlayer
import dev.dumbtalk.app.ui.AppIcon
import dev.dumbtalk.app.ui.CenteredMessage
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.LocalSoftkeys
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.Pill
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

    /** The message under the cursor; null while focus is in the composer (so Options shows). */
    var selectedId by remember { mutableStateOf<String?>(null) }
    /** The last message focused, where focus returns to when leaving the composer. */
    var lastMessageId by remember { mutableStateOf<String?>(null) }
    var composeControl by remember { mutableStateOf<ComposeControl?>(null) }
    var actionMessageId by remember { mutableStateOf<String?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var emojiFor by remember { mutableStateOf<EmojiUse?>(null) }
    var forwarding by remember { mutableStateOf<Message?>(null) }
    val listState = rememberLazyListState()
    val messageFocus = remember { FocusRequester() }
    val inputFocus = remember { FocusRequester() }
    var loaded by remember { mutableStateOf(false) }

    fun focusInput() = scope.launch { withFrameNanos { }; runCatching { inputFocus.requestFocus() } }
    fun send() = model.send { listState.animateScrollToItem(model.lastIndex) }

    DisposableEffect(screen.conversationId) {
        scope.launch { runCatching { service.openConversation(conversation) } }
        onDispose { app.scope.launch { runCatching { service.closeConversation(conversation) } } }
    }

    LaunchedEffect(screen.conversationId) {
        model.load()
        selectedId = model.messages?.lastOrNull()?.id
        lastMessageId = selectedId
        listState.scrollToItem(model.lastIndex)
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
    LaunchedEffect(loaded, actionMessageId, showOptions, media.overlay, emojiFor, forwarding) {
        if (!loaded || actionMessageId != null || showOptions || media.overlay != null || emojiFor != null || forwarding != null) return@LaunchedEffect
        if (lastMessageId == null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { messageFocus.requestFocus() }
    }

    val messages = model.messages.orEmpty()
    val actionMessage = messages.firstOrNull { it.id == actionMessageId }

    // The centre key does what the focused message is for.
    fun quoteTarget(message: Message) = message.quote?.messageId?.takeIf { id -> messages.any { it.id == id } }
    fun centerLabel(message: Message): String {
        val kind = message.attachments.firstOrNull()?.kind
        return when {
            message.deleted -> "Actions"
            quoteTarget(message) != null -> "Goto Quote"
            kind == AttachmentKind.AUDIO -> if (AudioPlayer.playing == message.id) "Stop" else "Play"
            kind == AttachmentKind.IMAGE -> "View photo"
            kind == AttachmentKind.VIDEO -> "Play video"
            kind == AttachmentKind.FILE -> "Open"
            capabilities.reactions -> "React"
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
            quoteTarget(message) != null -> jumpTo(quoteTarget(message)!!)
            kind == AttachmentKind.AUDIO -> media.togglePlay(message)
            kind == AttachmentKind.IMAGE || kind == AttachmentKind.VIDEO -> media.view(message)
            kind == AttachmentKind.FILE -> media.openExternally(message)
            capabilities.reactions -> model.toggleReaction(message, "👍")
            else -> actionMessageId = message.id
        }
    }

    fun mediaAction(message: Message): MenuItem? = when (message.attachments.firstOrNull()?.kind) {
        AttachmentKind.IMAGE -> MenuItem("View", AppIcon.SEARCH) { media.view(message) }
        AttachmentKind.VIDEO -> MenuItem("Play", AppIcon.MIC) { media.view(message) }
        AttachmentKind.AUDIO -> MenuItem(if (AudioPlayer.playing == message.id) "Stop" else "Play", AppIcon.MIC) { media.togglePlay(message) }
        AttachmentKind.FILE -> MenuItem("Open", AppIcon.ATTACH) { media.openExternally(message) }
        null -> null
    }

    when {
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

        emojiFor != null -> EmojiPanel(
            title = if (emojiFor is EmojiUse.React) "React" else "Emoji",
            onPick = { emoji ->
                when (val use = emojiFor) {
                    is EmojiUse.React -> { model.toggleReaction(use.message, emoji); actionMessageId = null }
                    EmojiUse.Compose -> { model.append(emoji); focusInput() }
                    null -> Unit
                }
                emojiFor = null
            },
            onClose = { emojiFor = null },
        )

        media.overlay != null -> MediaOverlays(media, caption = model.draft)

        actionMessage != null -> MessageActions(
            conversation, actionMessage, capabilities,
            onClose = { actionMessageId = null },
            onReact = { emoji -> model.toggleReaction(actionMessage, emoji); actionMessageId = null },
            onReply = { model.startReply(actionMessage); actionMessageId = null; focusInput() },
            onEdit = { model.startEdit(actionMessage); actionMessageId = null; focusInput() },
            onDelete = { model.delete(actionMessage); actionMessageId = null },
            onPin = { model.togglePin(actionMessage); actionMessageId = null },
            onMoreReactions = { emojiFor = EmojiUse.React(actionMessage) },
            onForward = { forwarding = actionMessage },
            media = mediaAction(actionMessage)?.let { item -> MenuItem(item.label, item.icon) { actionMessageId = null; item.onClick() } },
        )

        showOptions -> ChatOptions(
            conversation,
            onClose = { showOptions = false },
            onUpdate = { update ->
                model.update(update)
                showOptions = false
                if (update.archived == true) navigator.pop()
            },
        )

        else -> {
            val selected = messages.firstOrNull { it.id == selectedId }
            Softkeys(
                left = when {
                    softkeys.typing -> null
                    selectedId != null -> SoftkeyAction("Message") { actionMessageId = selectedId }
                    else -> SoftkeyAction("Options") { showOptions = true }
                },
                center = when {
                    softkeys.typing && model.draft.isNotBlank() -> SoftkeyAction(if (model.editing != null) "Save" else "Send") { send() }
                    softkeys.typing -> SoftkeyAction("Type")
                    // A focused composer button handles the key itself; this is its label.
                    composeControl == ComposeControl.SEND -> SoftkeyAction(if (model.editing != null) "Save" else "Send")
                    composeControl != null -> SoftkeyAction(composeControl!!.label)
                    selected != null -> SoftkeyAction(centerLabel(selected)) { activate(selected) }
                    else -> SoftkeyAction("Type") { runCatching { inputFocus.requestFocus() } }
                },
                right = SoftkeyAction(if (model.replyTo != null || model.editing != null) "Cancel" else "Back") {
                    when {
                        model.cancelCompose() -> Unit
                        softkeys.typing && lastMessageId != null -> runCatching { messageFocus.requestFocus() }
                        else -> navigator.pop()
                    }
                },
            )
            Column(Modifier.fillMaxSize()) {
                Header(conversation.title, trailing = { Pill(conversation.serviceId.label) })
                Box(Modifier.weight(1f)) {
                    val shown = model.messages
                    when {
                        shown == null -> CenteredMessage(model.loadError ?: "Loading…", color = if (model.loadError != null) Palette.error else Palette.muted)
                        shown.isEmpty() -> CenteredMessage("No messages yet.")
                        else -> MessageList(
                            conversation, shown, model.hasMore, listState, lastMessageId, messageFocus,
                            onSelect = { selectedId = it.id; lastMessageId = it.id; composeControl = null },
                            onOpen = { actionMessageId = it.id },
                            onLoadOlder = { scope.launch { model.load(older = true) } },
                            loadAttachment = model::attachmentPath,
                            belowLast = inputFocus,
                        )
                    }
                }
                ComposerNotes(conversation, model)
                ComposerBar(
                    model, capabilities, inputFocus, upTo = messageFocus,
                    // Leaving the messages clears the selection, so Options shows on the left.
                    onFocus = { composeControl = it; selectedId = null },
                    onVoice = media::openVoice,
                    onAttach = media::openAttach,
                    onEmoji = { emojiFor = EmojiUse.Compose },
                    onSend = ::send,
                )
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
