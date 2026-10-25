package dev.dumbtalk.app.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.dumbtalk.app.BuildConfig
import dev.dumbtalk.app.Navigator
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.app.ui.focusAfterFrame
import kotlinx.coroutines.launch

/** Where the source lives; the AGPL asks that users can get it. */
const val SOURCE_URL = "github.com/samtate/dumbtalk-android"

/**
 * What DumbTalk is and how to drive it. On first run it ends with [onDone],
 * which goes on to Android's permission prompts; later it's under Settings.
 */
@Composable
fun HelpScreen(navigator: Navigator, firstRun: Boolean, onDone: () -> Unit) {
    val done = { if (firstRun) onDone() else navigator.pop() }
    Softkeys(
        center = SoftkeyAction(if (firstRun) "Continue" else "Done", done),
        right = SoftkeyAction(if (firstRun) "Continue" else "Back", done),
    )
    TextPage(if (firstRun) "Welcome" else "Keys & help") {
        Paragraph(
            "DumbTalk puts Signal, Telegram, WhatsApp and SMS in one inbox. Everything runs on this phone: " +
                "there's no server and no account with us, and nothing is tracked.",
        )
        Section("KEYS")
        Paragraph("D-pad: move.")
        Paragraph("Centre: open, or the action shown in the middle.")
        Paragraph("Left softkey, Menu or 1: the menu, or a message's actions. In a text box, 1 types a digit.")
        Paragraph("Right softkey or Back: go back.")
        Paragraph("In a chat, the newest message starts selected. Down from it reaches the message box.")
        Paragraph("On DumberOS, Menu may open the notification panel instead; use 1 or the left softkey.")
        Section("STAYING CONNECTED")
        Paragraph(
            "These networks don't push messages to unofficial apps, so DumbTalk keeps its own connection open in " +
                "the background. Allow notifications and let it ignore battery optimisation, or messages arrive late." +
                if (firstRun) " Android asks about both next." else "",
        )
        Section("LINKED DEVICES")
        Paragraph(
            "Signal and WhatsApp link this phone as an extra device to the account on your main phone. " +
                "WhatsApp signs extra devices out after about 14 days without the main phone online, and Signal " +
                "after about 30 days without use. Signal doesn't copy old messages to new devices.",
        )
        Paragraph("DumbTalk is unofficial, and unofficial apps carry some risk of an account ban.")
        if (firstRun) Paragraph("Try the demo from the inbox to see how it works before linking anything.")
    }
}

@Composable
fun AboutScreen(navigator: Navigator) {
    Softkeys(right = SoftkeyAction("Back") { navigator.pop() })
    TextPage("About") {
        Paragraph("DumbTalk ${BuildConfig.VERSION_NAME}" + if (BuildConfig.DEBUG) " (debug)" else "")
        Paragraph("Unofficial. Not affiliated with Signal, Telegram or WhatsApp.")
        Section("SOURCE")
        Paragraph("Free software under the GNU Affero General Public License 3.0. The source is at $SOURCE_URL.")
        Section("BUILT ON")
        Paragraph("Signal: presage and libsignal. Telegram: TDLib. WhatsApp: whatsmeow.")
        Paragraph(
            "Interface font: \"Dot Matrix\" by Svein Kåre Gunnarson (Dionaea Fonts), freeware. " +
                "Other characters: GNU Unifont 17.0.05, under the SIL Open Font License 1.1 and GPL 2+ with the font embedding exception.",
        )
    }
}

/** A page of text that Up and Down scroll, since plain text can't take focus. */
@Composable
private fun TextPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val requester = remember { FocusRequester() }
    val step = with(LocalDensity.current) { 80.dp.toPx() }
    LaunchedEffect(Unit) { requester.focusAfterFrame() }
    Column(Modifier.fillMaxSize()) {
        Header(title)
        Column(
            Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .onKeyEvent { event ->
                    val direction = when (event.key) {
                        Key.DirectionUp -> -1
                        Key.DirectionDown -> 1
                        else -> return@onKeyEvent false
                    }
                    if (event.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(direction * step) }
                    true
                }
                .focusable()
                .verticalScroll(scroll)
                .padding(horizontal = 9.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun Paragraph(text: String) = BasicText(text, style = Type.body)

@Composable
private fun Section(text: String) = BasicText(text, style = Type.section, modifier = Modifier.padding(top = 6.dp))
