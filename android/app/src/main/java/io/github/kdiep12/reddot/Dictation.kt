package io.github.kdiep12.reddot

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.kdiep12.reddot.audio.Recorder
import io.github.kdiep12.reddot.net.ServerClient
import io.github.kdiep12.reddot.notes.Note
import io.github.kdiep12.reddot.notes.NoteStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * One recording at a time, from the bubble or the big dot:
 *
 *     start()  --> the mic is on, audio goes straight into the note's file
 *     stop()   --> the note is saved on the phone (before any network call)
 *              --> transcribe(): the server's text, or WAITING if it can't be reached
 *              --> the caller gets an [Outcome] (the bubble types the text, the app copies it)
 */
class Dictation(private val app: RedDotApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recorder = Recorder()
    private var current: Started? = null

    private data class Started(val id: String, val recordedAt: String, val source: String, val targetApp: String?)

    enum class StartResult { OK, NO_PERMISSION, MIC_UNAVAILABLE, BUSY }

    sealed interface Outcome {
        data object TooShort : Outcome
        data class Silent(val note: Note) : Outcome
        data class Text(val note: Note) : Outcome
        data class NoWords(val note: Note) : Outcome
        data class Failed(val note: Note, val willRetry: Boolean, val message: String) : Outcome
    }

    val isRecording: Boolean get() = current != null

    /** Who is recording right now: "bubble", "app", or null. */
    val recordingSource: String? get() = current?.source

    fun start(source: String, targetApp: String?, onLevel: (Float) -> Unit): StartResult {
        if (current != null) return StartResult.BUSY
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return StartResult.NO_PERMISSION
        }
        val id = UUID.randomUUID().toString()
        if (!recorder.start(app.notes.audioFile(id), onLevel)) return StartResult.MIC_UNAVAILABLE
        current = Started(id, Note.now(), source, targetApp)
        return StartResult.OK
    }

    /** Ends the recording; [onOutcome] is called on the main thread once there is a result. */
    fun stop(onOutcome: (Outcome) -> Unit) {
        val started = current ?: return onOutcome(Outcome.TooShort)
        current = null
        val result = recorder.stop() ?: return onOutcome(Outcome.TooShort)
        if (result.seconds < MIN_SECONDS) {
            result.file.parentFile?.deleteRecursively()
            onOutcome(Outcome.TooShort)
            return
        }
        var note = Note(id = started.id, recordedAt = started.recordedAt, seconds = result.seconds,
            source = started.source, targetApp = started.targetApp)
        if (result.silent) {
            note = note.copy(status = NoteStatus.FAILED, error = "The microphone recorded only silence")
            app.notes.save(note)
            onOutcome(Outcome.Silent(note))
            return
        }
        app.notes.save(note)
        scope.launch { onOutcome(transcribe(note)) }
    }

    fun cancel() {
        current = null
        recorder.cancel()
    }

    /** Sends a saved note to the server and records the result in the note. */
    suspend fun transcribe(note: Note, queueOnFailure: Boolean = true): Outcome {
        app.notes.save(note.copy(status = NoteStatus.TRANSCRIBING, error = null))
        return try {
            val result = app.server.transcribe(note, app.notes.audioFile(note.id))
            val done = note.copy(status = NoteStatus.DONE, text = result.text, language = result.language, error = null)
            app.notes.save(done)
            if (result.text.isBlank()) Outcome.NoWords(done) else Outcome.Text(done)
        } catch (failure: ServerClient.Failure) {
            val saved = note.copy(status = if (failure.retry) NoteStatus.WAITING else NoteStatus.FAILED, error = failure.message)
            app.notes.save(saved)
            if (failure.retry && queueOnFailure && failure !is ServerClient.Failure.NotSetUp) TranscribeWorker.enqueue(app)
            Outcome.Failed(saved, failure.retry, failure.message.orEmpty())
        }
    }

    companion object {
        const val MIN_SECONDS = 0.4   // shorter: a slip of the finger, not a note
    }
}
