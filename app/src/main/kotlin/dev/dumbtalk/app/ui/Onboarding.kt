// Shared Dumb UI kit: generated from dumb-kit/ui/Onboarding.kt by sync.py. Edit it there.
package dev.dumbtalk.app.ui

import android.content.Context
import android.view.KeyEvent
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The first run every Dumb app shares: which key opens menus (asked once for
 * the whole family), a few pages about this app, then the permissions it
 * needs, each explained. [Onboarding.needed] says whether to start it.
 */
object Onboarding {
    private const val PREFS = "onboarding"
    private const val DONE = "done"

    fun needed(context: Context) = !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(DONE, false)

    fun finish(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(DONE, true).apply()
    }

    /** Whether to ask about the left key: nobody in the family has chosen yet. Queries siblings, so call off the main thread. */
    fun askLeftKey(context: Context) = Family.sync(context) == null
}

/**
 * The whole first run: the menu key (unless a sibling app already asked),
 * [pages], then [permissions]. [onDone] follows, and later starts skip it.
 */
@Composable
fun FirstRun(pages: List<GuidePage>, permissions: List<PermissionItem>, onDone: () -> Unit) {
    val context = LocalContext.current
    val softkeys = LocalSoftkeys.current
    var step by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        if (step >= 0) return@LaunchedEffect
        val ask = withContext(Dispatchers.IO) { Onboarding.askLeftKey(context) }
        softkeys.refresh(context)
        step = if (ask) 0 else 1
    }
    val finish = {
        Onboarding.finish(context)
        onDone()
    }
    when (step) {
        -1 -> CenteredMessage("…")
        0 -> LeftKeyScreen { step = 1 }
        1 -> GuideScreen(pages, firstRun = true, onDone = { if (permissions.isEmpty()) finish() else step = 2 }, onBack = {})
        else -> PermissionsScreen(permissions, onDone = finish)
    }
}

class GuidePage(val title: String, val content: @Composable ColumnScope.() -> Unit)

/**
 * Pages read one at a time: centre or Right turns forward, the left softkey
 * back. On [firstRun] the right softkey skips to [onDone]; later (from
 * Settings › Help) it goes [onBack].
 */
@Composable
fun GuideScreen(pages: List<GuidePage>, firstRun: Boolean, onDone: () -> Unit, onBack: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[index.coerceAtMost(pages.lastIndex)]
    val finish: () -> Unit = { if (firstRun) onDone() else onBack() }
    val next: () -> Unit = { if (index >= pages.lastIndex) finish() else index++ }
    Softkeys(
        left = if (index > 0) SoftkeyAction("Previous") { index-- } else null,
        center = SoftkeyAction(if (index < pages.lastIndex) "Next" else if (firstRun) "Start" else "Done", next),
        right = when {
            !firstRun -> SoftkeyAction("Back", onBack)
            index < pages.lastIndex -> SoftkeyAction("Skip", finish)
            else -> null
        },
    )
    TextPage(
        page.title,
        trailing = { BasicText(pages.indices.joinToString(" ") { if (it == index) "●" else "○" }, style = Type.small) },
        onRight = next,
        pageKey = index,
        content = page.content,
    )
}

/**
 * The opening page: the app's mark, name and what it promises. [logo] is a
 * drawable; the launcher's foreground works, its padding allowed for by
 * [logoSize].
 */
fun welcomePage(name: String, tagline: String, logo: Int?, points: List<String>, logoSize: Dp = 108.dp) = GuidePage("Welcome") {
    if (logo != null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(painterResource(logo), null, Modifier.size(logoSize).clip(Square))
        }
    }
    Centred(name, Type.title)
    Centred(tagline, Type.preview)
    if (points.isNotEmpty()) Table { points.forEach { Cell("✓", it) } }
    Hint("Centre or ▶ for the next page")
}

/** The keys every Dumb app shares, then [extra] rows of this app's own. */
fun keysPage(extra: List<Pair<String, String>> = emptyList()) = GuidePage("Keys") {
    val dpadLeft = LocalSoftkeys.current.dpadLeft
    Table {
        Cell("▲ ▼", "Move")
        Cell("●", "Open, or the middle action")
        Cell(if (dpadLeft) "◀" else "Left soft", "Menu")
        Cell("↩", "Back")
        extra.forEach { (key, meaning) -> Cell(key, meaning) }
    }
    Hint("The bar at the bottom shows what each key does." + if (dpadLeft) " ◀ there means D-pad Left." else "")
}

