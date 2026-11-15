package com.therealityteam.aeropages.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.TextAlign

/**
 * Bridges the format-agnostic document model into Compose text styling. Kept
 * separate from the model so the model stays free of UI dependencies.
 */
object TextStyling {

    /** Resolve a [CharacterFormat] (with a paragraph-style fallback size) to a [SpanStyle]. */
    /** Text the review display leaves out: no width, no ink. */
    private val HIDDEN = SpanStyle(fontSize = 0.01.sp, color = Color.Transparent, letterSpacing = 0.sp)

    /** The ground a field's result shows on (see [spanOf]). */
    private val FIELD_SHADING = Color(0x1F000000)

    fun spanOf(format: CharacterFormat, fallbackSizePt: Float): SpanStyle {
        // A change the review display leaves out takes no room (it stays in the text: offsets do not move).
        if (!com.therealityteam.aeropages.model.Markups.shows(format)) return HIDDEN
        val marked = com.therealityteam.aeropages.model.Markups.marks(format)
        // A cross-reference links to what it names but reads as the text round it, as in Word.
        val link = format.link != null && format.field == null
        val decorations = mutableListOf<TextDecoration>()
        if (format.underline || (format.inserted && marked) || link) decorations.add(TextDecoration.Underline)
        // Track-changes deletions and manual strikethrough both draw a line through.
        if (format.strikethrough || (format.deleted && marked)) decorations.add(TextDecoration.LineThrough)
        val script = format.superscript || format.subscript
        val sizePt = (format.fontSizePt ?: fallbackSizePt) * (if (script) 0.72f else 1f)
        // Track-changes revisions (insert + delete) and hyperlinks show a distinct colour.
        val accentColor = when {
            link -> Color(0xFF1155CC)
            marked && (format.deleted || format.inserted || format.formatChanged) -> revisionColor(format.revAuthor) // track changes: one colour per reviewer
            else -> null
        }
        return SpanStyle(
            color = accentColor ?: format.color?.let { Color(it) } ?: Color.Unspecified,
            fontSize = sizePt.sp,
            // No explicit weight when not bold, so a bold base style (a slide master title) shows through.
            fontWeight = if (format.bold) FontWeight.Bold else null,
            fontStyle = if (format.italic) FontStyle.Italic else FontStyle.Normal,
            fontFamily = format.fontFamily?.let { familyOf(it) },   // null = inherit the base style's (master) family
            textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
            // A field's result on a light grey ground (LibreOffice's field shading), so it reads as one.
            background = format.highlight?.let { Color(it) } ?: if (format.field != null) FIELD_SHADING else Color.Unspecified,
            letterSpacing = format.letterSpacingPt?.sp ?: androidx.compose.ui.unit.TextUnit.Unspecified,
            fontFeatureSettings = if (format.smallCaps) "smcp" else null,
            baselineShift = when {
                format.superscript -> BaselineShift.Superscript
                format.subscript -> BaselineShift.Subscript
                else -> null
            },
        )
    }

    /**
     * Build a rich [AnnotatedString] for a whole paragraph from its runs; on a page ([tabs] given) each
     * tab a space stretched to its stop (see TabSpacing).
     */
    fun annotatedOf(paragraph: Paragraph, tabs: TabSpacing.Spec? = null, onLink: ((String) -> Unit)? = null): AnnotatedString {
        val fallback = paragraph.format.style.sizePt
        val stops = tabs?.let { TabSpacing.tabs(paragraph, it) }.orEmpty()
        val whole = if (stops.isEmpty()) "" else paragraph.plainText()
        var tab = 0
        var at = 0
        return buildAnnotatedString {
            paragraph.runs.forEach { run ->
                // With [onLink], a link's text takes a tap itself (the slide show follows it); taps elsewhere go by.
                val link = run.format.link?.takeIf { onLink != null }
                if (link != null) pushLink(androidx.compose.ui.text.LinkAnnotation.Clickable(link) { onLink!!(link) })
                withStyle(spanOf(run.format, fallback)) {
                    if (stops.isEmpty() || '\t' !in run.text) append(run.text)
                    else for (c in run.text) {
                        if (c == '\t' && tab < stops.size) {
                            val em = TabSpacing.letterSpacingEm(stops[tab++].advance, run.format, fallback, tabs!!.fontScale, TabSpacing.atEdge(whole, at))
                            withStyle(SpanStyle(letterSpacing = em.em)) { append(' ') }
                        } else append(c)
                        at++
                    }
                }
                if (link != null) pop()
                if (stops.isEmpty() || '\t' !in run.text) at += run.text.length
            }
            CommentShading.apply(this, paragraph.id, length)
            SearchShading.apply(this, paragraph.id, length)
            hideDropCap(this, paragraph, length)
        }
    }

