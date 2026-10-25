package dev.dumbtalk.signal

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.Message
import dev.dumbtalk.core.OutgoingAttachment
import dev.dumbtalk.core.ServiceId
import dev.dumbtalk.core.SetupStep
import dev.dumbtalk.core.bridge.BridgeException
import dev.dumbtalk.core.bridge.BridgeService
import dev.dumbtalk.core.bridge.NativeBridge
import java.io.File
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import uniffi.sigbridge.Listener
import uniffi.sigbridge.SignalClient

/**
 * Signal as a linked device, through the Rust bridge on presage. Signal
 * links only by scanning a QR code (an opened link is refused by design).
 */
class SignalService(context: Context, dataDir: File, deviceName: String, scope: CoroutineScope) :
    BridgeService(ServiceId.SIGNAL, scope) {

    /** Set once the bridge has started; [bridge] calls wait for that. */
    private lateinit var client: SignalClient

    init {
        val appContext = context.applicationContext
        val listener = object : Listener {
            override fun onEvent(kind: String, payload: String) = onBridgeEvent(kind, payload)
        }
        start({
            SignalNative.init(appContext)
            client = SignalClient(dataDir.absolutePath, deviceName, listener)
            RustBridge(client)
        }) { client.connect() }
    }

    override suspend fun sendAttachments(conversation: Conversation, files: List<OutgoingAttachment>, caption: String?, replyTo: Message?) {
        val encoded = org.json.JSONArray()
        files.forEach { file ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (file.mimeType.startsWith("image/")) BitmapFactory.decodeFile(file.path, bounds)
            encoded.put(JSONObject().put("path", file.path).put("mime", file.mimeType)
                .put("width", bounds.outWidth.takeIf { it > 0 }).put("height", bounds.outHeight.takeIf { it > 0 }))
        }
        bridge { client.sendMediaBatch(conversation.remoteId, encoded.toString(), caption.orEmpty(), replyTo?.id.orEmpty()) }
    }

    override suspend fun sendAttachment(conversation: Conversation, path: String, mimeType: String, caption: String?) =
        sendAttachments(conversation, listOf(OutgoingAttachment(path, mimeType)), caption)

    /** Reactions name people by ACI; their chat is "c:<aci>". */
    override fun personChat(personId: String) = if (personId.contains(':')) personId else "c:$personId"

    override fun log(message: String) {
        Log.w("Signal", message)
    }

    /** Signal's own apps send voice notes as AAC. */
    override suspend fun capabilities() = super.capabilities().copy(
        voiceNoteMime = "audio/aac", pins = true, polls = true, closingPolls = true,
        mentions = true, disappearingMessages = true, messageRequests = true, blocking = true, groups = true,
        // Signal's own choices: 30 seconds to 4 weeks.
        disappearingDurations = listOf(30, 300, 3_600, 28_800, 86_400, 604_800, 2_419_200),
    )

    override suspend fun beginSetup(): SetupStep {
        if (bridge { isLinked() }) return complete()
        return startLink()
    }

    override suspend fun advanceSetup(step: SetupStep, value: String?): SetupStep =
        if (step is SetupStep.Qr) nextLinkStep() else step

    private suspend fun startLink(): SetupStep {
        resetLinkEvents()
        bridge { client.startLink() }
        return nextLinkStep()
    }

    private suspend fun nextLinkStep(): SetupStep {
        val (kind, payload) = nextLinkEvent()
        return when (kind) {
            "qr" -> SetupStep.Qr(
                "qr", "Scan QR code",
                "On your main phone open Signal → Settings → Linked devices → Link new device, then scan this code. " +
                    "Signal only links by scanning, so if Signal is on this phone, show this code on another screen. " +
                    "Old messages don't come across: chats start from now.",
                payload.getString("code"),
            )
            "paired" -> complete()
            else -> {
                val error = payload.optString("error", "Linking failed")
                // The link connection lapses if nobody scans for a while: start again.
                if ("no provisioning message" in error) startLink() else throw BridgeException(error)
            }
        }
    }

    private fun complete() = SetupStep.Complete(
        "Signal connected",
        "Signal doesn't share old messages with new devices, so chats start from now. Signal unlinks devices " +
            "it hasn't heard from in about 30 days; keeping DumbTalk connected counts.",
    )
}

