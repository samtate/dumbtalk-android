package dev.dumbtalk.app.screens

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dumbtalk.app.DumbTalkApp
import dev.dumbtalk.app.ui.CenteredMessage
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.ListButton
import dev.dumbtalk.app.ui.ServiceIcon
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.forDisplay
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.Message

/** Where to send a message: your chats on any connected service, newest first. */
@Composable
fun ForwardPanel(app: DumbTalkApp, current: Conversation, onPick: (Conversation) -> Unit, onClose: () -> Unit) {
    val page by app.inbox.active.collectAsStateWithLifecycle()
    val targets = page?.conversations.orEmpty().filter { it.id != current.id }
    val requester = remember { FocusRequester() }
    LaunchedEffect(targets.isNotEmpty()) { runCatching { requester.requestFocus() } }
    Softkeys(center = SoftkeyAction("Send"), right = SoftkeyAction("Back", onClose))
    Column(Modifier.fillMaxSize()) {
        Header("Forward to")
        if (targets.isEmpty()) CenteredMessage("No other chats")
        LazyColumn(Modifier.fillMaxSize()) {
            items(targets, key = { it.id }) { target ->
                ListButton(
                    target.title.forDisplay(),
                    onClick = { onPick(target) },
                    leading = { ServiceIcon(target.serviceId, 18.dp) },
                    focusRequester = if (target.id == targets.first().id) requester else null,
                )
            }
        }
    }
}

/** The text a forwarded message is sent with: its attribution, then its content. */
fun forwardedText(from: Conversation, message: Message): String? {
    val who = (message.sender?.takeIf { it != "You" } ?: from.title)
    val body = message.text?.takeIf { it.isNotBlank() } ?: return null
    return "Forwarded from $who:\n$body"
}
