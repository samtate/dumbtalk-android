package dev.dumbtalk.app.screens

import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import dev.dumbtalk.core.AttachmentKind
import dev.dumbtalk.app.ui.AppIcon
import dev.dumbtalk.app.media.VoiceRecorder
import dev.dumbtalk.app.media.MediaFiles
import dev.dumbtalk.app.media.AudioPlayer
import androidx.compose.ui.platform.LocalContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.widget.Toast
import android.content.pm.PackageManager
import android.Manifest
import dev.dumbtalk.app.ui.Square
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dumbtalk.app.DumbTalkApp
import dev.dumbtalk.app.Navigator
import dev.dumbtalk.app.Screen
import dev.dumbtalk.app.ui.CenteredMessage
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.LocalSoftkeys
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.Pill
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.TextInput
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.app.ui.focusCard
import dev.dumbtalk.app.ui.messageSummary
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

    /** The message under the cursor; null while focus is in the composer (so Options shows). */
    var selectedId by remember { mutableStateOf<String?>(null) }
    /** The last message focused, where focus returns to when leaving the composer. */
    var lastMessageId by remember { mutableStateOf<String?>(null) }
    var actionMessageId by remember { mutableStateOf<String?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var emojiFor by remember { mutableStateOf<EmojiUse?>(null) }
    val listState = rememberLazyListState()
    val messageFocus = remember { FocusRequester() }
    val inputFocus = remember { FocusRequester() }
    var loaded by remember { mutableStateOf(false) }

    fun focusInput() = scope.launch { withFrameNanos { }; runCatching { inputFocus.requestFocus() } }

    // Media: a full-screen photo, a voice note being recorded, and pickers.
    val context = LocalContext.current
    var viewerPath by remember { mutableStateOf<String?>(null) }
    var recorder by remember { mutableStateOf<VoiceRecorder?>(null) }
    fun failed(what: String, error: Throwable) = Toast.makeText(context, "$what: ${error.message ?: "failed"}", Toast.LENGTH_SHORT).show()
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { MediaFiles.copyIn(context, uri) } }
                .onSuccess { model.sendFile(it.path, it.mime) }
                .onFailure { failed("Couldn't attach", it) }
        }
    }
    fun startRecording() {
        val next = VoiceRecorder(context, capabilities.voiceNoteMime)
        runCatching { next.start() }.onSuccess { recorder = next }.onFailure { failed("Couldn't record", it) }
    }
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
    }
    fun withAttachment(message: Message, use: (String) -> Unit) = scope.launch {
        runCatching { model.attachmentPath(message) }.onSuccess(use).onFailure { failed("Couldn't download", it) }
    }
    fun mediaAction(message: Message): MenuItem? = when (message.attachments.firstOrNull()?.kind) {
        AttachmentKind.IMAGE -> MenuItem("View", AppIcon.SEARCH) { withAttachment(message) { viewerPath = it } }
        AttachmentKind.AUDIO -> if (AudioPlayer.playing == message.id) {
            MenuItem("Stop", AppIcon.MIC) { AudioPlayer.stop() }
        } else {
            MenuItem("Play", AppIcon.MIC) { withAttachment(message) { AudioPlayer.play(message.id, it) } }
        }
        AttachmentKind.VIDEO, AttachmentKind.FILE -> MenuItem("Open", AppIcon.ATTACH) {
            withAttachment(message) { path ->
                if (!MediaFiles.open(context, path, message.attachments.first().contentType)) {
                    Toast.makeText(context, "No app here can open this file", Toast.LENGTH_SHORT).show()
                }
            }
        }
        null -> null
    }
    DisposableEffect(Unit) { onDispose { AudioPlayer.stop() } }
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
    LaunchedEffect(loaded, actionMessageId, showOptions) {
        if (!loaded || actionMessageId != null || showOptions) return@LaunchedEffect
        withFrameNanos { }
        runCatching { messageFocus.requestFocus() }
    }

    val actionMessage = model.messages?.firstOrNull { it.id == actionMessageId }
    val activeRecorder = recorder
    when {
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

        viewerPath != null -> ImageViewer(viewerPath!!) { viewerPath = null }

        activeRecorder != null -> RecordingPanel(
            activeRecorder,
            onSend = {
                recorder = null
                activeRecorder.stop(keep = true)?.let { (file, duration) ->
                    model.sendVoiceNote(file.absolutePath, capabilities.voiceNoteMime, duration)
                }
            },
            onCancel = { recorder = null; activeRecorder.stop(keep = false) },
        )

        actionMessage != null -> MessageActions(
            conversation, actionMessage, capabilities,
            onClose = { actionMessageId = null },
            onReact = { emoji -> model.toggleReaction(actionMessage, emoji); actionMessageId = null },
            onReply = { model.startReply(actionMessage); actionMessageId = null; focusInput() },
            onEdit = { model.startEdit(actionMessage); actionMessageId = null; focusInput() },
            onDelete = { model.delete(actionMessage); actionMessageId = null },
            onPin = { model.togglePin(actionMessage); actionMessageId = null },
            onMoreReactions = { emojiFor = EmojiUse.React(actionMessage) },
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
            send = buildList {
                add(MenuItem("Emoji", AppIcon.STICKER) { showOptions = false; emojiFor = EmojiUse.Compose })
                if (capabilities.attachments) add(MenuItem("Send file", AppIcon.ATTACH) { showOptions = false; pickFile.launch("*/*") })
                if (capabilities.voiceNotes) add(MenuItem("Voice note", AppIcon.MIC) {
                    showOptions = false
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                    else askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                })
            },
        )

        else -> {
            Softkeys(
                left = when {
                    softkeys.typing -> null
                    selectedId != null -> SoftkeyAction("Message") { actionMessageId = selectedId }
                    else -> SoftkeyAction("Options") { showOptions = true }
                },
                center = when {
                    softkeys.typing && model.draft.isNotBlank() -> SoftkeyAction(if (model.editing != null) "Save" else "Send") { send() }
                    softkeys.typing -> SoftkeyAction("")
                    else -> SoftkeyAction("Type") { runCatching { inputFocus.requestFocus() } }
                },
                right = SoftkeyAction(if (model.replyTo != null || model.editing != null) "Cancel" else "Back") {
                    when {
                        model.cancelCompose() -> Unit
                        softkeys.typing -> runCatching { messageFocus.requestFocus() }
                        else -> navigator.pop()
                    }
                },
            )
            Column(Modifier.fillMaxSize()) {
                Header(conversation.title, trailing = { Pill(conversation.serviceId.label) })
                Box(Modifier.weight(1f)) {
                    val messages = model.messages
                    when {
                        messages == null -> CenteredMessage(model.loadError ?: "Loading…", color = if (model.loadError != null) Palette.error else Palette.muted)
                        messages.isEmpty() -> CenteredMessage("No messages yet. Press Type to say hello.")
                        else -> MessageList(
                            conversation, messages, model.hasMore, listState, lastMessageId, messageFocus,
                            onSelect = { selectedId = it.id; lastMessageId = it.id },
                            onOpen = { actionMessageId = it.id },
                            onLoadOlder = { scope.launch { model.load(older = true) } },
                            loadAttachment = model::attachmentPath,
                        )
                    }
                }
                ComposerNotes(conversation, model)
                Row(
                    Modifier.fillMaxWidth().padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextInput(
                        model.draft,
                        onValueChange = model::type,
                        placeholder = "Message",
                        focusRequester = inputFocus,
                        // Leaving the messages clears the selection, so Options shows on the left.
                        modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) selectedId = null },
                        onDone = ::send,
                    )
                    Box(
                        Modifier
                            .size(32.dp)
                            .focusCard(::send, Square, SolidColor(Palette.send), onFocused = { selectedId = null })
                            .semantics { contentDescription = "Send" },
                        contentAlignment = Alignment.Center,
                    ) { BasicText(">", style = Type.header.copy(color = Palette.background)) }
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
