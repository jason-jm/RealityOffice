package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TableCell
import com.therealityteam.aeropages.model.TableRow
import com.therealityteam.aeropages.model.StyleSheet
import com.therealityteam.aeropages.model.TextRun

/**
 * OpenDocument Text (.odt) reader, the counterpart of [OdtWriter].
 *
 * Reads the body in document order — headings (`text:h`, with their outline level), paragraphs,
 * lists, tables and pictures — resolving each run's formatting through the package's style tables
 * ([OdfStyles]), so bold/italic/size/colour/links survive. Page size, margins and the
 * header/footer come from the master page in `styles.xml`.
 */
object OdtReader {

    fun read(bytes: ByteArray): Document {
        val files = OfficeXml.unzip(bytes)
        val content = OdfNode.parse(files["content.xml"]) ?: return Document.blank()
        val stylesPart = OdfNode.parse(files["styles.xml"])
        val styles = OdfStyles.of(content, stylesPart)
        val doc = Document()
        doc.blocks.clear()
        readStyleSheet(styles, doc)
        styles.defaultTabPt?.let { doc.defaultTabPt = it }
        // LibreOffice counts tab stops from the paragraph's indent unless the document says otherwise (Word's files converted).
        tabsFromIndent.set(files["settings.xml"]?.let { s -> Regex("""config:name="TabsRelativeToIndent"[^>]*>\s*(true|false)""").find(String(s, Charsets.UTF_8))?.groupValues?.get(1) != "false" } ?: true)

        val body = content.child("body")?.child("text")
        val track = SectionTrack()
        listTrack.set(ListTrack())
        annotations.set(HashMap())
        indexRanges.get().clear()
        // The sequences numbered by chapter: the level of the heading whose number they show and the separator after it.
        sequenceDecls.set(body?.child("sequence-decls")?.children?.filter { it.name == "sequence-decl" }?.mapNotNull { d ->
            val level = d.attr("display-outline-level")?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            d.attr("name")?.let { it.lowercase() to (level to (d.attr("separation-character") ?: ".")) }
        }?.toMap().orEmpty())
        chapterFields.set(false)
        val tracked = Changes(OdfChanges.regions(body?.child("tracked-changes")))
        // Track Changes on: the element there and not saying it is off (ODF's default is on).
        body?.child("tracked-changes")?.let { doc.trackRevisions = it.attr("track-changes") != "false" }
        // LibreOffice's protected changes (Word's Lock Tracking): a RedlineProtectionKey in settings.xml.
        files["settings.xml"]?.let { s -> Regex("""config:name="RedlineProtectionKey"[^>]*>([^<]+)<""").find(String(s, Charsets.UTF_8))?.groupValues?.get(1)?.trim() }
            ?.takeIf { it.isNotEmpty() }?.let { key ->
                doc.trackLock = if (key == com.therealityteam.aeropages.model.NO_KEY) emptyMap() else mapOf(com.therealityteam.aeropages.model.ODF_TRACK_KEY to key)
            }
        changes.set(tracked)
        try { body?.let { readBlocks(it, styles, files, doc, track) } } finally { listTrack.remove(); annotations.remove(); sequenceDecls.remove(); changes.remove() }
        // A deleted paragraph mark read inside the paragraph it joins: the paragraphs it was.
        OdfChanges.splitAll(doc, tracked.breaks)
        if (chapterFields.get() == true) com.therealityteam.aeropages.model.Fields.update(doc, kinds = setOf("STYLEREF"))
        chapterFields.remove()
        // A reply stands where its thread does; a comment no paragraph took (one in a header) goes on the first.
        val first = doc.locations().firstOrNull()?.paragraph?.id
        doc.comments.removeAll { it.targetId == PENDING_COMMENT && first == null }
        doc.comments.forEach { c -> if (c.targetId == PENDING_COMMENT) c.targetId = first!! }
        doc.comments.forEach { c -> c.parentId?.let { pid -> doc.comments.firstOrNull { it.id == pid }?.let { c.targetId = it.targetId } } }
        if (doc.blocks.isEmpty()) doc.blocks.add(ParagraphBlock(Paragraph.of("")))

        readPage(stylesPart, styles, doc, files)
        readSections(stylesPart, doc, track)
        // Line numbers: ODF's one setting is every section's.
        styles.lineNumbers?.let { n -> doc.page.lineNumbers = n; doc.paragraphs().forEach { it.format.sectionBreak?.lineNumbers = n } }
        tabsFromIndent.remove()
        doc.props = com.therealityteam.aeropages.format.OdfMeta.read(files["meta.xml"])
        return doc
    }

    /**
     * Where the document's sections begin: the blocks whose style starts a page of another master
     * page (`style:master-page-name`, LibreOffice's page style change), and the text columns of the
     * `text:section`s blocks sit in.
     */
    private class SectionTrack {
        val starts = java.util.IdentityHashMap<com.therealityteam.aeropages.model.Block, String>()
        val columns = java.util.IdentityHashMap<com.therealityteam.aeropages.model.Block, Int>()
        /** The page number a section's first block starts its pages from. */
        val numbers = java.util.IdentityHashMap<com.therealityteam.aeropages.model.Block, Int>()
    }

    /** Each master page's page: its page layout's size, margins and text columns. */
    private fun masterSetups(stylesPart: OdfNode?): Map<String, com.therealityteam.aeropages.model.PageSetup> {
        val root = stylesPart ?: return emptyMap()
        val layouts = root.descendants("page-layout").associateBy { it.attr("name") }
        return root.descendants("master-page").mapNotNull { m ->
            val name = m.attr("name") ?: return@mapNotNull null
            val layout = layouts[m.attr("page-layout-name")] ?: return@mapNotNull null
            name to (setupOf(layout, m) ?: return@mapNotNull null)
        }.toMap()
    }

    /**
     * The page a master page lays out: its layout's size, margins and columns — where it has a header,
     * the page's top margin is the header's distance from the edge and the body starts below the header
     * area (its least height, or the header's lines and the spacing under them, as LibreOffice lays it
     * out, the spacing given up first with dynamic spacing); a footer likewise at the foot.
     */
    private fun setupOf(layout: OdfNode, master: OdfNode): com.therealityteam.aeropages.model.PageSetup? {
        val props = layout.child("page-layout-properties") ?: return null
        val pg = com.therealityteam.aeropages.model.PageSetup()
        OdfValue.twipsPt(props.attr("page-width"))?.let { pg.widthPt = it }
        OdfValue.twipsPt(props.attr("page-height"))?.let { pg.heightPt = it }
        OdfValue.twipsPt(props.attr("margin-top"))?.let { pg.marginTopPt = it }
        OdfValue.twipsPt(props.attr("margin-bottom"))?.let { pg.marginBottomPt = it }
        OdfValue.twipsPt(props.attr("margin-left"))?.let { pg.marginLeftPt = it }
        OdfValue.twipsPt(props.attr("margin-right"))?.let { pg.marginRightPt = it }
        // A side with a border: ODF's margin reaches the border, the text stands its line and padding further in.
        fun inset(side: String): Float = OdfBorders.cellLineOf(props.attr("border-$side") ?: props.attr("border") ?: "none")
            ?.let { it.thicknessPt + (OdfValue.twipsPt(props.attr("padding-$side") ?: props.attr("padding")) ?: 0f) } ?: 0f
        pg.marginTopPt += inset("top"); pg.marginBottomPt += inset("bottom"); pg.marginLeftPt += inset("left"); pg.marginRightPt += inset("right")
        props.child("columns")?.let { c ->
            c.attr("column-count")?.toIntOrNull()?.let { pg.columns = it.coerceIn(1, com.therealityteam.aeropages.model.PageMetrics.MAX_COLUMNS) }
            // The gap: fo:column-gap, else (LibreOffice lists each column) the first column's right space and the next's left.
            val listed = c.children("column")
            pg.columnGapPt = OdfValue.twipsPt(c.attr("column-gap"))
                ?: listed.firstOrNull()?.let { (OdfValue.twipsPt(it.attr("end-indent")) ?: 0f) + (listed.getOrNull(1)?.let { n -> OdfValue.twipsPt(n.attr("start-indent")) } ?: 0f) }
                ?: 0f
            pg.columnLine = c.child("column-sep")?.let { sep -> sep.attr("style") != "none" && (OdfValue.twipsPt(sep.attr("width")) ?: 1f) > 0f } == true
        }
        props.attr("num-format")?.let { pg.pageNumberFormat = OdfLists.formatOf(it).takeIf { f -> f.numbered } ?: com.therealityteam.aeropages.model.NumFormat.DECIMAL }
        // A grid of lines (lines, or lines and characters): its pitch is a line's base height and the ruby above it.
        if (props.attr("layout-grid-mode") == "line" || props.attr("layout-grid-mode") == "both") {
            val base = OdfValue.twipsPt(props.attr("layout-grid-base-height")) ?: 0f
            val ruby = OdfValue.twipsPt(props.attr("layout-grid-ruby-height")) ?: 0f
            if (base + ruby > 0f) pg.linePitchPt = base + ruby
        }
        fun area(content: OdfNode?, style: String, spacingAttr: String): Float? {
            content ?: return null
            val p = layout.child(style)?.child("header-footer-properties")
            val least = OdfValue.twipsPt(p?.attr("min-height")) ?: 0f
            val spacing = OdfValue.twipsPt(p?.attr(spacingAttr)) ?: 0f
            // Word's way (dynamic spacing, as LibreOffice converts Word's and this app writes): the body starts the
            // area's least height down, and lower only where the header reaches past it (the layout's room for it).
            if (p?.attr("dynamic-spacing") == "true") return least
            val lines = (content.descendants("p").size + content.descendants("h").size + content.descendants("line-break").size).coerceAtLeast(1)
            return maxOf(least, lines * HEADER_LINE_PT + spacing)
        }
        area(master.child("header"), "header-style", "margin-bottom")?.let { pg.headerPt = pg.marginTopPt; pg.marginTopPt += it }
        area(master.child("footer"), "footer-style", "margin-top")?.let { pg.footerPt = pg.marginBottomPt; pg.marginBottomPt += it }
        return pg
    }

