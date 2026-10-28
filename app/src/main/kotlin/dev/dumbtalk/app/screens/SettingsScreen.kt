package dev.dumbtalk.app.screens

import android.app.Activity
import android.widget.Toast
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.dumbtalk.app.BuildConfig
import dev.dumbtalk.app.DumbTalkApp
import dev.dumbtalk.app.Navigator
import dev.dumbtalk.app.Screen
import dev.dumbtalk.app.background.BackgroundStatus
import dev.dumbtalk.app.background.DebugLog
import dev.dumbtalk.app.ui.LeftKeySetting
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.ListButton
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.app.ui.focusAfterFrame
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: DumbTalkApp, navigator: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requester = remember { FocusRequester() }
    var focusedRow by rememberSaveable { mutableStateOf("Services") }
    val initialRow = remember { focusedRow }
    // Rechecked on return from Android's settings, where it's changed.
    var background by remember { mutableStateOf(BackgroundStatus.of(context)) }
    LifecycleResumeEffect(Unit) {
        background = BackgroundStatus.of(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { requester.focusAfterFrame() }
    Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Back") { navigator.pop() })

    /** A row that's refocused when Settings is shown again. */
    @Composable
    fun SettingRow(title: String, description: String, onClick: () -> Unit) = ListButton(
        title, onClick,
        modifier = Modifier.onFocusChanged { if (it.isFocused) focusedRow = title },
        description = description,
        focusRequester = requester.takeIf { initialRow == title }, highlightFocus = true,
    )

    Column(Modifier.fillMaxSize()) {
        Header("Settings")
        Column(
            Modifier
                .focusProperties { canFocus = false }
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SettingRow("Services", "Signal, Telegram, WhatsApp and SMS") { navigator.push(Screen.Services) }
            SettingRow("Background delivery", background.summary) { BackgroundStatus.fix(context, background) }
            if (!background.ok) BasicText("Select to fix it in Android's settings.", style = Type.small)
            SettingRow("Keys & help", "How to use DumbTalk with a keypad") { navigator.push(Screen.Help) }
            LeftKeySetting()
            SettingRow("Back up", "An encrypted copy of your chats and accounts") {
                app.restartFor(context as Activity, DumbTalkApp.Maintenance.BACKUP)
            }
            SettingRow("Restore from backup", "Replaces what's here with a backup") {
                app.restartFor(context as Activity, DumbTalkApp.Maintenance.RESTORE)
            }
            SettingRow("Share debug log", "For a bug report. It can include names and numbers, so check it first") {
                scope.launch {
                    if (!DebugLog.share(context)) Toast.makeText(context, "No app here can share files", Toast.LENGTH_LONG).show()
                }
            }
            if (app.demo && !BuildConfig.DEMO) SettingRow("Leave the demo", "Restart with your real services") { app.restartWithDemo(context as Activity, false) }
            else SettingRow("Try the demo", "Restart with made-up chats; your accounts stay linked") { app.restartWithDemo(context as Activity, true) }
            SettingRow("About", "Version, source code and licences") { navigator.push(Screen.About) }
        }
    }
}
