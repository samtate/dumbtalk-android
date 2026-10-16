package dev.dumbtalk.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import dev.dumbtalk.core.ServiceId

/**
 * Small icons drawn as lit dots on a grid, like the dot-matrix font. Each
 * row is a string; '#' is a lit dot.
 */
enum class PixelArt(val rows: List<String>) {
    /** Signal's speech bubble. */
    SIGNAL(
        listOf(
            "..#####..",
            ".#.....#.",
            "#.......#",
            "#.#.#.#.#",
            "#.......#",
            "#.......#",
            ".#.....#.",
            "#.#####..",
            "#........",
        ),
    ),

    /** Telegram's paper plane. */
    TELEGRAM(
        listOf(
            ".........",
            ".......##",
            ".....##.#",
            "...##..#.",
            ".##...#..",
            "#....#...",
            ".##.##...",
            "...##....",
            "...#.....",
        ),
    ),

    /** WhatsApp's handset in a bubble. */
    WHATSAPP(
        listOf(
            "..#####..",
            ".#.....#.",
            "#.##....#",
            "#.#.....#",
            "#..#....#",
            "#...##..#",
            ".#....##.",
            "#.#####..",
            "#........",
        ),
    ),

    /** Note to Self. */
    PENCIL(
        listOf(
            "......##.",
            ".....#..#",
            "....#..#.",
            "...#..#..",
            "..#..#...",
            ".#..#....",
            ".###.....",
            "##.......",
            ".........",
        ),
    ),
}

@Composable
fun PixelIcon(art: PixelArt, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val columns = art.rows.maxOf { it.length }
        val cell = this.size.minDimension / maxOf(columns, art.rows.size)
        val radius = cell * 0.42f
        art.rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, dot ->
                if (dot == '#') drawCircle(color, radius, Offset((x + 0.5f) * cell, (y + 0.5f) * cell))
            }
        }
    }
}

/** Toned-down brand colours, so services stay recognisable on black. */
fun serviceColor(id: ServiceId) = when (id) {
    ServiceId.SIGNAL -> Color(0xFF7FA2F0)
    ServiceId.TELEGRAM -> Color(0xFF7CC4EA)
    ServiceId.WHATSAPP -> Color(0xFF7FD6A0)
}

@Composable
fun ServiceIcon(id: ServiceId, size: Dp = Dp(16f)) {
    val art = when (id) {
        ServiceId.SIGNAL -> PixelArt.SIGNAL
        ServiceId.TELEGRAM -> PixelArt.TELEGRAM
        ServiceId.WHATSAPP -> PixelArt.WHATSAPP
    }
    PixelIcon(art, size, serviceColor(id), Modifier.semantics { contentDescription = id.label })
}