    /** Whether this read's tab stops count from the paragraph's indent (LibreOffice's `TabsRelativeToIndent`, true unless set). */
    private val tabsFromIndent = ThreadLocal<Boolean?>()
    /** The comments read so far by their `office:name` (a range's end and a reply name them). */
    private val annotations = ThreadLocal<HashMap<String, com.therealityteam.aeropages.model.Comment>?>()
    /** An index mark's range open (`text:alphabetical-index-mark-start`, by its id): its element, the runs it is in, where it starts. */
    private val indexRanges = ThreadLocal.withInitial { HashMap<String, Triple<OdfNode, List<TextRun>, Int>>() }
    /** The sequences numbered by chapter (by lower-case name): the heading level and the separator. */
    private val sequenceDecls = ThreadLocal<Map<String, Pair<Int, String>>?>()
    /** Whether a sequence numbered by chapter was read (its chapter numbers are worked out after). */
    private val chapterFields = ThreadLocal<Boolean?>()

    /**
     * The text's tracked changes as they are read: its changed regions by id, those open where the text is (an insertion
     * or a format change between its start and end, which may span paragraphs), and the deleted paragraph marks met
     * (breaks, with the format of the paragraph each ended), the paragraphs they join split once the text is read.
     */
    private class Changes(val regions: Map<String, OdfChanges.Region>) {
        val open = LinkedHashMap<String, OdfChanges.Region>()
        val breaks = java.util.IdentityHashMap<TextRun, ParagraphFormat>()
        /** The change the text is in now: the one opened last. */
        val current: OdfChanges.Region? get() = open.values.lastOrNull()
    }
    private val changes = ThreadLocal<Changes?>()
    /** A comment's paragraph before the paragraph it is in has been made. */
    private const val PENDING_COMMENT = Long.MIN_VALUE

    /** The lists met so far: by `xml:id` and the latest of each list style (for lists continuing one), and the headings' list. */
    private class ListTrack { val byXmlId = HashMap<String, Int>(); val lastOfStyle = HashMap<String, Int>(); var outline = 0 }
    private val listTrack = ThreadLocal<ListTrack?>()

    /**
     * A `text:list`'s paragraphs, nested lists one level deeper, each passed to [add] with its node: the
     * first paragraph of each item is the item (with its list, level and kind), the rest of the item and
     * a `text:list-header` are paragraphs with no number. [outer]: the enclosing list's style and id.
     */
    private fun readList(
        node: OdfNode, styles: OdfStyles, doc: Document, files: Map<String, ByteArray>, level: Int,
        outer: Pair<String?, Int>?, add: (OdfNode, Paragraph) -> Unit,
    ) {
        val styleName = node.attr("style-name") ?: outer?.first
        val listId = outer?.second ?: listIdOf(node, styleName, styles, doc)
        val def = doc.lists[listId]
        for (item in node.children) {
            if (item.name != "list-item" && item.name != "list-header") continue
            var first = item.name == "list-item"
            for (c in item.children) when (c.name) {
                "p", "h" -> {
                    val para = paragraph(c, styles, null, doc, files)
                    if (first && def?.level(level)?.blank != true) {
                        val f = para.format
                        f.listLevel = level.coerceIn(0, 8)
                        f.listId = listId
                        f.listKind = when {
                            def != null -> if (def.level(level).format == com.therealityteam.aeropages.model.NumFormat.BULLET) ListKind.BULLET else ListKind.NUMBER
                            else -> styles.listKind(styleName) ?: ListKind.BULLET
                        }
                        first = false
                    }
                    add(c, para)
                }
                "list" -> { readList(c, styles, doc, files, level + 1, styleName to listId, add); first = false }
            }
        }
    }

    /** The app's list a top-level `text:list` belongs to: the one it continues, else a new one (0: plain bullets or numbers). */
    private fun listIdOf(node: OdfNode, styleName: String?, styles: OdfStyles, doc: Document): Int {
        val track = listTrack.get() ?: ListTrack()
        val def = styles.listDef(styleName)
        if (def == null || styleName == OdfLists.PLAIN_BULLETS || styleName == OdfLists.PLAIN_NUMBERS) return 0
        val id = node.attr("continue-list")?.let { track.byXmlId[it] }
            ?: (if (node.attr("continue-numbering") == "true") track.lastOfStyle[styleName] else null)
            ?: doc.newListId().also { doc.lists[it] = def }
        node.attr("id")?.let { track.byXmlId[it] = id }
        styleName?.let { track.lastOfStyle[it] = id }
        return id
    }

    /** A heading numbered by the outline style (LibreOffice's chapter numbering): its number, at its outline level. */
    private fun outlineNumber(node: OdfNode, para: Paragraph, styles: OdfStyles, doc: Document) {
        val outline = styles.outline ?: return
        if (node.attr("is-list-header") == "true") return
        val level = (headingLevel(node, styles) - 1).coerceIn(0, 8)
        if (!outline.level(level).format.numbered) return
        val track = listTrack.get() ?: return
        if (track.outline == 0) track.outline = doc.newListId().also { doc.lists[it] = outline }
        para.format.listKind = ListKind.NUMBER
        para.format.listId = track.outline
        para.format.listLevel = level
    }

    /** The paragraphs of a cell, a text box or a shape: its own and its lists' (each with its node). */
    private fun paragraphsOf(container: OdfNode, styles: OdfStyles, doc: Document, files: Map<String, ByteArray>): List<Pair<OdfNode, Paragraph>> {
        val out = ArrayList<Pair<OdfNode, Paragraph>>()
        for (c in container.children) when (c.name) {
            "p", "h" -> out.add(c to paragraph(c, styles, null, doc, files))
            "list" -> readList(c, styles, doc, files, 0, null) { n, p -> out.add(n to p) }
        }
        return out
    }

    /** A header line's height when a page's layout is worked out (12 pt text, single spaced). */
    private const val HEADER_LINE_PT = 14.4f

    /**
     * The document's sections as Word keeps them: a section of pages of one master page ends at the
     * paragraph before a block starting pages of a master (its break carrying the page it ends — and
     * the columns its text sections give it), the last one's page the document's.
     */
    private fun readSections(stylesPart: OdfNode?, doc: Document, track: SectionTrack) {
        val masters = masterSetups(stylesPart)
        if (masters.isEmpty()) return
        var current = "Standard".takeIf { it in masters } ?: masters.keys.first()
        var columns = 1
        var lastPara: Paragraph? = null
        var number: Int? = null
        fun pageOf(name: String) = (masters[name] ?: masters[current] ?: com.therealityteam.aeropages.model.PageSetup()).copy().also {
            if (columns > it.columns) it.columns = columns.coerceAtMost(3)
            it.pageNumberStart = number
        }
        for (block in doc.blocks) {
            // A block naming a master page starts new pages of it — another section, even of the same page.
            val start = track.starts[block]?.takeIf { it in masters }
            if (start != null) {
                if (lastPara != null) { lastPara.format.sectionBreak = pageOf(current); columns = 1 }
                current = start
                number = track.numbers[block]
            }
            track.columns[block]?.let { if (it > columns) columns = it }
            if (block is ParagraphBlock) lastPara = block.paragraph
        }
        doc.page.assignFrom(pageOf(current))
    }

