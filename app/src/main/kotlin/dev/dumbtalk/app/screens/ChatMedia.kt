package dev.dumbtalk.app.screens

import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.dumbtalk.app.media.AudioPlayer
import dev.dumbtalk.app.media.MediaFiles
import dev.dumbtalk.app.ui.AppIcon
import dev.dumbtalk.app.ui.CenteredMessage
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.Icon
import dev.dumbtalk.app.ui.ListButton
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.app.ui.formatSize
import dev.dumbtalk.core.AttachmentKind
import dev.dumbtalk.core.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Images under this size load as soon as they scroll into view. */
private const val AUTO_LOAD_BYTES = 8L * 1024 * 1024

fun Message.image() = attachments.firstOrNull { it.kind == AttachmentKind.IMAGE }

/** A photo shown inside its bubble, loaded (and downloaded) in the background. */
@Composable
fun InlineImage(message: Message, maxWidth: Dp, load: suspend (Message) -> String) {
    val attachment = message.image() ?: return
    if ((attachment.size ?: 0) > AUTO_LOAD_BYTES) return
    // Stickers are small, bare images; photos fill the bubble.
    val maxHeight = if (attachment.sticker) 140.dp else 260.dp
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

/** Shows whichever media overlay is open. */
@Composable
fun MediaOverlays(controller: MediaController, caption: String?) {
    when (val overlay = controller.overlay) {
        is MediaOverlay.Photo -> ImageViewer(overlay.path, controller::closeOverlay)
        is MediaOverlay.Video -> VideoViewer(overlay.path, controller::closeOverlay)
        MediaOverlay.Attach -> AttachPanel(controller, caption)
        MediaOverlay.Voice -> VoicePanel(controller)
        null -> Unit
    }
}

/**
 * A photo filling the screen. Centre toggles Fit and Zoom; zooming fits the
 * photo to the screen's width (tall pictures) or height (wide ones) and the
 * D-pad pans along the other axis.
 */
@Composable
fun ImageViewer(path: String, onClose: () -> Unit) {
    var zoomed by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    LaunchedEffect(zoomed) { scroll.scrollTo(0) }
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { MediaFiles.thumbnail(path, 2048)?.asImageBitmap() }
    }
    val aspect = bitmap?.let { it.width.toFloat() / it.height } ?: 1f
    // A picture taller than the screen zooms to its width and pans vertically; otherwise to its height.
    val vertical = viewport.height > 0 && aspect < viewport.width.toFloat() / viewport.height

    Softkeys(center = SoftkeyAction(if (zoomed) "Fit" else "Zoom") { zoomed = !zoomed }, right = SoftkeyAction("Back", onClose))
    Column(Modifier.fillMaxSize()) {
        Header("Photo", trailing = { BasicText(if (!zoomed) "Fit" else if (vertical) "Width" else "Height", style = Type.small) })
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewport = it }
                .focusRequester(requester)
                .onPreviewKeyEvent { event ->
                    if (!zoomed || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = (if (vertical) viewport.height else viewport.width) * 0.72f
                    val delta = when (event.key) {
                        Key.DirectionDown -> step.takeIf { vertical }
                        Key.DirectionUp -> (-step).takeIf { vertical }
                        Key.DirectionRight -> step.takeIf { !vertical }
                        Key.DirectionLeft -> (-step).takeIf { !vertical }
                        else -> null
                    } ?: return@onPreviewKeyEvent false
                    scope.launch { scroll.animateScrollBy(delta) }
                    true
                }
                .focusable(),
        ) {
            val image = bitmap
            when {
                image == null -> CenteredMessage("Loading…")
                !zoomed -> Image(image, contentDescription = "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                vertical -> Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Image(
                        image, contentDescription = "Photo", contentScale = ContentScale.FillBounds,
                        modifier = Modifier.width(with(density) { viewport.width.toDp() }).height(with(density) { (viewport.width / aspect).toDp() }),
                    )
                }
                else -> Box(Modifier.fillMaxSize().horizontalScroll(scroll)) {
                    Image(
                        image, contentDescription = "Photo", contentScale = ContentScale.FillBounds,
                        modifier = Modifier.height(with(density) { viewport.height.toDp() }).width(with(density) { (viewport.height * aspect).toDp() }),
                    )
                }
            }
        }
    }
}

