package dev.dumbtalk.app.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.LocusId
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import dev.dumbtalk.app.DumbTalkApp
import dev.dumbtalk.app.MainActivity
import dev.dumbtalk.app.R
import dev.dumbtalk.app.ui.messageSummary
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.ConversationKind
import dev.dumbtalk.core.Message
import dev.dumbtalk.core.ServiceId
import java.io.File
import kotlinx.coroutines.launch

/** What's on screen, so notifications for it can be skipped. */
object Visibility {
    /** "inbox", a conversation id, or null when the app isn't in front. */
    @Volatile var current: String? = null

    fun suppresses(conversationId: String): Boolean = current == "inbox" || current == conversationId
}

/**
 * One expandable MessagingStyle notification per chat, as the old wrapper
 * did: history comes from Android's active notification, so it survives the
 * process restarting and clears when dismissed or opened.
 *
 * They're grouped under a summary, so Android doesn't bundle them with the
 * ongoing connection notification; each network has its own channel (its own
 * sound, or none); and on Android 11+ each chat is a conversation shortcut, so
 * it shows in the shade's Conversations section with its picture.
 */
class MessageNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannelGroup(NotificationChannelGroup(CHANNEL_GROUP, "Messages"))
        for (service in ServiceId.entries) {
            manager.createNotificationChannel(
                NotificationChannel(channel(service), "${service.label} messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "New ${service.label} messages."
                    group = CHANNEL_GROUP
                },
            )
        }
        // Before there was a channel per network.
        manager.deleteNotificationChannel("dumbtalk-messages")
    }

    @Synchronized
    fun show(conversation: Conversation, message: Message) {
        if (conversation.isMuted || Visibility.suppresses(conversation.id)) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!manager.areNotificationsEnabled()) return

        val group = conversation.kind == ConversationKind.GROUP
        val previous = active(conversation.id)
        val history = previous?.let { messagingStyle(it)?.messages }.orEmpty().toMutableList()
        if (history.any { it.extras.getString(MESSAGE_ID) == message.id }) return

        val avatar = previous?.getLargeIcon() ?: avatar(conversation)
        val sender = message.sender ?: conversation.title
        val person = Person.Builder().setName(sender).setKey(sender)
            .setIcon(if (group) Icon.createWithBitmap(initials(sender)) else avatar).build()
        val text = messageSummary(message)
        history += Notification.MessagingStyle.Message(text, message.sentAt, person).apply {
            extras.putString(MESSAGE_ID, message.id)
        }
        val recent = history.sortedBy { it.timestamp }.takeLast(25)

        val style = Notification.MessagingStyle(Person.Builder().setName("You").build()).setGroupConversation(group)
        if (group) style.setConversationTitle(conversation.title)
        recent.forEach(style::addMessage)

        val open = openIntent(conversation.id)
        val shortcut = publishShortcut(conversation, avatar, open, person)
        val notification = Notification.Builder(context, channel(conversation.serviceId))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(conversation.title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setStyle(style)
            .setLargeIcon(avatar)
            .setWhen(recent.last().timestamp)
            .setShowWhen(true)
            .setContentIntent(PendingIntent.getActivity(context, conversation.id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(conversation.serviceId, recent.size))
            .apply { if (shortcut != null) setShortcutId(shortcut) }
            .addAction(replyAction(conversation.id))
            .addAction(
                Notification.Action.Builder(icon(), "Mark read", actionIntent(ACTION_READ, conversation.id, mutable = false))
                    .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .build(),
            )
            .build()
        manager.notify(conversation.id, ID, notification)
        updateSummary(conversation.serviceId)
    }

    /**
     * Adds what we said (or why it wasn't sent) to a chat's notification after
     * a reply from it; Android keeps showing a spinner until the notification
     * is updated.
     */
    @Synchronized
    fun replied(conversationId: String, text: String, failure: String?) {
        val previous = active(conversationId) ?: return
        val style = messagingStyle(previous) ?: return
        val line = if (failure == null) text else "⚠ Not sent: $failure"
        style.addMessage(Notification.MessagingStyle.Message(line, System.currentTimeMillis(), null as Person?))
        val notification = Notification.Builder.recoverBuilder(context, previous)
            .setStyle(style)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(conversationId, ID, notification)
    }

    /** Opening a chat, or reading it, clears its notification. */
    @Synchronized
    fun clear(conversationId: String) {
        manager.cancel(conversationId, ID)
        updateSummary(null)
    }

    /**
     * Clears notifications for chats read elsewhere (on the main phone, say):
     * those now with nothing unread and no message newer than the notification.
     */
    @Synchronized
    fun clearRead(conversations: List<Conversation>) {
        val shown = manager.activeNotifications.filter { it.id == ID && it.tag != null }.associateBy { it.tag }
        conversations.forEach { conversation ->
            val notification = shown[conversation.id]?.notification ?: return@forEach
            val newest = conversation.lastMessage?.sentAt ?: return@forEach
            if (conversation.unreadCount == 0 && newest >= notification.`when`) clear(conversation.id)
        }
    }

    private fun active(conversationId: String): Notification? =
        manager.activeNotifications.firstOrNull { it.tag == conversationId && it.id == ID }?.notification

    /** The group's summary lists each chat with news; it goes with the last of them. */
    private fun updateSummary(service: ServiceId?) {
        val chats = manager.activeNotifications.filter { it.id == ID && it.tag != null }.sortedByDescending { it.notification.`when` }
        if (chats.isEmpty()) {
            manager.cancel(SUMMARY_TAG, SUMMARY_ID)
            return
        }
        val lines = Notification.InboxStyle()
        chats.take(5).forEach { lines.addLine(it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) ?: "") }
        val count = if (chats.size == 1) "1 chat" else "${chats.size} chats"
        val channel = service?.let(::channel) ?: chats.first().notification.channelId
        val summary = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New messages")
            .setContentText(count)
            .setStyle(lines.setSummaryText(count))
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        manager.notify(SUMMARY_TAG, SUMMARY_ID, summary)
    }

    /** What the lock screen shows when notifications there are private: no names or text. */
    private fun publicVersion(service: ServiceId, count: Int): Notification =
        Notification.Builder(context, channel(service))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DumbTalk")
            .setContentText(if (count == 1) "New message" else "$count new messages")
            .build()

    private fun openIntent(conversationId: String): Intent = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .setData(Uri.Builder().scheme("dumbtalk").authority("chat").appendPath(conversationId).build())
        .putExtra(EXTRA_CONVERSATION, conversationId)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /**
     * A long-lived shortcut for the chat, which makes its notification a
     * conversation (Android 11+): it sorts to the top of the shade with its
     * picture, and can be given its own sound. Null before Android 11.
     */
    private fun publishShortcut(conversation: Conversation, avatar: Icon, open: Intent, person: Person): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val shortcut = ShortcutInfo.Builder(context, conversation.id)
            .setShortLabel(conversation.title.take(25).ifBlank { conversation.serviceId.label })
            .setLongLabel(conversation.title.ifBlank { conversation.serviceId.label })
            .setIcon(avatar)
            .setIntent(open)
            .setLongLived(true)
            .setLocusId(LocusId(conversation.id))
            .setCategories(setOf(SHORTCUT_CATEGORY))
            .apply { if (conversation.kind == ConversationKind.DIRECT) setPerson(person) }
            .build()
        return runCatching { context.getSystemService(ShortcutManager::class.java).pushDynamicShortcut(shortcut); conversation.id }.getOrNull()
    }

    private fun replyAction(conversationId: String): Notification.Action =
        Notification.Action.Builder(icon(), "Reply", actionIntent(ACTION_REPLY, conversationId, mutable = true))
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build())
            .setAllowGeneratedReplies(false)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .build()

    /** The reply's intent must be mutable so Android can add the typed text. */
    private fun actionIntent(action: String, conversationId: String, mutable: Boolean): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.Builder().scheme("dumbtalk").authority(action).appendPath(conversationId).build())
            .putExtra(EXTRA_CONVERSATION, conversationId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private fun icon() = Icon.createWithResource(context, R.drawable.ic_notification)

    private fun messagingStyle(notification: Notification): Notification.MessagingStyle? =
        Notification.Builder.recoverBuilder(context, notification).style as? Notification.MessagingStyle

    /** The chat's picture when it's been downloaded, else its initial. */
    private fun avatar(conversation: Conversation): Icon {
        val picture = conversation.avatarPath?.takeIf { File(it).isFile }?.let { path ->
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.let(::circle)
        }
        return Icon.createWithBitmap(picture ?: initials(conversation.title))
    }

    private fun circle(source: Bitmap): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val side = minOf(source.width, source.height)
        val crop = Rect((source.width - side) / 2, (source.height - side) / 2, (source.width + side) / 2, (source.height + side) / 2)
        canvas.drawBitmap(source, crop, Rect(0, 0, size, size), paint)
        return bitmap
    }

    private fun initials(title: String): Bitmap {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(49, 88, 75)
        canvas.drawCircle(64f, 64f, 64f, paint)
        paint.color = Color.WHITE
        paint.textSize = 56f
        paint.textAlign = Paint.Align.CENTER
        val letter = title.trim().takeIf { it.isNotEmpty() }?.let { it.substring(0, it.offsetByCodePoints(0, 1)).uppercase() } ?: "?"
        canvas.drawText(letter, 64f, 64f - (paint.ascent() + paint.descent()) / 2, paint)
        return bitmap
    }

    companion object {
        const val EXTRA_CONVERSATION = "conversationId"
        const val ACTION_REPLY = "reply"
        const val ACTION_READ = "read"
        const val KEY_REPLY = "reply"
        private const val CHANNEL_GROUP = "messages"
        private const val GROUP = "dev.dumbtalk.messages"
        private const val ID = 2
        private const val SUMMARY_TAG = "summary"
        private const val SUMMARY_ID = 3
        private const val MESSAGE_ID = "messageId"
        private const val SHORTCUT_CATEGORY = "dev.dumbtalk.category.CHAT"

        fun channel(service: ServiceId) = "messages-${service.key}"
    }
}

/** Reply and Mark read from a chat's notification, without opening DumbTalk. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? DumbTalkApp ?: return
        val id = intent.getStringExtra(MessageNotifier.EXTRA_CONVERSATION) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(MessageNotifier.KEY_REPLY)?.toString()?.trim()
        val pending = goAsync()
        app.scope.launch {
            try {
                // Straight after a restart the inbox may not have this chat yet.
                val conversation = app.inbox.find(id) ?: run { app.inbox.refresh(); app.inbox.find(id) } ?: return@launch
                val service = app.registry.forConversation(conversation)
                when (intent.action) {
                    MessageNotifier.ACTION_REPLY -> {
                        if (text.isNullOrEmpty()) return@launch
                        // Services store the reply and return at once, then send it.
                        val failure = runCatching { service.sendText(conversation, text) }.exceptionOrNull()
                        app.notifier.replied(id, text, failure?.let { it.message ?: "unknown error" })
                        if (failure == null) runCatching { service.markRead(conversation) }
                    }
                    MessageNotifier.ACTION_READ -> {
                        runCatching { service.markRead(conversation) }
                        app.notifier.clear(id)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
