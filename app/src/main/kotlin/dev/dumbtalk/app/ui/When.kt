package dev.dumbtalk.app.ui

import dev.dumbtalk.core.Presence
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

private val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val weekday = DateTimeFormatter.ofPattern("EEE")
private val date = DateTimeFormatter.ofPattern("d MMM")

/** When something happened, as briefly as is clear: "14:32", "yesterday 14:32", "Mon 14:32", "12 Oct 14:32". */
fun formatWhen(millis: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(millis).atZone(zone)
    val days = ChronoUnit.DAYS.between(at.toLocalDate(), Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
    val time = at.format(clock)
    return when {
        days <= 0L -> time
        days == 1L -> "yesterday $time"
        days < 7L -> "${at.format(weekday)} $time"
        else -> "${at.format(date)} $time"
    }
}

/** "online", "last seen 14:32", "last seen recently"…; null when the network says nothing. */
fun describePresence(presence: Presence?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String? = when {
    presence == null -> null
    presence.online -> "online"
    presence.lastSeen != null -> "last seen ${formatWhen(presence.lastSeen!!, now, zone)}"
    presence.approximately == Presence.Approximately.RECENTLY -> "last seen recently"
    presence.approximately == Presence.Approximately.WITHIN_A_WEEK -> "last seen within a week"
    presence.approximately == Presence.Approximately.WITHIN_A_MONTH -> "last seen within a month"
    else -> null
}
