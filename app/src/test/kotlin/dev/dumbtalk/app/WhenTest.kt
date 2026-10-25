package dev.dumbtalk.app

import dev.dumbtalk.app.ui.describePresence
import dev.dumbtalk.app.ui.formatWhen
import dev.dumbtalk.core.Presence
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhenTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 10, 25, 18, 0).toInstant(zone).toEpochMilli()
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 5).toInstant(zone).toEpochMilli()

    @Test
    fun timesSayTheDayOnlyWhenItIsNotToday() {
        assertEquals(false, formatWhen(at(25, 9), now, zone).contains(" "))
        assertEquals(true, formatWhen(at(24, 9), now, zone).startsWith("yesterday "))
        assertEquals(true, formatWhen(at(21, 9), now, zone).startsWith("Wed "))
        assertEquals(true, formatWhen(at(2, 9), now, zone).startsWith("2 Oct "))
    }

    @Test
    fun presenceReadsNaturally() {
        assertEquals("online", describePresence(Presence(online = true), now, zone))
        assertEquals("last seen yesterday ${formatWhen(at(24, 9), now, zone).substringAfter(' ')}", describePresence(Presence(false, at(24, 9)), now, zone))
        assertEquals("last seen recently", describePresence(Presence(false, approximately = Presence.Approximately.RECENTLY), now, zone))
        assertNull(describePresence(Presence(false), now, zone))
        assertNull(describePresence(null, now, zone))
    }
}
