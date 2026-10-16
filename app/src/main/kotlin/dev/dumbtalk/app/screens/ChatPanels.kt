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
import androidx.compose.runtime.remember
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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    /** View, Play/Stop or Open, for messages with an attachment. */
    media: MenuItem? = null,
    onMoreReactions: () -> Unit = {},
    onForward: () -> Unit = {},
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
                                        if (row == 0 && column == 0) requester else null,
                                        idleBorder = if (own) Palette.focus.copy(alpha = 0.5f) else Palette.tileBorder,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) { BasicText(emoji.forDisplay(), style = Type.centered(Type.body.copy(fontSize = 21.sp, lineHeight = 21.sp))) }
                        }
                    }
                }
            }
            val actions = buildList {
                if (media != null && !message.deleted) add(media)
                if (!message.deleted) add(MenuItem("Reply", AppIcon.REPLY, onReply))
                if (!message.deleted && (message.text != null || message.attachments.isNotEmpty())) add(MenuItem("Forward", AppIcon.FORWARD, onForward))
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
    onClose: () -> Unit,
    onUpdate: (ConversationUpdate) -> Unit,
) {
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
                conversation.members.forEach { BasicText(it.name.forDisplay(), style = Type.body, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
    }
}
