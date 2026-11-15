package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.format.odf.OdfPackage.HEAD
import com.therealityteam.aeropages.format.odf.OdfPackage.NS
import com.therealityteam.aeropages.format.odf.OdfPackage.color
import com.therealityteam.aeropages.format.odf.OdfPackage.escape
import com.therealityteam.aeropages.format.odf.OdfPackage.pt
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.ListDef
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.PageMetrics
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TextAlign
import java.io.OutputStream

/**
 * OpenDocument Text (.odt) writer: the counterpart of [com.therealityteam.aeropages.format.TextImporters.odt].
 *
 * Paragraph and character formatting become automatic styles (`P1`, `T1`…), headings become
 * `text:h` with an outline level, lists become `text:list`, tables become `table:table` and
 * pictures are packaged under `Pictures/` and referenced by a `draw:frame`. Page size, margins
 * and the header/footer live in `styles.xml` as a page layout plus a master page.
 */
object OdtWriter {

    fun write(original: Document, out: OutputStream) {
        // Tracked changes as ODF keeps them: a paragraph whose mark is deleted joined with the next (see OdfChanges).
        val plan = OdfChanges.plan(original)
        val doc = plan.doc
        val styles = StyleTable(doc.lists)
        styles.changes = OdfChanges.Recorder(plan)
        // Each table cell's paragraph with its table, row and column (a formula's ABOVE and LEFT become ranges in ODF).
        doc.locations().let { locs -> locs.indices.forEach { i -> com.therealityteam.aeropages.model.TableFormulas.cellOf(doc, locs, i)?.let { styles.formulaCells[locs[i].paragraph] = it } } }
        styles.sheet = doc.styles
        styles.masters = sectionMasters(doc, styles)
        styles.footnotes = doc.footnotes.map { it.text }
        styles.endnotes = doc.endnotes.map { it.text }
        styles.bookmarksClosed = com.therealityteam.aeropages.model.Bookmarks.of(doc).filter { it.closed }.mapTo(HashSet()) { it.name }
        styles.tocMarks = com.therealityteam.aeropages.model.Bookmarks.tocMarks(doc)
        styles.commentById = doc.comments.associateBy { it.id }
        styles.commentRanged = com.therealityteam.aeropages.model.Bookmarks.commentRanges(doc).keys.filterTo(HashSet()) { styles.commentById[it]?.parentId == null && it in styles.commentById }
        styles.commentReplies = doc.comments.filter { it.parentId != null }.groupBy { it.parentId!! }
        styles.commentsOn = doc.comments.filter { it.parentId == null && it.id !in styles.commentRanged }.groupBy { it.targetId }
        val pictures = LinkedHashMap<String, ByteArray>()
        val body = buildBody(doc, styles, pictures)
        // The changed regions (a deletion's paragraphs in their styles, which the styles written next must hold).
        val tracked = styles.changes?.xml(doc.trackRevisions) { p, text -> changedParagraph(p, text, styles) }.orEmpty()
        val parts = LinkedHashMap<String, ByteArray>()
        parts["content.xml"] = content(styles, body, tracked).toByteArray(Charsets.UTF_8)
        parts["styles.xml"] = styles(doc, styles.shapes.renderNamed(), styles.masters, pictures).toByteArray(Charsets.UTF_8)
        parts["meta.xml"] = com.therealityteam.aeropages.format.OdfMeta.write(doc.props)
        // Lock Tracking as LibreOffice keeps it (Edit ▸ Track Changes ▸ Protect): its RedlineProtectionKey, `AQ==` without a password.
        doc.trackLock?.let { lock -> parts["settings.xml"] = trackLockSettings(lock[com.therealityteam.aeropages.model.ODF_TRACK_KEY] ?: com.therealityteam.aeropages.model.NO_KEY) }
        pictures.forEach { (name, bytes) -> parts[name] = bytes }
        styles.formulas.forEach { (name, bytes) -> parts[name] = bytes }
        OdfPackage.write(out, OdfPackage.TEXT, parts)
    }

