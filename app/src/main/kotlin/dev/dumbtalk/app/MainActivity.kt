package dev.dumbtalk.app

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dev.dumbtalk.app.background.BackgroundPermissions
import dev.dumbtalk.app.background.MessageNotifier
import dev.dumbtalk.app.background.Visibility
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private val softkeys = SoftkeyController()
    private val navigator = Navigator()
    private val permissions = BackgroundPermissions(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DumbTalkApp
        permissions.ask()
        openFromNotification(intent)
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
                            LaunchedEffect(screen, resumed) { reportVisible(screen) }
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

    private var resumed by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        Visibility.current = null
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromNotification(intent)
    }

    /** Tells the notifier what's on screen, and clears an opened chat's notification. */
    private fun reportVisible(screen: Screen) {
        if (!resumed) return
        Visibility.current = when (screen) {
            is Screen.Inbox -> "inbox"
            is Screen.Chat -> screen.conversationId.also { (application as DumbTalkApp).notifier.clear(it) }
            else -> "other"
        }
    }

    /** Opens the chat a notification points at, once the inbox knows about it. */
    private fun openFromNotification(intent: Intent?) {
        val id = intent?.getStringExtra(MessageNotifier.EXTRA_CONVERSATION)?.takeIf { it.isNotEmpty() } ?: return
        intent.removeExtra(MessageNotifier.EXTRA_CONVERSATION)
        val app = application as DumbTalkApp
        lifecycleScope.launch {
            val conversation = withTimeoutOrNull(15_000) {
                app.inbox.active.first { page -> page != null && app.inbox.find(id) != null }
                app.inbox.find(id)
            } ?: app.inbox.find(id) ?: return@launch
            navigator.popToRoot()
            navigator.openChat(conversation)
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
