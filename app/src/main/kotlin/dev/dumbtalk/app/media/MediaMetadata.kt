package dev.dumbtalk.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.LruCache
import java.nio.ByteOrder
import kotlin.math.abs

/** Decode off the UI thread; previews never change a bubble's reserved dimensions. */
object MediaMetadata {
    private val videos = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    data class Audio(val duration: Long, val peaks: List<Int>)
    private val audio = LruCache<String, Audio>(100)

    fun encodedThumbnail(encoded: String): Bitmap? = runCatching {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    fun video(path: String): Bitmap? = videos.get(path) ?: MediaMetadataRetriever().let { reader ->
        try {
            reader.setDataSource(path)
            reader.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 320)
                ?.also { videos.put(path, it) }
        } finally { reader.release() }
    }

    /** Real PCM peaks, reduced into 48 columns; no synthetic waveform. */
    fun audio(path: String): Audio = audio.get(path) ?: decodeAudio(path).also { audio.put(path, it) }

    private fun decodeAudio(path: String): Audio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val peaks = IntArray(48)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (idle < 100) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(index))
                        val length = extractor.readSampleData(buffer, 0)
                        if (length < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, length, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    idle = 0
                    val buffer = decoder.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val samples = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        var peak = 0
                        while (samples.hasRemaining()) peak = maxOf(peak, abs(samples.get().toInt()))
                        val bucket = if (durationUs > 0) (info.presentationTimeUs * peaks.size / durationUs).toInt().coerceIn(peaks.indices) else 0
                        peaks[bucket] = maxOf(peaks[bucket], peak)
                    }
                    decoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else idle++
            }
            val max = peaks.maxOrNull()?.coerceAtLeast(1) ?: 1
            return Audio(durationUs / 1000, peaks.map { it * 255 / max })
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }
}
