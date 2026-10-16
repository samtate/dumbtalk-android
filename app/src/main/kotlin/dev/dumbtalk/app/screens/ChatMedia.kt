package dev.dumbtalk.app.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dumbtalk.app.media.MediaFiles
import dev.dumbtalk.app.media.VoiceRecorder
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.core.AttachmentKind
import dev.dumbtalk.core.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Images under this size load as soon as they scroll into view. */
private const val AUTO_LOAD_BYTES = 8L * 1024 * 1024

fun Message.image() = attachments.firstOrNull { it.kind == AttachmentKind.IMAGE }

/** A photo shown inside its bubble, loaded (and downloaded) in the background. */
@Composable
fun InlineImage(message: Message, maxWidth: Dp, load: suspend (Message) -> String) {
    val attachment = message.image() ?: return
    // Stickers are small, bare images; photos fill the bubble.
    val maxHeight = if (attachment.sticker) 140.dp else 260.dp
    if ((attachment.size ?: 0) > AUTO_LOAD_BYTES) return
    // null while loading; a failure shows as text rather than loading forever.
    val image by produceState<Result<ImageBitmap>?>(null, message.id) {
        value = withContext(Dispatchers.IO) {
            runCatching { requireNotNull(MediaFiles.thumbnail(load(message), 640)) { "not an image" }.asImageBitmap() }
        }
    }
    val loaded = image
    when {
        loaded == null -> BasicText("Loading photo…", style = Type.small)
        loaded.isFailure -> BasicText("Photo · couldn't load", style = Type.small)
        else -> Image(
            loaded.getOrThrow(), contentDescription = "Photo", contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = maxWidth).heightIn(max = maxHeight).padding(bottom = 3.dp),
        )
    }
}

/** A photo filling the screen; Back closes it. */
@Composable
fun ImageViewer(path: String, onClose: () -> Unit) {
    Softkeys(right = SoftkeyAction("Back", onClose))
    val side = with(LocalConfiguration.current) { maxOf(screenWidthDp, screenHeightDp) * 2 }
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { MediaFiles.thumbnail(path, side)?.asImageBitmap() }
    }
    Box(Modifier.fillMaxSize().background(Palette.background), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, contentDescription = "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            ?: BasicText("Loading…", style = Type.small)
    }
}

/** Shown while recording a voice note: centre sends, Back cancels. */
@Composable
fun RecordingPanel(recorder: VoiceRecorder, onSend: () -> Unit, onCancel: () -> Unit) {
    Softkeys(center = SoftkeyAction("Send", onSend), right = SoftkeyAction("Cancel", onCancel))
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recorder) {
        while (true) {
            elapsed = recorder.elapsedMillis
            delay(250)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header("Voice note")
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText("● REC", style = Type.header.copy(color = Palette.error))
                val seconds = elapsed / 1000
                BasicText(
                    "%d:%02d".format(seconds / 60, seconds % 60),
                    style = Type.title.copy(fontSize = 32.sp, lineHeight = 36.sp),
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                BasicText("Centre to send · Back to cancel", style = Type.small)
            }
        }
    }
}
