package dev.dumbtalk.app.screens

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.dumbtalk.app.background.BackgroundStatus
import dev.dumbtalk.app.ui.focusAfterFrame
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dumbtalk.app.DumbTalkApp
import dev.dumbtalk.app.Navigator
import dev.dumbtalk.app.R
import dev.dumbtalk.app.Screen
import dev.dumbtalk.app.ui.AppIcon
import dev.dumbtalk.app.ui.Avatar
import dev.dumbtalk.app.ui.CenteredMessage
import dev.dumbtalk.app.ui.Fonts
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.Icon
import dev.dumbtalk.app.ui.ListButton
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.Pill
import dev.dumbtalk.app.ui.ServiceIcon
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.SoftkeyController
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.Square
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.app.ui.focusCard
import dev.dumbtalk.app.ui.forDisplay
import dev.dumbtalk.app.ui.messageSummary
import dev.dumbtalk.app.ui.receiptMark
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.ConversationKind
import dev.dumbtalk.core.Direction

@Composable
fun InboxScreen(app: DumbTalkApp, navigator: Navigator, archived: Boolean) {
    val page by (if (archived) app.inbox.archived else app.inbox.active).collectAsStateWithLifecycle()
    val connected by app.inbox.connected.collectAsStateWithLifecycle()
    val conversations = page?.conversations.orEmpty()
    var focusedId by remember { mutableStateOf(navigator.inboxFocus[archived]) }
    val focused = conversations.firstOrNull { it.id == focusedId }

    Softkeys(
        left = SoftkeyAction("Menu") { navigator.push(Screen.Menu(focused, archived)) },
        center = SoftkeyAction(if (conversations.isEmpty()) "Select" else "Open"),
        right = if (archived) SoftkeyAction("Back") { navigator.pop() } else SoftkeyAction(SoftkeyController.EXIT),
    )

    Column(Modifier.fillMaxSize()) {
        val count = connected?.size
        Header(
            if (archived) "Archived" else "DumbTalk",
            trailing = when {
                archived || count == null -> null
                app.demo -> { { Pill("Demo") } }
                else -> { { Pill(if (count == 1) "1 service" else "$count services") } }
            },
        )
        if (!archived && (count ?: 0) > 0 && app.guidesUser) BackgroundWarning()
        when {
            page == null -> CenteredMessage("Loading…")
            connected?.isEmpty() == true -> NoServices(app, navigator)
            conversations.isEmpty() -> CenteredMessage(if (archived) "No archived chats" else "No conversations yet")
            else -> ConversationList(app, conversations, focusedId, onFocus = {
                focusedId = it.id
                navigator.inboxFocus[archived] = it.id
            }, onOpen = navigator::openChat)
        }
    }
}

@Composable
private fun NoServices(app: DumbTalkApp, navigator: Navigator) {
    val requester = remember { FocusRequester() }
    val activity = LocalContext.current as Activity
    LaunchedEffect(Unit) { requester.focusAfterFrame() }
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.dumbtalk_logo), null, Modifier.size(56.dp).clip(Square))
        CenteredMessage("Connect Signal, Telegram or WhatsApp to start.")
        ListButton("Connect a service", { navigator.push(Screen.Services) }, focusRequester = requester)
        ListButton("Try the demo", { app.restartWithDemo(activity, true) }, description = "Made-up chats, to try the keys first")
        ListButton("Restore from backup", { app.restartFor(activity, DumbTalkApp.Maintenance.RESTORE) }, description = "Moving from another phone")
    }
}

