package dev.dumbtalk.app

import dev.dumbtalk.app.ui.forDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayTextTest {
    private val thin = " "

    @Test
    fun dropsColourSelectorsAndSkinTones() {
        assertEquals("I ❤ it", "I ❤️ it".forDisplay())
        assertEquals("👍", "👍🏽".forDisplay())
    }

    @Test
    fun joinedEmojiKeepTheirFirstPart() {
        assertEquals("🐈", "🐈‍⬛".forDisplay())
        assertEquals("👨", "👨‍👩‍👧".forDisplay())
    }

    @Test
    fun separatesTouchingPictographs() {
        assertEquals("🐈${thin}♥${thin}😬", "🐈♥😬".forDisplay())
        assertEquals("♥${thin}2", "♥2".forDisplay())
        assertEquals("plain text 😄", "plain text 😄".forDisplay())
        assertEquals("😄 next", "😄 next".forDisplay())
    }

    @Test
    fun spoilersStayHiddenUntilRevealed() {
        val spans = listOf(dev.dumbtalk.core.TextSpan(4, 6, dev.dumbtalk.core.TextSpan.Style.SPOILER))
        assertEquals("The ░░░░░░ did it", dev.dumbtalk.app.ui.withoutSpoilers("The butler did it", spans))
        // A surrogate pair is one character, so one block.
        assertEquals("a ░ b", dev.dumbtalk.app.ui.withoutSpoilers("a 😀 b", listOf(dev.dumbtalk.core.TextSpan(2, 2, dev.dumbtalk.core.TextSpan.Style.SPOILER))))
    }
}
