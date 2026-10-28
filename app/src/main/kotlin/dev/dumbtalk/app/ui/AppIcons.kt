// Shared Dumb UI kit: generated from dumb-kit/ui/AppIcons.kt by sync.py. Edit it there.
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

private fun dot(x: Int, y: Int) = "M${x - 1} ${y}a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z"

/** Stroke icons shared by every Dumb app: a 24-unit viewBox drawn with a 1.8 stroke. */
enum class AppIcon(val pathData: String) {
    ADD("M12 5v14M5 12h14"),
    ALARM("M5 13a7 7 0 1 0 14 0a7 7 0 1 0 -14 0zM12 9v4l2.5 2M3 5l3-2.5M21 5l-3-2.5M6.5 19.5L5 21M17.5 19.5L19 21"),
    ARCHIVE("M4 7h16v13H4zM2.5 4h19v4h-19zM9 12h6"),
    ATTACH("M8.5 12.5 15 6a3.2 3.2 0 0 1 4.5 4.5l-8 8a5 5 0 0 1-7-7l8-8a2.8 2.8 0 0 1 4 4l-7.5 7.5a1 1 0 0 1-1.5-1.5L14 7"),
    BLOCK("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0zM6 18l12-12"),
    BLUETOOTH("M7 7l10 10-5 4V3l5 4L7 17"),
    BOTTOM("M12 3v14M6 11l6 6 6-6M5 21h14"),
    CALENDAR("M4 6h16v15H4zM4 10h16M8 3v4M16 3v4"),
    CAMERA("M3 7h5l2-3h4l2 3h5v14H3zM8 14a4 4 0 1 0 8 0a4 4 0 1 0 -8 0"),
    CHECK("M4 12l5 5L20 6"),
    CLOSE("M5 5l14 14M19 5L5 19"),
    COMPOSE("M4 20h4l11-11-4-4L4 16zM13.8 6.2l4 4M12 4H5a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h13a2 2 0 0 0 2-2v-7"),
    CONTACTS("M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0zM4 21v-1c0-3.3 3.6-6 8-6s8 2.7 8 6v1"),
    COPY("M9 9h11v11H9zM5 15H4V4h11v1"),
    DELETE("M5 7h14M9 7V4h6v3M7 7l1 14h8l1-14M10 11v6M14 11v6"),
    EARPIECE("M4 9v6h4l5 4V5L8 9z"),
    EDIT("M4 16l-.8 4.8L8 20l11-11-4-4zM13.8 6.2l4 4"),
    EMAIL("M3 5h18v14H3zM3 5l9 8 9-8"),
    END("M2.5 14.5c5-5 14-5 19 0l-3 3-3.5-2v-3a12 12 0 0 0-6 0v3l-3.5 2z"),
    EXERCISE("M3 9v6M6 6v12M18 6v12M21 9v6M6 12h12"),
    EXPORT("M12 15V3M7 8l5-5 5 5M3 17v4h18v-4"),
    FLAME("M12 3c1 4 6 6 6 11a6 6 0 0 1-12 0c0-3 2-4 2-7 2 1 3 3 3 5 1-3 1-6 1-9z"),
    FLASH("M13 2L5 14h6l-1 8 8-12h-6z"),
    FLIP("M3 8h4l2-3h6l2 3h4v11H3zM8.5 12.5a3.5 3.5 0 0 1 6-1.5M15.5 13.5a3.5 3.5 0 0 1-6 1.5M14.5 9v2h-2M9.5 17v-2h2"),
    FOLDER("M3 6h6l2 2h10v11H3z"),
    FOOD("M7 3v8a2 2 0 0 0 2 2v8M5 3v5M9 3v5M16 3c-2 2-2 6 0 8v10M16 3c2 2 2 6 0 8"),
    FORWARD("M13 5l7 7-7 7v-4C7 15 4 17 2 20c1-7 5-11 11-11z"),
    GALLERY("M3 5h18v14H3zM3 16l5-5 4 4 3-3 6 6M15.5 8.5h.01"),
    GROUP("M6 8a3 3 0 1 0 6 0a3 3 0 1 0 -6 0zM14.5 9a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0zM3 20v-2.2C3 14.6 5.7 13 9 13s6 1.6 6 4.8V20M15 14c3.4 0 6 1.5 6 4.5V20"),
    HEADSET("M4 16v-4a8 8 0 0 1 16 0v4M4 15h3v6H4zM17 15h3v6h-3z"),
    HELP("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0zM9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6V14M12 17h.01"),
    HISTORY("M3 12a9 9 0 1 0 3-6.7M3 4v4h4M12 7v5l3 2"),
    HOLD("M8 5v14M16 5v14"),
    HTML("M8 7l-5 5 5 5M16 7l5 5-5 5M14 4l-4 16"),
    IMAGE("M3 5h18v14H3zM3 16l5-5 4 4 3-3 6 6M15 9h.01"),
    IMPORT("M12 3v12M7 10l5 5 5-5M4 15v5h16v-5"),
    INBOX("M3 13l3-8h12l3 8v6H3zM3 13h5l1 3h6l1-3h5"),
    INCOMING("M18 6L6 18M6 9v9h9"),
    INFO("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0zM12 11v6M12 7.5h.01"),
    KEYPAD(listOf(6 to 4, 12 to 4, 18 to 4, 6 to 10, 12 to 10, 18 to 10, 6 to 16, 12 to 16, 18 to 16, 12 to 21).joinToString("") { dot(it.first, it.second) }),
    KEYS("M3 6h18v12H3zM7 10h.01M11 10h.01M15 10h.01M7 14h10"),
    LABEL("M3 12V4h8l10 10-8 8zM7.5 8h.01"),
    LINK("M10 14a4.5 4.5 0 0 0 6.4 0l3.2-3.2a4.5 4.5 0 0 0-6.4-6.4L12 5.6M14 10a4.5 4.5 0 0 0-6.4 0l-3.2 3.2a4.5 4.5 0 0 0 6.4 6.4l1.2-1.2"),
    LOCATION("M5 9a7 7 0 0 1 14 0c0 5-7 12-7 12S5 14 5 9zM10 9a2 2 0 1 0 4 0a2 2 0 1 0-4 0z"),
    MAIL("M3 6h18v12H3zM3 7l9 6 9-6"),
    MAIL_OPEN("M3 10l9-6 9 6v10H3zM3 10l9 6 9-6"),
    MENU("M5 7h14M5 12h14M5 17h14"),
    MERGE("M6 3v4a6 6 0 0 0 6 6v8M18 3v4a6 6 0 0 1-6 6M9 18l3 3 3-3"),
    MIC("M12 3h0a4 4 0 0 1 4 4v5a4 4 0 0 1 -4 4h-0a4 4 0 0 1 -4 -4v-5a4 4 0 0 1 4 -4zM5 11a7 7 0 0 0 14 0M12 18v3M8 21h8"),
    MIC_OFF("M12 3h0a4 4 0 0 1 4 4v5a4 4 0 0 1 -4 4h-0a4 4 0 0 1 -4 -4v-5a4 4 0 0 1 4 -4zM5 11a7 7 0 0 0 14 0M12 18v3M8 21h8M3 3l18 18"),
    MISSED("M3 8l7 7 4-4 7 7M3 8h5M3 8v5"),
    MOVE("M3 6h6l2 2h10v11H3zM9 13h7M13 10l3 3-3 3"),
    MUTE("M5 10v4h4l5 4V6L9 10zM18 9l4 6M22 9l-4 6"),
    NOTE("M4 3h16v18H4zM8 8h8M8 12h8M8 16h5"),
    OUTGOING("M6 18L18 6M9 6h9v9"),
    PAUSE("M8 5v14M16 5v14"),
    PERSON_ADD("M4 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0zM1.5 21v-1c0-3.3 3-6 6.5-6s6.5 2.7 6.5 6v1M19 8v6M16 11h6"),
    PHONE("M5 3h3l2 5-2.5 1.5a11 11 0 0 0 7 7L16 14l5 2v3a2 2 0 0 1-2 2A16 16 0 0 1 3 5a2 2 0 0 1 2-2z"),
    PIN("M8 3l8 8M6 9l9 9M13 5l6 6-4 2-5 5-2-4-4-4zM8 16l-5 5"),
    PLAY("M8 4l13 8-13 8z"),
    POLL("M4 20V10M10 20V4M16 20v-7M22 20V7"),
    QR("M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 18h2v2h-2zM18 14h2M14 18v2"),
    REFRESH("M4 12a8 8 0 0 1 14-5.3M18 3v4h-4M20 12a8 8 0 0 1-14 5.3M6 21v-4h4"),
    REPEAT("M17 3l3 3-3 3M4 11V9a3 3 0 0 1 3-3h13M7 21l-3-3 3-3M20 13v2a3 3 0 0 1-3 3H4"),
    REPLY("M10 7l-7 5 7 5v-3c6 0 9 2 11 6 0-8-4-11-11-11z"),
    REPLY_ALL("M12 7l-6 5 6 5v-3c5 0 7.5 2 9 6 0-8-3.5-11-9-11zM7 7l-6 5 6 5"),
    RESET("M4 12a8 8 0 1 0 2.5-5.8M4 4v5h5"),
    SAFETY("M12 2 20 5v6c0 5-3.3 9-8 11-4.7-2-8-6-8-11V5zM8.5 12l2.2 2.2 4.8-5"),
    SAVE("M5 3h11l3 3v15H5zM8 3v5h7V3M8 21v-7h8v7"),
    SEARCH("M4 10.5a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0zM15.5 15.5l5 5"),
    SEND("M3 3l19 9-19 9 4-9zM7 12h15"),
    SERVICES("M4 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0zM12 16a4 4 0 1 0 8 0a4 4 0 1 0 -8 0zM11 11l2 2"),
    SETTINGS("M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0zM12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2"),
    SHARE("M8 12l9-6M8 12l9 6M3 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0zM17 5a2 2 0 1 0 4 0a2 2 0 1 0-4 0zM17 19a2 2 0 1 0 4 0a2 2 0 1 0-4 0z"),
    SIZE("M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"),
    SMS("M4 5h16v11H9l-5 4z"),
    SNOOZE("M5 13a7 7 0 1 0 14 0a7 7 0 1 0 -14 0zM9.5 10.5h5l-5 5h5M3 5l3-2.5M21 5l-3-2.5"),
    SOUND("M4 9v6h4l5 4V5L8 9zM16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12"),
    SPAM("M8 3h8l5 5v8l-5 5H8l-5-5V8zM12 7v6M12 16.5v.5"),
    SPEAKER("M4 9v6h4l5 4V5L8 9zM16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12"),
    STAR("M12 3l2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-3-5.6 3 1.1-6.2L3 9.6l6.2-.9z"),
    STICKER("M5 3h14a2 2 0 0 1 2 2v9l-7 7H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM14 21v-5a2 2 0 0 1 2-2h5M8 9h.01M16 9h.01M8.5 13c2 2 5 2 7 0"),
    STOP("M5 5h14v14H5z"),
    STOPWATCH("M5 13a7 7 0 1 0 14 0a7 7 0 1 0 -14 0zM12 13l3-3M10 2h4M12 2v4M18.5 6.5l1.5-1.5"),
    SWAP("M7 4l-4 4 4 4M3 8h14M17 12l4 4-4 4M21 16H7"),
    SYNC("M4 12a8 8 0 0 1 14-5.3M18 3v4h-4M20 12a8 8 0 0 1-14 5.3M6 21v-4h4"),
    THREAD("M4 5h16v5H4zM7 10v4h13v5H7z"),
    TIMER("M4 13a8 8 0 1 0 16 0a8 8 0 1 0 -16 0zM12 9v5l3 2M9 2h6"),
    USER("M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0zM4 21v-1c0-3.3 3.6-6 8-6s8 2.7 8 6v1"),
    VIBRATE("M8 4h8v16H8zM4 8v8M20 8v8"),
    VIDEO("M3 7h12v10H3zM15 10l6-3v10l-6-3"),
    VOICEMAIL("M3 12a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0zM14 12a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0zM6.5 15.5h11"),
    WEIGHT("M4 7h16l-2 13H6zM9 7a3 3 0 0 1 6 0M12 11l2 3"),
    WIFI("M2 9a15 15 0 0 1 20 0M5.5 12.5a10 10 0 0 1 13 0M9 16a5 5 0 0 1 6 0M12 19.5h.01");
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
