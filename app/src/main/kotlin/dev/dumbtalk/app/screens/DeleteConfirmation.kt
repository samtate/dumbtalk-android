package dev.dumbtalk.app.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import dev.dumbtalk.app.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DeleteConfirmation(title: String, description: String, onDelete: suspend () -> Unit, onDeleted: () -> Unit, onCancel: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(busy) { if (!busy) focus.focusAfterFrame() }
    Softkeys(center = SoftkeyAction(if (busy) "Deleting" else "Select"), right = SoftkeyAction("Back") { if (!busy) onCancel() })
    Column(Modifier.fillMaxSize()) {
        Header(title)
        Column(Modifier.padding(8.dp)) {
            BasicText(description, style = Type.body, modifier = Modifier.padding(bottom = 8.dp))
            if (busy) CenteredMessage("Deleting…") else MenuGrid(listOf(
                MenuItem("Keep", AppIcon.CLOSE, onCancel),
                MenuItem("Delete", AppIcon.DELETE) {
                    if (!busy) {
                        busy = true
                        scope.launch {
                            try { onDelete(); onDeleted() }
                            catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                error = failure.message ?: "Could not delete"
                                busy = false
                            }
                        }
                    }
                },
            ), focus)
            error?.let { BasicText(it, style = Type.small.copy(color = Palette.error)) }
        }
    }
}
