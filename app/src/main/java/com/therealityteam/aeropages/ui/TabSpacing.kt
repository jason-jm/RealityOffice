package com.therealityteam.aeropages.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.staticCompositionLocalOf
import com.therealityteam.aeropages.format.pdf.PdfFaces
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.TabLayout
import java.util.WeakHashMap

/**
 * The canvas's tabs. Compose lays a tab out as a short jump of its own, so each tab of a paragraph
 * is shown as a space stretched by letter spacing to the width TabLayout gives it — measured here as
 * the canvas sets text: its faces, its sizes scaled by the font scale, points as dp. The text keeps
 * its length, so carets, selections and the page measurement stay where they are.
 */
object TabSpacing {
    /** What a page's tabs depend on: the document's default stops, the column's width, the font scale. */
    data class Spec(val defaultTabPt: Float, val columnPt: Float, val fontScale: Float)

    private class Entry(val sig: Int, val spec: Spec, val tabs: List<TabLayout.Tab>)
    private val cache = WeakHashMap<Paragraph, Entry>()
    private val paint = Paint(PdfFaces.TEXT_FLAGS)

    /** [p]'s tabs on a page of [spec]; empty when it has none. */
    fun tabs(p: Paragraph, spec: Spec): List<TabLayout.Tab> {
        if (p.runs.none { '\t' in it.text }) return emptyList()
        val sig = signature(p)
        cache[p]?.let { if (it.sig == sig && it.spec == spec) return it.tabs }
        val fallback = p.format.style.sizePt
        val tabs = TabLayout.tabs(p, spec.columnPt, spec.defaultTabPt) { s, f -> width(s, f, fallback, spec.fontScale) }
        cache[p] = Entry(sig, spec, tabs)
        return tabs
    }

    /**
     * The letter spacing (em of [f]'s size) that stretches a space of [f] to [advance] points. Android
     * spaces a character half before and half after it and leaves out the half that would fall past
     * the edge of a line, so a tab at a line's [edge] (the paragraph's start or end, a line break's
     * side) gets twice the spacing to come out as wide.
     */
    fun letterSpacingEm(advance: Float, f: CharacterFormat, fallbackSizePt: Float, fontScale: Float, edge: Boolean = false): Float {
        val size = sizeOf(f, fallbackSizePt) * fontScale
        if (size <= 0f) return 0f
        return (advance - width(" ", f, fallbackSizePt, fontScale)) / size * (if (edge) 2f else 1f)
    }

    /** Whether the character at [i] of [text] starts or ends a line of its own (the text's ends, a line break beside it). */
    fun atEdge(text: CharSequence, i: Int): Boolean = i == 0 || i == text.length - 1 || text[i - 1] == '\n' || text[i + 1] == '\n'


    private fun sizeOf(f: CharacterFormat, fallback: Float) = (f.fontSizePt ?: fallback) * (if (f.superscript || f.subscript) 0.72f else 1f)

    /** [s] set in [f] as the canvas sets it, in points. */
    private fun width(s: String, f: CharacterFormat, fallback: Float, fontScale: Float): Float {
        paint.textSize = sizeOf(f, fallback) * fontScale
        paint.typeface = typefaceOf(f.fontFamily, f.bold, f.italic)
        paint.letterSpacing = f.letterSpacingPt?.let { it / paint.textSize } ?: 0f
        return paint.measureText(s)
    }

    /** The face the canvas sets a font name in (TextStyling.familyOf's generic family). */
    private fun typefaceOf(name: String?, bold: Boolean, italic: Boolean): Typeface {
        val base = when (TextStyling.familyOf(name)) {
            androidx.compose.ui.text.font.FontFamily.Serif -> Typeface.SERIF
            androidx.compose.ui.text.font.FontFamily.Monospace -> Typeface.MONOSPACE
            androidx.compose.ui.text.font.FontFamily.SansSerif -> Typeface.SANS_SERIF
            androidx.compose.ui.text.font.FontFamily.Cursive -> Typeface.create("cursive", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(base, style)
    }

    private fun signature(p: Paragraph): Int {
        var h = p.format.hashCode()
        for (r in p.runs) h = 31 * (31 * h + r.text.hashCode()) + r.format.hashCode()
        return h
    }
}

/** The tab spec of the page being composed (null outside pages: tabs show as Compose sets them). */
internal val LocalTabSpec = staticCompositionLocalOf<TabSpacing.Spec?> { null }