    /** [paragraph]'s drop cap letters, styled as its first letter (in the drop cap's font) at [sizeSp]. */
    fun dropCapText(paragraph: Paragraph, sizeSp: Float): AnnotatedString {
        val fmt = com.therealityteam.aeropages.model.DropCaps.format(paragraph)
        return buildAnnotatedString {
            withStyle(spanOf(fmt, paragraph.format.style.sizePt).copy(fontSize = sizeSp.sp, background = Color.Transparent, baselineShift = null)) {
                append(com.therealityteam.aeropages.model.DropCaps.text(paragraph))
            }
        }
    }

    /** A drop cap's letters take no room in the paragraph's own text: they are drawn large beside its first lines (see DropCaps). */
    private fun hideDropCap(b: AnnotatedString.Builder, paragraph: Paragraph, length: Int) {
        val n = com.therealityteam.aeropages.model.DropCaps.length(paragraph).coerceAtMost(length)
        if (n > 0) b.addStyle(HIDDEN, 0, n)
    }

    /**
     * Base [TextStyle] for the active editor field. Carries only paragraph-level
     * traits (alignment, default size); per-run character formatting is layered on
     * via [fieldTransformation] so inline formatting shows while editing.
     */
    fun editBaseStyle(paragraph: Paragraph, gridPt: Float? = null): TextStyle =
        TextStyle(
            color = Color.Black,
            fontSize = paragraph.format.style.sizePt.sp,
            textAlign = composeAlign(paragraph.format.align),
            // The first line's indent (or, hanging, the other lines'): beyond the paragraph's inset (see PageMetrics.indents).
            textIndent = com.therealityteam.aeropages.model.PageMetrics.indents(paragraph.format).let { i ->
                if (i[1] == 0f && i[2] == 0f) null else androidx.compose.ui.text.style.TextIndent(firstLine = i[1].sp, restLine = i[2].sp)
            },
            // Every line is one pitch tall (largest font × single × line spacing) — the same model
            // the PDF export uses, so both break pages alike and 1.5 / double spacing shows here.
            lineHeight = com.therealityteam.aeropages.model.PageMetrics.pitchPt(paragraph, gridPt).sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None),
            // Chinese characters in their language's forms: Japanese text in Japanese glyphs whatever the app's language.
            localeList = com.therealityteam.aeropages.model.HanLocale.of(paragraph)?.let { androidx.compose.ui.text.intl.LocaleList(it.toLanguageTag()) },
        )

    /**
     * The text a paragraph is measured with: its runs, or — for an empty paragraph — a single
     * space in its format, which is exactly one line of its own size tall (the canvas then draws
     * the empty paragraph, or its edit field, at that height).
     */
    fun displayTextOf(paragraph: Paragraph, tabs: TabSpacing.Spec? = null): AnnotatedString {
        if (!paragraph.isEmpty()) return annotatedOf(paragraph, tabs)
        val fallback = paragraph.format.style.sizePt
        val fmt = paragraph.runs.firstOrNull()?.format ?: CharacterFormat()
        return buildAnnotatedString { withStyle(spanOf(fmt, fallback)) { append(" ") } }
    }

    /**
     * A [VisualTransformation] that re-applies the paragraph's per-run [SpanStyle]s
     * onto the field's plain text using an identity offset mapping, so the active
     * paragraph renders mixed inline formatting (bold here, red there) while
     * remaining fully editable.
     */
    internal fun fieldTransformation(paragraph: Paragraph, tabs: TabSpacing.Spec? = null, shape: WrapShape? = null): VisualTransformation =
        VisualTransformation { text ->
            val styled = annotatedForField(text.text, paragraph, tabs)
            // Beside a floating object, its lines as the page laid them out (the breaks take no characters).
            TransformedText(if (shape == null) styled else shaped(styled, paragraph, shape), OffsetMapping.Identity)
        }

    /** [text] (a paragraph's, styled) set as [shape] lays its lines out beside floating objects, with [paragraph]'s own indents. */
    private fun shaped(text: AnnotatedString, paragraph: Paragraph, shape: WrapShape): AnnotatedString {
        val indent = editBaseStyle(paragraph).textIndent
        return shape.apply(text, indent?.firstLine ?: androidx.compose.ui.unit.TextUnit.Unspecified, indent?.restLine ?: androidx.compose.ui.unit.TextUnit.Unspecified)
    }

    /** [text] (a body paragraph's, styled) as the page [flow] laid it out: beside floating objects when it is. */
    internal fun wrappedText(text: AnnotatedString, paragraph: Paragraph, flow: CanvasFlow?): AnnotatedString =
        flow?.shapeOf(paragraph)?.let { shaped(text, paragraph, it) } ?: text

    /**
     * Build an [AnnotatedString] over [text] applying [paragraph]'s run formats positionally; on a
     * page ([tabs] given) each tab a space stretched to its stop, the length unchanged.
     */
    fun annotatedForField(text: String, paragraph: Paragraph, tabs: TabSpacing.Spec? = null): AnnotatedString {
        val fallback = paragraph.format.style.sizePt
        val stops = tabs?.takeIf { '\t' in text }?.let { TabSpacing.tabs(paragraph, it) }.orEmpty()
        return buildAnnotatedString {
            append(if (stops.isEmpty()) text else text.replace('\t', ' '))
            var pos = 0
            for (run in paragraph.runs) {
                val start = pos
                val end = (pos + run.text.length).coerceAtMost(text.length)
                if (start < end) addStyle(spanOf(run.format, fallback), start, end)
                pos = end
                if (pos >= text.length) break
            }
            CommentShading.apply(this, paragraph.id, text.length)
            SearchShading.apply(this, paragraph.id, text.length)
            hideDropCap(this, paragraph, text.length)
            if (stops.isNotEmpty()) {
                var tab = 0
                var at = 0
                for (run in paragraph.runs) {
                    for (c in run.text) {
                        if (at >= text.length) break
                        if (c == '\t' && text[at] == '\t' && tab < stops.size) {
                            val em = TabSpacing.letterSpacingEm(stops[tab++].advance, run.format, fallback, tabs!!.fontScale, TabSpacing.atEdge(text, at))
                            addStyle(SpanStyle(letterSpacing = em.em), at, at + 1)
                        }
                        at++
                    }
                }
            }
        }
    }

    fun composeAlign(align: TextAlign): ComposeTextAlign = when (align) {
        TextAlign.LEFT -> ComposeTextAlign.Start
        TextAlign.CENTER -> ComposeTextAlign.Center
        TextAlign.RIGHT -> ComposeTextAlign.End
        TextAlign.JUSTIFY -> ComposeTextAlign.Justify
    }

    /** Map common Word font names (incl. Chinese fonts) onto on-device generic families. */
    fun familyOf(name: String?): FontFamily = when {
        name == null -> FontFamily.Default
        // Serif Chinese faces (宋/仿宋/楷) and Western serifs.
        name.contains("宋", false) || name.contains("仿宋", false) || name.contains("楷", false) ||
            name.contains("Times", true) || name.contains("Cambria", true) ||
            name.contains("Georgia", true) || name.contains("Song", true) ||
            name.contains("Ming", true) || name.contains("Kai", true) || name.contains("Serif", true) -> FontFamily.Serif
        name.contains("Courier", true) || name.contains("Consolas", true) ||
            name.contains("Mono", true) -> FontFamily.Monospace
        name.contains("Comic", true) -> FontFamily.Cursive
        // Sans Chinese faces (黑体/雅黑/等线/思源黑) and everything else.
        else -> FontFamily.SansSerif
    }

    /** Curated colour palette for the text-colour and highlight pickers. */
    /**
     * Word-style reviewer colours: reviewers get palette colours in the order they are first
     * seen in this session (red stays for unattributed revisions), so two names never share a
     * colour until the palette is exhausted.
     */
    private val REVIEWER_COLORS = listOf(Color(0xFF1F6FD6), Color(0xFF1E8449), Color(0xFF8E44AD), Color(0xFFD35400), Color(0xFF00838F), Color(0xFFAD1457))
    private val reviewerSlots = LinkedHashMap<String, Int>()
    fun revisionColor(author: String?): Color {
        if (author.isNullOrBlank()) return Color(0xFFCC0000)
        val slot = reviewerSlots.getOrPut(author) { reviewerSlots.size }
        return REVIEWER_COLORS[slot % REVIEWER_COLORS.size]
    }

    val TEXT_COLORS: List<Long> = listOf(
        0xFF000000, 0xFF434343, 0xFF999999, 0xFFCC0000, 0xFFE69138,
        0xFFF1C232, 0xFF6AA84F, 0xFF45818E, 0xFF3D85C6, 0xFF674EA7, 0xFFA64D79,
    )

    val HIGHLIGHT_COLORS: List<Long> = listOf(
        0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFFFF00FF, 0xFFFFA500, 0xFFD3D3D3,
    )

    val FONT_FAMILIES: List<String> = listOf(
        // Common Chinese fonts first (most relevant for CJK documents).
        "宋体", "黑体", "微软雅黑", "楷体", "仿宋", "等线", "华文宋体", "华文黑体", "华文楷体", "思源黑体", "思源宋体",
        // Western fonts.
        "Calibri", "Arial", "Times New Roman", "Cambria", "Georgia",
        "Courier New", "Consolas", "Verdana",
    )

    val FONT_SIZES: List<Float> = listOf(8f, 9f, 10f, 11f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 36f, 48f, 72f)
}

