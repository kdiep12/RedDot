package io.github.kdiep12.reddot

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/** What you set, in SharedPreferences. Read fresh every time: the bubble service and the
 *  screens share it, and it is only a handful of values. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("reddot", Context.MODE_PRIVATE)
    private val _serverChanges = MutableStateFlow(0)

    /** Counts changes to the server address or token: screens that show the server's state
     *  check it again when this changes. */
    val serverChanges: StateFlow<Int> = _serverChanges.asStateFlow()

    /** The RedDot server, e.g. "http://100.101.102.103:8090". Empty: not set up yet. */
    var serverUrl: String
        get() = prefs.getString("server_url", null) ?: normalizeServerUrl(BuildConfig.DEFAULT_SERVER_URL)
        set(value) {
            prefs.edit { putString("server_url", normalizeServerUrl(value)) }
            _serverChanges.value++
        }

    /** Only needed when the server isn't reached through Tailscale (its DICTATION_TOKEN). */
    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) {
            prefs.edit { putString("token", value.trim()) }
            _serverChanges.value++
        }

    /** The language you speak, "en", "vi"..., or "" to let Whisper detect it for every note.
     *  Detection can guess wrong on short notes, so a fixed language is the default. */
    var language: String
        get() = prefs.getString("language", "en") ?: "en"
        set(value) = prefs.edit { putString("language", value) }

    /** Show the bubble in other apps at all (the accessibility service stays on either way). */
    var bubbleEnabled: Boolean
        get() = prefs.getBoolean("bubble_enabled", true)
        set(value) = prefs.edit { putBoolean("bubble_enabled", value) }

    /** Only while the keyboard is up (otherwise whenever a text field has focus). */
    var bubbleOnlyWithKeyboard: Boolean
        get() = prefs.getBoolean("bubble_only_with_keyboard", true)
        set(value) = prefs.edit { putBoolean("bubble_only_with_keyboard", value) }

    /** A short buzz when recording starts and a double one when it stops: eyes-free feedback. */
    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(value) = prefs.edit { putBoolean("haptics", value) }

    /** Where you dragged the bubble: which screen edge, and its top as a share of the screen
     *  height (0 = top, 1 = bottom). */
    var bubbleOnLeft: Boolean
        get() = prefs.getBoolean("bubble_on_left", false)
        set(value) = prefs.edit { putBoolean("bubble_on_left", value) }
    var bubbleY: Float
        get() = prefs.getFloat("bubble_y", 0.42f)
        set(value) = prefs.edit { putFloat("bubble_y", value.coerceIn(0f, 1f)) }

    companion object {
        const val DEFAULT_PORT = 8090

        /** "100.101.102.103" -> "http://100.101.102.103:8090": what people type on a phone keyboard,
         *  turned into a full address. https addresses keep their own (default) port. */
        fun normalizeServerUrl(input: String): String {
            var text = input.trim().trimEnd('/')
            if (text.isEmpty()) return ""
            if (!text.contains("://")) text = "http://$text"
            return try {
                val uri = URI(text)
                val host = uri.host ?: return text
                val port = if (uri.port == -1 && uri.scheme == "http") DEFAULT_PORT else uri.port
                URI(uri.scheme, null, host, port, uri.path?.trimEnd('/')?.ifEmpty { null }, null, null).toString()
            } catch (_: Exception) {
                text
            }
        }
    }
}
