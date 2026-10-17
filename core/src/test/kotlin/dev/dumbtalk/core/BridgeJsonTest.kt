package dev.dumbtalk.core

import dev.dumbtalk.core.bridge.BridgeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeJsonTest {
    private val json = BridgeJson(ServiceId.SIGNAL) { emptyList() }

    private fun message(extra: String, reactions: String = "[]") = json.message(
        """{"id":"m","chat":"c:1","ts":1,"fromMe":false,"senderName":"Joel","reactions":$reactions,$extra}""",
    )

    @Test
    fun pollVotesAndOwnChoiceMap() {
        val poll = """{"question":"Lunch?","options":["Soup","Pie"],"multiple":false,"closed":true,"votes":[["Joel","You"],[]],"mine":[0]}"""
        val parsed = message(""""kind":"poll","poll":${org.json.JSONObject.quote(poll)}""").poll!!
        assertEquals(listOf("Joel", "You"), parsed.options[0].votes)
        assertTrue(parsed.options[0].mine)
        assertFalse(parsed.options[1].mine)
        assertTrue(parsed.closed)
    }

    @Test
    fun stickersAreBareImagesNotCaptions() {
        val sticker = message(""""kind":"sticker","mime":"image/webp"""")
        val attachment = sticker.attachments.single()
        assertEquals(AttachmentKind.IMAGE, attachment.kind)
        assertTrue(attachment.sticker)
        assertNull(sticker.text)
    }

    @Test
    fun attachmentKindsFollowTheBridgeKind() {
        fun kindOf(kind: String) = message(""""kind":"$kind"""").attachments.firstOrNull()?.kind
        assertEquals(AttachmentKind.IMAGE, kindOf("image"))
        assertEquals(AttachmentKind.VIDEO, kindOf("video"))
        assertEquals(AttachmentKind.AUDIO, kindOf("audio"))
        assertEquals(AttachmentKind.FILE, kindOf("document"))
        assertNull(kindOf("text"))
    }

    @Test
    fun photoCaptionsAndSizesSurvive() {
        val photo = message(""""kind":"image","text":"look","mime":"image/jpeg","size":4096""")
        assertEquals("look", photo.text)
        assertEquals(4096L, photo.attachments.single().size)
        assertFalse(photo.attachments.single().sticker)
    }

    @Test
    fun systemMessagesAreNeitherSideOfTheConversation() {
        assertEquals(Direction.SYSTEM, message(""""kind":"system","text":"📞 Call"""").direction)
    }

    @Test
    fun quotesAndReactionsMap() {
        val reply = message(
            """"kind":"text","text":"yes","quoteId":"q","quoteSender":"Maya","quoteText":"coming?"""",
            reactions = """[{"emoji":"👍","name":"Maya","fromMe":false},{"emoji":"❤","name":"x","fromMe":true}]""",
        )
        assertEquals("Maya", reply.quote?.author)
        assertEquals("coming?", reply.quote?.text)
        assertEquals(listOf("Maya", "x"), reply.reactions.map { it.author })
        assertEquals(listOf(false, true), reply.reactions.map { it.isOwn })
    }

    @Test
    fun stylesMentionsAndChatStateMap() {
        val styled = message(""""kind":"text","text":"@Ann hi","styles":[{"start":0,"length":4,"style":"mention","id":"a1"},{"start":5,"length":9,"style":"bold"},{"start":0,"length":1,"style":"sparkle"}]""")
        assertEquals(TextSpan(0, 4, TextSpan.Style.MENTION, "a1"), styled.spans[0])
        // Clipped to the text; unknown styles are dropped.
        assertEquals(TextSpan(5, 2, TextSpan.Style.BOLD), styled.spans[1])
        assertEquals(2, styled.spans.size)

        val chat = json.conversation("""{"jid":"c:1","name":"Ann","expireTimer":604800,"request":true,"blocked":false,"admin":true}""")
        assertEquals(604_800, chat.expiration)
        assertTrue(chat.isMessageRequest)
        assertFalse(chat.isBlocked)
        assertTrue(chat.isAdmin)

        val members = json.members("""[{"id":"a1","name":"Ann","admin":true},{"id":"b2","name":"","pending":true}]""")
        assertEquals(Member("a1", "Ann", isAdmin = true), members[0])
        assertEquals(Member("b2", "Someone", isPending = true), members[1])
        assertEquals("1 week", timerLabel(604_800))
        assertEquals("8 hours", timerLabel(28_800))
        assertEquals("Off", timerLabel(0))
    }
}
