package dev.dumbtalk.core

/**
 * Joins each run of messages from one album into the first of them, so an
 * album shows (and is acted on) as one message with all its photos. Captions
 * are kept in order; reactions from every part are shown together.
 */
fun mergeAlbums(messages: List<Message>): List<Message> {
    // WhatsApp's album parent only announces its parts; older builds stored it as unsupported.
    val parents = messages.mapNotNullTo(HashSet()) { it.albumId }
    val out = ArrayList<Message>(messages.size)
    for (message in messages) {
        if (message.id in parents && message.attachments.isEmpty()) continue
        val last = out.lastOrNull()
        val joins = message.albumId != null && last != null && last.albumId == message.albumId &&
            last.direction == message.direction && last.sender == message.sender && !last.deleted && !message.deleted
        if (!joins) {
            out += message
            continue
        }
        val (text, spans) = when {
            message.text == null -> last!!.text to last.spans
            last!!.text == null -> message.text to message.spans
            else -> (last.text + "\n" + message.text) to
                (last.spans + message.spans.map { it.copy(start = it.start + last.text.length + 1) })
        }
        out[out.lastIndex] = last.copy(
            text = text,
            spans = spans,
            attachments = last.attachments + message.attachments,
            reactions = last.reactions + message.reactions,
            receipt = message.receipt ?: last.receipt,
            quote = last.quote ?: message.quote,
            pinned = last.pinned || message.pinned,
            edited = last.edited || message.edited,
        )
    }
    return out
}
