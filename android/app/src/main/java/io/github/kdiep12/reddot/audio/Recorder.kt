package io.github.kdiep12.reddot.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Microphone -> a WAV file, written while you speak: 16 kHz, 16-bit, mono, the format the
 * server's Whisper and the web app's speech detector use (see CLAUDE.md, "Audio format").
 * 32 kB per second, about 1.9 MB per minute.
 *
 *     start(file)  opens the mic; a thread reads 100 ms at a time, appends it to the file
 *                  and reports its loudness (0..1) for the dot's glow
 *     stop()       closes the mic and fills in the WAV header's sizes
 *
 * The header is first written with "unknown" sizes, so even a file cut off by a crash is
 * readable (repairWav fixes it up properly).
 */
class Recorder {
    data class Result(val file: File, val seconds: Double, val silent: Boolean)

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var file: File? = null
    @Volatile private var samples = 0L
    @Volatile private var peak = 0
    private val main = Handler(Looper.getMainLooper())

    val isRecording: Boolean get() = running

    /** False if the microphone couldn't be opened (no permission, or another app is using it). */
    @SuppressLint("MissingPermission") // callers check RECORD_AUDIO first
    fun start(output: File, onLevel: (Float) -> Unit): Boolean {
        if (running) return false
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            // VOICE_RECOGNITION: the microphone tuned for speech recognition (no music-style processing).
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, CHUNK_SAMPLES * 2 * 4))
        } catch (_: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        output.parentFile?.mkdirs()
        val out = RandomAccessFile(output, "rw")
        out.setLength(0)
        out.write(header(UNKNOWN_SIZE))
        try {
            rec.startRecording()
        } catch (_: IllegalStateException) {
            rec.release()
            out.close()
            return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            out.close()
            return false
        }
        samples = 0
        peak = 0
        running = true
        record = rec
        file = output
        thread = Thread({ readLoop(rec, out, onLevel) }, "reddot-recorder").also { it.start() }
        return true
    }

    private fun readLoop(rec: AudioRecord, out: RandomAccessFile, onLevel: (Float) -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ShortArray(CHUNK_SAMPLES)
        val bytes = ByteArray(CHUNK_SAMPLES * 2)
        try {
            while (running) {
                val n = rec.read(buffer, 0, buffer.size)
                if (n < 0) break          // an error: the mic went away
                if (n == 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val s = buffer[i].toInt()
                    sum += (s * s).toDouble()
                    if (abs(s) > peak) peak = abs(s)
                    bytes[2 * i] = (s and 0xff).toByte()            // WAV is little-endian
                    bytes[2 * i + 1] = ((s shr 8) and 0xff).toByte()
                }
                out.write(bytes, 0, n * 2)
                samples += n
                // Loudness for the glow: speech is roughly 1000..5000 on the 16-bit scale.
                val level = sqrt((sqrt(sum / n) / 4000.0).coerceIn(0.0, 1.0)).toFloat()
                main.post { onLevel(level) }
            }
        } finally {
            out.seek(0)
            out.write(header(samples * 2))
            out.close()
        }
    }

    fun stop(): Result? {
        if (!running) return null
        running = false
        val rec = record
        try {
            rec?.stop()             // read() returns, the loop ends and writes the header
        } catch (_: IllegalStateException) {
        }
        thread?.join(2000)
        rec?.release()
        record = null
        thread = null
        val f = file ?: return null
        file = null
        // Every sample exactly 0: Android gave this app silence instead of the microphone.
        return Result(f, samples.toDouble() / SAMPLE_RATE, silent = peak == 0)
    }

    fun cancel() {
        stop()?.file?.parentFile?.deleteRecursively()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = 1600   // 100 ms, like the web app's chunks
        private const val UNKNOWN_SIZE = 0xFFFFFFFFL - 36

        private fun header(dataBytes: Long): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)                  // size of the fmt chunk
            putShort(1)                 // PCM
            putShort(1)                 // mono
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2)     // bytes per second
            putShort(2)                 // bytes per sample
            putShort(16)                // bits per sample
            put("data".toByteArray())
            putInt(dataBytes.toInt())
        }.array()

        /** Fills in the header of a WAV file cut off while recording; returns its length in seconds. */
        fun repairWav(file: File): Double? {
            if (!file.exists() || file.length() <= 44) return null
            val dataBytes = (file.length() - 44) / 2 * 2
            RandomAccessFile(file, "rw").use {
                it.seek(0)
                it.write(header(dataBytes))
            }
            return dataBytes / 2.0 / SAMPLE_RATE
        }
    }
}
