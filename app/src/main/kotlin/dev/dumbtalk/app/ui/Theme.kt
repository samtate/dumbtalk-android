package dev.dumbtalk.app.ui

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Colours from dumbtalk's _tokens.scss and component styles. Sizes are the
// web UI's CSS pixels as dp, which is what the old WebView wrapper rendered.
object Palette {
    val background = Color(0xFF0B1713)
    val panel = Color(0xFF14251F)
    val line = Color(0xFF315349)
    val text = Color(0xFFF4F7F5)
    val muted = Color(0xFF9DB1A8)
    val focus = Color(0xFF6C9FFF)
    val blue = Color(0xFF3A76F0)
    val error = Color(0xFFFF9399)
    val avatar = Color(0xFF31584B)
    val unread = Color(0xFFD73D47)
    val incoming = Color(0xFF233A32)
    val outgoing = Color(0xFF2454A7)
    val sender = Color(0xFF9FC4FF)
    val time = Color(0xFFC0CEC8)
    val tileBorder = Color(0xFF2C473E)
    val pill = Color(0xFF23453A)

    val header = Brush.linearGradient(listOf(Color(0xFF15342A), Color(0xFF10201B)))
    val row = Brush.linearGradient(listOf(Color(0xFF162B24), Color(0xFF12231E)))
    val tile = Brush.linearGradient(listOf(Color(0xFF1A3029), Color(0xFF13251F)))
    val softkeyBar = Brush.verticalGradient(listOf(Color(0xFF0B1512), Color(0xFF050A08)))
}

object Type {
    val body = TextStyle(color = Palette.text, fontSize = 13.sp, lineHeight = 15.sp)
    val title = body.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold)
    val header = body.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold)
    val preview = body.copy(color = Palette.muted, fontSize = 11.sp, lineHeight = 13.sp)
    val small = body.copy(fontSize = 10.sp, lineHeight = 12.sp)
    val softkey = body.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold)
    val section = body.copy(color = Palette.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun DumbTalkTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(Palette.focus, Palette.focus.copy(alpha = 0.4f)),
        content = content,
    )
}
