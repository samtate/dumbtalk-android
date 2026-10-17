package dev.dumbtalk.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.app.Application
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import dev.dumbtalk.app.sms.*
import dev.dumbtalk.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowSubscriptionManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmsServiceTest {
    private lateinit var context: Context
    private lateinit var provider: TestSmsProvider
    private lateinit var sms: SmsService
    private lateinit var manager: SmsManager

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CONTACTS)
        shadowOf(context.getSystemService(RoleManager::class.java)).addHeldRole(RoleManager.ROLE_SMS)
        provider = TestSmsProvider().also { it.onCreate() }
        ShadowContentResolver.registerProviderInternal("sms", provider)
        ShadowContentResolver.registerProviderInternal("mms-sms", provider)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(1)
        @Suppress("DEPRECATION")
        manager = SmsManager.getSmsManagerForSubscriptionId(1)
        sms = SmsService(context) { manager }
    }

    @Test fun receivedMessagesPersistAndDuplicateDeliveryDoesNotDuplicateHistory() = runBlocking {
        val first = sms.received("+15550123", "hello", 1000L, 1)
        val second = sms.received("+15550123", "hello", 1000L, 1)
        assertEquals(first.second.id, second.second.id)
        val fresh = SmsService(context)
        assertEquals("hello", fresh.listMessages(first.first).messages.single().text)
        val chat = fresh.listConversations(false).conversations.single()
        assertEquals(1, chat.unreadCount)
        fresh.markRead(chat)
        assertEquals(0, fresh.listConversations(false).conversations.single().unreadCount)
        fresh.updateConversation(chat, ConversationUpdate(archived = true, muted = true))
        assertTrue(fresh.listConversations(false).conversations.isEmpty())
        assertTrue(SmsService(context).listConversations(true).conversations.single().isMuted)
    }

    @Test fun longUnicodeTextUsesMultipartTransportAndStartsPending() = runBlocking {
        val chat = sms.createDirect("+15550123")
        val text = "Hello 🌍 ".repeat(45)
        val message = sms.sendText(chat, text)
        val sent = shadowOf(manager).lastSentMultipartTextMessageParams
        assertEquals("+15550123", sent.destinationAddress)
        assertEquals(text, sent.parts.joinToString(""))
        assertTrue(sent.parts.size > 1)
        assertEquals(sent.parts.size, sent.sentIntents.size)
        assertEquals(sent.parts.size, sent.deliveryIntents.size)
        assertNull(message.receipt) // Pending is not falsely presented as sent/read.
        assertFalse(sms.capabilities().replies)
        assertFalse(sms.capabilities().attachments)
    }

    @Test fun multipartFailureSurvivesRestartAndOldRetryCallbacksAreIgnored() {
        val store = SmsStore(context)
        val token = store.beginAttempt(3, 2)
        assertEquals(false to false, store.result(3, token, 0, "sent", 0))
        store.close()
        val fresh = SmsStore(context)
        assertEquals(true to false, fresh.result(3, token, 1, "sent", 1))
        val retry = fresh.beginAttempt(3, 1)
        assertNull(fresh.result(3, token, 1, "sent", 0))
        assertEquals(false to true, fresh.result(3, retry, 0, "sent", 0))
        assertEquals(false to true, fresh.result(3, retry, 0, "delivered", 0))
        fresh.close()
    }

    @Test fun queuedDeliveryPostsNotificationWithoutStartingDumbTalkApplicationOrBridges() = runBlocking {
        val directory = SmsWork.pendingDirectory(context).apply { mkdirs() }
        val file = java.io.File(directory, "incoming.json").apply {
            writeText(JSONObject().put("address", "+15550123").put("body", "background delivery")
                .put("date", 1234L).put("subscription", 1).toString())
        }
        SmsWork.drain(context)
        assertFalse(file.exists())
        assertEquals("background delivery", sms.listConversations(false).conversations.single().lastMessage?.text)
        val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
        assertTrue(notifications.any { it.tag == "sms:1" })
        assertFalse(context.applicationContext is DumbTalkApp)
    }

    @Test fun deliveryBroadcastPersistsAndNotifiesWithNoMainApplication() = runBlocking {
        // A GSM SMS-DELIVER PDU, UTF-16 text "hello", from +15550100000.
        val pdu = byteArrayOf(0, 0, 11, 0x91.toByte(), 0x51, 0x55, 0x10, 0, 0, 0xf0.toByte(),
            0, 8, 0x62, 0x01, 0x71, 0, 0, 0, 0, 10) + "hello".toByteArray(Charsets.UTF_16BE)
        context.sendBroadcast(Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION)
            .setComponent(ComponentName(context, SmsDeliveryReceiver::class.java))
            .putExtra("pdus", arrayOf(pdu)).putExtra("format", "3gpp").putExtra("subscription", 1))
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == "sms:1" }) {
                assertEquals("hello", sms.listConversations(false).conversations.single().lastMessage?.text)
                assertFalse(context.applicationContext is DumbTalkApp)
                return@runBlocking
            }
            delay(10)
        }
        fail("The cold delivery receiver did not post its SMS notification")
    }

    @Test fun carrierFailuresAndDeliveryCallbacksUpdateTheStoredMessage() = runBlocking {
        val chat = sms.createDirect("+15550123")
        val message = sms.sendText(chat, "transport test")
        val firstAttempt = shadowOf(manager).lastSentMultipartTextMessageParams
        firstAttempt.sentIntents.first().send(context, SmsManager.RESULT_ERROR_NO_SERVICE, null)
        awaitReceipt(chat, Receipt.State.FAILED)
        sms.retryMessage(chat, message.copy(receipt = Receipt(Receipt.State.FAILED)))
        val retry = shadowOf(manager).lastSentMultipartTextMessageParams
        retry.sentIntents.forEach { it.send(context, Activity.RESULT_OK, null) }
        awaitReceipt(chat, Receipt.State.SENT)
        retry.deliveryIntents.forEach { it.send(context, Activity.RESULT_OK, null) }
        awaitReceipt(chat, Receipt.State.DELIVERED)
        assertEquals(1, sms.listMessages(chat).messages.size) // Retry reuses the failed row.
    }

    private suspend fun awaitReceipt(chat: Conversation, state: Receipt.State) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (sms.listMessages(chat).messages.last().receipt?.state == state) return
            delay(10)
        }
        fail("SMS receipt did not become $state")
    }

    @Test fun smsDeliveryManifestUsesProtectedLightweightProcess() {
        val receiver = context.packageManager.getReceiverInfo(ComponentName(context, SmsDeliveryReceiver::class.java), 0)
        assertTrue(receiver.processName.endsWith(":sms"))
        assertEquals("android.permission.BROADCAST_SMS", receiver.permission)
        assertTrue(receiver.directBootAware)
        val respond = context.packageManager.getServiceInfo(ComponentName(context, SmsRespondService::class.java), 0)
        assertEquals("android.permission.SEND_RESPOND_VIA_MESSAGE", respond.permission)
    }

    /** A local provider fixture, never a SIM/modem and never real SMS storage. */
    class TestSmsProvider : ContentProvider() {
        private lateinit var db: SQLiteDatabase
        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL("CREATE TABLE sms (_id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id INTEGER, address TEXT, body TEXT, date INTEGER, date_sent INTEGER, type INTEGER, status INTEGER DEFAULT -1, read INTEGER DEFAULT 0, seen INTEGER DEFAULT 0, sub_id INTEGER)")
            return true
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            if (uri.authority == "mms-sms") return MatrixCursor(arrayOf("_id")).apply { addRow(arrayOf(1L)) }
            if (uri.pathSegments.firstOrNull() == "conversations") return db.rawQuery("SELECT thread_id, body AS snippet, COUNT(*) AS msg_count FROM sms GROUP BY thread_id", null)
            val where = where(uri, selection)
            return db.query("sms", projection, where, selectionArgs, null, null, sortOrder)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri = android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, db.insertOrThrow("sms", null, values))
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = db.update("sms", values, where(uri, selection), selectionArgs)
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = db.delete("sms", where(uri, selection), selectionArgs)
        override fun getType(uri: Uri): String = "vnd.android.cursor.item/sms"
        private fun where(uri: Uri, selection: String?): String? {
            val row = uri.lastPathSegment?.toLongOrNull()
            return if (row == null) selection else "_id=$row" + if (selection != null) " AND ($selection)" else ""
        }
    }
}