/** A video in the app: centre pauses and plays, Back closes. */
@Composable
fun VideoViewer(path: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val view = remember { VideoView(context) }
    var playing by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        view.setVideoPath(path)
        view.setOnPreparedListener { it.start() }
        view.setOnCompletionListener { playing = false }
        view.setOnErrorListener { _, _, _ -> failed = true; true }
        onDispose { view.stopPlayback() }
    }
    Softkeys(
        center = SoftkeyAction(if (playing) "Pause" else "Play") {
            if (view.isPlaying) { view.pause(); playing = false } else { view.start(); playing = true }
        },
        right = SoftkeyAction("Back", onClose),
    )
    Column(Modifier.fillMaxSize()) {
        Header("Video")
        if (failed) CenteredMessage("This phone can't play that video here.", color = Palette.error)
        else AndroidView({ view }, Modifier.fillMaxSize())
    }
}

/**
 * Send a file: choose a saved item or take a photo, then send. The text in
 * the message box goes with it as the caption.
 */
@Composable
fun AttachPanel(controller: MediaController, caption: String?) {
    val picked = controller.picked
    val choose = remember { FocusRequester() }
    val send = remember { FocusRequester() }
    LaunchedEffect(picked != null) { runCatching { (if (picked != null) send else choose).requestFocus() } }
    Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Cancel", controller::closeOverlay))
    Column(Modifier.fillMaxSize()) {
        Header("Send attachment")
        val info = picked?.let { File(it.path).let { file -> "${file.name} · ${formatSize(file.length())}" } }
        BasicText(
            info ?: "Choose a saved item or take a photo.",
            style = Type.preview.copy(color = Palette.text),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
        )
        if (picked != null && !caption.isNullOrBlank()) {
            BasicText("Your message will be the caption.", style = Type.small, modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp))
        }
        ListButton(if (picked != null) "Choose another" else "Choose file", controller::chooseFile, focusRequester = choose, leading = { Icon(AppIcon.ATTACH, size = 18.dp) })
        ListButton("Take photo", controller::takePhoto, leading = { Icon(AppIcon.COMPOSE, size = 18.dp) })
        if (picked != null) ListButton("Send", controller::sendPicked, focusRequester = send)
        ListButton("Cancel", controller::closeOverlay)
    }
}

private fun clock(millis: Long) = (millis / 1000).let { "%d:%02d".format(it / 60, it % 60) }

/**
 * Record, then listen back before sending. Ready: centre records.
 * Recording: centre stops. Review: Play, Send, Record again or Discard.
 */
@Composable
fun VoicePanel(controller: MediaController) {
    val stage = controller.voiceStage
    Softkeys(
        center = when (stage) {
            VoiceStage.READY -> SoftkeyAction("Record", controller::record)
            VoiceStage.RECORDING -> SoftkeyAction("Stop", controller::stopRecording)
            VoiceStage.REVIEW -> SoftkeyAction("Select")
        },
        right = SoftkeyAction("Cancel", controller::cancelVoice),
    )
    Column(Modifier.fillMaxSize()) {
        Header("Voice note")
        when (stage) {
            VoiceStage.READY -> CenteredMessage("Press Record, speak, then Stop. You can listen before sending.")
            VoiceStage.RECORDING -> {
                var elapsed by remember { mutableLongStateOf(0L) }
                val recorder = controller.recorder
                LaunchedEffect(recorder) {
                    while (true) {
                        elapsed = recorder?.elapsedMillis ?: 0
                        delay(250)
                    }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BasicText("● REC", style = Type.header.copy(color = Palette.error))
                        BasicText(clock(elapsed), style = Type.title.copy(fontSize = 32.sp, lineHeight = 36.sp), modifier = Modifier.padding(vertical = 12.dp))
                        BasicText("Centre to stop", style = Type.small)
                    }
                }
            }
            VoiceStage.REVIEW -> {
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                val duration = controller.recording?.second ?: 0
                BasicText("Recorded ${clock(duration)}", style = Type.preview.copy(color = Palette.text), modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                ListButton(if (AudioPlayer.playing == MediaController.DRAFT_ID) "Stop" else "Play", controller::toggleReview, focusRequester = first, leading = { Icon(AppIcon.MIC, size = 18.dp) })
                ListButton("Send", controller::sendVoice)
                ListButton("Record again", controller::record)
                ListButton("Discard", controller::cancelVoice)
            }
        }
    }
}
