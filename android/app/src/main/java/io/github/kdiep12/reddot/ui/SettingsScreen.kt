package io.github.kdiep12.reddot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.kdiep12.reddot.BuildConfig
import io.github.kdiep12.reddot.RedDotApp
import io.github.kdiep12.reddot.TranscribeWorker
import io.github.kdiep12.reddot.net.ServerClient
import kotlinx.coroutines.launch

private val LANGUAGES = listOf(
    "en" to "English",
    "vi" to "Vietnamese",
    "" to "Detect for every note (can guess wrong on short notes)",
    "de" to "German",
    "fr" to "French",
    "es" to "Spanish",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: RedDotApp, onBack: () -> Unit) {
    val settings = app.settings
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(settings.serverUrl) }
    var token by remember { mutableStateOf(settings.token) }
    var check by remember { mutableStateOf<String?>(null) }
    var language by remember { mutableStateOf(settings.language) }
    var bubbleEnabled by remember { mutableStateOf(settings.bubbleEnabled) }
    var onlyWithKeyboard by remember { mutableStateOf(settings.bubbleOnlyWithKeyboard) }
    var haptics by remember { mutableStateOf(settings.haptics) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Server", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = server, onValueChange = { server = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Server address") },
                supportingText = { Text("Your PC's Tailscale address, e.g. 100.101.102.103. Port 8090 is added for you.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Token (optional)") },
                supportingText = { Text("Only needed if the server isn't reached through Tailscale.") },
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(onClick = {
                settings.serverUrl = server
                settings.token = token
                server = settings.serverUrl
                check = "Checking…"
                scope.launch {
                    check = try {
                        "Connected: " + app.server.check().also { TranscribeWorker.enqueue(app) }
                    } catch (failure: ServerClient.Failure) {
                        failure.message
                    }
                }
            }) { Text("Save and test") }
            check?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            HorizontalDivider()
            Text("Language you speak", style = MaterialTheme.typography.titleMedium)
            for ((code, name) in LANGUAGES) {
                Row(
                    Modifier.fillMaxWidth().selectable(selected = language == code, onClick = {
                        language = code
                        settings.language = code
                    }).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = language == code, onClick = null)
                    Text(name, Modifier.padding(start = 12.dp))
                }
            }

            HorizontalDivider()
            Text("Bubble", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Show the red dot in other apps", bubbleEnabled) { bubbleEnabled = it; settings.bubbleEnabled = it }
            SwitchRow("Only while the keyboard is open", onlyWithKeyboard) { onlyWithKeyboard = it; settings.bubbleOnlyWithKeyboard = it }
            SwitchRow("Vibrate when recording starts and stops", haptics) { haptics = it; settings.haptics = it }
            Text("Hold the dot and speak, then let go. Or tap it, speak, and tap again. Drag it to move it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider()
            Text("RedDot ${BuildConfig.VERSION_NAME}. Notes stay on this phone until you delete them. Recordings go only to " +
                 "the server above, which transcribes them with Whisper.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
}

/** The server's vocabulary.txt and replacements.txt: add and remove words. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordsScreen(app: RedDotApp, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var vocabulary by remember { mutableStateOf<ServerClient.Vocabulary?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var newWord by remember { mutableStateOf("") }

    fun load(block: suspend () -> ServerClient.Vocabulary) {
        scope.launch {
            try {
                vocabulary = block()
                problem = null
            } catch (failure: ServerClient.Failure) {
                problem = failure.message
            }
        }
    }
    LaunchedEffect(Unit) { load { app.server.vocabulary() } }

    Scaffold(topBar = { TopAppBar(title = { Text("Words RedDot knows") }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("Names, jargon, species, site codes, written the way you want them. Whisper gets them as hints. " +
                     "RedDot also adds words itself when you correct a note.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = newWord, onValueChange = { newWord = it }, singleLine = true,
                        label = { Text("New word or phrase") }, modifier = Modifier.weight(1f))
                    Button(onClick = {
                        val word = newWord.trim()
                        if (word.isNotEmpty()) load { app.server.changeVocabulary(add = listOf(word)) }
                        newWord = ""
                    }, modifier = Modifier.padding(start = 8.dp)) { Text("Add") }
                }
            }
            problem?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            val words = vocabulary?.words.orEmpty()
            items(words, key = { "w-$it" }) { word ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(word, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { load { app.server.changeVocabulary(remove = listOf(word)) } }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove $word")
                    }
                }
            }
            val fixes = vocabulary?.replacements.orEmpty()
            if (fixes.isNotEmpty()) {
                item {
                    Text("Automatic fixes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    Text("Learned from corrections you made more than once.", style = MaterialTheme.typography.bodySmall)
                }
                items(fixes, key = { "r-${it.first}" }) { (heard, meant) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("“$heard” becomes “$meant”", Modifier.weight(1f))
                        IconButton(onClick = { load { app.server.changeVocabulary(removeReplacements = listOf(heard)) } }) {
                            Icon(Icons.Filled.Close, contentDescription = "Stop replacing $heard")
                        }
                    }
                }
            }
        }
    }
}
