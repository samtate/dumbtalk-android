package dev.dumbtalk.app.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Subtle tints that stand in for photos and tell people apart. A name
 * always prefers the same tint; within one chat, [assignTints] keeps
 * everyone distinct.
 */
private val tints = listOf(
    Color(0xFFE6C38F), Color(0xFF8FD3C9), Color(0xFFE7A0AC), Color(0xFFBDAAE8),
    Color(0xFFB5D98C), Color(0xFF98C2EE), Color(0xFFEDAD80), Color(0xFFD2CBAE),
    Color(0xFF9FE0B5), Color(0xFFF09A8A), Color(0xFFD6A6E3), Color(0xFFE3D27C),
)

private fun preferred(name: String) = Math.floorMod(name.hashCode(), tints.size)

/** A name's usual tint, used where only one person is shown (inbox avatars). */
fun avatarTint(name: String): Color = tints[preferred(name)]

private fun close(a: Color, b: Color) = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue) < 0.25f

/**
 * Distinct tints for the people in one chat, in order of appearance: each
 * gets their usual tint unless someone earlier has it, then the next free
 * one. Tints close to [avoid] (your own colour there) are skipped. With more
 * people than tints, colours repeat.
 */
fun assignTints(names: List<String>, avoid: Color): Map<String, Color> {
    val usable = tints.filterNot { close(it, avoid) }
    val taken = mutableSetOf<Color>()
    val result = linkedMapOf<String, Color>()
    for (name in names) {
        if (name in result) continue
        if (taken.size == usable.size) taken.clear()
        val start = Math.floorMod(name.hashCode(), usable.size)
        val tint = (usable.indices).map { usable[(start + it) % usable.size] }.first { it !in taken }
        taken += tint
        result[name] = tint
    }
    return result
}
