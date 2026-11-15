package com.therealityteam.aeropages.model

/**
 * The in-memory representation of a word-processing document.
 *
 * The model is deliberately format-agnostic: the DOCX/DOC readers translate their
 * native structures into this model, and the writers translate it back. UI and
 * editing logic only ever touch this model, never the file formats directly.
 */

/** Sentinel text for a manual page-break paragraph (OOXML `<w:br w:type="page"/>`). */
const val PAGE_BREAK: String = "\u000C"

/** Paragraph horizontal alignment, mapping to OOXML `w:jc`. */
enum class TextAlign { LEFT, CENTER, RIGHT, JUSTIFY }

/** List flavour for a paragraph. */
enum class ListKind { NONE, BULLET, NUMBER }

/**
 * Named paragraph styles we surface in the UI. The string [styleId] is what we
 * round-trip to OOXML (`w:pStyle w:val`). Unknown styles fall back to [NORMAL]
 * but keep their original id so saving does not lose information.
 */
enum class ParagraphStyle(val styleId: String, private val label: TextId, val sizePt: Float, val bold: Boolean) {
    NORMAL("Normal", TextId.STYLE_NORMAL, 11f, false),
    TITLE("Title", TextId.STYLE_TITLE, 28f, true),
    HEADING1("Heading1", TextId.STYLE_HEADING1, 20f, true),
    HEADING2("Heading2", TextId.STYLE_HEADING2, 16f, true),
    HEADING3("Heading3", TextId.STYLE_HEADING3, 13f, true),
    QUOTE("Quote", TextId.STYLE_QUOTE, 11f, false);

    /** Localised name shown in the style gallery. */
    val display: String get() = Texts.get(label)

    /** Word's canonical `w:name` for the style (localised Word files keep the name but change the id). */
    val wordName: String get() = when (this) {
        NORMAL -> "Normal"; TITLE -> "Title"; HEADING1 -> "heading 1"; HEADING2 -> "heading 2"; HEADING3 -> "heading 3"; QUOTE -> "Quote"
    }

    companion object {
        fun fromId(id: String?): ParagraphStyle =
            entries.firstOrNull { it.styleId.equals(id, ignoreCase = true) } ?: NORMAL
    }
}

/**
 * A paragraph style definition (OOXML `w:style w:type="paragraph"`). Null properties inherit
 * through [basedOn] and, at the root, from the sheet's document defaults. Built-in
 * [ParagraphStyle] ids start from [StyleSheet.builtin]; foreign styles are kept by id so a
 * round trip preserves them.
 */
class StyleDef(
    val id: String,
    var name: String = id,
    var basedOn: String? = null,
    var fontFamily: String? = null,
    var sizePt: Float? = null,
    var bold: Boolean? = null,
    var italic: Boolean? = null,
    var color: Long? = null,
    var align: TextAlign? = null,
    var spaceBeforePt: Float? = null,
    var spaceAfterPt: Float? = null,
    var lineSpacing: Float? = null,
    /** An exact or at-least line height instead of [lineSpacing] (see [ParagraphFormat.lineHeightPt]). */
    var lineHeightPt: Float? = null,
    var lineExact: Boolean = true,
    /** Heading outline level (0 = Heading 1); null for body styles. */
    var outlineLevel: Int? = null,
    /** Left, first-line (negative: hanging) and right indents in points, as [ParagraphFormat] has them. */
    var indentLeftPt: Float? = null,
    var firstLineIndentPt: Float? = null,
    var indentRightPt: Float? = null,
    /** The style's paragraph borders and shading (Word's `w:pBdr`, `w:shd`; Title in Office 2007 has a bottom line); null: its base's. */
    var borders: ParagraphBorders? = null,
    var shading: Long? = null,
) {
    fun copyOf(): StyleDef = StyleDef(
        id, name, basedOn, fontFamily, sizePt, bold, italic, color, align, spaceBeforePt, spaceAfterPt, lineSpacing, lineHeightPt, lineExact, outlineLevel,
        indentLeftPt, firstLineIndentPt, indentRightPt, borders, shading,
    )
    fun sameAs(o: StyleDef): Boolean =
        id == o.id && name == o.name && basedOn == o.basedOn && fontFamily == o.fontFamily && sizePt == o.sizePt &&
            bold == o.bold && italic == o.italic && color == o.color && align == o.align && spaceBeforePt == o.spaceBeforePt &&
            spaceAfterPt == o.spaceAfterPt && lineSpacing == o.lineSpacing && lineHeightPt == o.lineHeightPt && lineExact == o.lineExact &&
            outlineLevel == o.outlineLevel &&
            indentLeftPt == o.indentLeftPt && firstLineIndentPt == o.firstLineIndentPt && indentRightPt == o.indentRightPt &&
            borders == o.borders && shading == o.shading
}

/** A style with every inherited property resolved; character traits always have a value. */
class ResolvedStyle(
    val fontFamily: String?, val sizePt: Float, val bold: Boolean, val italic: Boolean, val color: Long?,
    val align: TextAlign?, val spaceBeforePt: Float?, val spaceAfterPt: Float?, val lineSpacing: Float?,
    val indentLeftPt: Float? = null, val firstLineIndentPt: Float? = null, val indentRightPt: Float? = null,
    /** The style's exact or at-least line height (with [lineSpacing] null), from the first style in the chain that spaces its lines. */
    val lineHeightPt: Float? = null, val lineExact: Boolean = true,
    /** The first borders and shading along the chain (null: none). */
    val borders: ParagraphBorders? = null, val shading: Long? = null,
) {
    fun sameAs(o: ResolvedStyle): Boolean =
        fontFamily == o.fontFamily && sizePt == o.sizePt && bold == o.bold && italic == o.italic && color == o.color &&
            align == o.align && spaceBeforePt == o.spaceBeforePt && spaceAfterPt == o.spaceAfterPt && lineSpacing == o.lineSpacing &&
            lineHeightPt == o.lineHeightPt && lineExact == o.lineExact &&
            indentLeftPt == o.indentLeftPt && firstLineIndentPt == o.firstLineIndentPt && indentRightPt == o.indentRightPt &&
            borders == o.borders && shading == o.shading
}

/** The document's paragraph styles, keyed by style id in file order (OOXML `styles.xml`). */
class StyleSheet {
    val styles: LinkedHashMap<String, StyleDef> = LinkedHashMap()
    /** Document defaults (OOXML `docDefaults`): the root every basedOn chain ends in. */
    var defaultSizePt: Float = 11f
    var defaultFontFamily: String? = null

