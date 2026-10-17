package dev.dumbtalk.app.screens

import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
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

/** A fixed aspect slot, including while downloading, decoding or showing an error. */
@Composable
fun InlineImage(message: Message, attachment: dev.dumbtalk.core.Attachment, maxWidth: Dp, load: suspend (Message, dev.dumbtalk.core.Attachment) -> String) {
    InlineVisual(message, attachment, maxWidth, load, video = false)
}

@Composable
fun InlineVideo(message: Message, attachment: dev.dumbtalk.core.Attachment, maxWidth: Dp,
    load: suspend (Message, dev.dumbtalk.core.Attachment) -> String,
    loadThumbnail: suspend (Message, dev.dumbtalk.core.Attachment) -> String?,
) {
    InlineVisual(message, attachment, maxWidth, load, video = true, loadThumbnail = loadThumbnail)
}

/** A message's photos and videos, in the order they were sent. */
fun Message.visuals() = attachments.filter { it.kind == AttachmentKind.IMAGE || it.kind == AttachmentKind.VIDEO }

/** The narrowest (tall phone screenshots) and widest (panoramas) a single photo or video shows; beyond these it is cropped. */
private const val TALLEST = 3f / 4f
private const val WIDEST = 2f

/** Whether the picture's own shape is known, so it can be cropped to the bubble's rather than fitted. */
private fun knownShape(attachment: dev.dumbtalk.core.Attachment) =
    !attachment.sticker && (attachment.width ?: 0) > 0 && (attachment.height ?: 0) > 0

/** The width and height one photo or video takes in a bubble. */
internal fun visualSize(attachment: dev.dumbtalk.core.Attachment, maxWidth: Dp, minWidth: Dp = 0.dp): Pair<Dp, Dp> {
    val maxHeight = if (attachment.sticker) 140.dp else 260.dp
    val ratio = if ((attachment.width ?: 0) > 0 && (attachment.height ?: 0) > 0)
        (attachment.width!!.toFloat() / attachment.height!!).let { if (attachment.sticker) it else it.coerceIn(TALLEST, WIDEST) }
        else if (attachment.kind == AttachmentKind.VIDEO) 1.5f else 1f
    val width = minOf(maxWidth, maxHeight * ratio)
    // Widened for a caption: same height, cropped at the sides' expense.
    return maxOf(width, minOf(minWidth, maxWidth)) to width / ratio
}

/** How wide a single photo or video is at least, so a long caption doesn't wrap into a narrow column. */
internal fun captionWidth(message: Message, inner: Dp): Dp =
    if ((message.text?.length ?: 0) > 40 && message.attachments.none { it.sticker }) inner * 0.75f else 0.dp

/** The size of the collage standing in for [count] photos and videos. */
internal fun collageSize(maxWidth: Dp, count: Int): Pair<Dp, Dp> {
    val width = minOf(maxWidth, 260.dp)
    return width to when (count) { 2 -> width / 2; 3 -> width * 2 / 3; else -> width }
}

/** Whether [attachment] previews without being asked for; large files wait to be opened. */
private fun autoLoads(attachment: dev.dumbtalk.core.Attachment) =
    (attachment.size ?: 0) <= AUTO_LOAD_BYTES || attachment.localPath != null

/** A photo's or video's preview bitmap, or null while loading or when it waits to be opened. */
@Composable
private fun rememberPreview(message: Message, attachment: dev.dumbtalk.core.Attachment,
    load: suspend (Message, dev.dumbtalk.core.Attachment) -> String,
    loadThumbnail: (suspend (Message, dev.dumbtalk.core.Attachment) -> String?)?,
): Result<ImageBitmap>? {
    val video = attachment.kind == AttachmentKind.VIDEO
    val autoLoad = autoLoads(attachment)
    val image by produceState<Result<ImageBitmap>?>(null, message.id, attachment.id, attachment.localPath, autoLoad) {
        if (autoLoad) value = withContext(Dispatchers.IO) {
            runCatching {
                val preview = if (video) attachment.thumbnailBase64?.let(dev.dumbtalk.app.media.MediaMetadata::encodedThumbnail) else null
                val thumbnailPath = if (video && preview == null) loadThumbnail?.invoke(message, attachment) else null
                val bitmap = preview ?: thumbnailPath?.let { MediaFiles.thumbnail(it, 640) }
                    ?: if (!video || (attachment.size ?: 0) <= AUTO_LOAD_BYTES) {
                        val path = load(message, attachment)
                        if (video) dev.dumbtalk.app.media.MediaMetadata.video(path) else MediaFiles.thumbnail(path, 640)
                    } else null
                requireNotNull(bitmap) { "preview unavailable" }.asImageBitmap()
            }
        }
    }
    return image
}

