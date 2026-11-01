package io.github.kdiep12.reddot.bubble

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Finds the text field you're typing in, in another app, for the bubble. Asking Android
 * ("which element has the input focus?") is not enough: Jetpack Compose can answer with its
 * whole screen view (in tests with Compose 1.12 it did until something had read its
 * accessibility tree), and an app's web view always did. Apps built that way looked as if they
 * had no text field at all: RedDot 0.2.0 showed no dot in the Claude app. So:
 *
 *   1. Android's answer, findFocus(FOCUS_INPUT), when it is an editable field: classic views.
 *   2. Android answered with a view that isn't editable (Compose, a web view): look inside it
 *      for the element that is focused and editable.
 *   3. No answer: look through the app window that has the keyboard's focus the same way.
 *   4. Still nothing: the field that last reported focus or a cursor move, if it still is
 *      focused and editable.
 *   5. The keyboard is open over another app, but no field can be seen at all (an editor
 *      hidden from accessibility): "keyboard only". The dot still shows; the words go to the
 *      clipboard, ready to paste.
 *
 * While searching it reads only each element's flags (focused, editable, password, visible)
 * and class name, never its text: the bubble may only use the field you're typing in.
 * Never a password field, never RedDot's own screens, never on the lock screen.
 */
class FieldFinder(private val service: AccessibilityService) {
    /** How a field was found, for the diagnostics line in Settings. */
    enum class How(val label: String) {
        SYSTEM_FOCUS("found by Android's focus"),
        INSIDE_VIEW("found inside the focused view"),
        WINDOW_SEARCH("found by searching the app's window"),
        CURSOR_EVENT("found from the last cursor move"),
        KEYBOARD_ONLY("keyboard only: words go to the clipboard"),
        PASSWORD("a password field: no dot"),
    }

    /** The field to type into. [node] is null in keyboard-only mode: then nothing is touched. */
    class Field(val node: AccessibilityNodeInfo?, val app: String, val how: How, val web: Boolean)

    /** What the last search found, without any text: shown in Settings when the dot is missing. */
    data class Report(
        val app: String,
        val fieldClass: String,       // the field's class, "" when none could be seen
        val password: Boolean,
        val focusClass: String,       // what Android itself reported as focused, "" for nothing
        val focusEditable: Boolean,
        val how: How,
        val web: Boolean,
    ) {
        fun describe(): String = listOf(
            app,
            "field: " + fieldClass.substringAfterLast('.').ifEmpty { "none visible" } + (if (password) " (password)" else ""),
            "Android's focus: " + focusClass.substringAfterLast('.').ifEmpty { "none" } +
                (if (focusClass.isNotEmpty() && !focusEditable) ", not editable" else ""),
            how.label,
            if (web) "web page" else "app view",
        ).joinToString(" · ")
    }

    var report: Report? = null
        private set

    private var cursorNode: AccessibilityNodeInfo? = null   // the editable field that last reported focus or a cursor move
    private var passwordWindow = NO_WINDOW                  // the window whose latest text event came from a password field
    private var last: Field? = null                         // the previous answer: checked first, so typing stays cheap
    private var lastWeb: Pair<AccessibilityNodeInfo, Boolean>? = null