    operator fun get(id: String?): StyleDef? {
        if (id == null) return null
        return styles[id] ?: styles.values.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
    fun put(def: StyleDef) { styles[def.id] = def }

    /** The id this document uses for a built-in style: by id, else by Word's canonical name (zh-CN files call heading 1 "1"). */
    fun idOf(style: ParagraphStyle): String =
        get(style.styleId)?.id ?: styles.values.firstOrNull { it.name.equals(style.wordName, ignoreCase = true) }?.id ?: style.styleId

    /** The built-in style a file style id stands for (by id or canonical name); NORMAL for foreign styles. */
    fun styleFor(id: String?): ParagraphStyle {
        val def = get(id) ?: return ParagraphStyle.fromId(id)
        return ParagraphStyle.entries.firstOrNull { it.styleId.equals(def.id, ignoreCase = true) }
            ?: ParagraphStyle.entries.firstOrNull { it.wordName.equals(def.name, ignoreCase = true) }
            ?: ParagraphStyle.NORMAL
    }

    /** The definition behind [style] as this document has it (the built-in default when the sheet lacks it); never inserts. */
    fun defOf(style: ParagraphStyle): StyleDef = get(idOf(style)) ?: builtin(style)

    /** Resolve [id] through its basedOn chain (cycle-safe) down to the document defaults. */
    fun resolve(id: String?): ResolvedStyle {
        var fontFamily: String? = null; var sizePt: Float? = null; var bold: Boolean? = null; var italic: Boolean? = null; var color: Long? = null
        var align: TextAlign? = null; var before: Float? = null; var after: Float? = null; var line: Float? = null
        var height: Float? = null; var exact = true; var lineSet = false
        var left: Float? = null; var first: Float? = null; var right: Float? = null
        var borders: ParagraphBorders? = null; var shading: Long? = null
        var cur = get(id) ?: ParagraphStyle.entries.firstOrNull { it.styleId.equals(id, ignoreCase = true) }?.let { builtin(it) }
        val seen = HashSet<String>()
        while (cur != null && seen.size < 16 && seen.add(cur.id.lowercase())) {
            if (fontFamily == null) fontFamily = cur.fontFamily
            if (sizePt == null) sizePt = cur.sizePt
            if (bold == null) bold = cur.bold
            if (italic == null) italic = cur.italic
            if (color == null) color = cur.color
            if (align == null) align = cur.align
            if (before == null) before = cur.spaceBeforePt
            if (after == null) after = cur.spaceAfterPt
            // Line spacing is one property (Word's w:spacing line and lineRule): a multiple, or an exact or least height.
            if (!lineSet && (cur.lineSpacing != null || cur.lineHeightPt != null)) { line = cur.lineSpacing; height = cur.lineHeightPt; exact = cur.lineExact; lineSet = true }
            if (left == null) left = cur.indentLeftPt
            if (first == null) first = cur.firstLineIndentPt
            if (right == null) right = cur.indentRightPt
            if (borders == null) borders = cur.borders
            if (shading == null) shading = cur.shading
            cur = get(cur.basedOn)
        }
        return ResolvedStyle(fontFamily ?: defaultFontFamily, sizePt ?: defaultSizePt, bold ?: false, italic ?: false, color, align, before, after, line, left, first, right,
            height, exact, borders?.takeIf { !it.none }, shading)
    }
    fun resolve(style: ParagraphStyle): ResolvedStyle = resolve(idOf(style))

    fun assignFrom(other: StyleSheet) {
        styles.clear(); other.styles.values.forEach { put(it.copyOf()) }
        defaultSizePt = other.defaultSizePt; defaultFontFamily = other.defaultFontFamily
    }
    fun copyOf(): StyleSheet = StyleSheet().also { it.assignFrom(this) }
    fun sameAs(o: StyleSheet): Boolean {
        if (defaultSizePt != o.defaultSizePt || defaultFontFamily != o.defaultFontFamily || styles.size != o.styles.size) return false
        val a = styles.values.iterator(); val b = o.styles.values.iterator()
        while (a.hasNext()) if (!a.next().sameAs(b.next())) return false
        return true
    }

    companion object {
        /** The built-in style [d] is (by id or Word's canonical name), or null: one of the document's own. */
        fun builtinOf(d: StyleDef): ParagraphStyle? =
            ParagraphStyle.entries.firstOrNull { it.styleId.equals(d.id, ignoreCase = true) || it.wordName.equals(d.name, ignoreCase = true) }

        /** Default definition of a built-in style (what a new document starts with). */
        fun builtin(style: ParagraphStyle): StyleDef = when (style) {
            ParagraphStyle.NORMAL -> StyleDef("Normal", "Normal")
            ParagraphStyle.TITLE -> StyleDef("Title", "Title", basedOn = "Normal", sizePt = 28f, bold = true)
            ParagraphStyle.HEADING1 -> StyleDef("Heading1", "heading 1", basedOn = "Normal", sizePt = 20f, bold = true, outlineLevel = 0)
            ParagraphStyle.HEADING2 -> StyleDef("Heading2", "heading 2", basedOn = "Normal", sizePt = 16f, bold = true, outlineLevel = 1)
            ParagraphStyle.HEADING3 -> StyleDef("Heading3", "heading 3", basedOn = "Normal", sizePt = 13f, bold = true, outlineLevel = 2)
            ParagraphStyle.QUOTE -> StyleDef("Quote", "Quote", basedOn = "Normal", italic = true)
        }
        fun defaults(): StyleSheet = StyleSheet().also { s -> ParagraphStyle.entries.forEach { s.put(builtin(it)) } }
    }
}

/**
 * Run-level (character) formatting. A null value means "inherit from the
 * paragraph style / document default", which lets us avoid writing redundant
 * properties back to disk.
 */
data class CharacterFormat(
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var strikethrough: Boolean = false,
    var superscript: Boolean = false,
    var subscript: Boolean = false,
    /** Track-changes: this run was inserted while 修订 was on (session-level). */
    var inserted: Boolean = false,
    /** Track-changes: this run was deleted while 修订 was on — shown struck through,
     *  kept until the change is accepted (removed) or rejected (un-marked). */
    var deleted: Boolean = false,
    /** Track-changes: this run's formatting was changed while 修订 was on — shown red
     *  (a format revision); cleared on accept/reject. */
    var formatChanged: Boolean = false,
    /** Small caps (OOXML `w:smallCaps`). */
    var smallCaps: Boolean = false,
    /** Character spacing in points (OOXML `w:spacing` in rPr). Null = normal. */
    var letterSpacingPt: Float? = null,
    /** Hyperlink target URL, if this run is a link. Null = not a link. */
    var link: String? = null,
    /** Point size, e.g. 11f. Null = inherit. */
    var fontSizePt: Float? = null,
    /** Font family name, e.g. "Calibri". Null = inherit. */
    var fontFamily: String? = null,
    /** Foreground colour as 0xAARRGGBB. Null = inherit (usually black). */
    var color: Long? = null,
    /** Highlight colour as 0xAARRGGBB. Null = none. */
    var highlight: Long? = null,
    /** Track-changes: who made the revision this run carries (OOXML `w:author`) and when (epoch ms). */
    var revAuthor: String? = null,
    var revDate: Long? = null,
    /**
     * The equation this run spells part of (an inline `m:oMath`), shared by all its runs and compared
     * by identity; null for ordinary text. Typing never takes it on.
     */
    var math: InlineMath? = null,
    /**
     * The field this run shows the result of (a caption's number, a cross-reference: Word's SEQ, REF, PAGEREF), shared by all
     * the runs of its result and compared by identity; null for ordinary text. Typing never takes it on.
     */
    var field: DocField? = null,
    /**
     * Track-changes: how the run looked before its format revision (Word's `w:rPrChange`), which rejecting the revision
     * brings back; null when not known (a revision read from ODT, .doc or RTF) or the run has none. Never changed in place.
     */
    var before: CharacterFormat? = null,
) {
    fun copyOf(): CharacterFormat = copy()

    /** Only how the text looks: no revision, equation or field — what a format revision remembers. */
    fun look(): CharacterFormat = CharacterFormat(
        bold = bold, italic = italic, underline = underline, strikethrough = strikethrough, superscript = superscript,
        subscript = subscript, smallCaps = smallCaps, letterSpacingPt = letterSpacingPt, link = link, fontSizePt = fontSizePt,
        fontFamily = fontFamily, color = color, highlight = highlight,
    )

    /** Takes [o]'s look (as [look] gives it), keeping this run's revision, equation and field. */
    fun takeLook(o: CharacterFormat) {
        bold = o.bold; italic = o.italic; underline = o.underline; strikethrough = o.strikethrough; superscript = o.superscript
        subscript = o.subscript; smallCaps = o.smallCaps; letterSpacingPt = o.letterSpacingPt; link = o.link
        fontSizePt = o.fontSizePt; fontFamily = o.fontFamily; color = o.color; highlight = o.highlight
    }

    /** Overwrites every field with [o]'s (for a `val` format that must take another's values). */
    fun assignFrom(o: CharacterFormat) {
        bold = o.bold; italic = o.italic; underline = o.underline; strikethrough = o.strikethrough
        superscript = o.superscript; subscript = o.subscript; inserted = o.inserted; deleted = o.deleted
        formatChanged = o.formatChanged; smallCaps = o.smallCaps; letterSpacingPt = o.letterSpacingPt; link = o.link
        fontSizePt = o.fontSizePt; fontFamily = o.fontFamily; color = o.color; highlight = o.highlight
        revAuthor = o.revAuthor; revDate = o.revDate; math = o.math; field = o.field; before = o.before
    }
}

/** Where a tracking lock read from ODT keeps LibreOffice's `RedlineProtectionKey`, and its value for a lock without a password. */
const val ODF_TRACK_KEY = "odf:RedlineProtectionKey"
const val NO_KEY = "AQ=="

/** Paragraph-level formatting. */
/** A paragraph mark's tracked change: [inserted] (else deleted), by [author] at [date]. */
data class RevisionMark(val inserted: Boolean, val author: String? = null, val date: Long? = null)

data class ParagraphFormat(
    var align: TextAlign = TextAlign.LEFT,
    var style: ParagraphStyle = ParagraphStyle.NORMAL,
    /** Preserves the original style id when [style] resolved to NORMAL as a fallback. */
    var rawStyleId: String? = null,
    var listKind: ListKind = ListKind.NONE,
    var listLevel: Int = 0,
    /** Which list the item belongs to ([Document.lists]); 0: the plain bullets or numbers, which restart after anything else. */
    var listId: Int = 0,
    /** Space before/after the paragraph, in points. */
    var spaceBeforePt: Float = 0f,
    var spaceAfterPt: Float = 8f,
    /** Left indent in points. */
    var indentLeftPt: Float = 0f,
    /**
     * The first line's indent from the left indent, in points: positive indents it (首行缩进 — two
     * characters in most Chinese documents), negative hangs it out (悬挂缩进: a list, a bibliography).
     */
    var firstLineIndentPt: Float = 0f,
    /** Right indent in points. */
    var indentRightPt: Float = 0f,
    /** The paragraph's own tab stops (OOXML `w:tabs`, ODF `style:tab-stops`); the document's default ones apply past them. */
    var tabStops: List<TabStop> = emptyList(),
    /** Line spacing multiplier (1.0 = single, 1.5, 2.0...). */
    var lineSpacing: Float = 1.15f,
    /**
     * Word's exact or at-least line spacing (`w:lineRule="exact"` / `"atLeast"`, .doc's LSPD in twips, ODF's
     * `fo:line-height` / `style:line-height-at-least` as lengths): every line this tall whatever its letters or the
     * line grid ([lineExact]), or at least this tall; null: [lineSpacing] times its single lines.
     */
    var lineHeightPt: Float? = null,
    var lineExact: Boolean = true,
    /**
     * A section break at this paragraph: the section it closes uses this page setup, the content
     * after it starts a new one. This is OOXML's shape (`w:pPr/w:sectPr`), and it rides with the
     * paragraph, so editing, undo and copy move the break with the text. The final section's setup
     * is [Document.page].
     */
    var sectionBreak: PageSetup? = null,
    /**
     * A tracked change of the paragraph's mark itself (Word's `w:pPr/w:rPr/w:ins` or `w:del`): an inserted mark goes on
     * Reject, a deleted one on Accept — joining the paragraph to the next, so a whole paragraph added or removed goes.
     */
    var revisionMark: RevisionMark? = null,
    /** Word's "keep with next" (`w:keepNext`): null = the style's own (Heading 1–3 keep with what follows). */
    var keepWithNext: Boolean? = null,
    /** "Keep lines together" (`w:keepLines`): the paragraph does not break across pages. */
    var keepTogether: Boolean = false,
    /** "Page break before" (`w:pageBreakBefore`): the paragraph always starts a page. */
    var pageBreakBefore: Boolean = false,
    /** Widow/orphan control (`w:widowControl`): no lone first or last line at a page edge (Word's default). */
    var widowControl: Boolean = true,
    /** Its lines keep to the section's line grid ([PageSetup.linePitchPt]; Word's `w:snapToGrid`, on unless turned off). */
    var snapToGrid: Boolean = true,
    /** Word's paragraph borders (Home ▸ Borders; `w:pBdr`), null: none — see [ParagraphBorders] and ParagraphBoxes. */
    var borders: ParagraphBorders? = null,
    /** Word's paragraph shading (Home ▸ Shading; `w:shd` fill), null: none. */
    var shading: Long? = null,
    /** Word's "Suppress line numbers" (`w:suppressLineNumbers`, ODF's `text:number-lines="false"`): its lines are neither numbered nor counted. */
    var suppressLineNumbers: Boolean = false,
    /** Word's drop cap (Insert ▸ Drop Cap; docx's `w:framePr w:dropCap`, ODF's `style:drop-cap`), null: none — see [DropCap]. */
    var dropCap: DropCap? = null,
) {
    fun copyOf(): ParagraphFormat = copy(sectionBreak = sectionBreak?.copy())

    /** Takes [o]'s formatting — a pasted paragraph's, Word's paragraph mark — keeping this paragraph's own section break. */
    fun assignFrom(o: ParagraphFormat) {
        align = o.align; style = o.style; rawStyleId = o.rawStyleId
        listKind = o.listKind; listLevel = o.listLevel; listId = o.listId
        spaceBeforePt = o.spaceBeforePt; spaceAfterPt = o.spaceAfterPt
        indentLeftPt = o.indentLeftPt; firstLineIndentPt = o.firstLineIndentPt; indentRightPt = o.indentRightPt
        tabStops = o.tabStops; lineSpacing = o.lineSpacing; lineHeightPt = o.lineHeightPt; lineExact = o.lineExact
        keepWithNext = o.keepWithNext; keepTogether = o.keepTogether; pageBreakBefore = o.pageBreakBefore
        widowControl = o.widowControl; snapToGrid = o.snapToGrid
        borders = o.borders; shading = o.shading; suppressLineNumbers = o.suppressLineNumbers
    }
}

/**
 * Word's drop cap: the paragraph's first [length] characters set large, [lines] lines tall — dropped into the text, its
 * first lines indented past them, or [inMargin] beside it in the left margin — [distancePt] from the text, in
 * [fontFamily] (null: the text's own). Word keeps them in a frame paragraph of their own (`w:framePr w:dropCap`); here
 * they stay the paragraph's text, so editing, the caret and Find see them where they are. See DropCaps.
 */
data class DropCap(
    val inMargin: Boolean = false,
    val lines: Int = 3,
    val distancePt: Float = 0f,
    val fontFamily: String? = null,
    val length: Int = 1,
) {
    companion object {
        /** Word's "Lines to drop" runs 1 to 10. */
        const val MAX_LINES = 10
    }
}

/** A contiguous span of text sharing one [CharacterFormat]. */
data class TextRun(
    var text: String,
    val format: CharacterFormat = CharacterFormat(),
) {
    fun copyOf(): TextRun = TextRun(text, format.copyOf())
}

/**
 * One end of a bookmark (Word's `w:bookmarkStart` / `w:bookmarkEnd`, ODF's `text:bookmark-start` /
 * `-end`): bookmark [name] starts ([end] false) or ends here, before character [offset] of the
 * paragraph holding the mark. A bookmark is a named place in the text — where a link within the
 * document (`#name`) or a cross-reference goes; its ends may sit in different paragraphs, or both at
 * one place (a point). Names starting with "_" are Word's hidden ones (`_Toc…`, `_GoBack`).
 */
data class BookmarkMark(val name: String, var offset: Int, val end: Boolean)

/**
 * A paragraph: an ordered list of runs plus paragraph formatting. Carries a
 * stable [id] so comments can anchor to it across deep copies (undo snapshots).
 */
class Paragraph(
    val runs: MutableList<TextRun> = mutableListOf(),
    val format: ParagraphFormat = ParagraphFormat(),
    val id: Long = nextId(),
    /** The bookmark ends in this paragraph, in text order (by [BookmarkMark.offset]; a point's start before its end). */
    val bookmarks: MutableList<BookmarkMark> = mutableListOf(),
) {
    /** Concatenated plain text of every run. */
    fun plainText(): String = buildString { runs.forEach { append(it.text) } }

    /** True when the paragraph has no visible text. */
    fun isEmpty(): Boolean = runs.all { it.text.isEmpty() }

    /** True for a manual page-break paragraph (its only text is [PAGE_BREAK]); checked without building the text. */
    fun isPageBreak(): Boolean {
        var len = 0
        for (r in runs) len += r.text.length
        return len == PAGE_BREAK.length && plainText() == PAGE_BREAK
    }

    fun copyOf(): Paragraph =
        Paragraph(runs.map { it.copyOf() }.toMutableList(), format.copyOf(), id, bookmarks.mapTo(ArrayList()) { it.copy() })

    companion object {
        private var counter = 0L
        fun nextId(): Long = ++counter

        /** Convenience: a paragraph holding a single run of [text]. */
        fun of(text: String, format: CharacterFormat = CharacterFormat(), pFormat: ParagraphFormat = ParagraphFormat()) =
            Paragraph(mutableListOf(TextRun(text, format)), pFormat)
    }
}

/** Page geometry, stored in points (1 inch = 72 pt; Word's twips = pt * 20). */
data class PageSetup(
    var widthPt: Float = 612f,   // 8.5"
    var heightPt: Float = 792f,  // 11"
    var marginTopPt: Float = 72f,
    var marginBottomPt: Float = 72f,
    var marginLeftPt: Float = 72f,
    var marginRightPt: Float = 72f,
    /** Text columns per page (1 = normal, 2/3… = 分栏; OOXML `w:cols`), up to [PageMetrics.MAX_COLUMNS]. */
    var columns: Int = 1,
    /** The space between columns, in points (OOXML `w:cols@w:space`, Word's default half an inch; ODF's `fo:column-gap`). */
    var columnGapPt: Float = 36f,
    /** Whether a line stands between the columns (Word's "Line between", OOXML `w:cols@w:sep`, ODF's `style:column-sep`). */
    var columnLine: Boolean = false,
    /** How far the header's top sits below the page's top edge, and the footer's foot above its bottom edge (OOXML `w:header` / `w:footer`). */
    var headerPt: Float = 36f,
    var footerPt: Float = 36f,
    /** The section's pages are numbered from this (Word's `w:pgNumType@w:start`); null: on from the pages before. */
    var pageNumberStart: Int? = null,
    /** How its page numbers show: 1, i, I, a, A, 一 … (Word's `w:pgNumType@w:fmt`, ODF's page layout `style:num-format`). */
    var pageNumberFormat: NumFormat = NumFormat.DECIMAL,
    /**
     * The section's line grid (Word's `w:docGrid` of type lines or lines-and-characters, its `w:linePitch`; ODF's
     * line layout grid), in points: each body line takes a whole number of pitches — a Chinese document's 五号 lines
     * 15.6 pt apart. Null: no grid.
     */
    var linePitchPt: Float? = null,
    /** Word's line numbers in the margin (Layout ▸ Line Numbers; `w:lnNumType`); null: none. */
    var lineNumbers: LineNumbering? = null,
) {
    /** Takes [o]'s size, margins, columns (their gap and line), header and footer distances, page numbering, line grid and line numbers. */
    fun assignFrom(o: PageSetup) {
        widthPt = o.widthPt; heightPt = o.heightPt
        marginTopPt = o.marginTopPt; marginBottomPt = o.marginBottomPt; marginLeftPt = o.marginLeftPt; marginRightPt = o.marginRightPt
        columns = o.columns; columnGapPt = o.columnGapPt; columnLine = o.columnLine; headerPt = o.headerPt; footerPt = o.footerPt
        pageNumberStart = o.pageNumberStart; pageNumberFormat = o.pageNumberFormat
        linePitchPt = o.linePitchPt
        lineNumbers = o.lineNumbers
    }

    /** What page [page] (1-based in the document) shows as its number, the section beginning at page [sectionFirst]. */
    fun pageNumberOf(page: Int, sectionFirst: Int): String =
        Lists.number(pageNumberStart?.let { it + (page - sectionFirst) } ?: page, pageNumberFormat)
}

/**
 * Block-level content: a document body is an ordered list of [Block]s, each
 * either a [ParagraphBlock] or a [TableBlock]. This mirrors OOXML, where
 * `<w:body>` interleaves `<w:p>` and `<w:tbl>`.
 */
sealed interface Block {
    fun copyOf(): Block
}

class ParagraphBlock(var paragraph: Paragraph) : Block {
    override fun copyOf(): Block = ParagraphBlock(paragraph.copyOf())
}

/**
 * A text box (Word's `wps` text box, ODF's `draw:text-box`): its own paragraphs in a framed box.
 * Kept in the flow right after the block it was anchored to; [widthPt] / [heightPt] are the frame
 * size when known (null = as wide as the page, as tall as the text).
 *
 * A drawn shape (Word's `wps` shapes — rectangles, arrows, stars, lines — ODF's custom shapes) is
 * a box with a [shape]: its geometry, fill, outline, dashes and a line's arrowheads (the shape's
 * own text box is unused: the text is [paragraphs], none for a shape without text).
 */
class TextBoxBlock(
    val paragraphs: MutableList<Paragraph> = mutableListOf(Paragraph.of("")),
    var widthPt: Float? = null,
    var heightPt: Float? = null,
    var shape: SlideShape? = null,
    /** Where it stands when it floats (anchored to the paragraph before it); null: in the text's flow. */
    var floating: FloatPosition? = null,
) : Block {
    override fun copyOf(): Block = TextBoxBlock(paragraphs.map { it.copyOf() }.toMutableList(), widthPt, heightPt, shape?.copyOf(), floating?.copy())
}

class TableBlock(val table: Table) : Block {
    override fun copyOf(): Block = TableBlock(table.copyOf())
}

/**
 * An inline image, rendered as its own block (centered). [data] holds the raw
 * encoded bytes (PNG/JPEG); [mime] is the content type. Display size is in points: the size of
 * the part the crop leaves. The byte array is shared across copies (images aren't edited in place).
 */
class ImageBlock(
    val data: ByteArray,
    val mime: String,
    var widthPt: Float,
    var heightPt: Float,
    override var cropL: Float = 0f, override var cropT: Float = 0f, override var cropR: Float = 0f, override var cropB: Float = 0f,
    /** Its outer shadow (Word's picture styles cast one), or none. */
    var shadow: ShapeShadow? = null,
    /** Where it stands when it floats (anchored to the paragraph before it); null: in line, centred in its column. */
    var floating: FloatPosition? = null,
    /** Mirrored left to right, upside down. */
    var flipH: Boolean = false,
    var flipV: Boolean = false,
    /** Its alternative text (Word's Alt Text, docx `wp:docPr@descr`, ODF `svg:desc`): what a screen reader says for it. */
    var altText: String = "",
    /** Marked decorative: a screen reader passes it by (Word's "Mark as decorative"). */
    var decorative: Boolean = false,
) : Block, Croppable {
    override fun copyOf(): Block = ImageBlock(data, mime, widthPt, heightPt, cropL, cropT, cropR, cropB, shadow, floating?.copy(), flipH, flipV, altText, decorative)
}

/** A table: rows of cells. Each cell holds its own paragraphs. */
/**
 * A chart in the flow of the text (Word's chart in a drawing, a chart object in ODF), [widthPt] ×
 * [heightPt] on the page, drawn as the slides and sheets draw theirs; centred in its column.
 */
class ChartBlock(val chart: SlideChart, var widthPt: Float, var heightPt: Float) : Block {
    override fun copyOf(): Block = ChartBlock(chart.copyOf(), widthPt, heightPt)

