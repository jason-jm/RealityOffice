package com.therealityteam.aeropages.model

import java.util.IdentityHashMap

/** Where a bordered or shaded paragraph stands in its box: whether its top ([first]) and bottom ([last]) are the box's. */
data class BoxEdge(val first: Boolean, val last: Boolean) {
    companion object {
        /** A paragraph boxed on its own. */
        val ALONE = BoxEdge(first = true, last = true)
    }
}

/**
 * Word's paragraph boxes: consecutive paragraphs with the same borders, shading and indents are one box — the top line
 * over the first, the bottom line under the last, the [ParagraphBorders.between] line between them, the sides and the
 * shading running down the whole box. The canvas and the PDF measure and draw a paragraph's box alike from here.
 */
object ParagraphBoxes {

    /** Whether [f] is boxed at all: it has borders or shading. */
    fun boxed(f: ParagraphFormat): Boolean = (f.borders?.none == false) || f.shading != null

    /** Whether [b] following [a] is in the same box. */
    fun joined(a: ParagraphFormat, b: ParagraphFormat): Boolean =
        boxed(a) && a.borders == b.borders && a.shading == b.shading &&
            PageMetrics.indents(a)[0] == PageMetrics.indents(b)[0] && a.indentRightPt == b.indentRightPt

    /**
     * Each boxed paragraph that shares its box with a neighbour, its edge; a paragraph boxed alone (or not at all) is
     * not listed. The body's paragraphs one after another (a table, a page break or anything else ends a box), each
     * table cell's and each text box's.
     */
    fun edges(doc: Document): Map<Paragraph, BoxEdge> {
        val out = IdentityHashMap<Paragraph, BoxEdge>()
        fun run(ps: List<Paragraph>) {
            for (i in ps.indices) {
                val f = ps[i].format
                if (!boxed(f)) continue
                val first = i == 0 || !joined(ps[i - 1].format, f)
                val last = i == ps.lastIndex || !joined(f, ps[i + 1].format)
                if (!(first && last)) out[ps[i]] = BoxEdge(first, last)
            }
        }
        val body = ArrayList<Paragraph>()
        for (b in doc.blocks) {
            if (b is ParagraphBlock && !b.paragraph.isPageBreak()) { body.add(b.paragraph); continue }
            run(body); body.clear()
            when (b) {
                is TableBlock -> b.table.rows.forEach { r -> r.cells.forEach { c -> run(c.content) } }
                is TextBoxBlock -> run(b.paragraphs)
                else -> {}
            }
        }
        run(body)
        return out
    }

    /** Room the box takes above [f]'s text (its line and its space): the top line over the first, the between line over the rest. */
    fun topExtra(f: ParagraphFormat, e: BoxEdge?): Float {
        val b = f.borders ?: return 0f
        val line = if (e?.first != false) b.top else b.between
        return line?.let { it.thicknessPt + b.spaceTop } ?: 0f
    }

    /** Room the box takes below [f]'s text: the bottom line under the last. */
    fun bottomExtra(f: ParagraphFormat, e: BoxEdge?): Float {
        val b = f.borders ?: return 0f
        if (e?.last == false) return 0f
        return b.bottom?.let { it.thicknessPt + b.spaceBottom } ?: 0f
    }

    /** All the space above [f]'s text: its space before and its box's room. */
    fun beforePt(f: ParagraphFormat, e: BoxEdge?): Float = f.spaceBeforePt + topExtra(f, e)

    /** All the space below [f]'s text. */
    fun afterPt(f: ParagraphFormat, e: BoxEdge?): Float = f.spaceAfterPt + bottomExtra(f, e)
}
