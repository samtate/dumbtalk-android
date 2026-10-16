package dev.dumbtalk.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dumbtalk.app.DumbTalkApp
import dev.dumbtalk.app.Navigator
import dev.dumbtalk.app.Screen
import dev.dumbtalk.app.ui.AppIcon
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.MenuTile
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.core.ConversationUpdate
import kotlinx.coroutines.launch

class MenuItem(val label: String, val icon: AppIcon, val onClick: () -> Unit)

/** Two-column tile grid; the first tile takes focus. */
@Composable
fun MenuGrid(items: List<MenuItem>, requester: FocusRequester? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEachIndexed { column, item ->
                    MenuTile(
                        item.label, item.icon, item.onClick, Modifier.weight(1f),
                        focusRequester = if (row == 0 && column == 0) requester else null,
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun MenuScreen(app: DumbTalkApp, navigator: Navigator, screen: Screen.Menu) {
    val scope = rememberCoroutineScope()
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Back") { navigator.pop() })

    val selected = screen.selected
    fun change(update: ConversationUpdate) {
        if (selected == null) return
        scope.launch {
            runCatching { app.registry.forConversation(selected).updateConversation(selected, update) }
            navigator.pop()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header("Menu")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
            MenuGrid(
                buildList {
                    add(MenuItem("New message", AppIcon.COMPOSE) { navigator.replace(Screen.NewMessage) })
                    add(MenuItem("Search", AppIcon.SEARCH) { navigator.replace(Screen.Search) })
                    add(
                        if (screen.archived) MenuItem("Inbox", AppIcon.ARCHIVE) { navigator.pop(); navigator.pop() }
                        else MenuItem("Archived", AppIcon.ARCHIVE) { navigator.replace(Screen.Inbox(archived = true)) },
                    )
                    add(MenuItem("Services", AppIcon.SERVICES) { navigator.replace(Screen.Services) })
                },
                requester,
            )
            if (selected != null) {
                BasicText(
                    selected.title,
                    style = Type.section,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp, top = 10.dp, bottom = 6.dp),
                )
                MenuGrid(
                    listOf(
                        MenuItem(if (selected.isFavourite) "Unstar" else "Star", AppIcon.STAR) {
                            change(ConversationUpdate(favourite = !selected.isFavourite))
                        },
                        MenuItem(if (selected.isMuted) "Unmute" else "Mute", AppIcon.MUTE) {
                            change(ConversationUpdate(muted = !selected.isMuted))
                        },
                        MenuItem(if (selected.isArchived) "Unarchive" else "Archive", AppIcon.ARCHIVE) {
                            change(ConversationUpdate(archived = !selected.isArchived))
                        },
                    ),
                )
            }
        }
    }
}
