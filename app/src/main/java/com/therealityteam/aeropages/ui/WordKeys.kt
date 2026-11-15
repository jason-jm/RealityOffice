package com.therealityteam.aeropages.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import com.therealityteam.aeropages.editor.CaseMode
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.TextAlign

/**
 * Word's everyday keys in Pages (a key down in the text): Ctrl+E / L / R / J alignment; Ctrl+] / [ the font a point
 * bigger / smaller and Ctrl+Shift+> / < a size up / down; Ctrl+1 / 2 / 5 single, double and 1.5 line spacing;
 * Ctrl+Alt+1 / 2 / 3 Heading 1–3 and Ctrl+Shift+N Normal; Ctrl+Enter a page break; Ctrl+K a link; Ctrl+D the Font
 * dialog; Ctrl+M / Ctrl+Shift+M the indent in or out; Shift+F3 the case round (UPPER, lower, Title); Ctrl+Space plain
 * characters; Ctrl+= subscript and Ctrl+Shift+= superscript; Ctrl+Shift+L bullets. True when it took the key.
 */
internal fun wordKey(editor: DocumentEditor, e: KeyEvent): Boolean {
    val ctrl = e.isCtrlPressed || e.isMetaPressed
    val shift = e.isShiftPressed
    val alt = e.isAltPressed
    if (!ctrl) {
        if (shift && e.key == Key.F3) { cycleCase(editor); return true }
        return false
    }
    when {
        alt && e.key == Key.One -> editor.setStyle(ParagraphStyle.HEADING1)
        alt && e.key == Key.Two -> editor.setStyle(ParagraphStyle.HEADING2)
        alt && e.key == Key.Three -> editor.setStyle(ParagraphStyle.HEADING3)
        alt -> return false
        shift && e.key == Key.N -> editor.setStyle(ParagraphStyle.NORMAL)
        shift && e.key == Key.L -> editor.toggleBullet()
        shift && e.key == Key.M -> editor.stepIndent(-INDENT_STEP)
        shift && e.key == Key.Period -> editor.setFontSize(stepSize(currentSize(editor), +1))
        shift && e.key == Key.Comma -> editor.setFontSize(stepSize(currentSize(editor), -1))
        shift && e.key == Key.Equals -> editor.toggleSuperscript()
        shift -> return false
        e.key == Key.E -> editor.setAlign(TextAlign.CENTER)
        e.key == Key.L -> editor.setAlign(TextAlign.LEFT)
        e.key == Key.R -> editor.setAlign(TextAlign.RIGHT)
        e.key == Key.J -> editor.setAlign(TextAlign.JUSTIFY)
        e.key == Key.RightBracket -> editor.setFontSize((currentSize(editor) + 1f).coerceAtMost(1638f))
        e.key == Key.LeftBracket -> editor.setFontSize((currentSize(editor) - 1f).coerceAtLeast(1f))
        e.key == Key.One -> editor.setLineSpacing(1f)
        e.key == Key.Two -> editor.setLineSpacing(2f)
        e.key == Key.Five -> editor.setLineSpacing(1.5f)
        e.key == Key.Enter || e.key == Key.NumPadEnter -> editor.insertPageBreak()
        e.key == Key.K -> editor.requestDialog("link")
        e.key == Key.D -> editor.requestDialog("font")
        e.key == Key.M -> editor.stepIndent(INDENT_STEP)
        e.key == Key.Spacebar -> editor.clearFormatting()
        e.key == Key.Equals -> editor.toggleSubscript()
        else -> return false
    }
    return true
}

/** Word's Ctrl+M: a tab stop's worth, two characters. */
private const val INDENT_STEP = 2f

/** Shift+F3: lower case becomes UPPER, UPPER becomes Title, anything else lower — Word's round. */
private fun cycleCase(editor: DocumentEditor) {
    val t = editor.selectedText()
    val mode = when {
        t.isNotEmpty() && t == t.lowercase() && t != t.uppercase() -> CaseMode.UPPER
        t.isNotEmpty() && t == t.uppercase() && t != t.lowercase() -> CaseMode.TITLE
        else -> CaseMode.LOWER
    }
    editor.changeCase(mode)
}
