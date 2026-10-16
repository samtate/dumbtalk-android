package dev.dumbtalk.app.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.painterResource
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
import dev.dumbtalk.app.R
import dev.dumbtalk.core.ServiceId

/**
 * The D-pad focus treatment used everywhere: a card that draws a blue border
 * while focused and activates on centre/Enter or a tap. With [claimsCenter]
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
    shape: Shape = RoundedCornerShape(8.dp),
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
        .border(1.dp, if (focused) Palette.focus else idleBorder, shape)
}

@Composable
fun Header(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(Palette.header)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(title, style = Type.header, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
}

@Composable
fun Pill(text: String) {
    BasicText(
        text.uppercase(),
        style = Type.small.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFFAFC7BC)),
        modifier = Modifier
            .border(1.dp, Color(0xFF456357), RoundedCornerShape(9.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

fun serviceIcon(id: ServiceId): Int = when (id) {
    ServiceId.SIGNAL -> R.drawable.service_signal
    ServiceId.TELEGRAM -> R.drawable.service_telegram
    ServiceId.WHATSAPP -> R.drawable.service_whatsapp
}

@Composable
fun ServiceIcon(id: ServiceId, size: Dp = 12.dp) {
    Image(painterResource(serviceIcon(id)), contentDescription = id.label, modifier = Modifier.size(size).clip(CircleShape))
}

fun initials(value: String): String =
    value.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("").ifEmpty { "?" }

@Composable
fun Avatar(title: String, unread: Int = 0, size: Dp = 34.dp, noteToSelf: Boolean = false) {
    Box(Modifier.size(size)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(Palette.avatar).border(1.dp, Color(0x24FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(if (noteToSelf) "🔖" else initials(title), style = Type.body.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold))
        }
        if (unread > 0) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 3.dp, y = 3.dp)
                    .heightIn(min = 15.dp)
                    .clip(CircleShape)
                    .background(Palette.unread)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(if (unread > 99) "99+" else unread.toString(), style = Type.small.copy(fontWeight = FontWeight.Bold))
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
            .focusCard(onClick, RoundedCornerShape(7.dp), Palette.tile, focusRequester, idleBorder = Palette.tileBorder)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(Palette.pill),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, size = 16.dp) }
        BasicText(label, style = Type.body.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), maxLines = 2)
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
            BasicText(title, style = Type.body.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        textStyle = Type.body.copy(fontSize = 14.sp),
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
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xFF0F1E19))
            .border(1.dp, if (focused) Palette.focus else Palette.line, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 6.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) BasicText(placeholder, style = Type.body.copy(fontSize = 14.sp, color = Palette.muted))
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