/** Says why messages may arrive late; Settings → Background delivery fixes it. */
@Composable
private fun BackgroundWarning() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(BackgroundStatus.of(context)) }
    LifecycleResumeEffect(Unit) {
        status = BackgroundStatus.of(context)
        onPauseOrDispose { }
    }
    if (status.ok) return
    BasicText(
        "${status.summary}. Fix it in Menu → Settings.",
        style = Type.small.copy(color = Palette.error),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
fun ConversationList(
    app: DumbTalkApp,
    conversations: List<Conversation>,
    focusedId: String?,
    onFocus: (Conversation) -> Unit,
    onOpen: (Conversation) -> Unit,
) {
    val listState = rememberLazyListState()
    val requester = remember { FocusRequester() }
    val initialIndex = conversations.indexOfFirst { it.id == focusedId }.coerceAtLeast(0)
    val initialId = conversations.getOrNull(initialIndex)?.id
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        // Restore the inbox at its top. If the selected chat lives beyond the
        // first viewport, center it so the rows above it remain visible too.
        listState.scrollToItem(0)
        withFrameNanos { }
        val layout = listState.layoutInfo
        val isOnFirstPage = layout.visibleItemsInfo.any { it.index == initialIndex }
        if (initialIndex > 0 && !isOnFirstPage) {
            val viewportHeight = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0)
            val rowHeight = layout.visibleItemsInfo.firstOrNull()?.size ?: with(density) { 60.dp.roundToPx() }
            val centerOffset = -((viewportHeight - rowHeight) / 2).coerceAtLeast(0)
            listState.scrollToItem(initialIndex, centerOffset)
            withFrameNanos { }
        }
        runCatching { requester.requestFocus() }
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 3.dp), modifier = Modifier.fillMaxSize()) {
        items(conversations, key = { it.id }) { conversation ->
            ConversationRow(
                app,
                conversation,
                onOpen = { onOpen(conversation) },
                onFocus = { onFocus(conversation) },
                focusRequester = if (conversation.id == initialId) requester else null,
            )
        }
    }
}

@Composable
fun ConversationRow(app: DumbTalkApp, conversation: Conversation, onOpen: () -> Unit, onFocus: () -> Unit, focusRequester: FocusRequester? = null) {
    val avatarPath by produceState(conversation.avatarPath, conversation.id) {
        value = runCatching { app.registry.forConversation(conversation).conversationAvatar(conversation) }.getOrNull() ?: conversation.avatarPath
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp, vertical = 3.dp)
            .focusCard(onOpen, focusRequester = focusRequester, onFocused = onFocus)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(conversation.title, conversation.unreadCount, noteToSelf = conversation.isNoteToSelf, imagePath = avatarPath)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    ((if (conversation.isFavourite) "★ " else "") + conversation.title).forDisplay(),
                    style = Type.title.copy(fontWeight = FontWeight.Normal),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.isMuted) Icon(AppIcon.MUTE, size = 11.dp, color = Palette.muted)
                    ServiceIcon(conversation.serviceId, 18.dp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(previewText(conversation), style = Type.preview, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // Ticks line up in one column under the service icons.
                conversation.lastMessage?.takeIf { it.direction == Direction.OUTGOING && conversation.typingNames.isEmpty() }?.let {
                    BasicText(receiptMark(it.receipt), style = Type.preview, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

private fun firstName(name: String?) = name?.trim()?.split(Regex("\\s+"))?.firstOrNull()

private fun previewText(conversation: Conversation) = buildAnnotatedString {
    if (conversation.typingNames.isNotEmpty()) {
        withStyle(SpanStyle(color = Palette.sender)) {
            append(conversation.typingNames.joinToString { firstName(it) ?: it } + " typing…")
        }
        return@buildAnnotatedString
    }
    val marker = when {
        conversation.isMessageRequest -> "Request · "
        conversation.isBlocked -> "Blocked · "
        else -> null
    }
    marker?.let { withStyle(SpanStyle(color = Palette.unread)) { append(it) } }
    val message = conversation.lastMessage
    if (message == null) {
        append(messageSummary(null))
        return@buildAnnotatedString
    }
    val sender = when {
        message.direction == Direction.OUTGOING -> "You"
        conversation.kind == ConversationKind.GROUP -> firstName(message.sender) ?: "Someone"
        else -> conversation.title.forDisplay()
    }
    withStyle(SpanStyle(fontFamily = Fonts.dotBold, color = Palette.sender)) { append("$sender: ") }
    append(messageSummary(message).forDisplay())
}
