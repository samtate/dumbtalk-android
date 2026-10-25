package dev.dumbtalk.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.dumbtalk.app.background.MessageNotifier
import dev.dumbtalk.app.background.NotificationActionReceiver
import dev.dumbtalk.core.Direction
import dev.dumbtalk.core.Message
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class NotificationReplyTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Test
    fun aReplyFromTheNotificationIsSentAndShownInIt() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val chat = waitFor { app.inbox.active.value?.conversations?.firstOrNull() }
        app.notifier.show(chat, Message("in-1", chat.id, System.currentTimeMillis(), Direction.INCOMING, sender = "Maya", text = "Lunch?"))
        val posted = notification(chat.id)
        assertEquals(listOf("Reply", "Mark read"), posted.actions.map { it.title.toString() })

        val reply = Intent(app, NotificationActionReceiver::class.java)
            .setAction(MessageNotifier.ACTION_REPLY)
            .putExtra(MessageNotifier.EXTRA_CONVERSATION, chat.id)
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(MessageNotifier.KEY_REPLY).build()), reply,
            Bundle().apply { putCharSequence(MessageNotifier.KEY_REPLY, "On my way") },
        )
        NotificationActionReceiver().onReceive(app, reply)

        // The service has the reply, and the notification shows it.
        waitFor {
            runBlocking { app.registry.forConversation(chat).listMessages(chat).messages }
                .lastOrNull()?.takeIf { it.text == "On my way" && it.direction == Direction.OUTGOING }
        }
        val texts = waitFor {
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                notification(chat.id).extras.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java),
            ).map { it.text.toString() }.takeIf { "On my way" in it }
        }
        assertTrue(texts.containsAll(listOf("Lunch?", "On my way")))
    }

    @Test
    fun chatsAreGroupedOnTheirNetworksChannelAndClearWhenReadElsewhere() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val chats = waitFor { app.inbox.active.value?.conversations?.takeIf { it.size >= 2 } }
        val (first, second) = chats
        val now = System.currentTimeMillis()
        app.notifier.show(first, Message("a", first.id, now, Direction.INCOMING, sender = "Ann", text = "Hi"))
        app.notifier.show(second, Message("b", second.id, now, Direction.INCOMING, sender = "Bob", text = "Yo"))

        val posted = notification(first.id)
        assertEquals(MessageNotifier.channel(first.serviceId), posted.channelId)
        assertEquals("dev.dumbtalk.messages", posted.group)
        assertTrue(summary() != null)

        // Read on the main phone: nothing unread and nothing newer than the notification.
        app.notifier.clearRead(listOf(first.copy(unreadCount = 0, lastMessage = Message("a", first.id, now, Direction.INCOMING))))
        assertTrue(active().none { it.tag == first.id })
        // A newer message than the notification (or anything unread) keeps it.
        app.notifier.clearRead(listOf(second.copy(unreadCount = 1, lastMessage = Message("b", second.id, now, Direction.INCOMING))))
        assertTrue(active().any { it.tag == second.id })
        // The summary goes with the last chat.
        app.notifier.clear(second.id)
        assertEquals(null, summary())
    }

    @Test
    @Config(sdk = [29, 31, 32])
    fun messagesAppearBeforeAndroidIntroducedTheNotificationPermission() {
        // POST_NOTIFICATIONS doesn't exist on these versions: no grant is needed.
        val chat = waitFor { app.inbox.active.value?.conversations?.firstOrNull() }
        app.notifier.show(chat, Message("legacy", chat.id, System.currentTimeMillis(), Direction.INCOMING, text = "Hello"))
        assertEquals("Hello", notification(chat.id).extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun messagesRequirePermissionOnAndroid13AndLater() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val chat = waitFor { app.inbox.active.value?.conversations?.firstOrNull() }
        app.notifier.show(chat, Message("denied", chat.id, System.currentTimeMillis(), Direction.INCOMING, text = "Hello"))
        assertTrue(active().isEmpty())
    }

    @Test
    fun summaryAndChatsHideNamesAndTextOnAPrivateLockScreen() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val chat = waitFor { app.inbox.active.value?.conversations?.firstOrNull() }
        app.notifier.show(chat, Message("private", chat.id, System.currentTimeMillis(), Direction.INCOMING, text = "Secret text"))
        for (posted in listOf(notification(chat.id), requireNotNull(summary()).notification)) {
            assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
            val public = requireNotNull(posted.publicVersion)
            assertEquals("DumbTalk", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("New message", public.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertTrue(!public.extras.containsKey(Notification.EXTRA_TEXT_LINES))
        }
    }

    private fun active() = app.getSystemService(NotificationManager::class.java).activeNotifications.toList()
    private fun summary() = active().firstOrNull { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }

    private fun notification(tag: String): Notification =
        active().single { it.tag == tag && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }.notification

    /** Runs the main looper (the receiver finishes there) until [value] isn't null. */
    private fun <T : Any> waitFor(value: () -> T?): T {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            value()?.let { return it }
            check(System.currentTimeMillis() < deadline) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }
}