/**
 * Commented text tinted as Word tints it: each paragraph's commented ranges by its id (a range across paragraphs a
 * part in each), the current comment's darker. The canvas works them out once a revision ([update]); the text builders
 * lay them on as backgrounds, which leave the text and its measure as they are.
 */
/** The current search's matches, shaded yellow on the page as Word's navigation pane shades them (the selected one shows as the selection). */
internal object SearchShading {
    private val TINT = Color(0x80FFE45C)
    @Volatile private var ranges: Map<Long, List<Pair<Int, Int>>> = emptyMap()
    private var key: Any? = null

    fun update(editor: com.therealityteam.aeropages.editor.DocumentEditor) {
        val k = Triple(System.identityHashCode(editor.document), editor.revision, editor.searchQuery to editor.searchOptions)
        if (k == key) return
        key = k
        val hits = if (editor.searchQuery.isEmpty()) emptyList() else editor.searchHits
        if (hits.isEmpty()) { ranges = emptyMap(); return }
        val locs = editor.document.locations()
        val out = HashMap<Long, MutableList<Pair<Int, Int>>>()
        for (h in hits) locs.getOrNull(h.para)?.paragraph?.let { out.getOrPut(it.id) { ArrayList() }.add(h.start to h.end) }
        ranges = out
    }