/** A preview filling [width] × [height]: the picture, or what's happening instead, with a play mark on videos. */
@Composable
private fun PreviewBox(message: Message, attachment: dev.dumbtalk.core.Attachment, width: Dp, height: Dp,
    load: suspend (Message, dev.dumbtalk.core.Attachment) -> String,
    loadThumbnail: (suspend (Message, dev.dumbtalk.core.Attachment) -> String?)?,
    scale: ContentScale, compact: Boolean = false, content: @Composable () -> Unit = {},
) {
    val video = attachment.kind == AttachmentKind.VIDEO
    val image = rememberPreview(message, attachment, load, loadThumbnail)
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (image?.isSuccess == true) 1f else 0f, label = "media reveal")
    Box(Modifier.width(width).height(height).background(Palette.panel), contentAlignment = Alignment.Center) {
        val loaded = image?.getOrNull()
        if (loaded != null) Image(loaded, contentDescription = if (video) "Video thumbnail" else "Photo", contentScale = scale,
            modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha })
        else if (!compact) BasicText(when {
            !autoLoads(attachment) -> if (video) "Play to load video" else "View to load photo"
            image?.isFailure == true -> "Preview unavailable"
            else -> if (video) "Loading video…" else "Loading photo…"
        }, style = Type.small, modifier = Modifier.padding(6.dp))
        if (video) Box(Modifier.size(if (compact) 26.dp else 36.dp).background(Palette.background.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
            Icon(AppIcon.PLAY, size = if (compact) 18.dp else 24.dp)
        }
        content()
    }
}

@Composable
private fun InlineVisual(message: Message, attachment: dev.dumbtalk.core.Attachment, maxWidth: Dp,
    load: suspend (Message, dev.dumbtalk.core.Attachment) -> String, video: Boolean,
    loadThumbnail: (suspend (Message, dev.dumbtalk.core.Attachment) -> String?)? = null,
) {
    val (width, height) = visualSize(attachment, maxWidth, captionWidth(message, maxWidth))
    val widened = width > visualSize(attachment, maxWidth).first
    PreviewBox(message, attachment, width, height, load, loadThumbnail.takeIf { video }, if (knownShape(attachment) || widened) ContentScale.Crop else ContentScale.Fit)
}

/**
 * Several photos and videos as one picture: two side by side, three as one
 * large and two small, four or more as a grid of four whose last shows how
 * many more there are. Opening it shows them all.
 */
