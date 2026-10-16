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
}
