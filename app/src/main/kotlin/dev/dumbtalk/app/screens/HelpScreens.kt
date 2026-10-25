package dev.dumbtalk.app.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.dumbtalk.app.BuildConfig
import dev.dumbtalk.app.Navigator
import dev.dumbtalk.app.R
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.Square
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.app.ui.focusAfterFrame
import kotlinx.coroutines.launch

/** Where the source lives; the AGPL asks that users can get it. */
const val SOURCE_URL = "github.com/samtate/dumbtalk-android"

private class HelpPage(val title: String, val content: @Composable ColumnScope.() -> Unit)

/**
 * What DumbTalk is and how to drive it, a page at a time. On first run it
 * ends with [onDone], which goes on to Android's permission prompts; later
 * it's under Settings, without the welcome.
 */
@Composable
fun HelpScreen(navigator: Navigator, firstRun: Boolean, onDone: () -> Unit) {
    val pages = remember(firstRun) { helpPages(firstRun) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val finish: () -> Unit = { if (firstRun) onDone() else navigator.pop() }
    val next: () -> Unit = { if (index == pages.lastIndex) finish() else index++ }
    val previous: () -> Unit = { if (index > 0) index-- }
    // ◀ (the left softkey) turns back a page, ▶ and centre forward.
    Softkeys(
        left = if (index > 0) SoftkeyAction("Previous", previous) else null,
        center = SoftkeyAction(if (index < pages.lastIndex) "Next" else if (firstRun) "Start" else "Done", next),
        right = when {
            !firstRun -> SoftkeyAction("Back") { navigator.pop() }
            index < pages.lastIndex -> SoftkeyAction("Skip", finish)
            else -> null // Start already finishes
        },
    )
    val page = pages[index]
    TextPage(
        page.title,
        trailing = { BasicText(pages.indices.joinToString(" ") { if (it == index) "●" else "○" }, style = Type.small) },
        onRight = next,
        pageKey = index,
        content = page.content,
    )
}

private fun helpPages(firstRun: Boolean) = buildList {
    if (firstRun) add(HelpPage("Welcome") {
        Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.dumbtalk_logo), null, Modifier.size(72.dp).clip(Square))
        }
        Centred("DumbTalk", Type.title)
        Centred("Signal · Telegram · WhatsApp · SMS", Type.preview)
        Centred("One inbox, on this phone.", Type.body)
        Table {
            Cell("✓", "No server")
            Cell("✓", "No account with us")
            Cell("✓", "No tracking")
        }
        Hint("Centre or ▶ for the next page")
    })
    add(HelpPage("Keys") {
        Table {
            Cell("▲ ▼", "Move")
            Cell("●", "Open, or the middle action")
            Cell("◀", "Menu, or a message's actions")
            Cell("↩", "Back")
        }
        Hint("The bar at the bottom shows what each key does. ◀ there means D-pad Left.")
        Hint("In a text box ◀ ▶ move the cursor; ↩ leaves it.")
    })
    add(HelpPage("In a chat") {
        Table {
            Cell("▲ ▼", "Pick a message")
            Cell("●", "Quick 👍")
            Cell("◀", "Reply, react, forward…")
            Cell("▼ at end", "Write a message")
        }
        Hint("The newest message starts selected. In the message box, ● sends.")
    })
    add(HelpPage("Stay connected") {
        Hint("These networks don't push to unofficial apps, so DumbTalk keeps its own connection open.")
        Table {
            Cell("🔔", "Allow notifications")
            Cell("🔋", "Don't optimise battery")
        }
        Hint(if (firstRun) "Android asks for both next." else "Check: Settings → Background delivery.")
    })
    add(HelpPage("Good to know") {
        Table {
            Cell("WhatsApp", "⏱ Signed out after 14 days without your main phone")
            Cell("Signal", "⏱ Unlinked after 30 days unused")
            Cell("Signal", "✗ No old messages")
            Cell("⚠", "Unofficial: some risk of a ban")
        }
        if (firstRun) Hint("Not ready to link? Try the demo from the inbox.")
    })
}

@Composable
fun AboutScreen(navigator: Navigator) {
    Softkeys(right = SoftkeyAction("Back") { navigator.pop() })
    TextPage("About") {
        Table {
            Cell("Version", BuildConfig.VERSION_NAME + if (BuildConfig.DEBUG) " (debug)" else "")
            Cell("Licence", "GNU AGPL 3.0")
            Cell("Source", SOURCE_URL)
        }
        Hint("Unofficial. Not affiliated with Signal, Telegram or WhatsApp.")
        Section("BUILT ON")
        Table {
            Cell("Signal", "presage, libsignal")
            Cell("Telegram", "TDLib")
            Cell("WhatsApp", "whatsmeow")
        }
        Section("FONTS")
        Hint("\"Dot Matrix\" by Svein Kåre Gunnarson (Dionaea Fonts), freeware.")
        Hint("GNU Unifont 17.0.05: SIL Open Font License 1.1 and GPL 2+ with the font embedding exception.")
    }
}

/**
 * A page that Up and Down scroll, since text can't take focus. [onRight]
 * turns the page; Left is left to the softkey bar. A new [pageKey] starts
 * the page at the top.
 */
@Composable
private fun TextPage(
    title: String,
    trailing: @Composable (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    pageKey: Any = Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = remember(pageKey) { ScrollState(0) }
    val scope = rememberCoroutineScope()
    val requester = remember { FocusRequester() }
    val step = with(LocalDensity.current) { 80.dp.toPx() }
    LaunchedEffect(Unit) { requester.focusAfterFrame() }
    Column(Modifier.fillMaxSize()) {
        Header(title, trailing = trailing)
        Column(
            Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .onKeyEvent { event ->
                    val action: (() -> Unit)? = when (event.key) {
                        Key.DirectionUp -> { { scope.launch { scroll.animateScrollBy(-step) } } }
                        Key.DirectionDown -> { { scope.launch { scroll.animateScrollBy(step) } } }
                        Key.DirectionRight -> onRight
                        else -> null
                    }
                    if (action == null) return@onKeyEvent false
                    if (event.type == KeyEventType.KeyDown) action()
                    true
                }
                .focusable()
                .verticalScroll(scroll)
                .padding(horizontal = 9.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** Rows of a two-column table: a key or label in a box, then what it means. */
@Composable
internal fun Table(rows: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = rows)

@Composable
internal fun Cell(key: String, meaning: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            key,
            style = Type.label.copy(textAlign = TextAlign.Center),
            modifier = Modifier.width(96.dp).border(1.dp, Palette.line, Square).padding(vertical = 4.dp, horizontal = 2.dp),
        )
        BasicText(meaning, style = Type.body, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun Centred(text: String, style: TextStyle) =
    BasicText(text, style = style.copy(textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())

@Composable
internal fun Hint(text: String) = BasicText(text, style = Type.preview)

@Composable
private fun Section(text: String) = BasicText(text, style = Type.section, modifier = Modifier.padding(top = 6.dp))
