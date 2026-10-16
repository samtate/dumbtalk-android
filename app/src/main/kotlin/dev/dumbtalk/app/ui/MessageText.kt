package dev.dumbtalk.app.ui

import dev.dumbtalk.core.AttachmentKind
import dev.dumbtalk.core.Message

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
    message.text?.let { return it }
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
