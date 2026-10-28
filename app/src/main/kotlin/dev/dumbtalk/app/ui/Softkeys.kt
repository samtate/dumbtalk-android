// Shared Dumb UI kit: generated from dumb-kit/ui/Softkeys.kt by sync.py. Edit it there.
package dev.dumbtalk.app.ui

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * A softkey label, its action, and optionally what holding the key does. A
 * centre action with no [onPress] is only a label: the centre key then
 * activates whatever is focused. A [focusable] softkey can itself take D-pad
 * focus (the composer's Send).
 */
class SoftkeyAction(
    val label: String,
    val onPress: (() -> Unit)? = null,
    val focusRequester: FocusRequester? = null,
    val focusable: Boolean = false,
    val upFocusRequester: FocusRequester? = null,
    val onHold: (() -> Unit)? = null,
) {
    var onFocused: (() -> Unit)? = null

    /** `SoftkeyAction("Back") { … }` binds the press. */
    constructor(label: String, onPress: () -> Unit) : this(label, onPress, null)
}

/**
 * What the visible screen does with the keys: three softkeys, the keypad
 * ([onDigit] on press, [onDigitHold] once the key is held; return false to
 * leave a held key alone) and the green Call key. [onKey] sees every key
 * first and returns true for those it takes (a viewfinder's volume keys).
 * [leftOnDpad] false keeps D-pad Left for the screen even when nothing on it
 * uses Left (a photo viewer at its first photo). [typing] marks a screen that
 * is all text entry, so digits and Left go to the field.
 */
data class SoftkeyConfig(
    val left: SoftkeyAction? = null,
    val center: SoftkeyAction? = null,
    val right: SoftkeyAction? = null,
    val onDigit: ((Char) -> Unit)? = null,
    val onDigitHold: ((Char) -> Boolean)? = null,
    val onCall: (() -> Unit)? = null,
    val onKey: ((KeyEvent) -> Boolean)? = null,
    val leftOnDpad: Boolean = true,
    val typing: Boolean = false,
)

/**
 * The visible screen's keys sit on top of a stack, so an overlay can take
 * them over and hand them back when it closes.
 */
class SoftkeyController {
    private class Entry(initial: SoftkeyConfig) {
        var config by mutableStateOf(initial)
    }

    private val stack = mutableStateListOf<Entry>()

    /** True while a text field has focus, so digits and Left go to the keyboard. */
    var typing by mutableStateOf(false)

    /**
     * Whether D-pad Left stands in for the left softkey. Keypad phones whose
     * system takes the real left softkey (DumberOS opens its notification
     * shade with it) need this; others can keep Left for moving. Chosen once
     * for the whole family: see [Family].
     */
    var dpadLeft by mutableStateOf(true)

    /** Takes up the family's left-key choice; call from the activity's onResume. */
    fun refresh(context: Context) {
        dpadLeft = Family.leftKey(context) != Family.LeftKey.SOFTKEY
    }

    /** The focused control, if it wants the centre key itself (e.g. Send). */
    private var centerClaim: Any? = null

    /** Keys whose press already acted, with what holding them does. */
    private val pressed = mutableMapOf<Int, (() -> Unit)?>()

    fun claimCenter(owner: Any, claim: Boolean) {
        if (claim) centerClaim = owner else if (centerClaim === owner) centerClaim = null
    }

    val current: SoftkeyConfig get() = stack.lastOrNull()?.config ?: SoftkeyConfig()

    private val isTyping get() = typing || current.typing

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
     * Maps the left softkey (and Menu) to left, the right softkey or Back to
     * right, centre to the screen's centre action unless the focused control
     * claims it, and the keypad and Call key to the screen's handlers. D-pad
     * Left goes to the screen first: see [onUnhandled].
     *
     * Everything acts on key-down and both halves of the press are consumed,
     * so one press can't act on two screens (Back closes a screen on down;
     * the next screen must not see the up). A held key fires its hold action
     * once, on the long-press repeat.
     */
    fun onKey(event: KeyEvent): Result {
        val code = event.keyCode
        if (code in pressed) {
            if (event.action == KeyEvent.ACTION_UP) pressed.remove(code)
            else if (event.isLongPress || event.repeatCount == 1) pressed.put(code, null)?.invoke()
            return Result.HANDLED
        }
        val config = current
        if (config.onKey?.invoke(event) == true) return Result.HANDLED
        val digit = digitFor(code)
        if (digit != null && isTyping) return Result.IGNORED
        if (digit != null && config.onDigit != null) {
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return Result.HANDLED
            config.onDigit.invoke(digit)
            pressed[code] = config.onDigitHold?.let { hold -> { hold(digit); Unit } }
            return Result.HANDLED
        }
        if (code == KeyEvent.KEYCODE_CALL) {
            val call = config.onCall ?: return Result.IGNORED
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return Result.HANDLED
            pressed[code] = null
            call()
            return Result.HANDLED
        }
        val back = code == KeyEvent.KEYCODE_SOFT_RIGHT || code == KeyEvent.KEYCODE_BACK
        val center = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (center && centerClaim != null) return Result.IGNORED
        val action = when {
            code == KeyEvent.KEYCODE_SOFT_LEFT || code == KeyEvent.KEYCODE_MENU -> config.left
            back -> config.right ?: if (code == KeyEvent.KEYCODE_BACK) SoftkeyAction(EXIT) else null
            center && config.center?.onPress != null -> config.center
            else -> return Result.IGNORED
        } ?: return Result.IGNORED
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return Result.HANDLED
        pressed[code] = action.onHold
        if (action.label == EXIT) return Result.EXIT
        action.onPress?.invoke()
        return Result.HANDLED
    }

    /**
     * D-pad Left, once the screen has passed on it: it presses the left
     * softkey wherever nothing uses Left itself. Lists and menus don't; text
     * fields, side-by-side buttons and grids do. Off when [dpadLeft] is.
     */
    fun onUnhandled(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_DPAD_LEFT || !leftOnDpad) return false
        val action = current.left?.onPress ?: return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        pressed[event.keyCode] = null
        action()
        return true
    }

    /** Whether D-pad Left presses the left softkey on the visible screen. */
    val leftOnDpad: Boolean get() = dpadLeft && current.leftOnDpad && !isTyping

    /**
     * The activity's whole key path: [onKey], then the screen, then
     * [onUnhandled]. [exit] runs for Back on a screen with nothing to go back to.
     */
    fun dispatch(event: KeyEvent, screen: (KeyEvent) -> Boolean, exit: () -> Unit): Boolean =
        when (onKey(event)) {
            Result.HANDLED -> true
            Result.EXIT -> { exit(); true }
            Result.IGNORED -> screen(event) || onUnhandled(event)
        }

    companion object {
        const val EXIT = "Exit"

        fun digitFor(code: Int): Char? = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (code - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> '0' + (code - KeyEvent.KEYCODE_NUMPAD_0)
            KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> '*'
            KeyEvent.KEYCODE_POUND -> '#'
            else -> null
        }
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
        if (event.key != Key.DirectionLeft || !softkeys.leftOnDpad) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) softkeys.current.left?.onPress?.invoke()
        true
    }
}

@Composable
fun Softkeys(
    left: SoftkeyAction? = null,
    center: SoftkeyAction? = null,
    right: SoftkeyAction? = null,
    onDigit: ((Char) -> Unit)? = null,
    onDigitHold: ((Char) -> Boolean)? = null,
    onCall: (() -> Unit)? = null,
    onKey: ((KeyEvent) -> Boolean)? = null,
    leftOnDpad: Boolean = true,
    typing: Boolean = false,
) {
    LocalSoftkeys.current.Register(SoftkeyConfig(left, center, right, onDigit, onDigitHold, onCall, onKey, leftOnDpad, typing))
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
        // ◀: D-pad Left presses it.
        val leftLabel = config.left?.let { if (controller.leftOnDpad) "◀ " + it.label else it.label }
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
        Modifier.pointerInput(action.onPress) { detectTapGestures { action.onPress.invoke() } }
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
