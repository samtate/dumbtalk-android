package dev.dumbtalk.app.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font as ComposeFont
import dev.dumbtalk.app.R
import android.graphics.fonts.FontFamily as PlatformFontFamily

/**
 * Text falls back from the dot-matrix face to GNU Unifont (pixel glyphs for
 * accents, symbols, other scripts and emoji) and only then to the system
 * font, so almost everything on screen stays pixel-styled. Built once at
 * startup from [init]; Android 10+ supports custom fallback chains.
 */
object Fonts {
    lateinit var dot: FontFamily
        private set
    lateinit var dotBold: FontFamily
        private set

    /** [pixelFallback] false uses the dot-matrix face with only the system fallback (tests). */
    fun init(context: Context, pixelFallback: Boolean = true) {
        val chains = if (pixelFallback) runCatching { pixelChains(context) }.getOrNull() else null
        dot = chains?.first ?: FontFamily(ComposeFont(R.font.dot_matrix))
        dotBold = chains?.second ?: FontFamily(ComposeFont(R.font.dot_matrix_bold))
    }

    private fun pixelChains(context: Context): Pair<FontFamily, FontFamily> {
        fun family(resource: Int) = PlatformFontFamily.Builder(Font.Builder(context.resources, resource).build()).build()
        val unifont = family(R.font.unifont)
        val unifontUpper = family(R.font.unifont_upper)
        fun chain(primary: Int) = FontFamily(
            Typeface.CustomFallbackBuilder(family(primary))
                .addCustomFallback(unifont)
                .addCustomFallback(unifontUpper)
                .setSystemFallback("sans-serif")
                .build(),
        )
        return chain(R.font.dot_matrix) to chain(R.font.dot_matrix_bold)
    }
}

private val presentationMarks = Regex("[\\uFE0E\\uFE0F\\x{1F3FB}-\\x{1F3FF}]")

/** A zero-width joiner and the emoji it joins on. */
private val joinedParts = Regex("\\u200D\\p{So}?")

/** Emoji and pictographs that Unifont draws as full-width cells. */
private val pictograph = Regex("([\\x{1F000}-\\x{1FAFF}\\u2600-\\u27BF\\u2B00-\\u2BFF])(?=\\S)")

/**
 * Text as it should be drawn with the pixel fonts:
 * - emoji variation selectors and skin tones are dropped, since Android
 *   would otherwise switch those sequences to the colour emoji font;
 * - joined emoji keep only their first part (🐈‍⬛ → 🐈, a family → its first
 *   person), which Unifont can draw;
 * - a thin space follows each pictograph, whose cells are edge to edge.
 */
fun String.forDisplay(): String =
    replace(presentationMarks, "")
        .replace(joinedParts, "")
        .replace(pictograph, "\$1\u2009")