    /** settings.xml holding only the tracked changes' protection key. */
    private fun trackLockSettings(key: String): ByteArray = (
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<office:document-settings xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:config="urn:oasis:names:tc:opendocument:xmlns:config:1.0" office:version="1.3"><office:settings>""" +
            """<config:config-item-set config:name="ooo:configuration-settings">""" +
            """<config:config-item config:name="RedlineProtectionKey" config:type="base64Binary">${key.filter { it.isLetterOrDigit() || it in "+/=" }}</config:config-item>""" +
            """</config:config-item-set></office:settings></office:document-settings>"""
        ).toByteArray(Charsets.UTF_8)

    // --- sections ------------------------------------------------------------------------------

    /**
     * The document's sections as ODF keeps them: each distinct page (size, margins, columns) a master
     * page of its own — the first section's `Standard` — and the first paragraph of each later
     * section named to start pages of its master (a paragraph whose break ends a section, as Word
     * keeps it, ends the pages before). Returns the masters by page, in order.
     */
    private fun sectionMasters(doc: Document, styles: StyleTable): LinkedHashMap<com.therealityteam.aeropages.model.PageSetup, String> {
        val pages = ArrayList<com.therealityteam.aeropages.model.PageSetup>()
        val firsts = ArrayList<Paragraph?>()
        var pendingStart = true
        for (block in doc.blocks) {
            val p = (block as? ParagraphBlock)?.paragraph ?: continue
            if (pendingStart) { firsts.add(p); pendingStart = false }
            p.format.sectionBreak?.let { pages.add(it); pendingStart = true }
        }
        if (pendingStart) firsts.add(null)
        pages.add(doc.page)
        val masters = LinkedHashMap<com.therealityteam.aeropages.model.PageSetup, String>()
        pages.forEachIndexed { k, pg ->
            val name = masters.getOrPut(pg.copy()) { if (masters.isEmpty()) "Standard" else "Section${masters.size + 1}" }
            // Every section after the first starts pages of its master (one like the last's too: a section break is a new page).
            if (k > 0) firsts.getOrNull(k)?.let { styles.sectionStarts[it] = name }
            // A section starting its page numbers again: its first paragraph says from what (the first section's too, on its master).
            pg.pageNumberStart?.let { n -> firsts.getOrNull(k)?.let { p -> styles.sectionNumbers[p] = n; styles.sectionStarts.putIfAbsent(p, name) } }
        }
        return masters
    }

    // --- content ---------------------------------------------------------------------------

    private fun content(styles: StyleTable, body: String, tracked: String = ""): String = buildString {
        append(HEAD)
        append("<office:document-content $NS>")
        append("<office:automatic-styles>").append(styles.render()).append(styles.shapes.render()).append("</office:automatic-styles>")
        append("<office:body><office:text>")
        append(tracked)
        // The sequences the captions number (LibreOffice declares each before the text).
        if (styles.sequences.isNotEmpty())
            append("<text:sequence-decls>").append(styles.sequences.joinToString("") { name ->
                val chapter = styles.sequenceChapters[name]
                if (chapter == null) """<text:sequence-decl text:display-outline-level="0" text:name="${escape(name)}"/>"""
                else """<text:sequence-decl text:display-outline-level="${chapter.first}" text:separation-character="${escape(chapter.second)}" text:name="${escape(name)}"/>"""
            }).append("</text:sequence-decls>")
        append(body).append("</office:text></office:body>")
        append("</office:document-content>")
    }

    private fun buildBody(doc: Document, styles: StyleTable, pictures: LinkedHashMap<String, ByteArray>): String = buildString {
        var listRun: MutableList<Paragraph>? = null
        var listKey = 0L
        // The xml:id of each list's last part, for the next part to continue it.
        val listParts = HashMap<Int, String>()

        fun flushList() {
            val items = listRun ?: return
            val f = items.first().format
            val def = doc.lists[f.listId]?.takeIf { f.listId != 0 }
            val name = if (def != null) styles.listStyle(f.listId, def) else styles.listStyle(f.listKind)
            var attrs = ""
            if (def != null) {
                val id = "list${f.listId}_${listParts.size + 1}"
                listParts[f.listId]?.let { attrs += """ text:continue-list="$it"""" }
                attrs += """ xml:id="$id""""
                listParts[f.listId] = id
            }
            append(listXml(items, name, attrs, styles))
            listRun = null; listKey = 0L
        }

        /** Which run of list items [p] goes into: its list's, or the plain kind's. */
        fun keyOf(p: Paragraph): Long = p.format.let { f -> if (f.listId != 0 && doc.lists[f.listId] != null) f.listId.toLong() else -1L - f.listKind.ordinal }

        /** A heading numbered by the outline (a document's chapter numbers): a heading of its own, not a list's. */
        val outline = outlineList(doc).also { styles.outline = it }
        fun outlined(p: Paragraph) = outline != 0 && p.format.listId == outline

        // A floating picture or text box goes into the paragraph it is anchored to (the one before it, not a list item's).
        val anchoredTo = java.util.IdentityHashMap<Paragraph, MutableList<com.therealityteam.aeropages.model.Block>>()
        val anchored = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<com.therealityteam.aeropages.model.Block, Boolean>())
        var host: Paragraph? = null
        for (b in doc.blocks) {
            if (b is ParagraphBlock) host = b.paragraph.takeIf { it.format.listKind == ListKind.NONE || outlined(it) }
            else if (floatOf(b) != null && host != null) { anchoredTo.getOrPut(host) { ArrayList() }.add(b); anchored.add(b) }
            else host = null
        }
        val textWidth = doc.page.widthPt - doc.page.marginLeftPt - doc.page.marginRightPt
        fun floatingFrame(b: com.therealityteam.aeropages.model.Block) = when (b) {
            is ImageBlock -> image(b, styles, pictures, floating = true, page = doc.page)
            is com.therealityteam.aeropages.model.TextBoxBlock -> textBox(b, styles, floating = true, page = doc.page)
            is TableBlock -> floatingTable(b.table, styles, pictures, doc.page, textWidth)
            else -> ""
        }
        fun floating(p: Paragraph): String {
            val write = { anchoredTo[p].orEmpty().joinToString("") { floatingFrame(it) } }
            // A frame's paragraphs stand inside the paragraph: a change open across its start is not theirs.
            return styles.changes?.aside(write) ?: write()
        }

        // A page break (a paragraph of its own here, Word's way) is ODF's break before the paragraph after it, as LibreOffice
        // writes it; with no paragraph after it, the break's own empty paragraph starts the new page (where Word puts its mark).
        val folded = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Paragraph, Boolean>())
        doc.blocks.forEachIndexed { i, b ->
            val p = (b as? ParagraphBlock)?.paragraph?.takeIf { it.isPageBreak() } ?: return@forEachIndexed
            val next = (doc.blocks.getOrNull(i + 1) as? ParagraphBlock)?.paragraph
            if (next != null && !next.isPageBreak() && anchoredTo[p].isNullOrEmpty()) { folded.add(p); styles.breaksBefore.add(next) }
            else styles.breaksBefore.add(p)
        }

        for (block in doc.blocks) {
            if (block in anchored) continue
            when (block) {
                is ParagraphBlock -> {
                    val p = block.paragraph
                    if (p in folded) continue
                    val kind = p.format.listKind
                    if (kind != ListKind.NONE && !outlined(p)) {
                        val key = keyOf(p)
                        if (listRun == null || key != listKey) { flushList(); listRun = mutableListOf(); listKey = key }
                        listRun!!.add(p)
                    } else {
                        flushList()
                        append(paragraph(p, styles, anchored = floating(p)))
                    }
                }
                // Floating with no paragraph before it to float from: a paragraph of its own to anchor it.
                is TableBlock -> { flushList(); append(if (block.table.floating != null) "<text:p>${floatingFrame(block)}</text:p>" else table(block.table, styles, pictures, textWidth)) }
                is com.therealityteam.aeropages.model.TextBoxBlock -> { flushList(); append(if (block.floating != null) "<text:p>${floatingFrame(block)}</text:p>" else textBox(block, styles, page = doc.page)) }
                is ImageBlock -> { flushList(); append(if (block.floating != null) "<text:p>${floatingFrame(block)}</text:p>" else image(block, styles, pictures)) }
                is com.therealityteam.aeropages.model.TocBlock -> { flushList(); append(block.caption?.let { tableOfFigures(doc, styles, block, it) } ?: tableOfContents(doc, styles)) }
                is com.therealityteam.aeropages.model.IndexBlock -> { flushList(); append(alphabeticalIndex(doc, block)) }
                is com.therealityteam.aeropages.model.ChartBlock -> {
                    flushList()
                    // A chart object (its data in a local table), as a character of a centred paragraph.
                    val n = styles.formulas.size + 1
                    styles.formulas["Object $n/content.xml"] = OdfChart.contentXml(block.chart, block.widthPt, block.heightPt).toByteArray(Charsets.UTF_8)
                    val style = styles.paragraphStyle(Paragraph(format = com.therealityteam.aeropages.model.ParagraphFormat(align = TextAlign.CENTER)))
                    append("""<text:p text:style-name="$style"><draw:frame draw:style-name="${styles.frameStyle()}" draw:name="Chart$n" text:anchor-type="as-char" """)
                    append("""svg:width="${pt(block.widthPt)}" svg:height="${pt(block.heightPt)}" draw:z-index="0">""")
                    append("""<draw:object xlink:href="./Object $n" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame></text:p>""")
                }
                is com.therealityteam.aeropages.model.EquationBlock -> {
                    flushList()
                    val style = styles.paragraphStyle(Paragraph(format = com.therealityteam.aeropages.model.ParagraphFormat(align = block.align)))
                    append("""<text:p text:style-name="$style">""").append(formula(block.math, true, styles)).append("</text:p>")
                }
                else -> Unit
            }
        }
        flushList()
    }

    /**
     * A table of figures as LibreOffice keeps one: an illustration index of the captions numbered by sequence [label]
     * (a table index for tables), each whole caption with a dot leader to its page, the entries linking to the captions.
     */
    private fun tableOfFigures(doc: com.therealityteam.aeropages.model.Document, styles: StyleTable, block: com.therealityteam.aeropages.model.TocBlock, label: String): String = buildString {
        val kind = if (label.lowercase() in TABLE_LABELS) "table-index" else "illustration-index"
        val style = if (kind == "table-index") "Table_20_index_20_1" else "Figure_20_Index_20_1"
        val name = escape(label)
        append("""<text:$kind text:name="$name Index" text:protected="true">""")
        append("""<text:$kind-source text:caption-sequence-name="$name" text:caption-sequence-format="text">""")
        append("""<text:$kind-entry-template text:style-name="$style">""")
        append("""<text:index-entry-link-start/><text:index-entry-text/><text:index-entry-tab-stop style:type="right" style:leader-char="."/><text:index-entry-page-number/><text:index-entry-link-end/>""")
        append("</text:$kind-entry-template></text:$kind-source><text:index-body>")
        doc.tocEntries(block).forEach { e ->
            val entry = "${escape(e.text)}<text:tab/>${doc.pageCache[e.paragraph.id]?.let(::escape).orEmpty()}"
            val mark = styles.tocMarks[e.paragraph.id]?.first
            append("""<text:p text:style-name="$style">""")
            append(if (mark != null) """<text:a xlink:type="simple" xlink:href="#${escape(mark)}">$entry</text:a>""" else entry)
            append("</text:p>")
        }
        append("</text:index-body></text:$kind>")
    }

    /**
     * Word's index as LibreOffice keeps one: an alphabetical index of the marked entries (a letter's separator line when
     * it has headings), each entry with its pages after a comma — or after a dotted tab — and the current lines as its body.
     */
    private fun alphabeticalIndex(doc: com.therealityteam.aeropages.model.Document, block: com.therealityteam.aeropages.model.IndexBlock): String = buildString {
        val pages = if (block.rightAlign) """<text:index-entry-tab-stop style:type="right" style:leader-char="."/>""" else "<text:index-entry-span>, </text:index-entry-span>"
        append("""<text:alphabetical-index text:name="Alphabetical Index1" text:protected="true">""")
        append("""<text:alphabetical-index-source text:ignore-case="true" text:alphabetical-separators="${block.headings}" text:combine-entries="true" text:use-keys-as-entries="false">""")
        append("<text:index-title-template/>")
        append("""<text:alphabetical-index-entry-template text:outline-level="separator" text:style-name="Index_20_Separator"><text:index-entry-text/></text:alphabetical-index-entry-template>""")
        for (level in 1..3) {
            append("""<text:alphabetical-index-entry-template text:outline-level="$level" text:style-name="Index_20_$level">""")
            append("<text:index-entry-text/>$pages<text:index-entry-page-number/></text:alphabetical-index-entry-template>")
        }
        append("</text:alphabetical-index-source><text:index-body>")
        val lines = com.therealityteam.aeropages.model.IndexEntries.lines(doc, block.headings)
        if (lines.isEmpty()) append("""<text:p text:style-name="Index_20_1">${escape(com.therealityteam.aeropages.model.Texts.get(com.therealityteam.aeropages.model.TextId.INDEX_EMPTY))}</text:p>""")
        lines.forEach { l ->
            val style = if (l.heading) "Index_20_Separator" else "Index_20_${l.level + 1}"
            val shown = l.pages { doc.pageCache[it.id] }
            val tail = when { shown.isEmpty() -> ""; block.rightAlign -> "<text:tab/>${escape(shown)}"; else -> ", ${escape(shown)}" }
            append("""<text:p text:style-name="$style">${escape(l.text)}$tail</text:p>""")
        }
        append("</text:index-body></text:alphabetical-index>")
    }

    /** Caption labels LibreOffice indexes as tables (its own and Word's in the languages the app speaks). */
    private val TABLE_LABELS = setOf("table", "tables", "表", "表格")

    /**
     * ODF's table of contents: a `text:table-of-content` built from outline levels 1–3 (headings are
     * written as `text:h` with their level), with a dot-leader page-number template per level and
     * the current entries as its body; LibreOffice refreshes the page numbers on Update Index.
     */
    private fun tableOfContents(doc: com.therealityteam.aeropages.model.Document, styles: StyleTable): String = buildString {
        val title = escape(com.therealityteam.aeropages.model.Texts.get(com.therealityteam.aeropages.model.TextId.TOC_HEADING))
        append("""<text:table-of-content text:name="Table of Contents1" text:protected="true">""")
        append("""<text:table-of-content-source text:outline-level="3"><text:index-title-template>$title</text:index-title-template>""")
        for (level in 1..3) {
            append("""<text:table-of-content-entry-template text:outline-level="$level" text:style-name="Contents_20_$level">""")
            append("""<text:index-entry-link-start/><text:index-entry-text/><text:index-entry-tab-stop style:type="right" style:leader-char="."/><text:index-entry-page-number/><text:index-entry-link-end/>""")
            append("</text:table-of-content-entry-template>")
        }
        append("</text:table-of-content-source><text:index-body>")
        append("""<text:index-title text:name="Table of Contents1_Head"><text:p text:style-name="Contents_20_Heading">$title</text:p></text:index-title>""")
        doc.tocEntries().forEach { e ->
            // Each entry links to its heading (through the heading's bookmark), as LibreOffice's own do.
            val entry = "${escape(e.text)}<text:tab/>${doc.pageCache[e.paragraph.id]?.let(::escape).orEmpty()}"
            val mark = styles.tocMarks[e.paragraph.id]?.first
            append("""<text:p text:style-name="Contents_20_${e.level + 1}">""")
            append(if (mark != null) """<text:a xlink:type="simple" xlink:href="#${escape(mark)}">$entry</text:a>""" else entry)
            append("</text:p>")
        }
        append("</text:index-body></text:table-of-content>")
    }

    /** One paragraph: `text:h` for headings (so the outline is real), `text:p` otherwise. */
    /** A floating picture's, text box's or table's position, if it has one. */
    private fun floatOf(b: com.therealityteam.aeropages.model.Block) = when (b) {
        is ImageBlock -> b.floating
        is com.therealityteam.aeropages.model.TextBoxBlock -> b.floating
        is TableBlock -> b.table.floating
        else -> null
    }

    /**
     * A floating table as LibreOffice keeps Word's: a frame anchored to its paragraph, at the table's place
     * and width, holding a text box that holds the table (growing with it).
     */
    private fun floatingTable(t: Table, styles: StyleTable, pictures: LinkedHashMap<String, ByteArray>, page: com.therealityteam.aeropages.model.PageSetup, textWidth: Float): String {
        val w = t.widthPt ?: textWidth
        val pos = inOdfFrames(t.floating ?: return table(t, styles, pictures, textWidth), page, 0f)
        return """<draw:frame draw:style-name="${styles.floatFrameStyle(pos, textFrame = true)}" text:anchor-type="paragraph" ${placeAttrs(pos)} svg:width="${pt(w)}" draw:z-index="0">""" +
            """<draw:text-box fo:min-height="${pt(PageMetrics.ROW_MIN)}">""" + table(t, styles, pictures, w) + "</draw:text-box></draw:frame>"
    }

    /** List items as a `text:list` of [name] (with [attrs]), each level a list nested in the item before it. */
    private fun listXml(items: List<Paragraph>, name: String, attrs: String, styles: StyleTable): String = buildString {
        fun emit(from: Int, level: Int, top: Boolean): Int {
            append(if (top) """<text:list text:style-name="$name"$attrs>""" else "<text:list>")
            var i = from
            while (i < items.size && items[i].format.listLevel >= level) {
                append("<text:list-item>")
                if (items[i].format.listLevel == level) { append(paragraph(items[i], styles)); i++ }
                if (i < items.size && items[i].format.listLevel > level) i = emit(i, level + 1, false)
                append("</text:list-item>")
            }
            append("</text:list>")
            return i
        }
        var i = 0
        while (i < items.size) i = emit(i, 0, top = true).let { if (it == i) i + 1 else it }
    }

    /** A cell's or a box's paragraphs: plain ones, and each run of list items a list (its own, restarting). */
    private fun paragraphsXml(paras: List<Paragraph>, styles: StyleTable): String = buildString {
        var i = 0
        while (i < paras.size) {
            val p = paras[i]
            if (p.format.listKind == ListKind.NONE) { append(paragraph(p, styles, forceParagraph = true)); i++; continue }
            val f = p.format
            val def = styles.lists[f.listId]?.takeIf { f.listId != 0 }
            var j = i
            while (j < paras.size && paras[j].format.listKind == f.listKind && paras[j].format.listId == f.listId) j++
            append(listXml(paras.subList(i, j), if (def != null) styles.listStyle(f.listId, def) else styles.listStyle(f.listKind), "", styles))
            i = j
        }
    }

    /** [p], and the frames floating from it ([anchored]) at its start. */
    private fun paragraph(p: Paragraph, styles: StyleTable, forceParagraph: Boolean = false, anchored: String = ""): String {
        val name = styles.paragraphStyle(p, styles.sectionStarts[p])
        val level = if (forceParagraph) 0 else outlineLevel(p.format.style)
        val inner = anchored + runs(p, styles)
        val unnumbered = if (styles.outline != 0 && p.format.listKind == ListKind.NONE) """ text:is-list-header="true"""" else ""
        return if (level > 0) """<text:h text:style-name="$name" text:outline-level="$level"$unnumbered>$inner</text:h>"""
        else """<text:p text:style-name="$name">$inner</text:p>"""
    }

    /**
     * The list that numbers the headings, written as ODF's outline numbering (a heading in a list loses its
     * heading look in LibreOffice): the first list whose items are all headings, each at the level its
     * heading's is (第一章 / 第一节, 1 / 1.1); 0 for none.
     */
    private fun outlineList(doc: Document): Int {
        val byList = doc.paragraphs().filter { it.format.listKind == ListKind.NUMBER && it.format.listId != 0 && doc.lists.containsKey(it.format.listId) }.groupBy { it.format.listId }
        return byList.entries.firstOrNull { (_, items) -> items.all { outlineLevel(it.format.style) == it.format.listLevel + 1 } }?.key ?: 0
    }

    private fun outlineLevel(style: ParagraphStyle): Int = when (style) {
        ParagraphStyle.HEADING1 -> 1
        ParagraphStyle.HEADING2 -> 2
        ParagraphStyle.HEADING3 -> 3
        else -> 0
    }

    /**
     * A formula object: the MathML in `Object N/content.xml`, and the frame showing it as a character
     * of its paragraph, centred on the line. The frame gives no size: LibreOffice sets the formula
     * and sizes it (a size given would scale the formula to it).
     */
    private fun formula(math: com.therealityteam.aeropages.model.MathNode, display: Boolean, styles: StyleTable): String {
        val n = styles.formulas.size + 1
        val dir = "Object $n"
        styles.formulas["$dir/content.xml"] = MathMl.document(math, display).toByteArray(Charsets.UTF_8)
        return """<draw:frame draw:style-name="${styles.formulaFrameStyle()}" draw:name="Formula$n" text:anchor-type="as-char" draw:z-index="0">""" +
            """<draw:object xlink:href="./$dir" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame>"""
    }

    /** A deletion's paragraph ([p]'s style, a heading as one) holding its deleted [text]. */
    private fun changedParagraph(p: Paragraph, text: String, styles: StyleTable): String {
        val name = styles.paragraphStyle(p)
        val level = outlineLevel(p.format.style)
        return if (level > 0) """<text:h text:style-name="$name" text:outline-level="$level">$text</text:h>"""
        else """<text:p text:style-name="$name">$text</text:p>"""
    }

    private fun runs(p: Paragraph, styles: StyleTable): String = buildString {
        val changes = styles.changes
        changes?.begin(p)
        // A heading the table of contents links to without a bookmark of its own: a new `_Toc` one round its text.
        val tocMark = styles.tocMarks[p.id]?.takeIf { it.second }?.first?.let { escape(it) }
        if (tocMark != null) append("""<text:bookmark-start text:name="$tocMark"/>""")
        // Bookmarks stand between runs: a run with one starting or ending inside it is cut there.
        val marks = com.therealityteam.aeropages.model.Bookmarks.Cursor(p)
        var pos = 0
        var i = 0
        while (i < p.runs.size) {
            val run = p.runs[i]
            i++
            append(bookmarks(marks.upTo(pos), styles))
            // Tracked changes: a deleted run's text goes to its deletion (a mark here), others start or end what they are in.
            if (changes != null && run.text.isNotEmpty()) {
                if (run.format.deleted) {
                    val plain = run.format.copyOf().also { it.deleted = false; it.inserted = false; it.formatChanged = false; it.revAuthor = null; it.revDate = null }
                    append(changes.deleted(run) { t -> textOf(t, plain, p, styles) })
                    pos += run.text.length
                    continue
                }
                append(changes.toward(run.format))
            }
            // An equation still as it was read goes back as a formula object; once edited its runs are text.
            val math = run.format.math
            if (math != null) {
                var end = i
                val text = StringBuilder(run.text)
                while (end < p.runs.size && p.runs[end].format.math === math) { text.append(p.runs[end].text); end++ }
                if (text.toString() == math.text) {
                    append(formula(math.math, false, styles))
                    i = end
                    pos += text.length
                    continue
                }
            }
            // A live field's result (a caption's number, a cross-reference): ODF's own field, its text inside, in its first run's look.
            val field = run.format.field
            if (field != null) {
                var end = i
                val text = StringBuilder(run.text)
                while (end < p.runs.size && p.runs[end].format.field === field) { text.append(p.runs[end].text); end++ }
                // A caption with its chapter's number (Word's STYLEREF, separator, `SEQ \s`): one sequence that shows the
                // chapter, as LibreOffice numbers by chapter — its declaration says the level and the separator.
                // It holds the sequence's own number: LibreOffice reads its value from the text and puts the chapter before it.
                val seq = chapterSequence(p, field, end)
                if (seq != null) {
                    val (seqField, sep, after) = seq
                    text.setLength(0)
                    for (k in end + 1 until after) text.append(p.runs[k].text)
                    end = after
                    styles.sequenceChapters.putIfAbsent(seqField.target, (seqField.after("\\s")?.toIntOrNull() ?: 1) to sep)
                }
                val xml = if (field.kind == "=") tableFormula(field, text.toString(), styles.formulaCells[p]) else fieldXml(seq?.first ?: field, text.toString())
                if (xml != null) {
                    val look = styles.lookOf(p)
                    val styleName = styles.textStyle(run.format.copyOf().also { it.link = null; it.field = null }, look.bold, look.italic)
                    append(if (styleName == null) xml else """<text:span text:style-name="$styleName">$xml</text:span>""")
                    styles.sequences.takeIf { (seq?.first ?: field).kind == "SEQ" }?.add((seq?.first ?: field).target)
                    i = end
                    pos += text.length
                    continue
                }
            }
            if (run.text.isEmpty()) continue
            // Superscript "[n]" / "[iv]" markers are note anchors: ODF keeps the note's text inline.
            if (run.format.superscript && run.text.contains('[')) {
                val notes = noteAnchors(run.text, styles)
                if (notes != null) { append(notes); pos += run.text.length; continue }
            }
            var from = 0
            while (true) {
                val cut = marks.cutIn(pos + from, pos + run.text.length) ?: break
                append(textOf(run.text.substring(from, cut - pos), run.format, p, styles))
                from = cut - pos
                append(bookmarks(marks.upTo(cut), styles))
            }
            append(textOf(run.text.substring(from), run.format, p, styles))
            pos += run.text.length
        }
        append(bookmarks(marks.rest(), styles))
        if (tocMark != null) append("""<text:bookmark-end text:name="$tocMark"/>""")
        // Comments on the paragraph as a whole: at its end, each thread's replies after it.
        styles.commentsOn[p.id]?.forEach { c -> append(annotation(c, null, null)); styles.commentReplies[c.id]?.forEach { append(annotation(it, null, null)) } }
        // What is open ends with the paragraph, unless its inserted mark carries the insertion into the next.
        changes?.end(p)?.let { append(it) }
    }

    /**
     * A comment as LibreOffice writes one: `office:annotation` with its author, date and text — named when its range
     * ends at an `office:annotation-end` ([name]), a reply naming its thread's ([parent], LibreOffice's `loext:parent-name`).
     */
    private fun annotation(c: com.therealityteam.aeropages.model.Comment, name: String?, parent: String?): String = buildString {
        append("<office:annotation")
        if (name != null) append(""" office:name="$name"""")
        if (parent != null) append(""" loext:parent-name="$parent"""")
        if (c.resolved) append(""" loext:resolved="true"""")
        append("><dc:creator>").append(escape(c.author)).append("</dc:creator>")
        val date = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(c.dateMs), java.time.ZoneOffset.UTC).withNano(0)
        append("<dc:date>").append(date).append("</dc:date>")
        c.text.split('\n').forEach { line -> append("<text:p>").append(escape(line)).append("</text:p>") }
        append("</office:annotation>")
    }

    /** The name a thread with a range is written under (`office:name`), its replies naming it. */
    private fun annotationName(id: Long) = "__Annotation__$id"

    /**
     * When chapter-number field [field] (`STYLEREF n \s`, its runs ending before run [end]) stands before a separator
     * (one run) and a sequence numbered by that chapter (`SEQ … \s n`): the sequence, the separator and the run after it.
     */
    private fun chapterSequence(p: Paragraph, field: com.therealityteam.aeropages.model.DocField, end: Int): Triple<com.therealityteam.aeropages.model.DocField, String, Int>? {
        if (field.kind != "STYLEREF" || !field.has("\\s")) return null
        val sep = p.runs.getOrNull(end)?.takeIf { it.format.field == null && it.text.length in 1..3 && it.text.none { c -> c.isLetterOrDigit() } } ?: return null
        val seq = p.runs.getOrNull(end + 1)?.format?.field?.takeIf { it.kind == "SEQ" && it.after("\\s") == com.therealityteam.aeropages.model.Fields.styleLevel(field.target)?.toString() } ?: return null
        var after = end + 1
        while (after < p.runs.size && p.runs[after].format.field === seq) after++
        return Triple(seq, sep.text, after)
    }

    /**
     * A live field as ODF writes one, showing [text]: a sequence (a caption's number) as `text:sequence`, a reference to a
     * bookmark as `text:bookmark-ref` — its text, its page, its number, above / below; a chapter's number or heading as
     * `text:chapter`; null for one ODF has no way to write.
     */
    /**
     * A table formula as LibreOffice keeps Word's: `text:table-formula` with its formula in LibreOffice's words — cells
     * in angle brackets (`<B2>*<C2>`), AVERAGE as MEAN, and ABOVE / LEFT / BELOW / RIGHT as the range of numbers they
     * take from the formula's cell [at] — showing [text].
     */
    private fun tableFormula(field: com.therealityteam.aeropages.model.DocField, text: String, at: Triple<com.therealityteam.aeropages.model.Table, Int, Int>?): String {
        var f = com.therealityteam.aeropages.model.TableFormulas.formulaOf(field.code)
        if (at != null) {
            val (t, r, c) = at
            fun name(rr: Int, cc: Int) = com.therealityteam.aeropages.model.Workbook.colLabel(cc) + (rr + 1)
            f = Regex("""(?i)\b(ABOVE|LEFT|BELOW|RIGHT)\b""").replace(f) { m ->
                val (dr, dc) = when (m.value.uppercase()) { "ABOVE" -> -1 to 0; "BELOW" -> 1 to 0; "LEFT" -> 0 to -1; else -> 0 to 1 }
                var rr = r + dr; var cc = c + dc; var lastR = r; var lastC = c
                while (rr in t.rows.indices && cc >= 0 && com.therealityteam.aeropages.model.TableFormulas.number(t, rr, cc) != null) { lastR = rr; lastC = cc; rr += dr; cc += dc }
                if (lastR == r && lastC == c) name(r + dr, c + dc) else "${name(r + dr, c + dc)}:${name(lastR, lastC)}"
            }
        }
        val lo = Regex("""(?i)\b([A-Z]{1,3}\d+)(:([A-Z]{1,3}\d+))?\b""").replace(f) { m -> "<" + m.value.uppercase() + ">" }
            .replace(Regex("""(?i)\bAVERAGE\b"""), "MEAN")
        return """<text:table-formula text:formula="ooow:${escape(lo)}">${escape(text)}</text:table-formula>"""
    }

    private fun fieldXml(field: com.therealityteam.aeropages.model.DocField, text: String): String? {
        val shown = escape(text)
        return when (field.kind) {
            "SEQ" -> {
                val name = escape(field.target)
                val format = when (field.after("\\*")) { "ALPHABETIC" -> "A"; "alphabetic" -> "a"; "ROMAN" -> "I"; "roman" -> "i"; else -> "1" }
                """<text:sequence text:name="$name" text:formula="ooow:$name+1" style:num-format="$format">$shown</text:sequence>"""
            }
            "REF", "PAGEREF" -> {
                val format = when {
                    field.kind == "PAGEREF" -> "page"
                    field.has("\\p") -> "direction"
                    field.has("\\r") || field.has("\\n") || field.has("\\w") -> "number"
                    else -> "text"
                }
                """<text:bookmark-ref text:reference-format="$format" text:ref-name="${escape(field.target)}">$shown</text:bookmark-ref>"""
            }
            "STYLEREF" -> {
                val level = com.therealityteam.aeropages.model.Fields.styleLevel(field.target) ?: return null
                val display = if (field.has("\\s") || field.has("\\n") || field.has("\\r") || field.has("\\w")) "number" else "name"
                """<text:chapter text:display="$display" text:outline-level="$level">$shown</text:chapter>"""
            }
            else -> null
        }
    }

    /** [text] of a run formatted as [format]: in its text style's span, in its link. */
    private fun textOf(text: String, format: CharacterFormat, p: Paragraph, styles: StyleTable): String {
        val spaced = spacePreserved(text)
        // A run that is not bold (italic) inside a bold (italic) heading says so, or it would take the style's.
        val look = styles.lookOf(p)
        val styleName = styles.textStyle(format, look.bold, look.italic)
        val span = if (styleName == null) spaced else """<text:span text:style-name="$styleName">$spaced</text:span>"""
        val link = format.link
        return if (!link.isNullOrBlank()) """<text:a xlink:type="simple" xlink:href="${escape(link)}">$span</text:a>""" else span
    }

    /**
     * Bookmark starts and ends where they stand: a name's first start — a `text:bookmark` when it is a
     * point (its end at the same place, or none after it) —, the end of one started; others left out.
     */
    private fun bookmarks(marks: List<com.therealityteam.aeropages.model.BookmarkMark>, styles: StyleTable): String {
        if (marks.isEmpty()) return ""
        val sb = StringBuilder()
        marks.forEachIndexed { k, m ->
            // An index entry: ODF's alphabetical index mark (a subentry's main entry as its first key).
            if (com.therealityteam.aeropages.model.IndexEntries.isEntry(m.name)) {
                com.therealityteam.aeropages.model.IndexEntries.entryOf(m.name)?.let { e ->
                    if (e.sub == null) sb.append("""<text:alphabetical-index-mark text:string-value="${escape(e.main)}"/>""")
                    else sb.append("""<text:alphabetical-index-mark text:string-value="${escape(e.sub)}" text:key1="${escape(e.main)}"/>""")
                }
                return@forEachIndexed
            }
            // A comment's range: its annotation (and its replies') where it starts, the end where it ends.
            if (com.therealityteam.aeropages.model.Bookmarks.isComment(m.name)) {
                val c = com.therealityteam.aeropages.model.Bookmarks.commentIdOf(m.name)?.takeIf { it in styles.commentRanged }?.let { styles.commentById[it] } ?: return@forEachIndexed
                val n = annotationName(c.id)
                if (!m.end) {
                    sb.append(annotation(c, n, null))
                    styles.commentReplies[c.id]?.forEach { sb.append(annotation(it, null, n)) }
                } else sb.append("""<office:annotation-end office:name="$n"/>""")
                return@forEachIndexed
            }
            val name = escape(m.name)
            if (!m.end) {
                if (!styles.bookmarksStarted.add(m.name)) return@forEachIndexed
                val point = m.name !in styles.bookmarksClosed || marks.subList(k + 1, marks.size).any { it.end && it.name == m.name }
                if (point) { styles.bookmarksEnded.add(m.name); sb.append("""<text:bookmark text:name="$name"/>""") }
                else sb.append("""<text:bookmark-start text:name="$name"/>""")
            } else if (m.name in styles.bookmarksStarted && styles.bookmarksEnded.add(m.name)) {
                sb.append("""<text:bookmark-end text:name="$name"/>""")
            }
        }
        return sb.toString()
    }

    /** A superscript run's markers as `text:note` elements (null when the run has no valid marker). */
    private fun noteAnchors(text: String, styles: StyleTable): String? {
        val rx = Regex("""\[([0-9]+|[ivxlcdm]+)]""")
        val matches = rx.findAll(text).toList()
        if (matches.isEmpty()) return null
        val out = StringBuilder()
        var pos = 0
        var any = false
        matches.forEach { m ->
            val body = m.groupValues[1]
            val digits = body.toIntOrNull()
            val (cls, id, note) = when {
                digits != null -> Triple("footnote", digits, styles.footnotes.getOrNull(digits - 1))
                else -> com.therealityteam.aeropages.model.Roman.parse(body).let { Triple("endnote", it ?: 0, it?.let { n -> styles.endnotes.getOrNull(n - 1) }) }
            }
            if (note == null) return@forEach
            any = true
            if (m.range.first > pos) out.append(escape(text.substring(pos, m.range.first)))
            val label = if (cls == "footnote") id.toString() else com.therealityteam.aeropages.model.Roman.of(id)
            out.append("""<text:note text:id="${cls.first()}tn$id" text:note-class="$cls"><text:note-citation>$label</text:note-citation>""")
            out.append("""<text:note-body><text:p>${escape(note)}</text:p></text:note-body></text:note>""")
            pos = m.range.last + 1
        }
        if (!any) return null
        if (pos < text.length) out.append(escape(text.substring(pos)))
        return out.toString()
    }

    /** ODF collapses runs of spaces unless they are marked up. */
    private fun spacePreserved(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == ' ') {
                var n = 0
                while (i + n < text.length && text[i + n] == ' ') n++
                if (n == 1) sb.append(' ') else sb.append(' ').append("""<text:s text:c="${n - 1}"/>""")
                i += n
            } else if (c == '\t') { sb.append("<text:tab/>"); i++ } else {
                sb.append(escape(c.toString())); i++
            }
        }
        return sb.toString()
    }

    /**
     * A text box: a `draw:frame` with a `draw:text-box`, anchored as a character in a paragraph of its
     * own (a drawn text box in its shape's graphic style); any other drawn shape a `draw:custom-shape`
     * (a line a `draw:line`) in its graphic style — fill, outline, dashes, arrowheads — holding its
     * paragraphs, as LibreOffice writes one.
     */
    private fun textBox(box: com.therealityteam.aeropages.model.TextBoxBlock, styles: StyleTable, floating: Boolean = false,
                        page: com.therealityteam.aeropages.model.PageSetup): String = buildString {
        val w = box.widthPt ?: 320f
        val h = box.heightPt ?: (box.paragraphs.size * 18f + 16f)
        val sh = box.shape
        // Floating: anchored to its paragraph at its place (no paragraph of its own); else a character in one.
        val pos = box.floating.takeIf { floating }?.let { inOdfFrames(it, page, h) }
        val anchor = if (pos != null) """text:anchor-type="paragraph" ${placeAttrs(pos)}""" else """text:anchor-type="as-char""""
        val placed = pos?.let { styles.floatProps(it) } ?: ""
        val (open, close) = if (pos != null) "" to "" else "<text:p>" to "</text:p>"
        if (sh != null && sh.type != com.therealityteam.aeropages.model.ShapeType.TEXTBOX) {
            val style = styles.shapes.shape(sh, placed)
            if (sh.type == com.therealityteam.aeropages.model.ShapeType.LINE) {
                val x1 = if (sh.flipH) w else 0f; val y1 = if (sh.flipV) h else 0f
                val (ox, oy) = if (pos != null) pos.x to pos.y else 0f to 0f
                val lineAnchor = if (pos != null) """text:anchor-type="paragraph"""" else """text:anchor-type="as-char""""
                append("""$open<draw:line draw:style-name="$style" $lineAnchor svg:x1="${pt(ox + x1)}" svg:y1="${pt(oy + y1)}" svg:x2="${pt(ox + w - x1)}" svg:y2="${pt(oy + h - y1)}"/>$close""")
                return@buildString
            }
            val turn = if (sh.rotationDeg != 0f) """ draw:transform="rotate (${Math.toRadians(-sh.rotationDeg.toDouble())})"""" else ""
            append("""$open<draw:custom-shape draw:style-name="$style" $anchor svg:width="${pt(w)}" svg:height="${pt(h)}"$turn>""")
            append(paragraphsXml(box.paragraphs, styles))
            append(OdpWriter.geometryXml(sh))
            append("</draw:custom-shape>$close")
            return@buildString
        }
        // A text box: a frame holding a text box — a drawn one (Word's) in its look, fill and line, turned as it is.
        val style = if (sh != null) styles.shapes.shape(sh, placed) else if (pos != null) styles.floatFrameStyle(pos, textFrame = true) else styles.frameStyle()
        val turn = if (sh != null && sh.rotationDeg != 0f) """ draw:transform="rotate (${Math.toRadians(-sh.rotationDeg.toDouble())})"""" else ""
        append("""$open<draw:frame draw:style-name="$style" $anchor svg:width="${pt(w)}" svg:height="${pt(h)}" draw:z-index="0"$turn>""")
        append("<draw:text-box>")
        append(paragraphsXml(box.paragraphs, styles))
        append("</draw:text-box></draw:frame>$close")
    }

    /**
     * [pos] in frames ODF has: a place in the band above or below the text (Word's top and bottom margin
     * frames, which ODF lacks) as an offset from the page's top, for an object [h] tall.
     */
    private fun inOdfFrames(pos: com.therealityteam.aeropages.model.FloatPosition, page: com.therealityteam.aeropages.model.PageSetup, h: Float): com.therealityteam.aeropages.model.FloatPosition {
        val top = when (pos.vFrom) {
            com.therealityteam.aeropages.model.FloatFrame.TOP_MARGIN -> 0f
            com.therealityteam.aeropages.model.FloatFrame.BOTTOM_MARGIN -> page.heightPt - page.marginBottomPt
            else -> return pos
        }
        val band = if (pos.vFrom == com.therealityteam.aeropages.model.FloatFrame.TOP_MARGIN) page.marginTopPt else page.marginBottomPt
        val y = top + when (pos.vAlign) {
            null -> pos.y
            com.therealityteam.aeropages.model.FloatAlign.START -> 0f
            com.therealityteam.aeropages.model.FloatAlign.CENTER -> (band - h) / 2f
            com.therealityteam.aeropages.model.FloatAlign.END -> band - h
        }
        return pos.copy(y = y, vFrom = com.therealityteam.aeropages.model.FloatFrame.PAGE, vAlign = null)
    }

    /** Where a floating frame stands: `svg:x` / `svg:y` from its frames. */
    private fun placeAttrs(pos: com.therealityteam.aeropages.model.FloatPosition): String = """svg:x="${pt(pos.x)}" svg:y="${pt(pos.y)}""""

    /** A table [textWidthPt] wide (the page's text width): cells hold their paragraphs, then their pictures. */
    private fun table(t: Table, styles: StyleTable, pictures: LinkedHashMap<String, ByteArray>, textWidthPt: Float): String = buildString {
        val cols = t.columnCount.coerceAtLeast(1)
        val name = styles.nextTableName()
        append("""<table:table table:name="$name" table:style-name="${styles.tableStyle(t)}">""")
        val total = (0 until cols).sumOf { t.weightOf(it).toDouble() }.coerceAtLeast(0.001)
        for (c in 0 until cols) append("""<table:table-column table:style-name="${styles.columnStyle((t.weightOf(c) / total).toFloat())}"/>""")
        // Leading header rows sit in table:table-header-rows (they repeat on every page in ODF too).
        val headerRows = t.headerRowCount()
        t.rows.forEachIndexed { ri, row ->
            if (ri == 0 && headerRows > 0) append("<table:table-header-rows>")
            append(row.heightPt?.let { """<table:table-row table:style-name="${styles.rowStyle(it, row.exactHeight)}">""" } ?: "<table:table-row>")
            var covered = 0
            for (cell in row.cells) {
                // A cell covered from above: one covered cell for each column it spans.
                if (cell.vMerged) { repeat(cell.gridSpan.coerceAtLeast(1)) { append("<table:covered-table-cell/>") }; covered += cell.gridSpan.coerceAtLeast(1); continue }
                val span = cell.gridSpan.coerceAtLeast(1)
                val rowSpan = cell.rowSpan.coerceAtLeast(1)
                val spanAttr = buildString {
                    if (span > 1) append(""" table:number-columns-spanned="$span"""")
                    if (rowSpan > 1) append(""" table:number-rows-spanned="$rowSpan"""")
                }
                append("""<table:table-cell office:value-type="string" table:style-name="${styles.cellStyle(cell.shade, t.bordersOf(cell))}"$spanAttr>""")
                append(paragraphsXml(cell.content, styles))
                if (cell.images.isNotEmpty()) {
                    val share = (covered until covered + span).sumOf { if (it < cols) t.weightOf(it).toDouble() else 0.0 } / total
                    val cellW = (textWidthPt * share).toFloat() - 12f
                    cell.images.forEach { append(image(it, styles, pictures, maxWidthPt = cellW.coerceAtLeast(24f))) }
                }
                append("</table:table-cell>")
                covered += span
                repeat(span - 1) { append("<table:covered-table-cell/>") }
            }
            append("</table:table-row>")
            if (headerRows > 0 && ri == headerRows - 1) append("</table:table-header-rows>")
        }
        append("</table:table>")
    }

    private fun image(block: ImageBlock, styles: StyleTable, pictures: LinkedHashMap<String, ByteArray>, maxWidthPt: Float? = null,
                      floating: Boolean = false, page: com.therealityteam.aeropages.model.PageSetup? = null): String {
        val name = "Pictures/image${pictures.size + 1}.${OdfPackage.imageExt(block.mime)}"
        pictures[name] = block.data
        // Inside a cell the picture is scaled down to the cell's width, as in the docx writer.
        val scale = if (maxWidthPt != null && block.widthPt > maxWidthPt && block.widthPt > 0f) maxWidthPt / block.widthPt else 1f
        val clip = com.therealityteam.aeropages.format.PictureCrop.foClip(block, block.data)
        val mirror = when {
            block.flipH && block.flipV -> "horizontal vertical"
            block.flipH -> "horizontal"
            block.flipV -> "vertical"
            else -> null
        }
        // Its alternative text after the image (svg:desc) and LibreOffice's decorative mark on the frame.
        val desc = OdfAltText.desc(block.altText)
        val frame = """svg:width="${pt(block.widthPt * scale)}" svg:height="${pt(block.heightPt * scale)}">""" +
            """<draw:image xlink:href="$name" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/>$desc</draw:frame>"""
        val pg = page ?: com.therealityteam.aeropages.model.PageSetup()
        val pos = block.floating.takeIf { floating }?.let { inOdfFrames(it, pg, block.heightPt * scale) }
        // Floating: anchored to its paragraph at its place; else a character of a centred paragraph of its own.
        if (pos != null) {
            return """<draw:frame draw:style-name="${styles.floatFrameStyle(pos, clip, block.shadow, mirror, picture = true, decorative = block.decorative)}" text:anchor-type="paragraph" ${placeAttrs(pos)} """ + frame
        }
        return """<text:p text:style-name="${styles.centeredStyle()}"><draw:frame draw:style-name="${styles.frameStyle(clip, block.shadow, mirror, picture = true, decorative = block.decorative)}" """ +
            """text:anchor-type="as-char" """ + frame + "</text:p>"
    }

    // --- styles.xml ------------------------------------------------------------------------

    /** styles.xml: the paragraph styles, the page, and the [named] drawing styles (gradients, dashes, markers) shapes use. */
    /** The font the app shows a document's unset text in (its font menus say Calibri). */
    private const val DEFAULT_FONT = "Calibri"

    /** The gap between text columns (Word's and LibreOffice's half inch). */
    /**
     * A page layout's margins, borders and padding: ODF's margin reaches the border and the padding stands between it and
     * the text, so with Word's page borders each side's [margins] (top, bottom, left, right: what the page's content stands
     * in from its edge) split into the border's place — its space from the edge, or out from the text — the line, and the
     * padding left; with a shadow, `style:shadow`. Sides without a line keep their whole margin.
     */
    private fun pageBorderProps(b: com.therealityteam.aeropages.model.PageBorders?, page: com.therealityteam.aeropages.model.PageSetup, margins: FloatArray): String = buildString {
        val sides = listOf("top", "bottom", "left", "right")
        val lines = listOf(b?.top, b?.bottom, b?.left, b?.right)
        val spaces = listOf(b?.spaceTop, b?.spaceBottom, b?.spaceLeft, b?.spaceRight)
        val text = floatArrayOf(page.marginTopPt, page.marginBottomPt, page.marginLeftPt, page.marginRightPt)
        for (k in 0..3) {
            val line = lines[k]
            if (b == null || b.none || line == null) { append("fo:margin-${sides[k]}=\"${pt(margins[k])}\" "); continue }
            val w = line.thicknessPt
            val at = if (b.fromText) text[k] - spaces[k]!! - w else spaces[k]!!
            val margin = minOf(at, margins[k] - w).coerceAtLeast(0f)
            append("fo:margin-${sides[k]}=\"${pt(margin)}\" fo:padding-${sides[k]}=\"${pt((margins[k] - margin - w).coerceAtLeast(0f))}\" ")
            append("fo:border-${sides[k]}=\"${OdfBorders.cellValueOf(line)}\" ")
            if (line.style == com.therealityteam.aeropages.model.LineStyle.DOUBLE) append("style:border-line-width-${sides[k]}=\"${OdfBorders.doubleWidths(line)}\" ")
        }
        if (b != null && b.shadow && !b.none) {
            val line = b.right ?: b.bottom ?: b.top ?: b.left
            val off = maxOf(2f, (line?.widthPt ?: 1f) * 2f)
            append("style:shadow=\"" + String.format(java.util.Locale.ROOT, "#%06X", (line?.color ?: 0xFF000000L) and 0xFFFFFF) + " ${pt(off)} ${pt(off)}\" ")
        }
    }


    /** A header line's height, the part of the header area that is not spacing (12 pt text, single spaced). */
    private const val HEADER_LINE_PT = 14.4f

    private fun styles(doc: Document, named: String = "", masters: LinkedHashMap<com.therealityteam.aeropages.model.PageSetup, String> = LinkedHashMap(),
                       pictures: LinkedHashMap<String, ByteArray> = LinkedHashMap()): String = buildString {
        val pg = doc.page
        append(HEAD)
        append("<office:document-styles $NS>")
        // The document's font and size (Calibri, 11 pt unless it says otherwise) as the default for
        // every paragraph — without them LibreOffice sets unstyled text in its serif at 12 pt.
        val font = doc.styles.defaultFontFamily ?: DEFAULT_FONT
        append("""<office:font-face-decls><style:font-face style:name="${escape(font)}" svg:font-family="${escape(font)}" style:font-family-generic="swiss" style:font-pitch="variable"/></office:font-face-decls>""")
        append("<office:styles>")
        append(named)
        val size = pt(doc.styles.defaultSizePt)
        append("""<style:default-style style:family="paragraph"><style:paragraph-properties style:tab-stop-distance="${pt(doc.defaultTabPt)}"/>""")
        append("""<style:text-properties style:font-name="${escape(font)}" fo:font-family="${escape(font)}" fo:font-size="$size" style:font-size-asian="$size" style:font-size-complex="$size"/></style:default-style>""")
        append("""<style:style style:name="Standard" style:family="paragraph"/>""")
        // The headings and the title as the document's own styles have them (a heading is kept with
        // what follows it; lines kept together only where a paragraph says so).
        fun look(style: com.therealityteam.aeropages.model.ParagraphStyle): String {
            val r = doc.styles.resolve(style)
            return buildString {
                append(OdfPackage.sizeAttr(pt(r.sizePt))).append(OdfPackage.weightAttr(if (r.bold) "bold" else "normal").trim())
                if (r.italic) append(" ").append(OdfPackage.italicAttr("italic").trim())
                r.color?.let { append(""" fo:color="${color(it)}"""") }
                r.fontFamily?.let { append(""" style:font-name="${escape(it)}" fo:font-family="${escape(it)}"""") }
            }
        }
        listOf(com.therealityteam.aeropages.model.ParagraphStyle.HEADING1, com.therealityteam.aeropages.model.ParagraphStyle.HEADING2, com.therealityteam.aeropages.model.ParagraphStyle.HEADING3)
            .forEachIndexed { k, style ->
                val i = k + 1
                append("""<style:style style:name="Heading_20_$i" style:display-name="Heading $i" style:family="paragraph" style:parent-style-name="Standard" style:next-style-name="Standard" style:default-outline-level="$i">""")
                append("""<style:paragraph-properties fo:keep-with-next="always"/>""")
                append("""<style:text-properties ${look(style)}/></style:style>""")
            }
        append("""<style:style style:name="Title" style:family="paragraph" style:parent-style-name="Standard">""")
        append("""<style:text-properties ${look(com.therealityteam.aeropages.model.ParagraphStyle.TITLE)}/></style:style>""")
        append(ownStylesXml(doc.styles))
        // Text frames' style (LibreOffice's "Frame"): a frame whose style comes from it is a text frame, which may hold a table.
        append("""<style:style style:name="Frame" style:family="graphic"/>""")
        // Pictures' style (LibreOffice's "Graphics"): a frame whose style comes from it is a Writer picture — without it
        // LibreOffice reads the picture as a drawing shape and drops what only pictures have (the decorative mark).
        append("""<style:style style:name="Graphics" style:family="graphic"/>""")
        // The tables of contents' and of figures' lines, as LibreOffice's own Contents / index styles: each level indented,
        // a dotted leader to the page number at the text's right edge (tab stops count from the indent) — without them
        // LibreOffice shows the stored entries with the number just after the text.
        if (doc.blocks.any { it is com.therealityteam.aeropages.model.TocBlock }) {
            val first = doc.setupAt(0)
            val width = first.widthPt - first.marginLeftPt - first.marginRightPt
            fun line(name: String, display: String, indent: Float) {
                append("""<style:style style:name="$name" style:display-name="$display" style:family="paragraph" style:parent-style-name="Standard" style:class="index">""")
                append("""<style:paragraph-properties fo:margin-left="${pt(indent)}" fo:margin-top="0pt" fo:margin-bottom="0pt"><style:tab-stops>""")
                append("""<style:tab-stop style:position="${pt(width - indent)}" style:type="right" style:leader-style="dotted" style:leader-text="."/>""")
                append("</style:tab-stops></style:paragraph-properties></style:style>")
            }
            append("""<style:style style:name="Contents_20_Heading" style:display-name="Contents Heading" style:family="paragraph" style:parent-style-name="Standard" style:class="index">""")
            append("""<style:paragraph-properties fo:margin-bottom="6pt"/><style:text-properties ${OdfPackage.sizeAttr("16pt")}${OdfPackage.weightAttr("bold").trimEnd()}/></style:style>""")
            for (level in 1..3) line("Contents_20_$level", "Contents $level", (level - 1) * 11f)
            line("Figure_20_Index_20_1", "Figure Index 1", 0f)
            line("Table_20_index_20_1", "Table index 1", 0f)
        }
        // An index's lines as LibreOffice's Index 1–3 (each level indented, a dotted tab to a right-aligned page) and its
        // letters' Index Separator.
        if (doc.blocks.any { it is com.therealityteam.aeropages.model.IndexBlock }) {
            val first = doc.setupAt(0)
            val width = first.widthPt - first.marginLeftPt - first.marginRightPt
            for (level in 1..3) {
                val indent = (level - 1) * 11f
                append("""<style:style style:name="Index_20_$level" style:display-name="Index $level" style:family="paragraph" style:parent-style-name="Standard" style:class="index">""")
                append("""<style:paragraph-properties fo:margin-left="${pt(indent)}" fo:margin-top="0pt" fo:margin-bottom="0pt"><style:tab-stops>""")
                append("""<style:tab-stop style:position="${pt(width - indent)}" style:type="right" style:leader-style="dotted" style:leader-text="."/>""")
                append("</style:tab-stops></style:paragraph-properties></style:style>")
            }
            append("""<style:style style:name="Index_20_Separator" style:display-name="Index Separator" style:family="paragraph" style:parent-style-name="Standard" style:class="index">""")
            append("""<style:paragraph-properties fo:margin-top="6pt" fo:margin-bottom="0pt"/><style:text-properties ${OdfPackage.weightAttr("bold").trimEnd()}/></style:style>""")
        }
        // The headings' numbers, when a document numbers them (chapter numbering).
        doc.lists[outlineList(doc)]?.let { append(OdfLists.listStyle("Outline", it, outline = true)) }
        // Word's line numbers: ODF has one setting for the whole document — the first numbered section's (its start, and a
        // restart at each section, have no ODF form).
        (listOf(doc.page) + doc.paragraphs().mapNotNull { it.format.sectionBreak }).firstNotNullOfOrNull { it.lineNumbers }?.let { n ->
            append("""<text:linenumbering-configuration text:number-lines="true" text:offset="${pt(com.therealityteam.aeropages.model.LineNumbers.distanceOf(n))}" style:num-format="1" """)
            append("""text:number-position="left" text:increment="${n.countBy.coerceAtLeast(1)}" text:restart-on-page="${n.restart == com.therealityteam.aeropages.model.LineNumberRestart.PAGE}" text:count-empty-lines="true" text:count-in-text-boxes="false"/>""")
        }
        append("</office:styles>")
        // A page layout and a master page for each of the document's pages (one per distinct
        // section page; "Standard" the first), its text columns among its layout's properties.
        val masters = masters.ifEmpty { linkedMapOf(pg.copy() to "Standard") }
        // Headers and footers: the default, the even pages' (left) and the first page's, as the document has them.
        // Each with text or pictures (a letterhead's logo alone makes one).
        fun hfOf(element: String, p: Paragraph?, slot: com.therealityteam.aeropages.model.HfSlot, shown: Boolean = true) =
            Triple(element, p, doc.hfPictures[slot].orEmpty()).takeIf { shown && (p != null || it.third.isNotEmpty()) }
        val heads = listOfNotNull(hfOf("header", doc.header, com.therealityteam.aeropages.model.HfSlot.HEADER),
            hfOf("header-left", doc.evenHeader, com.therealityteam.aeropages.model.HfSlot.EVEN_HEADER, doc.differentOddEven),
            hfOf("header-first", doc.firstHeader, com.therealityteam.aeropages.model.HfSlot.FIRST_HEADER, doc.differentFirstPage))
        val foots = listOfNotNull(hfOf("footer", doc.footer, com.therealityteam.aeropages.model.HfSlot.FOOTER),
            hfOf("footer-left", doc.evenFooter, com.therealityteam.aeropages.model.HfSlot.EVEN_FOOTER, doc.differentOddEven),
            hfOf("footer-first", doc.firstFooter, com.therealityteam.aeropages.model.HfSlot.FIRST_FOOTER, doc.differentFirstPage))
        val hf = HeaderStyles(pg, pictures)
        val hfXml = buildString { (heads + foots).forEach { (element, p, pics) -> append(hf.content(element, p, pics)) } }
        append("<office:automatic-styles>")
        append(hf.xml)
        masters.entries.forEachIndexed { k, (page, _) ->
            // With a header, the page's top margin is the header's distance from the edge and the header's
            // area reaches down to the body (LibreOffice's reading of Word's header distance); the footer alike.
            val headerArea = (page.marginTopPt - page.headerPt).takeIf { heads.isNotEmpty() && it > 0f }
            val footerArea = (page.marginBottomPt - page.footerPt).takeIf { foots.isNotEmpty() && it > 0f }
            append("""<style:page-layout style:name="pm${k + 1}"><style:page-layout-properties """)
            append("""fo:page-width="${pt(page.widthPt)}" fo:page-height="${pt(page.heightPt)}" """)
            val margins = floatArrayOf(if (headerArea != null) page.headerPt else page.marginTopPt, if (footerArea != null) page.footerPt else page.marginBottomPt,
                page.marginLeftPt, page.marginRightPt)
            append(pageBorderProps(doc.pageBorders, page, margins))
            if (page.pageNumberFormat != com.therealityteam.aeropages.model.NumFormat.DECIMAL) append("""style:num-format="${escape(OdfLists.odfFormat(page.pageNumberFormat))}" """)
            // The line grid, as LibreOffice writes Word's: its lines' pitch (no ruby above them), not shown or printed.
            page.linePitchPt?.let { pitch ->
                val lines = ((page.heightPt - page.marginTopPt - page.marginBottomPt) / pitch).toInt().coerceAtLeast(1)
                append("""style:layout-grid-mode="line" style:layout-grid-base-height="${pt(pitch)}" style:layout-grid-ruby-height="0pt" style:layout-grid-lines="$lines" """)
                append("""style:layout-grid-ruby-below="false" style:layout-grid-print="false" style:layout-grid-display="false" style:layout-grid-snap-to="true" """)
            }
            append("""style:print-orientation="${if (page.widthPt > page.heightPt) "landscape" else "portrait"}">""")
            if (page.columns > 1) {
                append("""<style:columns fo:column-count="${page.columns}" fo:column-gap="${pt(page.columnGapPt)}">""")
                // Word's line between: a hairline the columns' full height, as LibreOffice writes it.
                if (page.columnLine) append("""<style:column-sep style:style="solid" style:width="0.5pt" style:color="#000000" style:height="100%" style:vertical-align="top"/>""")
                append("</style:columns>")
            }
            append("</style:page-layout-properties>")
            fun area(element: String, h: Float?, spacing: String) {
                if (h == null) { append("<style:$element/>"); return }
                append("""<style:$element><style:header-footer-properties fo:min-height="${pt(h)}" fo:margin-left="0pt" fo:margin-right="0pt" """)
                append("""fo:$spacing="${pt((h - HEADER_LINE_PT).coerceAtLeast(0f))}" style:dynamic-spacing="true"/></style:$element>""")
            }
            area("header-style", headerArea, "margin-bottom")
            area("footer-style", footerArea, "margin-top")
            append("</style:page-layout>")
        }
        append("</office:automatic-styles>")
        append("<office:master-styles>")
        masters.entries.forEachIndexed { k, (_, name) ->
            append("""<style:master-page style:name="${escape(name)}" style:page-layout-name="pm${k + 1}">""")
            append(hfXml)
            append("</style:master-page>")
        }
        append("</office:master-styles>")
        append("</office:document-styles>")
    }

    /** Per-write context: the automatic styles the body needs (de-duplicated by property string) and the note texts. */
    private class StyleTable(val lists: Map<Int, ListDef> = emptyMap()) {
        /** Writes the tracked changes as the text is written (see OdfChanges). */
        var changes: OdfChanges.Recorder? = null
        /** Table cells' paragraphs: their table, row and grid column (for formulas). */
        val formulaCells = java.util.IdentityHashMap<com.therealityteam.aeropages.model.Paragraph, Triple<com.therealityteam.aeropages.model.Table, Int, Int>>()
        /** The list numbering the headings as the outline (0: none): the other headings say they have no number. */
        var outline = 0
        /** Drawn shapes' graphic styles and the named drawing styles they use. */
        val shapes = OdpWriter.SlideStyles("tb")
        var footnotes: List<String> = emptyList()
        var endnotes: List<String> = emptyList()
        /** Bookmarks written so far (a name's first start counts) and ended; the names whose end follows their start. */
        val bookmarksStarted = HashSet<String>()
        val bookmarksEnded = HashSet<String>()
        var bookmarksClosed: Set<String> = emptySet()
        /** The bookmark each heading the table of contents lists goes through (see Bookmarks.tocMarks). */
        var tocMarks: Map<Long, Pair<String, Boolean>> = emptyMap()
        /** The comments: those with a range (written where it starts and ends), each thread's replies, those on a paragraph (at its end), their names. */
        var commentRanged: Set<Long> = emptySet()
        var commentReplies: Map<Long, List<com.therealityteam.aeropages.model.Comment>> = emptyMap()
        var commentsOn: Map<Long, List<com.therealityteam.aeropages.model.Comment>> = emptyMap()
        var commentById: Map<Long, com.therealityteam.aeropages.model.Comment> = emptyMap()
        /** The sequences (caption labels) the text numbers, declared at the text's start. */
        val sequences = LinkedHashSet<String>()
        /** The sequences numbered by chapter: their heading level and the separator after the chapter's number. */
        val sequenceChapters = HashMap<String, Pair<Int, String>>()
        private val paragraphs = LinkedHashMap<String, String>()   // properties → name
        private val texts = LinkedHashMap<String, String>()
        private val others = LinkedHashMap<String, String>()       // name → xml
        private val clippedFrames = HashMap<String, String>()      // fo:clip → frame style name
        private var tableCount = 0

        /** The master page each section's first paragraph starts pages of (see [sectionMasters]). */
        val sectionStarts = java.util.IdentityHashMap<Paragraph, String>()
        /** The page number a section's first paragraph starts its pages from (`style:page-number`). */
        val sectionNumbers = java.util.IdentityHashMap<Paragraph, Int>()
        /** The document's pages, each its master page's name ("Standard" the first). */
        var masters = LinkedHashMap<com.therealityteam.aeropages.model.PageSetup, String>()

        /** The ODF name of [p]'s own style (one of the document's own, written by [ownStylesXml]), or null. */
        fun ownStyleName(p: Paragraph): String? =
            p.format.rawStyleId?.let { sheet[it] }?.takeIf { com.therealityteam.aeropages.model.StyleSheet.builtinOf(it) == null }?.let { odfStyleName(it.id) }

        /** What [p]'s style looks like: its own style's, else its built-in's. */
        fun lookOf(p: Paragraph): com.therealityteam.aeropages.model.ResolvedStyle =
            p.format.rawStyleId?.takeIf { sheet[it] != null }?.let { sheet.resolve(it) } ?: sheet.resolve(p.format.style)

        /** The paragraphs a page break comes before (the break's paragraph folded into the next one's style). */
        val breaksBefore: MutableSet<Paragraph> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

        fun paragraphStyle(p: Paragraph, master: String? = null): String {
            val f = p.format
            val own = ownStyleName(p)
            val parent = own ?: builtinOdf(p.format.style)
            val props = buildString {
                append("""fo:text-align="${align(f.align)}" style:justify-single-word="false" """)
                append("""fo:margin-top="${pt(f.spaceBeforePt)}" fo:margin-bottom="${pt(f.spaceAfterPt)}" """)
                // A multiple of single lines as a percentage; an exact height as a length, a least one as its own property.
                val height = f.lineHeightPt
                // Side lines stand at ODF's margin, their space as padding inside: the margin moves out by both.
                val bx = f.borders?.takeIf { !it.none }
                val lx = bx?.left?.let { it.thicknessPt + bx.spaceLeft } ?: 0f
                val rx = bx?.right?.let { it.thicknessPt + bx.spaceRight } ?: 0f
                append("""fo:margin-left="${pt(f.indentLeftPt - lx)}" """ + when {
                    height == null -> """fo:line-height="${(f.lineSpacing * 100).toInt()}%""""
                    f.lineExact -> """fo:line-height="${pt(height)}""""
                    else -> """style:line-height-at-least="${pt(height)}""""
                })
                // On one of the document's own styles, which may indent, a paragraph says its indents even when none.
                if (f.indentRightPt != 0f || own != null || rx > 0f) append(""" fo:margin-right="${pt(f.indentRightPt - rx)}"""")
                // Its borders and shading; none said outright on one of the document's own styles that has some.
                val look = if (own != null) lookOf(p) else null
                append(boxProps(bx, f.shading, clearBorders = look?.borders != null, clearShading = look?.shading != null))
                if (f.firstLineIndentPt != 0f || own != null) append(""" fo:text-indent="${pt(f.firstLineIndentPt)}"""")
                // Line and page breaks (the heading styles already keep with the next paragraph).
                f.keepWithNext?.let { append(""" fo:keep-with-next="${if (it) "always" else "auto"}"""") }
                if (f.keepTogether) append(""" fo:keep-together="always"""")
                if (f.pageBreakBefore || p in breaksBefore) append(""" fo:break-before="page"""")
                if (!f.widowControl) append(""" fo:widows="0" fo:orphans="0"""")
                if (!f.snapToGrid) append(""" style:snap-to-layout-grid="false"""")
                if (f.suppressLineNumbers) append(""" text:number-lines="false"""")
                sectionNumbers[p]?.let { append(""" style:page-number="$it"""") }
            }
            // Tab stops, from the paragraph's indent as ODF counts them by default; a drop cap after them (ODF has none in the margin).
            val tabs = (if (f.tabStops.isEmpty()) "" else tabStopsXml(f.tabStops, f.indentLeftPt)) + dropCapXml(p)
            // An empty paragraph has no span to carry its mark's look (its size makes the line's height): its style does.
            val mark = if (p.runs.all { it.text.isEmpty() }) p.runs.firstOrNull()?.format?.let { textProps(it) }.orEmpty() else ""
            val key = "$parent|$props|$tabs|$master|$mark"
            return paragraphs.getOrPut(key) {
                val name = "P${paragraphs.size + 1}"
                // A section's first paragraph starts pages of its master page.
                val masterAttr = master?.let { """ style:master-page-name="${escape(it)}"""" } ?: ""
                others[name] = """<style:style style:name="$name" style:family="paragraph" style:parent-style-name="$parent"$masterAttr>""" +
                    (if (tabs.isEmpty()) """<style:paragraph-properties $props/>""" else """<style:paragraph-properties $props>$tabs</style:paragraph-properties>""") +
                    (if (mark.isEmpty()) "" else "<style:text-properties $mark/>") +
                    "</style:style>"
                name
            }
        }

        /** The document's paragraph styles (what its headings look like). */
        var sheet: com.therealityteam.aeropages.model.StyleSheet = com.therealityteam.aeropages.model.StyleSheet.defaults()

        /** Null when the run needs no character style at all; [styleBold] / [styleItalic]: its paragraph style's, which a plain run turns off. */
        fun textStyle(f: CharacterFormat, styleBold: Boolean = false, styleItalic: Boolean = false): String? {
            val props = textProps(f, styleBold, styleItalic)
            if (props.isEmpty()) return null
            return texts.getOrPut(props) {
                val name = "T${texts.size + 1}"
                others[name] = """<style:style style:name="$name" style:family="text"><style:text-properties $props/></style:style>"""
                name
            }
        }

        /**
         * [p]'s drop cap as ODF has it (`style:drop-cap` in its paragraph properties; its letters' font a text style of
         * their own); "" without one. ODF has no drop cap in the margin: it is written dropped.
         */
        fun dropCapXml(p: Paragraph): String {
            val d = p.format.dropCap ?: return ""
            val n = com.therealityteam.aeropages.model.DropCaps.length(p)
            if (n == 0) return ""
            val font = d.fontFamily?.let { textStyle(CharacterFormat(fontFamily = it)) }?.let { """ style:style-name="$it"""" } ?: ""
            return """<style:drop-cap style:lines="${d.lines.coerceAtLeast(2)}" style:length="$n" style:distance="${pt(d.distancePt)}"$font/>"""
        }

        fun listStyle(kind: ListKind): String {
            val name = if (kind == ListKind.NUMBER) OdfLists.PLAIN_NUMBERS else OdfLists.PLAIN_BULLETS
            others.getOrPut(name) { OdfLists.listStyle(name, if (kind == ListKind.NUMBER) ListDef.NUMBERS else ListDef.BULLETS) }
            return name
        }

        /** List [id]'s style, its levels as [def] has them. */
        fun listStyle(id: Int, def: ListDef): String {
            val name = "L$id"
            others.getOrPut(name) { OdfLists.listStyle(name, def) }
            return name
        }

        /** The table styles made so far, by their properties. */
        private val tableStyleNames = HashMap<String, String>()

        /** A table's style: as wide as the text, or — in the text with a width of its own — that wide, aligned or indented. */
        fun tableStyle(t: Table? = null): String {
            val w = t?.widthPt?.takeIf { t.floating == null }
            if (t == null || w == null) {
                others.getOrPut("Ta1") { """<style:style style:name="Ta1" style:family="table"><style:table-properties style:rel-width="100%" table:align="margins"/></style:style>""" }
                return "Ta1"
            }
            val align = when (t.align) { com.therealityteam.aeropages.model.TextAlign.CENTER -> "center"; com.therealityteam.aeropages.model.TextAlign.RIGHT -> "right"; else -> "left" }
            val margin = if (align == "left" && t.indentPt != 0f) """ fo:margin-left="${pt(t.indentPt)}"""" else ""
            val props = """style:width="${pt(w)}" table:align="$align"$margin"""
            return tableStyleNames.getOrPut(props) {
                val name = "Ta${tableStyleNames.size + 2}"
                others[name] = """<style:style style:name="$name" style:family="table"><style:table-properties $props/></style:style>"""
                name
            }
        }

        /** A row's style: at least [heightPt] tall, or — [exact] — just that. */
        fun rowStyle(heightPt: Float, exact: Boolean): String {
            val name = "Tr${(heightPt * 10).toInt()}${if (exact) "x" else ""}"
            others.getOrPut(name) {
                val prop = if (exact) """style:row-height="${pt(heightPt)}"""" else """style:min-row-height="${pt(heightPt)}""""
                """<style:style style:name="$name" style:family="table-row"><style:table-row-properties $prop/></style:style>"""
            }
            return name
        }

        fun columnStyle(fraction: Float): String {
            val pctName = "Tc${(fraction * 1000).toInt()}"
            others.getOrPut(pctName) {
                """<style:style style:name="$pctName" style:family="table-column"><style:table-column-properties style:rel-column-width="${(fraction * 65535).toInt()}*"/></style:style>"""
            }
            return pctName
        }

        /** A cell's style: its shading, and a thin black border unless its table has [borders] none. */
        /** Cell styles by their properties: a cell's shade and its four borders. */
        private val cellLooks = HashMap<Pair<Long?, com.therealityteam.aeropages.model.CellBorders>, String>()

        /** A table cell's style: its shade and its borders — one `fo:border` when its four sides agree, else each side (a double one with its lines' widths). */
        fun cellStyle(shade: Long?, sides: com.therealityteam.aeropages.model.CellBorders = com.therealityteam.aeropages.model.CellBorders()): String =
            cellLooks.getOrPut(shade to sides) {
                val name = "Tcell${cellLooks.size + 1}"
                val fill = shade?.let { """ fo:background-color="${color(it)}"""" } ?: ""
                val border = buildString {
                    val all = listOf(sides.top, sides.bottom, sides.left, sides.right)
                    if (all.distinct().size == 1) {
                        append(""" fo:border="${OdfBorders.cellValueOf(sides.top)}"""")
                        sides.top?.takeIf { it.style == com.therealityteam.aeropages.model.LineStyle.DOUBLE }?.let { append(""" style:border-line-width="${OdfBorders.doubleWidths(it)}"""") }
                    } else listOf("top", "bottom", "left", "right").zip(all).forEach { (side, line) ->
                        append(""" fo:border-$side="${OdfBorders.cellValueOf(line)}"""")
                        line?.takeIf { it.style == com.therealityteam.aeropages.model.LineStyle.DOUBLE }?.let { append(""" style:border-line-width-$side="${OdfBorders.doubleWidths(it)}"""") }
                    }
                }
                others[name] = """<style:style style:name="$name" style:family="table-cell"><style:table-cell-properties$border fo:padding="0.06in"$fill/></style:style>"""
                name
            }

        fun centeredStyle(): String {
            others.getOrPut("Pimg") { """<style:style style:name="Pimg" style:family="paragraph" style:parent-style-name="Standard"><style:paragraph-properties fo:text-align="center"/></style:style>""" }
            return "Pimg"
        }

        /** A picture frame's graphic style: `fr1`, or one per crop ([clip], `fo:clip`'s `rect(…)`) and [shadow]. */
        fun frameStyle(clip: String? = null, shadow: com.therealityteam.aeropages.model.ShapeShadow? = null, mirror: String? = null, picture: Boolean = false,
                       decorative: Boolean = false): String {
            // A picture's: from "Graphics"; its decorative mark in the style, as LibreOffice writes it.
            if (picture) return clippedFrames.getOrPut("pic|$clip|$shadow|$mirror|$decorative") {
                val name = "fr${clippedFrames.size + 2}"
                others[name] = frameXml(name, (clip?.let { """ fo:clip="$it"""" } ?: "") + OdfLines.shadowAttrs(shadow) + (mirror?.let { """ style:mirror="$it"""" } ?: "") +
                    (if (decorative) """ loext:decorative="true"""" else ""), parent = "Graphics")
                name
            }
            if (clip == null && shadow == null && mirror == null) {
                others.getOrPut("fr1") { frameXml("fr1", "") }
                return "fr1"
            }
            return clippedFrames.getOrPut("$clip|$shadow|$mirror") {
                val name = "fr${clippedFrames.size + 2}"
                others[name] = frameXml(name, (clip?.let { """ fo:clip="$it"""" } ?: "") + OdfLines.shadowAttrs(shadow) + (mirror?.let { """ style:mirror="$it"""" } ?: ""))
                name
            }
        }

        private fun frameXml(name: String, extra: String, parent: String? = null) =
            """<style:style style:name="$name" style:family="graphic"${parent?.let { """ style:parent-style-name="$it"""" } ?: ""}><style:graphic-properties style:vertical-pos="middle" style:vertical-rel="text" style:wrap="none"$extra/></style:style>"""

        /**
         * A floating frame's position and wrapping as graphic properties: run through the text behind or
         * in front of it, the text above and below it (`none`) or around it (`parallel`); placed from or
         * aligned in the page, the margins' box, a margin's strip or its paragraph.
         */
        fun floatProps(pos: com.therealityteam.aeropages.model.FloatPosition): String = floatPropsOf(pos)

        /**
         * A floating picture's, text box's or table's frame style: its position and wrapping, its crop, shadow
         * and mirroring; a [textFrame]'s (a text box's, a table's) from "Frame", so LibreOffice reads it as
         * a text frame (as a drawing's text box, it would drop a table).
         */
        fun floatFrameStyle(pos: com.therealityteam.aeropages.model.FloatPosition,
                            clip: String? = null, shadow: com.therealityteam.aeropages.model.ShapeShadow? = null, mirror: String? = null,
                            textFrame: Boolean = false, picture: Boolean = false, decorative: Boolean = false): String {
            val props = floatProps(pos) + (clip?.let { """ fo:clip="$it"""" } ?: "") + OdfLines.shadowAttrs(shadow) + (mirror?.let { """ style:mirror="$it"""" } ?: "") +
                (if (decorative) """ loext:decorative="true"""" else "")
            return clippedFrames.getOrPut("float|$textFrame|$picture|$props") {
                val name = "fr${clippedFrames.size + 2}"
                val parent = if (textFrame) """ style:parent-style-name="Frame"""" else if (picture) """ style:parent-style-name="Graphics"""" else ""
                others[name] = """<style:style style:name="$name" style:family="graphic"$parent><style:graphic-properties $props/></style:style>"""
                name
            }
        }

        fun nextTableName(): String = "Table${++tableCount}"

        /** Formula objects to package: `Object N/content.xml` → its MathML. */
        val formulas = LinkedHashMap<String, ByteArray>()

        /** A formula frame's graphic style: centred on the line of text it stands in. */
        fun formulaFrameStyle(): String {
            others.getOrPut("frMath") {
                """<style:style style:name="frMath" style:family="graphic"><style:graphic-properties style:vertical-pos="middle" """ +
                    """style:vertical-rel="text" style:wrap="none" draw:ole-draw-aspect="1"/></style:style>"""
            }
            return "frMath"
        }

        fun render(): String = others.values.joinToString("")

        private fun align(a: TextAlign) = alignValue(a)
    }

    /**
     * A floating frame's position and wrapping as graphic properties: run through the text behind or
     * in front of it, the text above and below it (`none`) or around it (`parallel`); placed from or
     * aligned in the page, the margins' box, a margin's strip or its paragraph.
     */
    private fun floatPropsOf(pos: com.therealityteam.aeropages.model.FloatPosition): String {
        val wrap = when (pos.wrap) {
            com.therealityteam.aeropages.model.FloatWrap.BEHIND -> """style:wrap="run-through" style:run-through="background""""
            com.therealityteam.aeropages.model.FloatWrap.FRONT -> """style:wrap="run-through" style:run-through="foreground""""
            com.therealityteam.aeropages.model.FloatWrap.TOP_BOTTOM -> """style:wrap="none""""
            com.therealityteam.aeropages.model.FloatWrap.SQUARE -> """style:wrap="parallel""""
        }
        val hPos = when (pos.hAlign) {
            null -> "from-left"
            com.therealityteam.aeropages.model.FloatAlign.START -> "left"
            com.therealityteam.aeropages.model.FloatAlign.CENTER -> "center"
            com.therealityteam.aeropages.model.FloatAlign.END -> "right"
        }
        val hRel = when (pos.hFrom) {
            com.therealityteam.aeropages.model.FloatFrame.PAGE -> "page"
            com.therealityteam.aeropages.model.FloatFrame.MARGIN -> "page-content"
            com.therealityteam.aeropages.model.FloatFrame.LEFT_MARGIN -> "page-start-margin"
            com.therealityteam.aeropages.model.FloatFrame.RIGHT_MARGIN -> "page-end-margin"
            else -> "paragraph"
        }
        val vPos = when (pos.vAlign) {
            null -> "from-top"
            com.therealityteam.aeropages.model.FloatAlign.START -> "top"
            com.therealityteam.aeropages.model.FloatAlign.CENTER -> "middle"
            com.therealityteam.aeropages.model.FloatAlign.END -> "bottom"
        }
        val vRel = when (pos.vFrom) {
            com.therealityteam.aeropages.model.FloatFrame.PAGE, com.therealityteam.aeropages.model.FloatFrame.TOP_MARGIN,
            com.therealityteam.aeropages.model.FloatFrame.BOTTOM_MARGIN -> "page"
            com.therealityteam.aeropages.model.FloatFrame.MARGIN, com.therealityteam.aeropages.model.FloatFrame.COLUMN -> "page-content"
            else -> "paragraph"
        }
        return """$wrap style:horizontal-pos="$hPos" style:horizontal-rel="$hRel" style:vertical-pos="$vPos" style:vertical-rel="$vRel""""
    }

    /** `style:tab-stops` for [stops], their positions from [indentPt] (the paragraph's left indent). */
    private fun tabStopsXml(stops: List<com.therealityteam.aeropages.model.TabStop>, indentPt: Float): String = buildString {
        append("<style:tab-stops>")
        for (t in stops) {
            append("""<style:tab-stop style:position="${pt(t.posPt - indentPt)}"""")
            when (t.align) {
                com.therealityteam.aeropages.model.TabAlign.LEFT -> Unit
                com.therealityteam.aeropages.model.TabAlign.CENTER -> append(""" style:type="center"""")
                com.therealityteam.aeropages.model.TabAlign.RIGHT -> append(""" style:type="right"""")
                com.therealityteam.aeropages.model.TabAlign.DECIMAL -> append(""" style:type="char" style:char="."""")
            }
            when (t.leader) {
                com.therealityteam.aeropages.model.TabLeader.NONE -> Unit
                com.therealityteam.aeropages.model.TabLeader.DOT -> append(""" style:leader-style="dotted" style:leader-text="."""")
                com.therealityteam.aeropages.model.TabLeader.HYPHEN -> append(""" style:leader-style="dash" style:leader-text="-"""")
                com.therealityteam.aeropages.model.TabLeader.UNDERSCORE -> append(""" style:leader-style="solid" style:leader-text="_"""")
                com.therealityteam.aeropages.model.TabLeader.MIDDLE_DOT -> append(""" style:leader-style="dotted" style:leader-text="·"""")
            }
            append("/>")
        }
        append("</style:tab-stops>")
    }

    private fun alignValue(a: TextAlign) = when (a) {
        TextAlign.LEFT -> "start"
        TextAlign.CENTER -> "center"
        TextAlign.RIGHT -> "end"
        TextAlign.JUSTIFY -> "justify"
    }

    /** The ODF paragraph style a built-in one is written as (Normal and Quote as `Standard`). */
    private fun builtinOdf(style: ParagraphStyle): String = when (style) {
        ParagraphStyle.HEADING1 -> "Heading_20_1"
        ParagraphStyle.HEADING2 -> "Heading_20_2"
        ParagraphStyle.HEADING3 -> "Heading_20_3"
        ParagraphStyle.TITLE -> "Title"
        else -> "Standard"
    }

    /** Names this writer gives its own named and automatic styles: one of the document's own styles called so is written as `_` + its name. */
    private val RESERVED = Regex("""Standard|Heading_20_[1-3]|Title|Frame|Graphics|Outline|Contents_20_(Heading|[1-3])|Figure_20_Index_20_1|Table_20_index_20_1|[A-Z]{1,3}[0-9]+""")

    /** A style id as an ODF style name (see OdfStyles.encodedName). */
    private fun odfStyleName(id: String): String = OdfStyles.encodedName(id).let { if (RESERVED.matches(it)) "_$it" else it }

    /** The ODF name of the style [id] stands on: a built-in's, one of the document's own, else `Standard` (also where a chain would come back on itself). */
    private fun odfParentOf(sheet: com.therealityteam.aeropages.model.StyleSheet, own: String, id: String?): String {
        val d = sheet[id] ?: return ParagraphStyle.entries.firstOrNull { it.styleId.equals(id, ignoreCase = true) }?.let { builtinOdf(it) } ?: "Standard"
        com.therealityteam.aeropages.model.StyleSheet.builtinOf(d)?.let { return builtinOdf(it) }
        // A chain that comes back to the style itself stands on Standard.
        var cur: com.therealityteam.aeropages.model.StyleDef? = d
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur.id.lowercase())) {
            if (cur.id.equals(own, ignoreCase = true)) return "Standard"
            cur = sheet[cur.basedOn]
        }
        return odfStyleName(d.id)
    }

    /**
     * The document's own paragraph styles (made here, or from its file — the built-in six aside) as named styles,
     * which LibreOffice lists by their names: each on its base, saying only what it sets itself (the rest it inherits,
     * as in Word). The paragraphs' automatic styles stand on them (see [StyleTable.paragraphStyle]).
     */
    private fun ownStylesXml(sheet: com.therealityteam.aeropages.model.StyleSheet): String = buildString {
        for (d in sheet.styles.values) {
            if (com.therealityteam.aeropages.model.StyleSheet.builtinOf(d) != null) continue
            append("""<style:style style:name="${escape(odfStyleName(d.id))}" style:display-name="${escape(d.name)}" style:family="paragraph"""")
            append(""" style:parent-style-name="${odfParentOf(sheet, d.id, d.basedOn)}"""")
            d.outlineLevel?.let { append(""" style:default-outline-level="${it + 1}"""") }
            append(">")
            val para = buildString {
                d.align?.let { append(""" fo:text-align="${alignValue(it)}"""") }
                d.spaceBeforePt?.let { append(""" fo:margin-top="${pt(it)}"""") }
                d.spaceAfterPt?.let { append(""" fo:margin-bottom="${pt(it)}"""") }
                val bx = d.borders?.takeIf { !it.none }
                val lx = bx?.left?.let { it.thicknessPt + bx.spaceLeft } ?: 0f
                val rx = bx?.right?.let { it.thicknessPt + bx.spaceRight } ?: 0f
                if (d.indentLeftPt != null || lx > 0f) append(""" fo:margin-left="${pt((d.indentLeftPt ?: 0f) - lx)}"""")
                if (d.indentRightPt != null || rx > 0f) append(""" fo:margin-right="${pt((d.indentRightPt ?: 0f) - rx)}"""")
                d.firstLineIndentPt?.let { append(""" fo:text-indent="${pt(it)}"""") }
                val height = d.lineHeightPt
                when {
                    height != null && d.lineExact -> append(""" fo:line-height="${pt(height)}"""")
                    height != null -> append(""" style:line-height-at-least="${pt(height)}"""")
                    d.lineSpacing != null -> append(""" fo:line-height="${((d.lineSpacing ?: 1f) * 100).toInt()}%"""")
                }
                append(boxProps(bx, d.shading))
            }
            if (para.isNotEmpty()) append("<style:paragraph-properties$para/>")
            val text = buildString {
                d.sizePt?.let { append(" ").append(OdfPackage.sizeAttr(pt(it)).trim()) }
                d.bold?.let { append(" ").append(OdfPackage.weightAttr(if (it) "bold" else "normal").trim()) }
                d.italic?.let { append(" ").append(OdfPackage.italicAttr(if (it) "italic" else "normal").trim()) }
                d.color?.let { append(""" fo:color="${color(it)}"""") }
                d.fontFamily?.let { append(""" style:font-name="${escape(it)}" fo:font-family="${escape(it)}"""") }
            }
            if (text.isNotEmpty()) append("<style:text-properties$text/>")
            append("</style:style>")
        }
    }

    /**
     * Paragraph borders and shading as ODF's paragraph properties: each side's line (`fo:border-*`, none where it has
     * none) with its space as `fo:padding-*`, a double line's widths, the fill; [clearBorders] / [clearShading]: none said
     * outright (a paragraph turning off its style's). Word's between line has no ODF form (LibreOffice joins the boxes).
     */
    private fun boxProps(b: com.therealityteam.aeropages.model.ParagraphBorders?, shading: Long?, clearBorders: Boolean = false, clearShading: Boolean = false): String = buildString {
        if (b != null) {
            for ((side, line, space) in listOf(Triple("top", b.top, b.spaceTop), Triple("bottom", b.bottom, b.spaceBottom), Triple("left", b.left, b.spaceLeft), Triple("right", b.right, b.spaceRight))) {
                append(""" fo:border-$side="${OdfBorders.cellValueOf(line)}" fo:padding-$side="${pt(if (line != null) space else 0f)}"""")
                if (line?.style == com.therealityteam.aeropages.model.LineStyle.DOUBLE) append(""" style:border-line-width-$side="${OdfBorders.doubleWidths(line)}"""")
            }
        } else if (clearBorders) append(""" fo:border="none" fo:padding="0pt"""")
        if (shading != null) append(""" fo:background-color="${color(shading)}"""") else if (clearShading) append(""" fo:background-color="transparent"""")
    }

    /** A text style's properties for [f] (empty when it sets none); [styleBold] / [styleItalic]: its paragraph style's, which a plain run turns off. */
    private fun textProps(f: CharacterFormat, styleBold: Boolean = false, styleItalic: Boolean = false): String = buildString {
        if (f.bold) append(OdfPackage.weightAttr("bold"))
        else if (styleBold) append(OdfPackage.weightAttr("normal"))
        if (f.italic) append(OdfPackage.italicAttr("italic"))
        else if (styleItalic) append(OdfPackage.italicAttr("normal"))
        if (f.underline) append("""style:text-underline-style="solid" style:text-underline-width="auto" style:text-underline-color="font-color" """)
        if (f.strikethrough) append("""style:text-line-through-style="solid" """)
        if (f.superscript) append("""style:text-position="super 58%" """)
        if (f.subscript) append("""style:text-position="sub 58%" """)
        if (f.smallCaps) append("""fo:font-variant="small-caps" """)
        f.fontSizePt?.let { append(OdfPackage.sizeAttr(pt(it))) }
        f.fontFamily?.let { append("""style:font-name="${escape(it)}" fo:font-family="${escape(it)}" """) }
        f.color?.let { append("""fo:color="${color(it)}" """) }
        f.highlight?.let { append("""fo:background-color="${color(it)}" """) }
        f.letterSpacingPt?.let { append("""fo:letter-spacing="${pt(it)}" """) }
    }.trim()

    /**
     * The headers and footers of styles.xml's master pages, with the automatic styles their paragraphs
     * and runs use there (MP…, MT…): each a paragraph aligned as it is, with tab stops at the middle
     * and the right edge of [page]'s text (see HeaderFooters), its lines as line breaks and its page
     * number and count as fields.
     */
    private class HeaderStyles(private val page: com.therealityteam.aeropages.model.PageSetup, private val pictures: LinkedHashMap<String, ByteArray>) {
        private val paragraphs = LinkedHashMap<TextAlign, String>()
        private val texts = LinkedHashMap<String, String>()
        private val frames = LinkedHashMap<String, String>()   // properties → style name
        val xml = StringBuilder()

        /**
         * A header or footer ([element]) holding [p] (none: an empty line) and its pictures [pics]: its lines, each
         * a paragraph (a line of pictures alone aligned as they were); a picture standing in its line a frame in
         * that line's paragraph (`as-char`), a floating one a frame anchored to the first where it stands on
         * the page (their styles among the page's automatic styles).
         */
        fun content(element: String, p: Paragraph?, pics: List<ImageBlock> = emptyList()): String = buildString {
            val width = (page.widthPt - page.marginLeftPt - page.marginRightPt).coerceAtLeast(36f)
            val align = p?.format?.align ?: TextAlign.LEFT
            fun paragraphStyle(a: TextAlign) = paragraphs.getOrPut(a) {
                val name = "MP${paragraphs.size + 1}"
                xml.append("""<style:style style:name="$name" style:family="paragraph" style:parent-style-name="Standard">""")
                xml.append("""<style:paragraph-properties fo:text-align="${alignValue(a)}" fo:margin-top="0pt" fo:margin-bottom="0pt">""")
                xml.append("""<style:tab-stops><style:tab-stop style:position="${pt(width / 2f)}" style:type="center"/><style:tab-stop style:position="${pt(width)}" style:type="right"/></style:tab-stops>""")
                xml.append("</style:paragraph-properties></style:style>")
                name
            }
            fun frameStyle(props: String) = frames.getOrPut(props) {
                val n = "MFr${frames.size + 1}"
                xml.append("""<style:style style:name="$n" style:family="graphic" style:parent-style-name="Graphics"><style:graphic-properties $props/></style:style>""")
                n
            }
            fun picture(img: ImageBlock, place: (String) -> String) {
                val name = "Pictures/${element}_image${pictures.size + 1}.${OdfPackage.imageExt(img.mime)}"
                pictures[name] = img.data
                val mirror = when { img.flipH && img.flipV -> " style:mirror=\"horizontal vertical\""; img.flipH -> " style:mirror=\"horizontal\""; img.flipV -> " style:mirror=\"vertical\""; else -> "" }
                val look = (com.therealityteam.aeropages.format.PictureCrop.foClip(img, img.data)?.let { " fo:clip=\"$it\"" } ?: "") + mirror
                append(place(look))
                append("""svg:width="${pt(img.widthPt)}" svg:height="${pt(img.heightPt)}" draw:z-index="0">""")
                append("""<draw:image xlink:href="$name" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame>""")
            }
            val lines = com.therealityteam.aeropages.model.HeaderFooters.runLines(p?.runs.orEmpty())
            val count = maxOf(lines.size, (pics.mapNotNull { it.floating?.line }.maxOrNull() ?: -1) + 1)
            append("<style:$element>")
            for (li in 0 until count) {
                val runs = lines.getOrElse(li) { emptyList() }
                val inLine = pics.filter { it.floating?.line == li }
                append("<text:p text:style-name=\"${paragraphStyle(com.therealityteam.aeropages.model.HeaderFooters.lineAlign(runs, inLine, align))}\">")
                if (li == 0) pics.filter { it.floating != null && it.floating?.line == null }.forEach { img ->
                    val pos = inOdfFrames(img.floating!!, page, img.heightPt)
                    picture(img) { look ->
                        """<draw:frame draw:style-name="${frameStyle(floatPropsOf(pos) + look)}" text:anchor-type="paragraph" svg:x="${pt(pos.x)}" svg:y="${pt(pos.y)}" """
                    }
                }
                inLine.forEach { img ->
                    picture(img) { look ->
                        """<draw:frame draw:style-name="${frameStyle("""style:vertical-pos="top" style:vertical-rel="baseline"""" + look)}" text:anchor-type="as-char" """
                    }
                }
                writeRuns(runs)
                append("</text:p>")
            }
            append("</style:$element>")
        }

        /** A header's line's runs: their looks as automatic text styles, the page number and count as fields. */
        private fun StringBuilder.writeRuns(runs: List<com.therealityteam.aeropages.model.TextRun>) {
            for (run in runs) {
                val props = textProps(run.format)
                val ts = if (props.isEmpty()) null else texts.getOrPut(props) {
                    val name = "MT${texts.size + 1}"
                    xml.append("""<style:style style:name="$name" style:family="text"><style:text-properties $props/></style:style>""")
                    name
                }
                if (ts != null) append("""<text:span text:style-name="$ts">""")
                var rest = run.text
                while (rest.isNotEmpty()) {
                    val page = rest.indexOf(com.therealityteam.aeropages.model.HeaderFooters.PAGE)
                    val pages = rest.indexOf(com.therealityteam.aeropages.model.HeaderFooters.PAGES)
                    val at = listOf(page, pages).filter { it >= 0 }.minOrNull() ?: rest.length
                    rest.substring(0, at).split('\n').forEachIndexed { i, line -> if (i > 0) append("<text:line-break/>"); append(spacePreserved(line)) }
                    if (at == rest.length) break
                    if (at == page) { append("""<text:page-number text:select-page="current">1</text:page-number>"""); rest = rest.substring(at + com.therealityteam.aeropages.model.HeaderFooters.PAGE.length) }
                    else { append("<text:page-count>1</text:page-count>"); rest = rest.substring(at + com.therealityteam.aeropages.model.HeaderFooters.PAGES.length) }
                }
                if (ts != null) append("</text:span>")
            }
        }
    }
}
