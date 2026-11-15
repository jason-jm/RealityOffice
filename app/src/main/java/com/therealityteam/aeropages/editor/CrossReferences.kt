package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.BookmarkMark
import com.therealityteam.aeropages.model.Bookmarks
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.DocField
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Fields
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.StyleDef
import com.therealityteam.aeropages.model.TextRun

/**
 * Captions and cross-references as Word makes them: a caption is a paragraph in the Caption style whose number is a
 * sequence field (`SEQ Figure \* ARABIC`); a cross-reference is a REF or PAGEREF field naming a hidden `_Ref…` bookmark
 * round what it refers to — a heading, a bookmark, a caption or the label and number of one.
 */
object CrossReferences {
    /** What a cross-reference can refer to: a heading, a bookmark, or a caption with sequence [label]. */
    sealed class Target {
        /** Its paragraph, an index into [Document.locations] (a bookmark: where it starts). */
        abstract val para: Int
        /** How the list shows it. */
        abstract val title: String
    }
    data class Heading(override val para: Int, override val title: String, val level: Int) : Target()
    data class Mark(val name: String, override val para: Int, override val title: String) : Target()
    /** A caption: its sequence's result runs from [seqStart] to [seqEnd]; its own text starts at [textStart]. */
    data class Caption(override val para: Int, override val title: String, val label: String, val seqStart: Int, val seqEnd: Int, val textStart: Int) : Target()

    /** What a cross-reference shows of its target. */
    enum class Content {
        /** The heading's text, the bookmark's, the entire caption. */
        TEXT,
        /** A caption's label and number only ("Figure 2"). */
        LABEL_NUMBER,
        /** A caption's text only, after its number. */
        CAPTION_TEXT,
        /** The heading's (paragraph's) number. */
        NUMBER,
        /** The page it is on. */
        PAGE,
        /** "above" or "below". */
        ABOVE_BELOW,
    }

    /** The contents a cross-reference to [t] can show, Word's order. */
    fun contentsFor(t: Target): List<Content> = when (t) {
        is Heading -> listOf(Content.TEXT, Content.PAGE, Content.NUMBER, Content.ABOVE_BELOW)
        is Mark -> listOf(Content.TEXT, Content.PAGE, Content.NUMBER, Content.ABOVE_BELOW)
        is Caption -> listOf(Content.TEXT, Content.LABEL_NUMBER, Content.CAPTION_TEXT, Content.PAGE, Content.ABOVE_BELOW)
    }

    /** The sequences (caption labels) [doc] numbers, in the order they first show. */
    fun labels(doc: Document): List<String> =
        Fields.placed(doc).filter { it.field.kind == "SEQ" && it.field.target.isNotEmpty() }.map { it.field.target }.distinctBy { it.lowercase() }

    /** [doc]'s headings (Heading 1–3, and paragraphs of an outline level) in order. */
    fun headings(doc: Document): List<Heading> {
        val labels = com.therealityteam.aeropages.model.PageMetrics.listLabels(doc)
        return doc.locations().mapIndexedNotNull { i, loc ->
            val level = when (loc.paragraph.format.style) {
                ParagraphStyle.HEADING1 -> 0; ParagraphStyle.HEADING2 -> 1; ParagraphStyle.HEADING3 -> 2; else -> return@mapIndexedNotNull null
            }
            val text = loc.paragraph.plainText().trim()
            if (text.isEmpty()) return@mapIndexedNotNull null
            Heading(i, labels[loc.paragraph]?.let { "$it $text" } ?: text, level)
        }
    }

    /** [doc]'s own bookmarks (not the hidden ones Word and the app make), with their text. */
    fun bookmarks(doc: Document): List<Mark> {
        val locs = doc.locations()
        return Bookmarks.of(doc).filter { !it.hidden }.map { Mark(it.name, it.startPara, Fields.textOf(locs, it).ifBlank { it.name }) }
    }

    /** [doc]'s captions numbered by sequence [label]. */
    fun captions(doc: Document, label: String): List<Caption> {
        val locs = doc.locations()
        return Fields.placed(doc).filter { it.field.kind == "SEQ" && it.field.target.equals(label, ignoreCase = true) }.map { pl ->
            val text = locs[pl.para].paragraph.plainText()
            var start = pl.end
            while (start < text.length && (text[start].isWhitespace() || text[start] in SEPARATORS)) start++
            Caption(pl.para, text.trim(), label, pl.start, pl.end, start)
        }
    }