    companion object {
        /** Word's size for a chart inserted in a document: 6 × 3.5 inches. */
        const val DEFAULT_W = 432f
        const val DEFAULT_H = 252f
    }
}

/**
 * One side of a table cell's border as a document draws it: its line's kind — solid ([LineStyle.THIN]),
 * double, dotted, dashed, dash-dot … (a sheet's kinds; the width is this one's) —, its width in points and
 * its colour (null: automatic, black).
 */
data class CellBorder(val style: LineStyle = LineStyle.THIN, val widthPt: Float = 0.5f, val color: Long? = null) {
    /** How thick it draws: a double line's two lines and the gap between, each [widthPt]; else [widthPt]. */
    val thicknessPt: Float get() = if (style == LineStyle.DOUBLE) widthPt * 3f else widthPt
}

/**
 * Word's paragraph borders (`w:pBdr`): each side's line (null: none), [between] the line between paragraphs of one box,
 * and how far each stands from the text, points (Word's `w:space`, 0–31; its Box setting's 1, 1, 4 and 4). The top and
 * bottom lines take their room above and below the text; the side lines stand out beside it, the text keeping its
 * indents. Consecutive paragraphs with the same borders, shading and indents are one box (see ParagraphBoxes).
 */
data class ParagraphBorders(
    val top: CellBorder? = null,
    val bottom: CellBorder? = null,
    val left: CellBorder? = null,
    val right: CellBorder? = null,
    val between: CellBorder? = null,
    val spaceTop: Float = 1f,
    val spaceBottom: Float = 1f,
    val spaceLeft: Float = 4f,
    val spaceRight: Float = 4f,
) {
    val none: Boolean get() = top == null && bottom == null && left == null && right == null && between == null

    companion object {
        /** Word's Box: [line] on every side. */
        fun box(line: CellBorder) = ParagraphBorders(line, line, line, line)
    }
}

/** When Word's line numbers start again: at each page, at each section, or never. */
enum class LineNumberRestart { PAGE, SECTION, CONTINUOUS }

/**
 * Word's line numbers (Layout ▸ Line Numbers ▸ Line Numbering Options): the first number, which numbers show (every
 * [countBy]th — all lines are counted), when they start again, and how far their right edge stands from the text
 * (points; null: Word's Auto, a quarter inch).
 */
data class LineNumbering(
    val start: Int = 1,
    val countBy: Int = 1,
    val restart: LineNumberRestart = LineNumberRestart.PAGE,
    val distancePt: Float? = null,
)

/** Which pages a document's page borders go round (Word's Apply to: whole document, first page only, all except first). */
enum class PageBorderPages { ALL, FIRST, NOT_FIRST }

/**
 * Word's page borders: each side's line (null: none), how far each stands from the page's edge — or, [fromText], from the
 * text's margin — in points, a shadow on the right and bottom ([shadow], Word's "Shadow" setting), and which pages.
 * Word's default: 24 pt from the edge of the page.
 */
data class PageBorders(
    val top: CellBorder? = null,
    val bottom: CellBorder? = null,
    val left: CellBorder? = null,
    val right: CellBorder? = null,
    val spaceTop: Float = 24f,
    val spaceBottom: Float = 24f,
    val spaceLeft: Float = 24f,
    val spaceRight: Float = 24f,
    val fromText: Boolean = false,
    val shadow: Boolean = false,
    val pages: PageBorderPages = PageBorderPages.ALL,
) {
    val none: Boolean get() = top == null && bottom == null && left == null && right == null

    /** Whether page [index] (0-based in the document) shows them. */
    fun shows(index: Int): Boolean = !none && when (pages) {
        PageBorderPages.ALL -> true
        PageBorderPages.FIRST -> index == 0
        PageBorderPages.NOT_FIRST -> index > 0
    }

    /**
     * Where the lines stand on a page of [pg], in points: the left, top, right and bottom lines' centres — from the page's
     * edges by their spaces, or out from the text's margins (a side without a line: as if a hairline were there).
     */
    fun box(pg: PageSetup): FloatArray {
        fun w(l: CellBorder?) = l?.thicknessPt ?: 0f
        return if (!fromText) floatArrayOf(
            spaceLeft + w(left) / 2f, spaceTop + w(top) / 2f,
            pg.widthPt - spaceRight - w(right) / 2f, pg.heightPt - spaceBottom - w(bottom) / 2f,
        ) else floatArrayOf(
            pg.marginLeftPt - spaceLeft - w(left) / 2f, pg.marginTopPt - spaceTop - w(top) / 2f,
            pg.widthPt - pg.marginRightPt + spaceRight + w(right) / 2f, pg.heightPt - pg.marginBottomPt + spaceBottom + w(bottom) / 2f,
        )
    }

    companion object {
        /** A box of [line] on all four sides, Word's default distance from the page's edge. */
        fun box(line: CellBorder, shadow: Boolean = false) = PageBorders(line, line, line, line, shadow = shadow)
    }
}

/** A table cell's four borders, each null where it has none. */
data class CellBorders(val top: CellBorder? = null, val bottom: CellBorder? = null, val left: CellBorder? = null, val right: CellBorder? = null) {
    val none: Boolean get() = top == null && bottom == null && left == null && right == null

