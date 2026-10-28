package dev.dumbtalk.app

import dev.dumbtalk.app.screens.MessageLink
import dev.dumbtalk.app.screens.messageLinks
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageLinksTest {
    @Test
    fun findsEachLinkOnceWithoutTrailingPunctuation() {
        val links = messageLinks("See https://example.com/a, (www.test.org) and https://example.com/a.")
        assertEquals(listOf(MessageLink("https://example.com/a", "example.com/a"), MessageLink("https://www.test.org", "test.org")), links)
    }

    @Test
    fun keepsBracketsThatBelongToTheLink() {
        assertEquals("https://en.wikipedia.org/wiki/Nokia_(company)", messageLinks("https://en.wikipedia.org/wiki/Nokia_(company)!").single().url)
    }

    @Test
    fun noLinksInPlainText() {
        assertEquals(emptyList<MessageLink>(), messageLinks("meet at 5.30, ok?"))
        assertEquals(emptyList<MessageLink>(), messageLinks(null))
    }
}
