package dev.dumbtalk.core

import dev.dumbtalk.core.bridge.BridgeException
import dev.dumbtalk.core.bridge.BridgeJson
import dev.dumbtalk.core.bridge.BridgeService
import dev.dumbtalk.core.bridge.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Records calls and returns canned JSON in the shape the real bridges use. */
private class FakeBridge : NativeBridge {
    var linked = true
    var online = true
    val calls = mutableListOf<String>()
    var failWith: String? = null

    private fun call(name: String) {
        calls += name
        failWith?.let { throw RuntimeException(it) }
    }

    override fun isLinked() = linked
    override fun isOnline() = online
    override fun accountJson() = """{"phone":"447000000000"}"""
    override fun logout() = call("logout")
    override fun reconnect(networkChanged: Boolean) = call("reconnect:$networkChanged")
    override fun chatsJson(archived: Boolean) = """{"chats":[
        {"jid":"c:1","name":"Maya","group":false,"archived":$archived,"pinned":true,"muted":false,"unread":2,"lastTs":5,
         "last":{"id":"m1","chat":"c:1","senderName":"Maya","fromMe":false,"ts":5,"kind":"text","text":"hi","reactions":[]}}],
        "archivedCount":3}"""
    override fun chatJson(chat: String) = """{"jid":"$chat","name":"Maya","group":false,
        "last":{"id":"m1","chat":"$chat","senderName":"Maya","fromMe":false,"ts":5,"kind":"text","text":"hi","reactions":[]}}"""
    override fun messagesJson(chat: String, beforeTs: Long, beforeId: String, limit: Long): String {
        call("messages:$beforeTs:$beforeId:$limit")
        return """[{"id":"m1","chat":"$chat","fromMe":true,"ts":5,"kind":"text","text":"yo","status":"read","reactions":[]}]"""
    }
    override fun searchJson(query: String) = "[]"
    override fun sendText(chat: String, text: String, quoteId: String, spansJson: String): String {
        call("send:$chat:$text:$quoteId")
        return """{"id":"m2","chat":"$chat","fromMe":true,"ts":6,"kind":"text","text":"$text","status":"sent","reactions":[]}"""
    }
    override fun retry(chat: String, id: String): String {
        call("retry:$id")
        return """{"id":"$id","chat":"$chat","fromMe":true,"ts":6,"kind":"text","text":"again","status":"sending","reactions":[]}"""
    }
    override fun receiptsJson(chat: String, id: String) = """[{"name":"Ann","delivered":100,"read":200},{"name":"Bob","delivered":150}]"""
    override fun presenceJson(idsJson: String) = """{"c:1":{"online":false,"lastSeen":500}}"""
    override fun react(chat: String, id: String, emoji: String, remove: Boolean) = call("react:$id:$emoji:$remove")
    override fun edit(chat: String, id: String, text: String) = call("edit:$id")
    override fun delete(chat: String, id: String) = call("delete:$id")
    override fun markRead(chat: String) = call("read:$chat")
    override fun setTyping(chat: String, active: Boolean) = call("typing:$active")
    override fun setArchived(chat: String, value: Boolean) = call("archive:$value")
    override fun setPinned(chat: String, value: Boolean) = call("pin:$value")
    override fun setMuted(chat: String, value: Boolean) = call("mute:$value")
    override fun resolvePhone(phone: String) = "c:resolved"
    override fun contactsJson() = """[{"id":"c:1","name":"Maya","detail":"+447000000001"},{"id":"c:2","name":"Zed"}]"""
    override fun downloadMedia(chat: String, id: String): String { call("download:$id"); return "/tmp/$id.jpg" }
    override fun sendMedia(chat: String, path: String, mime: String, caption: String, voiceNote: Boolean): String {
        call("media:$mime:$voiceNote")
        return """{"id":"m3","chat":"$chat","fromMe":true,"ts":7,"kind":"audio","reactions":[]}"""
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeServiceTest {
    private class TestService(scope: CoroutineScope, val fake: FakeBridge) : BridgeService(ServiceId.WHATSAPP, scope) {
        init { start({ fake }) { } }
        override fun log(message: String) {}
        override suspend fun beginSetup(): SetupStep = error("not used")
        override suspend fun advanceSetup(step: SetupStep, value: String?): SetupStep = error("not used")
        fun emit(kind: String, payload: String) = onBridgeEvent(kind, payload)
    }

    private fun TestScope.service(fake: FakeBridge = FakeBridge()): TestService {
        // The service creates its bridge on Dispatchers.IO; run that for real.
        val service = TestService(CoroutineScope(Dispatchers.IO), fake)
        return service
    }

    private val chat = Conversation("whatsapp:c:1", ServiceId.WHATSAPP, "c:1", ConversationKind.DIRECT, "Maya")

    @Test
    fun statusReflectsLinkingAndConnection() = runTest {
        val fake = FakeBridge()
        val service = service(fake)
        assertEquals(ServiceStatus(ServiceId.WHATSAPP, connected = true, ready = true, accountLabel = "+447000000000"), service.status())
        fake.linked = false
        assertEquals(false, service.status().connected)
    }

    @Test
    fun unlinkedServiceShowsNoChats() = runTest {
        val service = service(FakeBridge().apply { linked = false })
        assertEquals(ConversationPage(emptyList(), 0), service.listConversations(archived = false))
    }

    @Test
    fun mapsChatsAndPassesArchivedFlag() = runTest {
        val page = service().listConversations(archived = true)
        assertEquals(3, page.archivedCount)
        val first = page.conversations.single()
        assertEquals("whatsapp:c:1", first.id)
        assertTrue(first.isFavourite && first.isArchived)
        assertEquals(2, first.unreadCount)
        assertEquals("hi", first.lastMessage?.text)
    }

    @Test
    fun pagesHistoryByTheOldestMessageShown() = runTest {
        val fake = FakeBridge()
        val service = service(fake)
        val oldest = Message("m0", chat.id, 3, Direction.INCOMING)
        service.listMessages(chat, before = oldest)
        assertEquals("messages:3:m0:50", fake.calls.single())
    }

    @Test
    fun readReceiptsMapToTheContract() = runTest {
        val message = service().listMessages(chat, null).messages.single()
        assertEquals(Receipt.State.READ, message.receipt?.state)
    }

    @Test
    fun sendingReturnsTheMessageAndAnnouncesTheChange() = runTest {
        val service = service()
        val events = mutableListOf<ServiceEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { service.events.toList(events) } // subscribed before sending
        val sent = service.sendText(chat, "hello", replyTo = Message("q", chat.id, 1, Direction.INCOMING))
        advanceUntilIdle()
        assertEquals("hello", sent.text)
        assertEquals("send:c:1:hello:q", service.fake.calls.single())
        assertTrue(events.any { it is ServiceEvent.MessagesChanged && it.conversationId == chat.id })
        collector.cancel()
    }

    @Test
    fun aFailedSendIsRetriedUnderItsOwnId() = runTest {
        val service = service()
        assertTrue(service.capabilities().retries)
        val events = mutableListOf<ServiceEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { service.events.toList(events) }
        service.retryMessage(chat, Message("m2", chat.id, 6, Direction.OUTGOING, receipt = Receipt(Receipt.State.FAILED)))
        advanceUntilIdle()
        assertEquals("retry:m2", service.fake.calls.single())
        assertTrue(events.any { it is ServiceEvent.MessagesChanged && it.conversationId == chat.id })
        collector.cancel()
    }

    @Test
    fun sendingAndFailedStatusesMapToTheClockAndFailure() = runTest {
        val json = BridgeJson(ServiceId.WHATSAPP) { emptyList() }
        fun status(value: String) = json.message("""{"id":"m","chat":"c:1","fromMe":true,"ts":1,"kind":"text","text":"x","status":"$value","reactions":[]}""").receipt
        assertEquals(null, status("sending"))
        assertEquals(Receipt.State.FAILED, status("failed")?.state)
    }

    @Test
    fun detailsSayWhoAMessageReachedAndWhen() = runTest {
        val service = service()
        val sent = Message("m2", chat.id, 6, Direction.OUTGOING, receipt = Receipt(Receipt.State.READ))
        val readers = service.messageDetails(chat, sent).receipt!!.readBy
        assertEquals(listOf(Receipt.Reader("Ann", 100, 200), Receipt.Reader("Bob", 150)), readers)
        // Nothing to say about other people's messages.
        val received = Message("m1", chat.id, 5, Direction.INCOMING)
        assertEquals(received, service.messageDetails(chat, received))
    }

    @Test
    fun presenceIsTheOtherPersonsInAOneToOneChat() = runTest {
        assertEquals(Presence(online = false, lastSeen = 500), service().presence(chat))
    }

    @Test
    fun aNetworkChangeDropsTheConnectionEvenIfItLooksOpen() = runTest {
        val service = service()
        service.reconnect(networkChanged = true)
        service.reconnect(networkChanged = false)
        assertEquals(listOf("reconnect:true", "reconnect:false"), service.fake.calls)
    }

    @Test
    fun removingAReactionSendsRemove() = runTest {
        val service = service()
        service.react(chat, Message("m1", chat.id, 1, Direction.INCOMING), "👍", remove = true)
        assertEquals("react:m1:👍:true", service.fake.calls.single())
    }

    @Test
    fun bridgeFailuresBecomeReadableExceptions() = runTest {
        val service = service(FakeBridge().apply { failWith = "network down" })
        try {
            service.sendText(chat, "x")
            fail("expected an exception")
        } catch (error: BridgeException) {
            assertEquals("network down", error.message)
        }
    }

    @Test
    fun mediaGoesThroughTheBridgeWithItsKind() = runTest {
        val service = service()
        val attachment = Attachment("m9", AttachmentKind.IMAGE)
        val message = Message("m9", chat.id, 1, Direction.INCOMING, attachments = listOf(attachment))
        assertEquals("/tmp/m9.jpg", service.downloadAttachment(chat, message, attachment))
        service.sendVoiceNote(chat, "/tmp/v.ogg", "audio/ogg", 1500)
        service.sendAttachment(chat, "/tmp/p.jpg", "image/jpeg")
        assertEquals(listOf("download:m9", "media:audio/ogg:true", "media:image/jpeg:false"), service.fake.calls)
    }

    @Test
    fun incomingEventsBecomeNotifications() = runTest {
        val service = service()
        // Subscribe before emitting: the flow doesn't replay. The announcement
        // runs on a real IO thread, so wait in real time.
        val announced = async(start = CoroutineStart.UNDISPATCHED) {
            service.events.first { it is ServiceEvent.IncomingMessage } as ServiceEvent.IncomingMessage
        }
        service.emit("incoming", """{"chat":"c:1","id":"m1"}""")
        val event = withContext(Dispatchers.Default) { withTimeout(5_000) { announced.await() } }
        assertEquals("hi", event.message.text)
        assertEquals("whatsapp:c:1", event.conversation.id)
    }

    @Test
    fun typingNoticesShowOnTheConversation() = runTest {
        val service = service()
        service.emit("typing", """{"chat":"c:1","sender":"Maya","active":true}""")
        assertEquals(listOf("Maya"), service.listConversations(false).conversations.single().typingNames)
        service.emit("typing", """{"chat":"c:1","sender":"Maya","active":false}""")
        assertEquals(emptyList<String>(), service.listConversations(false).conversations.single().typingNames)
    }
}
