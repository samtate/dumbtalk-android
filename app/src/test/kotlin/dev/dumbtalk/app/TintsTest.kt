package dev.dumbtalk.app

import androidx.compose.ui.graphics.Color
import dev.dumbtalk.app.ui.assignTints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TintsTest {
    private val telegramBlue = Color(0xFF7CC4EA)

    @Test
    fun everyoneInAChatGetsTheirOwnColour() {
        val people = listOf("David Tate", "Gillian Tate", "Alex", "Sam T", "Mum", "Chris")
        val tints = assignTints(people, avoid = telegramBlue)
        assertEquals(people.size, tints.values.toSet().size)
    }

    @Test
    fun nobodySharesYourColour() {
        val tints = assignTints(listOf("David Tate", "Gillian Tate", "Alex"), avoid = telegramBlue)
        tints.values.forEach { assertNotEquals(telegramBlue, it) }
    }

    @Test
    fun aPersonKeepsTheirColourAsOthersJoinLater() {
        val first = assignTints(listOf("David Tate"), avoid = telegramBlue).getValue("David Tate")
        val later = assignTints(listOf("David Tate", "Gillian Tate"), avoid = telegramBlue).getValue("David Tate")
        assertEquals(first, later)
    }
}
