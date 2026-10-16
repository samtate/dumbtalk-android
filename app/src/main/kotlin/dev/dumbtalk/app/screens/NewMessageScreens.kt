package dev.dumbtalk.app.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.dumbtalk.app.DumbTalkApp
import dev.dumbtalk.app.Navigator
import dev.dumbtalk.app.Screen
import dev.dumbtalk.app.ui.CenteredMessage
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.ListButton
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.ServiceIcon
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.TextInput
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.core.Contact
import dev.dumbtalk.core.MessagingService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Contacts from every connected service, to start a chat with one without typing a number. */
@Composable
fun NewMessageScreen(app: DumbTalkApp, navigator: Navigator) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val inputFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { inputFocus.requestFocus() } }

    val contacts by produceState<List<Contact>?>(null) {
        value = coroutineScope {
            app.registry.connected()
                .filter { runCatching { it.capabilities().compose }.getOrDefault(false) }
                .map { service -> async { runCatching { service.listContacts() }.getOrDefault(emptyList()) } }
                .awaitAll()
                .flatten()
                .sortedBy { it.name.lowercase() }
        }
    }
    val needle = query.trim().lowercase()
    val shown = contacts.orEmpty().filter { needle.isEmpty() || needle in it.name.lowercase() || needle in it.detail.orEmpty() }

    Softkeys(right = SoftkeyAction("Back") { navigator.pop() })
    Column(Modifier.fillMaxSize()) {
        Header("New message")
        TextInput(query, { query = it }, placeholder = "Search contacts", focusRequester = inputFocus, modifier = Modifier.fillMaxWidth().padding(6.dp))
        error?.let { CenteredMessage(it, color = Palette.error) }
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "number") {
                ListButton("Enter a number…", { navigator.push(Screen.NewNumber) }, description = "For someone not in your contacts")
            }
            if (contacts == null) item(key = "loading") { CenteredMessage("Loading contacts…") }
            items(shown, key = { it.serviceId.key + it.id }) { contact ->
                ListButton(
                    contact.name,
                    onClick = {
                        if (!busy) {
                            busy = true
                            scope.launch {
                                runCatching { app.registry.byId(contact.serviceId).startChat(contact) }
                                    .onSuccess { navigator.popToRoot(); navigator.openChat(it) }
                                    .onFailure { error = it.message ?: "Couldn't open that chat" }
                                busy = false
                            }
                        }
                    },
                    description = contact.detail,
                    leading = { ServiceIcon(contact.serviceId, 18.dp) },
                )
            }
            if (contacts != null && shown.isEmpty() && needle.isNotEmpty()) item(key = "none") { CenteredMessage("No contact matches") }
        }
    }
}

@Composable
fun NewNumberScreen(app: DumbTalkApp, navigator: Navigator) {
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf<MessagingService?>(null) }
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val services by produceState<List<MessagingService>?>(null) {
        value = app.registry.connected().filter { runCatching { it.capabilities().compose }.getOrDefault(false) }
    }
    val requester = remember(chosen, services) { FocusRequester() }
    LaunchedEffect(requester) { runCatching { requester.requestFocus() } }

    val service = chosen ?: services?.singleOrNull()
    var busy by remember { mutableStateOf(false) }
    fun start() {
        val target = service ?: return
        if (address.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { target.createDirect(address.trim()) }
                .onSuccess { navigator.popToRoot(); navigator.openChat(it) }
                .onFailure { error = it.message ?: "Could not find that contact" }
            busy = false
        }
    }

    Softkeys(
        center = if (service != null) SoftkeyAction("Start") { start() } else SoftkeyAction("Select"),
        right = SoftkeyAction("Back") { if (chosen != null) chosen = null else navigator.pop() },
    )
    Column(Modifier.fillMaxSize()) {
        Header("Enter a number")
        when {
            services == null -> CenteredMessage("Loading…")
            services!!.isEmpty() -> CenteredMessage("No connected service can start new chats.")
            service == null -> {
                BasicText("Send with", style = Type.section, modifier = Modifier.padding(9.dp))
                services!!.forEachIndexed { index, item ->
                    ListButton(item.label, { chosen = item }, leading = { ServiceIcon(item.id, 22.dp) }, focusRequester = if (index == 0) requester else null)
                }
            }
            else -> {
                BasicText("${service.label} phone number or username", style = Type.section, modifier = Modifier.padding(9.dp))
                TextInput(
                    address, { address = it },
                    placeholder = "+44…",
                    focusRequester = requester,
                    keyboardType = KeyboardType.Phone,
                    onDone = ::start,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp),
                )
                error?.let { CenteredMessage(it, color = Palette.error) }
            }
        }
    }
}
