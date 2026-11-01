package io.github.kdiep12.reddot.bubble

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import io.github.kdiep12.reddot.Dictation
import io.github.kdiep12.reddot.RedDotApp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The voice bubble in other apps, the way Wispr Flow does it on Android:
 *
 *   you tap a text field --> the keyboard opens --> the red dot appears beside it (FieldFinder)
 *   hold the dot and speak --> RedDot records (Dictation) --> the server transcribes
 *   --> the text goes into that field at the cursor (TextInserter), checked a moment later
 *
 * An accessibility service, because only those may see which field is focused in another app
 * and write into it. It draws the dot as an accessibility overlay, which needs no separate
 * "display over other apps" permission and never takes the focus away from the field.
 */
class BubbleService : AccessibilityService(), BubbleView.Listener {
    private lateinit var app: RedDotApp
    private lateinit var windowManager: WindowManager
    private lateinit var finder: FieldFinder
    private val main = Handler(Looper.getMainLooper())
    private var bubble: BubbleView? = null
    private var layout: WindowManager.LayoutParams? = null
    private var keyboardTop: Int? = null       // screen y of the keyboard's top edge, if it is open
    private var targetApp: String? = null      // the app the current recording is for
    private var busy = false                   // recording or transcribing: keep the dot on screen
    private var dragging = false
    private var dragX = 0f
    private var dragY = 0f

