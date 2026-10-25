package dev.dumbtalk.app.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import dev.dumbtalk.app.DumbTalkApp
import dev.dumbtalk.app.Navigator
import dev.dumbtalk.app.Screen
import dev.dumbtalk.app.ChatTool
import dev.dumbtalk.app.ui.*
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.text.DateFormat
import java.util.Date

@Composable
fun ChatToolsScreen(app: DumbTalkApp, navigator: Navigator, screen: Screen.ChatTools) {
    var query by rememberSaveable { mutableStateOf("") }
    var focused by rememberSaveable { mutableStateOf<String?>(null) }
    ChatToolsPanel(app, screen.conversation, screen.tool, query,
        onQueryChange = { query = it; focused = null }, selectedResult = focused,
        onResultFocus = { focused = it },
        onPick = { focused = it.id; navigator.openChat(screen.conversation, target = it) },
        onClose = { navigator.pop() })
}

/** Shared by chat options and the selected chat's actions in the inbox menu. */
@Composable
internal fun ChatToolsPanel(
    app: DumbTalkApp, conversation: Conversation, tool: ChatTool,
    query: String, onQueryChange: (String) -> Unit, selectedResult: String?,
    onResultFocus: (String) -> Unit, onPick: (SearchResult) -> Unit, onClose: () -> Unit,
) {
    val service = app.registry.forConversation(conversation)
    val input = remember { FocusRequester() }
    val resultFocus = remember { FocusRequester() }
    val list = rememberLazyListState()
    val restoreId = remember(query, tool) { selectedResult }
    var retry by remember { mutableIntStateOf(0) }
    val state by produceState<Pair<List<SearchResult>?, String?>>(null to null, query, tool, retry) {
        if (tool == ChatTool.SEARCH && query.trim().length < 2) { value = emptyList<SearchResult>() to null; return@produceState }
        value = null to null
        if (tool == ChatTool.SEARCH) delay(350)
        try {
            val found = if (tool == ChatTool.SEARCH) service.searchMessages(query.trim(), conversation)
            else service.listPinnedMessages(conversation).map {
                SearchResult(it.id, conversation.id, it.sender ?: "You", messageSummary(it), it.sentAt)
            }
            value = found.sortedByDescending { it.sentAt } to null
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            value = emptyList<SearchResult>() to (error.message ?: "Could not load messages")
        }
    }
    val results = state.first
    val error = state.second
    val index = results.orEmpty().indexOfFirst { it.id == restoreId }.coerceAtLeast(0)
    val targetId = results.orEmpty().getOrNull(index)?.id
    LaunchedEffect(tool) {
        if (tool == ChatTool.SEARCH && restoreId == null) input.focusAfterFrame()
    }
    LaunchedEffect(results, error) {
        if (error != null && tool == ChatTool.PINS) resultFocus.focusAfterFrame()
        else if (!results.isNullOrEmpty() && (restoreId != null || tool == ChatTool.PINS)) {
            list.requestScrollToItem(index)
            snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.key == targetId } }.first { it }
            resultFocus.focusAfterFrame()
        }
    }
    val typing = LocalSoftkeys.current.typing
    Softkeys(center = SoftkeyAction(if (typing) "Search" else "Open"),
        right = SoftkeyAction(if (typing && !results.isNullOrEmpty()) "Results" else "Back") {
            if (typing && !results.isNullOrEmpty()) runCatching { resultFocus.requestFocus() } else onClose()
        })
    val date = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Column(Modifier.fillMaxSize()) {
        Header(if (tool == ChatTool.SEARCH) "Search chat" else "Pinned messages")
        BasicText(conversation.title.forDisplay(), style = Type.section, modifier = Modifier.padding(8.dp))
        if (tool == ChatTool.SEARCH) TextInput(query, onQueryChange, placeholder = "Search this chat", focusRequester = input,
            onDone = { if (!results.isNullOrEmpty()) runCatching { resultFocus.requestFocus() } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).focusProperties { if (!results.isNullOrEmpty()) down = resultFocus })
        when {
            error != null -> {
                CenteredMessage(error, color = Palette.error)
                ListButton("Retry", { retry++ }, focusRequester = if (tool == ChatTool.PINS) resultFocus else null)
            }
            results == null -> CenteredMessage(if (tool == ChatTool.SEARCH) "Searching…" else "Loading pins…")
            results.isEmpty() -> CenteredMessage(when {
                tool == ChatTool.PINS -> "No pinned messages"
                query.trim().length < 2 -> "Type at least two characters"
                else -> "No matches in this chat"
            })
            else -> LazyColumn(Modifier.weight(1f), state = list) {
                itemsIndexed(results, key = { _, item -> item.id }) { row, result ->
                    ListButton("${result.sender.ifBlank { "You" }} · ${date.format(Date(result.sentAt))}",
                        { onResultFocus(result.id); onPick(result) },
                        description = result.text,
                        focusRequester = if (row == index) resultFocus else null,
                        modifier = Modifier.onFocusChanged { if (it.isFocused) onResultFocus(result.id) }
                            .then(if (row == 0 && tool == ChatTool.SEARCH) Modifier.focusProperties { up = input } else Modifier),
                        highlightFocus = true)
                }
            }
        }
    }
}
