package io.github.kdiep12.reddot.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kdiep12.reddot.Dictation
import io.github.kdiep12.reddot.RedDotApp
import io.github.kdiep12.reddot.bubble.BubbleView
import io.github.kdiep12.reddot.net.ServerClient
import io.github.kdiep12.reddot.notes.Note
import io.github.kdiep12.reddot.notes.NoteStatus
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------------- the home screen ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(app: RedDotApp, onSettings: () -> Unit, onWords: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val say: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    val context = LocalContext.current
    val recording = remember { AppRecording(app, context, say) }
    val notes by app.notes.notes.collectAsStateWithLifecycle()
    val setup = rememberSetup(app)
    var openNoteId by remember { mutableStateOf<String?>(null) }

    // Leaving the app ends a recording from the big dot; the note is saved and transcribed.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { recording.stop() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RedDot") },
                actions = {
                    TextButton(onClick = onWords) { Text("Words") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SetupCard(setup, onSettings) }
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BigDot(recording)
                    Text(recording.hint(), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (notes.isEmpty()) {
                item {
                    Text("Your notes appear here: from the dot above, and from the bubble in other apps.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            var lastDay: LocalDate? = null
            for (note in notes) {
                val day = note.localTime().toLocalDate()
                if (day != lastDay) {
                    lastDay = day
                    item(key = "day-$day") { DayHeader(day) }
                }
                item(key = note.id) { NoteRow(note) { openNoteId = note.id } }
            }
        }
    }

    val open = notes.firstOrNull { it.id == openNoteId }
    if (open != null) NoteSheet(app, open, onDismiss = { openNoteId = null })
}

private fun Note.localTime(): OffsetDateTime =
    runCatching { OffsetDateTime.parse(recordedAt).atZoneSameInstant(ZoneId.systemDefault()).toOffsetDateTime() }
        .getOrDefault(OffsetDateTime.now())

@Composable
private fun DayHeader(day: LocalDate) {
    val today = LocalDate.now()
    val label = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))
    }
    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun NoteRow(note: Note, onClick: () -> Unit) {
    val context = LocalContext.current
    val where = remember(note.targetApp) { note.targetApp?.let { appLabel(context, it) } }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(note.localTime().format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Text(listOfNotNull(where?.let { "in $it" }, "%.0f s".format(note.seconds)).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                StatusLabel(note)
            }
            if (note.shownText.isNotBlank()) {
                Text(note.shownText, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun StatusLabel(note: Note) {
    val (text, color) = when (note.status) {
        NoteStatus.RECORDED, NoteStatus.TRANSCRIBING -> "Transcribing…" to MaterialTheme.colorScheme.onSurfaceVariant
        NoteStatus.WAITING -> "Waiting for the server" to MaterialTheme.colorScheme.tertiary
        NoteStatus.FAILED -> "Not transcribed" to MaterialTheme.colorScheme.error
        NoteStatus.DONE -> (if (note.editedText != null) "Corrected" else "") to MaterialTheme.colorScheme.onSurfaceVariant
    }
    if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}

private fun appLabel(context: Context, packageName: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
}.getOrDefault(packageName.substringAfterLast('.'))

// ---------------- the big dot: recording a note in the app ----------------

/** Hold-to-talk and tap-to-toggle for the big dot, on top of [Dictation]. */
class AppRecording(private val app: RedDotApp, private val context: Context, private val say: (String) -> Unit) {
    enum class Mode { IDLE, HOLDING, TOGGLED, TRANSCRIBING }

    var mode by mutableStateOf(Mode.IDLE)
        private set
    var level by mutableFloatStateOf(0f)
        private set
    private var stopOnRelease = false

    fun hint(): String = when (mode) {
        Mode.IDLE -> "Hold the dot and speak, or tap to start and tap again to stop"
        Mode.HOLDING -> "Listening… let go to finish"
        Mode.TOGGLED -> "Listening… tap to finish"
        Mode.TRANSCRIBING -> "Transcribing…"
    }

    fun press() {
        when (mode) {
            Mode.IDLE -> when (app.dictation.start("app", null) { level = it }) {
                Dictation.StartResult.OK -> {
                    mode = Mode.HOLDING
                    stopOnRelease = false
                }
                Dictation.StartResult.NO_PERMISSION -> say("Allow the microphone first (see Set up RedDot above)")
                Dictation.StartResult.MIC_UNAVAILABLE -> say("The microphone is being used by another app")
                Dictation.StartResult.BUSY -> say("The bubble is recording right now")
            }
            Mode.TOGGLED -> stopOnRelease = true
            else -> {}
        }
    }

    fun release(heldMs: Long) {
        when {
            mode == Mode.HOLDING && heldMs >= BubbleView.HOLD_MS -> stop()
            mode == Mode.HOLDING -> mode = Mode.TOGGLED
            mode == Mode.TOGGLED && stopOnRelease -> stop()
        }
    }

    fun stop() {
        if (mode != Mode.HOLDING && mode != Mode.TOGGLED) return
        mode = Mode.TRANSCRIBING
        level = 0f
        app.dictation.stop { outcome ->
            mode = Mode.IDLE
            when (outcome) {
                Dictation.Outcome.TooShort -> {}
                is Dictation.Outcome.Silent -> say("The microphone recorded only silence")
                is Dictation.Outcome.NoWords -> say("No words heard")
                is Dictation.Outcome.Text -> {
                    copyText(context, outcome.note.text.orEmpty())
                    say("Transcribed and copied")
                }
                is Dictation.Outcome.Failed -> say(if (outcome.willRetry) "Saved. RedDot transcribes it once the server is reachable."
                                                  else outcome.message)
            }
        }
    }
}

@Composable
private fun BigDot(recording: AppRecording) {
    val mode = recording.mode
    val listening = mode == AppRecording.Mode.HOLDING || mode == AppRecording.Mode.TOGGLED
    val level by animateFloatAsState(recording.level, label = "level")
    val color by animateColorAsState(
        when {
            listening -> RedDotSpeech
            mode == AppRecording.Mode.TRANSCRIBING -> RedDotRed.copy(alpha = 0.7f)
            else -> RedDotRed
        }, label = "color")
    val angle = if (mode == AppRecording.Mode.TRANSCRIBING) {
        rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f,
            infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "angle").value
    } else 0f
    val current by rememberUpdatedState(recording)
    Canvas(
        Modifier
            .size(200.dp)
            .semantics {
                contentDescription = "Record a note. " + current.hint()
                onClick { current.press(); current.release(0); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val downAt = System.currentTimeMillis()
                    current.press()
                    waitForUpOrCancellation()
                    current.release(System.currentTimeMillis() - downAt)
                }
            },
    ) {
        val r = size.minDimension / 2 * 0.62f
        if (listening) drawCircle(Color(0xFFFF1E1E).copy(alpha = 0.25f + 0.35f * level), radius = r * (1.12f + 0.4f * level))
        drawCircle(color, radius = if (listening) r * 1.06f else r)
        if (mode == AppRecording.Mode.TOGGLED) {
            val s = r * 0.3f
            drawRect(Color.White, topLeft = center - Offset(s, s), size = Size(2 * s, 2 * s))
        }
        if (mode == AppRecording.Mode.TRANSCRIBING) {
            val a = r * 0.45f
            drawArc(Color.White, angle, 100f, useCenter = false, topLeft = center - Offset(a, a), size = Size(2 * a, 2 * a),
                style = Stroke(width = r * 0.09f, cap = StrokeCap.Round))
        }
    }
}

// ---------------- setup checklist ----------------

class Setup {
    var server by mutableStateOf("Checking the server…")
    var serverOk by mutableStateOf<Boolean?>(null)
    var micOk by mutableStateOf(false)
    var notificationsOk by mutableStateOf(false)
    var bubbleOk by mutableStateOf(false)
    val complete get() = serverOk == true && micOk && bubbleOk
}

@Composable
private fun rememberSetup(app: RedDotApp): Setup {
    val context = LocalContext.current
    val setup = remember { Setup() }
    val scope = rememberCoroutineScope()
    val serverChanges by app.settings.serverChanges.collectAsStateWithLifecycle()
    // Every time you come back to the app (from Android's settings, say), or change the server, look again.
    LifecycleResumeEffect(serverChanges) {
        setup.micOk = granted(context, Manifest.permission.RECORD_AUDIO)
        setup.notificationsOk = Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)
        setup.bubbleOk = bubbleServiceOn(context)
        val job = scope.launch {
            setup.serverOk = null
            setup.server = "Checking the server…"
            try {
                setup.server = "Server: " + app.server.check()
                setup.serverOk = true
            } catch (failure: ServerClient.Failure) {
                setup.server = failure.message.orEmpty()
                setup.serverOk = false
            }
        }
        onPauseOrDispose { job.cancel() }
    }
    return setup
}

private fun granted(context: Context, permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun bubbleServiceOn(context: Context): Boolean =
    context.getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == context.packageName }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupCard(setup: Setup, onSettings: () -> Unit) {
    val context = LocalContext.current
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { setup.micOk = it }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { setup.notificationsOk = it }
    if (setup.complete) {
        Text("${setup.server} · bubble on", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Set up RedDot", style = MaterialTheme.typography.titleMedium)
            SetupRow(setup.serverOk == true, if (setup.serverOk == false) setup.server else setup.server.removePrefix("Server: ")) {
                if (setup.serverOk == false) OutlinedButton(onClick = onSettings) { Text("Settings") }
            }
            SetupRow(setup.micOk, "Microphone") {
                if (!setup.micOk) Button(onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow") }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                SetupRow(setup.notificationsOk, "Notifications (optional): when notes recorded offline are transcribed") {
                    if (!setup.notificationsOk) OutlinedButton(onClick = { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
                }
            }
            SetupRow(setup.bubbleOk, "The voice bubble in other apps") {}
            if (!setup.bubbleOk) {
                Text("In Accessibility, open RedDot voice bubble and turn it on. If Android says the setting is restricted: " +
                     "open App info, tap ⋮ in the top corner, choose Allow restricted settings, then try again.",
                    style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility") }
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)))
                    }) { Text("App info") }
                }
            }
        }
    }
}

