package io.github.kdiep12.reddot

import android.app.Application
import android.content.Context
import io.github.kdiep12.reddot.bubble.FieldFinder
import io.github.kdiep12.reddot.net.ServerClient
import io.github.kdiep12.reddot.notes.NoteStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * The app's single shared state. The bubble (an accessibility service) and the screens run in
 * the same process, so they share these objects instead of talking to each other:
 *
 *     settings  what you set: server, language, bubble behaviour
 *     notes     every recording, kept on the phone until you delete it
 *     server    the RedDot server (Whisper + learning), over HTTP
 *     dictation record -> save -> transcribe, for the bubble and for the big dot alike
 *     lastField what the bubble last found as the field you type in (no text): Settings shows
 *               it, for when the dot doesn't appear in some app
 */
class RedDotApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var notes: NoteStore
        private set
    lateinit var server: ServerClient
        private set
    lateinit var dictation: Dictation
        private set
    val lastField = MutableStateFlow<FieldFinder.Report?>(null)

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        notes = NoteStore(File(filesDir, "notes"))
        server = ServerClient(settings)
        dictation = Dictation(this)
        Notifications.createChannel(this)
        // Notes recorded while the server was unreachable, from an earlier run: try again.
        if (notes.waiting().isNotEmpty()) TranscribeWorker.enqueue(this)
    }

    companion object {
        fun of(context: Context): RedDotApp = context.applicationContext as RedDotApp
    }
}
