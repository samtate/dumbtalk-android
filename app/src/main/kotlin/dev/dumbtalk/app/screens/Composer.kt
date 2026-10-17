package dev.dumbtalk.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import dev.dumbtalk.app.ui.LocalControlsEnabled
import dev.dumbtalk.app.ui.Icon
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.Square
import dev.dumbtalk.app.ui.TextInput
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.app.ui.focusCard
import dev.dumbtalk.core.Capabilities

/** The controls beside the message box; each one relabels the centre softkey. */
enum class ComposeControl(val label: String) {
    VOICE("Record"), ATTACH("Attach"), EMOJI("Emoji"), CLEAR("Clear"), SEND("Send"), CHAT_OPTIONS("Chat"),
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
    controls: Map<ComposeControl, FocusRequester> = emptyMap(),
    onBounds: (ComposeControl, Rect) -> Unit = { _, _ -> },
    optionsFocus: FocusRequester? = controls[ComposeControl.CHAT_OPTIONS],
) {
    Row(
        Modifier.fillMaxWidth().padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (capabilities.voiceNotes) {
            ComposerButton("Record voice note", upTo, optionsFocus, controls[ComposeControl.VOICE], { onBounds(ComposeControl.VOICE, it) }, { onFocus(ComposeControl.VOICE) }, onVoice) { Icon(AppIcon.MIC, size = 16.dp) }
        }
        if (capabilities.attachments) {
            ComposerButton("Send attachment", upTo, optionsFocus, controls[ComposeControl.ATTACH], { onBounds(ComposeControl.ATTACH, it) }, { onFocus(ComposeControl.ATTACH) }, onAttach) { Icon(AppIcon.ATTACH, size = 16.dp) }
        }
        ComposerButton("Emoji", upTo, optionsFocus, controls[ComposeControl.EMOJI], { onBounds(ComposeControl.EMOJI, it) }, { onFocus(ComposeControl.EMOJI) }, onEmoji) { Icon(AppIcon.STICKER, size = 16.dp) }
        if (model.draft.isNotEmpty()) {
            ComposerButton("Clear message", upTo, optionsFocus, controls[ComposeControl.CLEAR], {}, { onFocus(ComposeControl.CLEAR) }, model::clearDraft) { BasicText("×", style = Type.header) }
        }
        // The field keeps its own cursor and IME composition; when the draft
        // changes from outside (a mention, an emoji) the cursor goes to the end.
        var field by remember { mutableStateOf(TextFieldValue(model.draft, TextRange(model.draft.length))) }
        val value = if (field.text == model.draft) field else TextFieldValue(model.draft, TextRange(model.draft.length))
        TextInput(
            value,
            onValueChange = { field = it; if (it.text != model.draft) model.type(it.text) },
            placeholder = "Message",
            focusRequester = inputFocus,
            modifier = Modifier.weight(1f).focusProperties {
                up = upTo
                if (optionsFocus != null) down = optionsFocus
            }.onFocusChanged { if (it.isFocused) onFocus(null) },
            onDone = onSend,
        )
        val sendFocused = remember { mutableStateOf(false) }

        Box(
            Modifier
                .size(32.dp)
                .onFocusChanged { sendFocused.value = it.isFocused }
                .focusProperties { up = upTo; if (optionsFocus != null) down = optionsFocus }
                .focusCard(
                    onSend,
                    Square,
                    SolidColor(if (sendFocused.value) Palette.background else Palette.send),
                    focusRequester = controls[ComposeControl.SEND],
                    onFocused = { onFocus(ComposeControl.SEND) },
                    idleBorder = Palette.tileBorder,
                )
                .semantics { contentDescription = "Send" },
            contentAlignment = Alignment.Center,
        ) { BasicText(">", style = Type.header.copy(color = if (sendFocused.value) Palette.text else Palette.background)) }
    }
}

@Composable
private fun ComposerButton(
    description: String,
    upTo: FocusRequester,
    optionsFocus: FocusRequester?,
    requester: FocusRequester?,
    onBounds: (Rect) -> Unit,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(32.dp)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .focusProperties { up = upTo; if (optionsFocus != null) down = optionsFocus }
            .focusCard(onClick = if (LocalControlsEnabled.current) onClick else ({ }), shape = Square, background = Palette.tile, focusRequester = requester, onFocused = onFocused, idleBorder = Palette.tileBorder)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * People matching the "@…" being typed, just above the message box: Up
 * from the box reaches the nearest, centre picks one and returns to typing.
 */
@Composable
fun MentionSuggestions(
    model: ChatModel,
    nearest: FocusRequester,
    upTo: FocusRequester,
    inputFocus: FocusRequester,
) {
    LaunchedEffect(model.mentionsEnabled, model.draft) { model.prepareMentions() }
    val suggestions = model.mentionSuggestions
    if (suggestions.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        // Nearest the box last, as they read upwards from it.
        suggestions.asReversed().forEachIndexed { index, member ->
            val last = index == suggestions.lastIndex
            dev.dumbtalk.app.ui.ListButton(
                "@" + member.name,
                onClick = { model.pickMention(member); runCatching { inputFocus.requestFocus() } },
                modifier = Modifier.focusProperties {
                    if (index == 0) up = upTo
                    if (last) down = inputFocus
                },
                focusRequester = if (last) nearest else null,
                description = if (member.isAdmin) "Admin" else null,
            )
        }
    }
}