    /** Called for every event the service gets: remembers fields that report focus or cursor moves. */
    fun onEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        if (event.packageName?.toString() == service.packageName) return
        if (event.isPassword) {
            passwordWindow = event.windowId
            cursorNode = null
            return
        }
        val source = event.source ?: return
        when {
            source.isPassword -> { passwordWindow = event.windowId; cursorNode = null }
            source.isEditable -> {
                cursorNode = source
                if (event.windowId == passwordWindow) passwordWindow = NO_WINDOW
            }
        }
    }

    /** The field you're typing in, or null: then the bubble stays hidden. [keyboard]: the
     *  keyboard's window, if it is open (keyboard-only mode needs it). */
    fun find(keyboard: AccessibilityWindowInfo?): Field? {
        if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return null
        val window = focusedWindow()
        val root = window?.root
        if (root != null && ours(root)) return null

        // The previous answer, if it still is the focused, editable field in that window.
        last?.let { previous ->
            val node = previous.node
            if (node != null && node.windowId == window?.id && stillTyping(node)) return previous
        }

        val focus = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeUnless { ours(it) }
        val focusClass = focus?.className?.toString().orEmpty()
        val focusEditable = focus?.isEditable == true
        var found: AccessibilityNodeInfo? = null
        var how = How.SYSTEM_FOCUS
        if (focus != null) {
            if (focus.isEditable || focus.isPassword) found = focus
            else found = search(focus)?.also { how = How.INSIDE_VIEW }
        }
        if (found == null && root != null) found = search(root)?.also { how = How.WINDOW_SEARCH }
        if (found == null) {
            found = cursorNode?.takeIf { it.windowId == window?.id && stillTyping(it) && !ours(it) }
            if (found != null) how = How.CURSOR_EVENT
        }

        if (found != null && found.isPassword) {
            report = Report(found.packageName?.toString().orEmpty(), found.className?.toString().orEmpty(), password = true,
                            focusClass, focusEditable, How.PASSWORD, web = false)
            last = null
            return null
        }
        val field = when {
            found != null -> Field(found, found.packageName?.toString().orEmpty(), how, isWeb(found))
            keyboard != null && root != null && window.id != passwordWindow ->
                Field(null, root.packageName?.toString().orEmpty(), How.KEYBOARD_ONLY, web = false)
            else -> null
        }
        last = field
        field?.let {
            report = Report(it.app, it.node?.className?.toString().orEmpty(), password = false, focusClass, focusEditable, it.how, it.web)
        }
        return field
    }

    /** The app window that has the keyboard's focus (not the keyboard, not an overlay like the dot). */
    private fun focusedWindow(): AccessibilityWindowInfo? {
        val windows = service.windows
        return windows.firstOrNull {
            it.isFocused && it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        } ?: windows.firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
    }

    /** Fresh from the app: still focused, editable and not a password field. */
    private fun stillTyping(node: AccessibilityNodeInfo): Boolean =
        node.refresh() && node.isFocused && node.isEditable && !node.isPassword

    /**
     * The focused editable element (or focused password field) below [top], depth first. Only
     * flags and class names are read. Hidden elements and their children are skipped, and
     * later children come first: in chat apps the message box sits at the end of the screen.
     * At most [MAX_NODES] elements and [MAX_DEPTH] levels, so a huge web page can't stall it.
     */
    private fun search(top: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var visited = 0
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(top to 0)
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = stack.removeLast()
            visited++
            if (node.isFocused && (node.isEditable || node.isPassword)) return node
            if (depth >= MAX_DEPTH) continue
            // Pushed in order, so the last child is looked at first.
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (child.isVisibleToUser) stack.addLast(child to depth + 1)
            }
        }
        return null
    }

    /** A field in a web page (a WebView in an app, or Chrome): its text is better pasted than
     *  replaced, because web editors keep their own copy of the text. */
    private fun isWeb(node: AccessibilityNodeInfo): Boolean {
        lastWeb?.let { (known, web) -> if (known == node) return web }
        var web = node.extras?.containsKey(CHROME_ROLE) == true
        var parent = node.parent
        var steps = 0
        while (!web && parent != null && steps < MAX_DEPTH) {
            val name = parent.className?.toString().orEmpty()
            web = "WebView" in name || "chromium" in name
            parent = parent.parent
            steps++
        }
        lastWeb = node to web
        return web
    }

    private fun ours(node: AccessibilityNodeInfo) = node.packageName?.toString() == service.packageName

    companion object {
        const val MAX_NODES = 400
        const val MAX_DEPTH = 30
        private const val NO_WINDOW = Int.MIN_VALUE
        private const val CHROME_ROLE = "AccessibilityNodeInfo.chromeRole"   // set on every node of a Chromium web page
    }
}