    /** The app's own default face: a document saying it says nothing more than the app's default. */
    private const val APP_FONT = "Calibri"

    /** The ODF paragraph styles the app's built-in ones stand for, as LibreOffice (and the app) name them. */
    private val BUILT_IN = listOf(
        ParagraphStyle.HEADING1 to "Heading_20_1", ParagraphStyle.HEADING2 to "Heading_20_2",
        ParagraphStyle.HEADING3 to "Heading_20_3", ParagraphStyle.TITLE to "Title",
    )

    /**
     * The document's own text defaults and styles: its paragraphs' default face and size (`Standard`,
     * else the paragraph `style:default-style` — LibreOffice's Liberation Serif 12 pt), and its
     * Heading 1–3 and Title as it defines them (sizes relative to their parents worked out), so its
     * text shows in its own faces and new headings look like its own.
     */
    private fun readStyleSheet(styles: OdfStyles, doc: Document) {
        val standard = styles.resolvedParagraph("Standard")?.text
        val base = styles.defaultParagraphText
        (standard?.fontFamily ?: base?.fontFamily)?.let { doc.styles.defaultFontFamily = it.takeUnless { f -> f == APP_FONT } }
        (standard?.fontSizePt ?: base?.fontSizePt)?.let { doc.styles.defaultSizePt = it }
        for ((style, name) in BUILT_IN) {
            val p = styles.resolvedParagraph(name) ?: continue
            val def = doc.styles[doc.styles.idOf(style)] ?: StyleSheet.builtin(style).also { doc.styles.put(it) }
            styles.resolvedSizePt(name, doc.styles.defaultSizePt)?.let { def.sizePt = it }
            p.text.fontFamily?.let { def.fontFamily = it.takeUnless { f -> f == APP_FONT } }
            if (p.text.bold) def.bold = true
            if (p.text.italic) def.italic = true
            p.text.color?.let { def.color = it }
            p.align?.let { def.align = it }
        }
        // The styles someone made (no class: LibreOffice's own pool styles have one) are the document's own, used or not;
        // the pool's are taken in as paragraphs use them (see ownStyleId).
        for ((name, named) in styles.namedParagraphs) if (named.second == null) ownStyleId(name, styles, doc)
    }

    /** The ODF names of the app's built-in styles, as LibreOffice (and the app) name them. */
    private fun builtinNamed(name: String): ParagraphStyle? = when (name) {
        "Standard" -> ParagraphStyle.NORMAL
        else -> BUILT_IN.firstOrNull { it.second == name }?.first
    }

    /**
     * The style id the document's named style [name] has (`office:styles`), read into its style sheet with the
     * styles it derives from when first asked for: the built-ins' ids for Standard, Heading 1–3 and Title; null for
     * an automatic style, or an index's (its lines are regenerated). Only what the style itself says is kept — the
     * rest it inherits, as in Word — its id its shown name without spaces (`Text_20_body` → "Textbody", as
     * LibreOffice names it in a docx).
     */
    private fun ownStyleId(name: String, styles: OdfStyles, doc: Document, depth: Int = 0): String? {
        builtinNamed(name)?.let { return doc.styles.idOf(it) }
        val (display, cls) = styles.namedParagraphs[name] ?: return null
        if (cls == "index" || depth > 8) return null
        val shown = display?.takeIf { it.isNotBlank() } ?: OdfStyles.decodedName(name)
        val id = OdfStyles.decodedName(name).filterNot { it.isWhitespace() }.ifEmpty { return null }
        doc.styles[id]?.let { return it.id }
        val p = styles.paragraph(name) ?: return null
        val def = com.therealityteam.aeropages.model.StyleDef(id, shown)
        doc.styles.put(def)   // before its base: a chain that comes back here ends
        def.basedOn = p.parent?.let { ownStyleId(it, styles, doc, depth + 1) }
        val fallback = doc.styles.defaultSizePt
        val size = styles.resolvedSizePt(name, fallback)
        val baseSize = p.parent?.let { styles.resolvedSizePt(it, fallback) } ?: fallback
        if (size != null && size != baseSize) def.sizePt = size
        p.text.fontFamily?.takeUnless { it == APP_FONT }?.let { def.fontFamily = it }
        if (p.text.bold) def.bold = true
        if (p.text.italic) def.italic = true
        p.text.color?.let { def.color = it }
        def.align = p.align
        def.spaceBeforePt = p.spaceBeforePt
        def.spaceAfterPt = p.spaceAfterPt
        def.indentLeftPt = p.indentLeftPt
        p.firstLineIndent?.let { def.firstLineIndentPt = it.pt(size ?: fallback) }
        p.indentRight?.let { def.indentRightPt = it.pt(size ?: fallback) }
        // Its borders and shading (the indents the text's, as above).
        p.borders?.takeIf { !it.none }?.let { b ->
            def.borders = b
            if (p.boxLeft > 0f) def.indentLeftPt = (p.indentLeftPt ?: 0f) + p.boxLeft
            if (p.boxRight > 0f) def.indentRightPt = (def.indentRightPt ?: 0f) + p.boxRight
        }
        p.background?.takeIf { (it ushr 24) != 0L }?.let { def.shading = it }
        if (p.lineHeightPt != null) { def.lineHeightPt = p.lineHeightPt; def.lineExact = p.lineExact } else def.lineSpacing = p.lineSpacing
        p.outlineLevel?.let { def.outlineLevel = (it - 1).coerceAtLeast(0) }
        return id
    }

    /** The document's own style a paragraph styled [styleName] has (its nearest named style, unless a built-in's), or null. */
    private fun ownStyleOf(styleName: String?, styles: OdfStyles, doc: Document): String? {
        val named = styles.ancestry(styleName).firstOrNull { it in styles.namedParagraphs || builtinNamed(it) != null } ?: return null
        if (builtinNamed(named) != null) return null
        return ownStyleId(named, styles, doc)?.takeIf { id -> doc.styles[id]?.let { StyleSheet.builtinOf(it) } == null }
    }

    /** ODF's drawn shapes that stand in a paragraph (group members count one by one). */
    private val DRAWN_SHAPES = OdpReader.DRAWN_ELEMENTS

    /** The frames anchored in [node] itself, in document order — not those inside another frame. */
    private fun framesOf(node: OdfNode): List<OdfNode> {
        val out = ArrayList<OdfNode>()
        fun walk(n: OdfNode) { for (c in n.children) if (c.name == "frame") out.add(c) else walk(c) }
        walk(node)
        return out
    }

    /** The drawn shapes anchored in [node], in document order. */
    private fun drawnShapes(node: OdfNode): List<OdfNode> {
        val out = ArrayList<OdfNode>()
        fun walk(n: OdfNode) { for (c in n.children) if (c.name in DRAWN_SHAPES) out.add(c) else if (c.name != "frame") walk(c) }
        walk(node)
        return out
    }

    private fun readBlocks(container: OdfNode, styles: OdfStyles, files: Map<String, ByteArray>, doc: Document, track: SectionTrack = SectionTrack(), columns: Int = 1) {
        for (node in container.children) {
            val before = doc.blocks.size
            readBlock(node, styles, files, doc, track, columns)
            if (doc.blocks.size > before) {
                // A block whose style starts another master page's pages begins a section; a text section's columns are its blocks'.
                if (node.name == "h" || node.name == "p" || node.name == "table") styles.masterPageOf(node.attr("style-name"))?.let {
                    track.starts[doc.blocks[before]] = it
                    styles.pageNumberOf(node.attr("style-name"))?.let { n -> track.numbers[doc.blocks[before]] = n }
                }
                if (columns > 1) for (i in before until doc.blocks.size) track.columns.putIfAbsent(doc.blocks[i], columns)
            }
        }
    }