    private val refreshLater = Runnable { refresh() }
    private val autoStop = Runnable { if (bubble?.state == BubbleView.State.RECORDING) onStop() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        app = RedDotApp.of(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        finder = FieldFinder(this)
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (::finder.isInitialized) finder.onEvent(event)
        // One tap on a field sends a burst of events (click, focus, windows): look once they settle.
        main.removeCallbacks(refreshLater)
        main.postDelayed(refreshLater, SETTLE_MS)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    private fun shutDown() {
        main.removeCallbacksAndMessages(null)
        if (::app.isInitialized && app.dictation.recordingSource == SOURCE) app.dictation.cancel()
        busy = false
        removeBubble()
    }

    // ---------------- when to show the dot ----------------

    private fun keyboard(): AccessibilityWindowInfo? =
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    /** The field you're typing in, and what was found for Settings' diagnostics line. */
    private fun field(keyboard: AccessibilityWindowInfo?): FieldFinder.Field? {
        val field = finder.find(keyboard)
        // Kept in the app, not the service: Android reconnects the service now and then (after
        // an update, or while a test tool inspects the screen), and the line should survive that.
        finder.report?.let { app.lastField.value = it }
        return field
    }

    private fun refresh() {
        if (!::app.isInitialized) return
        val keyboard = keyboard()
        keyboardTop = keyboard?.let { Rect().also(it::getBoundsInScreen).top }
        val wanted = busy || (app.settings.bubbleEnabled && field(keyboard) != null &&
            (keyboard != null || !app.settings.bubbleOnlyWithKeyboard))
        if (wanted) showBubble() else removeBubble()
    }

    private fun showBubble() {
        val size = dp(BubbleView.SIZE_DP)
        val view = bubble
        if (view == null) {
            val params = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Never focusable: the text field keeps the focus and the keyboard stays open.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            place(params)
            val created = BubbleView(this, this)
            windowManager.addView(created, params)
            bubble = created
            layout = params
        } else if (!dragging) {
            layout?.let {
                place(it)
                windowManager.updateViewLayout(view, it)
            }
        }
    }

    private fun removeBubble() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        layout = null
        dragging = false
    }

    /** At the screen edge you dragged it to, at your height, but never under the keyboard. */
    private fun place(params: WindowManager.LayoutParams) {
        val size = dp(BubbleView.SIZE_DP)
        val (width, height) = screenSize()
        val margin = dp(4)
        params.x = if (app.settings.bubbleOnLeft) margin else width - size - margin
        var y = (app.settings.bubbleY * height).roundToInt()
        keyboardTop?.let { y = min(y, it - size - margin) }
        params.y = y.coerceIn(dp(32), maxOf(dp(32), height - size - margin))
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            DisplayMetrics().also {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(it)
            }.let { it.widthPixels to it.heightPixels }
        }

    // ---------------- BubbleView.Listener: recording ----------------

    override fun onPressStart(): Boolean {
        val target = field(keyboard())?.app
        return when (app.dictation.start(SOURCE, target) { level -> bubble?.setLevel(level) }) {
            Dictation.StartResult.OK -> {
                targetApp = target
                busy = true
                bubble?.state = BubbleView.State.RECORDING
                buzz(start = true)
                main.postDelayed(autoStop, MAX_RECORDING_MS)
                true
            }
            Dictation.StartResult.NO_PERMISSION -> toast("Open RedDot and allow the microphone first").let { false }
            Dictation.StartResult.MIC_UNAVAILABLE -> toast("The microphone is being used by another app").let { false }
            Dictation.StartResult.BUSY -> toast("RedDot is already recording").let { false }
        }
    }

    override fun onStop() {
        main.removeCallbacks(autoStop)
        if (app.dictation.recordingSource != SOURCE) return
        bubble?.state = BubbleView.State.TRANSCRIBING
        buzz(start = false)
        app.dictation.stop(::deliver)
    }

    override fun onCancel() {
        main.removeCallbacks(autoStop)
        if (app.dictation.recordingSource == SOURCE) app.dictation.cancel()
        busy = false
        bubble?.state = BubbleView.State.IDLE
    }

    private fun deliver(outcome: Dictation.Outcome) {
        busy = false
        val view = bubble
        when (outcome) {
            Dictation.Outcome.TooShort -> view?.state = BubbleView.State.IDLE
            is Dictation.Outcome.Silent -> {
                view?.state = BubbleView.State.ERROR
                toast("The microphone gave RedDot only silence. Open RedDot and check the microphone permission.")
            }
            is Dictation.Outcome.NoWords -> {
                view?.state = BubbleView.State.ERROR
                toast("RedDot didn't hear any words")
            }
            is Dictation.Outcome.Text -> {
                put(outcome.note.id, outcome.note.text.orEmpty())
                view?.state = BubbleView.State.DONE
            }
            is Dictation.Outcome.Failed -> {
                view?.state = BubbleView.State.ERROR
                toast(if (outcome.willRetry) "Saved on the phone. RedDot transcribes it once the server is reachable."
                      else "Couldn't transcribe: ${outcome.message}")
            }
        }
        // Hide the dot if the field is gone, once the check mark or the grey flash is over.
        main.removeCallbacks(refreshLater)
        main.postDelayed(refreshLater, BubbleView.FLASH_MS + 100)
    }

    // ---------------- putting the words in ----------------

    /** Into the field you're typing in, if it is still in the app you started recording in;
     *  otherwise (or in keyboard-only mode) onto the clipboard. */
    private fun put(noteId: String, text: String) {
        val field = field(keyboard())
        val node = field?.node
        if (field == null || field.app != targetApp || node == null) {
            TextInserter.copy(this, text.trim())
            val keyboardOnly = field?.how == FieldFinder.How.KEYBOARD_ONLY && field.app == targetApp
            toast(if (keyboardOnly) "Copied. Paste it with your keyboard's paste button." else "Copied. Paste it where you need it.")
            return
        }
        val attempt = TextInserter.insert(this, node, text, field.web)
        if (attempt == null) toast("Copied. Paste it where you need it.")
        else main.postDelayed({ check(noteId, attempt) }, CHECK_MS)
    }

    /** A moment later: did the field really change? Some apps accept the text and ignore it. */
    private fun check(noteId: String, attempt: TextInserter.Attempt) {
        when (TextInserter.changed(attempt)) {
            true -> app.notes.update(noteId) { it.copy(inserted = true) }
            null -> {}   // the field is gone (sent, closed): nothing to check against; the note is in RedDot either way
            false -> {
                val again = if (attempt.pasted) null else TextInserter.paste(this, attempt.node, attempt.before, attempt.piece)
                if (again != null) main.postDelayed({ check(noteId, again) }, CHECK_MS)
                else toast("Copied. Paste it where you need it.")
            }
        }
    }

    // ---------------- BubbleView.Listener: moving the dot ----------------

    override fun onDrag(dx: Float, dy: Float) {
        val params = layout ?: return
        val view = bubble ?: return
        if (!dragging) {
            dragging = true
            dragX = params.x.toFloat()
            dragY = params.y.toFloat()
        }
        dragX += dx
        dragY += dy
        params.x = dragX.roundToInt()
        params.y = dragY.roundToInt()
        windowManager.updateViewLayout(view, params)
    }

    override fun onDragEnd() {
        val params = layout ?: return
        val (width, height) = screenSize()
        app.settings.bubbleOnLeft = params.x + dp(BubbleView.SIZE_DP) / 2 < width / 2
        app.settings.bubbleY = params.y.toFloat() / height
        dragging = false
        refresh()   // snaps to the nearest edge
    }

    // ---------------- feedback ----------------

    private fun buzz(start: Boolean) {
        if (!app.settings.haptics) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        // One short buzz to start, two to stop: you can feel which it was without looking.
        vibrator.vibrate(if (start) VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                         else VibrationEffect.createWaveform(longArrayOf(0, 25, 80, 25), -1))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val SOURCE = "bubble"
        const val SETTLE_MS = 120L
        const val CHECK_MS = 400L                     // an app redraws a changed field well within this
        const val MAX_RECORDING_MS = 5 * 60 * 1000L   // a forgotten hands-free recording ends after 5 minutes
    }
}
