package dev.dumbtalk.app.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import dev.dumbtalk.app.DumbTalkApp
import dev.dumbtalk.app.Navigator
import dev.dumbtalk.app.ui.CenteredMessage
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.ListButton
import dev.dumbtalk.app.ui.ServiceIcon
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.TextInput
import dev.dumbtalk.core.SearchResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(app: DumbTalkApp, navigator: Navigator) {
    var query by remember { mutableStateOf("") }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Back") { navigator.pop() })

    // Debounced so T9 typing doesn't search on every key press.
    val results by produceState<List<SearchResult>?>(emptyList(), query) {
        if (query.trim().length < 2) { value = emptyList(); return@produceState }
        delay(400)
        value = null
        value = coroutineScope {
            app.registry.connected()
                .filter { runCatching { it.capabilities().search }.getOrDefault(false) }
                .map { service -> async { runCatching { service.searchMessages(query.trim()) }.getOrDefault(emptyList()) } }
                .awaitAll()
                .flatten()
                .sortedByDescending { it.sentAt }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header("Search")
        TextInput(query, { query = it }, placeholder = "Search messages", focusRequester = requester, modifier = Modifier.fillMaxWidth().padding(6.dp))
        when (val found = results) {
            null -> CenteredMessage("Searching…")
            else -> if (found.isEmpty() && query.trim().length >= 2) CenteredMessage("No matches") else LazyColumn {
                items(found, key = { it.conversationId + it.id }) { result ->
                    val conversation = app.inbox.find(result.conversationId)
                    ListButton(
                        conversation?.title ?: result.sender,
                        onClick = { conversation?.let(navigator::openChat) },
                        description = "${result.sender}: ${result.text}",
                        leading = conversation?.let { { ServiceIcon(it.serviceId, 16.dp) } },
                    )
                }
            }
        }
    }
}