@Composable
private fun SetupRow(ok: Boolean, label: String, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Filled.Check else Icons.Filled.Warning, contentDescription = if (ok) "Done" else "To do",
            tint = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        action()
    }
}

// ---------------- one note: read, correct, play, share ----------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NoteSheet(app: RedDotApp, note: Note, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(note.id, note.shownText) { mutableStateOf(note.shownText) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Messages for this sheet appear in it: the screen's snackbar would be hidden behind it.
    var message by remember(note.id) { mutableStateOf<String?>(null) }
    val say: (String) -> Unit = { message = it }
    DisposableEffect(note.id) { onDispose { player?.release() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(note.localTime().format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")) + " · %.0f s".format(note.seconds),
                style = MaterialTheme.typography.titleSmall)
            if (note.status != NoteStatus.DONE) {
                Text(note.error ?: "Transcribing…", style = MaterialTheme.typography.bodySmall,
                    color = if (note.status == NoteStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Transcript") },
                modifier = Modifier.fillMaxWidth(), minLines = 3)
            if (note.status == NoteStatus.DONE && draft.trim() != note.shownText.trim()) {
                Button(onClick = {
                    val before = note.shownText
                    val corrected = draft.trim()
                    app.notes.update(note.id) { it.copy(editedText = corrected) }
                    scope.launch {
                        try {
                            val learned = app.server.correction(note.id, before, corrected)
                            val news = learned.newWords + learned.newReplacements.map { it.second }
                            say(if (news.isEmpty()) "Saved" else "Saved. RedDot learned: " + news.distinct().joinToString(", "))
                        } catch (failure: ServerClient.Failure) {
                            say("Saved on the phone. The server couldn't learn from it: ${failure.message}")
                        }
                    }
                }) { Text("Save correction") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    player?.let {
                        it.release()
                        player = null
                        return@TextButton
                    }
                    player = runCatching {
                        MediaPlayer().apply {
                            setDataSource(app.notes.audioFile(note.id).path)
                            setOnCompletionListener { mp -> mp.release(); player = null }
                            prepare()
                            start()
                        }
                    }.getOrNull()
                }) { Text(if (player != null) "Stop" else "Play") }
                TextButton(onClick = { copyText(context, draft); say("Copied") }) { Text("Copy") }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, draft)
                    context.startActivity(Intent.createChooser(send, null))
                }) { Text("Share") }
                if (note.status == NoteStatus.WAITING || note.status == NoteStatus.FAILED) {
                    TextButton(onClick = {
                        scope.launch {
                            when (val outcome = app.dictation.transcribe(note)) {
                                is Dictation.Outcome.Failed -> say(outcome.message)
                                is Dictation.Outcome.NoWords -> say("No words heard")
                                else -> say("Transcribed")
                            }
                        }
                    }) { Text("Try again") }
                }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this note?") },
            text = { Text("Its text and recording are removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDismiss()
                    app.notes.delete(note.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("RedDot note", text))
}
