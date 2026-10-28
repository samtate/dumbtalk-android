// Shared Dumb UI kit: generated from dumb-kit/ui/Components.kt by sync.py. Edit it there.
package dev.dumbtalk.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Background controls are inert while a composer popover owns focus. */
val LocalControlsEnabled = staticCompositionLocalOf { true }

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
    onVerticalMove: ((Int) -> Boolean)? = null,
): Modifier {
    val controlsEnabled = LocalControlsEnabled.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val currentOnClick = rememberUpdatedState(if (controlsEnabled) onClick else ({ }))
    val softkeys = LocalSoftkeys.current
    val owner = remember { Any() }
    DisposableEffect(owner) { onDispose { softkeys.claimCenter(owner, false) } }
    return this
        .focusProperties { canFocus = controlsEnabled }
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged {
            softkeys.claimCenter(owner, it.isFocused && claimsCenter)
            if (it.isFocused) onFocused?.invoke()
        }
        .onKeyEvent { event ->
            if (event.key == Key.DirectionUp || event.key == Key.DirectionDown) {
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                return@onKeyEvent onVerticalMove?.invoke(if (event.key == Key.DirectionUp) -1 else 1) ?: false
            }
            if (!claimsCenter) return@onKeyEvent false
            if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter) return@onKeyEvent false
            if (event.type == KeyEventType.KeyUp) currentOnClick.value()
            true
        }
        .focusable(interactionSource = interaction)
        .pointerInput(Unit) { detectTapGestures { currentOnClick.value() } }
        // One element for screen readers (and tests): the card with its contents.
        .semantics(mergeDescendants = true) { onClick { currentOnClick.value(); true } }
        .clip(shape)
        .background(background, shape)
        .border(if (focused) 2.dp else 1.dp, if (focused) Palette.focus else idleBorder, shape)
}

/**
 * Sends focus to [target] when [key] is pressed, if [target] is on screen;
 * otherwise the key moves focus as usual. Used instead of focusProperties'
 * up/left, which crash when the target isn't composed (the newest message
 * scrolled out of the list, say).
 */
@Composable
fun Modifier.moveFocusOn(key: Key, target: FocusRequester?): Modifier {
    if (target == null) return this
    val moved = remember { BooleanArray(1) }
    return onPreviewKeyEvent { event ->
        if (event.key != key) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) moved[0] = runCatching { target.requestFocus() }.isSuccess
        moved[0]
    }
}

/**
 * Focuses this after the next frame, once a newly composed target is
 * attached. Returns false, rather than throwing, when it isn't on screen.
 */
suspend fun FocusRequester.focusAfterFrame(): Boolean {
    withFrameNanos { }
    return runCatching { requestFocus() }.getOrDefault(false)
}

/** Near-square corners, like a character display's cells. */
val Square = RoundedCornerShape(2.dp)

@Composable
fun Header(title: String, modifier: Modifier = Modifier, leading: @Composable (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(Palette.header)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        if (leading != null) Spacer(Modifier.width(7.dp))
        BasicText(title.forDisplay(), style = Type.header, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
}

/** A small outlined label, e.g. a count or a service name in a header. */
@Composable
fun Pill(text: String, color: Color = Palette.time) {
    BasicText(
        text.uppercase(),
        style = Type.small.copy(color = color),
        modifier = Modifier
            .border(1.dp, if (color == Palette.time) Palette.line else color, Square)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/**
 * Up to two initials from words that start with a letter or digit (emoji and
 * symbols are skipped); "#" for a bare number, "?" for anything else.
 */
fun initials(value: String): String {
    val words = value.split(Regex("\\s+"))
    val letters = words.mapNotNull { word -> word.firstOrNull()?.takeIf { it.isLetter() }?.uppercaseChar() }
    if (letters.isNotEmpty()) return letters.take(2).joinToString("")
    return if (value.any { it.isDigit() }) "#" else "?"
}

/** A contact's photo (a content URI), or their initials in their tint. */
@Composable
fun Avatar(title: String, photoUri: String?, size: Dp = 34.dp) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, photoUri) {
        value = if (photoUri.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(Uri.parse(photoUri), Size(256, 256), null) }.getOrNull()
        }
    }
    AvatarBox(title, bitmap, size, unread = 0, noteToSelf = false)
}

/** A chat's picture (a local file), or initials, with its unread count. */
@Composable
fun Avatar(title: String, unread: Int = 0, size: Dp = 34.dp, noteToSelf: Boolean = false, imagePath: String? = null) {
    val bitmap by produceState<Bitmap?>(null, imagePath) {
        value = if (imagePath.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(File(imagePath).absolutePath) }.getOrNull()
        }
    }
    AvatarBox(title, bitmap, size, unread, noteToSelf)
}

