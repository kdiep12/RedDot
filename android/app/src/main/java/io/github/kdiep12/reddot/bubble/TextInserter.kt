package io.github.kdiep12.reddot.bubble

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Puts the transcript into the text field that has focus, at the cursor:
 *
 *  1. ACTION_SET_TEXT with the field's text plus the new words, then the cursor after them.
 *     Leaves the clipboard alone, and works in most apps (plain Android and Compose fields).
 *  2. Otherwise: the words on the clipboard and ACTION_PASTE (web pages, unusual editors).
 *  3. Otherwise, or when you switched to another app meanwhile: only the clipboard, and the
 *     caller tells you to paste.
 */
object TextInserter {
    /** True if the text went into the field; false if it is only on the clipboard. */
    fun insert(service: AccessibilityService, text: String, expectedApp: String?): Boolean {
        val node = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null || !node.isEditable || node.isPassword ||
            (expectedApp != null && node.packageName?.toString() != expectedApp)) {
            copy(service, text.trim())
            return false
        }
        val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start !in 0..existing.length) start = existing.length    // -1: no cursor known, append
        if (end !in start..existing.length) end = start
        val before = existing.substring(0, start)
        val after = existing.substring(end)
        val piece = fitBetween(before, text, after)

        val setText = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, before + piece + after)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)) {
            val cursor = before.length + piece.length
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            })
            return true
        }
        copy(service, piece)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
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

    private fun copy(service: AccessibilityService, text: String) {
        service.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("RedDot", text))
    }
}
