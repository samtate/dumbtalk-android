package dev.dumbtalk.app.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dumbtalk.app.DumbTalkApp
import dev.dumbtalk.app.Navigator
import dev.dumbtalk.app.Screen
import dev.dumbtalk.app.ui.CenteredMessage
import dev.dumbtalk.app.ui.Square
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.ListButton
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.ServiceIcon
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.core.ServiceEvent
import dev.dumbtalk.core.ServiceId
import dev.dumbtalk.core.ServiceStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@Composable
fun ServicesScreen(app: DumbTalkApp, navigator: Navigator) {
    val scope = rememberCoroutineScope()
    var statuses by remember { mutableStateOf<List<ServiceStatus>?>(null) }
    var confirmDisconnect by remember { mutableStateOf<ServiceId?>(null) }
    // Focus returns to the row last acted on when the confirmation closes.
    var focusedService by remember { mutableStateOf<ServiceId?>(null) }
    val requester = remember { FocusRequester() }

    suspend fun refresh() {
        statuses = coroutineScope {
            app.registry.installed.map { service ->
                async { runCatching { service.status() }.getOrDefault(ServiceStatus(service.id, connected = false, ready = false)) }
            }.awaitAll()
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        app.registry.events.filter { it is ServiceEvent.StatusChanged }.conflate().collect { refresh() }
    }

    LaunchedEffect(statuses != null, confirmDisconnect) {
        if (statuses == null || confirmDisconnect != null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }

    val pending = confirmDisconnect
    if (pending != null) {
        Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Back") { confirmDisconnect = null })
        val confirmFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { confirmFocus.requestFocus() } }
        Column(Modifier.fillMaxSize()) {
            Header("Disconnect ${pending.label}?")
            CenteredMessage("This unlinks DumbTalk and deletes its ${pending.label} data from this phone.")
            ListButton("Keep connected", { confirmDisconnect = null }, focusRequester = confirmFocus)
            ListButton("Disconnect", {
                scope.launch {
                    runCatching { app.registry.byId(pending).disconnect() }
                    confirmDisconnect = null
                    refresh()
                }
            })
        }
        return
    }

    Softkeys(center = SoftkeyAction("Open"), right = SoftkeyAction("Back") { navigator.pop() })
    Column(Modifier.fillMaxSize()) {
        Header("Services")
        BasicText(
            "Connected services share one conversation list while keeping their own accounts.",
            style = Type.preview,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
        )
        statuses?.forEach { status ->
            ListButton(
                status.id.label,
                onClick = {
                    focusedService = status.id
                    if (status.connected) confirmDisconnect = status.id else navigator.push(Screen.Setup(status.id))
                },
                description = status.accountLabel ?: if (status.connected) "Connected" else "Not connected",
                focusRequester = if (status.id == (focusedService ?: statuses!!.first().id)) requester else null,
                leading = { ServiceIcon(status.id, size = 28.dp) },
                trailing = {
                    BasicText(
                        // Linked but not connected right now reads as Offline.
                        when {
                            status.connected && status.ready -> "On"
                            status.connected -> "Offline"
                            else -> "Off"
                        },
                        style = Type.bold(Type.small.copy(color = if (status.connected && status.ready) Palette.background else Palette.muted)),
                        modifier = Modifier
                            .background(if (status.connected && status.ready) Palette.text else Palette.background, Square)
                            .border(1.dp, if (status.connected && status.ready) Palette.text else Palette.line, Square)
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                },
            )
        } ?: CenteredMessage("Loading…")
    }
}