@Composable
fun MediaCollage(message: Message, visuals: List<dev.dumbtalk.core.Attachment>, maxWidth: Dp,
    load: suspend (Message, dev.dumbtalk.core.Attachment) -> String,
    loadThumbnail: suspend (Message, dev.dumbtalk.core.Attachment) -> String?,
) {
    val gap = 2.dp
    val (width, height) = collageSize(maxWidth, visuals.size)
    val half = (width - gap) / 2
    @Composable
    fun Tile(index: Int, tileWidth: Dp, tileHeight: Dp) {
        val more = visuals.size - 3
        PreviewBox(message, visuals[index], tileWidth, tileHeight, load, loadThumbnail, ContentScale.Crop, compact = true) {
            if (index == 3 && visuals.size > 4) {
                Box(Modifier.fillMaxSize().background(Palette.background.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    BasicText("+$more", style = Type.title.copy(color = Palette.text, fontSize = 28.sp, lineHeight = 32.sp))
                }
            }
        }
    }
    val spaced = androidx.compose.foundation.layout.Arrangement.spacedBy(gap)
    Box(Modifier.width(width).height(height).semantics { contentDescription = "${visuals.size} photos and videos" }) {
        when (visuals.size) {
            2 -> Row(horizontalArrangement = spaced) { Tile(0, half, height); Tile(1, half, height) }
            3 -> Row(horizontalArrangement = spaced) {
                Tile(0, half, height)
                Column(verticalArrangement = spaced) { Tile(1, half, (height - gap) / 2); Tile(2, half, (height - gap) / 2) }
            }
            else -> Column(verticalArrangement = spaced) {
                val row = (height - gap) / 2
                Row(horizontalArrangement = spaced) { Tile(0, half, row); Tile(1, half, row) }
                Row(horizontalArrangement = spaced) { Tile(2, half, row); Tile(3, half, row) }
            }
        }
    }
}

/** Pixel columns show actual amplitudes; elapsed time and colour follow playback. */
@Composable
fun InlineAudio(message: Message, attachment: dev.dumbtalk.core.Attachment, maxWidth: Dp, load: suspend (Message, dev.dumbtalk.core.Attachment) -> String) {
    val metadata by produceState<dev.dumbtalk.app.media.MediaMetadata.Audio?>(null, message.id, attachment.id) {
        if ((attachment.size ?: 0) <= AUTO_LOAD_BYTES || attachment.localPath != null) {
            value = withContext(Dispatchers.IO) { runCatching { dev.dumbtalk.app.media.MediaMetadata.audio(load(message, attachment)) }.getOrNull() }
        }
    }
    val playing = AudioPlayer.playing == message.id
    var elapsed by remember(message.id) { mutableLongStateOf(0L) }
    LaunchedEffect(playing) {
        elapsed = 0
        while (playing) { elapsed = AudioPlayer.positionMillis; delay(100) }
    }
    val duration = attachment.durationMillis ?: metadata?.duration?.takeIf { it > 0 } ?: if (playing) AudioPlayer.durationMillis else 0L
    val samples = attachment.waveform.ifEmpty { metadata?.peaks.orEmpty() }
    val progress = if (duration > 0 && playing) (elapsed.toFloat() / duration).coerceIn(0f, 1f) else 0f
    androidx.compose.foundation.layout.Row(Modifier.width(minOf(maxWidth, 230.dp)).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (playing) AppIcon.STOP else AppIcon.PLAY, size = 20.dp)
        androidx.compose.foundation.Canvas(Modifier.weight(1f).height(32.dp).padding(horizontal = 6.dp)) {
            val columns = 32
            val pitch = size.width / columns
            val unit = 3.dp.toPx()
            for (i in 0 until columns) {
                val sample = samples.getOrNull(i * samples.size / columns) ?: 0
                val blocks = (1 + sample * 7 / 255).coerceIn(1, 8)
                val top = (size.height - blocks * unit) / 2
                val color = if (i.toFloat() / columns < progress) Palette.send else Palette.muted
                repeat(blocks) { j -> drawRect(color, androidx.compose.ui.geometry.Offset(i * pitch, top + j * unit), androidx.compose.ui.geometry.Size((pitch - 1.dp.toPx()).coerceAtLeast(1f), unit - 1.dp.toPx())) }
            }
        }
        BasicText(if (playing) clock(elapsed) else if (duration > 0) clock(duration) else "–:––", style = Type.small)
    }
}

/** Shows whichever media overlay is open. */
@Composable
fun MediaOverlays(controller: MediaController, caption: String?) {
    when (val overlay = controller.overlay) {
        is MediaOverlay.Photo -> ImageViewer(overlay.path, controller::closeOverlay, overlay.attachments.size, overlay.index, { controller.nextPhoto(overlay) }, { controller.previousPhoto(overlay) })
        is MediaOverlay.Video -> VideoViewer(overlay.path, controller::closeOverlay)
        MediaOverlay.Attach -> AttachPanel(controller, caption)
        MediaOverlay.Voice -> VoicePanel(controller)
        null -> Unit
    }
}

/**
 * A photo filling the screen. Centre toggles Fit and Zoom; zooming fits the
 * photo to the screen's width (tall pictures) or height (wide ones) and the
 * D-pad pans along the other axis. Unzoomed, Left and Right move between an
 * album's photos.
 */
