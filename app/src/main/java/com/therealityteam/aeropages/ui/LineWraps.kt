package com.therealityteam.aeropages.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * A paragraph's lines beside floating objects (square wrapping), as the canvas sets them: each line beside an object
 * — starting at [starts], offsets into the paragraph's text — is a paragraph style of its own (Compose sets a paragraph
 * style's text on lines of its own: the break needs no character, so offsets stay the paragraph's), indented past the
 * object on its left by [lefts] (sp) and holding only what was measured to fit short of the one on its right; from
 * [restFrom] on (the text's [length]: nowhere) the lines run the full width again.
 */
internal class WrapShape(val starts: IntArray, val lefts: FloatArray, val restFrom: Int, val length: Int,
                         /** The lines beside objects set from their left (a centred or right-aligned paragraph's: [lefts] already place them). */
                         val fromLeft: Boolean = false) {
    /**
     * [text] (the paragraph's text, styled; its length [length]) set so: the lines before the first beside an object as
     * the paragraph sets them, then each line beside one indented past it, then the rest with the other lines' indent —
     * [first] / [rest] are the paragraph's own first-line and other lines' indents.
     */
    fun apply(text: AnnotatedString, first: TextUnit, rest: TextUnit): AnnotatedString {
        if (text.length != length || starts.isEmpty()) return text
        fun plus(a: TextUnit, b: Float): TextUnit = ((if (a.isSpecified) a.value else 0f) + b).sp
        val restIndent = TextIndent(plus(rest, 0f), plus(rest, 0f))
        return buildAnnotatedString {
            fun part(a: Int, b: Int, indent: TextIndent?) {
                if (b <= a) return
                if (indent == null) { append(text.subSequence(a, b)); return }
                val p = pushStyle(ParagraphStyle(textIndent = indent, textAlign = if (fromLeft && indent !== restIndent) androidx.compose.ui.text.style.TextAlign.Left else androidx.compose.ui.text.style.TextAlign.Unspecified))
                append(text.subSequence(a, b))
                pop(p)
            }
            if (starts[0] > 0) part(0, starts[0], null)
            starts.forEachIndexed { i, s ->
                val end = if (i + 1 < starts.size) starts[i + 1] else restFrom
                val base = if (s == 0) first else rest
                part(s, end, TextIndent(plus(base, lefts[i]), plus(base, lefts[i])))
            }
            // (this@WrapShape's length: the builder's own is what it holds so far.)
            part(restFrom, this@WrapShape.length, restIndent)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is WrapShape && other.length == length && other.restFrom == restFrom && other.fromLeft == fromLeft && other.starts.contentEquals(starts) && other.lefts.contentEquals(lefts)

    override fun hashCode(): Int = (starts.contentHashCode() * 31 + lefts.contentHashCode()) * 31 + restFrom
}
