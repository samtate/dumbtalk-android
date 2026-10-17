package dev.dumbtalk.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.Square
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.app.ui.focusCard
import dev.dumbtalk.app.ui.forDisplay
import dev.dumbtalk.core.Message

/** What a picked emoji is for. */
sealed interface EmojiUse {
    data class React(val message: Message) : EmojiUse
    data object Compose : EmojiUse
}

/** Common emoji in a grid: a keypad can't type them. */
private val EMOJI = listOf(
    "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "😉",
    "😍", "😘", "😋", "😛", "😜", "🤔", "😐", "😑", "🙄", "😏", "😴", "😌",
    "😔", "😷", "🤒", "🤢", "🥵", "🥶", "😵", "🤯", "😎", "🤓", "😕", "🙁",
    "😮", "😲", "😳", "🥺", "😢", "😭", "😱", "😖", "😞", "😤", "😡", "🤬",
    "❤", "🧡", "💛", "💚", "💙", "💜", "🖤", "💔", "👍", "👎", "👌", "✌",
    "🤞", "👏", "🙌", "🙏", "💪", "👋", "🤝", "🫶", "🔥", "✨", "🎉", "🎂",
    "🎁", "☕", "🍺", "🍕", "🍔", "⭐", "💯", "✅", "❌", "❓", "❗", "👀",
)

private const val COLUMNS = 6

/** A grid of emoji; centre picks the focused one. */
@Composable
fun EmojiPanel(title: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Back", onClose))
    Column(Modifier.fillMaxWidth()) {
        Header(title)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EMOJI.chunked(COLUMNS).forEachIndexed { row, emojis ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    emojis.forEachIndexed { column, emoji ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .focusCard({ onPick(emoji) }, Square, Palette.tile, if (row == 0 && column == 0) requester else null, idleBorder = Palette.tileBorder),
                            contentAlignment = Alignment.Center,
                        ) { BasicText(emoji.forDisplay(), style = Type.centered(Type.body.copy(fontSize = 22.sp, lineHeight = 22.sp))) }
                    }
                }
            }
        }
    }
}
