package io.github.kdiep12.reddot.notes

import io.github.kdiep12.reddot.audio.Recorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A note's life:
 *
 *     RECORDED --> TRANSCRIBING --> DONE
 *                       |
 *                       +--> WAITING  the server was unreachable: TranscribeWorker tries again
 *                       |             when there is a network
 *                       +--> FAILED   the server refused it (bad audio, wrong token): "Try again"
 */
enum class NoteStatus { RECORDED, TRANSCRIBING, WAITING, DONE, FAILED }

data class Note(
    val id: String,                    // a UUID, also the note's folder name and its id on the server
    val recordedAt: String,            // ISO 8601 with the phone's UTC offset, when recording started
    val seconds: Double,
    val source: String,                // "bubble" (in another app) or "app" (the big dot)
    val targetApp: String? = null,     // the app the bubble dictated into, e.g. "com.anthropic.claude"
    val status: NoteStatus = NoteStatus.RECORDED,
    val text: String? = null,          // what the server returned
    val editedText: String? = null,    // your correction; the server learns from it
    val inserted: Boolean = false,     // the bubble put the text into the field
    val error: String? = null,
    val language: String? = null,
) {
    /** What to show and copy: your correction if there is one. */
    val shownText: String get() = editedText ?: text.orEmpty()

    val instant: Instant get() = runCatching { OffsetDateTime.parse(recordedAt).toInstant() }.getOrDefault(Instant.EPOCH)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("recordedAt", recordedAt)
        put("seconds", seconds)
        put("source", source)
        put("targetApp", targetApp ?: JSONObject.NULL)
        put("status", status.name)
        put("text", text ?: JSONObject.NULL)
        put("editedText", editedText ?: JSONObject.NULL)
        put("inserted", inserted)
        put("error", error ?: JSONObject.NULL)
        put("language", language ?: JSONObject.NULL)
    }

    companion object {
        fun now(): String = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()

        fun fromJson(o: JSONObject) = Note(
            id = o.getString("id"),
            recordedAt = o.getString("recordedAt"),
            seconds = o.optDouble("seconds", 0.0),
            source = o.optString("source", "app"),
            targetApp = o.stringOrNull("targetApp"),
            status = runCatching { NoteStatus.valueOf(o.getString("status")) }.getOrDefault(NoteStatus.WAITING),
            text = o.stringOrNull("text"),
            editedText = o.stringOrNull("editedText"),
            inserted = o.optBoolean("inserted", false),
            error = o.stringOrNull("error"),
            language = o.stringOrNull("language"),
        )

        private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key)
    }
}

/**
 * Every note on the phone, one folder each:  files/notes/<id>/note.json + audio.wav
 *
 * The audio file is written while you speak and note.json as soon as you stop, before any
 * network call, so a note survives a dead server, no signal, or the app being killed (PRD G1).
 * The list is a StateFlow: screens redraw by themselves when a note changes. Thread-safe.
 */
class NoteStore(private val dir: File) {
    private val lock = Any()
    private val _notes = MutableStateFlow(loadAll())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    fun audioFile(id: String): File = File(File(dir, id), "audio.wav")

    fun get(id: String): Note? = _notes.value.firstOrNull { it.id == id }

    fun save(note: Note) = synchronized(lock) {
        val folder = File(dir, note.id).apply { mkdirs() }
        val tmp = File(folder, "note.json.tmp")
        tmp.writeText(note.toJson().toString(1))
        tmp.renameTo(File(folder, "note.json"))   // atomic: never half a file
        _notes.value = (_notes.value.filter { it.id != note.id } + note).sortedByDescending { it.instant }
    }

    fun update(id: String, change: (Note) -> Note): Note? = synchronized(lock) {
        get(id)?.let(change)?.also { save(it) }
    }

    fun delete(id: String) = synchronized(lock) {
        File(dir, id).deleteRecursively()
        _notes.value = _notes.value.filter { it.id != id }
    }

    /** Notes still to be sent to the server, oldest first. */
    fun waiting(): List<Note> =
        _notes.value.filter { it.status == NoteStatus.RECORDED || it.status == NoteStatus.WAITING }.sortedBy { it.instant }

    private fun loadAll(): List<Note> {
        dir.mkdirs()
        return dir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { folder ->
            val json = File(folder, "note.json")
            if (json.exists()) {
                runCatching { Note.fromJson(JSONObject(json.readText())) }.getOrNull()
                    // The app stopped in the middle of a request: send it again.
                    ?.let { if (it.status == NoteStatus.TRANSCRIBING) it.copy(status = NoteStatus.WAITING) else it }
            } else {
                recover(folder)
            }
        }.sortedByDescending { it.instant }
    }

    /** A folder with audio but no note.json: the app was killed while recording. Keep what was said. */
    private fun recover(folder: File): Note? {
        val audio = File(folder, "audio.wav")
        val seconds = Recorder.repairWav(audio) ?: return null
        if (seconds < 0.5) {
            folder.deleteRecursively()
            return null
        }
        val note = Note(
            id = folder.name,
            recordedAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(audio.lastModified()), ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.SECONDS).toString(),
            seconds = seconds,
            source = "app",
            status = NoteStatus.WAITING,
            error = "Recovered after the app stopped while recording",
        )
        File(folder, "note.json").writeText(note.toJson().toString(1))
        return note
    }
}