@Composable
private fun AvatarBox(title: String, bitmap: Bitmap?, size: Dp, unread: Int, noteToSelf: Boolean) {
    val tint = if (noteToSelf) Palette.text else avatarTint(title)
    Box(Modifier.size(size)) {
        Box(
            Modifier.size(size).clip(Square).border(1.dp, tint.copy(alpha = 0.6f), Square),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null && !noteToSelf) {
                Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size), contentScale = ContentScale.Crop)
            } else if (noteToSelf) {
                Icon(AppIcon.EDIT, size = size * 0.55f, color = tint)
            } else {
                BasicText(
                    initials(title),
                    style = Type.centered(Type.bold(Type.label.copy(color = tint, fontSize = Type.label.fontSize * (size / 34.dp).coerceAtMost(2.5f)))),
                )
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
                    style = Type.centered(Type.bold(Type.small.copy(color = Palette.background))),
                )
            }
        }
    }
}

/**
 * A person, or something that stands for one (a call, a chat): their avatar,
 * name in the larger type and a [detail] line beneath. Borderless until focused.
 */
@Composable
fun PersonRow(
    title: String,
    photoUri: String?,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    trailing: @Composable (() -> Unit)? = null,
    detail: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp, vertical = 3.dp)
            .focusCard(onClick, focusRequester = focusRequester, onFocused = onFocused)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(title, photoUri)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            BasicText(title.forDisplay(), style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail()
        }
        trailing?.invoke()
    }
}

