package dev.dumbtalk.app.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A softkey label and its action. A centre action with no [onPress] is only
 * a label: the centre key then activates whatever is focused.
 */
class SoftkeyAction(val label: String, val onPress: (() -> Unit)? = null)

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

    /** True while a text field has focus, so number keys type instead. */
    var typing by mutableStateOf(false)

    /** The focused control, if it wants the centre key itself (e.g. Send). */
    private var centerClaim: Any? = null

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
     * Maps hardware keys to softkeys: left softkey, Menu, or 1 when not
     * typing → left; right softkey or Back → right; centre when the screen
     * gives it an action, unless the focused control claims it.
     *
     * Everything acts on key-down and both halves of the press are consumed.
     * Acting on key-up would let one press do two things: Back closes a chat
     * on down, and the inbox's Exit would then fire on the same press's up.
     */
    fun onKey(event: KeyEvent): Result {
        val code = event.keyCode
        val center = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (center && centerClaim != null) return Result.IGNORED
        val config = current
        val back = code == KeyEvent.KEYCODE_SOFT_RIGHT || code == KeyEvent.KEYCODE_BACK
        val action = when {
            code == KeyEvent.KEYCODE_SOFT_LEFT || code == KeyEvent.KEYCODE_MENU -> config.left
            code == KeyEvent.KEYCODE_1 && !typing -> config.left
            back -> config.right ?: if (code == KeyEvent.KEYCODE_BACK) SoftkeyAction(EXIT) else null
            center && config.center?.onPress != null -> config.center
            else -> return Result.IGNORED
        } ?: return if (code == KeyEvent.KEYCODE_1) Result.IGNORED else Result.HANDLED
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return Result.HANDLED
        if (action.label == EXIT) return Result.EXIT
        action.onPress?.invoke()
        return Result.HANDLED
    }

    companion object {
        const val EXIT = "Exit"
    }
}

val LocalSoftkeys = staticCompositionLocalOf<SoftkeyController> { error("No SoftkeyController") }

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
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        val leftLabel = config.left?.let { if (controller.typing) it.label else "1=${it.label}" }
        SoftkeyLabel(leftLabel, TextAlign.Start, config.left?.onPress, Modifier.weight(1f))
        SoftkeyLabel(config.center?.label, TextAlign.Center, config.center?.onPress, Modifier.weight(1f))
        SoftkeyLabel(config.right?.label, TextAlign.End, config.right?.onPress, Modifier.weight(1f))
    }
}

@Composable
private fun SoftkeyLabel(label: String?, align: TextAlign, onPress: (() -> Unit)?, modifier: Modifier) {
    // Tappable for touch-screen keypad phones, but never focusable: the D-pad
    // must not wander into the bar.
    val tap = if (onPress != null) Modifier.focusProperties { canFocus = false }.clickable(onClick = onPress) else Modifier
    BasicText(
        text = label?.uppercase().orEmpty(),
        style = Type.softkey.copy(textAlign = align),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.then(tap),
    )
}