    fun apply(builder: androidx.compose.ui.text.AnnotatedString.Builder, paragraphId: Long, length: Int) {
        ranges[paragraphId]?.forEach { (a, b) ->
            val s = a.coerceIn(0, length); val e = b.coerceIn(0, length)
            if (e > s) builder.addStyle(SpanStyle(background = TINT), s, e)
        }
    }
}

internal object CommentShading {
    private val TINT = Color(0x40F2C94C)
    private val CURRENT = Color(0x90F2C94C)
    @Volatile private var ranges: Map<Long, List<Triple<Int, Int, Boolean>>> = emptyMap()
    private var key: Any? = null

    /** [shown]: whether a comment shows (Show Markup's Comments and reviewers); none tinted when it says none do. */
    fun update(doc: com.therealityteam.aeropages.model.Document, revision: Int, current: Long?, shown: (com.therealityteam.aeropages.model.Comment) -> Boolean = { true }, filter: Any? = null) {
        val k = listOf(System.identityHashCode(doc), revision, current, filter)
        if (k == key) return
        key = k
        val byId = doc.comments.associateBy { it.id }
        val found = com.therealityteam.aeropages.model.Bookmarks.commentRanges(doc).filterKeys { id -> byId[id]?.let(shown) != false }
        if (found.isEmpty()) { ranges = emptyMap(); return }
        val locs = doc.locations()
        val out = HashMap<Long, MutableList<Triple<Int, Int, Boolean>>>()
        for ((id, f) in found) for (i in f.startPara..f.endPara) {
            val p = locs.getOrNull(i)?.paragraph ?: continue
            val a = if (i == f.startPara) f.start else 0
            val b = if (i == f.endPara) f.end else p.runs.sumOf { it.text.length }
            if (b > a) out.getOrPut(p.id) { ArrayList() }.add(Triple(a, b, id == current))
        }
        ranges = out
    }

    fun apply(builder: androidx.compose.ui.text.AnnotatedString.Builder, paragraphId: Long, length: Int) {
        ranges[paragraphId]?.forEach { (a, b, current) ->
            val s = a.coerceIn(0, length); val e = b.coerceIn(0, length)
            if (e > s) builder.addStyle(SpanStyle(background = if (current) CURRENT else TINT), s, e)
        }
    }
}
