package dev.dumbtalk.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.dumbtalk.app.ui.AppIcon
import dev.dumbtalk.app.ui.Icon
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.Square
import dev.dumbtalk.app.ui.TextInput
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.app.ui.focusCard
import dev.dumbtalk.core.Capabilities

/** The controls beside the message box; each one relabels the centre softkey. */
enum class ComposeControl(val label: String) {
    VOICE("Record"), ATTACH("Attach"), EMOJI("Emoji"), CLEAR("Clear"), SEND("Send"),
}

/**
 * Voice note, attachment and emoji buttons, a clear-draft ×, the message box
 * and Send, left to right. Up from any of them returns to the messages.
 */
@Composable
fun ComposerBar(
    model: ChatModel,
    capabilities: Capabilities,
    inputFocus: FocusRequester,
    upTo: FocusRequester,
    /** Which control just took focus; null for the message box. */
    onFocus: (ComposeControl?) -> Unit,
    onVoice: () -> Unit,
    onAttach: () -> Unit,
    onEmoji: () -> Unit,
    onSend: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (capabilities.voiceNotes) {
            ComposerButton("Record voice note", upTo, { onFocus(ComposeControl.VOICE) }, onVoice) { Icon(AppIcon.MIC, size = 16.dp) }
        }
        if (capabilities.attachments) {
            ComposerButton("Send attachment", upTo, { onFocus(ComposeControl.ATTACH) }, onAttach) { Icon(AppIcon.ATTACH, size = 16.dp) }
        }
        ComposerButton("Emoji", upTo, { onFocus(ComposeControl.EMOJI) }, onEmoji) { Icon(AppIcon.STICKER, size = 16.dp) }
        if (model.draft.isNotEmpty()) {
            ComposerButton("Clear message", upTo, { onFocus(ComposeControl.CLEAR) }, model::clearDraft) { BasicText("×", style = Type.header) }
        }
        TextInput(
            model.draft,
            onValueChange = model::type,
            placeholder = "Message",
            focusRequester = inputFocus,
            modifier = Modifier.weight(1f).focusProperties { up = upTo }.onFocusChanged { if (it.isFocused) onFocus(null) },
            onDone = onSend,
        )
        Box(
            Modifier
                .size(32.dp)
                .focusProperties { up = upTo }
                .focusCard(onSend, Square, SolidColor(Palette.send), onFocused = { onFocus(ComposeControl.SEND) })
                .semantics { contentDescription = "Send" },
            contentAlignment = Alignment.Center,
        ) { BasicText(">", style = Type.header.copy(color = Palette.background)) }
    }
}

@Composable
private fun ComposerButton(
    description: String,
    upTo: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(32.dp)
            .focusProperties { up = upTo }
            .focusCard(onClick, Square, Palette.tile, onFocused = onFocused, idleBorder = Palette.tileBorder)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}