/** The muted line under a [PersonRow]'s name. */
@Composable
fun DetailText(text: String, color: Color = Palette.muted) {
    BasicText(text.forDisplay(), style = Type.preview.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** The two-column icon tiles used by option menus. */
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
    highlightFocus: Boolean = false,
    onFocused: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val inverted = highlightFocus && focused
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp, vertical = 2.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusCard(onClick, background = if (inverted) SolidColor(Palette.focus) else Palette.tile, focusRequester = focusRequester,
                onFocused = onFocused, idleBorder = Palette.tileBorder)
            .heightIn(min = 36.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            BasicText(title.forDisplay(), style = Type.label.copy(color = if (inverted) Palette.background else Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (description != null) BasicText(description, style = Type.preview.copy(color = if (inverted) Palette.background else Palette.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    val interaction = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        enabled = LocalControlsEnabled.current,
        maxLines = if (singleLine) 1 else 4,
        textStyle = Type.body,
        cursorBrush = SolidColor(Palette.focus),
        interactionSource = interaction,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onSend = { onDone?.invoke() }),
        modifier = inputModifier(modifier, interaction, focusRequester),
        decorationBox = { inner -> InputDecoration(value.isEmpty(), placeholder, inner) },
    )
}

/** The same field over a [TextFieldValue], for callers that place the cursor. */
@Composable
fun TextInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    focusRequester: FocusRequester? = null,
    onDone: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    // Compose sends D-pad Left/Right out of a text field as focus moves. Move
    // the cursor instead while there's text that way; at either end the key
    // moves focus as usual (to the composer's buttons, or Send).
    val cursorKey = remember { arrayOfNulls<Key>(1) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = LocalControlsEnabled.current,
        maxLines = 1,
        textStyle = Type.body,
        cursorBrush = SolidColor(Palette.focus),
        interactionSource = interaction,
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onSend = { onDone?.invoke() }),
        modifier = inputModifier(modifier, interaction, focusRequester).onPreviewKeyEvent { event ->
            val step = when (event.key) {
                Key.DirectionLeft -> -1
                Key.DirectionRight -> 1
                else -> return@onPreviewKeyEvent false
            }
            if (event.type == KeyEventType.KeyUp) return@onPreviewKeyEvent (cursorKey[0] == event.key).also { cursorKey[0] = null }
            val target = (if (value.selection.collapsed) value.selection.start else if (step < 0) value.selection.min else value.selection.max) +
                (if (value.selection.collapsed) step else 0)
            if (target !in 0..value.text.length || (value.selection.collapsed && target == value.selection.start)) return@onPreviewKeyEvent false
            cursorKey[0] = event.key
            onValueChange(value.copy(selection = TextRange(target)))
            true
        },
        decorationBox = { inner -> InputDecoration(value.text.isEmpty(), placeholder, inner) },
    )
}

@Composable
private fun InputDecoration(empty: Boolean, placeholder: String, inner: @Composable () -> Unit) {
    Box {
        if (empty) BasicText(placeholder, style = Type.body.copy(color = Palette.muted))
        inner()
    }
}

/** Focus, typing-flag and border handling shared by both text fields. */
@Composable
private fun inputModifier(modifier: Modifier, interaction: MutableInteractionSource, focusRequester: FocusRequester?): Modifier {
    val softkeys = LocalSoftkeys.current
    val controlsEnabled = LocalControlsEnabled.current
    val focused by interaction.collectIsFocusedAsState()
    // The typing flag follows the focus system directly. A plain holder, not
    // state: it only remembers whether this field set the flag.
    val ownsTyping = remember { BooleanArray(1) }
    DisposableEffect(Unit) { onDispose { if (ownsTyping[0]) softkeys.typing = false } }
    return modifier
        .focusProperties { canFocus = controlsEnabled }
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged {
            if (it.isFocused != ownsTyping[0]) {
                ownsTyping[0] = it.isFocused
                softkeys.typing = it.isFocused
            }
        }
        .background(Palette.background, Square)
        .border(if (focused) 2.dp else 1.dp, if (focused) Palette.focus else Palette.line, Square)
        .padding(horizontal = 7.dp, vertical = 6.dp)
}

@Composable
fun CenteredMessage(text: String, modifier: Modifier = Modifier, color: Color = Palette.muted) {
    Box(modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = Type.body.copy(color = color, textAlign = TextAlign.Center))
    }
}

class MenuItem(val label: String, val icon: AppIcon, val onClick: () -> Unit)

/** Two-column tile grid; the first tile takes [requester]. */
@Composable
fun MenuGrid(items: List<MenuItem>, requester: FocusRequester? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEachIndexed { column, item ->
                    MenuTile(item.label, item.icon, item.onClick, Modifier.weight(1f),
                        focusRequester = if (row == 0 && column == 0) requester else null)
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A settings row with a current value and an optional explanation. */
@Composable
fun SettingRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    description: String? = null,
    focusRequester: FocusRequester? = null,
) {
    Column(
        modifier.fillMaxWidth()
            .padding(horizontal = 5.dp, vertical = 2.dp)
            .focusCard(
                onClick,
                background = Palette.tile,
                focusRequester = focusRequester,
                idleBorder = Palette.tileBorder,
            )
            .heightIn(min = 36.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(
                title.forDisplay(),
                style = Type.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (value != null) BasicText(
                value,
                style = Type.section.copy(color = Palette.text, textAlign = TextAlign.End),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
        }
        if (description != null) BasicText(
            description,
            style = Type.preview,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Focuses [requester] once the screen has drawn, and again whenever [key] changes. */
@Composable
fun FocusOnStart(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}

/** A small heading above a group of rows. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    BasicText(text.uppercase(), style = Type.section, modifier = modifier.padding(start = 10.dp, top = 10.dp, bottom = 4.dp))
}

@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
    BasicText(text, style = Type.small.copy(color = Palette.error), modifier = modifier.padding(horizontal = 10.dp, vertical = 4.dp))
}
