package dev.dumbtalk.app.ui

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * Retro, VFD-style: white on pure black, grey for secondary text, thin grey
 * outlines instead of filled cards, and a solid white frame for D-pad focus.
 * Colour is kept for meaning only (errors).
 */
object Palette {
    val background = Color(0xFF000000)
    val panel = Color(0xFF111111)
    val line = Color(0xFF3A3A3A)
    val text = Color(0xFFFFFFFF)
    val muted = Color(0xFF9E9E9E)
    val focus = Color(0xFFFFFFFF)
    val error = Color(0xFFFF6E6E)
    val avatar = Color(0xFF000000)
    val unread = Color(0xFFFFFFFF)
    val incoming = Color(0xFF141414)
    val outgoing = Color(0xFF2A2A2A)
    val sender = Color(0xFFBDBDBD)
    val time = Color(0xFF9E9E9E)
    val tileBorder = Color(0xFF3A3A3A)
    val pill = Color(0xFF1C1C1C)
    val send = Color(0xFFFFFFFF)

    val header: Brush = SolidColor(background)
    val row: Brush = SolidColor(background)
    val tile: Brush = SolidColor(background)
    val softkeyBar: Brush = SolidColor(background)
}

/**
 * The dot-matrix face covers plain Latin only (Android falls back to the
 * system font for other characters, emoji included) and blurs below ~12sp,
 * so nothing set in it is smaller than that.
 */
object Type {
    /** Message text and anything users write. Trial: dot matrix here too. */
    val body = TextStyle(color = Palette.text, fontFamily = Fonts.dot, fontSize = 16.sp, lineHeight = 20.sp)
    val preview = body.copy(color = Palette.muted, fontSize = 14.sp, lineHeight = 17.sp)

    /** "Dot Matrix" by Svein Kåre Gunnarson (Dionaea Fonts, freeware), regular and bold. */
    private val dot = TextStyle(color = Palette.text, fontFamily = Fonts.dot, letterSpacing = 0.5.sp)
    private val dotBold = dot.copy(fontFamily = Fonts.dotBold)
    val title = dot.copy(fontSize = 17.sp, lineHeight = 20.sp)
    val header = dotBold.copy(fontSize = 16.sp, lineHeight = 18.sp)
    val label = dot.copy(fontSize = 15.sp, lineHeight = 18.sp)
    val small = dot.copy(color = Palette.time, fontSize = 12.sp, lineHeight = 14.sp)
    val softkey = dotBold.copy(fontSize = 13.sp)
    val section = dotBold.copy(color = Palette.muted, fontSize = 12.sp)

    /**
     * For text centred in a box: drops the font's built-in padding and the
     * letter spacing after the last glyph, both of which push dot-matrix
     * text off-centre.
     */
    /** The same style in the bold dot-matrix face. */
    fun bold(style: TextStyle) = style.copy(fontFamily = Fonts.dotBold)

    fun centered(style: TextStyle) = style.copy(
        letterSpacing = 0.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
        textAlign = TextAlign.Center,
    )
}

@Composable
fun DumbTalkTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(Palette.focus, Palette.focus.copy(alpha = 0.35f)),
        content = content,
    )
}