/** Adapts the UniFFI-generated client to [NativeBridge]. */
private class RustBridge(private val client: SignalClient) : NativeBridge {
    override fun isLinked() = client.isLinked()
    override fun isOnline() = JSONObject(client.accountJson()).optBoolean("online")
    override fun accountJson(): String = client.accountJson()
    override fun logout() = client.logout()
    override fun reconnect(networkChanged: Boolean) = client.reconnect(networkChanged)
    override fun chatsJson(archived: Boolean): String = client.chatsJson(archived)
    override fun chatJson(chat: String): String = client.chatJson(chat)
    override fun messagesJson(chat: String, beforeTs: Long, beforeId: String, limit: Long): String =
        client.messagesJson(chat, beforeTs, beforeId, limit)
    override fun pinnedMessagesJson(chat: String): String = client.pinnedMessagesJson(chat)
    override fun pinMessage(chat: String, id: String, pinned: Boolean) = client.pinMessage(chat, id, pinned)
    override fun createPoll(chat: String, question: String, optionsJson: String, multiple: Boolean): String = client.createPoll(chat, question, optionsJson, multiple)
    override fun votePoll(chat: String, id: String, optionsJson: String) = client.votePoll(chat, id, optionsJson)
    override fun closePoll(chat: String, id: String) = client.closePoll(chat, id)
    override fun searchJson(query: String): String = client.searchJson(query)
    override fun sendText(chat: String, text: String, quoteId: String, spansJson: String): String = client.sendText(chat, text, quoteId, spansJson)
    override fun retry(chat: String, id: String): String = client.retry(chat, id)
    override fun receiptsJson(chat: String, id: String): String = client.receiptsJson(chat, id)
    override fun membersJson(chat: String): String = client.membersJson(chat)
    override fun setExpireTimer(chat: String, seconds: Int) = client.setExpireTimer(chat, seconds.toUInt())
    override fun respondRequest(chat: String, action: String) = client.respondRequest(chat, action)
    override fun createGroup(name: String, membersJson: String): String = client.createGroup(name, membersJson)
    override fun renameGroup(chat: String, name: String) = client.renameGroup(chat, name)
    override fun addMembers(chat: String, membersJson: String) = client.addMembers(chat, membersJson)
    override fun removeMember(chat: String, member: String) = client.removeMember(chat, member)
    override fun leaveGroup(chat: String) = client.leaveGroup(chat)
    override fun react(chat: String, id: String, emoji: String, remove: Boolean) = client.react(chat, id, emoji, remove)
    override fun edit(chat: String, id: String, text: String) = client.edit(chat, id, text)
    override fun delete(chat: String, id: String) = client.delete(chat, id)
    override fun markRead(chat: String) = client.markRead(chat)
    override fun setTyping(chat: String, active: Boolean) = client.setTyping(chat, active)
    override fun setArchived(chat: String, value: Boolean) = client.setArchived(chat, value)
    override fun setPinned(chat: String, value: Boolean) = client.setPinned(chat, value)
    override fun setMuted(chat: String, value: Boolean) = client.setMuted(chat, value)
    override fun resolvePhone(phone: String): String = client.resolvePhone(phone)
    override fun contactsJson(): String = client.contactsJson()
    override fun conversationAvatar(chat: String): String = client.conversationAvatar(chat)
    override fun downloadMedia(chat: String, id: String): String = client.downloadMedia(chat, id)
    override fun downloadMediaAt(chat: String, id: String, index: Int): String = client.downloadAttachment(chat, id, index.toUInt())
    override fun sendMedia(chat: String, path: String, mime: String, caption: String, voiceNote: Boolean): String =
        client.sendMedia(chat, path, mime, caption, voiceNote)
}
