package dev.dumbtalk.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumsTest {
    private fun photo(id: String, album: String?, text: String? = null, sender: String = "Ana") = Message(
        id = id, conversationId = "c", sentAt = id.hashCode().toLong(), direction = Direction.INCOMING, sender = sender,
        text = text, attachments = listOf(Attachment(id, AttachmentKind.IMAGE)), albumId = album,
    )

    @Test
    fun albumPartsJoinIntoTheFirst() {
        val merged = mergeAlbums(listOf(
            Message("parent", "c", 0, Direction.INCOMING, sender = "Ana", text = "Unsupported message"),
            photo("a", "parent"),
            photo("b", "parent", text = "Beach").copy(spans = listOf(TextSpan(0, 5, TextSpan.Style.BOLD))),
            photo("c", "parent", text = "Day two"),
            photo("d", "other", sender = "Ben"),
            photo("e", null),
        ))
        assertEquals(listOf("a", "d", "e"), merged.map { it.id })
        val album = merged.first()
        assertEquals(listOf("a", "b", "c"), album.attachments.map { it.id })
        assertEquals("Beach\nDay two", album.text)
        assertEquals(listOf(TextSpan(0, 5, TextSpan.Style.BOLD)), album.spans)
    }
}
