package dev.dumbtalk.app.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import dev.dumbtalk.app.media.AudioPlayer
import dev.dumbtalk.app.media.MediaFiles
import dev.dumbtalk.app.media.VoiceRecorder
import dev.dumbtalk.core.AttachmentKind
import dev.dumbtalk.core.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A full-screen media step over the chat. */
sealed interface MediaOverlay {
    data class Photo(val path: String) : MediaOverlay
    data class Video(val path: String, val mime: String?) : MediaOverlay
    /** Choose a file or take a photo, then send it with the draft as caption. */
    data object Attach : MediaOverlay
    /** Record, listen back, then send. */
    data object Voice : MediaOverlay
}

/** Where a voice note is in its life: waiting, recording, or recorded and ready to review. */
enum class VoiceStage { READY, RECORDING, REVIEW }

/**
 * Everything media in one open chat: viewing photos and videos, playing
 * audio, the attach and voice-note flows, and the pickers they use.
 */
class MediaController(
    private val context: Context,
    private val model: ChatModel,
    private val scope: CoroutineScope,
) {
    var overlay by mutableStateOf<MediaOverlay?>(null)
        private set

    /** The file chosen (or photo taken) in the attach panel. */
    var picked by mutableStateOf<MediaFiles.Picked?>(null)
        private set

    var voiceStage by mutableStateOf(VoiceStage.READY)
        private set
    var recorder by mutableStateOf<VoiceRecorder?>(null)
        private set
    /** The finished recording and its length, while reviewing. */
    var recording by mutableStateOf<Pair<File, Long>?>(null)
        private set

    /** MIME type voice notes should be recorded as; set by the screen from the service. */
    var voiceMime = "audio/ogg"

    internal lateinit var filePicker: ManagedActivityResultLauncher<String, Uri?>
    internal lateinit var camera: ManagedActivityResultLauncher<Uri, Boolean>
    internal lateinit var microphone: ManagedActivityResultLauncher<String, Boolean>
    private var pendingPhoto: File? = null

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    // region Viewing and playing

    private fun withAttachment(message: Message, use: (String) -> Unit) {
        scope.launch {
            runCatching { model.attachmentPath(message) }
                .onSuccess(use)
                .onFailure { toast("Couldn't download: ${it.message ?: "failed"}") }
        }
    }

    /** Opens a photo or video full screen. */
    fun view(message: Message) = withAttachment(message) { path ->
        val attachment = message.attachments.first()
        overlay = if (attachment.kind == AttachmentKind.VIDEO) MediaOverlay.Video(path, attachment.contentType) else MediaOverlay.Photo(path)
    }

    /** Plays a voice note or audio file, or stops it if it's playing. */
    fun togglePlay(message: Message) {
        if (AudioPlayer.playing == message.id) return AudioPlayer.stop()
        withAttachment(message) { runCatching { AudioPlayer.play(message.id, it) }.onFailure { toast("Couldn't play it") } }
    }

    /** Hands a file to whichever app can open it. */
    fun openExternally(message: Message) = withAttachment(message) { path ->
        if (!MediaFiles.open(context, path, message.attachments.first().contentType)) toast("No app here can open this file")
    }

    fun closeOverlay() {
        overlay = null
        AudioPlayer.stop()
    }

    // endregion

    // region Attach

    fun openAttach() {
        picked = null
        overlay = MediaOverlay.Attach
    }

    fun chooseFile() = filePicker.launch("*/*")

    fun takePhoto() {
        val file = File(File(context.cacheDir, "outgoing").apply { mkdirs() }, "photo-${System.currentTimeMillis()}.jpg")
        pendingPhoto = file
        camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
    }

    internal fun onFilePicked(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { MediaFiles.copyIn(context, uri) } }
                .onSuccess { picked = it }
                .onFailure { toast("Couldn't attach: ${it.message ?: "failed"}") }
        }
    }

    internal fun onPhotoTaken(taken: Boolean) {
        val file = pendingPhoto ?: return
        if (taken && file.length() > 0) picked = MediaFiles.Picked(file.absolutePath, "image/jpeg") else file.delete()
    }

    /** Sends the picked file with the draft text as its caption. */
    fun sendPicked() {
        val file = picked ?: return
        model.sendFile(file.path, file.mime, model.takeDraft())
        picked = null
        overlay = null
    }

    // endregion

    // region Voice notes

    fun openVoice() {
        discardRecording()
        overlay = MediaOverlay.Voice
    }

    fun record() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
        beginRecording()
    }

    internal fun beginRecording() {
        discardRecording()
        val next = VoiceRecorder(context, voiceMime)
        runCatching { next.start() }
            .onSuccess { recorder = next; voiceStage = VoiceStage.RECORDING }
            .onFailure { toast("Couldn't record: ${it.message ?: "failed"}") }
    }

    /** Ends the recording and moves to review; too-short recordings are dropped. */
    fun stopRecording() {
        val done = recorder?.stop(keep = true)
        recorder = null
        recording = done
        voiceStage = if (done != null) VoiceStage.REVIEW else VoiceStage.READY
        if (done == null) toast("That was too short")
    }

    fun toggleReview() {
        val (file, _) = recording ?: return
        if (AudioPlayer.playing == DRAFT_ID) AudioPlayer.stop()
        else runCatching { AudioPlayer.play(DRAFT_ID, file.absolutePath) }.onFailure { toast("Couldn't play it") }
    }

    fun sendVoice() {
        val (file, duration) = recording ?: return
        AudioPlayer.stop()
        model.sendVoiceNote(file.absolutePath, voiceMime, duration)
        recording = null
        voiceStage = VoiceStage.READY
        overlay = null
    }

    /** Drops a recording in progress or under review. */
    fun discardRecording() {
        AudioPlayer.stop()
        recorder?.stop(keep = false)
        recorder = null
        recording?.first?.delete()
        recording = null
        voiceStage = VoiceStage.READY
    }

    fun cancelVoice() {
        discardRecording()
        overlay = null
    }

    // endregion

    companion object {
        /** Id of the draft recording while it plays in review. */
        const val DRAFT_ID = "voice-draft"
    }
}

/** Creates the controller and its system pickers for the open chat. */
@Composable
fun rememberMediaController(model: ChatModel, voiceMime: String): MediaController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(model) { MediaController(context, model, scope) }
    controller.voiceMime = voiceMime
    controller.filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), controller::onFilePicked)
    controller.camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), controller::onPhotoTaken)
    controller.microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.beginRecording()
    }
    DisposableEffect(controller) { onDispose { controller.discardRecording() } }
    return controller
}
