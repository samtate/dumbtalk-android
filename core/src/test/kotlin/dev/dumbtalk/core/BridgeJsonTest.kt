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
        assertEquals(listOf("Maya", "You"), reply.reactions.map { it.author })
        assertEquals(listOf(false, true), reply.reactions.map { it.isOwn })
    }
}
