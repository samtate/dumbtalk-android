package dev.dumbtalk.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import dev.dumbtalk.app.ui.AppIcon
import dev.dumbtalk.app.ui.CenteredMessage
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.ListButton
import dev.dumbtalk.app.ui.MenuTile
import dev.dumbtalk.app.ui.moveFocusOn
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.TextInput
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.app.ui.forDisplay
import dev.dumbtalk.core.Contact
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.Member
import dev.dumbtalk.core.MessagingService
import dev.dumbtalk.core.timerLabel

// Panels for disappearing messages, message requests, blocking and groups.

/** Off and the service's timers; the current one is ticked. */
@Composable
internal fun TimerPicker(current: Int, durations: List<Int>, onPick: (Int) -> Unit, onClose: () -> Unit) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Back", onClose))
    val choices = listOf(0) + durations.filter { it > 0 }.distinct().sorted()
    Column(Modifier.fillMaxSize()) {
        Header("Disappearing messages")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            BasicText(
                "New messages in this chat disappear this long after they're sent (Signal: after they're read).",
                style = Type.small.copy(color = Palette.muted),
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            )
            choices.forEach { seconds ->
                ListButton(
                    (if (seconds == current) "✓ " else "") + timerLabel(seconds),
                    onClick = { onPick(seconds) },
                    focusRequester = if (seconds == current || (seconds == 0 && current !in choices)) requester else null,
                )
            }
        }
    }
}

/**
 * In place of the message box while a chat is a message request: the
 * sender can't tell you've seen it until you accept.
 */
@Composable
internal fun RequestBar(
    conversation: Conversation,
    firstFocus: FocusRequester,
    upTo: FocusRequester,
    blocking: Boolean,
    onAccept: () -> Unit,
    onBlock: () -> Unit,
    onDelete: () -> Unit,
    onFocus: (Boolean) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(
            "${conversation.title.forDisplay()} isn't in your contacts. Accept to reply; they won't know you've read it until then.",
            style = Type.small.copy(color = Palette.muted),
            modifier = Modifier.padding(horizontal = 5.dp),
        )
        Row(Modifier.onFocusChanged { onFocus(it.hasFocus) }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MenuTile("Accept", AppIcon.SEND, onAccept, Modifier.weight(1f).moveFocusOn(Key.DirectionUp, upTo), focusRequester = firstFocus)
            if (blocking) MenuTile("Block", AppIcon.BLOCK, onBlock, Modifier.weight(1f).moveFocusOn(Key.DirectionUp, upTo))
            MenuTile("Delete", AppIcon.DELETE, onDelete, Modifier.weight(1f).moveFocusOn(Key.DirectionUp, upTo))
        }
    }
}

/** In place of the message box in a chat with someone you blocked. */
@Composable
internal fun BlockedBar(conversation: Conversation, firstFocus: FocusRequester, upTo: FocusRequester, onUnblock: () -> Unit, onFocus: (Boolean) -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(4.dp).onFocusChanged { onFocus(it.hasFocus) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText("You blocked ${conversation.title.forDisplay()}.", style = Type.small.copy(color = Palette.muted), modifier = Modifier.padding(horizontal = 5.dp))
        MenuTile("Unblock", AppIcon.BLOCK, onUnblock, Modifier.fillMaxWidth().moveFocusOn(Key.DirectionUp, upTo), focusRequester = firstFocus)
    }
}

