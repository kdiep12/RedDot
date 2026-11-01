package io.github.kdiep12.reddot.bubble

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Puts the transcript into the text field you're typing in (found by [FieldFinder]), at the
 * cursor:
 *
 *  1. App fields: ACTION_SET_TEXT with the field's text plus the new words, then the cursor
 *     after them. Leaves the clipboard alone; works for plain Android and Compose fields.
 *  2. Web pages, and app fields that ignore 1: the words on the clipboard, then ACTION_PASTE.
 *     A web editor (ProseMirror, Lexical...) keeps its own copy of the text: replacing the
 *     page's text behind its back can be undone by the editor or lost on send. A paste goes
 *     through the editor like typing does.
 *  3. Otherwise only the clipboard, and the caller tells you to paste.
 *
 * Some apps accept an action and then ignore it, so the caller checks a moment later with
 * [changed] whether the field really changed, and falls back to the next way if it didn't.
 */
object TextInserter {
    /** One try at putting [piece] into [node], to be checked with [changed]. */
    class Attempt(val node: AccessibilityNodeInfo, val pasted: Boolean, val before: String, val piece: String)

    /** Null: nothing worked, the words are on the clipboard. */
    fun insert(service: AccessibilityService, node: AccessibilityNodeInfo, text: String, web: Boolean): Attempt? {
        val existing = textOf(node)
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start !in 0..existing.length) start = existing.length    // -1: no cursor known, append
        if (end !in start..existing.length) end = start
        val before = existing.substring(0, start)
        val after = existing.substring(end)
        val piece = fitBetween(before, text, after)

        if (!web) {
            val setText = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, before + piece + after)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)) {
                val cursor = before.length + piece.length
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                })
                return Attempt(node, pasted = false, existing, piece)
            }
        }
        return paste(service, node, existing, piece)
    }

    /** The second way, also used when the field ignored ACTION_SET_TEXT. Null: only copied.
     *  The piece keeps its spaces, so pasted words don't stick to the text around them. */
    fun paste(service: AccessibilityService, node: AccessibilityNodeInfo, before: String, piece: String): Attempt? {
        copy(service, piece)
        return if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) Attempt(node, pasted = true, before, piece) else null
    }

    /**
     * True: the field's text changed since the attempt (it worked, or you typed meanwhile:
     * either way don't add the words a second time). False: unchanged, the app ignored it.
     * Null: the field is gone (the message was sent, the screen changed): can't tell.
     */
    fun changed(attempt: Attempt): Boolean? {
        if (!attempt.node.refresh()) return null
        return textOf(attempt.node) != attempt.before
    }

    /** The words, with a space added where they would otherwise stick to the text around them. */
    fun fitBetween(before: String, text: String, after: String): String {
        var piece = text.trim()
        if (piece.isEmpty()) return piece
        if (before.isNotEmpty() && !before.last().isWhitespace() && (piece.first().isLetterOrDigit() || piece.first() in "\"'(“‘")) {
            piece = " $piece"
        }
        if (after.isNotEmpty() && !after.first().isWhitespace() && after.first().isLetterOrDigit()) piece = "$piece "
        return piece
    }

    fun copy(service: AccessibilityService, text: String) {
        service.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("RedDot", text))
    }

    /** The field's own text: an empty field shows its hint ("Type a message") as its text. */
    private fun textOf(node: AccessibilityNodeInfo): String =
        if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
}
