package dev.dumbtalk.whatsapp

import android.util.Log
import dev.dumbtalk.core.ServiceId
import dev.dumbtalk.core.SetupStep
import dev.dumbtalk.core.bridge.BridgeException
import dev.dumbtalk.core.bridge.BridgeService
import dev.dumbtalk.core.bridge.NativeBridge
import dev.dumbtalk.whatsapp.go.wabridge.Client
import dev.dumbtalk.whatsapp.go.wabridge.Listener
import dev.dumbtalk.whatsapp.go.wabridge.Wabridge
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * WhatsApp as a linked device, through the Go bridge on whatsmeow. Links by
 * QR, or by phone number with a pairing code (which works with WhatsApp on
 * the same phone).
 */
class WhatsAppService(dataDir: File, deviceName: String, scope: CoroutineScope) :
    BridgeService(ServiceId.WHATSAPP, scope) {

    /** Set once the bridge has started; [bridge] calls wait for that. */
    private lateinit var client: Client

    init {
        val listener = object : Listener {
            override fun onEvent(kind: String, payload: String) = onBridgeEvent(kind, payload)
        }
        start({
            client = Wabridge.newClient(dataDir.absolutePath, deviceName, listener)
            GoBridge(client)
        }) { client.connect() }
    }

    override fun log(message: String) {
        Log.w("WhatsApp", message)
    }

    override suspend fun capabilities() = super.capabilities().copy(
        pins = true, polls = true, directPolls = true, mentions = true, disappearingMessages = true, blocking = true, groups = true,
        // WhatsApp offers 24 hours, 7 days and 90 days.
        disappearingDurations = listOf(86_400, 604_800, 7_776_000),
    )

    override suspend fun beginSetup(): SetupStep {
        if (bridge { isLinked() }) return complete()
        return SetupStep.Choice(
            "method", "Link WhatsApp", "Choose how to link this phone.",
            listOf(
                SetupStep.Choice.Option("qr", "QR code", "Scan from WhatsApp on your main phone"),
                SetupStep.Choice.Option("phone", "Phone number", "Type a pairing code instead"),
            ),
        )
    }

    override suspend fun advanceSetup(step: SetupStep, value: String?): SetupStep = when (step) {
        is SetupStep.Choice -> if (value == "phone") {
            SetupStep.Input(
                "phone", "Phone number", "Enter your WhatsApp number with its country code.",
                SetupStep.Input.Field.PHONE, placeholder = "+44…",
            )
        } else {
            startLink("")
        }
        is SetupStep.Input -> {
            val phone = value.orEmpty().filter { it.isDigit() }
            if (phone.length < 7) throw BridgeException("Enter the full number with its country code")
            startLink(phone)
        }
        is SetupStep.Qr -> nextLinkStep("")
        is SetupStep.PairCode -> nextLinkStep(step.phone)
        is SetupStep.Complete -> step
    }

    private suspend fun startLink(phone: String): SetupStep {
        resetLinkEvents()
        bridge { client.startLink(phone) }
        return nextLinkStep(phone)
    }

    /** QR codes refresh roughly every 20 seconds; each refresh is a new step. */
    private suspend fun nextLinkStep(phone: String): SetupStep {
        val (kind, payload) = nextLinkEvent()
        return when (kind) {
            "qr" -> SetupStep.Qr(
                "qr", "Scan QR code",
                "On your main phone open WhatsApp → Settings → Linked devices → Link a device, then scan this code.",
                payload.getString("code"),
            )
            "pair-code" -> SetupStep.PairCode(
                "pair", "Pairing code",
                "On your main phone open WhatsApp → Settings → Linked devices → Link a device → Link with phone number instead, then enter this code.",
                payload.getString("code").chunked(4).joinToString("-"),
                phone,
            )
            "paired" -> complete()
            else -> throw BridgeException(payload.optString("error", "Linking failed"))
        }
    }

    private fun complete() = SetupStep.Complete(
        "WhatsApp connected",
        "Recent chats arrive from your main phone over the next few minutes.",
    )
}

/** Adapts the gomobile-generated client to [NativeBridge]. */
private class GoBridge(private val client: Client) : NativeBridge {
    override fun isLinked() = client.isLinked
    override fun isOnline() = client.isConnected
    override fun accountJson(): String = client.accountJSON()
    override fun logout() = client.logout()
    // whatsmeow reconnects by itself once connected; this covers a first connection that failed.
    override fun reconnect() = client.connect()
    override fun chatsJson(archived: Boolean): String = client.chatsJSON(archived)
    override fun chatJson(chat: String): String = client.chatJSON(chat)
    override fun messagesJson(chat: String, beforeTs: Long, beforeId: String, limit: Long): String =
        client.messagesJSON(chat, beforeTs, beforeId, limit)
    override fun pinnedMessagesJson(chat: String): String = client.pinnedMessagesJSON(chat)
    override fun pinMessage(chat: String, id: String, pinned: Boolean) = client.pinMessage(chat, id, pinned)
    override fun createPoll(chat: String, question: String, optionsJson: String, multiple: Boolean): String = client.createPoll(chat, question, optionsJson, multiple)
    override fun votePoll(chat: String, id: String, optionsJson: String) = client.votePoll(chat, id, optionsJson)
    override fun searchJson(query: String): String = client.searchJSON(query)
    override fun sendText(chat: String, text: String, quoteId: String, spansJson: String): String = client.sendText(chat, text, quoteId, spansJson)
    override fun membersJson(chat: String): String = client.membersJSON(chat)
    override fun setExpireTimer(chat: String, seconds: Int) = client.setExpireTimer(chat, seconds.toLong())
    override fun setBlocked(chat: String, blocked: Boolean) = client.setBlocked(chat, blocked)
    override fun createGroup(name: String, membersJson: String): String = client.createGroup(name, membersJson)
    override fun renameGroup(chat: String, name: String) = client.renameGroup(chat, name)
    override fun addMembers(chat: String, membersJson: String) = client.addMembers(chat, membersJson)
    override fun removeMember(chat: String, member: String) = client.removeMember(chat, member)
    override fun leaveGroup(chat: String) = client.leaveGroup(chat)
    override fun react(chat: String, id: String, emoji: String, remove: Boolean) = client.react(chat, id, if (remove) "" else emoji)
    override fun edit(chat: String, id: String, text: String) = client.edit(chat, id, text)
    override fun delete(chat: String, id: String) = client.revoke(chat, id)
    override fun markRead(chat: String) = client.markRead(chat)
    override fun setTyping(chat: String, active: Boolean) = client.setTyping(chat, active)
    override fun openChat(chat: String) = client.openChat(chat)
    override fun closeChat() = client.closeChat()
    override fun setArchived(chat: String, value: Boolean) = client.setArchived(chat, value)
    override fun setPinned(chat: String, value: Boolean) = client.setPinned(chat, value)
    override fun setMuted(chat: String, value: Boolean) = client.setMuted(chat, value)
    override fun resolvePhone(phone: String): String = client.resolvePhone(phone)
    override fun contactsJson(): String = client.contactsJSON()
    override fun downloadMedia(chat: String, id: String): String = client.downloadMedia(chat, id)
    override fun conversationAvatar(chat: String): String = client.conversationAvatar(chat)
    override fun sendMedia(chat: String, path: String, mime: String, caption: String, voiceNote: Boolean): String =
        client.sendMedia(chat, path, mime, caption, voiceNote)
}
