package io.github.kdiep12.reddot.net

import io.github.kdiep12.reddot.Settings
import io.github.kdiep12.reddot.notes.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The RedDot server's HTTP API (redDotServer/dictation_api.py). Every call runs on the IO
 * dispatcher and either returns its result or throws a [Failure] that says whether trying
 * again later makes sense.
 */
class ServerClient(private val settings: Settings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)     // no answer in 6 s: treat the server as unreachable
        .writeTimeout(60, TimeUnit.SECONDS)      // uploading a long note on a slow mobile connection
        .readTimeout(120, TimeUnit.SECONDS)      // Whisper on a CPU can take a while for a long note
        .build()

    sealed class Failure(message: String) : Exception(message) {
        /** Worth retrying later: no network, server down, Whisper still loading. */
        abstract val retry: Boolean

        class NotSetUp : Failure("No server set up yet: open RedDot's settings") {
            override val retry = true
        }

        class Unreachable(url: String) : Failure("Can't reach the server at $url") {
            override val retry = true
        }

        class Refused(val code: Int, detail: String) : Failure(detail) {
            override val retry = code >= 500
        }
    }

    data class Transcription(val text: String, val language: String?, val seconds: Double)
    data class Vocabulary(val words: List<String>, val replacements: List<Pair<String, String>>)
    data class Learned(val newWords: List<String>, val newReplacements: List<Pair<String, String>>)

    suspend fun transcribe(note: Note, audio: File): Transcription {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "${note.id}.wav", audio.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("response_format", "json")
            .addFormDataPart("note_id", note.id)
            .addFormDataPart("recorded_at", note.recordedAt)
        if (settings.language.isNotEmpty()) form.addFormDataPart("language", settings.language)
        val json = call(Request.Builder().url(url("/v1/audio/transcriptions")).post(form.build()))
        return Transcription(json.optString("text"), json.optString("language").ifEmpty { null }, json.optDouble("duration", 0.0))
    }

    /** "large-v3-turbo on cuda": checks the address, the access rules and Whisper in one go. */
    suspend fun check(): String {
        val health = call(Request.Builder().url(url("/healthz")))
        val transcription = health.optJSONObject("transcription")
            ?: throw Failure.Refused(404, "The server runs, but its transcription API is off (DICTATION_API=1)")
        call(Request.Builder().url(url("/v1/models")))   // 401 here: not allowed without a token
        val model = transcription.optString("model")
        val device = transcription.optString("device")
        return if (transcription.optBoolean("ready")) "$model on $device" else "$model (loads with the first note)"
    }

    suspend fun vocabulary(): Vocabulary = parseVocabulary(call(Request.Builder().url(url("/v1/vocabulary"))))

    suspend fun changeVocabulary(add: List<String> = emptyList(), remove: List<String> = emptyList(),
                                 removeReplacements: List<String> = emptyList()): Vocabulary {
        val body = JSONObject().put("add", JSONArray(add)).put("remove", JSONArray(remove))
            .put("remove_replacements", JSONArray(removeReplacements))
        return parseVocabulary(call(Request.Builder().url(url("/v1/vocabulary")).post(body.toJsonBody())))
    }

    /** Tell the server what a transcript should have been, so it learns your words. */
    suspend fun correction(noteId: String, original: String, corrected: String): Learned {
        val body = JSONObject().put("note_id", noteId).put("original", original).put("corrected", corrected)
        val json = call(Request.Builder().url(url("/v1/corrections")).post(body.toJsonBody()))
        return Learned(json.optJSONArray("new_words").strings(), json.optJSONArray("new_replacements").pairs())
    }

    private fun url(path: String): HttpUrl {
        val base = settings.serverUrl
        if (base.isEmpty()) throw Failure.NotSetUp()
        return (base + path).toHttpUrlOrNull() ?: throw Failure.Refused(0, "\"$base\" isn't a valid server address")
    }

    private suspend fun call(builder: Request.Builder): JSONObject = withContext(Dispatchers.IO) {
        if (settings.token.isNotEmpty()) builder.header("Authorization", "Bearer ${settings.token}")
        val request = builder.build()
        val response = try {
            http.newCall(request).execute()
        } catch (_: IOException) {
            throw Failure.Unreachable("${request.url.scheme}://${request.url.host}:${request.url.port}")
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull().orEmpty()
                throw Failure.Refused(it.code, detail.ifEmpty { "The server answered HTTP ${it.code}" })
            }
            try {
                JSONObject(text)
            } catch (_: JSONException) {
                throw Failure.Refused(it.code, "The server's answer wasn't JSON: is this a RedDot server?")
            }
        }
    }

    private fun JSONObject.toJsonBody() = toString().toRequestBody("application/json".toMediaType())

    private fun parseVocabulary(json: JSONObject) =
        Vocabulary(json.optJSONArray("words").strings(), json.optJSONArray("replacements").pairs())

    private fun JSONArray?.strings(): List<String> = this?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()

    private fun JSONArray?.pairs(): List<Pair<String, String>> = this?.let { a ->
        List(a.length()) { a.optJSONObject(it).let { o -> o.optString("heard") to o.optString("meant") } }
    }.orEmpty()
}
