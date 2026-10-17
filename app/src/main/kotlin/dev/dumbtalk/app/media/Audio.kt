package dev.dumbtalk.app.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Plays one voice note or audio file at a time. */
object AudioPlayer {
    /** Id of the message playing, for "Stop" labels. */
    var playing by mutableStateOf<String?>(null)
        private set
    private var player: MediaPlayer? = null
    val positionMillis: Long get() = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
    val durationMillis: Long get() = runCatching { player?.duration?.toLong() ?: 0L }.getOrDefault(0L)

    fun play(messageId: String, path: String) {
        stop()
        val next = MediaPlayer()
        try {
            next.setDataSource(path)
            next.setOnCompletionListener { stop() }
            next.prepare()
            next.start()
            player = next
            playing = messageId
        } catch (failure: Exception) {
            next.release()
            throw failure
        }
    }

    fun stop() {
        player?.release()
        player = null
        playing = null
    }
}

/**
 * Records a voice note in the format the service wants: Ogg/Opus for
 * WhatsApp and Telegram, AAC for Signal.
 */
class VoiceRecorder(private val context: Context, private val mime: String) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var started = 0L

    val elapsedMillis: Long get() = if (recorder == null) 0 else SystemClock.elapsedRealtime() - started

    fun start() {
        val aac = mime == "audio/aac"
        val out = File(File(context.cacheDir, "outgoing").apply { mkdirs() }, "voice-${System.currentTimeMillis()}.${if (aac) "aac" else "ogg"}")
        recorder = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            if (aac) {
                setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            } else {
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            }
            setAudioSamplingRate(48_000)
            setAudioEncodingBitRate(32_000)
            setOutputFile(out)
            prepare()
            start()
        }
        file = out
        started = SystemClock.elapsedRealtime()
    }

    /** Stops and returns the recording and its length, or null if cancelled or too short. */
    fun stop(keep: Boolean): Pair<File, Long>? {
        val duration = elapsedMillis
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        val out = file ?: return null
        if (!keep || duration < 700) {
            out.delete()
            return null
        }
        return out to duration
    }
}
