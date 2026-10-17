package dev.dumbtalk.app.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import dev.dumbtalk.core.AttachmentKind
import dev.dumbtalk.core.Message
import dev.dumbtalk.core.TextSpan

/** "340 KB" or "2.3 MB". */
fun formatSize(bytes: Long): String =
    if (bytes < 1024 * 1024) "${maxOf(1, bytes / 1024)} KB" else "%.1f MB".format(bytes / (1024.0 * 1024.0))

/**
 * The line that says what an attachment is, for those not shown as a
 * picture: "▶ Video · 2.3 MB", "report.pdf · 120 KB", "▶ Audio message".
 */
fun attachmentLine(message: Message): String? {
    val attachment = message.attachments.firstOrNull() ?: return null
    val size = attachment.size?.takeIf { it > 0 }?.let { " · ${formatSize(it)}" }.orEmpty()
    return when (attachment.kind) {
        AttachmentKind.IMAGE -> null
        AttachmentKind.VIDEO -> "▶ Video$size"
        AttachmentKind.AUDIO -> "▶ Audio message$size"
        AttachmentKind.FILE -> (attachment.filename ?: "File") + size
    }
}

/** One-line description of a message, for previews, quotes and notifications. */
fun messageSummary(message: Message?): String {
    if (message == null) return "No messages yet"
    if (message.deleted) return "Message deleted"
    message.text?.let { return withoutSpoilers(it, message.spans) }
    if (message.poll != null) return "Poll: ${message.poll!!.question}"
    if (message.stickerPath != null) return "Sticker"
    val first = message.attachments.firstOrNull()
    if (first?.sticker == true) return "Sticker"
    return when (first?.kind) {
        AttachmentKind.IMAGE -> "Photo"
        AttachmentKind.VIDEO -> "Video"
        AttachmentKind.AUDIO -> "Voice note"
        AttachmentKind.FILE -> "Attachment"
        null -> "Message"
    }
}

/** Spoiler text hidden behind shade blocks, one per character. */
fun withoutSpoilers(text: String, spans: List<TextSpan>): String {
    val hidden = spans.filter { it.style == TextSpan.Style.SPOILER }
    if (hidden.isEmpty()) return text
    val out = StringBuilder(text)
    hidden.forEach { span ->
        for (i in span.start until span.end.coerceAtMost(text.length)) {
            // One block per character: the low half of a surrogate pair goes.
            out.setCharAt(i, if (Character.isLowSurrogate(text[i])) '\u0000' else '░')
        }
    }
    return out.toString().replace("\u0000", "")
}

/**
 * A message's text with its styles: bold, italic, struck through, monospaced
 * (on a panel), mentions (bold, in the sender colour) and spoilers, which
 * stay shaded unless [revealed]. Each piece is prepared for the pixel fonts.
 */
fun styledText(text: String, spans: List<TextSpan>, revealed: Boolean): AnnotatedString {
    if (spans.isEmpty()) return AnnotatedString(text.forDisplay())
    val cuts = (spans.flatMap { listOf(it.start, it.end) } + listOf(0, text.length))
        .map { it.coerceIn(0, text.length) }
        .map { if (it in 1 until text.length && Character.isLowSurrogate(text[it])) it - 1 else it }
        .distinct().sorted()
    return buildAnnotatedString {
        cuts.zipWithNext().forEach { (from, to) ->
            val styles = spans.filter { it.start <= from && it.end >= to }.map { it.style }.toSet()
            val piece = text.substring(from, to)
            val hidden = TextSpan.Style.SPOILER in styles && !revealed
            val decorations = listOfNotNull(
                TextDecoration.LineThrough.takeIf { TextSpan.Style.STRIKE in styles },
                TextDecoration.Underline.takeIf { TextSpan.Style.UNDERLINE in styles },
            )
            val style = SpanStyle(
                fontFamily = Fonts.dotBold.takeIf { TextSpan.Style.BOLD in styles || TextSpan.Style.MENTION in styles },
                fontStyle = FontStyle.Italic.takeIf { TextSpan.Style.ITALIC in styles },
                textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                background = when {
                    hidden -> Palette.muted
                    TextSpan.Style.MONO in styles -> Palette.panel
                    else -> androidx.compose.ui.graphics.Color.Unspecified
                },
                color = when {
                    hidden -> Palette.muted
                    TextSpan.Style.MENTION in styles -> Palette.sender
                    else -> androidx.compose.ui.graphics.Color.Unspecified
                },
            )
            withStyle(style) { append(if (hidden) withoutSpoilers(piece, listOf(TextSpan(0, piece.length, TextSpan.Style.SPOILER))) else piece.forDisplay()) }
        }
    }
}

/** Whether a message has spoilers to reveal. */
fun hasSpoilers(message: Message) = message.spans.any { it.style == TextSpan.Style.SPOILER }
