package dev.dumbtalk.app.sms

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.UserManager
import android.provider.Telephony
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.util.Log
import dev.dumbtalk.app.MainActivity
import dev.dumbtalk.app.R
import dev.dumbtalk.app.background.MessageNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Runs in :sms, so delivery never waits for fonts or any network bridge. */
class SmsDeliveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) return
        val first = parts.first()
        val address = first.originatingAddress ?: return
        val payload = JSONObject().apply {
            put("address", address); put("body", parts.joinToString("") { it.messageBody.orEmpty() })
            put("date", first.timestampMillis)
            put("subscription", intent.getIntExtra("subscription", intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1)))
        }
        val pending = goAsync()
        SmsWork.scope.launch {
            try {
                // Durable before provider/contact work. Also protects deliveries received
                // before first unlock, when credential-protected storage is unavailable.
                val directory = SmsWork.pendingDirectory(context)
                directory.mkdirs()
                val file = File(directory, "${UUID.randomUUID()}.json")
                file.outputStream().use { output -> output.write(payload.toString().toByteArray()); output.fd.sync() }
                if (context.getSystemService(UserManager::class.java).isUserUnlocked) SmsWork.deliver(context, file)
                else SmsWork.notice(context, "SMS received", "Unlock your phone to view your SMS.", "sms-pending")
            } catch (failure: Exception) {
                Log.e("SMS", "Delivery could not be committed", failure)
                SmsWork.notice(context, "SMS needs attention", "DumbTalk could not store an incoming SMS. Open the app and check SMS permissions.", "sms-error")
            } finally { pending.finish() }
        }
    }
}

class SmsUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_UNLOCKED && intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        SmsWork.scope.launch {
            try { SmsWork.drain(context) } finally { pending.finish() }
        }
    }
}

/** Required default-app component. MMS is explicitly outside this version. */
class UnsupportedMmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val pending = goAsync()
        SmsWork.scope.launch {
            try {
                intent.getByteArrayExtra("data")?.let { data ->
                    val directory = File(context.createDeviceProtectedStorageContext().filesDir, "pending-mms").apply { mkdirs() }
                    File(directory, "${UUID.randomUUID()}.pdu").outputStream().use { it.write(data); it.fd.sync() }
                }
                SmsWork.notice(context, "MMS is not supported", "A multimedia message arrived. DumbTalk currently handles plain SMS only.", "sms-mms")
            } finally { pending.finish() }
        }
    }
}

class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmsService.SENT && intent.action != SmsService.DELIVERED) return
        val row = intent.getLongExtra("row", -1)
        val token = intent.getStringExtra("token") ?: return
        val part = intent.getIntExtra("part", -1)
        if (row < 0 || part < 0) return
        val result = resultCode
        val pending = goAsync()
        SmsWork.scope.launch {
            try {
                SmsWork.resultLock.withLock {
                    val delivered = intent.action == SmsService.DELIVERED
                    val pdu = intent.getByteArrayExtra("pdu")
                    val deliveryStatus = if (delivered && pdu != null) SmsMessage.createFromPdu(pdu, intent.getStringExtra("format") ?: "3gpp")?.status else null
                    if (delivered && deliveryStatus != null && deliveryStatus in 32..63) return@withLock
                    val code = if (result == Activity.RESULT_OK && (!delivered || deliveryStatus == null || deliveryStatus < 32)) 0 else 1
                    val store = SmsStore(context)
                    try {
                        val aggregate = store.result(row, token, part, if (delivered) "delivered" else "sent", code) ?: return@withLock
                        val (failed, complete) = aggregate
                        val values = ContentValues()
                        if (delivered) {
                            if (failed) values.put("status", Telephony.Sms.STATUS_FAILED)
                            else if (complete) values.put("status", Telephony.Sms.STATUS_COMPLETE)
                        } else {
                            if (failed) {
                                values.put("type", Telephony.Sms.MESSAGE_TYPE_FAILED)
                                values.put("status", Telephony.Sms.STATUS_FAILED)
                            } else if (complete) values.put("type", Telephony.Sms.MESSAGE_TYPE_SENT)
                        }
                        val updated = if (values.size() > 0) context.contentResolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, row), values, null, null) else 0
                        if (failed && updated > 0) SmsWork.notice(context, "SMS not sent or delivered", "Open DumbTalk to check the message and retry.", "sms-failed-$row")
                    } finally { store.close() }
                }
            } catch (failure: Exception) { Log.e("SMS", "Could not update SMS result", failure) }
            finally { pending.finish() }
        }
    }
}

/** The phone app's explicit 'respond with text' action. */
class SmsRespondService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != "android.intent.action.RESPOND_VIA_MESSAGE") { stopSelf(startId); return START_NOT_STICKY }
        val address = intent.data?.schemeSpecificPart?.substringBefore('?')
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (address.isNullOrBlank() || text.isNullOrBlank()) { stopSelf(startId); return START_NOT_STICKY }
        SmsWork.scope.launch {
            val sms = SmsService(this@SmsRespondService)
            try { sms.sendText(sms.createDirect(Uri.decode(address)), text) }
            catch (failure: Exception) { Log.e("SMS", "Respond via SMS failed", failure); SmsWork.notice(this@SmsRespondService, "SMS not sent", "Open DumbTalk to check SMS setup.", "sms-respond-failed") }
            finally { sms.store.close(); stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
}

internal object SmsWork {
    val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    val resultLock = Mutex()
    private val deliveryLock = Mutex()
    fun pendingDirectory(context: Context) = File(context.createDeviceProtectedStorageContext().filesDir, "pending-sms")
    suspend fun deliver(context: Context, file: File) = deliveryLock.withLock {
        val payload = JSONObject(file.readText())
        val sms = SmsService(context)
        try {
            if (!sms.isDefault()) return@withLock // Keep queued deliveries until the user re-enables SMS.
            val (chat, message) = sms.received(payload.getString("address"), payload.getString("body"), payload.getLong("date"), payload.getInt("subscription"))
            MessageNotifier(context).show(chat, message)
            file.delete()
        } finally { sms.store.close() }
    }
    suspend fun drain(context: Context) {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        pendingDirectory(context).listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name }?.forEach {
            runCatching { deliver(context, it) }.onFailure { error -> Log.e("SMS", "Could not restore queued SMS", error) }
        }
        if (pendingDirectory(context).listFiles().isNullOrEmpty()) context.getSystemService(NotificationManager::class.java).cancel("sms-pending", 30)
    }
    fun notice(context: Context, title: String, text: String, tag: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("sms-status", "SMS status", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(tag, 30, Notification.Builder(context, "sms-status").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
    }
}
