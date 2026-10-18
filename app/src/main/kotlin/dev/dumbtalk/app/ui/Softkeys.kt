package dev.dumbtalk.app.ui

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A softkey label and its action. A centre action with no [onPress] is only
 * a label: the centre key then activates whatever is focused.
 */
class SoftkeyAction(
    val label: String,
    val onPress: (() -> Unit)?,
    val focusRequester: FocusRequester?,
    val focusable: Boolean,
    val upFocusRequester: FocusRequester?,
) {
    var onFocused: (() -> Unit)? = null

    constructor(label: String) : this(label, null, null, false, null)
    constructor(label: String, onPress: () -> Unit) : this(label, onPress, null, false, null)
}

data class SoftkeyConfig(
    val left: SoftkeyAction? = null,
    val center: SoftkeyAction? = null,
    val right: SoftkeyAction? = null,
)

/**
 * The visible screen's softkeys sit on top of a stack, as in the web UI, so
 * an overlay can take the keys over and hand them back when it closes.
 */
class SoftkeyController {
    private class Entry(initial: SoftkeyConfig) {
        var config by mutableStateOf(initial)
    }

    private val stack = mutableStateListOf<Entry>()

    /** True while a text field has focus, so text keys go to the keyboard. */
    var typing by mutableStateOf(false)

    /** The focused control, if it wants the centre key itself (e.g. Send). */
    private var centerClaim: Any? = null
    private val handledPresses = mutableSetOf<Int>()

    fun claimCenter(owner: Any, claim: Boolean) {
        if (claim) centerClaim = owner else if (centerClaim === owner) centerClaim = null
    }

    val current: SoftkeyConfig get() = stack.lastOrNull()?.config ?: SoftkeyConfig()

    @Composable
    internal fun Register(config: SoftkeyConfig) {
        val entry = remember { Entry(config) }
        SideEffect { entry.config = config }
        DisposableEffect(entry) {
            stack += entry
            onDispose { stack -= entry }
        }
    }

    enum class Result { HANDLED, EXIT, IGNORED }

    /**
     * Maps the left softkey (and Menu) to left; right softkey or Back →
     * right; centre when the screen gives it an action, unless the focused
     * control claims it. D-pad Left goes to the screen first: see
     * [onUnhandled].
     *
     * Everything acts on key-down and both halves of the press are consumed.
     * Acting on key-up would let one press do two things: Back closes a chat
     * on down, and the inbox's Exit would then fire on the same press's up.
     */
    fun onKey(event: KeyEvent): Result {
        val code = event.keyCode
        // The down action may replace the screen or its binding. Keep the up
        // event with that action instead of activating a newly focused item.
        if (code in handledPresses) {
            if (event.action == KeyEvent.ACTION_UP) handledPresses.remove(code)
            return Result.HANDLED
        }
        val back = code == KeyEvent.KEYCODE_SOFT_RIGHT || code == KeyEvent.KEYCODE_BACK
        val center = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (center && centerClaim != null) return Result.IGNORED
        val config = current
        val action = when {
            code == KeyEvent.KEYCODE_SOFT_LEFT || code == KeyEvent.KEYCODE_MENU -> config.left
            back -> config.right ?: if (code == KeyEvent.KEYCODE_BACK) SoftkeyAction(EXIT) else null
            center && config.center?.onPress != null -> config.center
            else -> return Result.IGNORED
        } ?: return Result.IGNORED
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return Result.HANDLED
        handledPresses.add(code)
        if (action.label == EXIT) return Result.EXIT
        action.onPress?.invoke()
        return Result.HANDLED
    }

    companion object {
        const val EXIT = "Exit"


    }

    /**
     * D-pad Left, once the screen has passed on it. DumberOS takes the real
     * left softkey for its notification shade, so Left stands in for it
     * wherever nothing uses Left itself: lists and message bubbles do, while
     * text fields, side-by-side buttons and the photo viewer keep it.
     */
    fun onUnhandled(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_DPAD_LEFT || typing) return false
        val action = current.left?.onPress ?: return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        handledPresses.add(event.keyCode)
        action()
        return true
    }
}

val LocalSoftkeys = staticCompositionLocalOf<SoftkeyController> { error("No SoftkeyController") }

/**
 * Makes D-pad Left press the left softkey on this control, for controls
 * where Compose would otherwise move focus sideways: a message bubble (to
 * the bubble beside it) or the composer's first button.
 */
@Composable
fun Modifier.leftIsSoftkey(): Modifier {
    val softkeys = LocalSoftkeys.current
    return onPreviewKeyEvent { event ->
        if (event.key != Key.DirectionLeft) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) softkeys.current.left?.onPress?.invoke()
        true
    }
}

@Composable
fun Softkeys(left: SoftkeyAction? = null, center: SoftkeyAction? = null, right: SoftkeyAction? = null) {
    LocalSoftkeys.current.Register(SoftkeyConfig(left, center, right))
}

@Composable
fun SoftkeyBar(controller: SoftkeyController, modifier: Modifier = Modifier) {
    val config = controller.current
    // A single rule above the labels, no box.
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
    Row(
        modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(Palette.softkeyBar)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ◀: D-pad Left presses it (except while typing, when Left moves the cursor).
        val leftLabel = config.left?.let { if (controller.typing) it.label else "◀ " + it.label }
        SoftkeyLabel(leftLabel, TextAlign.Start, config.left, Modifier.weight(1f), controller)
        SoftkeyLabel(config.center?.label, TextAlign.Center, config.center, Modifier.weight(1f), controller)
        SoftkeyLabel(config.right?.label, TextAlign.End, config.right, Modifier.weight(1f), controller)
    }
}

@Composable
private fun SoftkeyLabel(label: String?, align: TextAlign, action: SoftkeyAction?, modifier: Modifier, controller: SoftkeyController) {
    val owner = remember { Any() }
    val focusable = action?.focusable == true
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(owner, focusable) { onDispose { controller.claimCenter(owner, false); focused = false } }
    val tap = if (action?.onPress != null) {
        Modifier.pointerInput(action.onPress) { detectTapGestures { action.onPress?.invoke() } }
    } else Modifier
    val focus = if (focusable) {
        Modifier
            .then(action?.focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .then(action?.upFocusRequester?.let { Modifier.focusProperties { up = it } } ?: Modifier)
            .onFocusChanged {
                focused = it.isFocused
                controller.claimCenter(owner, it.isFocused)
                if (it.isFocused) action?.onFocused?.invoke()
            }
            .onKeyEvent { event ->
                val isCenter = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (!isCenter) return@onKeyEvent false
                if (event.type == KeyEventType.KeyUp) action?.onPress?.invoke()
                true
            }
            .focusable()
    } else Modifier.focusProperties { canFocus = false }
    BasicText(
        text = label?.uppercase().orEmpty(),
        style = Type.softkey.copy(textAlign = align, color = if (focused) Palette.background else Palette.text),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .height(26.dp)
            .then(focus)
            .then(if (focused) Modifier.background(Palette.focus, Square).border(1.dp, Palette.focus, Square) else Modifier)
            .padding(horizontal = 2.dp)
            .then(tap)
            .semantics { action?.onPress?.let { press -> onClick { press(); true } } }
            .wrapContentHeight(Alignment.CenterVertically),
    )
}
