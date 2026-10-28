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
import dev.dumbtalk.app.ui.keysPage
import dev.dumbtalk.app.ui.welcomePage
import dev.dumbtalk.app.ui.Centred
import dev.dumbtalk.app.ui.Hint
import dev.dumbtalk.app.ui.Cell
import dev.dumbtalk.app.ui.Table
import dev.dumbtalk.app.ui.TextPage
import dev.dumbtalk.app.ui.GuideScreen
import dev.dumbtalk.app.ui.GuidePage
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

/** What DumbTalk is and how to drive it, a page at a time; from Settings, without the welcome. */
@Composable
fun HelpScreen(navigator: Navigator) =
    GuideScreen(helpPages(firstRun = false), firstRun = false, onDone = {}, onBack = { navigator.pop() })

internal fun helpPages(firstRun: Boolean) = buildList<GuidePage> {
    if (firstRun) add(
        welcomePage(
            "DumbTalk", "Signal · Telegram · WhatsApp · SMS", R.drawable.dumbtalk_logo,
            listOf("One inbox, on this phone", "No server, no account with us", "No tracking"), logoSize = 72.dp,
        ),
    )
    add(keysPage(listOf("◀ on a message" to "Reply, react, forward…")))
    add(GuidePage("In a chat") {
        Table {
            Cell("▲ ▼", "Pick a message")
            Cell("●", "Quick 👍")
            Cell("◀", "Reply, react, forward…")
            Cell("▼ at end", "Write a message")
        }
        Hint("The newest message starts selected. In the message box, ● sends.")
    })
    add(GuidePage("Stay connected") {
        Hint("These networks don't push to unofficial apps, so DumbTalk keeps its own connection open.")
        Table {
            Cell("🔔", "Allow notifications")
            Cell("🔋", "Don't optimise battery")
        }
        Hint(if (firstRun) "Allow both on the next page." else "Check: Settings → Background delivery.")
    })
    add(GuidePage("Good to know") {
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

@Composable
private fun Section(text: String) = BasicText(text, style = Type.section, modifier = Modifier.padding(top = 6.dp))
