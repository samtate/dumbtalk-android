package dev.dumbtalk.app.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.net.Uri
import dev.dumbtalk.app.MainActivity
import dev.dumbtalk.app.R
import dev.dumbtalk.app.ui.messageSummary
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.ConversationKind
import dev.dumbtalk.core.Message

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
 */
class MessageNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages from Signal, Telegram and WhatsApp."
            },
        )
    }

    @Synchronized
    fun show(conversation: Conversation, message: Message) {
        if (conversation.isMuted || Visibility.suppresses(conversation.id)) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!manager.areNotificationsEnabled()) return

        val group = conversation.kind == ConversationKind.GROUP
        val previous = manager.activeNotifications.firstOrNull { it.tag == conversation.id }?.notification
        val history = previous?.let { messagingStyle(it)?.messages }.orEmpty().toMutableList()
        if (history.any { it.extras.getString(MESSAGE_ID) == message.id }) return

        val avatar = previous?.getLargeIcon() ?: Icon.createWithBitmap(initials(conversation.title))
        val sender = message.sender ?: conversation.title
        val person = Person.Builder().setName(sender).setKey(sender).apply { if (!group) setIcon(avatar) }.build()
        val text = messageSummary(message)
        history += Notification.MessagingStyle.Message(text, message.sentAt, person).apply {
            extras.putString(MESSAGE_ID, message.id)
        }
        val recent = history.sortedBy { it.timestamp }.takeLast(25)

        val style = Notification.MessagingStyle(Person.Builder().setName("You").build()).setGroupConversation(group)
        if (group) style.setConversationTitle(conversation.title)
        recent.forEach(style::addMessage)

        val open = Intent(context, MainActivity::class.java)
            .setData(Uri.Builder().scheme("dumbtalk").authority("chat").appendPath(conversation.id).build())
            .putExtra(EXTRA_CONVERSATION, conversation.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(conversation.title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setStyle(style)
            .setLargeIcon(avatar)
            .setWhen(recent.last().timestamp)
            .setContentIntent(PendingIntent.getActivity(context, conversation.id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        manager.notify(conversation.id, ID, notification)
    }

    /** Opening a chat clears its notification. */
    fun clear(conversationId: String) = manager.cancel(conversationId, ID)

    private fun messagingStyle(notification: Notification): Notification.MessagingStyle? =
        Notification.Builder.recoverBuilder(context, notification).style as? Notification.MessagingStyle

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
        private const val CHANNEL = "dumbtalk-messages"
        private const val ID = 2
        private const val MESSAGE_ID = "messageId"
    }
}