@Composable
fun ImageViewer(path: String, onClose: () -> Unit, photoCount: Int = 1, photoIndex: Int = 0, onNext: () -> Unit = {}, onPrevious: () -> Unit = {}) {
    var zoomed by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    LaunchedEffect(zoomed) { scroll.scrollTo(0) }
    LaunchedEffect(path) { zoomed = false }
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { MediaFiles.thumbnail(path, 2048)?.asImageBitmap() }
    }
    val aspect = bitmap?.let { it.width.toFloat() / it.height } ?: 1f
    // A picture taller than the screen zooms to its width and pans vertically; otherwise to its height.
    val vertical = viewport.height > 0 && aspect < viewport.width.toFloat() / viewport.height

    Softkeys(left = if (photoCount > 1) SoftkeyAction("Next") { onNext() } else null,
        center = SoftkeyAction(if (zoomed) "Fit" else "Zoom") { zoomed = !zoomed }, right = SoftkeyAction("Back", onClose))
    Column(Modifier.fillMaxSize()) {
        Header(if (photoCount > 1) "Photo ${photoIndex + 1}/$photoCount" else "Photo", trailing = { BasicText(if (!zoomed) "Fit" else if (vertical) "Width" else "Height", style = Type.small) })
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewport = it }
                .focusRequester(requester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (!zoomed) {
                        if (photoCount < 2) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionRight -> onNext()
                            Key.DirectionLeft -> onPrevious()
                            else -> return@onPreviewKeyEvent false
                        }
                        return@onPreviewKeyEvent true
                    }
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
fun AttachPanel(controller: MediaController, caption: String?, onType: () -> Unit = {}) {
    val picked = controller.picked
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Done", controller::closeOverlay))
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Header("Send attachment")
        BasicText(if (controller.picking) "Preparing attachments…" else if (picked.isEmpty()) "Choose a saved item or take a photo." else "${picked.size}/10 attached · add text in the message box.",
            style = Type.small, modifier = Modifier.padding(6.dp))
        picked.forEachIndexed { index, file ->
            ListButton("Remove ${File(file.path).name}", { controller.removePicked(index) }, leading = { Icon(AppIcon.DELETE) })
        }
        MenuGrid(buildList {
            if (picked.size < 10 && !controller.picking) {
                add(MenuItem("Choose file", AppIcon.ATTACH, controller::chooseFile))
                add(MenuItem("Take photo", AppIcon.CAMERA, controller::takePhoto))
            }
            add(MenuItem("Add text", AppIcon.COMPOSE, onType))
            if (picked.isNotEmpty() && !controller.picking) add(MenuItem("Send", AppIcon.SEND, controller::sendPicked))
            add(MenuItem("Done", AppIcon.CLOSE, controller::closeOverlay))
        }, first)
        if (!caption.isNullOrBlank()) BasicText("Caption: $caption", style = Type.small, modifier = Modifier.padding(6.dp))
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
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Header("Voice note")
        when (stage) {
            VoiceStage.READY -> {
                CenteredMessage("Press Record, speak, then Stop. You can listen before sending.")
                MenuGrid(listOf(MenuItem("Record", AppIcon.MIC, controller::record), MenuItem("Cancel", AppIcon.CLOSE, controller::cancelVoice)))
            }
            VoiceStage.RECORDING -> {
                var elapsed by remember { mutableLongStateOf(0L) }
                val recorder = controller.recorder
                LaunchedEffect(recorder) {
                    while (true) {
                        elapsed = recorder?.elapsedMillis ?: 0
                        delay(250)
                    }
                }
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BasicText("● REC", style = Type.header.copy(color = Palette.error))
                        BasicText(clock(elapsed), style = Type.title.copy(fontSize = 32.sp, lineHeight = 36.sp), modifier = Modifier.padding(vertical = 12.dp))
                        BasicText("Centre to stop", style = Type.small)
                        MenuGrid(listOf(MenuItem("Stop", AppIcon.STOP, controller::stopRecording)))
                    }
                }
            }
            VoiceStage.REVIEW -> {
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                val duration = controller.recording?.second ?: 0
                BasicText("Recorded ${clock(duration)}", style = Type.preview.copy(color = Palette.text), modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                MenuGrid(listOf(
                    MenuItem(if (AudioPlayer.playing == MediaController.DRAFT_ID) "Stop" else "Play", if (AudioPlayer.playing == MediaController.DRAFT_ID) AppIcon.STOP else AppIcon.PLAY, controller::toggleReview),
                    MenuItem("Send", AppIcon.SEND, controller::sendVoice),
                    MenuItem("Record again", AppIcon.MIC, controller::record),
                    MenuItem("Discard", AppIcon.DELETE, controller::cancelVoice),
                ), first)
            }
        }
    }
}
