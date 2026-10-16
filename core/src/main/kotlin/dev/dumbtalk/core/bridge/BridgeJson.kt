package dev.dumbtalk.core.bridge

import dev.dumbtalk.core.Attachment
import dev.dumbtalk.core.AttachmentKind
import dev.dumbtalk.core.Contact
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.ConversationKind
import dev.dumbtalk.core.ConversationPage
import dev.dumbtalk.core.Direction
import dev.dumbtalk.core.Message
import dev.dumbtalk.core.Poll
import dev.dumbtalk.core.Quote
import dev.dumbtalk.core.Reaction
import dev.dumbtalk.core.Receipt
import dev.dumbtalk.core.SearchResult
import dev.dumbtalk.core.ServiceId
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps the JSON the native bridges (WhatsApp in Go, Signal in Rust) return.
 * Both use the same shape:
 *
 * chat: {jid, name, group, archived, pinned, muted, unread, lastTs, last?, noteToSelf}
 * message: {id, chat, sender, senderName, fromMe, ts, kind, text, filename?, mime?,
 *   size?, quoteId?, quoteSender?, quoteText?, edited, deleted, forwarded?, status?,
 *   poll?, reactions: [{emoji, name, fromMe}]}
 */
class BridgeJson(private val service: ServiceId, private val typingNames: (String) -> List<String>) {

    fun conversationId(chat: String) = "${service.key}:$chat"

    fun page(encoded: String): ConversationPage {
        val page = JSONObject(encoded)
        return ConversationPage(page.optJSONArray("chats").objects().map(::conversation), page.optInt("archivedCount"))
    }

    fun conversation(encoded: String): Conversation = conversation(JSONObject(encoded))

    fun conversation(chat: JSONObject): Conversation {
        val jid = chat.getString("jid")
        return Conversation(
            id = conversationId(jid),
            serviceId = service,
            remoteId = jid,
            kind = if (chat.optBoolean("group")) ConversationKind.GROUP else ConversationKind.DIRECT,
            title = chat.optString("name").ifBlank { jid.substringBefore('@') },
            isNoteToSelf = chat.optBoolean("noteToSelf"),
            isArchived = chat.optBoolean("archived"),
            isFavourite = chat.optBoolean("pinned"),
            isMuted = chat.optBoolean("muted"),
            unreadCount = chat.optInt("unread"),
            typingNames = typingNames(jid),
            lastMessage = chat.optJSONObject("last")?.let(::message),
        )
    }

    fun messages(encoded: String): List<Message> = JSONArray(encoded).objects().map(::message)

    fun message(encoded: String): Message = message(JSONObject(encoded))

    fun message(json: JSONObject): Message {
        val kind = json.optString("kind")
        val fromMe = json.optBoolean("fromMe")
        val attachmentKind = when (kind) {
            "image" -> AttachmentKind.IMAGE
            "video" -> AttachmentKind.VIDEO
            "audio" -> AttachmentKind.AUDIO
            "document" -> AttachmentKind.FILE
            "sticker" -> AttachmentKind.IMAGE
            else -> null
        }
        return Message(
            id = json.getString("id"),
            conversationId = conversationId(json.getString("chat")),
            sentAt = json.getLong("ts"),
            direction = when {
                kind == "system" -> Direction.SYSTEM
                fromMe -> Direction.OUTGOING
                else -> Direction.INCOMING
            },
            sender = if (fromMe) "You" else json.optString("senderName").ifBlank { null },
            text = json.optString("text").ifBlank { null },
            attachments = listOfNotNull(attachmentKind?.let {
                Attachment(
                    id = json.getString("id"),
                    kind = it,
                    contentType = json.optString("mime").ifBlank { null },
                    filename = json.optString("filename").ifBlank { null },
                    size = json.optLong("size").takeIf { size -> size > 0 },
                    sticker = kind == "sticker",
                )
            }),
            reactions = json.optJSONArray("reactions").objects().map {
                val own = it.optBoolean("fromMe")
                Reaction(it.getString("emoji"), if (own) "You" else it.optString("name"), own)
            },
            receipt = if (fromMe) receipt(json.optString("status")) else null,
            quote = json.optString("quoteId").takeIf { it.isNotEmpty() }?.let {
                Quote(json.optString("quoteSender").ifBlank { "Message" }, json.optString("quoteText").ifBlank { null })
            },
            edited = json.optBoolean("edited"),
            deleted = json.optBoolean("deleted"),
            poll = json.optString("poll").takeIf { it.isNotEmpty() }?.let(::poll),
            forwardedFrom = if (json.optBoolean("forwarded")) "another chat" else null,
        )
    }

    fun contacts(encoded: String): List<Contact> = JSONArray(encoded).objects().map {
        Contact(it.getString("id"), service, it.getString("name"), it.optString("detail").ifBlank { null })
    }

    fun searchResults(encoded: String, conversation: Conversation?): List<SearchResult> =
        messages(encoded)
            .filter { conversation == null || it.conversationId == conversation.id }
            .map { SearchResult(it.id, it.conversationId, it.sender.orEmpty(), it.text.orEmpty(), it.sentAt) }

    private fun receipt(status: String) = when (status) {
        "sent" -> Receipt(Receipt.State.SENT)
        "delivered" -> Receipt(Receipt.State.DELIVERED)
        "read" -> Receipt(Receipt.State.READ)
        else -> null // pending
    }

    private fun poll(encoded: String): Poll {
        val json = JSONObject(encoded)
        val options = json.optJSONArray("options") ?: JSONArray()
        return Poll(
            question = json.optString("question"),
            options = (0 until options.length()).map { Poll.Option(it, options.getString(it), emptyList()) },
            multiple = json.optBoolean("multiple"),
            closed = false,
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}

/** Typing notices from a bridge, which lapse unless refreshed. */
class TypingTracker(private val lapseMillis: Long = 6_000) {
    private val typing = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Long>>()

    fun update(chat: String, name: String, active: Boolean) {
        val names = typing.getOrPut(chat) { java.util.concurrent.ConcurrentHashMap() }
        if (active) names[name] = System.currentTimeMillis() + lapseMillis else names.remove(name)
    }

    fun names(chat: String): List<String> {
        val now = System.currentTimeMillis()
        return typing[chat].orEmpty().filterValues { it > now }.keys.toList()
    }

    val lapse get() = lapseMillis
}
