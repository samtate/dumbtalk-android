package dev.dumbtalk.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import dev.dumbtalk.app.screens.ChatScreen
import dev.dumbtalk.app.screens.InboxScreen
import dev.dumbtalk.app.screens.MenuScreen
import dev.dumbtalk.app.screens.NewMessageScreen
import dev.dumbtalk.app.screens.SearchScreen
import dev.dumbtalk.app.screens.ServicesScreen
import dev.dumbtalk.app.screens.SetupScreen
import dev.dumbtalk.app.ui.DumbTalkTheme
import dev.dumbtalk.app.ui.LocalSoftkeys
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.SoftkeyBar
import dev.dumbtalk.app.ui.SoftkeyController

class MainActivity : ComponentActivity() {
    private val softkeys = SoftkeyController()
    private val navigator = Navigator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DumbTalkApp
        setContent {
            DumbTalkTheme {
                CompositionLocalProvider(LocalSoftkeys provides softkeys) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(Palette.background)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .imePadding(),
                    ) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            val saveable = rememberSaveableStateHolder()
                            val screen = navigator.current
                            saveable.SaveableStateProvider(screen.toString()) {
                                when (screen) {
                                    is Screen.Inbox -> InboxScreen(app, navigator, screen.archived)
                                    is Screen.Menu -> MenuScreen(app, navigator, screen)
                                    is Screen.Chat -> ChatScreen(app, navigator, screen)
                                    Screen.Services -> ServicesScreen(app, navigator)
                                    is Screen.Setup -> SetupScreen(app, navigator, screen.serviceId)
                                    Screen.Search -> SearchScreen(app, navigator)
                                    Screen.NewMessage -> NewMessageScreen(app, navigator)
                                }
                            }
                        }
                        SoftkeyBar(softkeys)
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        when (softkeys.onKey(event)) {
            SoftkeyController.Result.HANDLED -> true
            SoftkeyController.Result.EXIT -> {
                finish()
                true
            }
            SoftkeyController.Result.IGNORED -> super.dispatchKeyEvent(event)
        }
}
