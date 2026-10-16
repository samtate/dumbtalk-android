package dev.dumbtalk.app.screens

import dev.dumbtalk.core.AttachmentKind
import dev.dumbtalk.app.media.AudioPlayer
import androidx.compose.ui.text.buildAnnotatedString
import dev.dumbtalk.app.ui.receiptMark
import dev.dumbtalk.app.ui.assignTints
import dev.dumbtalk.app.ui.serviceColor
import dev.dumbtalk.app.ui.avatarTint
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import dev.dumbtalk.app.ui.forDisplay
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dumbtalk.app.ui.CenteredMessage
import dev.dumbtalk.app.ui.Square
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.app.ui.focusCard
import dev.dumbtalk.app.ui.messageSummary
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.ConversationKind
import dev.dumbtalk.core.Direction
import dev.dumbtalk.core.Message
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// The scrolling message list of a chat: day dividers, sender names and bubbles.

@Composable
internal fun MessageList(
    conversation: Conversation,
    messages: List<Message>,
    hasMore: Boolean,
    listState: LazyListState,
    selectedId: String?,
    selectedFocus: FocusRequester,
    onSelect: (Message) -> Unit,
    onOpen: (Message) -> Unit,
    onLoadOlder: () -> Unit,
    loadAttachment: suspend (Message) -> String,
) {
    val zone = ZoneId.systemDefault()
    val time = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val day = remember { DateTimeFormatter.ofPattern("EEE, MMM d") }
    val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.88f).dp
    // One distinct colour per person in this chat; yours is the service's.
    val ownTint = serviceColor(conversation.serviceId)
    fun personOf(message: Message) =
        if (conversation.kind == ConversationKind.GROUP) message.sender ?: conversation.title else conversation.title
    val tints = remember(messages, conversation) {
        val people = messages.filter { it.direction == Direction.INCOMING }.map(::personOf) +
            messages.mapNotNull { it.quote?.author }.filter { it != "You" }
        assignTints(people, avoid = ownTint)
    }
    val tintOf = { name: String -> if (name == "You") ownTint else tints[name] ?: avatarTint(name) }
    LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp), modifier = Modifier.fillMaxSize()) {
        if (hasMore) {
            item(key = "older") {
                Box(Modifier.fillMaxWidth().padding(4.dp), contentAlignment = Alignment.Center) {
                    BasicText(
                        "Load older messages",
                        style = Type.small,
                        modifier = Modifier
                            .focusCard(onLoadOlder, Square, SolidColor(Palette.background), idleBorder = Palette.line)
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
                val tint = if (message.direction == Direction.OUTGOING) ownTint else tintOf(personOf(message))
                if (showSender) BasicText(message.sender.orEmpty().forDisplay(), style = Type.small.copy(color = tint), modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                Bubble(
                    message,
                    tint,
                    tintOf,
                    loadAttachment,
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
            style = Type.small.copy(color = Palette.muted),
            modifier = Modifier.padding(horizontal = 6.dp).padding(horizontal = 7.dp, vertical = 1.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(Palette.line))
    }
}

@Composable
private fun Bubble(
    message: Message,
    tint: Color,
    tintOf: (String) -> Color,
    loadAttachment: suspend (Message) -> String,
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
                .focusCard(
                    onOpen, Square, SolidColor(lerp(Palette.background, tint, 0.24f)),
                    focusRequester, onFocus, idleBorder = tint.copy(alpha = 0.7f), claimsCenter = false,
                )
                .padding(horizontal = 7.dp, vertical = 5.dp),
        ) {
            message.forwardedFrom?.let { BasicText("Forwarded from $it", style = Type.small.copy(color = Palette.muted)) }
            message.quote?.let { quote ->
                val quoteTint = tintOf(quote.author)
                Column(
                    Modifier
                        .padding(bottom = 3.dp)
                        .border(1.dp, quoteTint.copy(alpha = 0.6f), Square)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    BasicText(quote.author.forDisplay(), style = Type.bold(Type.small.copy(color = quoteTint)))
                    BasicText((quote.text ?: "Message").forDisplay(), style = Type.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!message.deleted) InlineImage(message, maxWidth - 14.dp, loadAttachment)
            val italic = message.deleted
            val label = when {
                message.deleted -> "This message was deleted"
                AudioPlayer.playing == message.id -> "■ Playing…"
                message.attachments.firstOrNull()?.kind == AttachmentKind.AUDIO && message.text == null -> "▶ ${messageSummary(message)}"
                message.image() != null && message.text == null -> null // the photo speaks for itself
                else -> messageSummary(message).forDisplay()
            }
            if (label != null) {
                BasicText(
                    label,
                    style = Type.body.copy(
                        color = if (italic) Palette.muted else Palette.text,
                        fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                    ),
                )
            }
            if (message.reactions.isNotEmpty()) {
                BasicText(
                    message.reactions.groupBy { it.emoji }.entries.joinToString(" ") { (emoji, list) -> if (list.size > 1) "$emoji${list.size}" else emoji }.forDisplay(),
                    style = Type.body,
                    modifier = Modifier.padding(top = 2.dp).border(1.dp, tint.copy(alpha = 0.45f), Square).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            val meta = buildAnnotatedString {
                if (message.pinned) append("📌".forDisplay() + " ")
                if (message.edited && !message.deleted) append("edited · ")
                append(time)
                if (outgoing) {
                    append(" ")
                    append(receiptMark(message.receipt))
                }
            }
            BasicText(meta, style = Type.small.copy(color = Palette.time, textAlign = TextAlign.End), modifier = Modifier.align(Alignment.End))
        }
    }
}

