package dev.dumbtalk.app.sms

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.LruCache
import androidx.core.content.ContextCompat
import dev.dumbtalk.app.background.MessageNotifier
import dev.dumbtalk.core.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext

/** Android is the database and transport; no socket, polling loop or bridge. */
class SmsService(private val context: Context, private val managerForSubscription: ((Int) -> SmsManager)? = null) : MessagingService {
    override val id = ServiceId.SMS
    private val resolver = context.contentResolver
    internal val store = SmsStore(context)
    private val changes = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<ServiceEvent> = changes
    private var observing = false
    private val names = LruCache<String, Pair<String, String?>>(256)
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            changes.tryEmit(ServiceEvent.ConversationsChanged(id))
            visibleThread?.let { changes.tryEmit(ServiceEvent.MessagesChanged(id, "sms:$it")) }
        }
    }
    private var visibleThread: Long? = null

    fun isDefault(): Boolean = context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)
    fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun available(): Boolean = context.getSystemService(RoleManager::class.java).isRoleAvailable(RoleManager.ROLE_SMS)
    fun enabled(): Boolean = isDefault() && hasPermission(Manifest.permission.READ_SMS) && hasPermission(Manifest.permission.SEND_SMS) && hasPermission(Manifest.permission.RECEIVE_SMS)
    fun refresh() {
        if (enabled() && !observing) {
            resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            observing = true
        } else if (!enabled() && observing) {
            resolver.unregisterContentObserver(observer)
            observing = false
        }
        names.evictAll()
        changes.tryEmit(ServiceEvent.StatusChanged(id))
    }
    override suspend fun status(): ServiceStatus {
        return ServiceStatus(id, connected = enabled(), ready = enabled(), accountLabel = if (enabled()) "Default SMS app" else "Set up SMS on this phone")
    }
    override suspend fun capabilities() = Capabilities(deletes = true, search = true, compose = true, muting = true, replies = false, retries = true, deleteConversations = true)
    override suspend fun beginSetup(): SetupStep = SetupStep.Complete("SMS", "Enable SMS in Services to choose DumbTalk as your default SMS app.")
    override suspend fun advanceSetup(step: SetupStep, value: String?): SetupStep = beginSetup()
    override suspend fun disconnect() { error("Choose another default SMS app in Android settings. Your messages are kept.") }
    private fun checkEnabled() { check(enabled()) { "Make DumbTalk the default SMS app and allow SMS permissions first" } }
    private fun thread(conversation: Conversation) = conversation.remoteId.toLong()
    private fun rowId(message: Message) = message.id.substringAfterLast(':').toLong()
    private fun rowUri(message: Long) = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, message)

    override suspend fun listConversations(archived: Boolean): ConversationPage = withContext(Dispatchers.IO) {
        checkEnabled()
        val flags = store.chats()
        val unread = mutableMapOf<Long, Int>()
        resolver.query(Telephony.Sms.CONTENT_URI, arrayOf("thread_id"), "read=0 AND type=1", null, null)?.use {
            while (it.moveToNext()) { val key = it.getLong(0); unread[key] = (unread[key] ?: 0) + 1 }
        }
        val all = linkedMapOf<Long, Conversation>()
        resolver.query(Telephony.Sms.Conversations.CONTENT_URI, arrayOf("thread_id", "snippet", "msg_count"), null, null, null)?.use {
            while (it.moveToNext()) {
                val key = it.getLong(0)
                if (key in all) continue
                val message = latest(key) ?: continue
                val address = addressOf(rowId(message)) ?: continue
                all[key] = conversation(key, address, message, unread[key] ?: 0, flags[key])
            }
        }
        flags.forEach { (key, value) ->
            if (key !in all && value.address != null) all[key] = conversation(key, value.address, null, 0, value)
        }
        ConversationPage(all.values.filter { it.isArchived == archived }.sortedForInbox(), all.values.count { it.isArchived })
    }
    internal fun conversation(key: Long, address: String, last: Message? = latest(key), unread: Int = 1, flags: SmsStore.Flags? = store.chats()[key]): Conversation {
        return Conversation("sms:$key", id, key.toString(), ConversationKind.DIRECT, contact(address).first,
            isArchived = flags?.archived == true, isFavourite = flags?.favourite == true, isMuted = flags?.muted == true,
            unreadCount = unread, lastMessage = last)
    }
    private fun latest(key: Long): Message? = resolver.query(Telephony.Sms.CONTENT_URI, columns, "thread_id=? AND type<>3", arrayOf(key.toString()), "date DESC, _id DESC")?.use {
        if (it.moveToFirst()) map(it) else null
    }
    private fun addressOf(message: Long): String? = resolver.query(rowUri(message), arrayOf("address"), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    private fun map(c: Cursor): Message {
        val key = c.long("thread_id")
        val type = c.int("type")
        val receipt = when {
            type == Telephony.Sms.MESSAGE_TYPE_INBOX -> null
            type == Telephony.Sms.MESSAGE_TYPE_FAILED || c.int("status") >= Telephony.Sms.STATUS_FAILED -> Receipt(Receipt.State.FAILED)
            type != Telephony.Sms.MESSAGE_TYPE_SENT -> null
            c.int("status") == Telephony.Sms.STATUS_COMPLETE -> Receipt(Receipt.State.DELIVERED)
            else -> Receipt(Receipt.State.SENT)
        }
        return Message("sms:message:${c.long("_id")}", "sms:$key", c.long("date"),
            if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) Direction.INCOMING else Direction.OUTGOING,
            sender = if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) contact(c.string("address")).first else null,
            text = c.string("body"), receipt = receipt)
    }
    override suspend fun listMessages(conversation: Conversation, before: Message?): MessagePage = withContext(Dispatchers.IO) {
        checkEnabled()
        val selection = "thread_id=? AND type<>3" + if (before != null) " AND (date<? OR (date=? AND _id<?))" else ""
        val args = mutableListOf(conversation.remoteId)
        before?.let { args.add(it.sentAt.toString()); args.add(it.sentAt.toString()); args.add(rowId(it).toString()) }
        val rows = mutableListOf<Message>()
        resolver.query(Telephony.Sms.CONTENT_URI, columns, selection, args.toTypedArray(), "date DESC, _id DESC")?.use {
            while (rows.size < PAGE + 1 && it.moveToNext()) rows.add(map(it))
        }
        MessagePage(rows.take(PAGE).reversed(), rows.size > PAGE)
    }
    override suspend fun openConversation(conversation: Conversation) { visibleThread = thread(conversation) }
    override suspend fun closeConversation(conversation: Conversation) { visibleThread = null }
    override suspend fun markRead(conversation: Conversation) = withContext(Dispatchers.IO) {
        checkEnabled()
        resolver.update(Telephony.Sms.CONTENT_URI, ContentValues().apply { put("read", 1); put("seen", 1) }, "thread_id=? AND read=0", arrayOf(conversation.remoteId))
        MessageNotifier(context).clear(conversation.id)
        Unit
    }
    override suspend fun updateConversation(conversation: Conversation, update: ConversationUpdate) = withContext(Dispatchers.IO) {
        store.update(thread(conversation), update = update)
        changes.emit(ServiceEvent.ConversationsChanged(id))
    }
    override suspend fun deleteMessage(conversation: Conversation, message: Message) = withContext(Dispatchers.IO) {
        checkEnabled()
        resolver.delete(rowUri(rowId(message)), "thread_id=?", arrayOf(conversation.remoteId))
        changes.emit(ServiceEvent.ConversationsChanged(id)); changes.emit(ServiceEvent.MessagesChanged(id, conversation.id))
    }
    override suspend fun deleteConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        checkEnabled()
        val ids = mutableListOf<Long>()
        resolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id"), "thread_id=?", arrayOf(conversation.remoteId), null)?.use {
            while (it.moveToNext()) ids.add(it.getLong(0))
        }
        resolver.delete(Telephony.Sms.CONTENT_URI, "thread_id=?", arrayOf(conversation.remoteId))
        store.deleteChat(thread(conversation), ids)
        MessageNotifier(context).clear(conversation.id)
        val notifications = context.getSystemService(NotificationManager::class.java)
        ids.forEach { notifications.cancel("sms-failed-$it", 30) }
        changes.emit(ServiceEvent.ConversationsChanged(id))
        changes.emit(ServiceEvent.MessagesChanged(id, conversation.id))
    }
    override suspend fun searchMessages(query: String, conversation: Conversation?): List<SearchResult> = withContext(Dispatchers.IO) {
        checkEnabled()
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val args = mutableListOf("%$escaped%")
        val selection = "type<>3 AND body LIKE ? ESCAPE '\\'" + if (conversation != null) " AND thread_id=?" else ""
        conversation?.let { args.add(it.remoteId) }
        buildList {
            resolver.query(Telephony.Sms.CONTENT_URI, columns, selection, args.toTypedArray(), "date DESC, _id DESC")?.use {
                while (size < 100 && it.moveToNext()) { val message = map(it); add(SearchResult(message.id, message.conversationId, message.sender ?: "You", message.text.orEmpty(), message.sentAt)) }
            }
        }
    }
    override suspend fun listContacts(): List<Contact> = withContext(Dispatchers.IO) {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return@withContext emptyList()
        buildList {
            resolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf("display_name", "data1"), null, null, "display_name COLLATE NOCASE")?.use {
                while (it.moveToNext()) {
                    val address = it.getString(1) ?: continue
                    add(Contact(address, id, it.getString(0) ?: address, address))
                }
            }
        }.distinctBy { PhoneNumberUtils.normalizeNumber(it.id) }
    }
    override suspend fun startChat(contact: Contact) = createDirect(contact.id)
    override suspend fun createDirect(address: String, title: String?): Conversation = withContext(Dispatchers.IO) {
        checkEnabled()
        val number = PhoneNumberUtils.normalizeNumber(address)
        require(number.isNotBlank() && number.any(Char::isDigit) && address.none { it == ';' || it == ',' || it == '@' }) { "Enter one phone number" }
        val key = Telephony.Threads.getOrCreateThreadId(context, number)
        store.update(key, address = number)
        val last = latest(key)
        changes.emit(ServiceEvent.ConversationsChanged(id))
        conversation(key, number, last, unread = 0)
    }
    override suspend fun conversationAvatar(conversation: Conversation): String? = withContext(Dispatchers.IO) {
        val number = latest(thread(conversation))?.let { addressOf(rowId(it)) } ?: store.chats()[thread(conversation)]?.address ?: return@withContext null
        val photo = contact(number).second ?: return@withContext null
        val file = File(context.cacheDir, "sms-avatar-${conversation.remoteId}.jpg")
        resolver.openInputStream(Uri.parse(photo))?.use { input -> file.outputStream().use { input.copyTo(it) } }
        file.takeIf { it.exists() }?.absolutePath
    }
    private fun contact(address: String): Pair<String, String?> {
        names.get(address)?.let { return it }
        var result: Pair<String, String?> = address to null
        if (hasPermission(Manifest.permission.READ_CONTACTS)) {
            runCatching {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
                resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI), null, null, null)?.use {
                    if (it.moveToFirst()) result = (it.getString(0) ?: address) to it.getString(1)
                }
            }

        }
        names.put(address, result)
        return result
    }
    override suspend fun sendText(conversation: Conversation, text: String, replyTo: Message?): Message = withContext(Dispatchers.IO) {
        checkEnabled()
        require(replyTo == null) { "SMS does not support quote replies" }
        require(text.isNotBlank()) { "Enter a message" }
        val key = thread(conversation)
        val address = latest(key)?.let { addressOf(rowId(it)) } ?: store.chats()[key]?.address ?: error("No recipient for this SMS")
        val subscription = SubscriptionManager.getDefaultSmsSubscriptionId()
        check(SubscriptionManager.isValidSubscriptionId(subscription)) { "Choose a default SMS SIM in Android settings" }
        val uri = resolver.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
            put("thread_id", key); put("address", address); put("body", text); put("date", System.currentTimeMillis())
            put("type", Telephony.Sms.MESSAGE_TYPE_OUTBOX); put("status", Telephony.Sms.STATUS_PENDING)
            put("read", 1); put("seen", 1); put("sub_id", subscription)
        }) ?: error("Could not save outgoing SMS")
        val row = ContentUris.parseId(uri)
        try { submit(row, address, text, subscription) }
        catch (e: Exception) {
            resolver.update(uri, ContentValues().apply { put("type", Telephony.Sms.MESSAGE_TYPE_FAILED); put("status", Telephony.Sms.STATUS_FAILED) }, null, null)
            changes.emit(ServiceEvent.MessagesChanged(id, conversation.id))
            throw e
        }
        changes.emit(ServiceEvent.ConversationsChanged(id)); changes.emit(ServiceEvent.MessagesChanged(id, conversation.id))
        resolver.query(uri, columns, null, null, null)!!.use { it.moveToFirst(); map(it) }
    }
    override suspend fun retryMessage(conversation: Conversation, message: Message) = withContext(Dispatchers.IO) {
        checkEnabled()
        val row = rowId(message)
        val address = addressOf(row) ?: error("SMS no longer exists")
        val subscription = SubscriptionManager.getDefaultSmsSubscriptionId()
        check(SubscriptionManager.isValidSubscriptionId(subscription)) { "Choose a default SMS SIM in Android settings" }
        resolver.update(rowUri(row), ContentValues().apply { put("type", Telephony.Sms.MESSAGE_TYPE_OUTBOX); put("status", Telephony.Sms.STATUS_PENDING); put("sub_id", subscription) }, null, null)
        try { submit(row, address, message.text.orEmpty(), subscription) }
        catch (e: Exception) {
            resolver.update(rowUri(row), ContentValues().apply { put("type", Telephony.Sms.MESSAGE_TYPE_FAILED); put("status", Telephony.Sms.STATUS_FAILED) }, null, null)
            throw e
        }
    }
    @Suppress("DEPRECATION")
    private fun submit(row: Long, address: String, text: String, subscription: Int) {
        val manager = managerForSubscription?.invoke(subscription) ?: if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscription)
            else SmsManager.getSmsManagerForSubscriptionId(subscription)
        val parts = manager.divideMessage(text)
        val token = store.beginAttempt(row, parts.size)
        fun callback(kind: String, index: Int): PendingIntent {
            val intent = Intent(context, SmsResultReceiver::class.java)
                .setAction(kind).setData(Uri.parse("dumbtalk-sms://$kind/$row/$token/$index"))
                .putExtra("row", row).putExtra("token", token).putExtra("part", index)
            val mutable = if (kind == DELIVERED) { if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0 } else PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
        }
        manager.sendMultipartTextMessage(address, null, parts,
            ArrayList(parts.indices.map { callback(SENT, it) }), ArrayList(parts.indices.map { callback(DELIVERED, it) }))
    }
    internal fun received(address: String, body: String, date: Long, subscription: Int): Pair<Conversation, Message> {
        val key = Telephony.Threads.getOrCreateThreadId(context, address)
        // A receiver/process restart must not duplicate an already committed delivery.
        val duplicate = resolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id"), "type=1 AND thread_id=? AND date_sent=? AND body=? AND sub_id=?", arrayOf(key.toString(), date.toString(), body, subscription.toString()), null)?.use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
        val uri = duplicate?.let(::rowUri) ?: resolver.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
            put("thread_id", key); put("address", address); put("body", body); put("date", System.currentTimeMillis()); put("date_sent", date)
            put("type", Telephony.Sms.MESSAGE_TYPE_INBOX); put("status", Telephony.Sms.STATUS_NONE)
            put("read", 0); put("seen", 0); put("sub_id", subscription)
        }) ?: error("Could not save received SMS")
        val message = resolver.query(uri, columns, null, null, null)!!.use { it.moveToFirst(); map(it) }
        return conversation(key, address, message) to message
    }
    companion object {
        const val SENT = "dev.dumbtalk.SMS_SENT"
        const val DELIVERED = "dev.dumbtalk.SMS_DELIVERED"
        private const val PAGE = 60
        private val columns = arrayOf("_id", "thread_id", "address", "body", "date", "type", "status", "sub_id")
        private fun Cursor.long(key: String) = getLong(getColumnIndexOrThrow(key))
        private fun Cursor.int(key: String) = getInt(getColumnIndexOrThrow(key))
        private fun Cursor.string(key: String) = getString(getColumnIndexOrThrow(key)).orEmpty()
    }
}
