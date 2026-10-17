package dev.dumbtalk.core

import dev.dumbtalk.core.demo.DemoService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceRegistryTest {
    private fun TestScope.registry() =
        ServiceRegistry(ServiceId.entries.map { DemoService(it, backgroundScope, clock = { 1_000_000_000L }, replyDelayMillis = 10) })

    @Test
    fun inboxMergesServicesWithFavouritesFirst() = runTest {
        val inbox = registry().inbox(archived = false).conversations

        assertEquals(ServiceId.entries.filter { it != ServiceId.SMS }.toSet(), inbox.map { it.serviceId }.toSet())
        val favourites = inbox.takeWhile { it.isFavourite }
        assertEquals(setOf("Maya Chen", "Family"), favourites.map { it.title }.toSet())
        val rest = inbox.drop(favourites.size)
        assertEquals(rest.sortedByDescending { it.lastMessage?.sentAt }, rest)
    }

    @Test
    fun disconnectedServiceLeavesInbox() = runTest {
        val registry = registry()
        registry.byId(ServiceId.TELEGRAM).disconnect()

        val inbox = registry.inbox(archived = false).conversations
        assertTrue(inbox.none { it.serviceId == ServiceId.TELEGRAM })
        assertEquals(2, registry.connected().size)
    }

    @Test
    fun archivingMovesConversationAndCounts() = runTest {
        val registry = registry()
        val mum = registry.inbox(archived = false).conversations.first { it.title == "Mum" }

        registry.forConversation(mum).updateConversation(mum, ConversationUpdate(archived = true))

        assertTrue(registry.inbox(archived = false).conversations.none { it.id == mum.id })
        val archived = registry.inbox(archived = true)
        assertEquals(listOf(mum.id), archived.conversations.map { it.id })
        assertEquals(1, archived.archivedCount)
    }

    @Test
    fun sentMessageIsReadAndAnswered() = runTest {
        val registry = registry()
        val maya = registry.inbox(archived = false).conversations.first { it.title == "Maya Chen" }
        val service = registry.forConversation(maya)

        val sent = service.sendText(maya, "See you there")
        advanceTimeBy(1_000) // backgroundScope work is not covered by advanceUntilIdle
        runCurrent()

        val messages = service.listMessages(maya).messages
        assertEquals(Receipt.State.READ, messages.first { it.id == sent.id }.receipt?.state)
        assertEquals(Direction.INCOMING, messages.last().direction)
    }

    @Test
    fun telegramSetupWalksApiCredentialsThenQr() = runTest {
        val service = DemoService(ServiceId.TELEGRAM, backgroundScope)
        service.disconnect()

        var step = service.beginSetup()
        assertEquals(SetupStep.Input.Field.API_ID, (step as SetupStep.Input).field)
        step = service.advanceSetup(step, "123")
        step = service.advanceSetup(step, "0123456789abcdef0123456789abcdef")
        step = service.advanceSetup(step as SetupStep.Choice, "qr")
        assertTrue(step is SetupStep.Qr)
        step = service.advanceSetup(step)

        assertTrue(step is SetupStep.Complete)
        assertTrue(service.status().connected)
    }
}
