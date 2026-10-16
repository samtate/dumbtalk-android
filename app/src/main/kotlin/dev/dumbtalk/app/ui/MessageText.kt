package dev.dumbtalk.app.ui

import dev.dumbtalk.core.AttachmentKind
import dev.dumbtalk.core.Message

/** One-line description of a message, for previews, quotes and notifications. */
fun messageSummary(message: Message?): String {
    if (message == null) return "No messages yet"
    if (message.deleted) return "Message deleted"
    message.text?.let { return it }
    if (message.poll != null) return "Poll: ${message.poll!!.question}"
    if (message.stickerPath != null) return "Sticker"
    return when (message.attachments.firstOrNull()?.kind) {
        AttachmentKind.IMAGE -> "Photo"
        AttachmentKind.VIDEO -> "Video"
        AttachmentKind.AUDIO -> "Voice note"
        AttachmentKind.FILE -> "Attachment"
        null -> "Message"
    }
}