    private fun readBlock(node: OdfNode, styles: OdfStyles, files: Map<String, ByteArray>, doc: Document, track: SectionTrack, columns: Int) {
            when (node.name) {
                "h" -> doc.blocks.add(ParagraphBlock(paragraph(node, styles, headingLevel(node, styles), doc, files).also { outlineNumber(node, it, styles, doc) }))
                "p" -> {
                    // Pictures: frames showing an image — not a formula's preview (LibreOffice keeps one beside the formula),
                    // nor one in a floating table's cell (the table shows it).
                    val inTables = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<OdfNode, Boolean>())
                    framesOf(node).filter { f -> f.child("text-box")?.children("table")?.isNotEmpty() == true }.forEach { inTables.addAll(it.descendants("frame")) }
                    val frames = node.descendants("frame").filter { it.child("image") != null && !readObject(it, files) && it !in inTables }
                    val boxes = framesOf(node).filter { it.child("text-box") != null }
                    val shapes = drawnShapes(node)
                    val para = paragraph(node, styles, null, doc, files)
                    // Charts: blocks of their own after the paragraph, at their frames' size.
                    val charts = node.descendants("frame").mapNotNull { f -> chartOf(f, files)?.let { f to it } }
                    // A formula alone in its paragraph is displayed: a block of its own, aligned as the paragraph was.
                    val formulas = node.descendants("frame").mapNotNull { formulaOf(it, files) }
                    if (formulas.isNotEmpty() && para.runs.all { it.format.math != null || it.text.isBlank() }) {
                        val size = para.runs.firstNotNullOfOrNull { it.format.fontSizePt } ?: com.therealityteam.aeropages.model.EquationBlock.DEFAULT_SIZE
                        formulas.forEach { doc.blocks.add(com.therealityteam.aeropages.model.EquationBlock(it, para.format.align, size)) }
                    } else if (para.plainText().isNotBlank() || (frames.isEmpty() && boxes.isEmpty() && charts.isEmpty() && shapes.isEmpty()) ||
                        // Something floats from it: a line of its own, and what floats counts from it.
                        (frames + boxes + shapes).any { floatOf(it, styles) != null }) doc.blocks.add(ParagraphBlock(para))
                    charts.forEach { (frame, chart) ->
                        doc.blocks.add(com.therealityteam.aeropages.model.ChartBlock(chart,
                            OdfValue.lengthPt(frame.attr("width")) ?: com.therealityteam.aeropages.model.ChartBlock.DEFAULT_W,
                            OdfValue.lengthPt(frame.attr("height")) ?: com.therealityteam.aeropages.model.ChartBlock.DEFAULT_H))
                    }
                    frames.forEach { frame -> image(frame, files, styles)?.let { doc.blocks.add(it) } }
                    // Drawn shapes become shape boxes after their anchor paragraph: their look, their size, their paragraphs.
                    shapes.forEach { n ->
                        val (shape, box) = OdpReader.readShape(n, styles)
                        val paras = paragraphsOf(n, styles, doc, files).map { it.second }
                            .filter { it.plainText().isNotEmpty() }
                        // Where it floats: its box's corner (a line's from its ends).
                        doc.blocks.add(com.therealityteam.aeropages.model.TextBoxBlock(paras.toMutableList(), box[2].takeIf { it > 0f }, box[3], shape,
                            floatOf(n, styles)?.also { f -> f.x = box[0]; f.y = box[1] }))
                    }
                    // draw:text-box frames become text box blocks after their anchor paragraph — a drawn
                    // text box (Word's) when its style gives it a fill or a line, or it is turned.
                    boxes.forEach { frame ->
                        val tb = frame.child("text-box") ?: return@forEach
                        // A frame holding a table (how LibreOffice keeps Word's positioned table): the table, floating where the frame is.
                        val tables = tb.children("table")
                        if (tables.isNotEmpty()) {
                            tables.forEachIndexed { i, t ->
                                doc.blocks.add(TableBlock(table(t, styles, doc, files).also { tt ->
                                    if (i == 0) { tt.floating = floatOf(frame, styles); tt.widthPt = OdfValue.lengthPt(frame.attr("width")) }
                                }))
                            }
                            return@forEach
                        }
                        val paras = paragraphsOf(tb, styles, doc, files).map { it.second }
                        val look = styles.resolvedGraphic(frame.attr("style-name"))
                        val drawn = (look != null && (look.filled != null || look.stroked != null || look.activeGradient != null)) || frame.attr("transform") != null
                        doc.blocks.add(com.therealityteam.aeropages.model.TextBoxBlock(
                            paras.ifEmpty { listOf(Paragraph.of("")) }.toMutableList(),
                            OdfValue.lengthPt(frame.attr("width")), OdfValue.lengthPt(frame.attr("height")),
                            if (drawn) OdpReader.readShape(frame, styles).first else null,
                            floatOf(frame, styles),
                        ))
                    }
                }
                // A frame anchored to the page (it stands in the body, outside any paragraph): a picture or text box placed on the page.
                "frame" -> {
                    if (node.child("image") != null && !readObject(node, files)) image(node, files, styles)?.let { doc.blocks.add(it) }
                    else node.child("text-box")?.let { tb ->
                        val paras = paragraphsOf(tb, styles, doc, files).map { it.second }
                        doc.blocks.add(com.therealityteam.aeropages.model.TextBoxBlock(paras.ifEmpty { listOf(Paragraph.of("")) }.toMutableList(),
                            OdfValue.lengthPt(node.attr("width")), OdfValue.lengthPt(node.attr("height")), null, floatOf(node, styles)))
                    }
                }
                "list" -> readList(node, styles, doc, files, 0, null) { _, p -> doc.blocks.add(ParagraphBlock(p)) }
                "table" -> doc.blocks.add(TableBlock(table(node, styles, doc, files)))
                // A table of contents is regenerated from the headings; its cached body is not copied as text.
                "table-of-content" -> doc.blocks.add(com.therealityteam.aeropages.model.TocBlock())
                // A table of figures (an illustration or table index of a caption sequence): regenerated from the captions.
                // An alphabetical index: Word's index, regenerated from the marked entries.
                "alphabetical-index" -> {
                    val source = node.child("alphabetical-index-source")
                    val tab = source?.children("alphabetical-index-entry-template")?.firstOrNull { it.attr("outline-level") == "1" }?.child("index-entry-tab-stop") != null
                    doc.blocks.add(com.therealityteam.aeropages.model.IndexBlock(rightAlign = tab, headings = source?.attr("alphabetical-separators") == "true"))
                }
                "illustration-index", "table-index" -> doc.blocks.add(com.therealityteam.aeropages.model.TocBlock(
                    node.child("${node.name}-source")?.attr("caption-sequence-name")?.takeIf { it.isNotBlank() } ?: if (node.name == "table-index") "Table" else "Figure"))
                "section" -> readBlocks(node, styles, files, doc, track, styles.columnsOf(node.attr("style-name")) ?: columns)
                "text" -> readBlocks(node, styles, files, doc, track, columns)
            }
    }

    /** `text:outline-level` first, then the style's own level, then the style it derives from (`Heading_20_2`). */
    private fun headingLevel(node: OdfNode, styles: OdfStyles): Int {
        node.attr("outline-level")?.toIntOrNull()?.let { return it }
        val name = node.attr("style-name")
        styles.resolvedParagraph(name)?.outlineLevel?.let { return it }
        return styles.ancestry(name).firstNotNullOfOrNull { headingOf(it) } ?: 1
    }

    /** `Heading_20_2` / `Heading 2` → 2; null when the style is not a heading. */
    private fun headingOf(styleName: String?): Int? =
        Regex("Heading[_ ]*(?:20_)?(\\d)").find(styleName.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    /** A paragraph or heading; [fields]: a header's, where the page number and count are fields (see HeaderFooters). */
    private fun paragraph(node: OdfNode, styles: OdfStyles, headingLevel: Int?, doc: Document, files: Map<String, ByteArray> = emptyMap(), fields: Boolean = false): Paragraph {
        val style = styles.resolvedParagraph(node.attr("style-name"))
        val format = ParagraphFormat(
            align = style?.align ?: com.therealityteam.aeropages.model.TextAlign.LEFT,
            style = when (headingLevel ?: styles.ancestry(node.attr("style-name")).firstNotNullOfOrNull { headingOf(it) }) {
                1 -> ParagraphStyle.HEADING1
                2 -> ParagraphStyle.HEADING2
                3 -> ParagraphStyle.HEADING3
                null -> if (styles.ancestry(node.attr("style-name")).any { it.startsWith("Title") }) ParagraphStyle.TITLE else ParagraphStyle.NORMAL
                else -> ParagraphStyle.HEADING3
            },
        )
        style?.spaceBeforePt?.let { format.spaceBeforePt = it }
        style?.spaceAfterPt?.let { format.spaceAfterPt = it }
        style?.indentLeftPt?.let { format.indentLeftPt = it }
        // A first line or right indent in characters is so many times the paragraph style's font size.
        val em by lazy { styles.resolvedSizePt(node.attr("style-name"), doc.styles.defaultSizePt) ?: doc.styles.defaultSizePt }
        style?.firstLineIndent?.let { format.firstLineIndentPt = it.pt(em) }
        style?.indentRight?.let { format.indentRightPt = it.pt(em) }
        // Borders and shading: ODF's margin is outside the side lines and their padding, Word's indents are the text's.
        style?.let { st ->
            st.borders?.takeIf { !it.none }?.let { b -> format.borders = b; format.indentLeftPt += st.boxLeft; format.indentRightPt += st.boxRight }
            st.background?.takeIf { (it ushr 24) != 0L }?.let { format.shading = it }
        }
        val height = style?.lineHeightPt
        if (height != null) { format.lineSpacing = 1f; format.lineHeightPt = height; format.lineExact = style.lineExact }
        else style?.lineSpacing?.let { format.lineSpacing = it }
        // Tab stops count from the column's edge in the model: from the indent in the file, unless the document says not.
        style?.tabStops?.let { stops ->
            val shift = if (tabsFromIndent.get() != false) format.indentLeftPt else 0f
            format.tabStops = stops.map { it.copy(posPt = it.posPt + shift) }.sortedBy { it.posPt }
        }
        // Headings keep with the next paragraph by default: only a different setting is kept explicitly.
        style?.keepWithNext?.let { if (it != com.therealityteam.aeropages.model.PageMetrics.keepsWithNext(Paragraph(format = format))) format.keepWithNext = it }
        style?.keepTogether?.let { format.keepTogether = it }
        style?.breakBefore?.let { format.pageBreakBefore = it }
        style?.widowControl?.let { format.widowControl = it }
        style?.snapToGrid?.let { format.snapToGrid = it }
        ownStyleOf(node.attr("style-name"), styles, doc)?.let { format.rawStyleId = it }
        if (style?.numberLines == false) format.suppressLineNumbers = true

        val runs = ArrayList<TextRun>()
        // The style's text, sized as its chain says — a size relative to its parent's worked out (the
        // chain merged would give the parent's own size).
        val base = (style?.text ?: CharacterFormat()).copyOf().also { f ->
            styles.resolvedSizePt(node.attr("style-name"), doc.styles.defaultSizePt)?.let { f.fontSizePt = it }
            // What the styles leave unset is the document's default, set on the runs as the app's styles are.
            if (f.fontFamily == null) f.fontFamily = doc.styles.defaultFontFamily
            if (f.fontSizePt == null) f.fontSizePt = doc.styles.defaultSizePt
        }
        val marks = ArrayList<com.therealityteam.aeropages.model.BookmarkMark>()
        val commentsBefore = doc.comments.size
        collectRuns(node, styles, base, null, runs, doc, files, fields, marks)
        if (runs.isEmpty()) runs.add(TextRun("", base.copyOf()))
        // An insertion still open at its end takes in its mark (the break to the next paragraph).
        changes.get()?.open?.values?.lastOrNull { it.kind == OdfChanges.Kind.INSERTION }?.let { format.revisionMark = com.therealityteam.aeropages.model.RevisionMark(true, it.author, it.date) }
        // A drop cap: its letters in their text style's font; "word" the first word's length.
        style?.dropCap?.let { dc ->
            val text = runs.joinToString("") { it.text }
            val n = if (dc.length == OdfStyles.DropCapProps.WORD) text.takeWhile { !it.isWhitespace() }.length else dc.length
            if (n > 0) format.dropCap = com.therealityteam.aeropages.model.DropCap(lines = dc.lines, distancePt = dc.distancePt, length = n,
                fontFamily = styles.text(dc.style)?.fontFamily?.takeIf { f -> f != runs.firstOrNull { it.text.isNotEmpty() }?.format?.fontFamily })
        }
        return Paragraph(runs, format, bookmarks = marks).also { p ->
            // The comments met in it are on it.
            for (i in commentsBefore until doc.comments.size) if (doc.comments[i].targetId == PENDING_COMMENT) doc.comments[i].targetId = p.id
        }
    }

    /**
     * A deletion's text where its mark is: each of its paragraphs' runs, deleted by its author at its date, a break (a
     * deleted paragraph mark, with the format of the paragraph it ends) between two. Read with nothing open, as the
     * deletion is what it is.
     */
    private fun deleted(r: OdfChanges.Region, c: Changes, styles: OdfStyles, doc: Document, files: Map<String, ByteArray>, out: MutableList<TextRun>) {
        val saved = LinkedHashMap(c.open)
        c.open.clear()
        try {
            val paras = r.node.children.filter { it.name == "p" || it.name == "h" }
            var before: ParagraphFormat? = null
            for (n in paras) {
                val p = paragraph(n, styles, if (n.name == "h") headingLevel(n, styles) else null, doc, files)
                if (before != null) {
                    val br = TextRun(OdfChanges.BREAK, CharacterFormat(deleted = true, revAuthor = r.author, revDate = r.date))
                    out.add(br)
                    c.breaks[br] = before
                }
                p.runs.filter { it.text.isNotEmpty() }.forEach { run ->
                    run.format.deleted = true; run.format.inserted = false; run.format.revAuthor = r.author; run.format.revDate = r.date
                    out.add(run)
                }
                before = p.format
            }
        } finally { c.open.putAll(saved) }
    }

    /**
     * Walk mixed content in order, carrying the character format down through spans and links. Notes
     * met on the way are appended to [doc] (footnotes / endnotes) in document order; [fields]: the page
     * number and count become their tokens (a header's), else show what they last showed.
     */
    private fun collectRuns(node: OdfNode, styles: OdfStyles, base: CharacterFormat, link: String?, out: MutableList<TextRun>, doc: Document, files: Map<String, ByteArray> = emptyMap(), fields: Boolean = false,
                            marks: MutableList<com.therealityteam.aeropages.model.BookmarkMark>? = null) {
        /** A bookmark's start or end here: before the text read so far (`text:bookmark` is a point: both). */
        fun mark(name: String?, start: Boolean, end: Boolean) {
            if (marks == null || name.isNullOrEmpty()) return
            val at = out.sumOf { it.text.length }
            if (start) marks.add(com.therealityteam.aeropages.model.BookmarkMark(name, at, false))
            if (end) marks.add(com.therealityteam.aeropages.model.BookmarkMark(name, at, true))
        }
        /** An index entry marked here: [node]'s keys over [value] (its string value, else the text it marks). */
        fun indexMark(node: OdfNode, value: String?) {
            if (marks == null) return
            val text = com.therealityteam.aeropages.model.IndexEntries.clean(node.attr("string-value") ?: value.orEmpty())
            val key1 = node.attr("key1")?.let(com.therealityteam.aeropages.model.IndexEntries::clean)?.takeIf { it.isNotEmpty() }
            val key2 = node.attr("key2")?.let(com.therealityteam.aeropages.model.IndexEntries::clean)?.takeIf { it.isNotEmpty() }
            val entry = if (key1 != null) com.therealityteam.aeropages.model.IndexEntries.Entry(key1, listOfNotNull(key2, text.ifEmpty { null }).joinToString(": ").ifEmpty { null })
                else com.therealityteam.aeropages.model.IndexEntries.Entry(text)
            if (entry.main.isEmpty()) return
            marks.add(com.therealityteam.aeropages.model.BookmarkMark(com.therealityteam.aeropages.model.IndexEntries.markName(entry), out.sumOf { it.text.length }, false))
        }
        /** [f] marked as the change the text is in makes it (inserted, its format changed). */
        fun tracked(f: CharacterFormat) = f.also { OdfChanges.mark(it, changes.get()?.current) }
        fun emit(text: String) {
            if (text.isEmpty()) return
            val format = tracked(base.copyOf().also { it.link = link })
            val last = out.lastOrNull()
            if (last != null && last.format == format) out[out.lastIndex] = TextRun(last.text + text, format)
            else out.add(TextRun(text, format))
        }
        for (part in node.parts) {
            when (part) {
                is String -> emit(part)
                is OdfNode -> when (part.name) {
                    "span" -> collectRuns(part, styles, merge(base, styles.text(part.attr("style-name")), styles.textTurnsOff(part.attr("style-name"))), link, out, doc, files, fields, marks)
                    "a" -> collectRuns(part, styles, base, part.attr("href") ?: link, out, doc, files, fields, marks)
                    "bookmark" -> mark(part.attr("name"), start = true, end = true)
                    // Tracked changes: an insertion's or format change's ends, a deletion's mark (its text read from its region).
                    "change-start" -> changes.get()?.let { c -> part.attr("change-id")?.let { id -> c.regions[id]?.takeIf { it.kind != OdfChanges.Kind.DELETION }?.let { c.open[id] = it } } }
                    "change-end" -> part.attr("change-id")?.let { changes.get()?.open?.remove(it) }
                    "change" -> changes.get()?.let { c -> part.attr("change-id")?.let { c.regions[it] }?.takeIf { it.kind == OdfChanges.Kind.DELETION }?.let { r -> deleted(r, c, styles, doc, files, out) } }
                    "bookmark-start" -> mark(part.attr("name"), start = true, end = false)
                    // Index entries: a point mark, or a range whose text is the entry (marked where the range ends).
                    "alphabetical-index-mark" -> indexMark(part, null)
                    "alphabetical-index-mark-start" -> part.attr("id")?.let { indexRanges.get()[it] = Triple(part, out, out.sumOf { r -> r.text.length }) }
                    "alphabetical-index-mark-end" -> part.attr("id")?.let { indexRanges.get().remove(it) }?.let { (start, runs, at) ->
                        if (runs === out) indexMark(start, out.joinToString("") { it.text }.let { t -> t.substring(at.coerceAtMost(t.length)) })
                    }
                    "bookmark-end" -> mark(part.attr("name"), start = false, end = true)
                    "s" -> emit(" ".repeat((part.attr("c")?.toIntOrNull() ?: 1).coerceIn(1, 200)))
                    "tab" -> emit("\t")
                    "line-break" -> emit("\n")
                    // A caption's number (`text:sequence`) and a cross-reference to a bookmark (`text:bookmark-ref`): live fields.
                    "sequence", "bookmark-ref" -> {
                        // A sequence numbered by chapter holds its own number and shows the chapter's before it: Word's STYLEREF,
                        // the separator and `SEQ \s` (the chapter's number worked out once the document is read).
                        val chapter = if (part.name == "sequence") sequenceDecls.get()?.get(part.attr("name").orEmpty().lowercase()) else null
                        if (chapter != null && part.text.isNotEmpty()) {
                            com.therealityteam.aeropages.model.Fields.of("STYLEREF ${chapter.first} \\s")?.let { f ->
                                out.add(TextRun("0", tracked(base.copyOf().also { it.field = f; it.link = link })))
                            }
                            out.add(TextRun(chapter.second, tracked(base.copyOf().also { it.link = link })))
                            chapterFields.set(true)
                        }
                        val code = if (part.name == "sequence") {
                            val name = part.attr("name").orEmpty()
                            val format = when (part.attr("num-format")) { "A" -> "ALPHABETIC"; "a" -> "alphabetic"; "I" -> "ROMAN"; "i" -> "roman"; else -> "ARABIC" }
                            "SEQ $name \\* $format" + (if (chapter != null) " \\s ${chapter.first}" else "")
                        } else {
                            val name = part.attr("ref-name").orEmpty()
                            when (part.attr("reference-format")) {
                                "page" -> "PAGEREF $name \\h"
                                "number", "number-all-superior", "number-no-superior", "chapter" -> "REF $name \\r \\h"
                                "direction" -> "REF $name \\p \\h"
                                else -> "REF $name \\h"
                            }
                        }
                        val field = com.therealityteam.aeropages.model.Fields.of(code)
                        val text = part.text
                        if (field == null || text.isEmpty()) emit(text)
                        else out.add(TextRun(text, tracked(base.copyOf().also { it.field = field; if (field.kind != "SEQ") it.link = "#" + field.target else it.link = link })))
                    }
                    // A table formula (LibreOffice's, or Word's as LibreOffice keeps it): Word's `=` field, the cells out of their brackets.
                    "table-formula" -> {
                        val formula = part.attr("formula").orEmpty().substringAfter(':', part.attr("formula").orEmpty())
                            .replace(Regex("""<([A-Za-z]{1,3}\d+(:[A-Za-z]{1,3}\d+)?)>"""), "$1").replace(Regex("""(?i)\bMEAN\b"""), "AVERAGE").trim()
                        val field = com.therealityteam.aeropages.model.Fields.of("=$formula")
                        if (field == null || part.text.isEmpty()) emit(part.text)
                        else out.add(TextRun(part.text, tracked(base.copyOf().also { it.field = field; it.link = link })))
                    }
                    // A chapter's number or heading (Word's STYLEREF n \s / STYLEREF n); number and name together stays text.
                    "chapter" -> {
                        val level = part.attr("outline-level")?.toIntOrNull() ?: 1
                        val code = when (part.attr("display")) { "number" -> "STYLEREF $level \\s"; "name" -> "STYLEREF $level"; else -> null }
                        val field = code?.let { com.therealityteam.aeropages.model.Fields.of(it) }
                        if (field == null || part.text.isEmpty()) emit(part.text)
                        else out.add(TextRun(part.text, tracked(base.copyOf().also { it.field = field; it.link = link })))
                    }
                    "page-number" -> if (fields) emit(com.therealityteam.aeropages.model.HeaderFooters.PAGE) else emit(part.text)
                    "page-count" -> if (fields) emit(com.therealityteam.aeropages.model.HeaderFooters.PAGES) else emit(part.text)
                    // A date or time that is not fixed shows now (as LibreOffice and Word show it), in its data style.
                    "date", "time" -> if (part.attr("fixed") == "true") emit(part.text) else emit(nowIn(part, styles) ?: part.text)
                    "note" -> {
                        // A note becomes its superscript marker in the text plus an entry on the document.
                        val body = part.child("note-body")?.descendants("p")?.joinToString("\n") { it.text }.orEmpty()
                        val marker = if (part.attr("note-class") == "endnote") {
                            doc.endnotes.add(com.therealityteam.aeropages.model.Endnote(body))
                            "[${com.therealityteam.aeropages.model.Roman.of(doc.endnotes.size)}]"
                        } else {
                            doc.footnotes.add(com.therealityteam.aeropages.model.Footnote(body))
                            "[${doc.footnotes.size}]"
                        }
                        val fmt = tracked(base.copyOf().also { it.superscript = true; it.link = link })
                        out.add(TextRun(marker, fmt))
                    }
                    // A comment (LibreOffice's, or Word's through it): its author, date and text; named, it marks where its range
                    // starts (its `office:annotation-end` where it ends); a reply names its thread.
                    "annotation" -> {
                        val c = com.therealityteam.aeropages.model.Comment(
                            part.child("creator")?.text?.trim().orEmpty(),
                            part.children.filter { it.name == "p" || it.name == "list" }.joinToString("\n") { it.text },
                            PENDING_COMMENT,
                            dateMs = part.child("date")?.text?.trim()?.let { d -> runCatching { java.time.LocalDateTime.parse(d).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull() } ?: System.currentTimeMillis(),
                            resolved = part.attr("resolved") == "true",
                        )
                        val names = annotations.get()
                        part.attr("parent-name")?.let { names?.get(it) }?.let { parent -> c.parentId = parent.parentId ?: parent.id }
                        doc.comments.add(c)
                        part.attr("name")?.takeIf { it.isNotEmpty() }?.let { n ->
                            names?.put(n, c)
                            if (c.parentId == null) mark(com.therealityteam.aeropages.model.Bookmarks.commentMark(c.id), start = true, end = false)
                        }
                    }
                    "annotation-end" -> part.attr("name")?.let { annotations.get()?.get(it) }?.takeIf { it.parentId == null }
                        ?.let { c -> mark(com.therealityteam.aeropages.model.Bookmarks.commentMark(c.id), start = false, end = true) }
                    // A formula in the text: runs that spell it, tied to it; pictures become their own block.
                    "frame" -> formulaOf(part, files)?.let { m ->
                        out.addAll(com.therealityteam.aeropages.model.MathText.runs(com.therealityteam.aeropages.model.InlineMath(m),
                            CharacterFormat(fontSizePt = base.fontSizePt, color = base.color)))
                    }
                    "image" -> Unit
                    // A drawn shape's text is its own (a block after the paragraph), not the paragraph's.
                    in DRAWN_SHAPES -> Unit
                    else -> collectRuns(part, styles, base, link, out, doc, files, fields, marks)
                }
            }
        }
    }

    /** A `text:date` / `text:time`'s now, in its data style (else the date as ISO, the time as h:mm); null when its style is unreadable. */
    private fun nowIn(node: OdfNode, styles: OdfStyles): String? {
        val now = java.time.LocalDateTime.now()
        val serial = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.of(1899, 12, 30), now.toLocalDate()) + now.toLocalTime().toSecondOfDay() / 86400.0
        val code = styles.dataCode(node.attr("data-style-name")) ?: if (node.name == "time") "h:mm" else "yyyy-mm-dd"
        return runCatching { com.therealityteam.aeropages.model.FormatCode.format(serial, code).text }.getOrNull()
    }

    /**
     * True when [frame] shows an object read as itself (a formula, a chart): the picture beside it
     * is only its preview. Other objects keep their preview as a picture.
     */
    private fun readObject(frame: OdfNode, files: Map<String, ByteArray>): Boolean =
        frame.child("object") != null && (formulaOf(frame, files) != null || chartOf(frame, files) != null)

    /** The chart a frame shows — a `draw:object` whose content is a chart — or null. */
    private fun chartOf(frame: OdfNode, files: Map<String, ByteArray>): com.therealityteam.aeropages.model.SlideChart? {
        val href = frame.child("object")?.attr("href")?.removePrefix("./")?.trimEnd('/') ?: return null
        val bytes = files["$href/content.xml"] ?: return null
        if (MathMl.isFormula(bytes)) return null
        return OdfChart.read(bytes)
    }

    /** The formula a frame shows — a `draw:object` whose content is MathML — or null. */
    private fun formulaOf(frame: OdfNode, files: Map<String, ByteArray>): com.therealityteam.aeropages.model.MathNode? {
        val href = frame.child("object")?.attr("href")?.removePrefix("./")?.trimEnd('/') ?: return null
        val bytes = files["$href/content.xml"] ?: return null
        if (!MathMl.isFormula(bytes)) return null
        return MathMl.read(OdfNode.parse(bytes))
    }

    /** [base] with a span's [add]ed traits; [off] are those the span turns off (`fo:font-weight="normal"` inside a bold heading). */
    private fun merge(base: CharacterFormat, add: CharacterFormat?, off: Set<String> = emptySet()): CharacterFormat {
        if (add == null) return base
        return CharacterFormat(
            bold = (base.bold && "bold" !in off) || add.bold,
            italic = (base.italic && "italic" !in off) || add.italic,
            underline = (base.underline && "underline" !in off) || add.underline,
            strikethrough = (base.strikethrough && "strikethrough" !in off) || add.strikethrough,
            superscript = base.superscript || add.superscript,
            subscript = base.subscript || add.subscript,
            smallCaps = base.smallCaps || add.smallCaps,
            letterSpacingPt = add.letterSpacingPt ?: base.letterSpacingPt,
            fontSizePt = add.fontSizePt ?: base.fontSizePt,
            fontFamily = add.fontFamily ?: base.fontFamily,
            color = add.color ?: base.color,
            highlight = add.highlight ?: base.highlight,
        )
    }

    private fun table(node: OdfNode, styles: OdfStyles, doc: Document, files: Map<String, ByteArray>): Table {
        val widths = ArrayList<Float>()
        node.children("table-column").forEach { col ->
            val repeat = (col.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 64)
            val rel = col.attr("style-name")?.let { name ->
                styles.columnWidth(name)
            } ?: 1f
            repeat(repeat) { widths.add(rel) }
        }
        val rows = ArrayList<TableRow>()
        // Rows spanned from above, by the grid column they start at: rows still to cover and columns wide.
        val spannedDown = HashMap<Int, IntArray>()
        node.children.filter { it.name == "table-row" || it.name == "table-header-rows" }.forEach { rowNode ->
            val rowNodes = if (rowNode.name == "table-header-rows") rowNode.children("table-row") else listOf(rowNode)
            rowNodes.forEach { r ->
                val cells = ArrayList<TableCell>()
                var col = 0
                var across = 0   // covered cells still to come of the cell before's span across
                r.children.forEach { c ->
                    when (c.name) {
                        "table-cell" -> {
                            val cell = TableCell(mutableListOf())
                            paragraphsOf(c, styles, doc, files).forEach { (p, para) ->
                                // A paragraph holding only pictures gives the cell its pictures, not an empty line.
                                val frames = p.descendants("frame").filter { it.child("image") != null && !readObject(it, files) }
                                if (frames.isEmpty() || para.plainText().isNotBlank()) cell.content.add(para)
                                frames.forEach { frame -> image(frame, files, styles)?.let { cell.images.add(it) } }
                            }
                            if (cell.content.isEmpty()) cell.content.add(Paragraph.of(""))
                            cell.gridSpan = (c.attr("number-columns-spanned")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                            cell.rowSpan = (c.attr("number-rows-spanned")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                            val look = styles.cell(c.attr("style-name"))
                            cell.shade = look?.background
                            // Its borders, side by side as its style says (none where it says none).
                            cell.borders = look?.edges?.map { v -> v?.let { OdfBorders.cellLineOf(it) } }?.let { (t, b, l, r) -> com.therealityteam.aeropages.model.CellBorders(t, b, l, r) }
                            cells.add(cell)
                            if (cell.rowSpan > 1) spannedDown[col] = intArrayOf(cell.rowSpan - 1, cell.gridSpan)
                            across = cell.gridSpan - 1
                            col++
                        }
                        // A covered cell: the cell before spans across it (nothing of its own), or a cell above
                        // spans down over it — the place it keeps, as wide as that cell — or a stray one.
                        "covered-table-cell" -> {
                            val down = spannedDown[col]
                            when {
                                across > 0 -> across--
                                down != null && down[0] > 0 -> {
                                    cells.add(TableCell(mutableListOf(Paragraph.of("")), gridSpan = down[1], vMerged = true))
                                    if (--down[0] == 0) spannedDown.remove(col)
                                    across = down[1] - 1
                                }
                                else -> cells.add(TableCell(mutableListOf(Paragraph.of("")), vMerged = true))
                            }
                            col++
                        }
                    }
                }
                rows.add(TableRow(cells, header = rowNode.name == "table-header-rows").also { row ->
                    val rs = r.attr("style-name")
                    styles.rowHeight(rs)?.let { row.heightPt = it; row.exactHeight = true } ?: styles.rowMinHeight(rs)?.let { row.heightPt = it }
                })
            }
        }
        val total = widths.sum().takeIf { it > 0f } ?: widths.size.toFloat()
        val weights = widths.map { if (total > 0f) it * widths.size / total else 1f }
        // ODF draws a cell's borders only where its style says: one line everywhere is the table's grid, none
        // anywhere no borders, any other way each cell its own.
        return Table(rows, columnWidths = weights.toMutableList()).also { t ->
            t.adoptBorders { ri, ci -> rows[ri].cells[ci].borders ?: com.therealityteam.aeropages.model.CellBorders() }
            // Its own width and where it stands ("margins": as wide as the text).
            styles.tableLayout(node.attr("style-name"))?.takeIf { it.align != "margins" }?.let { l ->
                t.widthPt = l.widthPt
                t.align = when (l.align) { "center" -> com.therealityteam.aeropages.model.TextAlign.CENTER; "right" -> com.therealityteam.aeropages.model.TextAlign.RIGHT
                    else -> com.therealityteam.aeropages.model.TextAlign.LEFT }
                if (t.align == com.therealityteam.aeropages.model.TextAlign.LEFT) l.marginLeftPt?.let { t.indentPt = it }
            }
        }
    }

    /**
     * A `draw:frame` wrapping a `draw:image`: the size is the frame's, the bytes are in the package,
     * the crop is its graphic style's `fo:clip`.
     */
    private fun image(frame: OdfNode, files: Map<String, ByteArray>, styles: OdfStyles): ImageBlock? {
        val href = frame.child("image")?.attr("href")?.removePrefix("./") ?: return null
        val data = files[href] ?: return null
        val w = OdfValue.lengthPt(frame.attr("width")) ?: 240f
        val h = OdfValue.lengthPt(frame.attr("height")) ?: 180f
        val mime = com.therealityteam.aeropages.format.PictureType.mimeOf(data, href)
        return ImageBlock(data, mime, w, h).also {
            val look = styles.resolvedGraphic(frame.attr("style-name"))
            it.shadow = OdpReader.shadowOf(look)
            com.therealityteam.aeropages.format.PictureCrop.readFoClip(look?.clip, data, it)
            it.floating = floatOf(frame, styles)
            look?.mirror?.let { m -> it.flipH = "horizontal" in m; it.flipV = "vertical" in m }
            // Its alternative text (svg:desc, else the title) and LibreOffice's decorative mark.
            OdfAltText.read(frame, look).let { (alt, decorative) -> it.altText = alt; it.decorative = decorative }
        }
    }

    /**
     * Where a frame or drawn shape anchored to its paragraph (or a character, or the page) stands: at
     * `svg:x` / `svg:y` from the frames its graphic style's `style:horizontal-rel` / `vertical-rel`
     * name (or aligned there by `horizontal-pos` / `vertical-pos`), the text going round it, above
     * and below it (`style:wrap="none"`) or not at all — behind or in front (`run-through`). Null for
     * one standing as a character in its line.
     */
    internal fun floatOf(frame: OdfNode, styles: OdfStyles): com.therealityteam.aeropages.model.FloatPosition? {
        val anchor = frame.attr("anchor-type") ?: return null
        if (anchor == "as-char" || anchor == "frame") return null
        val look = styles.resolvedGraphic(frame.attr("style-name"))
        val page = anchor == "page"
        val hFrom = when (look?.hRel) {
            "page" -> com.therealityteam.aeropages.model.FloatFrame.PAGE
            "page-content" -> com.therealityteam.aeropages.model.FloatFrame.MARGIN
            "page-start-margin", "page-start-margin-area" -> com.therealityteam.aeropages.model.FloatFrame.LEFT_MARGIN
            "page-end-margin", "page-end-margin-area" -> com.therealityteam.aeropages.model.FloatFrame.RIGHT_MARGIN
            null -> if (page) com.therealityteam.aeropages.model.FloatFrame.PAGE else com.therealityteam.aeropages.model.FloatFrame.COLUMN
            else -> com.therealityteam.aeropages.model.FloatFrame.COLUMN
        }
        val vFrom = when (look?.vRel) {
            "page" -> com.therealityteam.aeropages.model.FloatFrame.PAGE
            "page-content" -> com.therealityteam.aeropages.model.FloatFrame.MARGIN
            // LibreOffice's own (loext:vertical-rel) for Word's bands above and below the text.
            "page-content-top" -> com.therealityteam.aeropages.model.FloatFrame.TOP_MARGIN
            "page-content-bottom" -> com.therealityteam.aeropages.model.FloatFrame.BOTTOM_MARGIN
            null -> if (page) com.therealityteam.aeropages.model.FloatFrame.PAGE else com.therealityteam.aeropages.model.FloatFrame.PARAGRAPH
            else -> if (page) com.therealityteam.aeropages.model.FloatFrame.PAGE else com.therealityteam.aeropages.model.FloatFrame.PARAGRAPH
        }
        fun align(v: String?) = when (v) {
            "left", "top", "inside" -> com.therealityteam.aeropages.model.FloatAlign.START
            "center", "middle" -> com.therealityteam.aeropages.model.FloatAlign.CENTER
            "right", "bottom", "outside" -> com.therealityteam.aeropages.model.FloatAlign.END
            else -> null
        }
        val wrap = when (look?.wrap) {
            "run-through" -> if (look.runThrough == "background") com.therealityteam.aeropages.model.FloatWrap.BEHIND else com.therealityteam.aeropages.model.FloatWrap.FRONT
            "none" -> com.therealityteam.aeropages.model.FloatWrap.TOP_BOTTOM
            else -> com.therealityteam.aeropages.model.FloatWrap.SQUARE
        }
        return com.therealityteam.aeropages.model.FloatPosition(
            OdfValue.lengthPt(frame.attr("x")) ?: 0f, hFrom, align(look?.hPos),
            OdfValue.lengthPt(frame.attr("y")) ?: 0f, vFrom, align(look?.vPos), wrap,
        )
    }

    /**
     * Page size and margins and the headers and footers of the first master page: its own, its left
     * (even) pages' (`style:header-left`) and its first page's (`style:header-first`) — their text
     * and their pictures.
     */
    private fun readPage(stylesPart: OdfNode?, styles: OdfStyles, doc: Document, files: Map<String, ByteArray> = emptyMap()) {
        val root = stylesPart ?: return
        val master = root.descendants("master-page").firstOrNull() ?: return
        val layoutName = master.attr("page-layout-name")
        val layout = root.descendants("page-layout").firstOrNull { it.attr("name") == layoutName }
        layout?.let { setupOf(it, master) }?.let { doc.page.assignFrom(it) }
        layout?.child("page-layout-properties")?.let { doc.pageBorders = pageBordersOf(it) }
        HF_SLOTS.forEach { (name, slot) ->
            val node = master.child(name)?.takeIf { it.attr("display") != "false" } ?: return@forEach
            val (p, pics) = headerFooter(node, styles, doc, files)
            when (slot) {
                com.therealityteam.aeropages.model.HfSlot.HEADER -> doc.header = p
                com.therealityteam.aeropages.model.HfSlot.FOOTER -> doc.footer = p
                com.therealityteam.aeropages.model.HfSlot.EVEN_HEADER -> doc.evenHeader = p
                com.therealityteam.aeropages.model.HfSlot.EVEN_FOOTER -> doc.evenFooter = p
                com.therealityteam.aeropages.model.HfSlot.FIRST_HEADER -> doc.firstHeader = p
                com.therealityteam.aeropages.model.HfSlot.FIRST_FOOTER -> doc.firstFooter = p
            }
            if (pics.isNotEmpty()) doc.hfPictures[slot] = pics.toMutableList()
            // A first page's or even pages' header holding text or only a picture is their own.
            if (p != null || pics.isNotEmpty()) when (slot) {
                com.therealityteam.aeropages.model.HfSlot.EVEN_HEADER, com.therealityteam.aeropages.model.HfSlot.EVEN_FOOTER -> doc.differentOddEven = true
                com.therealityteam.aeropages.model.HfSlot.FIRST_HEADER, com.therealityteam.aeropages.model.HfSlot.FIRST_FOOTER -> doc.differentFirstPage = true
                else -> {}
            }
        }
    }

    /**
     * A page layout's borders as Word's page borders: each side's line, its margin (where ODF puts the border) as its space
     * from the page's edge, a shadow where `style:shadow` casts one. Null without any.
     */
    private fun pageBordersOf(props: OdfNode): com.therealityteam.aeropages.model.PageBorders? {
        fun line(side: String) = OdfBorders.cellLineOf(props.attr("border-$side") ?: props.attr("border") ?: "none")
        fun space(side: String) = OdfValue.twipsPt(props.attr("margin-$side")) ?: 0f
        return com.therealityteam.aeropages.model.PageBorders(
            top = line("top"), bottom = line("bottom"), left = line("left"), right = line("right"),
            spaceTop = space("top"), spaceBottom = space("bottom"), spaceLeft = space("left"), spaceRight = space("right"),
            shadow = props.attr("shadow").let { it != null && it != "none" },
        ).takeIf { !it.none }
    }

    /** A master page's header and footer elements and the slots they fill. */
    private val HF_SLOTS = listOf(
        "header" to com.therealityteam.aeropages.model.HfSlot.HEADER, "footer" to com.therealityteam.aeropages.model.HfSlot.FOOTER,
        "header-left" to com.therealityteam.aeropages.model.HfSlot.EVEN_HEADER, "footer-left" to com.therealityteam.aeropages.model.HfSlot.EVEN_FOOTER,
        "header-first" to com.therealityteam.aeropages.model.HfSlot.FIRST_HEADER, "footer-first" to com.therealityteam.aeropages.model.HfSlot.FIRST_FOOTER,
    )

    /**
     * A header's or footer's paragraphs as one — their runs with a line break between them, the page
     * number and count fields as their tokens, the alignment of the first that holds text; null when it
     * shows no text — and its pictures: frames anchored to its paragraphs where they place them, and
     * those standing in a line (`as-char`) on their paragraph's line (a paragraph holding only a picture
     * is a line of its own).
     */
    private fun headerFooter(node: OdfNode, styles: OdfStyles, doc: Document, files: Map<String, ByteArray>): Pair<Paragraph?, List<ImageBlock>> {
        val paras = ArrayList<OdfNode>()
        fun walk(n: OdfNode) { for (c in n.children) if (c.name == "p" || c.name == "h") paras.add(c) else walk(c) }
        walk(node)
        class Line(val p: Paragraph, val anchored: List<ImageBlock>, val inLine: List<ImageBlock>)
        val all = paras.map { n ->
            val anchored = ArrayList<ImageBlock>()
            val inLine = ArrayList<ImageBlock>()
            framesOf(n).forEach { f -> image(f, files, styles)?.let { if (it.floating != null) anchored.add(it) else inLine.add(it) } }
            Line(paragraph(n, styles, null, doc, fields = true), anchored, inLine)
        }
        fun texty(l: Line) = l.p.plainText().isNotBlank()
        val shown = all.dropWhile { !texty(it) && it.inLine.isEmpty() }.dropLastWhile { !texty(it) && it.inLine.isEmpty() }
        val pictures = ArrayList<ImageBlock>()
        all.forEach { pictures.addAll(it.anchored) }
        // Each paragraph's first line among the header's lines (its own line breaks make more).
        var lineNo = 0
        shown.forEach { l ->
            l.inLine.forEach { img -> img.floating = com.therealityteam.aeropages.model.FloatPlacement.inHeaderLine(lineNo, l.p.format.align); pictures.add(img) }
            lineNo += 1 + l.p.runs.sumOf { r -> r.text.count { it == '\n' } }
        }
        val first = shown.firstOrNull { texty(it) } ?: return null to pictures
        val runs = ArrayList<TextRun>()
        shown.forEachIndexed { i, l ->
            if (i > 0) runs.add(TextRun("\n", (l.p.runs.firstOrNull() ?: runs.lastOrNull() ?: first.p.runs.first()).format.copyOf()))
            runs.addAll(l.p.runs.filter { it.text.isNotEmpty() })
        }
        return Paragraph(runs, ParagraphFormat(align = first.p.format.align, spaceBeforePt = 0f, spaceAfterPt = 0f)) to pictures
    }
}
