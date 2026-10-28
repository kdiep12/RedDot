package io.github.kdiep12.reddot.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.kdiep12.reddot.RedDotApp
import io.github.kdiep12.reddot.Settings
import io.github.kdiep12.reddot.TranscribeWorker

/** The app's one screen host: home (dot + notes), settings, and the word list. */
class MainActivity : ComponentActivity() {
    /** A reddot://setup link waiting for confirmation: (server, token). */
    private var setupLink by mutableStateOf<Pair<String, String?>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readSetupLink(intent)
        val app = RedDotApp.of(this)
        setContent {
            RedDotTheme {
                Screens(app)
                setupLink?.let { (server, token) ->
                    // Asked first: otherwise any web page could send your recordings to its own server.
                    AlertDialog(
                        onDismissRequest = { setupLink = null },
                        title = { Text("Use this server?") },
                        text = { Text("RedDot will send your recordings to ${Settings.normalizeServerUrl(server)} for transcription.") },
                        confirmButton = {
                            TextButton(onClick = {
                                app.settings.serverUrl = server
                                if (token != null) app.settings.token = token
                                setupLink = null
                                TranscribeWorker.enqueue(this)
                                Toast.makeText(this, "Server set", Toast.LENGTH_SHORT).show()
                            }) { Text("Use it") }
                        },
                        dismissButton = { TextButton(onClick = { setupLink = null }) { Text("Cancel") } },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readSetupLink(intent)
    }

    private fun readSetupLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "reddot" || uri.host != "setup") return
        val server = uri.getQueryParameter("server") ?: return
        setupLink = server to uri.getQueryParameter("token")
    }
}

@Composable
private fun Screens(app: RedDotApp) {
    var page by rememberSaveable { mutableStateOf("home") }
    BackHandler(enabled = page != "home") { page = "home" }
    when (page) {
        "settings" -> SettingsScreen(app, onBack = { page = "home" })
        "words" -> WordsScreen(app, onBack = { page = "home" })
        else -> HomeScreen(app, onSettings = { page = "settings" }, onWords = { page = "words" })
    }
}
