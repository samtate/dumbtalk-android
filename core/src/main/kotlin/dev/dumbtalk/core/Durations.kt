package dev.dumbtalk.core

/** How a disappearing-message timer reads: "30 seconds", "8 hours", "1 week". */
fun timerLabel(seconds: Int): String {
    if (seconds <= 0) return "Off"
    val (count, unit) = when {
        seconds % 604_800 == 0 -> seconds / 604_800 to "week"
        seconds % 86_400 == 0 -> seconds / 86_400 to "day"
        seconds % 3_600 == 0 -> seconds / 3_600 to "hour"
        seconds % 60 == 0 -> seconds / 60 to "minute"
        else -> seconds to "second"
    }
    return "$count $unit" + if (count == 1) "" else "s"
}
