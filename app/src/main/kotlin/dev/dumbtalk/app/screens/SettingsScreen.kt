package dev.dumbtalk.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.dumbtalk.app.Navigator
import dev.dumbtalk.app.Screen
import dev.dumbtalk.app.background.BackgroundStatus
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.ListButton
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.Type

@Composable
fun SettingsScreen(navigator: Navigator) {
    val context = LocalContext.current
    val requester = remember { FocusRequester() }
    var focusedRow by rememberSaveable { mutableStateOf("Services") }
    val initialRow = remember { focusedRow }
    // Rechecked on return from Android's settings, where it's changed.
    var background by remember { mutableStateOf(BackgroundStatus.of(context)) }
    LifecycleResumeEffect(Unit) {
        background = BackgroundStatus.of(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Back") { navigator.pop() })
    Column(Modifier.fillMaxSize()) {
        Header("Settings")
        Column(
            Modifier
                .focusProperties { canFocus = false }
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ListButton("Services", { navigator.push(Screen.Services) },
                modifier = Modifier.onFocusChanged { if (it.isFocused) focusedRow = "Services" },
                description = "Signal, Telegram, WhatsApp and SMS",
                focusRequester = requester.takeIf { initialRow == "Services" }, highlightFocus = true)
            ListButton("Background delivery", { BackgroundStatus.fix(context, background) },
                modifier = Modifier.onFocusChanged { if (it.isFocused) focusedRow = "Background delivery" },
                description = background.summary,
                focusRequester = requester.takeIf { initialRow == "Background delivery" }, highlightFocus = true)
            if (!background.ok) BasicText("Select to fix it in Android's settings.", style = Type.small)
        }
    }
}
