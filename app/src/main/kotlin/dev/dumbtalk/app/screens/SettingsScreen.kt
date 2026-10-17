package dev.dumbtalk.app.screens

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import dev.dumbtalk.app.Screen
import dev.dumbtalk.app.Navigator
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.ListButton
import dev.dumbtalk.app.background.BackgroundStatus
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.SoftkeyController
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.Type

private enum class KeySetup { SETTINGS, CHOOSE, CAPTURE, CONFIRM }

@Composable
fun SettingsScreen(navigator: Navigator, controller: SoftkeyController) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(KeySetup.SETTINGS) }
    var candidate by remember { mutableStateOf(KeyEvent.KEYCODE_UNKNOWN) }
    var saved by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    var overviewFocus by rememberSaveable { mutableStateOf("Services") }
    val initialOverviewFocus = remember(page) { overviewFocus }
    val chosen = controller.leftShortcutCode
    // Rechecked on return from Android's settings, where it's changed.
    var background by remember { mutableStateOf(BackgroundStatus.of(context)) }
    LifecycleResumeEffect(Unit) {
        background = BackgroundStatus.of(context)
        onPauseOrDispose { }
    }
    fun preview(code: Int) {
        candidate = code
        controller.cancelLeftShortcutCapture()
        controller.testShortcutCode = code.takeUnless { it == KeyEvent.KEYCODE_UNKNOWN }
        controller.shortcutTestPassed = false
        page = KeySetup.CONFIRM
    }
    fun cancel() {
        controller.cancelLeftShortcutCapture()
        controller.testShortcutCode = null
        page = when (page) {
            KeySetup.CAPTURE, KeySetup.CONFIRM -> KeySetup.CHOOSE
            KeySetup.CHOOSE -> KeySetup.SETTINGS
            KeySetup.SETTINGS -> { navigator.pop(); KeySetup.SETTINGS }
        }
    }
    fun save() {
        controller.leftShortcutCode = candidate
        context.getSharedPreferences("app_ui", Context.MODE_PRIVATE).edit()
            .putInt("left_shortcut", candidate).apply()
        controller.testShortcutCode = null
        saved = true
        page = KeySetup.SETTINGS
    }
    LaunchedEffect(page) {
        if (page == KeySetup.SETTINGS || page == KeySetup.CHOOSE) {
            withFrameNanos { }
            requester.requestFocus()
        }
    }
    LaunchedEffect(controller.capturedKeyCode, page) {
        if (page == KeySetup.CAPTURE) controller.capturedKeyCode?.let(::preview)
    }
    DisposableEffect(Unit) {
        onDispose {
            controller.cancelLeftShortcutCapture()
            controller.testShortcutCode = null
        }
    }
    Softkeys(
        center = when (page) {
            KeySetup.CONFIRM -> if (controller.shortcutTestPassed) SoftkeyAction("Save", ::save) else null
            KeySetup.CAPTURE -> null
            else -> SoftkeyAction("Select")
        },
        right = SoftkeyAction(if (page == KeySetup.CAPTURE || page == KeySetup.CONFIRM) "Cancel" else "Back", ::cancel),
    )
    Column(Modifier.fillMaxSize()) {
        Header(if (page == KeySetup.SETTINGS) "Settings" else "Left action key")
        Column(
            Modifier
                .focusProperties { canFocus = false }
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (page) {
                KeySetup.SETTINGS -> {
                    ListButton("Services", { navigator.push(Screen.Services) },
                        modifier = Modifier.onFocusChanged { if (it.isFocused) overviewFocus = "Services" },
                        description = "Signal, Telegram, WhatsApp and SMS",
                        focusRequester = requester.takeIf { initialOverviewFocus == "Services" }, highlightFocus = true)
                    ListButton("Left action key", { saved = false; page = KeySetup.CHOOSE },
                        modifier = Modifier.onFocusChanged { if (it.isFocused) overviewFocus = "Left action key" },
                        description = SoftkeyController.keyLabel(chosen), focusRequester = requester.takeIf { initialOverviewFocus == "Left action key" }, highlightFocus = true)
                    if (saved) BasicText("Key setting saved.", style = Type.small)
                    BasicText("Opens the menu shown at the bottom left of the screen.", style = Type.small)
                    ListButton("Background delivery", { BackgroundStatus.fix(context, background) },
                        modifier = Modifier.onFocusChanged { if (it.isFocused) overviewFocus = "Background delivery" },
                        description = background.summary,
                        focusRequester = requester.takeIf { initialOverviewFocus == "Background delivery" }, highlightFocus = true)
                    if (!background.ok) BasicText("Select to fix it in Android's settings.", style = Type.small)
                }
                KeySetup.CHOOSE -> {
                    BasicText("Current: ${SoftkeyController.keyLabel(chosen)}", style = Type.body)
                    BasicText("Choose a key, then test it before saving.", style = Type.small)
                    ListButton("Press a key…", {
                        controller.beginLeftShortcutCapture()
                        page = KeySetup.CAPTURE
                    }, focusRequester = requester, highlightFocus = true)
                    ListButton("1 · default", { preview(KeyEvent.KEYCODE_1) }, highlightFocus = true)
                    ListButton("Left softkey", { preview(KeyEvent.KEYCODE_SOFT_LEFT) }, highlightFocus = true)
                    ListButton("Menu key", { preview(KeyEvent.KEYCODE_MENU) }, highlightFocus = true)
                    BasicText("While typing, text keys stay with T9. In chat, Down from the composer selects Chat in the bottom bar.", style = Type.small)
                }
                KeySetup.CAPTURE -> {
                    BasicText("Press your preferred key.", style = Type.body)
                    BasicText("Back cancels. Direction and center keys stay reserved for navigation.", style = Type.small)
                    controller.captureError?.let { BasicText(it, style = Type.small.copy(color = Palette.error)) }
                    BasicText("If nothing happens, your phone may be using that key itself. Cancel and try another.", style = Type.small)
                }
                KeySetup.CONFIRM -> {
                    BasicText("Use ${SoftkeyController.keyLabel(candidate)}?", style = Type.header)
                    if (candidate != KeyEvent.KEYCODE_UNKNOWN) {
                        BasicText(
                            if (controller.shortcutTestPassed) "Key received. Choose Save to use it."
                            else "Press ${SoftkeyController.keyLabel(candidate)} again to test. Choose Save when ready.",
                            style = Type.body,
                        )
                        if (SoftkeyController.isTextKey(candidate)) {
                            BasicText("This key opens menus outside typing. It will still type normally in a text field.", style = Type.small)
                        }
                        BasicText("If the phone intercepts this key, Cancel and choose another.", style = Type.small)
                    } else {
                        BasicText("Use the visible menu label by touch. In chat, Down from the composer also selects it.", style = Type.body)
                    }
                    BasicText("Current setting stays in use until you save.", style = Type.small)
                }
            }
        }
    }
}
