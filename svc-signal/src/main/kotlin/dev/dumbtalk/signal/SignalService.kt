package dev.dumbtalk.signal

import android.content.Context
import android.util.Log
import dev.dumbtalk.core.ServiceId
import dev.dumbtalk.core.SetupStep
import dev.dumbtalk.core.bridge.BridgeException
import dev.dumbtalk.core.bridge.BridgeService
import dev.dumbtalk.core.bridge.NativeBridge
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import uniffi.sigbridge.Listener
import uniffi.sigbridge.SignalClient
import java.io.File

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

    override fun log(message: String) {
        Log.w("Signal", message)
    }

    /** Signal's own apps send voice notes as AAC. */
    override suspend fun capabilities() = super.capabilities().copy(voiceNoteMime = "audio/aac")

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
                    "Signal only links by scanning, so if Signal is on this phone, show this code on another screen.",
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
        "Signal doesn't share old messages with new devices, so chats start from now.",
    )
}

/** Adapts the UniFFI-generated client to [NativeBridge]. */
private class RustBridge(private val client: SignalClient) : NativeBridge {
    override fun isLinked() = client.isLinked()
    override fun isOnline() = JSONObject(client.accountJson()).optBoolean("online")
    override fun accountJson(): String = client.accountJson()
    override fun logout() = client.logout()
    override fun chatsJson(archived: Boolean): String = client.chatsJson(archived)
    override fun chatJson(chat: String): String = client.chatJson(chat)
    override fun messagesJson(chat: String, beforeTs: Long, beforeId: String, limit: Long): String =
        client.messagesJson(chat, beforeTs, beforeId, limit)
    override fun searchJson(query: String): String = client.searchJson(query)
    override fun sendText(chat: String, text: String, quoteId: String): String = client.sendText(chat, text, quoteId)
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
    override fun downloadMedia(chat: String, id: String): String = client.downloadMedia(chat, id)
    override fun sendMedia(chat: String, path: String, mime: String, caption: String, voiceNote: Boolean): String =
        client.sendMedia(chat, path, mime, caption, voiceNote)
}
