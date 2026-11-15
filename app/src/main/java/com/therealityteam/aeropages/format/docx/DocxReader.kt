package com.therealityteam.aeropages.format.docx

import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.DocFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.PageSetup
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.ResolvedStyle
import com.therealityteam.aeropages.model.StyleDef
import com.therealityteam.aeropages.model.StyleSheet
import com.therealityteam.aeropages.model.TextRun
import com.therealityteam.aeropages.format.XmlPull
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * A self-built Office Open XML (.docx) reader.
 *
 * Strategy: a .docx file is a ZIP package. We read the relevant parts into byte
 * buffers, then stream `word/document.xml` (and `word/numbering.xml`) through an
 * [XmlPullParser]. We deliberately depend on nothing heavier than the JDK zip
 * package + the Android-bundled XmlPull parser, so it is light and runs on PICO.
 */
object DocxReader {

    fun read(input: InputStream): Document {
        val parts = com.therealityteam.aeropages.format.OfficeXml.unzip(input)
        val documentXml = parts["word/document.xml"]
            ?: throw IllegalArgumentException("Not a valid .docx: missing word/document.xml")
        val levelIndents = HashMap<String, Map<Int, Indent>>()
        val numbering = parts["word/numbering.xml"]?.let { parseNumbering(it, levelIndents) } ?: emptyMap()
        val lists = ListState(parts["word/numbering.xml"]?.let { runCatching { WordLists.parse(it) }.getOrNull() } ?: WordLists.Parsed(emptyMap(), emptyMap()))
        val rels = parts["word/_rels/document.xml.rels"]?.let { parseRels(it) } ?: emptyMap()
        val commentsMap = parts["word/comments.xml"]?.let { parseComments(it) } ?: emptyMap()
        val rawIndents = HashMap<String, Indent>()
        val rawTabs = HashMap<String, MutableList<TabOp>>()
        val tables = TableStyles()
        val styles = parts["word/styles.xml"]?.let { parseStyles(it, rawIndents, rawTabs, tables, lists.styleNums) } ?: StyleSheet.defaults()

        val doc = try {
            styleIndents.set(rawIndents); numberingIndents.set(levelIndents); styleTabs.set(rawTabs); tableStyles.set(tables); bookmarkState.set(BookmarkState()); listState.set(lists); indexReading.set(IndexReading())
            com.therealityteam.aeropages.platform.Trace.section("docx.body") { parseDocument(documentXml, numbering, rels, parts, commentsMap, styles) }
        } finally { paletteCache.remove(); styleIndents.remove(); numberingIndents.remove(); styleTabs.remove(); tableStyles.remove(); bookmarkState.remove(); listState.remove(); indexReading.remove() }
        doc.lists.putAll(lists.used)
        // A page break in the middle of a paragraph: the paragraph's text before it, the break, its text after.
        com.therealityteam.aeropages.model.PageBreaks.split(doc.blocks)
        // Reply threading + resolved flags (Word's commentsExtended.xml), matched through the comments' paraIds.
        parts["word/commentsExtended.xml"]?.let { parseCommentsExtended(it) }?.takeIf { it.isNotEmpty() }?.let { ext ->
            val byParaId = commentsMap.values.filter { it.paraId != null && it.created != null }.associateBy { it.paraId!! }
            commentsMap.values.forEach { pc ->
                val c = pc.created ?: return@forEach
                val (parentPara, done) = ext[pc.paraId ?: return@forEach] ?: return@forEach
                c.resolved = done
                parentPara?.let { pp -> byParaId[pp]?.created?.let { parent -> c.parentId = parent.id } }
            }
        }
        commentRangesRead(doc, commentsMap)
        doc.sourceFormat = DocFormat.DOCX
        doc.props = com.therealityteam.aeropages.format.OoxmlCore.read(parts[com.therealityteam.aeropages.format.OoxmlCore.PART])
        // A page without a header (footer) has no distance for one — LibreOffice writes 0 —: it keeps Word's default.
        val pages = listOf(doc.page) + doc.blocks.mapNotNull { (it as? com.therealityteam.aeropages.model.ParagraphBlock)?.paragraph?.format?.sectionBreak }
        val defaults = com.therealityteam.aeropages.model.PageSetup()
        if (doc.header == null && doc.evenHeader == null && doc.firstHeader == null) pages.forEach { it.headerPt = defaults.headerPt }
        if (doc.footer == null && doc.evenFooter == null && doc.firstFooter == null) pages.forEach { it.footerPt = defaults.footerPt }
        parts["word/footnotes.xml"]?.let { doc.footnotes.addAll(parseNotes(it, "footnote").map { com.therealityteam.aeropages.model.Footnote(it) }) }
        parts["word/endnotes.xml"]?.let { doc.endnotes.addAll(parseNotes(it, "endnote").map { com.therealityteam.aeropages.model.Endnote(it) }) }
        // Different odd & even headers is a document setting, not part of sectPr.
        parts["word/settings.xml"]?.let { bytes ->
            val settings = String(bytes, Charsets.UTF_8)
            if (settings.contains("evenAndOddHeaders")) doc.differentOddEven = true
            // Track Changes on (an explicit off — w:val 0 / false / off — is not).
            Regex("""<w:trackRevisions(?:\s+w:val="([^"]*)")?\s*/?>""").find(settings)?.let { m -> doc.trackRevisions = m.groupValues[1].lowercase() !in setOf("0", "false", "off") }
            // Lock Tracking: the protection that keeps Track Changes on, its password attributes kept as they are.
            Regex("""<w:documentProtection\b([^>]*?)/?>""").find(settings)?.let { m ->
                val attrs = Regex("""([\w:]+)="([^"]*)"""").findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
                if (attrs["w:edit"] == "trackedChanges" && attrs["w:enforcement"]?.lowercase() in setOf("1", "true", "on"))
                    doc.trackLock = attrs - "w:edit" - "w:enforcement"
            }
            Regex("""defaultTabStop\s+w:val="(\d+)"""").find(settings)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { doc.defaultTabPt = Ooxml.twipsToPt(it) }
        }
        // Watermark text carried as a custom document property (see DocxWriter.buildCustomProps).
        parts["docProps/custom.xml"]?.let { bytes ->
            val xml = bytes.toString(Charsets.UTF_8)
            fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
            Regex("name=\"AeroWatermark\"[^>]*>\\s*<vt:lpwstr>(.*?)</vt:lpwstr>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)?.let { doc.watermark = unescape(it) }
            // Bibliography sources: "author|title|year" records ';'-joined (fields %-escaped).
            Regex("name=\"AeroSources\"[^>]*>\\s*<vt:lpwstr>(.*?)</vt:lpwstr>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)?.let { enc ->
                    unescape(enc).split(';').forEach { rec ->
                        val f = rec.split('|')
                        if (f.size == 3) doc.sources.add(com.therealityteam.aeropages.model.Source(
                            DocxWriter.decodeSourceField(f[0]), DocxWriter.decodeSourceField(f[1]), DocxWriter.decodeSourceField(f[2])))
                    }
                }
        }
        return doc
    }

    /**
     * Parse styles.xml: document defaults plus every paragraph style (id, name, basedOn, the
     * character and paragraph traits we model). Character/table/numbering styles are skipped.
     */
    private fun parseStyles(
        bytes: ByteArray, indents: MutableMap<String, Indent>, tabs: MutableMap<String, MutableList<TabOp>>, tables: TableStyles,
        nums: MutableMap<String, Pair<String?, Int?>>,
    ): StyleSheet {
        val sheet = StyleSheet()
        var tableStyle: String? = null
        val parser = newParser(bytes)
        var event = parser.eventType
        var cur: StyleDef? = null
        var inDocDefaults = false
        var inPPr = false
        var inStylePart = false
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.namespace == Ooxml.W) {
                val v = parser.getAttributeValue(Ooxml.W, "val")
                when (parser.name) {
                    "docDefaults" -> inDocDefaults = true
                    "style" -> {
                        val type = parser.getAttributeValue(Ooxml.W, "type")
                        val id = parser.getAttributeValue(Ooxml.W, "styleId")
                        cur = if (type == "paragraph") id?.let { StyleDef(it) } else null
                        tableStyle = if (type == "table") id else null
                        if (type == "table" && parser.getAttributeValue(Ooxml.W, "default").let { it == "1" || it == "true" }) tables.default = id
                    }
                    // A table style's borders: its whole table's, or — a part's (header row, bands…) — that it draws some.
                    "tblStylePr" -> inStylePart = true
                    "tblBorders" -> tableStyle?.let { id ->
                        if (inStylePart) { if (parser.bordersVisible("tblBorders")) tables.partsDraw.add(id) }
                        else tables.sets[id] = WordBorders.readSet(parser, "tblBorders")
                    }
                    "name" -> cur?.let { if (v != null) it.name = v }
                    "basedOn" -> cur?.basedOn = v
                    "pPr" -> inPPr = true
                    "rPr" -> inPPr = false
                    "jc" -> if (inPPr) cur?.align = Ooxml.parseJc(v)
                    "outlineLvl" -> cur?.outlineLevel = v?.toIntOrNull()
                    "ind" -> if (inPPr) cur?.let { indents[it.id] = parser.readIndent() }
                    // Its paragraph borders and shading (a style's run shading, in its rPr, is not the paragraph's).
                    "pBdr" -> if (inPPr) cur?.let { it.borders = WordBorders.readParagraph(parser).takeIf { b -> !b.none } }
                    "shd" -> if (inPPr) cur?.let { it.shading = WordBorders.shadingOf(parser) }
                    // A style's list (headings numbered 1, 1.1 …): its numId, and its level when it names one.
                    "numId" -> if (inPPr) cur?.let { nums[it.id] = v to nums[it.id]?.second }
                    "ilvl" -> if (inPPr) cur?.let { nums[it.id] = nums[it.id]?.first to v?.toIntOrNull() }
                    "tab" -> if (inPPr) cur?.let { d -> parser.readTabOp()?.let { tabs.getOrPut(d.id) { ArrayList() }.add(it) } }
                    "spacing" -> if (inPPr) cur?.let { d ->
                        parser.getAttributeValue(Ooxml.W, "before")?.toIntOrNull()?.let { d.spaceBeforePt = Ooxml.twipsToPt(it) }
                        parser.getAttributeValue(Ooxml.W, "after")?.toIntOrNull()?.let { d.spaceAfterPt = Ooxml.twipsToPt(it) }
                        parser.getAttributeValue(Ooxml.W, "line")?.toIntOrNull()?.let { line ->
                            val rule = parser.getAttributeValue(Ooxml.W, "lineRule")
                            if (rule == "exact" || rule == "atLeast") { d.lineHeightPt = Ooxml.twipsToPt(line); d.lineExact = rule == "exact"; d.lineSpacing = null }
                            else { d.lineSpacing = line / 240f; d.lineHeightPt = null }
                        }
                    }
                    "b" -> if (!inPPr) cur?.bold = parser.boolAttr()
                    "i" -> if (!inPPr) cur?.italic = parser.boolAttr()
                    "sz" -> v?.toIntOrNull()?.let { hp -> if (cur != null) cur.sizePt = Ooxml.halfPtToPt(hp) else if (inDocDefaults) sheet.defaultSizePt = Ooxml.halfPtToPt(hp) }
                    "rFonts" -> (parser.getAttributeValue(Ooxml.W, "ascii") ?: parser.getAttributeValue(Ooxml.W, "hAnsi"))?.let { f ->
                        if (cur != null) cur.fontFamily = f else if (inDocDefaults) sheet.defaultFontFamily = f
                    }
                    "color" -> if (!inPPr) cur?.let { it.color = Ooxml.parseColor(v) }
                }
            } else if (event == XmlPullParser.END_TAG && parser.namespace == Ooxml.W) {
                when (parser.name) {
                    "style" -> { cur?.let { sheet.put(it) }; cur = null; inStylePart = false }
                    "docDefaults" -> inDocDefaults = false
                    "pPr" -> inPPr = false
                    "tblStylePr" -> inStylePart = false
                }
            }
            event = parser.next()
        }
        // Each style's own indents in points, a character as wide as the style's font is tall.
        for ((id, ind) in indents) {
            val def = sheet[id] ?: continue
            val em = sheet.resolve(id).sizePt
            ind.left?.let { def.indentLeftPt = indentPt(it, ind.leftChars, em) }
            ind.right?.let { def.indentRightPt = indentPt(it, ind.rightChars, em) }
            ind.first?.let { def.firstLineIndentPt = indentPt(it, ind.firstChars, em) }
        }
        return sheet
    }

    /**
     * A `w:ind` as written: each side in twips or — its `…Chars` attribute non-zero, which wins — in
     * hundredths of a character (字符: as wide as the paragraph's font is tall); the first line signed,
     * hanging negative; null where the element says nothing.
     */
    private class Indent {
        var left: Int? = null; var leftChars = false
        var right: Int? = null; var rightChars = false
        var first: Int? = null; var firstChars = false
    }

    /** The `w:ind` each paragraph style sets, for the paragraphs of this read that inherit it. */
    private val styleIndents = ThreadLocal<Map<String, Indent>?>()

    /** Each list's levels' `w:ind` (by numId, then level): a list item's indents unless it sets its own. */
    private val numberingIndents = ThreadLocal<Map<String, Map<Int, Indent>>?>()

    private fun indentPt(v: Int, chars: Boolean, em: Float): Float = if (chars) v / 100f * em else Ooxml.twipsToPt(v)

    /** The `w:ind` the parser is on (`start`/`end` as `left`/`right`; hanging before a first-line indent, as the schema has it). */
    private fun XmlPullParser.readIndent(): Indent {
        fun int(name: String) = getAttributeValue(Ooxml.W, name)?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
        val ind = Indent()
        fun side(chars: Int?, twips: Int?, set: (Int, Boolean) -> Unit) = when {
            chars != null && chars != 0 -> set(chars, true)
            twips != null -> set(twips, false)
            chars != null -> set(0, false)
            else -> Unit
        }
        side(int("leftChars") ?: int("startChars"), int("left") ?: int("start")) { v, c -> ind.left = v; ind.leftChars = c }
        side(int("rightChars") ?: int("endChars"), int("right") ?: int("end")) { v, c -> ind.right = v; ind.rightChars = c }
        val hangingChars = int("hangingChars"); val hanging = int("hanging")
        val firstChars = int("firstLineChars"); val first = int("firstLine")
        when {
            hangingChars != null && hangingChars != 0 -> { ind.first = -hangingChars; ind.firstChars = true }
            firstChars != null && firstChars != 0 -> { ind.first = firstChars; ind.firstChars = true }
            hanging != null && hanging != 0 -> ind.first = -hanging
            first != null -> ind.first = first
            hanging != null || hangingChars != null || firstChars != null -> ind.first = 0
        }
        return ind
    }

    /**
     * The table styles' borders (their own `w:tblBorders`, by side), the styles only a part of which draws
     * any (a header row's, a band's), and the default table style (Word's Normal Table, borderless).
     */
    private class TableStyles { val sets = HashMap<String, Map<String, com.therealityteam.aeropages.model.CellBorder?>>(); val partsDraw = HashSet<String>(); var default: String? = null }
    private val tableStyles = ThreadLocal<TableStyles?>()

    /**
     * The bookmarks being read (`w:bookmarkStart` names its `w:id`, `w:bookmarkEnd` only gives the id):
     * each id's name; the marks met between paragraphs — the next paragraph starts with them — and the
     * last paragraph read into the body or a cell, where an end met after it goes.
     */
    private class BookmarkState {
        val names = HashMap<String, String>()
        val pending = ArrayList<com.therealityteam.aeropages.model.BookmarkMark>()
        var last: Paragraph? = null
    }

    private val bookmarkState = ThreadLocal<BookmarkState?>()

    /**
     * Word's index being read: an `INDEX` field's result runs over paragraphs of their own (its entries as Word last laid
     * them out); they become one live [com.therealityteam.aeropages.model.IndexBlock] where the field starts.
     */
    private class IndexReading {
        /** The index the field asks for, from its result's first paragraph on (null: none being read). */
        var block: com.therealityteam.aeropages.model.IndexBlock? = null
        /** Whether the field's end has been met. */
        var ended = false
        var added = false
    }
    private val indexReading = ThreadLocal<IndexReading?>()

    /** Whether [para] was part of an index's cached result — then the index block stands in for it (added once). */
    private fun indexTook(doc: Document, para: Paragraph): Boolean {
        val st = indexReading.get() ?: return false
        val block = st.block ?: return false
        if (!st.added) { doc.blocks.add(block); st.added = true }
        // A section ending in the index (Word sets its index in a section of its own columns) still ends there.
        if (para.format.sectionBreak != null) { para.runs.clear(); para.runs.add(TextRun("")); para.bookmarks.clear(); doc.addParagraph(para) }
        if (st.ended) indexReading.set(IndexReading())
        return true
    }

    /** A bookmark's start or end met in paragraph [para] (where its text so far ends), or between paragraphs (null). */
    private fun bookmarkMark(parser: XmlPullParser, para: Paragraph?) {
        val st = bookmarkState.get() ?: return
        val id = parser.getAttributeValue(Ooxml.W, "id") ?: return
        val end = parser.name == "bookmarkEnd" || parser.name == "commentRangeEnd"
        // A comment's range: marks under Word's comment id for now, the comment's own once it is read (see read).
        val name = if (parser.name.startsWith("commentRange")) COMMENT_RANGE + id
            else if (end) st.names[id] ?: return else (parser.getAttributeValue(Ooxml.W, "name") ?: return).also { st.names[id] = it }
        when {
            para != null -> para.bookmarks.add(com.therealityteam.aeropages.model.BookmarkMark(name, para.runs.sumOf { it.text.length }, end))
            // Between paragraphs: a start goes to the next paragraph's start, an end to the last one's end
            // (or with its start, when that has not found a paragraph yet).
            !end || st.pending.any { it.name == name } || st.last == null -> st.pending.add(com.therealityteam.aeropages.model.BookmarkMark(name, 0, end))
            else -> st.last!!.let { p -> p.bookmarks.add(com.therealityteam.aeropages.model.BookmarkMark(name, p.runs.sumOf { it.text.length }, true)) }
        }
    }

    /** The name a comment's range marks are read under, Word's comment id after it. */
    private const val COMMENT_RANGE = "\u0001w"

    /**
     * The comment ranges read (marked by Word's comment ids) as the comments' own marks — a reply's range is its
     * thread's, so it goes —, each thread on the paragraph its range starts in; ranges of comments never referenced go.
     */
    private fun commentRangesRead(doc: Document, comments: Map<String, ParsedComment>) {
        val locs = doc.locations()
        if (locs.none { l -> l.paragraph.bookmarks.any { it.name.startsWith(COMMENT_RANGE) } }) return
        locs.forEach { l ->
            val marks = l.paragraph.bookmarks
            for (i in marks.indices.reversed()) {
                val m = marks[i]
                if (!m.name.startsWith(COMMENT_RANGE)) continue
                val c = comments[m.name.substring(COMMENT_RANGE.length)]?.created
                if (c == null || c.parentId != null) marks.removeAt(i)
                else marks[i] = m.copy(name = com.therealityteam.aeropages.model.Bookmarks.commentMark(c.id))
            }
        }
        com.therealityteam.aeropages.model.Bookmarks.commentRanges(doc).forEach { (id, f) ->
            val target = locs[f.startPara].paragraph.id
            doc.comments.forEach { c -> if (c.id == id || c.parentId == id) c.targetId = target }
        }
    }

    /** A paragraph read into the body or a cell: an end met between paragraphs after it goes to its end. */
    private fun bookmarksReach(para: Paragraph) {
        val st = bookmarkState.get() ?: return
        st.last = para
    }

    /**
     * Word's drop cap: [frame] (a paragraph of its own, `w:framePr w:dropCap`, holding the letters set large) becomes
     * [para]'s first letters, in [para]'s size (the letters' own font kept as the drop cap's), with its bookmarks and comments.
     */
    private fun mergeDropCap(frame: Paragraph, para: Paragraph, doc: Document) {
        val d = frame.format.dropCap ?: return
        com.therealityteam.aeropages.model.DropCaps.merge(frame, para, d)
        doc.comments.filter { it.targetId == frame.id }.forEach { it.targetId = para.id }
    }

    /** A paragraph read but left out (only pictures): its marks pass to the next paragraph. */
    private fun bookmarksPassOn(para: Paragraph) {
        val st = bookmarkState.get() ?: return
        if (para.bookmarks.isNotEmpty()) st.pending.addAll(0, para.bookmarks.map { it.copy(offset = 0) })
        para.bookmarks.clear()
    }

    /** A plain grid of Word's default line (single, half a point, automatic colour) on every side. */
    private val GRID: Map<String, com.therealityteam.aeropages.model.CellBorder?> =
        listOf("top", "left", "bottom", "right", "insideH", "insideV").associateWith { com.therealityteam.aeropages.model.CellBorder() }

    /** The borders the cells of the table being read set themselves (`w:tcBorders`), by cell. */
    private val cellBorderSets = ThreadLocal<java.util.IdentityHashMap<com.therealityteam.aeropages.model.TableCell, Map<String, com.therealityteam.aeropages.model.CellBorder?>>?>()

    /** Whether the borders element [tag] the parser is on draws any side (a `w:val` other than nil or none); consumes it. */
    private fun XmlPullParser.bordersVisible(tag: String): Boolean {
        var drawn = false
        var event = next()
        while (!(event == XmlPullParser.END_TAG && name == tag) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) getAttributeValue(Ooxml.W, "val")?.let { if (it != "nil" && it != "none") drawn = true }
            event = next()
        }
        return drawn
    }

