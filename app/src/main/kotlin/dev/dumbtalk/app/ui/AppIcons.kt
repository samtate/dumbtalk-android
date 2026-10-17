package dev.dumbtalk.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Stroke icons from dumbtalk's AppIcon.tsx (24-unit viewBox, 1.8 stroke).
enum class AppIcon(val pathData: String) {
    PLAY("M8 4l13 8-13 8z"),
    BOTTOM("M12 3v14M6 11l6 6 6-6M5 21h14"),
    STOP("M5 5h14v14H5z"),
    SEND("M3 3l19 9-19 9 4-9zM7 12h15"),
    CLOSE("M5 5l14 14M19 5L5 19"),
    CAMERA("M3 7h5l2-3h4l2 3h5v14H3zM8 14a4 4 0 1 0 8 0a4 4 0 1 0 -8 0"),
    ARCHIVE("M4 7h16v13H4zM2.5 4h19v4h-19zM9 12h6"),
    ATTACH("M8.5 12.5 15 6a3.2 3.2 0 0 1 4.5 4.5l-8 8a5 5 0 0 1-7-7l8-8a2.8 2.8 0 0 1 4 4l-7.5 7.5a1 1 0 0 1-1.5-1.5L14 7"),
    BLOCK("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0zM6 18l12-12"),
    COMPOSE("M4 20h4l11-11-4-4L4 16zM13.8 6.2l4 4M12 4H5a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h13a2 2 0 0 0 2-2v-7"),
    DELETE("M5 7h14M9 7V4h6v3M7 7l1 14h8l1-14M10 11v6M14 11v6"),
    EDIT("M4 16l-.8 4.8L8 20l11-11-4-4zM13.8 6.2l4 4"),
    FORWARD("M13 5l7 7-7 7v-4C7 15 4 17 2 20c1-7 5-11 11-11z"),
    GROUP("M6 8a3 3 0 1 0 6 0a3 3 0 1 0 -6 0zM14.5 9a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0zM3 20v-2.2C3 14.6 5.7 13 9 13s6 1.6 6 4.8V20M15 14c3.4 0 6 1.5 6 4.5V20"),
    MENU("M5 7h14M5 12h14M5 17h14"),
    MIC("M12 3h0a4 4 0 0 1 4 4v5a4 4 0 0 1 -4 4h-0a4 4 0 0 1 -4 -4v-5a4 4 0 0 1 4 -4zM5 11a7 7 0 0 0 14 0M12 18v3M8 21h8"),
    MUTE("M5 10v4h4l5 4V6L9 10zM18 9l4 6M22 9l-4 6"),
    PIN("M8 3l8 8M6 9l9 9M13 5l6 6-4 2-5 5-2-4-4-4zM8 16l-5 5"),
    POLL("M4 20V10M10 20V4M16 20v-7M22 20V7"),
    REPLY("M10 7l-7 5 7 5v-3c6 0 9 2 11 6 0-8-4-11-11-11z"),
    SAFETY("M12 2 20 5v6c0 5-3.3 9-8 11-4.7-2-8-6-8-11V5zM8.5 12l2.2 2.2 4.8-5"),
    SEARCH("M4 10.5a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0zM15.5 15.5l5 5"),
    SERVICES("M4 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0zM12 16a4 4 0 1 0 8 0a4 4 0 1 0 -8 0zM11 11l2 2"),
    SETTINGS("M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0zM12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2"),
    STAR("M12 3l2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-3-5.6 3 1.1-6.2L3 9.6l6.2-.9z"),
    STICKER("M5 3h14a2 2 0 0 1 2 2v9l-7 7H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM14 21v-5a2 2 0 0 1 2-2h5M8 9h.01M16 9h.01M8.5 13c2 2 5 2 7 0"),
    TIMER("M4 13a8 8 0 1 0 16 0a8 8 0 1 0 -16 0zM12 9v5l3 2M9 2h6");
}

@Composable
fun Icon(icon: AppIcon, modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Palette.text) {
    val path = remember(icon) { PathParser().parsePathString(icon.pathData).toPath() }
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / 24f
        scale(unit, pivot = Offset.Zero) {
            drawPath(path, color, style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