/**
 * Asks which key opens menus, by press or by choice, and records it for the
 * whole family. Keypad systems such as DumberOS keep the real left softkey
 * for their notification shade, so there D-pad Left stands in.
 */
@Composable
fun LeftKeyScreen(onChosen: () -> Unit) {
    val context = LocalContext.current
    val softkeys = LocalSoftkeys.current
    val choose: (Family.LeftKey) -> Unit = {
        Family.setLeftKey(context, it)
        softkeys.refresh(context)
        onChosen()
    }
    val first = remember { FocusRequester() }
    FocusOnStart(first)
    Softkeys(
        center = SoftkeyAction("Select"),
        // Pressing either key answers the question.
        onKey = { event ->
            val choice = when (event.keyCode) {
                KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_MENU -> Family.LeftKey.SOFTKEY
                KeyEvent.KEYCODE_DPAD_LEFT -> Family.LeftKey.DPAD
                else -> null
            }
            if (choice != null && event.action == KeyEvent.ACTION_UP) choose(choice)
            choice != null
        },
    )
    Column(Modifier.fillMaxSize()) {
        Header("Menu key")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            BasicText(
                "Press the key you'd like to open menus with.",
                style = Type.body,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
            ListButton(
                "◀ D-pad Left", { choose(Family.LeftKey.DPAD) },
                description = "For phones whose left softkey opens the notification shade, such as DumberOS.",
                focusRequester = first,
            )
            ListButton(
                "Left softkey", { choose(Family.LeftKey.SOFTKEY) },
                description = "For phones where apps get the left softkey. D-pad Left then just moves.",
            )
            Hint("This applies to every Dumb app. Change it in Settings.", Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

/** A Settings row for the family's menu key. */
@Composable
fun LeftKeySetting(focusRequester: FocusRequester? = null) {
    val context = LocalContext.current
    val softkeys = LocalSoftkeys.current
    SettingRow(
        "Menu key",
        {
            Family.setLeftKey(context, if (softkeys.dpadLeft) Family.LeftKey.SOFTKEY else Family.LeftKey.DPAD)
            softkeys.refresh(context)
        },
        value = if (softkeys.dpadLeft) "D-pad Left" else "Left softkey",
        description = "For every Dumb app",
        focusRequester = focusRequester,
    )
}

/**
 * A permission, why the app wants it, and how to ask. [granted] is checked
 * again every moment the list is showing, so answers from Android's dialogs
 * and Settings pages appear without leaving the screen.
 */
class PermissionItem(
    val title: String,
    val why: String,
    val granted: () -> Boolean,
    val request: () -> Unit,
)

@Composable
fun PermissionsScreen(items: List<PermissionItem>, onDone: () -> Unit, title: String = "Permissions") {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(700); tick++ } }
    val states = remember(tick) { items.map { it.granted() } }
    val first = remember { FocusRequester() }
    FocusOnStart(first)
    Softkeys(center = SoftkeyAction("Allow"), right = SoftkeyAction(if (states.all { it }) "Done" else "Later", onDone))
    Column(Modifier.fillMaxSize()) {
        Header(title, trailing = { Pill("${states.count { it }}/${items.size}") })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            items.forEachIndexed { i, item ->
                SettingRow(
                    item.title, { if (!states[i]) item.request() },
                    value = if (states[i]) "✓ On" else "Allow",
                    description = item.why,
                    focusRequester = if (i == 0) first else null,
                )
            }
            Hint(
                if (states.all { it }) "All set." else "You can change these later in Android's Settings.",
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * A page that Up and Down scroll, since text can't take focus. [onRight]
 * turns the page; Left is left to the softkey bar. A new [pageKey] starts
 * the page at the top.
 */
@Composable
fun TextPage(
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
fun Table(rows: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = rows)

@Composable
fun Cell(key: String, meaning: String) {
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
fun Centred(text: String, style: TextStyle) =
    BasicText(text, style = style.copy(textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) = BasicText(text, style = Type.preview, modifier = modifier)