    /** What may stand between a caption's number and its text. */
    private const val SEPARATORS = ":：.．-—–、"

    /** The part of its paragraph a cross-reference to [t] showing [c] needs a bookmark round. */
    private fun rangeFor(doc: Document, t: Target, c: Content): IntRange {
        val len = ParagraphOps.length(doc.locations()[t.para].paragraph)
        return when {
            t is Caption && c == Content.LABEL_NUMBER -> 0 until t.seqEnd
            t is Caption && c == Content.CAPTION_TEXT -> t.textStart.coerceAtMost(len) until len
            else -> 0 until len
        }
    }

    /**
     * The bookmark a cross-reference to [t] showing [c] names: [t]'s own, or a hidden `_Ref…` one round what it shows
     * — one already there round the same text, else a new one (Word's names: `_Ref` and nine digits).
     */
    fun bookmarkFor(doc: Document, t: Target, c: Content): String {
        if (t is Mark) return t.name
        val range = rangeFor(doc, t, c)
        val found = Bookmarks.of(doc)
        found.firstOrNull { it.name.startsWith("_Ref") && it.startPara == t.para && it.endPara == t.para && it.start == range.first && it.end == range.last + 1 }
            ?.let { return it.name }
        val taken = found.mapTo(HashSet()) { it.name }
        var n = 100_000_000 + (doc.locations()[t.para].paragraph.id.hashCode() and 0x7FFFFFF) % 800_000_000
        while ("_Ref$n" in taken) n++
        val name = "_Ref$n"
        val p = doc.locations()[t.para].paragraph
        Bookmarks.place(p, BookmarkMark(name, range.first, false))
        Bookmarks.place(p, BookmarkMark(name, range.last + 1, true))
        return name
    }

    /** The field code of a cross-reference to bookmark [name] showing [c], a link to it when [link]. */
    fun codeFor(name: String, c: Content, link: Boolean): String {
        val h = if (link) " \\h" else ""
        return when (c) {
            Content.PAGE -> "PAGEREF $name$h"
            Content.NUMBER -> "REF $name \\r$h"
            Content.ABOVE_BELOW -> "REF $name \\p$h"
            else -> "REF $name$h"
        }
    }

    /** Word's Caption style (as its Normal template has it), added to [doc]'s styles when they lack one; its id. */
    fun captionStyle(doc: Document): String {
        doc.styles["Caption"]?.let { return it.id }
        doc.styles.styles.values.firstOrNull { it.name.equals("caption", ignoreCase = true) }?.let { return it.id }
        doc.styles.put(StyleDef("Caption", "caption", basedOn = doc.styles.idOf(ParagraphStyle.NORMAL), sizePt = 10f, color = CAPTION_COLOR,
            spaceBeforePt = 0f, spaceAfterPt = 10f))
        return "Caption"
    }

    /** Word's caption colour (Text 2). */
    const val CAPTION_COLOR = 0xFF44546AL

    /**
     * A caption paragraph for sequence [label]: the label (unless not [withLabel]) and the next number of the sequence —
     * a field numbered as [format] says (`ARABIC`, `ALPHABETIC`, `roman` …) — then a space for its text. With a
     * [chapterSeparator], Word's "Include chapter number": the Heading 1's number (`STYLEREF 1 \s`), the separator,
     * and the sequence numbered again in each chapter (`\s 1`) — "Figure 2-1".
     */
    fun captionParagraph(doc: Document, label: String, format: String, withLabel: Boolean, chapterSeparator: String? = null): Paragraph {
        val style = captionStyle(doc)
        val fmt = CharacterFormat(fontSizePt = 10f, color = CAPTION_COLOR)
        val runs = ArrayList<TextRun>()
        if (withLabel) runs.add(TextRun("$label ", fmt.copyOf()))
        if (chapterSeparator != null) {
            runs.add(TextRun("1", fmt.copyOf().also { it.field = DocField("STYLEREF 1 \\s") }))
            runs.add(TextRun(chapterSeparator, fmt.copyOf()))
        }
        runs.add(TextRun("1", fmt.copyOf().also { it.field = DocField("SEQ $label \\* $format" + if (chapterSeparator != null) " \\s 1" else "") }))
        runs.add(TextRun(" ", fmt.copyOf()))
        return Paragraph(runs, com.therealityteam.aeropages.model.ParagraphFormat(
            align = com.therealityteam.aeropages.model.TextAlign.CENTER, rawStyleId = style, spaceBeforePt = 0f, spaceAfterPt = 10f))
    }
}
