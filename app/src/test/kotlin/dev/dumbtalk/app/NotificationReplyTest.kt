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

    private fun notification(tag: String): Notification =
        app.getSystemService(NotificationManager::class.java).activeNotifications.single { it.tag == tag }.notification

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
