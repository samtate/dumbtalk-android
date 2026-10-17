package dev.dumbtalk.app.screens

import android.util.Log
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import dev.dumbtalk.core.ConversationKind
import dev.dumbtalk.core.Capabilities
import dev.dumbtalk.app.ChatTool
import kotlinx.coroutines.launch

class MenuItem(val label: String, val icon: AppIcon, val onClick: () -> Unit)

/** Two-column tile grid; the first tile takes focus. */
@Composable
fun MenuGrid(items: List<MenuItem>, requester: FocusRequester? = null, focusedLabel: String? = null, onFocused: (String) -> Unit = {}) {
    // Keep the restored target stable while the panel mounts: transient focus
    // on another tile must not move the requester before its initial request.
    val initialLabel = remember { focusedLabel?.takeIf { label -> items.any { it.label == label } } ?: items.firstOrNull()?.label }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEachIndexed { column, item ->
                    MenuTile(
                        item.label, item.icon, item.onClick, Modifier.weight(1f).onFocusChanged { if (it.isFocused) onFocused(item.label) },
                        focusRequester = if (item.label == initialLabel) requester else null,
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun MenuScreen(app: DumbTalkApp, navigator: Navigator, screen: Screen.Menu) {
    val requester = remember { FocusRequester() }
    var focusedLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteThread by remember { mutableStateOf(false) }
    var showPollCreator by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showPollCreator, showDeleteThread) {
        if (!showPollCreator && !showDeleteThread) { withFrameNanos { }; runCatching { requester.requestFocus() } }
    }
    val selected = screen.selected
    val service = selected?.let { app.registry.forConversation(it) }
    val capabilities by produceState(Capabilities(), service) {
        value = service?.let { runCatching { it.capabilities() }.getOrDefault(Capabilities()) } ?: Capabilities()
    }
    if (showDeleteThread && selected != null && service != null) {
        DeleteConfirmation("Delete SMS thread?", "Delete all SMS messages with ${selected.title} from this phone? This cannot be undone.",
            onDelete = { service.deleteConversation(selected) }, onDeleted = { navigator.popToRoot() }, onCancel = { showDeleteThread = false })
        return
    }
    if (showPollCreator && selected != null && service != null) {
        PollCreator(
            onCreate = { question, options, multiple ->
                app.scope.launch {
                    runCatching { service.createPoll(selected, question, options, multiple) }
                        .onFailure { Log.w("Menu", "create poll failed", it) }
                }
                navigator.pop()
            },
            onClose = { showPollCreator = false },
        )
        return
    }
    Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Back") { navigator.pop() })
    val canGroup by produceState(false) {
        value = app.registry.connected().any { runCatching { it.capabilities().groups }.getOrDefault(false) }
    }

    val chatItems = if (selected != null && service != null) chatOptionItems(
        selected,
        polls = capabilities.polls && (capabilities.directPolls || selected.kind == ConversationKind.GROUP || selected.isNoteToSelf),
        onUpdate = { update ->
            app.scope.launch {
                runCatching { service.updateConversation(selected, update) }
                    .onFailure { Log.w("Menu", "update conversation failed", it) }
            }
            navigator.pop()
        },
        onCreatePoll = { showPollCreator = true },
        search = capabilities.search, pins = capabilities.pins,
        onSearch = { navigator.push(Screen.ChatTools(selected, ChatTool.SEARCH)) },
        onPins = { navigator.push(Screen.ChatTools(selected, ChatTool.PINS)) },
        deleteThread = capabilities.deleteConversations, onDeleteThread = { showDeleteThread = true },
    ) else emptyList()
    val restoreChatAction = chatItems.any { it.label == focusedLabel }
    Column(Modifier.fillMaxSize()) {
        Header("Menu")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
            BasicText("APP", style = Type.section, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
            MenuGrid(
                buildList {
                    add(MenuItem("New message", AppIcon.COMPOSE) { navigator.replace(Screen.NewMessage) })
                    add(MenuItem("Search", AppIcon.SEARCH) { navigator.replace(Screen.Search) })
                    add(
                        if (screen.archived) MenuItem("Inbox", AppIcon.ARCHIVE) { navigator.pop(); navigator.pop() }
                        else MenuItem("Archived", AppIcon.ARCHIVE) { navigator.replace(Screen.Inbox(archived = true)) },
                    )
                    add(MenuItem("Settings", AppIcon.SETTINGS) { navigator.push(Screen.Settings) })
                    // Last, so the tiles above keep their places for muscle memory.
                    if (canGroup) add(MenuItem("New group", AppIcon.GROUP) { navigator.replace(Screen.NewGroup) })
                },
                requester.takeUnless { restoreChatAction }, focusedLabel, { focusedLabel = it },
            )
            if (selected != null) {
                BasicText(
                    "CHAT · ${selected.title}",
                    style = Type.section,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp, top = 10.dp, bottom = 6.dp),
                )
                MenuGrid(
                    chatItems,
                    requester.takeIf { restoreChatAction }, focusedLabel, { focusedLabel = it },
                )
                if (selected.kind == ConversationKind.GROUP && selected.members.isNotEmpty()) {
                    BasicText("MEMBERS", style = Type.section, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    selected.members.forEach { BasicText(it.name, style = Type.body, modifier = Modifier.padding(vertical = 2.dp)) }
                }
            }
        }
    }
}
