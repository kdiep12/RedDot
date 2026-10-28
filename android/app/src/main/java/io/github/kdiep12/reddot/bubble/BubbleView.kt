package io.github.kdiep12.reddot.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * The red dot floating over other apps. Draws itself and turns touches into intentions:
 *
 *     hold, speak, let go            push-to-talk: recording ends when you let go
 *     quick tap, speak, tap again    hands-free: recording ends with the second tap
 *     drag (within the first 0.7 s)  moves the dot; a recording that just started is dropped
 *
 * Colours follow the web app's looks.ts: idle red, a brighter red with a glow that grows
 * with your voice while recording, grey after a problem.
 */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onPressStart(): Boolean     // true: recording started
        fun onStop()                    // end the recording and transcribe it
        fun onCancel()                  // the press turned into a drag: drop the recording
        fun onDrag(dx: Float, dy: Float)
        fun onDragEnd()
    }

    enum class State { IDLE, RECORDING, TRANSCRIBING, DONE, ERROR }

    var state = State.IDLE
        set(value) {
            field = value
            if (value != State.RECORDING) toggled = false
            contentDescription = when (value) {
                State.RECORDING -> "RedDot is listening. Tap to finish."
                State.TRANSCRIBING -> "RedDot is transcribing"
                else -> "RedDot. Hold to dictate, or tap to start and tap again to finish."
            }
            if (value == State.DONE || value == State.ERROR) {
                postDelayed({ if (state == value) state = State.IDLE }, FLASH_MS)
            }
            invalidate()
        }

    /** Recording that keeps going after a quick tap, until the next tap. */
    private var toggled = false
    private var level = 0f

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val arcBox = RectF()
    private val check = Path()

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var startedByThisPress = false
    private var stopOnRelease = false
    private var dragging = false
    private val dragSlop = ViewConfiguration.get(context).scaledTouchSlop * 2f

    init {
        isClickable = true   // so TalkBack's double tap reaches performClick()
        state = State.IDLE
    }

    /** The microphone's loudness, 0..1, ten times a second while recording. */
    fun setLevel(value: Float) {
        level = level * 0.5f + value * 0.5f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = DOT_RADIUS_DP * density
        val recording = state == State.RECORDING

        if (recording) {
            paint.color = Color.argb((70 + 110 * level).toInt(), 255, 30, 30)
            canvas.drawCircle(cx, cy, r * (1.12f + 0.32f * level), paint)
        }
        paint.color = when (state) {
            State.RECORDING -> Color.rgb(255, 5, 5)        // looks.ts "speech": [1.00, 0.02, 0.02]
            State.ERROR -> Color.rgb(115, 115, 122)        // looks.ts "offline": [0.45, 0.45, 0.48]
            else -> Color.rgb(219, 33, 33)                 // looks.ts "idle": [0.86, 0.13, 0.13]
        }
        paint.setShadowLayer(4 * density, 0f, 1.5f * density, 0x66000000)
        canvas.drawCircle(cx, cy, if (recording) r * 1.06f else r, paint)
        paint.clearShadowLayer()

        stroke.strokeWidth = 2.5f * density
        when {
            recording && toggled -> {          // a small square: "tap to stop"
                paint.color = Color.WHITE
                val s = r * 0.32f
                canvas.drawRoundRect(cx - s, cy - s, cx + s, cy + s, 2 * density, 2 * density, paint)
            }
            state == State.TRANSCRIBING -> {   // a turning arc
                val a = r * 0.5f
                arcBox.set(cx - a, cy - a, cx + a, cy + a)
                val angle = (SystemClock.uptimeMillis() % 1000L) * 0.36f
                canvas.drawArc(arcBox, angle, 100f, false, stroke)
            }
            state == State.DONE -> {           // a check mark
                check.reset()
                check.moveTo(cx - r * 0.38f, cy + r * 0.02f)
                check.lineTo(cx - r * 0.1f, cy + r * 0.3f)
                check.lineTo(cx + r * 0.4f, cy - r * 0.28f)
                canvas.drawPath(check, stroke)
            }
        }
        if (state == State.TRANSCRIBING || recording) postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility") // TalkBack uses performClick() below
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; lastX = downX; lastY = downY
                downAt = SystemClock.uptimeMillis()
                dragging = false
                startedByThisPress = false
                stopOnRelease = false
                when (state) {
                    State.RECORDING -> stopOnRelease = true      // the second tap of hands-free mode
                    State.TRANSCRIBING -> {}                     // busy: can only be dragged
                    else -> startedByThisPress = listener.onPressStart()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > dragSlop &&
                    SystemClock.uptimeMillis() - downAt < DRAG_WINDOW_MS) {
                    dragging = true
                    stopOnRelease = false
                    if (startedByThisPress) {
                        startedByThisPress = false
                        listener.onCancel()
                    }
                }
                if (dragging) listener.onDrag(e.rawX - lastX, e.rawY - lastY)
                lastX = e.rawX; lastY = e.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val held = SystemClock.uptimeMillis() - downAt
                when {
                    dragging -> listener.onDragEnd()
                    stopOnRelease -> listener.onStop()
                    startedByThisPress && (held >= HOLD_MS || e.actionMasked == MotionEvent.ACTION_CANCEL) -> listener.onStop()
                    startedByThisPress -> {          // a quick tap: keep listening until the next tap
                        toggled = true
                        invalidate()
                    }
                }
            }
        }
        return true
    }

    /** TalkBack (and other accessibility tools): a click starts hands-free recording or ends it. */
    override fun performClick(): Boolean {
        super.performClick()
        when (state) {
            State.RECORDING -> listener.onStop()
            State.TRANSCRIBING -> {}
            else -> if (listener.onPressStart()) {
                toggled = true
                invalidate()
            }
        }
        return true
    }

    companion object {
        const val SIZE_DP = 72           // the touch area: big enough for gloves
        const val DOT_RADIUS_DP = 22     // the visible dot (44 dp across); the rest is room for the glow
        const val HOLD_MS = 350L         // held at least this long: push-to-talk, otherwise a tap
        const val DRAG_WINDOW_MS = 700L  // moving later than this is a shaky finger, not a drag
        const val FLASH_MS = 900L        // how long the check mark or the grey dot stays
    }
}
