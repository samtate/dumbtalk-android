package dev.dumbtalk.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dumbtalk.core.ServiceId

/**
 * The D-pad focus treatment used everywhere: a card that draws a solid white
 * frame while focused and activates on centre/Enter or a tap. With [claimsCenter]
 * false the centre key goes to the screen's centre softkey instead (chat
 * bubbles, where centre means Type); taps still activate.
 *
 * This deliberately avoids `clickable`: since Compose 1.8 it refuses focus
 * in touch mode, and a touchscreen keypad phone starts in touch mode, so
 * initial focus requests would silently fail. `focusable` always accepts it.
 */
@Composable
fun Modifier.focusCard(
    onClick: () -> Unit,
    shape: Shape = Square,
    background: Brush = Palette.row,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    idleBorder: Color = Color.Transparent,
    claimsCenter: Boolean = true,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val currentOnClick by rememberUpdatedState(onClick)
    val softkeys = LocalSoftkeys.current
    val owner = remember { Any() }
    DisposableEffect(owner) { onDispose { softkeys.claimCenter(owner, false) } }
    return this
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged {
            softkeys.claimCenter(owner, it.isFocused && claimsCenter)
            if (it.isFocused) onFocused?.invoke()
        }
        .onKeyEvent { event ->
            if (!claimsCenter) return@onKeyEvent false
            if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter) return@onKeyEvent false
            if (event.type == KeyEventType.KeyUp) currentOnClick()
            true
        }
        .focusable(interactionSource = interaction)
        .pointerInput(Unit) { detectTapGestures { currentOnClick() } }
        .semantics { onClick { currentOnClick(); true } }
        .clip(shape)
        .background(background, shape)
        .border(if (focused) 2.dp else 1.dp, if (focused) Palette.focus else idleBorder, shape)
}

/** Near-square corners, like a character display's cells. */
val Square = RoundedCornerShape(2.dp)

@Composable
fun Header(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(Palette.header)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(title, style = Type.header, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
}

/** A small outlined label, e.g. a service name in a header. */
@Composable
fun Pill(text: String) {
    BasicText(
        text.uppercase(),
        style = Type.small,
        modifier = Modifier
            .border(1.dp, Palette.line, Square)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}


/** Up to two initials from words that start with a letter or digit (emoji and symbols are skipped). */
fun initials(value: String): String =
    value.split(Regex("\\s+"))
        .mapNotNull { word -> word.firstOrNull()?.takeIf { it.isLetterOrDigit() }?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifEmpty { "?" }

/**
 * Subtle tints so chats with the same initials look different; the same
 * name always gets the same tint. Stands in until real photos are shown.
 */
private val avatarTints = listOf(
    Color(0xFFE6C38F), Color(0xFF8FD3C9), Color(0xFFE7A0AC), Color(0xFFBDAAE8),
    Color(0xFFB5D98C), Color(0xFF98C2EE), Color(0xFFEDAD80), Color(0xFFD2CBAE),
)

fun avatarTint(title: String): Color = avatarTints[Math.floorMod(title.hashCode(), avatarTints.size)]

@Composable
fun Avatar(title: String, unread: Int = 0, size: Dp = 34.dp, noteToSelf: Boolean = false) {
    val tint = if (noteToSelf) Palette.text else avatarTint(title)
    Box(Modifier.size(size)) {
        Box(
            Modifier.size(size).border(1.dp, tint.copy(alpha = 0.6f), Square),
            contentAlignment = Alignment.Center,
        ) {
            if (noteToSelf) {
                PixelIcon(PixelArt.PENCIL, size * 0.55f, tint)
            } else {
                BasicText(initials(title), style = Type.centered(Type.label.copy(color = tint, fontWeight = FontWeight.Bold)))
            }
        }
        // Unread count as an inverted block, like a lit display segment.
        if (unread > 0) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = 4.dp)
                    .heightIn(min = 16.dp)
                    .background(Palette.unread, Square)
                    .padding(horizontal = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    if (unread > 99) "99+" else unread.toString(),
                    style = Type.centered(Type.small.copy(color = Palette.background, fontWeight = FontWeight.Bold)),
                )
            }
        }
    }
}

/** The two-column icon tiles used by the main and chat option menus. */
@Composable
fun MenuTile(label: String, icon: AppIcon, onClick: () -> Unit, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null) {
    Row(
        modifier
            .heightIn(min = 42.dp)
            .focusCard(onClick, Square, Palette.tile, focusRequester, idleBorder = Palette.tileBorder)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, size = 18.dp)
        BasicText(label, style = Type.label, maxLines = 2)
    }
}

/** A button-like row with a title and optional description. */
@Composable
fun ListButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    focusRequester: FocusRequester? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp, vertical = 2.dp)
            .focusCard(onClick, background = Palette.tile, focusRequester = focusRequester, idleBorder = Palette.tileBorder)
            .heightIn(min = 36.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            BasicText(title, style = Type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (description != null) BasicText(description, style = Type.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

/**
 * Text field that tells the softkey controller it has focus so the 1 key
 * types a digit instead of pressing the left softkey.
 */
@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    focusRequester: FocusRequester? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    val softkeys = LocalSoftkeys.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // The typing flag follows the focus system directly. A plain holder, not
    // state: it only remembers whether this field set the flag.
    val ownsTyping = remember { BooleanArray(1) }
    DisposableEffect(Unit) { onDispose { if (ownsTyping[0]) softkeys.typing = false } }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 4,
        textStyle = Type.body,
        cursorBrush = SolidColor(Palette.focus),
        interactionSource = interaction,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onSend = { onDone?.invoke() }),
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (it.isFocused != ownsTyping[0]) {
                    ownsTyping[0] = it.isFocused
                    softkeys.typing = it.isFocused
                }
            }
            .background(Palette.background, Square)
            .border(if (focused) 2.dp else 1.dp, if (focused) Palette.focus else Palette.line, Square)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) BasicText(placeholder, style = Type.body.copy(color = Palette.muted))
                inner()
            }
        },
    )
}

@Composable
fun CenteredMessage(text: String, modifier: Modifier = Modifier, color: Color = Palette.muted) {
    Box(modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = Type.body.copy(color = color, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
    }
}