/** A group's people and what you can change: name, members, leaving. */
@Composable
internal fun GroupInfoPanel(
    conversation: Conversation,
    model: ChatModel,
    onRename: () -> Unit,
    onAdd: () -> Unit,
    onRemove: (Member) -> Unit,
    onLeave: () -> Unit,
    onClose: () -> Unit,
    focusedLabel: String? = null,
    onFocused: (String) -> Unit = {},
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    LaunchedEffect(model.members) { if (model.members == null) model.loadMembers() }
    Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Back", onClose))
    val members = model.members
    Column(Modifier.fillMaxSize()) {
        Header("Group info")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
            BasicText(conversation.title.forDisplay(), style = Type.section, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
            MenuGrid(
                buildList {
                    add(MenuItem("Rename", AppIcon.EDIT, onRename))
                    add(MenuItem("Add people", AppIcon.GROUP, onAdd))
                    add(MenuItem("Leave group", AppIcon.CLOSE, onLeave))
                },
                requester, focusedLabel, onFocused,
            )
            model.sendError?.let { BasicText(it, style = Type.small.copy(color = Palette.error), modifier = Modifier.padding(top = 6.dp)) }
            BasicText(
                if (members == null) "MEMBERS" else "MEMBERS · ${members.size + 1}",
                style = Type.section,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
            BasicText("You" + if (conversation.isAdmin) " · admin" else "", style = Type.body, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp))
            when {
                members == null -> BasicText("Loading…", style = Type.small.copy(color = Palette.muted))
                else -> members.forEach { member ->
                    val detail = listOfNotNull("admin".takeIf { member.isAdmin }, "invited".takeIf { member.isPending }).joinToString(" · ").ifEmpty { null }
                    if (conversation.isAdmin) {
                        ListButton(member.name.forDisplay(), { onRemove(member) }, description = listOfNotNull(detail, "Select to remove").joinToString(" · "))
                    } else {
                        BasicText(member.name.forDisplay() + (detail?.let { " · $it" } ?: ""), style = Type.body, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp))
                    }
                }
            }
            if (!conversation.isAdmin) {
                BasicText("Only group admins can remove people.", style = Type.small.copy(color = Palette.muted), modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
internal fun Confirmation(title: String, text: String, keep: String, act: String, icon: AppIcon, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Back", onCancel))
    Column(Modifier.fillMaxSize()) {
        Header(title)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(text.forDisplay(), style = Type.body)
            MenuGrid(listOf(MenuItem(keep, AppIcon.CLOSE, onCancel), MenuItem(act, icon, onConfirm)), requester)
        }
    }
}

/** One line of text, such as a group name; centre saves. */
@Composable
internal fun NameInput(title: String, initial: String, action: String, onSave: (String) -> Unit, onClose: () -> Unit, problem: String? = null) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    fun save() = if (name.isBlank()) error = "Enter a name" else onSave(name.trim())
    Softkeys(center = SoftkeyAction(action) { save() }, right = SoftkeyAction("Back", onClose))
    Column(Modifier.fillMaxSize()) {
        Header(title)
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextInput(name, { name = it; error = null }, placeholder = "Group name", focusRequester = requester, onDone = { save() }, modifier = Modifier.fillMaxWidth())
            (error ?: problem)?.let { BasicText(it, style = Type.small.copy(color = Palette.error)) }
        }
    }
}

/**
 * Picks several of a service's contacts: centre ticks one, the left
 * softkey goes on with those ticked. [exclude] hides people already there.
 */
@Composable
internal fun ContactPicker(
    title: String,
    service: MessagingService,
    action: String,
    onDone: (List<Contact>) -> Unit,
    onClose: () -> Unit,
    exclude: Set<String> = emptySet(),
) {
    var query by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<Contact>() }
    val inputFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { inputFocus.requestFocus() } }
    val contacts by produceState<List<Contact>?>(null, service) {
        value = runCatching { service.listContacts() }.getOrDefault(emptyList()).filter { it.id !in exclude }
    }
    val needle = query.trim().lowercase()
    val shown = contacts.orEmpty().filter { needle.isEmpty() || needle in it.name.lowercase() || needle in it.detail.orEmpty() }
    Softkeys(
        left = if (chosen.isNotEmpty()) SoftkeyAction("$action (${chosen.size})") { onDone(chosen.toList()) } else null,
        center = SoftkeyAction("Select"),
        right = SoftkeyAction("Back", onClose),
    )
    Column(Modifier.fillMaxSize()) {
        Header(title)
        TextInput(query, { query = it }, placeholder = "Search contacts", focusRequester = inputFocus, modifier = Modifier.fillMaxWidth().padding(6.dp))
        if (chosen.isNotEmpty()) {
            BasicText(
                chosen.joinToString { it.name }.forDisplay(),
                style = Type.small.copy(color = Palette.sender),
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 9.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (contacts == null) item(key = "loading") { CenteredMessage("Loading contacts…") }
            items(shown, key = { it.id }) { contact ->
                val ticked = chosen.any { it.id == contact.id }
                ListButton(
                    (if (ticked) "✓ " else "") + contact.name,
                    onClick = { if (ticked) chosen.removeAll { it.id == contact.id } else chosen.add(contact) },
                    description = contact.detail,
                )
            }
            if (contacts != null && shown.isEmpty()) item(key = "none") {
                CenteredMessage(if (needle.isEmpty()) "No contacts to add" else "No contact matches")
            }
        }
    }
}