    companion object {
        fun all(line: CellBorder?) = CellBorders(line, line, line, line)
    }
}

class Table(
    val rows: MutableList<TableRow> = mutableListOf(),
    /** Every cell's sides drawn in [gridLine] (where a cell has no borders of its own); false: none. */
    var borders: Boolean = true,
    /** Relative column weights (one per column); used for proportional widths. */
    val columnWidths: MutableList<Float> = mutableListOf(),
    /** Where it stands when it floats (Word's positioned table); null: in the text's flow, as wide as its column. */
    var floating: FloatPosition? = null,
    /** The table's own width in points, floating or in the text (null: its column's; never wider than the column). */
    var widthPt: Float? = null,
    /** The line a bordered table's grid draws. */
    var gridLine: CellBorder = CellBorder(),
    /** In the text, a table narrower than its column: where it stands across it (Word's `w:jc`, ODF's `table:align`). */
    var align: TextAlign = TextAlign.LEFT,
    /** …and, standing left, how far in from the column's edge in points (Word's `w:tblInd`; may be negative). */
    var indentPt: Float = 0f,
) {
    val columnCount: Int get() = rows.maxOfOrNull { it.cells.size } ?: 0

    /** What [cell]'s sides draw: its own borders, else the table's grid (or nothing). */
    fun bordersOf(cell: TableCell): CellBorders = cell.borders ?: if (borders) CellBorders.all(gridLine) else CellBorders()

    /**
     * Takes each cell's borders as [each] (row, cell) works them out: one line everywhere is the table's
     * grid of it, none anywhere no borders, any other way each cell its own (cells a merge covers aside).
     */
    fun adoptBorders(each: (Int, Int) -> CellBorders) {
        val sides = ArrayList<CellBorder?>()
        rows.forEachIndexed { r, row -> row.cells.forEachIndexed { c, cell -> if (!cell.vMerged) each(r, c).let { sides += listOf(it.top, it.bottom, it.left, it.right) } } }
        val first = sides.firstOrNull()
        when {
            sides.all { it == null } -> { borders = false; rows.forEach { row -> row.cells.forEach { it.borders = null } } }
            first != null && sides.all { it == first } -> { borders = true; gridLine = first; rows.forEach { row -> row.cells.forEach { it.borders = null } } }
            else -> { borders = false; rows.forEachIndexed { r, row -> row.cells.forEachIndexed { c, cell -> cell.borders = each(r, c) } } }
        }
    }

    /** Each cell takes the borders it draws now as its own (before some of them change). */
    fun ownBorders() {
        rows.forEach { row -> row.cells.forEach { it.borders = bordersOf(it) } }
        borders = false
    }

    /** The grid column cell [ci] of row [ri] starts at. */
    fun gridColOf(ri: Int, ci: Int): Int = rows[ri].cells.take(ci).sumOf { it.gridSpan.coerceAtLeast(1) }

    /** Row [ri]'s cell starting at grid column [col] (null: none starts there). */
    fun cellAtGrid(ri: Int, col: Int): Int? {
        var g = 0
        rows.getOrNull(ri)?.cells?.forEachIndexed { i, c -> if (g == col) return i; g += c.gridSpan.coerceAtLeast(1) }
        return null
    }

    /**
     * What row [ri]'s cell [ci] draws along its sides on the page: its borders — for a cell a vertical merge
     * covers, the merge's first cell's — but none across the merge (the first cell no bottom, a covered one
     * no top, and a bottom only where the merge ends).
     */
    fun drawnSides(ri: Int, ci: Int): CellBorders {
        val cell = rows[ri].cells[ci]
        val b = bordersOf(anchorOf(ri, ci))
        if (!cell.vMerged) return if (cell.rowSpan > 1) b.copy(bottom = null) else b
        val col = gridColOf(ri, ci)
        val goesOn = rows.getOrNull(ri + 1)?.let { next -> cellAtGrid(ri + 1, col)?.let { next.cells[it].vMerged } } == true
        return b.copy(top = null, bottom = if (goesOn) null else b.bottom)
    }

    /** The cell a vertical merge over row [ri]'s cell [ci] starts from (the cell itself when it is not covered). */
    fun anchorOf(ri: Int, ci: Int): TableCell {
        val cell = rows[ri].cells[ci]
        if (!cell.vMerged) return cell
        val col = gridColOf(ri, ci)
        for (r in ri - 1 downTo 0) {
            val above = rows[r].cells[cellAtGrid(r, col) ?: break]
            if (!above.vMerged) return above
        }
        return cell
    }

    /** Weight for column [col], defaulting to 1f. */
    fun weightOf(col: Int): Float = columnWidths.getOrElse(col) { 1f }

    /** How many leading rows are header rows (only a leading run repeats across pages, as in Word). */
    fun headerRowCount(): Int = rows.takeWhile { it.header }.size

    fun copyOf(): Table = Table(rows.map { it.copyOf() }.toMutableList(), borders, columnWidths.toMutableList(), floating?.copy(), widthPt, gridLine, align, indentPt)

    companion object {
        /** Build an empty [rows] x [cols] table of blank cells with equal columns. */
        fun of(rowCount: Int, cols: Int): Table = Table(
            MutableList(rowCount) { TableRow(MutableList(cols) { TableCell() }) },
            columnWidths = MutableList(cols) { 1f },
        )
    }
}

class TableRow(
    val cells: MutableList<TableCell> = mutableListOf(),
    /** A header row (Word's "repeat as header row", `w:tblHeader`): leading header rows repeat when the table continues on the next page. */
    var header: Boolean = false,
    /** Its height in points (Word's `w:trHeight`, ODF's row height): at least that, or — [exactHeight] — just that; null: its content's. */
    var heightPt: Float? = null,
    var exactHeight: Boolean = false,
) {
    fun copyOf(): TableRow = TableRow(cells.map { it.copyOf() }.toMutableList(), header, heightPt, exactHeight)
}

class TableCell(
    val content: MutableList<Paragraph> = mutableListOf(Paragraph.of("")),
    /** Cell background fill as 0xAARRGGBB (OOXML `w:shd`). Null = none. */
    var shade: Long? = null,
    /** Number of grid columns this cell spans (horizontal merge, OOXML `w:gridSpan`). */
    var gridSpan: Int = 1,
    /** Vertical merge (OOXML `w:vMerge`): rows this cell spans (anchor cell only). */
    var rowSpan: Int = 1,
    /** True when this cell is covered by a vertical merge above (renders empty). */
    var vMerged: Boolean = false,
    /** Pictures inside the cell, shown under its paragraphs (OOXML inline drawings in the cell). */
    val images: MutableList<ImageBlock> = mutableListOf(),
    /** Its own borders (Word's `w:tcBorders`, ODF's cell style borders); null: the table's grid ([Table.bordersOf]). */
    var borders: CellBorders? = null,
) {
    fun copyOf(): TableCell = TableCell(content.map { it.copyOf() }.toMutableList(), shade, gridSpan, rowSpan, vMerged,
        images.map { it.copyOf() as ImageBlock }.toMutableList(), borders)
}

/** A review comment anchored to a paragraph by its stable [targetId]. */
/**
 * A review comment anchored to a paragraph. Replies are comments whose [parentId] is the
 * parent's [id] (OOXML commentsExtended `paraIdParent`); [resolved] maps to `w15:done`.
 */
class Comment(
    var author: String, var text: String, var targetId: Long,
    val id: Long = Paragraph.nextId(), var parentId: Long? = null,
    var dateMs: Long = System.currentTimeMillis(), var resolved: Boolean = false,
) {
    fun copyOf(): Comment = Comment(author, text, targetId, id, parentId, dateMs, resolved)
}

/**
 * A live table-of-contents block: the document's current headings — or, with [caption], a table of figures: the
 * captions numbered by that sequence ("Figure", "Table"; Word's `TOC \c "Figure"`, ODF's illustration index).
 */
class TocBlock(val caption: String? = null) : Block {
    override fun copyOf(): Block = TocBlock(caption)
}

/**
 * One table-of-contents line: a heading paragraph, its level (0 = Heading 1) and its number when it has one ("1.2",
 * "第一章") — or, in a table of figures ([figure]), a caption, read as it is ("Figure 2 Costs").
 */
class TocEntry(val paragraph: Paragraph, val level: Int, val label: String? = null, val figure: Boolean = false) {
    val text: String get() = label?.let { "$it ${paragraph.plainText()}" } ?: paragraph.plainText()
}

/** A live bibliography block; renders [Document.sources]. */
class BibliographyBlock : Block {
    override fun copyOf(): Block = BibliographyBlock()
}

/** A footnote (app-level): auto-numbered by list order. */
/** An endnote: like a footnote, but collected at the end of the document; anchored by a superscript `[i]`, `[ii]`… marker. */
class Endnote(var text: String) {
    fun copyOf(): Endnote = Endnote(text)
}

/** Lower-case roman numerals — the marker style endnotes use (`[i]`, `[iv]`), distinct from footnotes' `[1]`. */
object Roman {
    private val digits = listOf(1000 to "m", 900 to "cm", 500 to "d", 400 to "cd", 100 to "c", 90 to "xc", 50 to "l", 40 to "xl", 10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i")
    private val canonical = Regex("^m{0,3}(cm|cd|d?c{0,3})(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})$")

    fun of(n: Int): String {
        if (n <= 0 || n > 3999) return n.toString()
        var rest = n
        return buildString { for ((v, s) in digits) while (rest >= v) { append(s); rest -= v } }
    }

    /** The value of a canonical numeral (either case), or null for anything else ("civil" is not a number). */
    fun parse(s: String): Int? {
        val t = s.lowercase()
        if (t.isEmpty() || !canonical.matches(t)) return null
        var total = 0
        var i = 0
        while (i < t.length) {
            val two = if (i + 1 < t.length) digits.firstOrNull { it.second == t.substring(i, i + 2) } else null
            if (two != null) { total += two.first; i += 2; continue }
            val one = digits.firstOrNull { it.second == t.substring(i, i + 1) } ?: return null
            total += one.first; i++
        }
        return total
    }

    /** Endnote markers inside a run: `[i]`, `[xiv]` — only canonical numerals count. */
    val marker: Regex = Regex("""\[([ivxlcdm]+)]""")
}

class Footnote(var text: String) {
    fun copyOf(): Footnote = Footnote(text)
}

/** A bibliography source for citations. */
class Source(var author: String, var title: String, var year: String) {
    fun copyOf(): Source = Source(author, title, year)
}

/**
 * A resolved location of a paragraph within the document, so structural edits
 * (split/merge/insert/delete) can act on the right container regardless of
 * whether the paragraph is top-level or inside a table cell.
 */
sealed class ParaLoc {
    abstract val paragraph: Paragraph
}

class TopLoc(val doc: Document, val blockIndex: Int, override val paragraph: Paragraph) : ParaLoc()
class CellLoc(val cell: TableCell, val indexInCell: Int, override val paragraph: Paragraph) : ParaLoc()
class BoxLoc(val box: TextBoxBlock, val indexInBox: Int, override val paragraph: Paragraph) : ParaLoc()

/**
 * A whole document: ordered block content plus page setup.
 */
class Document(
    val blocks: MutableList<Block> = mutableListOf(),
    val page: PageSetup = PageSetup(),
    /** Original on-disk path, if loaded from a file. */
    var sourcePath: String? = null,
    /** Source format so "Save" can round-trip to the same kind. */
    var sourceFormat: DocFormat = DocFormat.DOCX,
    /** Page header / footer (single paragraph each). Null = none. */
    var header: Paragraph? = null,
    var footer: Paragraph? = null,
    /** Review comments, anchored to paragraphs by id. */
    val comments: MutableList<Comment> = mutableListOf(),
    /** Footnotes (app-level), numbered by order. */
    val footnotes: MutableList<Footnote> = mutableListOf(),
    /** Endnotes (app-level), numbered by order and printed after the body. */
    val endnotes: MutableList<Endnote> = mutableListOf(),
    /** Bibliography sources for citations. */
    val sources: MutableList<Source> = mutableListOf(),
    /** Page background colour as 0xAARRGGBB (OOXML `w:background`). Null = white. */
    var pageColor: Long? = null,
    /** Text watermark behind the page (app-level). Null = none. */
    var watermark: String? = null,
    /** The pages' borders (Word's Design ▸ Page Borders, OOXML `w:pgBorders`, ODF's page layout borders); null: none. */
    var pageBorders: PageBorders? = null,
    /** Paragraph style definitions (OOXML `styles.xml`). */
    val styles: StyleSheet = StyleSheet.defaults(),
    /** Header / footer used on even pages when [differentOddEven] (OOXML `w:type="even"`). */
    var evenHeader: Paragraph? = null,
    var evenFooter: Paragraph? = null,
    /** Header / footer of the first page when [differentFirstPage] (OOXML `w:type="first"` + `w:titlePg`). */
    var firstHeader: Paragraph? = null,
    var firstFooter: Paragraph? = null,
    /** Word's "different odd & even pages" (settings `w:evenAndOddHeaders`). */
    var differentOddEven: Boolean = false,
    /** Word's "different first page" (`w:titlePg`). */
    var differentFirstPage: Boolean = false,
    /** How far apart the default tab stops are (Word's `w:defaultTabStop`, ODF's `style:tab-stop-distance`). */
    var defaultTabPt: Float = 36f,
    /**
     * Pictures in the headers and footers (a letterhead's logo), by header or footer: each floating where it
     * stands on every page that header or footer shows on.
     */
    val hfPictures: MutableMap<HfSlot, MutableList<ImageBlock>> = mutableMapOf(),
    /** The lists' numbering, by [ParagraphFormat.listId] (see [Lists]). */
    val lists: MutableMap<Int, ListDef> = mutableMapOf(),
) : HasProps {
    /** A new list id, not yet used. */
    fun newListId(): Int = (lists.keys.maxOrNull() ?: 0) + 1

    /** The header's ([header]) or footer's pictures shown on 1-based [pageNumber] (the variant [headerFor] / [footerFor] pick). */
    fun hfPicturesFor(pageNumber: Int, header: Boolean): List<ImageBlock> = hfPictures[hfSlotFor(pageNumber, header)].orEmpty()

    /** Which header or footer 1-based [pageNumber] shows: first-page, even or default. */
    fun hfSlotFor(pageNumber: Int, header: Boolean): HfSlot = when {
        differentFirstPage && pageNumber == 1 -> if (header) HfSlot.FIRST_HEADER else HfSlot.FIRST_FOOTER
        differentOddEven && pageNumber % 2 == 0 -> if (header) HfSlot.EVEN_HEADER else HfSlot.EVEN_FOOTER
        else -> if (header) HfSlot.HEADER else HfSlot.FOOTER
    }

    /** The header shown on 1-based [pageNumber]: first-page, even or default variant. */
    fun headerFor(pageNumber: Int): Paragraph? = when {
        differentFirstPage && pageNumber == 1 -> firstHeader
        differentOddEven && pageNumber % 2 == 0 -> evenHeader
        else -> header
    }
    fun footerFor(pageNumber: Int): Paragraph? = when {
        differentFirstPage && pageNumber == 1 -> firstFooter
        differentOddEven && pageNumber % 2 == 0 -> evenFooter
        else -> footer
    }
    /** True when any page shows a header. */
    val hasAnyHeader: Boolean get() = header != null || (differentOddEven && evenHeader != null) || (differentFirstPage && firstHeader != null)

    /** The page setup in force for the block at [index] — the first section break at or after it. */
    fun setupAt(index: Int): PageSetup {
        for (i in index.coerceAtLeast(0) until blocks.size) {
            val b = blocks.getOrNull(i)
            if (b is ParagraphBlock) b.paragraph.format.sectionBreak?.let { return it }
        }
        return page
    }

    /** The document split into sections: each is its own run of blocks with its own page setup. */
    fun sections(): List<Pair<List<Block>, PageSetup>> {
        val out = ArrayList<Pair<List<Block>, PageSetup>>()
        var current = ArrayList<Block>()
        for (b in blocks) {
            current.add(b)
            val brk = (b as? ParagraphBlock)?.paragraph?.format?.sectionBreak
            if (brk != null) { out.add(current to brk); current = ArrayList() }
        }
        if (current.isNotEmpty() || out.isEmpty()) out.add(current to page)
        return out
    }

    /** True when the document has more than one section. */
    val hasSections: Boolean get() = blocks.any { (it as? ParagraphBlock)?.paragraph?.format?.sectionBreak != null }

    /** Convenience for readers/builders that only add top-level paragraphs. */
    fun addParagraph(p: Paragraph) { blocks.add(ParagraphBlock(p)) }

    /** Enumerate every paragraph in reading order, with its container location. */
    fun locations(): List<ParaLoc> {
        val out = ArrayList<ParaLoc>()
        blocks.forEachIndexed { bi, block ->
            when (block) {
                is ParagraphBlock -> out.add(TopLoc(this, bi, block.paragraph))
                is TableBlock -> block.table.rows.forEach { row ->
                    row.cells.forEach { cell ->
                        cell.content.forEachIndexed { ci, p -> out.add(CellLoc(cell, ci, p)) }
                    }
                }
                is TextBoxBlock -> block.paragraphs.forEachIndexed { pi, p -> out.add(BoxLoc(block, pi, p)) }
                is ImageBlock -> { /* images contain no editable paragraphs */ }
                is EquationBlock -> { /* a displayed equation is edited as a whole */ }
                is ChartBlock -> { /* a chart holds no paragraphs */ }
                is TocBlock -> { /* generated content, no editable paragraphs */ }
                is IndexBlock -> { /* generated content, no editable paragraphs */ }
                is BibliographyBlock -> { /* generated content, no editable paragraphs */ }
            }
        }
        return out
    }

    /** Every paragraph in reading order. */
    fun paragraphs(): List<Paragraph> = locations().map { it.paragraph }

    /**
     * The table of contents' entries in document order: Heading 1–3 paragraphs anywhere (tables
     * and text boxes included), as Word's default `TOC \o "1-3"` collects them. Title is left out.
     */
    fun tocEntries(block: TocBlock? = null): List<TocEntry> {
        block?.caption?.let { label ->
            val locs = locations()
            return Fields.placed(this).filter { it.field.kind == "SEQ" && it.field.target.equals(label, ignoreCase = true) && !it.field.has("\\h") }
                .map { locs[it.para].paragraph }.distinct().map { TocEntry(it, 0, figure = true) }
        }
        val labels = if (blocks.any { b -> b is ParagraphBlock && b.paragraph.format.listKind == ListKind.NUMBER && b.paragraph.format.style != ParagraphStyle.NORMAL }) PageMetrics.listLabels(this) else emptyMap()
        return locations().mapNotNull { loc ->
            val level = when (loc.paragraph.format.style) {
                ParagraphStyle.HEADING1 -> 0
                ParagraphStyle.HEADING2 -> 1
                ParagraphStyle.HEADING3 -> 2
                else -> return@mapNotNull null
            }
            TocEntry(loc.paragraph, level, labels[loc.paragraph]?.takeIf { loc.paragraph.format.listKind == ListKind.NUMBER })
        }
    }

    fun copyOf(): Document = Document(
        blocks.map { it.copyOf() }.toMutableList(),
        page.copy(),
        sourcePath,
        sourceFormat,
        header?.copyOf(),
        footer?.copyOf(),
        comments.map { it.copyOf() }.toMutableList(),
        footnotes.map { it.copyOf() }.toMutableList(),
        endnotes.map { it.copyOf() }.toMutableList(),
        sources.map { it.copyOf() }.toMutableList(),
        pageColor,
        watermark,
        pageBorders,
        styles.copyOf(),
        evenHeader?.copyOf(), evenFooter?.copyOf(), firstHeader?.copyOf(), firstFooter?.copyOf(),
        differentOddEven, differentFirstPage, defaultTabPt,
        hfPictures.mapValuesTo(mutableMapOf()) { (_, pics) -> pics.map { it.copyOf() as ImageBlock }.toMutableList() },
        lists.toMutableMap(),
    ).also { it.props = props; it.pageCache = pageCache; it.coverPage = coverPage; it.trackRevisions = trackRevisions; it.trackLock = trackLock }

    /**
     * Word's cover page (Insert ▸ Cover Page): the document starts with one — its blocks up to the first page break (see
     * CoverPages) — which a new cover replaces and Remove Current Cover Page takes away. docx keeps it in a "Cover Pages"
     * content control.
     */
    var coverPage: Boolean = false

    /** Track Changes is on in the document (Word's `w:trackRevisions`, ODF's `text:track-changes`): it opens recording. */
    var trackRevisions: Boolean = false
    /**
     * Word's Lock Tracking (`w:documentProtection w:edit="trackedChanges"`): Track Changes can't be turned off and the
     * changes can't be accepted or rejected. Null: not locked; else the lock's password attributes as the file wrote them
     * (empty: none), written back as they were.
     */
    var trackLock: Map<String, String>? = null
    /**
     * The lock keeps a password: a Word hash (`w:hash` / `w:hashValue`, not empty — LibreOffice writes empty ones for a lock
     * without a password) or LibreOffice's `RedlineProtectionKey` other than its no-password `AQ==`.
     */
    val trackLockHasPassword: Boolean get() = trackLock?.let { l ->
        !l["w:hash"].isNullOrEmpty() || !l["w:hashValue"].isNullOrEmpty() || (l[ODF_TRACK_KEY] ?: NO_KEY) != NO_KEY
    } == true

    /** The file's properties (File → Info). */
    override var props: DocProps = DocProps()

    /**
     * The page each paragraph (by id) started on as the canvas last laid the document out, numbered as its section
     * numbers it ("iii", "1"): a table of contents is written with these — the field's last result, which readers
     * that do not lay the document out again (LibreOffice converting it, previews) show. Not part of the document's
     * content: not compared, not undone; empty until the canvas has laid it out.
     */
    var pageCache: Map<Long, String> = emptyMap()

    companion object {
        /** A blank single-paragraph document, used for "New". */
        fun blank(): Document = Document(mutableListOf(ParagraphBlock(Paragraph.of(""))))

        /** A new blank document as Word starts one where the reader is (Letter or A4, the language's margins). */
        fun newBlank(): Document = blank().also { it.page.assignFrom(PagePresets.regionalPage()) }
    }
}

/** A header or footer: Word's default one, its even pages' and its first page's. */
enum class HfSlot(val header: Boolean) { HEADER(true), FOOTER(false), EVEN_HEADER(true), EVEN_FOOTER(false), FIRST_HEADER(true), FIRST_FOOTER(false) }

/** Supported on-disk formats. */
enum class DocFormat(val extension: String) {
    DOCX("docx"),
    DOC("doc");

    companion object {
        fun fromPath(path: String): DocFormat =
            if (path.substringAfterLast('.', "").equals("doc", ignoreCase = true)) DOC else DOCX
    }
}
