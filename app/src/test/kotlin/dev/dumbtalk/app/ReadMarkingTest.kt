package dev.dumbtalk.app

import dev.dumbtalk.app.screens.ChatModel
import dev.dumbtalk.core.ServiceId
import dev.dumbtalk.core.demo.DemoService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadMarkingTest {
    /** Past the model's pause before reading; advanceUntilIdle skips backgroundScope work. */
    private fun kotlinx.coroutines.test.TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    @Test
    fun movingOntoAMessageReadsTheChatUpToIt() = runTest {
        val service = DemoService(ServiceId.TELEGRAM, backgroundScope)
        suspend fun chat() = service.listConversations(archived = false).conversations.single { it.title == "Indie Dev Chat" }
        val start = chat()
        assertEquals(4, start.unreadCount)
        val model = ChatModel(service, { start }, backgroundScope)
        model.load()
        val (first, second) = model.messages!!

        // Reaching the older message reads it, not the one after it…
        model.reached(first)
        settle()
        assertEquals(1, chat().unreadCount)

        // …going back changes nothing, and reaching the newest reads the rest.
        model.reached(first)
        model.reached(second)
        settle()
        assertEquals(0, chat().unreadCount)
    }
}