    /** A `w:tab`: a stop set, or — [clear] its position — one an inherited style set taken away. */
    private class TabOp(val stop: com.therealityteam.aeropages.model.TabStop?, val clear: Float?)

    /** The `w:tab` each paragraph style sets or clears, for the paragraphs of this read that inherit them. */
    private val styleTabs = ThreadLocal<Map<String, List<TabOp>>?>()

    /** The `w:tab` the parser is on (a bar tab draws a line, which is not kept). */
    private fun XmlPullParser.readTabOp(): TabOp? {
        val pos = getAttributeValue(Ooxml.W, "pos")?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() } ?: return null
        val pt = Ooxml.twipsToPt(pos)
        val align = when (getAttributeValue(Ooxml.W, "val")) {
            "clear" -> return TabOp(null, pt)
            "bar" -> return null
            "center" -> com.therealityteam.aeropages.model.TabAlign.CENTER
            "right", "end" -> com.therealityteam.aeropages.model.TabAlign.RIGHT
            "decimal" -> com.therealityteam.aeropages.model.TabAlign.DECIMAL
            else -> com.therealityteam.aeropages.model.TabAlign.LEFT
        }
        return TabOp(com.therealityteam.aeropages.model.TabStop(pt, align, Ooxml.parseLeader(getAttributeValue(Ooxml.W, "leader"))), null)
    }

    /** [ops] applied to [stops]: a stop takes the place of any at its position, a clear removes the one there. */
    private fun applyTabs(stops: MutableList<com.therealityteam.aeropages.model.TabStop>, ops: List<TabOp>) {
        for (op in ops) {
            val at = op.stop?.posPt ?: op.clear ?: continue
            stops.removeAll { kotlin.math.abs(it.posPt - at) < 0.5f }
            op.stop?.let { stops.add(it) }
        }
    }

    /** [id]'s style and the styles it is based on, nearest first. */
    private fun styleChain(styles: StyleSheet, id: String?): List<String> {
        val out = ArrayList<String>()
        var cur = styles[id]
        while (cur != null && out.size < 16 && out.none { it.equals(cur!!.id, ignoreCase = true) }) { out.add(cur.id); cur = styles[cur.basedOn] }
        return out
    }

    /** The indents of [id]'s style and the styles it is based on, nearest first. */
    private fun indentChain(styles: StyleSheet, id: String?): List<Indent> {
        val raw = styleIndents.get() ?: return emptyList()
        val out = ArrayList<Indent>()
        var cur = styles[id]
        val seen = HashSet<String>()
        while (cur != null && seen.size < 16 && seen.add(cur.id.lowercase())) { raw[cur.id]?.let { out.add(it) }; cur = styles[cur.basedOn] }
        return out
    }

    /** Which paragraph properties a `w:pPr` set explicitly (the rest inherit from the style). */
    private class PPrSeen {
        var jc = false; var before = false; var after = false; var line = false; var ind: Indent? = null; var numId: String? = null; var ilvl = false
        /** Its own borders and shading (`w:pBdr`, `w:shd`), which replace its style's — nil included. */
        var borders = false; var shading = false
        val tabs = ArrayList<TabOp>()
        /** The paragraph mark's character properties (`w:pPr/w:rPr`): an empty paragraph's line is as tall as its mark's text. */
        var mark: CharacterFormat? = null; val markSeen = RunSeen()
    }
    /** Which toggle run properties a `w:rPr` set explicitly. */
    private class RunSeen { var bold = false; var italic = false; var before: RunSeen? = null }

    private const val W14 = "http://schemas.microsoft.com/office/word/2010/wordml"
    private const val W15 = "http://schemas.microsoft.com/office/word/2012/wordml"

    /** A comment from comments.xml; [created] is the model comment once a reference to it was met. */
    private class ParsedComment(val author: String, val text: String, val dateMs: Long, val paraId: String?) {
        var created: com.therealityteam.aeropages.model.Comment? = null
        fun toComment(targetId: Long): com.therealityteam.aeropages.model.Comment =
            com.therealityteam.aeropages.model.Comment(author, text, targetId, dateMs = dateMs).also { created = it }
    }

    /** Parse comments.xml into commentId -> comment (author, text, date, the paragraph's w14:paraId). */
    private fun parseComments(bytes: ByteArray): Map<String, ParsedComment> {
        val out = HashMap<String, ParsedComment>()
        val parser = newParser(bytes)
        var id: String? = null
        var author = ""
        var date: Long? = null
        var paraId: String? = null
        val text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.namespace == Ooxml.W && parser.name == "comment") {
                id = parser.getAttributeValue(Ooxml.W, "id")
                author = parser.getAttributeValue(Ooxml.W, "author") ?: ""
                date = Ooxml.parseDate(parser.getAttributeValue(Ooxml.W, "date"))
                paraId = null
                text.setLength(0)
            } else if (event == XmlPullParser.START_TAG && parser.namespace == Ooxml.W && parser.name == "p" && id != null && paraId == null) {
                paraId = parser.getAttributeValue(W14, "paraId")
            } else if (event == XmlPullParser.TEXT && id != null) {
                text.append(parser.text)
            } else if (event == XmlPullParser.END_TAG && parser.namespace == Ooxml.W && parser.name == "comment") {
                if (id != null) out[id!!] = ParsedComment(author, text.toString().trim(), date ?: 0L, paraId)
                id = null
            }
            event = parser.next()
        }
        return out
    }

    /** commentsExtended.xml: paraId -> (parent paraId, done). */
    private fun parseCommentsExtended(bytes: ByteArray): Map<String, Pair<String?, Boolean>> {
        val out = HashMap<String, Pair<String?, Boolean>>()
        val parser = newParser(bytes)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "commentEx") {
                val pid = parser.getAttributeValue(W15, "paraId")
                if (pid != null) out[pid] = parser.getAttributeValue(W15, "paraIdParent") to (parser.getAttributeValue(W15, "done") == "1")
            }
            event = parser.next()
        }
        return out
    }

    /** Parse footnotes.xml / endnotes.xml into id-ordered note texts (separator/continuation notes skipped). */
    private fun parseNotes(bytes: ByteArray, element: String): List<String> {
        val byId = sortedMapOf<Int, String>()
        val parser = newParser(bytes)
        var id: Int? = null
        val text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.namespace == Ooxml.W && parser.name == element) {
                val type = parser.getAttributeValue(Ooxml.W, "type")
                id = if (type == null) parser.getAttributeValue(Ooxml.W, "id")?.toIntOrNull() else null
                text.setLength(0)
            } else if (event == XmlPullParser.TEXT && id != null) {
                text.append(parser.text)
            } else if (event == XmlPullParser.END_TAG && parser.namespace == Ooxml.W && parser.name == element) {
                id?.let { if (it > 0) byId[it] = text.toString().trim() }
                id = null
            }
            event = parser.next()
        }
        return byId.values.toList()
    }

    /** Parse document.xml.rels into rId -> target (relative to word/). */
    private fun parseRels(bytes: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        val parser = newParser(bytes)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                if (id != null && target != null) out[id] = target
            }
            event = parser.next()
        }
        return out
    }

    /** A drawing found inside a paragraph: relationship id + display size in pt. */
    /** A picture met in a run: its relationship, its extent and — from the `a:srcRect` after its blip — its crop. */
    private class FoundImage(val rId: String, var widthPt: Float, var heightPt: Float, val chart: Boolean = false) : com.therealityteam.aeropages.model.Croppable {
        override var cropL = 0f; override var cropT = 0f; override var cropR = 0f; override var cropB = 0f
        /** Word's SVG picture: the SVG itself (`asvg:svgBlip`) beside the PNG stand-in [rId] names. */
        var svgRId: String? = null
        /** The picture's outer shadow (`pic:spPr`'s effect list). */
        var shadow: com.therealityteam.aeropages.model.ShapeShadow? = null
        /** Where it stands when its drawing floats (`wp:anchor`); its transform's flips. */
        var floating: com.therealityteam.aeropages.model.FloatPosition? = null
        var flipH = false; var flipV = false
        /** Its drawing's alternative text (`wp:docPr@descr`) and decorative mark (`adec:decorative`). */
        var altText = ""; var decorative = false
    }

    /** DrawingML's picture namespace (`pic:pic`, `pic:spPr`). */
    private const val PIC_NS = "http://schemas.openxmlformats.org/drawingml/2006/picture"

    /** DrawingML's chart namespace (`c:chart` names a drawing's chart part). */
    private const val C_NS = "http://schemas.openxmlformats.org/drawingml/2006/chart"
    /** Office 2019's decorative mark on a drawing (`adec:decorative` in its docPr's extension list). */
    internal const val ADEC_NS = "http://schemas.microsoft.com/office/drawing/2017/decorative"

    /**
     * What a text box's own paragraphs need to be parsed, plus the boxes found so far; [parts] give
     * the theme a drawn shape's colours come from.
     */
    private class BoxCtx(val numbering: Map<String, ListKind>, val rels: Map<String, String>, val styles: StyleSheet, val parts: Map<String, ByteArray>) {
        val boxes = ArrayList<com.therealityteam.aeropages.model.TextBoxBlock>()
        /** Tables a text box holds (LibreOffice keeps a text frame's table so): they stand where the box does. */
        val tables = java.util.IdentityHashMap<com.therealityteam.aeropages.model.TextBoxBlock, List<com.therealityteam.aeropages.model.Table>>()
        val palette: com.therealityteam.aeropages.format.pptx.PptxReader.Palette by lazy { themePalette(parts) }
    }

    /** The document's theme (word/theme): what a shape's scheme colours and style references are; read once per document. */
    private val paletteCache = ThreadLocal<Pair<Map<String, ByteArray>, com.therealityteam.aeropages.format.pptx.PptxReader.Palette>?>()

    private fun themePalette(parts: Map<String, ByteArray>): com.therealityteam.aeropages.format.pptx.PptxReader.Palette {
        paletteCache.get()?.let { (p, palette) -> if (p === parts) return palette }
        val theme = parts.keys.sorted().firstOrNull { it.startsWith("word/theme/") && it.endsWith(".xml") }?.let { com.therealityteam.aeropages.format.XmlPull.parse(parts[it]) }
        return com.therealityteam.aeropages.format.pptx.PptxReader.paletteOf(theme).also { paletteCache.set(parts to it) }
    }

    /** Word's drawn shapes' namespace (`wps:wsp`). */
    private const val WPS_NS = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"

    /** Skip the element the parser is positioned on (its START_TAG was just read), including everything inside it. */
    private fun XmlPullParser.skipSubtree() {
        var depth = 1
        while (depth > 0) {
            when (next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** `w:txbxContent`: the paragraphs inside a text box (wps or VML). Null when no [ctx] collects boxes here. */
    private fun XmlPullParser.parseTextBox(ctx: BoxCtx?, widthPt: Float, heightPt: Float): com.therealityteam.aeropages.model.TextBoxBlock? {
        val paragraphs = ArrayList<Paragraph>()
        val tables = ArrayList<com.therealityteam.aeropages.model.Table>()
        var event = next()
        while (!(event == XmlPullParser.END_TAG && name == "txbxContent" && namespace == Ooxml.W)) {
            if (event == XmlPullParser.START_TAG && namespace == Ooxml.W && name == "p") {
                if (ctx != null) paragraphs.add(parseParagraph(this, ctx.numbering, ArrayList(), ctx.rels, ArrayList(), ctx.styles, null))
                else skipSubtree()
            } else if (event == XmlPullParser.START_TAG && namespace == Ooxml.W && name == "tbl") {
                if (ctx != null) tables.add(parseTable(this, ctx.numbering, ctx.rels, emptyMap(), Document(mutableListOf(), styles = ctx.styles), ctx.parts))
                else skipSubtree()
            }
            if (event == XmlPullParser.END_DOCUMENT) break
            event = next()
        }
        if (ctx == null) return null
        return com.therealityteam.aeropages.model.TextBoxBlock(
            paragraphs.ifEmpty { listOf(Paragraph.of("")) }.toMutableList(),
            widthPt.takeIf { it > 0f }, heightPt.takeIf { it > 0f },
        ).also { if (tables.isNotEmpty()) ctx.tables[it] = tables }
    }

    /**
     * A drawn shape's end: its look (DrawingML's, as slides and sheets read it) on the text box its
     * text went into — its uncoloured text in the shape style's font colour, Word's white on accent
     * blue — or, a shape without text, a box of its own holding none. Its size is its own extent,
     * else the drawing's ([w] × [h]).
     */
    private fun finishShape(node: com.therealityteam.aeropages.format.XmlNode, textBox: com.therealityteam.aeropages.model.TextBoxBlock?, ctx: BoxCtx, w: Float, h: Float,
                            floating: com.therealityteam.aeropages.model.FloatPosition? = null) {
        val shape = com.therealityteam.aeropages.format.pptx.DrawingShape.read(node, ctx.palette) { com.therealityteam.aeropages.model.SlideBox("") }
        val ext = node.child("spPr")?.child("xfrm")?.child("ext")
        val width = ext?.attr("cx")?.toLongOrNull()?.let { Ooxml.emuToPt(it) } ?: w
        val height = ext?.attr("cy")?.toLongOrNull()?.let { Ooxml.emuToPt(it) } ?: h
        if (textBox != null) {
            textBox.shape = shape
            if (width > 0f) textBox.widthPt = width
            if (height > 0f) textBox.heightPt = height
            node.child("style")?.child("fontRef")?.let { ctx.palette.colorOf(it) }?.let { color ->
                textBox.paragraphs.forEach { p -> p.runs.forEach { if (it.format.color == null) it.format.color = color } }
            }
        } else if (shape.fill != null || shape.border != null) {
            ctx.boxes.add(com.therealityteam.aeropages.model.TextBoxBlock(mutableListOf(), width.takeIf { it > 0f }, height.coerceAtLeast(0f), shape, floating?.copy()))
        }
    }

    /** A field being read in a header: its code, then — past its separator — its result, shown or (a page number's) replaced. */
    private class HfField(val code: StringBuilder = StringBuilder(), var result: Boolean = false, var token: String? = null,
                          /** A live field (SEQ, REF, PAGEREF): its result's runs are its own. */
                          var live: com.therealityteam.aeropages.model.DocField? = null)

    private fun fieldToken(code: CharSequence): String? = com.therealityteam.aeropages.model.HeaderFooters.fieldToken(code)

    /** A header's or footer's text as one paragraph (null: none) and its pictures. */
    private class HdrFtr(val paragraph: Paragraph?, val pictures: List<com.therealityteam.aeropages.model.ImageBlock>)

    /**
     * A header or footer part (`w:hdr` / `w:ftr`): its text as one paragraph — its paragraphs' runs,
     * formatted as their styles and properties say, a line break between paragraphs, tabs kept (they
     * move on to the centre and right slots, see HeaderFooters), the page number and page count fields
     * as their tokens (any other field shows what it last showed), the alignment of the first that
     * holds text; null when it shows none — and its pictures (a letterhead's logo): floating where their
     * anchors put them, or in their line (a paragraph holding only a picture is a line of its own). A VML
     * watermark in it — where Word keeps one — goes to [watermark].
     */
    private fun loadHdrFtr(rId: String?, rels: Map<String, String>, parts: Map<String, ByteArray>, styles: StyleSheet, watermark: (String) -> Unit): HdrFtr? {
        val target = rels[rId ?: return null] ?: return null
        val path = "word/" + target.removePrefix("/").removePrefix("word/")
        val bytes = parts[path] ?: return null
        val partRels by lazy { parts[com.therealityteam.aeropages.format.XmlPull.relsOf(path)]?.let { parseRels(it) } ?: emptyMap() }
        val anchored = ArrayList<com.therealityteam.aeropages.model.ImageBlock>()
        // In-line pictures, each with the line (counted in its paragraph) it stands on.
        class Para(var styleId: String? = null, var jc: com.therealityteam.aeropages.model.TextAlign? = null, val runs: MutableList<TextRun> = ArrayList(),
                   val inline: MutableList<Pair<com.therealityteam.aeropages.model.ImageBlock, Int>> = ArrayList())
        val lines = ArrayList<Para>()
        val open = ArrayList<Para>()
        val fields = ArrayList<HfField>()
        fun hidden() = fields.any { !it.result || it.token != null }
        val parser = newParser(bytes)
        var inPPr = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.namespace == Ooxml.W) {
                val cur = open.lastOrNull()
                when (parser.name) {
                    "p" -> open.add(Para())
                    "pPr" -> inPPr = true
                    "pStyle" -> if (inPPr) cur?.styleId = parser.getAttributeValue(Ooxml.W, "val")
                    "jc" -> if (inPPr) cur?.jc = Ooxml.parseJc(parser.getAttributeValue(Ooxml.W, "val"))
                    "fldSimple" -> {
                        val f = HfField(StringBuilder(parser.getAttributeValue(Ooxml.W, "instr").orEmpty()), result = true)
                        f.token = fieldToken(f.code)
                        f.token?.let { t -> if (!hidden()) cur?.runs?.add(TextRun(t, styleFormat(styles, cur.styleId))) }
                        fields.add(f)
                    }
                    "r" -> if (cur != null) {
                        val style = styles.resolve(cur.styleId ?: styles.idOf(com.therealityteam.aeropages.model.ParagraphStyle.NORMAL))
                        val fmt = CharacterFormat()
                        val seen = RunSeen()
                        val sb = StringBuilder()
                        fun traits() {
                            if (!seen.bold) fmt.bold = style.bold
                            if (!seen.italic) fmt.italic = style.italic
                            if (fmt.fontSizePt == null) fmt.fontSizePt = style.sizePt
                            if (fmt.fontFamily == null) fmt.fontFamily = style.fontFamily
                            if (fmt.color == null) fmt.color = style.color
                        }
                        fun flush() { if (sb.isNotEmpty()) { traits(); if (!hidden()) cur.runs.add(TextRun(sb.toString(), fmt.copyOf())); sb.clear() } }
                        event = parser.next()
                        while (!(event == XmlPullParser.END_TAG && parser.name == "r" && parser.namespace == Ooxml.W) && event != XmlPullParser.END_DOCUMENT) {
                            if (event == XmlPullParser.START_TAG && parser.namespace == Ooxml.W) when (parser.name) {
                                "rPr" -> parser.parseRunProps(fmt, seen)
                                "t" -> sb.append(parser.readElementText("t"))
                                "tab" -> sb.append('\t')
                                // An absolute tab to the centre or the right margin: on to that slot.
                                "ptab" -> {
                                    val line = (cur.runs.joinToString("") { it.text } + sb).substringAfterLast('\n')
                                    val want = when (parser.getAttributeValue(Ooxml.W, "alignment")) { "center" -> 1; "right" -> 2; else -> 0 }
                                    repeat(maxOf(1, want - line.count { it == '\t' })) { sb.append('\t') }
                                }
                                "br", "cr" -> sb.append('\n')
                                "noBreakHyphen" -> sb.append('-')
                                "drawing" -> hfPicture(com.therealityteam.aeropages.format.XmlPull.readSubtree(parser), path, partRels, parts)?.let { (img, inline) ->
                                    if (!inline) anchored.add(img)
                                    else cur.inline.add(img to cur.runs.sumOf { r -> r.text.count { it == '\n' } } + sb.count { it == '\n' })
                                }
                                "instrText" -> fields.lastOrNull()?.code?.append(parser.readElementText("instrText"))
                                "fldChar" -> {
                                    flush()
                                    when (parser.getAttributeValue(Ooxml.W, "fldCharType")) {
                                        "begin" -> fields.add(HfField())
                                        "separate" -> fields.lastOrNull()?.let { f ->
                                            f.token = fieldToken(f.code)
                                            f.result = true
                                            f.token?.let { t -> traits(); if (fields.dropLast(1).none { !it.result || it.token != null }) cur.runs.add(TextRun(t, fmt.copyOf())) }
                                        }
                                        "end" -> fields.removeLastOrNull()?.let { f ->
                                            // A field with no result shown: a page number still shows.
                                            if (!f.result) fieldToken(f.code)?.let { t -> traits(); if (!hidden()) cur.runs.add(TextRun(t, fmt.copyOf())) }
                                        }
                                    }
                                }
                            } else if (event == XmlPullParser.START_TAG && parser.name == "textpath") {
                                parser.getAttributeValue(null, "string")?.takeIf { it.isNotBlank() }?.let(watermark)
                            }
                            event = parser.next()
                        }
                        flush()
                    }
                }
            } else if (event == XmlPullParser.START_TAG && parser.name == "textpath") {
                parser.getAttributeValue(null, "string")?.takeIf { it.isNotBlank() }?.let(watermark)
            } else if (event == XmlPullParser.END_TAG && parser.namespace == Ooxml.W) {
                when (parser.name) {
                    "p" -> open.removeLastOrNull()?.let { lines.add(it) }
                    "pPr" -> inPPr = false
                    "fldSimple" -> fields.removeLastOrNull()
                }
            }
            event = parser.next()
        }
        fun texty(l: Para) = l.runs.any { it.text.isNotBlank() }
        fun alignOf(l: Para) = l.jc ?: styles.resolve(l.styleId).align ?: com.therealityteam.aeropages.model.TextAlign.LEFT
        val shown = lines.dropWhile { !texty(it) && it.inline.isEmpty() }.dropLastWhile { !texty(it) && it.inline.isEmpty() }
        // Each paragraph's first line among the header's lines (a paragraph's own breaks make more).
        val pictures = ArrayList<com.therealityteam.aeropages.model.ImageBlock>(anchored)
        var lineNo = 0
        shown.forEach { l ->
            l.inline.forEach { (img, within) -> img.floating = com.therealityteam.aeropages.model.FloatPlacement.inHeaderLine(lineNo + within, alignOf(l)); pictures.add(img) }
            lineNo += 1 + l.runs.sumOf { r -> r.text.count { it == '\n' } }
        }
        val first = shown.firstOrNull { texty(it) } ?: return HdrFtr(null, pictures)
        val runs = ArrayList<TextRun>()
        shown.forEachIndexed { i, l ->
            if (i > 0) runs.add(TextRun("\n", (l.runs.firstOrNull()?.format ?: runs.lastOrNull()?.format ?: first.runs.first().format).copyOf()))
            runs.addAll(l.runs)
        }
        return HdrFtr(Paragraph(runs, ParagraphFormat(align = alignOf(first), spaceBeforePt = 0f, spaceAfterPt = 0f)), pictures)
    }

    /**
     * A drawing in a header or footer part ([path], its relationships [partRels]): its picture at its extent
     * and crop, mirrored as it says, floating where its anchor puts it — and whether it stands in its line
     * instead (`wp:inline`). Null for a drawing holding no picture of the package.
     */
    private fun hfPicture(drawing: com.therealityteam.aeropages.format.XmlNode, path: String, partRels: Map<String, String>,
                          parts: Map<String, ByteArray>): Pair<com.therealityteam.aeropages.model.ImageBlock, Boolean>? {
        val holder = drawing.child("anchor") ?: drawing.child("inline") ?: return null
        val blip = holder.descendants("blip").firstOrNull() ?: return null
        val media = partRels[blip.attr(Ooxml.R, "embed") ?: return null] ?: return null
        val data = parts[com.therealityteam.aeropages.format.XmlPull.resolve(path, media)] ?: parts["word/" + media.removePrefix("/").removePrefix("word/")] ?: return null
        val ext = holder.child("extent")
        val w = ext?.attr("cx")?.toLongOrNull()?.let { Ooxml.emuToPt(it) }?.takeIf { it > 0f } ?: return null
        val h = ext.attr("cy")?.toLongOrNull()?.let { Ooxml.emuToPt(it) }?.takeIf { it > 0f } ?: return null
        val img = com.therealityteam.aeropages.model.ImageBlock(data, com.therealityteam.aeropages.format.PictureType.mimeOf(data, media), w, h)
        holder.descendants("srcRect").firstOrNull()?.let { r -> com.therealityteam.aeropages.format.PictureCrop.readSrcRect({ n -> r.attr(n) }, img) }
        holder.descendants("xfrm").firstOrNull()?.let { x -> img.flipH = x.attr("flipH").let { it == "1" || it == "true" }; img.flipV = x.attr("flipV").let { it == "1" || it == "true" } }
        if (holder.name == "inline") return img to true
        val behind = holder.attr("behindDoc").let { it == "1" || it == "true" }
        val pos = com.therealityteam.aeropages.model.FloatPosition()
        holder.child("positionH")?.let { DrawingAnchors.readPosition(it, pos, true) }
        holder.child("positionV")?.let { DrawingAnchors.readPosition(it, pos, false) }
        holder.children.firstNotNullOfOrNull { DrawingAnchors.wrapOf(it.name, behind) }?.let { pos.wrap = it }
        img.floating = pos
        return img to false
    }

    /** A run's format from [styleId]'s traits alone (a field's token outside any run). */
    private fun styleFormat(styles: StyleSheet, styleId: String?): CharacterFormat {
        val s = styles.resolve(styleId ?: styles.idOf(com.therealityteam.aeropages.model.ParagraphStyle.NORMAL))
        return CharacterFormat(bold = s.bold, italic = s.italic, fontSizePt = s.sizePt, fontFamily = s.fontFamily, color = s.color)
    }

    private fun newParser(bytes: ByteArray): XmlPullParser = XmlPull.newParser(bytes)

    // --- numbering.xml: map numId -> bullet/number -----------------------------

    /** numbering.xml: each list's kind (by its level 0) and, into [levelIndents], its levels' indents — by numId. */
    private fun parseNumbering(bytes: ByteArray, levelIndents: MutableMap<String, Map<Int, Indent>>): Map<String, ListKind> {
        val abstractFmt = HashMap<String, ListKind>()   // abstractNumId -> kind (level 0)
        val abstractIndents = HashMap<String, HashMap<Int, Indent>>()   // abstractNumId -> level -> w:ind
        val numToAbstract = HashMap<String, String>()   // numId -> abstractNumId
        val parser = newParser(bytes)
        var currentAbstract: String? = null
        var currentNum: String? = null
        var levelZeroSeen = false
        var level = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.namespace == Ooxml.W && parser.name == "abstractNum") currentAbstract = null
            if (event == XmlPullParser.START_TAG && parser.namespace == Ooxml.W) {
                when (parser.name) {
                    "abstractNum" -> {
                        currentAbstract = parser.getAttributeValue(Ooxml.W, "abstractNumId")
                        levelZeroSeen = false
                    }
                    "lvl" -> {
                        val ilvl = parser.getAttributeValue(Ooxml.W, "ilvl")
                        levelZeroSeen = (ilvl == "0")
                        level = ilvl?.toIntOrNull() ?: 0
                    }
                    "ind" -> currentAbstract?.let { abstractIndents.getOrPut(it) { HashMap() }[level] = parser.readIndent() }
                    "numFmt" -> if (levelZeroSeen && currentAbstract != null) {
                        val v = parser.getAttributeValue(Ooxml.W, "val")
                        abstractFmt[currentAbstract!!] =
                            if (v == "bullet") ListKind.BULLET else ListKind.NUMBER
                    }
                    "num" -> currentNum = parser.getAttributeValue(Ooxml.W, "numId")
                    "abstractNumId" -> if (currentNum != null) {
                        numToAbstract[currentNum!!] = parser.getAttributeValue(Ooxml.W, "val") ?: ""
                    }
                }
            }
            event = parser.next()
        }
        numToAbstract.forEach { (num, abs) -> abstractIndents[abs]?.let { levelIndents[num] = it } }
        return numToAbstract.mapValues { (_, abs) -> abstractFmt[abs] ?: ListKind.BULLET }
    }

    // --- document.xml ----------------------------------------------------------

    /**
     * The rest of a contents control (the parser past its `w:sdtPr`): its cached entries are skipped; the caption label
     * when its field is a table of figures (`TOC \c "Figure"`), else null.
     */
    private fun tocCaption(parser: XmlPullParser): String? {
        val code = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (parser.name == "instrText" && code.length < 400) { code.append(parser.nextText()); depth-- }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return TOC_CAPTION.find(code)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }?.takeIf { it.isNotBlank() }
    }

    /** A TOC field's `\c` switch: its caption label, quoted or not. */
    private val TOC_CAPTION = Regex("""\\c\s+(?:"([^"]*)"|(\S+))""")

    /**
     * At a body-level `w:sdt`: reads its `w:sdtPr` (leaving the parser on that element's end tag)
     * and reports whether the control is Word's table of contents (`docPartGallery` = "Table of Contents").
     */
    /** The building-block gallery a content control belongs to (`w:docPartGallery`: "Table of Contents", "Cover Pages"…), reading its `w:sdtPr`. */
    private fun sdtGallery(parser: XmlPullParser): String? {
        var e = parser.next()
        while (e == XmlPullParser.TEXT) e = parser.next()
        if (e != XmlPullParser.START_TAG || parser.name != "sdtPr") return null
        var gallery: String? = null
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (parser.name == "docPartGallery") gallery = parser.getAttributeValue(Ooxml.W, "val")
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return gallery
            }
        }
        return gallery
    }

    private fun parseDocument(
        bytes: ByteArray,
        numbering: Map<String, ListKind>,
        rels: Map<String, String>,
        parts: Map<String, ByteArray>,
        commentsMap: Map<String, ParsedComment>,
        styles: StyleSheet,
    ): Document {
        val doc = Document(mutableListOf(), styles = styles)
        val parser = newParser(bytes)
        var event = parser.eventType
        // A drop cap's frame paragraph, held until the paragraph whose letters it holds comes.
        var dropFrame: Paragraph? = null
        fun flushFrame() {
            val frame = dropFrame ?: return
            dropFrame = null
            frame.format.dropCap = null
            doc.addParagraph(frame); bookmarksReach(frame)
        }
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.namespace == Ooxml.W) {
                if (parser.name == "tbl" || parser.name == "sdt" || parser.name == "sectPr") flushFrame()
                when (parser.name) {
                    "p" -> run p@{
                        val found = ArrayList<FoundImage>()
                        val foundComments = ArrayList<String>()
                        val boxes = BoxCtx(numbering, rels, doc.styles, parts)
                        val equations = ArrayList<com.therealityteam.aeropages.model.EquationBlock>()
                        val para = parseParagraph(parser, numbering, found, rels, foundComments, doc.styles, boxes, equations)
                        if (indexTook(doc, para)) return@p
                        foundComments.forEach { cid ->
                            commentsMap[cid]?.let { pc -> doc.comments.add(pc.toComment(para.id)) }
                        }
                        val plainText = found.isEmpty() && boxes.boxes.isEmpty() && equations.isEmpty() && para.format.sectionBreak == null
                        // The paragraph after a drop cap's frame takes its letters; anything else, and the frame is a paragraph of its own.
                        var merged = false
                        dropFrame?.let { frame ->
                            if (plainText && para.format.dropCap == null && para.plainText().isNotEmpty() && !para.plainText()[0].isWhitespace()) {
                                dropFrame = null
                                mergeDropCap(frame, para, doc)
                                merged = true
                            } else flushFrame()
                        }
                        if (!merged && para.format.dropCap != null && plainText && para.plainText().isNotBlank() && para.plainText().length <= 32) { dropFrame = para }
                        else {
                        // (A frame too long for a drop cap's letters, or holding more than text: an ordinary paragraph.)
                        if (!merged) para.format.dropCap = null
                        // The paragraph's text comes first, then its displayed equations, inline pictures and text
                        // boxes as their own blocks (a paragraph that only holds one of them contributes just that —
                        // unless it ends a section, or something floats from it: then it is a line of its own in Word,
                        // and what floats counts from it).
                        if ((found.isEmpty() && boxes.boxes.isEmpty() && equations.isEmpty()) || para.plainText().isNotEmpty() ||
                            para.format.sectionBreak != null || found.any { it.floating != null } || boxes.boxes.any { it.floating != null }) {
                            doc.addParagraph(para); bookmarksReach(para)
                        } else bookmarksPassOn(para)
                        doc.blocks.addAll(equations)
                        found.forEach { fi -> (if (fi.chart) makeChartBlock(fi, rels, parts) else makeImageBlock(fi, rels, parts))?.let { doc.blocks.add(it) } }
                        boxes.boxes.forEach { box ->
                            // A box holding a table: the table, floating where the box does (as wide as it); the box only for its own text.
                            val tables = boxes.tables[box]
                            tables?.forEachIndexed { i, t ->
                                if (i == 0) { t.floating = box.floating?.copy(); t.widthPt = box.widthPt }
                                doc.blocks.add(com.therealityteam.aeropages.model.TableBlock(t))
                            }
                            if (tables.isNullOrEmpty() || box.paragraphs.any { it.plainText().isNotBlank() }) doc.blocks.add(box)
                        }
                        }
                    }
                    "tbl" -> doc.blocks.add(com.therealityteam.aeropages.model.TableBlock(parseTable(parser, numbering, rels, commentsMap, doc, parts)))
                    // Word's table of contents lives in a content control marked as the TOC gallery:
                    // it becomes a live contents block (the cached entries are dropped, not duplicated as text).
                    // Other controls fall through and their paragraphs are read as usual.
                    "sdt" -> {
                        val gallery = sdtGallery(parser)
                        if (gallery != null && (gallery.contains("Table of Contents", ignoreCase = true) || gallery.contains("Table of Figures", ignoreCase = true)))
                            doc.blocks.add(com.therealityteam.aeropages.model.TocBlock(tocCaption(parser)))
                        // Word's cover page at the start: its paragraphs are read as they come; the document remembers it has one.
                        else if (gallery.equals("Cover Pages", ignoreCase = true) && doc.blocks.isEmpty()) doc.coverPage = true
                    }
                    "background" -> doc.pageColor = Ooxml.parseColor(parser.getAttributeValue(Ooxml.W, "color"))
                    "sectPr" -> parseSectPr(parser, doc, rels, parts)
                    "bookmarkStart", "bookmarkEnd", "commentRangeStart", "commentRangeEnd" -> bookmarkMark(parser, null)
                }
            }
            event = parser.next()
        }
        flushFrame()
        if (doc.blocks.isEmpty()) doc.addParagraph(Paragraph.of(""))
        // Marks after the last paragraph: at its end.
        bookmarkState.get()?.let { st ->
            val last = st.last ?: doc.paragraphs().lastOrNull()
            if (last != null) st.pending.forEach { last.bookmarks.add(it.copy(offset = last.runs.sumOf { r -> r.text.length })) }
            st.pending.clear()
        }
        return doc
    }

    /** A drawing's chart: its part (the document's relationship) read as the slides read theirs, at the drawing's size. */
    private fun makeChartBlock(found: FoundImage, rels: Map<String, String>, parts: Map<String, ByteArray>): com.therealityteam.aeropages.model.ChartBlock? {
        val target = rels[found.rId] ?: return null
        val path = "word/" + target.removePrefix("/").removePrefix("word/")
        val bytes = parts[path] ?: parts[target.removePrefix("/")] ?: return null
        val chart = com.therealityteam.aeropages.format.pptx.PptxReader.parseChartPart(bytes) ?: return null
        return com.therealityteam.aeropages.model.ChartBlock(chart,
            found.widthPt.takeIf { it > 0f } ?: com.therealityteam.aeropages.model.ChartBlock.DEFAULT_W,
            found.heightPt.takeIf { it > 0f } ?: com.therealityteam.aeropages.model.ChartBlock.DEFAULT_H)
    }

    private fun makeImageBlock(
        found: FoundImage,
        rels: Map<String, String>,
        parts: Map<String, ByteArray>,
    ): com.therealityteam.aeropages.model.ImageBlock? {
        val target = rels[found.rId] ?: return null
        val path = "word/" + target.removePrefix("/").removePrefix("word/")
        val png = parts[path] ?: parts[target.removePrefix("/")] ?: return null
        // The SVG itself when Word kept one beside its stand-in: drawn crisp at any size.
        val svg = found.svgRId?.let { rels[it] }?.let { t -> parts["word/" + t.removePrefix("/").removePrefix("word/")] ?: parts[t.removePrefix("/")] }
            ?.takeIf { com.therealityteam.aeropages.format.svg.SvgReader.isSvg(it) }
        val bytes = svg ?: png
        val mime = com.therealityteam.aeropages.format.PictureType.mimeOf(bytes, path)
        val w = if (found.widthPt > 0f) found.widthPt else 300f
        val h = if (found.heightPt > 0f) found.heightPt else 200f
        return com.therealityteam.aeropages.model.ImageBlock(bytes, mime, w, h, found.cropL, found.cropT, found.cropR, found.cropB, found.shadow,
            found.floating, found.flipH, found.flipV, found.altText, found.decorative)
    }

    /** Parse a `<w:tbl>`, consuming its whole subtree. */
    private fun parseTable(
        parser: XmlPullParser,
        numbering: Map<String, ListKind>,
        rels: Map<String, String>,
        commentsMap: Map<String, ParsedComment>,
        doc: Document,
        parts: Map<String, ByteArray> = emptyMap(),
    ): com.therealityteam.aeropages.model.Table {
        val table = com.therealityteam.aeropages.model.Table()
        var tblStyle: String? = null
        var tblBorders: Map<String, com.therealityteam.aeropages.model.CellBorder?>? = null
        var tblW: Float? = null
        // The table's left cell margin (twips; Word's default 108): a positioned table's text stands at its tblpX, its border that far left.
        var cellMarginLeft = 108f
        val cellSets = java.util.IdentityHashMap<com.therealityteam.aeropages.model.TableCell, Map<String, com.therealityteam.aeropages.model.CellBorder?>>()
        val outer = cellBorderSets.get()
        cellBorderSets.set(cellSets)
        try {
            var event = parser.next()
            while (!(event == XmlPullParser.END_TAG && parser.name == "tbl" && parser.namespace == Ooxml.W)) {
                if (event == XmlPullParser.START_TAG && parser.namespace == Ooxml.W) {
                    when (parser.name) {
                        "tblStyle" -> tblStyle = parser.getAttributeValue(Ooxml.W, "val")
                        "tblBorders" -> tblBorders = WordBorders.readSet(parser, "tblBorders")
                        // A positioned (floating) table: where it stands, from the page, the margins or its column / paragraph.
                        "tblpPr" -> table.floating = tablePosition { parser.getAttributeValue(Ooxml.W, it) }
                        "tblW" -> if (parser.getAttributeValue(Ooxml.W, "type") == "dxa") parser.getAttributeValue(Ooxml.W, "w")?.toFloatOrNull()?.let { tblW = it / 20f }
                        // Where it stands across its column (rows' and cells' own "jc"s are in their paragraphs, read elsewhere).
                        "jc" -> table.align = when (parser.getAttributeValue(Ooxml.W, "val")) { "center" -> com.therealityteam.aeropages.model.TextAlign.CENTER
                            "right", "end" -> com.therealityteam.aeropages.model.TextAlign.RIGHT; else -> com.therealityteam.aeropages.model.TextAlign.LEFT }
                        "tblInd" -> if (parser.getAttributeValue(Ooxml.W, "type") != "pct") parser.getAttributeValue(Ooxml.W, "w")?.toFloatOrNull()?.let { table.indentPt = it / 20f }
                        "tblCellMar" -> {
                            var e = parser.next()
                            while (!(e == XmlPullParser.END_TAG && parser.name == "tblCellMar")) {
                                if (e == XmlPullParser.START_TAG && (parser.name == "left" || parser.name == "start") && parser.getAttributeValue(Ooxml.W, "type") != "pct")
                                    parser.getAttributeValue(Ooxml.W, "w")?.toFloatOrNull()?.let { cellMarginLeft = it }
                                e = parser.next()
                            }
                        }
                        "gridCol" -> parser.getAttributeValue(Ooxml.W, "w")?.toFloatOrNull()
                            ?.let { table.columnWidths.add(it) }
                        "tr" -> table.rows.add(parseRow(parser, numbering, rels, commentsMap, doc, parts))
                        "bookmarkStart", "bookmarkEnd", "commentRangeStart", "commentRangeEnd" -> bookmarkMark(parser, null)
                    }
                }
                event = parser.next()
            }
        } finally { cellBorderSets.set(outer) }
        if (table.rows.isEmpty()) table.rows.add(com.therealityteam.aeropages.model.TableRow(mutableListOf(com.therealityteam.aeropages.model.TableCell())))
        resolveVMerges(table)
        // A table in the text: its own width when it gives one in twips ("auto" and percentages: its column's).
        if (table.floating == null) table.widthPt = tblW?.takeIf { it > 0f }
        // A floating table's own width: its tblW, else its grid's; its place, its left border's.
        table.floating?.let { f ->
            table.widthPt = tblW?.takeIf { it > 0f } ?: table.columnWidths.sum().takeIf { it > 0f }?.div(20f)
            if (f.hAlign == null) f.x -= cellMarginLeft / 20f
        }
        // Borders: each cell's own, else the table's own sides, else its style's (Word's default table style — Normal
        // Table — has none; a style only a part of which draws borders: a plain grid).
        val styles = tableStyles.get()
        val styleId = tblStyle ?: styles?.default
        val styleSides = styleId?.let { styles?.sets?.get(it) }
            ?: if (styleId != null && styles?.partsDraw?.contains(styleId) == true) GRID else emptyMap()
        WordBorders.resolve(table, styleSides + (tblBorders ?: emptyMap())) { ri, ci -> cellSets[table.rows[ri].cells[ci]] }
        return table
    }

    /**
     * A positioned table's place (`w:tblpPr`): `tblpX` / `tblpY` twips from the frames `horzAnchor` /
     * `vertAnchor` name (text — its column, its paragraph —, margin or page), or aligned there by
     * `tblpXSpec` / `tblpYSpec`; the text goes around it. [attr] reads one of its attributes.
     */
    internal fun tablePosition(attr: (String) -> String?): com.therealityteam.aeropages.model.FloatPosition {
        fun frame(v: String?, across: Boolean) = when (v) {
            "page" -> com.therealityteam.aeropages.model.FloatFrame.PAGE
            "margin" -> com.therealityteam.aeropages.model.FloatFrame.MARGIN
            else -> if (across) com.therealityteam.aeropages.model.FloatFrame.COLUMN else com.therealityteam.aeropages.model.FloatFrame.PARAGRAPH
        }
        fun twips(v: String?) = v?.toFloatOrNull()?.div(20f) ?: 0f
        return com.therealityteam.aeropages.model.FloatPosition(
            twips(attr("tblpX")), frame(attr("horzAnchor"), true), DrawingAnchors.alignOf(attr("tblpXSpec")),
            twips(attr("tblpY")), frame(attr("vertAnchor") ?: "margin", false), DrawingAnchors.alignOf(attr("tblpYSpec")),
            com.therealityteam.aeropages.model.FloatWrap.SQUARE,
        )
    }

    /** Turn vMerge restart sentinels (rowSpan=0) into real spans by counting the continuation cells below. */
    private fun resolveVMerges(table: com.therealityteam.aeropages.model.Table) {
        table.rows.forEachIndexed { ri, row ->
            row.cells.forEachIndexed { ci, cell ->
                if (cell.rowSpan == 0) {
                    var span = 1
                    for (r2 in ri + 1 until table.rows.size) {
                        val below = table.rows[r2].cells.getOrNull(ci) ?: break
                        if (below.vMerged) span++ else break
                    }
                    cell.rowSpan = span
                }
            }
        }
    }

    private fun parseRow(
        parser: XmlPullParser,
        numbering: Map<String, ListKind>,
        rels: Map<String, String>,
        commentsMap: Map<String, ParsedComment>,
        doc: Document,
        parts: Map<String, ByteArray> = emptyMap(),
    ): com.therealityteam.aeropages.model.TableRow {
        val row = com.therealityteam.aeropages.model.TableRow()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "tr" && parser.namespace == Ooxml.W)) {
            if (event == XmlPullParser.START_TAG && parser.namespace == Ooxml.W) {
                when (parser.name) {
                    "tc" -> row.cells.add(parseCell(parser, numbering, rels, commentsMap, doc, parts))
                    "bookmarkStart", "bookmarkEnd", "commentRangeStart", "commentRangeEnd" -> bookmarkMark(parser, null)
                    // w:trPr/w:tblHeader: repeat as header row (an absent w:val means on).
                    "tblHeader" -> row.header = parser.getAttributeValue(Ooxml.W, "val").let { it == null || it == "1" || it == "true" || it == "on" }
                    // w:trPr/w:trHeight: at least so many twips, or exactly ("auto": its content's).
                    "trHeight" -> {
                        val rule = parser.getAttributeValue(Ooxml.W, "hRule")
                        parser.getAttributeValue(Ooxml.W, "val")?.toFloatOrNull()?.takeIf { it > 0f && rule != "auto" }?.let { row.heightPt = it / 20f; row.exactHeight = rule == "exact" }
                    }
                }
            }
            event = parser.next()
        }
        if (row.cells.isEmpty()) row.cells.add(com.therealityteam.aeropages.model.TableCell())
        return row
    }

    private fun parseCell(
        parser: XmlPullParser,
        numbering: Map<String, ListKind>,
        rels: Map<String, String>,
        commentsMap: Map<String, ParsedComment>,
        doc: Document,
        parts: Map<String, ByteArray> = emptyMap(),
    ): com.therealityteam.aeropages.model.TableCell {
        val paras = mutableListOf<Paragraph>()
        val found = ArrayList<FoundImage>()
        var shade: Long? = null
        var gridSpan = 1
        var vMerge: String? = null
        var ownBorders: Map<String, com.therealityteam.aeropages.model.CellBorder?>? = null
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "tc" && parser.namespace == Ooxml.W)) {
            if (event == XmlPullParser.START_TAG && parser.namespace == Ooxml.W) {
                when (parser.name) {
                    // tcPr precedes the cell's paragraphs, so only accept these before the
                    // first <w:p> — avoids picking up a run-level <w:shd> as the cell shade.
                    "shd" -> if (paras.isEmpty()) shade = Ooxml.parseColor(parser.getAttributeValue(Ooxml.W, "fill"))
                    "gridSpan" -> if (paras.isEmpty()) parser.getAttributeValue(Ooxml.W, "val")?.toIntOrNull()?.let { gridSpan = it }
                    "vMerge" -> if (paras.isEmpty()) vMerge = parser.getAttributeValue(Ooxml.W, "val") ?: "continue"
                    "tcBorders" -> if (paras.isEmpty()) ownBorders = WordBorders.readSet(parser, "tcBorders")
                    "bookmarkStart", "bookmarkEnd", "commentRangeStart", "commentRangeEnd" -> bookmarkMark(parser, null)
                    "p" -> {
                        // Cells preserve hyperlinks (via rels), comments and their inline pictures.
                        val fc = ArrayList<String>()
                        val boxes = BoxCtx(numbering, rels, doc.styles, parts)
                        val picturesBefore = found.size
                        val para = parseParagraph(parser, numbering, found, rels, fc, doc.styles, boxes)
                        fc.forEach { cid ->
                            commentsMap[cid]?.let { pc -> doc.comments.add(pc.toComment(para.id)) }
                        }
                        // A paragraph that only holds pictures gives the cell its pictures, not an empty line
                        // (the writer puts each cell picture in a paragraph of its own).
                        val onlyPictures = found.size > picturesBefore && para.plainText().isEmpty() && fc.isEmpty() && boxes.boxes.isEmpty()
                        if (!onlyPictures) { paras.add(para); bookmarksReach(para) } else bookmarksPassOn(para)
                        // A text box inside a cell has no place of its own: its paragraphs join the cell.
                        boxes.boxes.forEach { paras.addAll(it.paragraphs) }
                    }
                }
            }
            event = parser.next()
        }
        if (paras.isEmpty()) paras.add(Paragraph.of(""))
        val images = found.filter { !it.chart }.mapNotNull { makeImageBlock(it, rels, parts) }.toMutableList()
        return com.therealityteam.aeropages.model.TableCell(paras, shade, gridSpan,
            rowSpan = 1, vMerged = vMerge == "continue", images = images).also { cell ->
                if (vMerge == "restart") cell.rowSpan = 0 /* resolved after the table */
                ownBorders?.let { cellBorderSets.get()?.put(cell, it) }
            }
    }

    /** Parses a `<w:p>` element. The parser is positioned on the start tag. */
    private fun parseParagraph(
        parser: XmlPullParser,
        numbering: Map<String, ListKind>,
        found: MutableList<FoundImage>,
        rels: Map<String, String>,
        foundComments: MutableList<String>,
        styles: StyleSheet,
        boxes: BoxCtx? = null,
        equations: MutableList<com.therealityteam.aeropages.model.EquationBlock>? = null,
    ): Paragraph {
        val para = Paragraph()
        // Bookmarks met just before it start it.
        bookmarkState.get()?.pending?.let { pending -> if (pending.isNotEmpty()) { para.bookmarks.addAll(pending); pending.clear() } }
        val seen = PPrSeen()
        // Resolved after <w:pPr> (which precedes the runs): the style's traits fill in what runs leave unset.
        val resolved by lazy { styles.resolve(para.format.rawStyleId ?: styles.idOf(para.format.style)) }
        var currentLink: String? = null
        // Track-changes scope: runs inside <w:ins>/<w:del> get the matching flag.
        var inIns = false
        var inDel = false
        var revAuthor: String? = null
        var revDate: Long? = null
        // Complex fields across the paragraph's runs (a MACROBUTTON shows its code's text).
        val fields = ArrayList<HfField>()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "p" && parser.namespace == Ooxml.W)) {
            if (event == XmlPullParser.START_TAG && parser.namespace == Ooxml.W) {
                when (parser.name) {
                    "pPr" -> parseParagraphProps(parser, para.format, numbering, styles, seen)
                    "r" -> {
                        val before = para.runs.size
                        parser.parseRunInto(para, found, currentLink, foundComments, resolved, boxes, fields)
                        if (inIns || inDel) for (i in before until para.runs.size) {
                            if (inIns) para.runs[i].format.inserted = true
                            if (inDel) para.runs[i].format.deleted = true
                            para.runs[i].format.revAuthor = revAuthor; para.runs[i].format.revDate = revDate
                        }
                    }
                    // A link: its address (a place in it after "#"), or a bookmark in this document ("#name").
                    "hyperlink" -> {
                        val anchor = parser.getAttributeValue(Ooxml.W, "anchor")?.takeIf { it.isNotEmpty() }
                        currentLink = rels[parser.getAttributeValue(Ooxml.R, "id")]?.let { url -> anchor?.let { "$url#$it" } ?: url } ?: anchor?.let { "#$it" }
                    }
                    "bookmarkStart", "bookmarkEnd", "commentRangeStart", "commentRangeEnd" -> bookmarkMark(parser, para)
                    // A simple field: its result runs follow (a link's links); a date that updates shows today's instead.
                    "fldSimple" -> {
                        val code = StringBuilder(parser.getAttributeValue(Ooxml.W, "instr").orEmpty())
                        val date = com.therealityteam.aeropages.model.FieldDates.shown(code.toString())
                        if (date != null && fields.none { it.token != null }) para.runs.add(TextRun(date, CharacterFormat(bold = resolved.bold, italic = resolved.italic,
                            fontSizePt = resolved.sizePt, fontFamily = resolved.fontFamily, color = resolved.color)))
                        fields.add(HfField(code, result = true, token = date, live = com.therealityteam.aeropages.model.Fields.of(code)))
                    }
                    "ins" -> { inIns = true; revAuthor = parser.getAttributeValue(Ooxml.W, "author"); revDate = Ooxml.parseDate(parser.getAttributeValue(Ooxml.W, "date")) }
                    "del" -> { inDel = true; revAuthor = parser.getAttributeValue(Ooxml.W, "author"); revDate = Ooxml.parseDate(parser.getAttributeValue(Ooxml.W, "date")) }
                }
            } else if (event == XmlPullParser.START_TAG && parser.namespace == Omml.M) {
                when (parser.name) {
                    // A displayed equation: a block of its own after the paragraph (where blocks can go), else
                    // the paragraph's text, set in its alignment.
                    "oMathPara" -> {
                        val node = com.therealityteam.aeropages.format.XmlPull.readSubtree(parser)
                        val align = when (node.child("oMathParaPr")?.child("jc")?.attr("val")) {
                            "left" -> com.therealityteam.aeropages.model.TextAlign.LEFT
                            "right" -> com.therealityteam.aeropages.model.TextAlign.RIGHT
                            else -> com.therealityteam.aeropages.model.TextAlign.CENTER
                        }
                        val size = mathSize(node) ?: resolved.sizePt ?: com.therealityteam.aeropages.model.EquationBlock.DEFAULT_SIZE
                        for (om in node.children("oMath")) {
                            val math = Omml.read(om)
                            if (equations != null) equations.add(com.therealityteam.aeropages.model.EquationBlock(math, align, size))
                            else {
                                if (para.plainText().isEmpty()) para.format.align = align
                                para.runs.addAll(com.therealityteam.aeropages.model.MathText.runs(com.therealityteam.aeropages.model.InlineMath(math), CharacterFormat(fontSizePt = size, color = resolved.color)))
                            }
                        }
                    }
                    // An equation in the text: runs that spell it, all tied to it.
                    "oMath" -> {
                        val node = com.therealityteam.aeropages.format.XmlPull.readSubtree(parser)
                        val size = mathSize(node) ?: resolved.sizePt
                        para.runs.addAll(com.therealityteam.aeropages.model.MathText.runs(com.therealityteam.aeropages.model.InlineMath(Omml.read(node)), CharacterFormat(fontSizePt = size, color = resolved.color)))
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.namespace == Ooxml.W) {
                when (parser.name) {
                    "hyperlink" -> currentLink = null
                    "fldSimple" -> fields.removeLastOrNull()
                    "ins" -> inIns = false
                    "del" -> inDel = false
                }
            }
            event = parser.next()
        }
        if (para.runs.isEmpty()) {
            // An empty paragraph: its style's look under its mark's own (Word sizes the empty line by the mark).
            val f = CharacterFormat(bold = resolved.bold, italic = resolved.italic, fontSizePt = resolved.sizePt, fontFamily = resolved.fontFamily, color = resolved.color)
            seen.mark?.let { m ->
                m.fontSizePt?.let { f.fontSizePt = it }; m.fontFamily?.let { f.fontFamily = it }; m.color?.let { f.color = it }
                if (seen.markSeen.bold) f.bold = m.bold; if (seen.markSeen.italic) f.italic = m.italic
            }
            para.runs.add(TextRun("", f))
        }
        // Paragraph traits the pPr left unset come from the style.
        val r = resolved
        if (!seen.jc) r.align?.let { para.format.align = it }
        if (!seen.before) r.spaceBeforePt?.let { para.format.spaceBeforePt = it }
        if (!seen.after) r.spaceAfterPt?.let { para.format.spaceAfterPt = it }
        // Borders and shading: its own, else its style's.
        if (!seen.borders) para.format.borders = r.borders
        if (!seen.shading) para.format.shading = r.shading
        if (!seen.line && (r.lineSpacing != null || r.lineHeightPt != null)) {
            para.format.lineSpacing = r.lineSpacing ?: 1f; para.format.lineHeightPt = r.lineHeightPt; para.format.lineExact = r.lineExact
        }
        // No list of its own: its style's (Word's numbered headings), at the paragraph's level if it names one.
        if (seen.numId == null) listState.get()?.let { ls ->
            ls.ofStyle(styles, para.format.rawStyleId ?: styles.idOf(para.format.style))?.let { (num, lvl) ->
                if (!seen.ilvl) para.format.listLevel = lvl.coerceIn(0, 8)
                ls.apply(para.format, num)
                seen.numId = num
            }
        }
        // Indents: each side the paragraph's own, else its list level's, else its style's (Word's order);
        // a character as wide as its text is tall.
        val level = if (para.format.listKind == ListKind.NONE) null else numberingIndents.get()?.get(seen.numId)?.get(para.format.listLevel)
        val chain = listOfNotNull(seen.ind, level) + indentChain(styles, para.format.rawStyleId ?: styles.idOf(para.format.style))
        val em = para.runs.firstOrNull { it.text.isNotBlank() }?.format?.fontSizePt ?: r.sizePt
        chain.firstOrNull { it.left != null }?.let { para.format.indentLeftPt = indentPt(it.left!!, it.leftChars, em) }
        chain.firstOrNull { it.right != null }?.let { para.format.indentRightPt = indentPt(it.right!!, it.rightChars, em) }
        chain.firstOrNull { it.first != null }?.let { para.format.firstLineIndentPt = indentPt(it.first!!, it.firstChars, em) }
        // Tab stops: the styles' from the root down, then the paragraph's own, each setting or clearing stops.
        val stops = ArrayList<com.therealityteam.aeropages.model.TabStop>()
        styleTabs.get()?.let { raw -> styleChain(styles, para.format.rawStyleId ?: styles.idOf(para.format.style)).asReversed().forEach { id -> raw[id]?.let { applyTabs(stops, it) } } }
        applyTabs(stops, seen.tabs)
        if (stops.isNotEmpty()) para.format.tabStops = stops.sortedBy { it.posPt }
        return para
    }

    /** The size a formula's runs give (`w:sz` in their `w:rPr`, half-points), or null when they give none. */
    private fun mathSize(node: com.therealityteam.aeropages.format.XmlNode): Float? =
        node.descendants("sz").firstOrNull()?.attr("val")?.toIntOrNull()?.takeIf { it > 0 }?.let { Ooxml.halfPtToPt(it) }

    /** A `w:sectPr` inside a paragraph's `w:pPr`: page size, margins and columns of that section. */
    private fun parseSectionBreak(parser: XmlPullParser): com.therealityteam.aeropages.model.PageSetup {
        val page = com.therealityteam.aeropages.model.PageSetup()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "sectPr" && parser.namespace == Ooxml.W)) {
            if (event == XmlPullParser.START_TAG && parser.namespace == Ooxml.W) {
                when (parser.name) {
                    "pgSz" -> {
                        parser.getAttributeValue(Ooxml.W, "w")?.toIntOrNull()?.let { page.widthPt = Ooxml.twipsToPt(it) }
                        parser.getAttributeValue(Ooxml.W, "h")?.toIntOrNull()?.let { page.heightPt = Ooxml.twipsToPt(it) }
                    }
                    "pgMar" -> {
                        parser.getAttributeValue(Ooxml.W, "top")?.toIntOrNull()?.let { page.marginTopPt = Ooxml.twipsToPt(it) }
                        parser.getAttributeValue(Ooxml.W, "bottom")?.toIntOrNull()?.let { page.marginBottomPt = Ooxml.twipsToPt(it) }
                        parser.getAttributeValue(Ooxml.W, "left")?.toIntOrNull()?.let { page.marginLeftPt = Ooxml.twipsToPt(it) }
                        parser.getAttributeValue(Ooxml.W, "right")?.toIntOrNull()?.let { page.marginRightPt = Ooxml.twipsToPt(it) }
                        parser.getAttributeValue(Ooxml.W, "header")?.toIntOrNull()?.let { page.headerPt = Ooxml.twipsToPt(it) }
                        parser.getAttributeValue(Ooxml.W, "footer")?.toIntOrNull()?.let { page.footerPt = Ooxml.twipsToPt(it) }
                    }
                    "cols" -> readColumns(parser, page)
                    "pgNumType" -> readPageNumbers(parser, page)
                    "docGrid" -> readGrid(parser, page)
                    "lnNumType" -> page.lineNumbers = readLineNumbers(parser)
                }
            }
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
        return page
    }

    /** `w:docGrid`: a grid of lines (type lines, or lines and characters) and its pitch; the others set none. */
    /**
     * `w:pgBorders` (the parser on it, consumed): each side's line and its space (points; from the page's edge with
     * offsetFrom="page", else — the schema's default — from the text), a shadow on any side, and which pages it is on.
     * Null when it draws nothing.
     */
    private fun readPageBorders(parser: XmlPullParser): com.therealityteam.aeropages.model.PageBorders? {
        val fromText = parser.getAttributeValue(Ooxml.W, "offsetFrom") != "page"
        val pages = when (parser.getAttributeValue(Ooxml.W, "display")) {
            "firstPage" -> com.therealityteam.aeropages.model.PageBorderPages.FIRST
            "notFirstPage" -> com.therealityteam.aeropages.model.PageBorderPages.NOT_FIRST
            else -> com.therealityteam.aeropages.model.PageBorderPages.ALL
        }
        val lines = HashMap<String, com.therealityteam.aeropages.model.CellBorder?>()
        val spaces = HashMap<String, Float>()
        var shadow = false
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "pgBorders") && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.namespace == Ooxml.W) {
                val side = when (parser.name) { "start" -> "left"; "end" -> "right"; else -> parser.name }
                if (side in setOf("top", "left", "bottom", "right")) {
                    lines[side] = WordBorders.lineOf(parser.getAttributeValue(Ooxml.W, "val"), parser.getAttributeValue(Ooxml.W, "sz"), parser.getAttributeValue(Ooxml.W, "color"))
                    parser.getAttributeValue(Ooxml.W, "space")?.toFloatOrNull()?.let { spaces[side] = it.coerceIn(0f, 31f) }
                    if (parser.getAttributeValue(Ooxml.W, "shadow").let { it == "1" || it == "true" || it == "on" }) shadow = true
                }
            }
            event = parser.next()
        }
        // Word's distance when a side says none: 24 pt from the page's edge, 4 pt from the text.
        val dflt = if (fromText) 4f else 24f
        return com.therealityteam.aeropages.model.PageBorders(
            top = lines["top"], bottom = lines["bottom"], left = lines["left"], right = lines["right"],
            spaceTop = spaces["top"] ?: dflt, spaceBottom = spaces["bottom"] ?: dflt, spaceLeft = spaces["left"] ?: dflt, spaceRight = spaces["right"] ?: dflt,
            fromText = fromText, shadow = shadow, pages = pages,
        ).takeIf { !it.none }
    }

    /**
     * `w:cols` (the parser on it, consumed): how many columns (num, else as many `w:col` as listed — unequal widths are
     * read as equal), the space between them (the schema's default half an inch; listed columns: the first one's), and
     * whether a line stands between them (sep).
     */
    private fun readColumns(parser: XmlPullParser, page: com.therealityteam.aeropages.model.PageSetup) {
        var num = parser.getAttributeValue(Ooxml.W, "num")?.toIntOrNull()
        var space = parser.getAttributeValue(Ooxml.W, "space")?.toIntOrNull()
        page.columnLine = parser.getAttributeValue(Ooxml.W, "sep").let { it == "1" || it == "true" || it == "on" }
        var listed = 0
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "cols") && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "col" && parser.namespace == Ooxml.W) {
                if (listed == 0) parser.getAttributeValue(Ooxml.W, "space")?.toIntOrNull()?.let { if (space == null || num == null) space = it }
                listed++
            }
            event = parser.next()
        }
        if (num == null && listed > 0) num = listed
        page.columns = (num ?: 1).coerceIn(1, com.therealityteam.aeropages.model.PageMetrics.MAX_COLUMNS)
        page.columnGapPt = Ooxml.twipsToPt(space ?: 720)
    }

    /**
     * `w:lnNumType`: Word's line numbers — every countBy-th shown (none given or 0: no line numbers), the first one start + 1
     * (Word writes the value before the first; none: 1), restarting at each page (the schema's default), section or never,
     * and the distance from the text (twips; none: Auto).
     */
    private fun readLineNumbers(parser: XmlPullParser): com.therealityteam.aeropages.model.LineNumbering? {
        val countBy = parser.getAttributeValue(Ooxml.W, "countBy")?.toIntOrNull() ?: return null
        if (countBy <= 0) return null
        return com.therealityteam.aeropages.model.LineNumbering(
            start = (parser.getAttributeValue(Ooxml.W, "start")?.toIntOrNull() ?: 0) + 1,
            countBy = countBy,
            restart = when (parser.getAttributeValue(Ooxml.W, "restart")) {
                "newSection" -> com.therealityteam.aeropages.model.LineNumberRestart.SECTION
                "continuous" -> com.therealityteam.aeropages.model.LineNumberRestart.CONTINUOUS
                else -> com.therealityteam.aeropages.model.LineNumberRestart.PAGE
            },
            distancePt = parser.getAttributeValue(Ooxml.W, "distance")?.toIntOrNull()?.takeIf { it > 0 }?.let { Ooxml.twipsToPt(it) },
        )
    }

    private fun readGrid(parser: XmlPullParser, page: com.therealityteam.aeropages.model.PageSetup) {
        val type = parser.getAttributeValue(Ooxml.W, "type")
        val pitch = parser.getAttributeValue(Ooxml.W, "linePitch")?.toIntOrNull()
        page.linePitchPt = if ((type == "lines" || type == "linesAndChars" || type == "snapToChars") && pitch != null && pitch > 0) Ooxml.twipsToPt(pitch) else null
    }

    private fun parseParagraphProps(parser: XmlPullParser, fmt: ParagraphFormat, numbering: Map<String, ListKind>, styles: StyleSheet, seen: PPrSeen) {
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "pPr" && parser.namespace == Ooxml.W)) {
            if (event == XmlPullParser.START_TAG && parser.namespace == Ooxml.W) {
                when (parser.name) {
                    "pStyle" -> {
                        val id = parser.getAttributeValue(Ooxml.W, "val")
                        fmt.rawStyleId = id
                        fmt.style = styles.styleFor(id)
                    }
                    "jc" -> { seen.jc = true; fmt.align = Ooxml.parseJc(parser.getAttributeValue(Ooxml.W, "val")) }
                    "rPr" -> {
                        val m = CharacterFormat(); parser.parseRunProps(m, seen.markSeen); seen.mark = m
                        if (m.inserted || m.deleted) fmt.revisionMark = com.therealityteam.aeropages.model.RevisionMark(m.inserted, m.revAuthor, m.revDate)
                    }
                    // A section break: this paragraph ends a section with its own page setup.
                    "sectPr" -> fmt.sectionBreak = parseSectionBreak(parser)
                    "numPr" -> seen.numId = parseNumPr(parser, fmt, seen)
                    "spacing" -> {
                        parser.getAttributeValue(Ooxml.W, "before")?.toIntOrNull()
                            ?.let { seen.before = true; fmt.spaceBeforePt = Ooxml.twipsToPt(it) }
                        parser.getAttributeValue(Ooxml.W, "after")?.toIntOrNull()
                            ?.let { seen.after = true; fmt.spaceAfterPt = Ooxml.twipsToPt(it) }
                        // w:line in 240ths when lineRule="auto" (240 = single spacing), else twips exactly or at least.
                        val line = parser.getAttributeValue(Ooxml.W, "line")?.toIntOrNull()
                        if (line != null) {
                            seen.line = true
                            val rule = parser.getAttributeValue(Ooxml.W, "lineRule")
                            if (rule == "exact" || rule == "atLeast") { fmt.lineSpacing = 1f; fmt.lineHeightPt = Ooxml.twipsToPt(line); fmt.lineExact = rule == "exact" }
                            else { fmt.lineSpacing = line / 240f; fmt.lineHeightPt = null }
                        }
                    }
                    "ind" -> seen.ind = parser.readIndent()
                    "pBdr" -> { seen.borders = true; fmt.borders = WordBorders.readParagraph(parser).takeIf { !it.none } }
                    "shd" -> { seen.shading = true; fmt.shading = WordBorders.shadingOf(parser) }
                    "tab" -> parser.readTabOp()?.let { seen.tabs.add(it) }
                    // Line and page breaks (Word's Paragraph ▸ Line and Page Breaks).
                    "keepNext" -> fmt.keepWithNext = parser.boolAttr()
                    "keepLines" -> fmt.keepTogether = parser.boolAttr()
                    "pageBreakBefore" -> fmt.pageBreakBefore = parser.boolAttr()
                    "widowControl" -> fmt.widowControl = parser.boolAttr()
                    "snapToGrid" -> fmt.snapToGrid = parser.boolAttr()
                    "suppressLineNumbers" -> fmt.suppressLineNumbers = parser.boolAttr()
                    // Word's drop cap: this paragraph is the frame that holds the letters of the next one (see mergeDropCap).
                    "framePr" -> parser.getAttributeValue(Ooxml.W, "dropCap")?.takeIf { it == "drop" || it == "margin" }?.let { dc ->
                        fmt.dropCap = com.therealityteam.aeropages.model.DropCap(
                            inMargin = dc == "margin",
                            lines = parser.getAttributeValue(Ooxml.W, "lines")?.toIntOrNull()?.coerceIn(1, com.therealityteam.aeropages.model.DropCap.MAX_LINES) ?: 1,
                            distancePt = parser.getAttributeValue(Ooxml.W, "hSpace")?.toIntOrNull()?.let { Ooxml.twipsToPt(it) }?.coerceIn(0f, 72f) ?: 0f,
                        )
                    }
                }
            }
            event = parser.next()
        }
    }

    /** A `w:numPr`: the paragraph's list and level; returns the list's numId ("0": no list — it ends one a style starts). */
    private fun parseNumPr(parser: XmlPullParser, fmt: ParagraphFormat, seen: PPrSeen): String? {
        var event = parser.next()
        var numId: String? = null
        while (!(event == XmlPullParser.END_TAG && parser.name == "numPr" && parser.namespace == Ooxml.W)) {
            if (event == XmlPullParser.START_TAG && parser.namespace == Ooxml.W) {
                when (parser.name) {
                    "ilvl" -> parser.getAttributeValue(Ooxml.W, "val")?.toIntOrNull()?.let { fmt.listLevel = it.coerceIn(0, 8); seen.ilvl = true }
                    "numId" -> numId = parser.getAttributeValue(Ooxml.W, "val")
                }
            }
            event = parser.next()
        }
        listState.get()?.apply(fmt, numId) ?: run { fmt.listKind = if (numId == null || numId == "0") ListKind.NONE else ListKind.BULLET }
        return numId
    }

    /** The lists of this read: numbering.xml's, the styles' numPr, and the lists the paragraphs use (for [Document.lists]). */
    private class ListState(val parsed: WordLists.Parsed) {
        val styleNums = HashMap<String, Pair<String?, Int?>>()
        val used = HashMap<Int, com.therealityteam.aeropages.model.ListDef>()

        /** [numId]'s list on [fmt], at the level it has ("0", or one numbering.xml lacks: no list). */
        fun apply(fmt: ParagraphFormat, numId: String?) {
            val entry = parsed.byNum[numId]
            // No list, or a level that shows nothing: not a list item.
            if (numId == null || numId == "0" || entry == null || entry.second.level(fmt.listLevel).blank) { fmt.listKind = ListKind.NONE; fmt.listId = 0; return }
            fmt.listKind = parsed.kindOf(numId, fmt.listLevel) ?: ListKind.BULLET
            fmt.listId = entry.first
            if (entry.first != 0) used[entry.first] = entry.second
        }

        /** The list a paragraph of [styleId] (or a style it is based on) takes: numId and level; null for none. */
        fun ofStyle(styles: StyleSheet, styleId: String?): Pair<String, Int>? {
            for (id in styleChain(styles, styleId)) {
                val (num, lvl) = styleNums[id] ?: continue
                if (num == null) continue
                return num to (lvl ?: parsed.levelStyles[num]?.get(styleId) ?: parsed.levelStyles[num]?.get(id) ?: 0)
            }
            return null
        }
    }

    private val listState = ThreadLocal<ListState?>()

    /** `w:pgNumType`: the section's first page number and the numbers' format. */
    private fun readPageNumbers(parser: XmlPullParser, page: PageSetup) {
        parser.getAttributeValue(Ooxml.W, "start")?.toIntOrNull()?.let { page.pageNumberStart = it }
        parser.getAttributeValue(Ooxml.W, "fmt")?.let { page.pageNumberFormat = WordLists.formatOf(it).takeIf { f -> f.numbered } ?: com.therealityteam.aeropages.model.NumFormat.DECIMAL }
    }

    private fun parseSectPr(
        parser: XmlPullParser,
        doc: Document,
        rels: Map<String, String>,
        parts: Map<String, ByteArray>,
        target: com.therealityteam.aeropages.model.PageSetup? = null,
    ) {
        val page = target ?: doc.page
        // The headers' and footers' pictures, by which one.
        val pictures = ArrayList<Pair<com.therealityteam.aeropages.model.HfSlot, com.therealityteam.aeropages.model.ImageBlock>>()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "sectPr" && parser.namespace == Ooxml.W)) {
            if (event == XmlPullParser.START_TAG && parser.namespace == Ooxml.W) {
                when (parser.name) {
                    "headerReference" -> {
                        val type = parser.getAttributeValue(Ooxml.W, "type")
                        val id = parser.getAttributeValue(Ooxml.R, "id")
                        val hf = loadHdrFtr(id, rels, parts, doc.styles) { if (doc.watermark == null) doc.watermark = it }
                        hf?.paragraph?.let { p ->
                            when (type) { "even" -> doc.evenHeader = p; "first" -> doc.firstHeader = p; else -> if (doc.header == null) doc.header = p }
                        }
                        val slot = when (type) { "even" -> com.therealityteam.aeropages.model.HfSlot.EVEN_HEADER; "first" -> com.therealityteam.aeropages.model.HfSlot.FIRST_HEADER; else -> com.therealityteam.aeropages.model.HfSlot.HEADER }
                        hf?.pictures?.forEach { pictures.add(slot to it) }
                    }
                    "footerReference" -> {
                        val type = parser.getAttributeValue(Ooxml.W, "type")
                        val id = parser.getAttributeValue(Ooxml.R, "id")
                        val hf = loadHdrFtr(id, rels, parts, doc.styles) { if (doc.watermark == null) doc.watermark = it }
                        hf?.paragraph?.let { p ->
                            when (type) { "even" -> doc.evenFooter = p; "first" -> doc.firstFooter = p; else -> if (doc.footer == null) doc.footer = p }
                        }
                        val slot = when (type) { "even" -> com.therealityteam.aeropages.model.HfSlot.EVEN_FOOTER; "first" -> com.therealityteam.aeropages.model.HfSlot.FIRST_FOOTER; else -> com.therealityteam.aeropages.model.HfSlot.FOOTER }
                        hf?.pictures?.forEach { pictures.add(slot to it) }
                    }
                    "titlePg" -> doc.differentFirstPage = true
                    "pgNumType" -> readPageNumbers(parser, page)
                    "docGrid" -> readGrid(parser, page)
                    // Page borders: the first section's are the document's (as its headers are).
                    "pgBorders" -> readPageBorders(parser).let { if (doc.pageBorders == null) doc.pageBorders = it }
                    "cols" -> readColumns(parser, page)
                    "lnNumType" -> page.lineNumbers = readLineNumbers(parser)
                    "pgSz" -> {
                        parser.getAttributeValue(Ooxml.W, "w")?.toIntOrNull()?.let { page.widthPt = Ooxml.twipsToPt(it) }
                        parser.getAttributeValue(Ooxml.W, "h")?.toIntOrNull()?.let { page.heightPt = Ooxml.twipsToPt(it) }
                    }
                    "pgMar" -> {
                        parser.getAttributeValue(Ooxml.W, "top")?.toIntOrNull()?.let { page.marginTopPt = Ooxml.twipsToPt(it) }
                        parser.getAttributeValue(Ooxml.W, "bottom")?.toIntOrNull()?.let { page.marginBottomPt = Ooxml.twipsToPt(it) }
                        parser.getAttributeValue(Ooxml.W, "left")?.toIntOrNull()?.let { page.marginLeftPt = Ooxml.twipsToPt(it) }
                        parser.getAttributeValue(Ooxml.W, "right")?.toIntOrNull()?.let { page.marginRightPt = Ooxml.twipsToPt(it) }
                        parser.getAttributeValue(Ooxml.W, "header")?.toIntOrNull()?.let { page.headerPt = Ooxml.twipsToPt(it) }
                        parser.getAttributeValue(Ooxml.W, "footer")?.toIntOrNull()?.let { page.footerPt = Ooxml.twipsToPt(it) }
                    }
                }
            }
            event = parser.next()
        }
        // The first section's pictures for each header and footer (as its text: the first one met).
        pictures.groupBy({ it.first }, { it.second }).forEach { (slot, pics) ->
            if (slot !in doc.hfPictures) doc.hfPictures[slot] = pics.toMutableList()
        }
    }

    /** Parse a `<w:r>` run and append it to [para]. Receiver is the parser. */
    private fun XmlPullParser.parseRunInto(
        para: Paragraph, found: MutableList<FoundImage>, link: String?, foundComments: MutableList<String>, style: ResolvedStyle, boxes: BoxCtx? = null,
        fields: MutableList<HfField> = ArrayList(),
    ) {
        val fmt = CharacterFormat()
        val seen = RunSeen()
        fmt.link = link
        val sb = StringBuilder()
        var pendingW = 0f
        var pendingH = 0f
        // The drawing being read floats (wp:anchor): where it stands; whether it is behind the text, placed simply.
        var pendingFloat: com.therealityteam.aeropages.model.FloatPosition? = null
        // The drawing's alternative text and decorative mark (its docPr comes before its picture).
        var pendingAlt = ""
        var pendingDecorative = false
        var behindDoc = false
        var simplePos = false
        // The namespace of the last shape properties met (pic:spPr's transform flips its picture).
        var spPrNs: String? = null
        // The run holds a drawing (a picture, shape or text box: blocks of their own): no empty text for it.
        var drew = false
        // A drawn shape (wps:wsp) being read: its properties, and the text box its text went into.
        var wsp: com.therealityteam.aeropages.format.XmlNode? = null
        var wspBox: com.therealityteam.aeropages.model.TextBoxBlock? = null
        var event = next()
        while (!(event == XmlPullParser.END_TAG && name == "r" && namespace == Ooxml.W)) {
            if (event == XmlPullParser.END_TAG && name == "wsp" && namespace == WPS_NS) {
                wsp?.let { node -> if (boxes != null) finishShape(node, wspBox, boxes, pendingW, pendingH, pendingFloat) }
                wsp = null; wspBox = null
            } else if (event == XmlPullParser.START_TAG && namespace == Ooxml.W) {
                when (name) {
                    "rPr" -> parseRunProps(fmt, seen)
                    "drawing", "pict" -> drew = true
                    // A field's result: not when the field shows something of its own (today's date).
                    "t" -> readElementText("t").let { if (fields.none { it.token != null }) sb.append(it) }
                    "delText" -> sb.append(readElementText("delText"))
                    "tab" -> sb.append('\t')
                    "br" -> sb.append(if (getAttributeValue(Ooxml.W, "type") == "page") '' else '\n')
                    "cr" -> sb.append('\n')
                    "instrText" -> fields.lastOrNull()?.code?.append(readElementText("instrText"))
                    "fldChar" -> when (getAttributeValue(Ooxml.W, "fldCharType")) {
                        "begin" -> fields.add(HfField())
                        "separate" -> fields.lastOrNull()?.let { f ->
                            f.result = true
                            f.live = com.therealityteam.aeropages.model.Fields.of(f.code)
                            if (fields.dropLast(1).none { it.token != null }) com.therealityteam.aeropages.model.FieldDates.shown(f.code.toString())?.let { f.token = it; sb.append(it) }
                            // Word's index: its result paragraphs give way to a live index.
                            if (com.therealityteam.aeropages.model.IndexEntries.isIndexField(f.code)) indexReading.get()?.let { st ->
                                if (st.block == null) st.block = com.therealityteam.aeropages.model.IndexEntries.blockOf(f.code)
                            }
                        }
                        "end" -> fields.removeLastOrNull().let { f ->
                            // A MACROBUTTON (a template's "[Click here and type…]") shows what its code says; it has no result.
                            if (f != null && !f.result && fields.all { it.result }) com.therealityteam.aeropages.format.doc.DocReader.macroButtonText(f.code)?.let { sb.append(it) }
                            // An index entry: marked where its XE field stands (LibreOffice gives it an empty result).
                            if (f != null) com.therealityteam.aeropages.model.IndexEntries.fromFieldCode(f.code)?.let { e ->
                                para.bookmarks.add(com.therealityteam.aeropages.model.BookmarkMark(com.therealityteam.aeropages.model.IndexEntries.markName(e), para.runs.sumOf { it.text.length } + sb.length, false))
                            }
                            // The end of an index's field (begun paragraphs before, or in this one).
                            if (f == null || com.therealityteam.aeropages.model.IndexEntries.isIndexField(f.code)) indexReading.get()?.let { st -> if (st.block != null) st.ended = true }
                        }
                    }
                    "commentReference" -> getAttributeValue(Ooxml.W, "id")?.let { foundComments.add(it) }
                    // Footnote anchor → the visible superscript "[n]" marker (writer's inverse).
                    "footnoteReference" -> getAttributeValue(Ooxml.W, "id")?.toIntOrNull()?.let { if (it > 0) sb.append("[$it]") }
                    // A text box (wps:txbx or VML v:textbox): its paragraphs become a TextBoxBlock after this paragraph.
                    "txbxContent" -> parseTextBox(boxes, pendingW, pendingH)?.let { it.floating = pendingFloat?.copy(); boxes?.boxes?.add(it); if (wsp != null) wspBox = it }
                    // Endnote anchor → the visible superscript "[i]" roman marker.
                    "endnoteReference" -> getAttributeValue(Ooxml.W, "id")?.toIntOrNull()?.let { if (it > 0) sb.append("[${com.therealityteam.aeropages.model.Roman.of(it)}]") }
                }
            } else if (event == XmlPullParser.START_TAG) {
                // DrawingML lives in non-w namespaces: wp:extent (size), a:blip (image rId).
                if (name == "spPr" || name == "grpSpPr") spPrNs = namespace
                when (name) {
                    // In line with the text, or floating: where it stands and how the text goes by it.
                    "inline" -> if (namespace == DrawingAnchors.WP) { pendingFloat = null; pendingAlt = ""; pendingDecorative = false }
                    "anchor" -> if (namespace == DrawingAnchors.WP) {
                        pendingFloat = com.therealityteam.aeropages.model.FloatPosition()
                        pendingAlt = ""; pendingDecorative = false
                        behindDoc = getAttributeValue(null, "behindDoc").let { it == "1" || it == "true" }
                        simplePos = getAttributeValue(null, "simplePos").let { it == "1" || it == "true" }
                    }
                    "simplePos" -> if (namespace == DrawingAnchors.WP && simplePos) pendingFloat?.let { f ->
                        f.hFrom = com.therealityteam.aeropages.model.FloatFrame.PAGE; f.vFrom = com.therealityteam.aeropages.model.FloatFrame.PAGE
                        f.x = Ooxml.emuToPt(getAttributeValue(null, "x")?.toLongOrNull() ?: 0L)
                        f.y = Ooxml.emuToPt(getAttributeValue(null, "y")?.toLongOrNull() ?: 0L)
                    }
                    "positionH", "positionV" -> if (namespace == DrawingAnchors.WP) {
                        val across = name == "positionH"
                        val node = com.therealityteam.aeropages.format.XmlPull.readSubtree(this)
                        if (!simplePos) pendingFloat?.let { DrawingAnchors.readPosition(node, it, across) }
                    }
                    "wrapNone", "wrapSquare", "wrapTight", "wrapThrough", "wrapTopAndBottom" ->
                        if (namespace == DrawingAnchors.WP) pendingFloat?.let { f -> DrawingAnchors.wrapOf(name, behindDoc)?.let { f.wrap = it } }
                    // A picture's transform: mirrored as it says.
                    "xfrm" -> if (spPrNs == PIC_NS) found.lastOrNull()?.let { fi ->
                        fi.flipH = getAttributeValue(null, "flipH").let { it == "1" || it == "true" }
                        fi.flipV = getAttributeValue(null, "flipV").let { it == "1" || it == "true" }
                    }
                    "extent" -> {
                        getAttributeValue(null, "cx")?.toLongOrNull()?.let { pendingW = Ooxml.emuToPt(it) }
                        getAttributeValue(null, "cy")?.toLongOrNull()?.let { pendingH = Ooxml.emuToPt(it) }
                    }
                    "docPr" -> if (namespace == DrawingAnchors.WP) pendingAlt = getAttributeValue(null, "descr").orEmpty()
                    "decorative" -> if (namespace == ADEC_NS) pendingDecorative = getAttributeValue(null, "val").let { it == "1" || it == "true" }
                    "blip" -> getAttributeValue(Ooxml.R, "embed")?.let {
                        found.add(FoundImage(it, pendingW, pendingH).also { fi -> fi.floating = pendingFloat?.copy(); fi.altText = pendingAlt; fi.decorative = pendingDecorative })
                    }
                    "svgBlip" -> found.lastOrNull()?.let { it.svgRId = getAttributeValue(Ooxml.R, "embed") }
                    // A chart in the drawing: its part is read after the paragraph.
                    "chart" -> if (namespace == C_NS) getAttributeValue(Ooxml.R, "id")?.let { found.add(FoundImage(it, pendingW, pendingH, chart = true)) }
                    // pic:blipFill's crop follows its blip.
                    "srcRect" -> found.lastOrNull()?.let { fi -> com.therealityteam.aeropages.format.PictureCrop.readSrcRect({ n -> getAttributeValue(null, n) }, fi) }
                    // pic:spPr's effect list, after its blip: the picture's shadow. (A drawn shape's is read whole with its spPr.)
                    "effectLst" -> if (wsp == null) found.lastOrNull()?.let { fi ->
                        com.therealityteam.aeropages.format.XmlPull.readSubtree(this).child("outerShdw")?.let {
                            fi.shadow = com.therealityteam.aeropages.format.pptx.DrawingShape.shadowOf(it, boxes?.palette ?: com.therealityteam.aeropages.format.pptx.PptxReader.Palette.DEFAULT)
                        }
                    }
                    // mc:AlternateContent repeats the same shape as a Fallback for old readers: only the Choice counts.
                    "Fallback" -> if (namespace == Ooxml.MC) skipSubtree()
                    // A drawn shape: its geometry, fill, outline and text anchor, read whole (its text is read as a text box's).
                    "wsp" -> if (namespace == WPS_NS) wsp = com.therealityteam.aeropages.format.XmlNode(WPS_NS, "wsp", emptyList())
                    "cNvSpPr", "cNvCnPr", "spPr", "style", "bodyPr" ->
                        if (namespace == WPS_NS) wsp?.children?.add(com.therealityteam.aeropages.format.XmlPull.readSubtree(this))
                }
            }
            event = next()
        }
        // Style traits fill in whatever the run did not set explicitly (Word's inheritance, baked).
        if (!seen.bold) fmt.bold = style.bold
        if (!seen.italic) fmt.italic = style.italic
        if (fmt.fontSizePt == null) fmt.fontSizePt = style.sizePt
        if (fmt.fontFamily == null) fmt.fontFamily = style.fontFamily
        if (fmt.color == null) fmt.color = style.color
        // The look before a format change inherits the same way (a change from the style's bold to not bold).
        fmt.before?.let { b ->
            if (seen.before?.bold != true) b.bold = style.bold
            if (seen.before?.italic != true) b.italic = style.italic
            if (b.fontSizePt == null) b.fontSizePt = style.sizePt
            if (b.fontFamily == null) b.fontFamily = style.fontFamily
            if (b.color == null) b.color = style.color
            fmt.before = b.look().also { it.link = fmt.link }
        }
        // A HYPERLINK field's result links where its code says.
        if (fmt.link == null) fields.lastOrNull { it.result && com.therealityteam.aeropages.format.doc.DocReader.hyperlinkOf(it.code) != null }
            ?.let { fmt.link = com.therealityteam.aeropages.format.doc.DocReader.hyperlinkOf(it.code) }
        // A live field's result: its runs (a cross-reference's \h links to its bookmark).
        fields.lastOrNull { it.result && it.live != null }?.live?.let { f ->
            fmt.field = f
            if (fmt.link == null && f.kind != "SEQ" && f.has("\\h")) fmt.link = "#" + f.target
        }
        if (sb.isEmpty() && drew) return
        para.runs.add(TextRun(sb.toString(), fmt))
    }

    private fun XmlPullParser.parseRunProps(fmt: CharacterFormat, seen: RunSeen = RunSeen()) {
        var event = next()
        while (!(event == XmlPullParser.END_TAG && name == "rPr" && namespace == Ooxml.W)) {
            if (event == XmlPullParser.START_TAG && namespace == Ooxml.W) {
                when (name) {
                    "b" -> { seen.bold = true; fmt.bold = boolAttr() }
                    "i" -> { seen.italic = true; fmt.italic = boolAttr() }
                    "u" -> fmt.underline = (getAttributeValue(Ooxml.W, "val") ?: "single") != "none"
                    "strike" -> fmt.strikethrough = boolAttr()
                    "sz" -> getAttributeValue(Ooxml.W, "val")?.toIntOrNull()
                        ?.let { fmt.fontSizePt = Ooxml.halfPtToPt(it) }
                    "rFonts" -> fmt.fontFamily = getAttributeValue(Ooxml.W, "ascii")
                        ?: getAttributeValue(Ooxml.W, "hAnsi")
                    "color" -> fmt.color = Ooxml.parseColor(getAttributeValue(Ooxml.W, "val"))
                    "highlight" -> fmt.highlight = Ooxml.parseHighlight(getAttributeValue(Ooxml.W, "val"))
                    "vertAlign" -> when (getAttributeValue(Ooxml.W, "val")) {
                        "superscript" -> fmt.superscript = true
                        "subscript" -> fmt.subscript = true
                    }
                    "smallCaps" -> fmt.smallCaps = boolAttr()
                    // A paragraph mark's own tracked change (CT_ParaRPr), read back as the paragraph's revision mark.
                    "ins", "del" -> {
                        if (name == "ins") fmt.inserted = true else fmt.deleted = true
                        fmt.revAuthor = getAttributeValue(Ooxml.W, "author"); fmt.revDate = Ooxml.parseDate(getAttributeValue(Ooxml.W, "date"))
                    }
                    "rPrChange" -> {
                        fmt.formatChanged = true
                        fmt.revAuthor = getAttributeValue(Ooxml.W, "author"); fmt.revDate = Ooxml.parseDate(getAttributeValue(Ooxml.W, "date"))
                        // The nested <w:rPr> holds the previous formatting: the look rejecting the change brings back.
                        var depth = 1
                        while (depth > 0) {
                            val ev = next()
                            if (ev == XmlPullParser.END_DOCUMENT) break
                            if (ev == XmlPullParser.START_TAG && name == "rPr" && namespace == Ooxml.W && depth == 1) {
                                val b = CharacterFormat(); val bs = RunSeen()
                                parseRunProps(b, bs)
                                fmt.before = b; seen.before = bs
                            } else if (ev == XmlPullParser.START_TAG && name == "rPrChange") depth++ else if (ev == XmlPullParser.END_TAG && name == "rPrChange") depth--
                        }
                    }
                    "spacing" -> getAttributeValue(Ooxml.W, "val")?.toIntOrNull()
                        ?.let { fmt.letterSpacingPt = it / 20f }
                }
            }
            event = next()
        }
    }

    /** A toggle property is on unless `w:val` is explicitly "0"/"false". */
    private fun XmlPullParser.boolAttr(): Boolean {
        val v = getAttributeValue(Ooxml.W, "val") ?: return true
        return v != "0" && v != "false"
    }

    /** Read concatenated text until the matching end tag [tag]. */
    private fun XmlPullParser.readElementText(tag: String): String {
        val sb = StringBuilder()
        var event = next()
        while (!(event == XmlPullParser.END_TAG && name == tag)) {
            if (event == XmlPullParser.TEXT) sb.append(text)
            if (event == XmlPullParser.END_DOCUMENT) break
            event = next()
        }
        return sb.toString()
    }
}
