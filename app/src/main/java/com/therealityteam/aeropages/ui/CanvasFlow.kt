package com.therealityteam.aeropages.ui

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.therealityteam.aeropages.editor.PageFlow
import com.therealityteam.aeropages.editor.TableSlice
import com.therealityteam.aeropages.model.BoxEdge
import com.therealityteam.aeropages.model.ParagraphBoxes
import com.therealityteam.aeropages.model.Block
import com.therealityteam.aeropages.model.BibliographyBlock
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.PageMetrics
import com.therealityteam.aeropages.model.PageSetup
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TextBoxBlock
import com.therealityteam.aeropages.model.FloatPlacement
import com.therealityteam.aeropages.model.FloatWrap
import com.therealityteam.aeropages.model.TextId
import com.therealityteam.aeropages.model.Texts
import com.therealityteam.aeropages.model.TocBlock
import com.therealityteam.aeropages.model.TocEntry
import java.util.IdentityHashMap

/** What one page shows: its columns' pieces, the footnotes at its bottom, and what floats over it. */
internal class LaidOutPage(
    val setup: PageSetup,
    val frames: List<List<PageItem>>,
    val notes: List<Int>,
    /** Width of one text column, in pixels (the body width on a one-column page). */
    val columnPx: Int,
    val gapPx: Int,
    /** Pictures and text boxes behind or in front of the text, where they stand on this page. */
    val floats: List<PlacedFloat> = emptyList(),
)

/**
 * A floating picture or text box on its page: its box in pixels from the page's top-left, whether it is behind
 * the text, and what moving it means — its position for a new top-left (pixels) in the frames it counts from.
 */
internal class PlacedFloat(val block: Block, val x: Int, val y: Int, val w: Int, val h: Int, val behind: Boolean,
                           val moveTo: (Float, Float) -> com.therealityteam.aeropages.model.FloatPosition? = { _, _ -> null })

/** A piece of content on a page. */
internal sealed class PageItem {
    /** A body paragraph: lines [from, to) of it; [to] = -1 runs through the last line. */
    class Para(val block: ParagraphBlock, val from: Int = 0, val to: Int = -1) : PageItem() {
        val whole: Boolean get() = from == 0 && to == -1
    }
    /** Rows of a table; a floating one that moves with its paragraph keeps its place across ([x]) at its own width ([w]), pixels. */
    class TablePart(val block: TableBlock, val slice: TableSlice?, val x: Int? = null, val w: Int? = null) : PageItem()
    /** A picture in the text's flow; [x]: its left edge across the column (pixels) when it keeps a floating place, else centred. */
    class Picture(val block: ImageBlock, val x: Int? = null) : PageItem()
    class Formula(val block: com.therealityteam.aeropages.model.EquationBlock) : PageItem()
    class Chart(val block: com.therealityteam.aeropages.model.ChartBlock) : PageItem()
    class Box(val block: TextBoxBlock, val x: Int? = null) : PageItem()
    /** Generated text the reader cannot edit (a contents, bibliography or endnotes heading / entry). */
    class Generated(val paragraph: Paragraph, val from: Int = 0, val to: Int = -1) : PageItem()
    /** One table-of-contents line. */
    class TocLine(val entry: TocEntry) : PageItem()
    /** One line of an index ([key] tells it apart from its neighbours). */
    class IndexLine(val block: com.therealityteam.aeropages.model.IndexBlock, val line: com.therealityteam.aeropages.model.IndexEntries.Line, val key: String) : PageItem()
    /** Room a column leaves empty where its text steps over a floating object ([heightPx] tall). */
    class Gap(val heightPx: Int) : PageItem()
}

/** A body paragraph's piece maker, which names the paragraph (the flow may lay it out again beside floating objects). */
private class ParaSource(val paragraph: Paragraph, val make: (PageFlow.Piece) -> PageItem?) : (PageFlow.Piece) -> PageItem? {
    override fun invoke(pc: PageFlow.Piece): PageItem? = make(pc)
}

/** A paragraph measured as the canvas draws it (pixels). */
internal class ParaMetrics(
    val beforePx: Int,
    val afterPx: Int,
    /** Each line's bottom from the top of the text; the last one is the text's (rounded-up) height. */
    val bottoms: FloatArray,
    /** Offset just past each line's last character. */
    val lineEnds: IntArray,
    /** Footnote numbers anchored on each line, or null when there are none. */
    val notes: List<IntArray>?,
) {
    val lineCount: Int get() = bottoms.size
    val heightPx: Int get() = beforePx + bottoms.last().toInt() + afterPx

    private var flowItem: PageFlow.Lines? = null

    /** This paragraph as the page flow sees it, with its pagination options (made once per measurement). */
    fun flowItem(keepWithNext: Boolean, keepTogether: Boolean = false, widowControl: Boolean = true, breakBefore: Boolean = false): PageFlow.Lines =
        flowItem?.takeIf {
            it.keepWithNext == keepWithNext && it.keepTogether == keepTogether && it.widowControl == widowControl && it.breakBefore == breakBefore
        } ?: PageFlow.Lines(beforePx.toFloat(), bottoms, afterPx.toFloat(), keepWithNext, notes, keepTogether, widowControl, breakBefore)
            .also { flowItem = it }

    /** The line holding character [offset]. */
    fun lineOf(offset: Int): Int {
        for (i in lineEnds.indices) if (offset < lineEnds[i]) return i
        return lineEnds.lastIndex.coerceAtLeast(0)
    }
}

/**
 * Lays the document out the way the canvas draws it: every paragraph is measured with the same text,
 * style, width and pixel rounding the page uses (so page breaks fall between real lines and nothing
 * overflows a page), then [PageFlow] breaks the pages by the rules the PDF export uses too.
 * Measurements are cached per paragraph until its text, format or width changes.
 */
internal class CanvasFlow {
    private class Entry(val sig: Int, val width: Int, val density: Float, val fontScale: Float, val notes: Int, val metrics: ParaMetrics, val rowWidth: Int = 0, val drop: DropDraw? = null)

    /**
     * A paragraph's drop cap as [measure] set it: its letters ([text], styled at their size), where they stand from the
     * text's left (px; dropped at the lines' start, in the margin left of the text), the line whose baseline they sit
     * on ([baselineLine], counting on past the text's last line at [pitchPx] apart), the room the paragraph keeps below
     * its text when it has fewer lines than they drop, and — dropped — the shape of the lines indented past them.
     */
    internal class DropDraw(val text: androidx.compose.ui.text.AnnotatedString, val x: Float, val baselineLine: Int, val pitchPx: Float, val extraPx: Float, val shape: WrapShape?)

    private val cache = IdentityHashMap<Paragraph, Entry>()
    private val touched = IdentityHashMap<Paragraph, Boolean>()
    private val generated = HashMap<String, Paragraph>()
    private val generatedUsed = HashSet<String>()

    private lateinit var measurer: TextMeasurer
    private lateinit var density: Density
    private var footnoteCount = 0
    /** The document's default tab stops' distance (tabs are measured as the canvas shows them, see TabSpacing). */
    private var defaultTabPt = 36f

    /**
     * Progressive layout: a pass spends at most [budgetNanos] measuring new paragraphs; later ones
     * get an estimate (kept in [estimates] until they are measured) and [incomplete] asks the canvas
     * for another pass next frame, so opening a long document never blocks the UI. Paragraphs on the
     * pages in view ([priority]) are always measured, so what the reader sees is exact at once.
     */
    private var budgetNanos = Long.MAX_VALUE
    private var spentNanos = 0L
    private val estimates = IdentityHashMap<Paragraph, Entry>()

    /** Paragraphs the last pass only estimated (in reading order), with the row width they need. */
    private val pending = ArrayList<Pair<Paragraph, Int>>()
    private var pendingNext = 0
    private val priority: MutableSet<Paragraph> = java.util.Collections.newSetFromMap(IdentityHashMap())
    private var lastPages: List<LaidOutPage> = emptyList()

    /** How long the last full layout pass took (the canvas spaces refining passes by it). */
    var lastPassMs = 0L
        private set

    /** True when the last [layout] estimated some paragraphs; laying out again refines them. */
    var incomplete = false
        private set

    /** List labels of the body's and tables' paragraphs, as of the last [layout]. */
    var labels: Map<Paragraph, String> = emptyMap()
        private set

    // --- geometry in pixels (the canvas's dp values rounded exactly as its modifiers round them) ---

    fun px(pt: Float): Int = with(density) { pt.dp.roundToPx() }

    fun measure(p: Paragraph, rowWidthPx: Int): ParaMetrics {
        touched[p] = true
        val f = p.format
        val marker = px(PageMetrics.markerWidth(f, labels[p], PageMetrics.maxSizePt(p)))
        val inset = com.therealityteam.aeropages.model.PageMetrics.indents(f)
        val width = (rowWidthPx - px(inset[0]) - px(inset[3]) - marker).coerceAtLeast(1)
        val sig = signature(p)
        cache[p]?.let { e ->
            if (e.sig == sig && e.width == width && e.density == density.density && e.fontScale == density.fontScale && e.notes == footnoteCount) return e.metrics
        }
        if (p !in priority && spentNanos > budgetNanos) {
            incomplete = true
            pending.add(p to rowWidthPx)
            estimates[p]?.let { e ->
                if (e.sig == sig && e.width == width && e.density == density.density && e.fontScale == density.fontScale && e.notes == footnoteCount) return e.metrics
            }
            return estimate(p, width).also { estimates[p] = Entry(sig, width, density.density, density.fontScale, footnoteCount, it) }
        }
        measuredThisPass++
        val t0 = System.nanoTime()
        val r: TextLayoutResult = measurer.measure(
            TextStyling.displayTextOf(p, TabSpacing.Spec(defaultTabPt, rowWidthPx / density.density, density.fontScale)), TextStyling.editBaseStyle(p, grids[p]),
            constraints = Constraints(minWidth = width, maxWidth = width),
            skipCache = true,
        )
        spentNanos += System.nanoTime() - t0
        estimates.remove(p)
        rowWidthNow = rowWidthPx
        val drop = if (f.dropCap != null && com.therealityteam.aeropages.model.DropCaps.length(p) > 0) dropCapOf(p, r, width, rowWidthPx) else null
        val laid = drop?.second ?: r
        val n = laid.lineCount.coerceAtLeast(1)
        val bottoms = FloatArray(n) { if (it < laid.lineCount) laid.getLineBottom(it) else 0f }
        bottoms[n - 1] = laid.size.height.toFloat() + (drop?.first?.extraPx ?: 0f)
        val ends = IntArray(n) { if (it < laid.lineCount) laid.getLineEnd(it) else 0 }
        val anchors = PageMetrics.footnoteAnchors(p, footnoteCount)
        val notes = if (anchors.isEmpty()) null else {
            val per = Array(n) { ArrayList<Int>() }
            anchors.forEach { (offset, number) -> per[laid.getLineForOffset(offset).coerceIn(0, n - 1)].add(number) }
            per.map { it.toIntArray() }
        }
        val m = ParaMetrics(px(ParagraphBoxes.beforePt(f, edges[p])), px(ParagraphBoxes.afterPt(f, edges[p])), bottoms, ends, notes)
        cache[p] = Entry(sig, width, density.density, density.fontScale, footnoteCount, m, rowWidthNow, drop?.first)
        return m
    }

    /**
     * [p]'s drop cap set beside its text (laid out naturally in [natural], [width] px wide): the letters sized to run from
     * the first line's capitals to the dropped line's baseline (see DropCaps); dropped, each of the first lines measured
     * to fit the room they leave and set as a [WrapShape] — returned with the text laid out so.
     */
    private fun dropCapOf(p: Paragraph, natural: TextLayoutResult, width: Int, rowWidthPx: Int): Pair<DropDraw, TextLayoutResult> {
        val d = p.format.dropCap!!
        val pitch = (natural.getLineBottom(0) - natural.getLineTop(0)).coerceAtLeast(1f)
        val body = with(density) { com.therealityteam.aeropages.model.PageMetrics.maxSizePt(p).sp.toPx() }
        val capText = com.therealityteam.aeropages.model.DropCaps.text(p)
        val sizePx = com.therealityteam.aeropages.model.DropCaps.size(capText, d.lines, pitch, body)
        val cap = TextStyling.dropCapText(p, with(density) { sizePx.toSp() }.value)
        val capW = measurer.measure(cap, androidx.compose.ui.text.TextStyle.Default, softWrap = false, skipCache = true).getLineRight(0)
        val gap = px(d.distancePt).toFloat()
        val inset = com.therealityteam.aeropages.model.PageMetrics.indents(p.format)
        val firstPx = with(density) { inset[1].dp.toPx() }
        val restPx = with(density) { inset[2].dp.toPx() }
        if (d.inMargin) return DropDraw(cap, restPx - capW - gap, d.lines - 1, pitch, 0f, null) to natural
        val tabs = TabSpacing.Spec(defaultTabPt, rowWidthPx / density.density, density.fontScale)
        val text = TextStyling.displayTextOf(p, tabs)
        val style = TextStyling.editBaseStyle(p, grids[p])
        val plain = style.copy(textIndent = null, textAlign = androidx.compose.ui.text.style.TextAlign.Left)
        val indent = capW + gap
        val starts = ArrayList<Int>(); val lefts = ArrayList<Float>()
        var offset = 0
        val len = text.length
        while (offset < len && starts.size < d.lines) {
            val avail = (width - restPx - indent).toInt().coerceAtLeast(1)
            val laid = measurer.measure(text.subSequence(offset, len), plain, constraints = Constraints(minWidth = avail, maxWidth = avail), skipCache = true)
            val end = (offset + laid.getLineEnd(0)).coerceIn(offset + 1, len)
            // Every dropped line starts past the letters, the first line's own indent set aside (the letters stand at the paragraph's indent).
            starts.add(offset); lefts.add(with(density) { (indent + (if (offset == 0) restPx - firstPx else 0f)).toSp() }.value)
            offset = end
        }
        val shape = WrapShape(starts.toIntArray(), lefts.toFloatArray(), offset, len)
        val shown = shape.apply(text, style.textIndent?.firstLine ?: androidx.compose.ui.unit.TextUnit.Unspecified, style.textIndent?.restLine ?: androidx.compose.ui.unit.TextUnit.Unspecified)
        val laid = measurer.measure(shown, style, constraints = Constraints(minWidth = width, maxWidth = width), skipCache = true)
        val extra = ((d.lines - laid.lineCount).coerceAtLeast(0) * pitch)
        return DropDraw(cap, restPx, d.lines - 1, pitch, extra, shape) to laid
    }

    /** [p]'s drop cap as the last measure set it (null: none). */
    fun dropOf(p: Paragraph): DropDraw? = cache[p]?.drop

    /**
     * Measures paragraphs the last pass only estimated, for about [budgetMs]; true while some remain.
     * The canvas runs this between frames and lays out again with the results.
     */
    fun refineStep(budgetMs: Long): Boolean {
        val end = System.nanoTime() + budgetMs * 1_000_000L
        budgetNanos = Long.MAX_VALUE
        while (pendingNext < pending.size && System.nanoTime() < end) {
            val (p, width) = pending[pendingNext++]
            measure(p, width)
        }
        return pendingNext < pending.size
    }

    /** Stand-in metrics until [p] is measured: estimated line count at its real pitch and spacing. */
    private fun estimate(p: Paragraph, width: Int): ParaMetrics {
        val emPerPt = density.density * density.fontScale
        val lines = PageMetrics.estimateLines(p, width.toFloat(), emPerPt)
        val pitch = PageMetrics.pitchPt(p, grids[p]) * emPerPt
        val bottoms = FloatArray(lines) { (it + 1) * pitch }
        bottoms[lines - 1] = kotlin.math.ceil(bottoms[lines - 1])
        val len = p.runs.sumOf { it.text.length }
        val ends = IntArray(lines) { ((it + 1).toLong() * len / lines).toInt() }
        val anchors = PageMetrics.footnoteAnchors(p, footnoteCount)
        val notes = if (anchors.isEmpty()) null else {
            val per = Array(lines) { ArrayList<Int>() }
            anchors.forEach { (offset, number) -> per[(offset.toLong() * lines / len.coerceAtLeast(1)).toInt().coerceIn(0, lines - 1)].add(number) }
            per.map { it.toIntArray() }
        }
        return ParaMetrics(px(ParagraphBoxes.beforePt(p.format, edges[p])), px(ParagraphBoxes.afterPt(p.format, edges[p])), bottoms, ends, notes)
    }

    /** Cached lookup for a paragraph laid out this pass (null when it was not) — around what floats beside it, when it is. */
    fun metricsOf(p: Paragraph): ParaMetrics? = wrapped[p]?.metrics ?: cache[p]?.metrics

    /** A paragraph laid out around floating objects beside it: its lines' shape and its measurement so. */
    private class WrapEntry(val sig: Int, val width: Int, val top: Int, val sides: List<PageFlow.Side>, val shape: WrapShape?, val metrics: ParaMetrics)

    /** Laid out around floating objects, as the last [layout] placed them (a paragraph's own, where it starts). */
    private val wrapped = IdentityHashMap<Paragraph, WrapEntry>()
    /** Layouts around floating objects tried this pass (the flow may try a paragraph at a few places). */
    private val wrapTries = IdentityHashMap<Paragraph, MutableList<WrapEntry>>()

    private var pagesIndexed: List<LaidOutPage>? = null
    private val pageOfPara = IdentityHashMap<Paragraph, Int>()

    /** The page (1-based) [p] starts on as the last [layout] laid it out — a table's or a text box's paragraph, its block's — or null. */
    fun pageOf(p: Paragraph): Int? {
        if (pagesIndexed !== lastPages) {
            pageOfPara.clear()
            lastPages.forEachIndexed { pi, page ->
                page.frames.forEach { frame ->
                    frame.forEach { item ->
                        when (item) {
                            is PageItem.Para -> pageOfPara.putIfAbsent(item.block.paragraph, pi + 1)
                            is PageItem.TablePart -> item.block.table.rows.forEach { r -> r.cells.forEach { c -> c.content.forEach { pageOfPara.putIfAbsent(it, pi + 1) } } }
                            is PageItem.Box -> item.block.paragraphs.forEach { pageOfPara.putIfAbsent(it, pi + 1) }
                            else -> Unit
                        }
                    }
                }
                page.floats.forEach { f -> (f.block as? TextBoxBlock)?.paragraphs?.forEach { pageOfPara.putIfAbsent(it, pi + 1) } }
            }
            pagesIndexed = lastPages
        }
        return pageOfPara[p]
    }

    /** How [p] is set beside floating objects (null: across its column, as usual). */
    fun shapeOf(p: Paragraph): WrapShape? = wrapped[p]?.shape ?: cache[p]?.drop?.shape

    /**
     * [p] laid out with its first line's top at [top] (from the body's top, px) beside [sides], in a row [rowWidthPx] wide:
     * the lines an object stands beside each measured to fit the room it leaves (see [WrapShape]); null when none of its
     * lines meets one.
     */
    private fun wrap(p: Paragraph, rowWidthPx: Int, top: Float, sides: List<PageFlow.Side>): WrapEntry? {
        val natural = cache[p]?.metrics ?: return null
        // A paragraph with a drop cap keeps its own shape (its first lines beside the letters), not one beside floating objects.
        if (p.format.dropCap != null) return null
        val sig = signature(p)
        val key = kotlin.math.round(top).toInt()
        wrapTries[p]?.firstOrNull { it.sig == sig && it.width == rowWidthPx && it.top == key && it.sides == sides }?.let { return it }
        val f = p.format
        val label = labels[p]
        val marker = px(PageMetrics.markerWidth(f, label, PageMetrics.maxSizePt(p)))
        val inset = com.therealityteam.aeropages.model.PageMetrics.indents(f)
        val startX = px(inset[0]) + marker
        val endX = px(inset[3])
        val width = (rowWidthPx - startX - endX).coerceAtLeast(1)
        val tabs = TabSpacing.Spec(defaultTabPt, rowWidthPx / density.density, density.fontScale)
        val text = TextStyling.displayTextOf(p, tabs)
        val style = TextStyling.editBaseStyle(p, grids[p])
        val firstPx = with(density) { (inset[1]).dp.toPx() }
        val restPx = with(density) { (inset[2]).dp.toPx() }
        val pitch = natural.bottoms.first().coerceAtLeast(1f)
        fun span(k: Int): Pair<Float, Float> {
            val t = top + if (k == 0) 0f else if (k - 1 < natural.bottoms.size) natural.bottoms[k - 1] else natural.bottoms.last() + (k - natural.bottoms.size) * pitch
            return t to t + pitch
        }
        /** What the objects beside line [k] take from the text's left and right (px). */
        fun room(k: Int): Pair<Float, Float>? {
            val (a, b) = span(k)
            val met = sides.filter { it.bottom > a + 0.5f && it.top < b - 0.5f }
            if (met.isEmpty()) return null
            val l = met.maxOf { it.left } - startX
            val r = met.maxOf { it.right } - endX
            return if (l <= 0f && r <= 0f) null else l.coerceAtLeast(0f) to r.coerceAtLeast(0f)
        }
        val kStart = (0 until natural.lineCount).firstOrNull { room(it) != null } ?: return null
        val len = text.length
        var offset = if (kStart == 0) 0 else natural.lineEnds[kStart - 1].coerceIn(0, len)
        val starts = ArrayList<Int>(); val lefts = ArrayList<Float>()
        var k = kStart
        val plain = style.copy(textIndent = null)
        while (offset < len && k < kStart + 400) {
            val (l, r) = room(k) ?: break
            val indent = if (offset == 0) firstPx else restPx
            val avail = (width - l - r - indent).toInt()
            // Too little room beside it for a word: the line runs across (rare — the text keeps clear of a wide object).
            if (avail < pitch * 2) break
            val rest = text.subSequence(offset, len)
            val laid = measurer.measure(rest, plain.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Left), constraints = Constraints(minWidth = avail, maxWidth = avail), skipCache = true)
            val end = (offset + laid.getLineEnd(0)).coerceIn(offset + 1, len)
            // A centred or right-aligned line sits in the room beside the object, as it would across a narrower column.
            val spare = (avail - laid.getLineRight(0)).coerceAtLeast(0f)
            val shift = when (f.align) {
                com.therealityteam.aeropages.model.TextAlign.CENTER -> spare / 2f
                com.therealityteam.aeropages.model.TextAlign.RIGHT -> spare
                else -> 0f
            }
            starts.add(offset); lefts.add(with(density) { (l + shift).toSp() }.value)
            offset = end
            k++
        }
        if (starts.isEmpty()) return null
        val shape = WrapShape(starts.toIntArray(), lefts.toFloatArray(), offset, len,
            fromLeft = f.align == com.therealityteam.aeropages.model.TextAlign.CENTER || f.align == com.therealityteam.aeropages.model.TextAlign.RIGHT)
        val shown = shape.apply(text, style.textIndent?.firstLine ?: androidx.compose.ui.unit.TextUnit.Unspecified, style.textIndent?.restLine ?: androidx.compose.ui.unit.TextUnit.Unspecified)
        val r: TextLayoutResult = measurer.measure(shown, style, constraints = Constraints(minWidth = width, maxWidth = width), skipCache = true)
        val n = r.lineCount.coerceAtLeast(1)
        val bottoms = FloatArray(n) { if (it < r.lineCount) r.getLineBottom(it) else 0f }
        bottoms[n - 1] = r.size.height.toFloat()
        val ends = IntArray(n) { if (it < r.lineCount) r.getLineEnd(it) else 0 }
        val anchors = PageMetrics.footnoteAnchors(p, footnoteCount)
        val notes = if (anchors.isEmpty()) null else {
            val per = Array(n) { ArrayList<Int>() }
            anchors.forEach { (o, number) -> per[r.getLineForOffset(o).coerceIn(0, n - 1)].add(number) }
            per.map { it.toIntArray() }
        }
        val m = ParaMetrics(natural.beforePx, natural.afterPx, bottoms, ends, notes)
        return WrapEntry(sig, rowWidthPx, key, sides, shape, m).also { e -> wrapTries.getOrPut(p) { ArrayList() }.let { list -> if (list.size >= 6) list.removeAt(0); list.add(e) } }
    }

    /** Each body paragraph's section's line grid (as of the last [layout]); others keep to none. */
    private val grids = IdentityHashMap<Paragraph, Float>()

    /** The line grid [p]'s lines keep to — its section's, a body paragraph's — for measuring and drawing it alike. */
    fun gridOf(p: Paragraph): Float? = grids[p]

    /** Where each bordered or shaded paragraph sharing a box stands in it (as of the last [layout]); see ParagraphBoxes. */
    private val edges = IdentityHashMap<Paragraph, BoxEdge>()

    /** Where [p] stands in its box (null: alone, or not boxed) — its spacing and its lines are measured and drawn so. */
    fun edgeOf(p: Paragraph): BoxEdge? = edges[p]

    private fun signature(p: Paragraph): Int {
        // (How the tracked changes show changes the text's measure: part of it; so does where it stands in a box.)
        var h = (p.format.hashCode() * 31 + (grids[p]?.hashCode() ?: 0)) * 31 + com.therealityteam.aeropages.model.Markups.shown + com.therealityteam.aeropages.model.Markups.version * 7919
        h = 31 * h + (edges[p]?.hashCode() ?: 0)
        p.runs.forEach { r -> h = 31 * h + r.text.hashCode(); h = 31 * h + r.format.hashCode() }
        return h
    }

    /** A generated paragraph with a stable identity (so its measurement stays cached). */
    private fun gen(key: String, make: () -> Paragraph): Paragraph { generatedUsed.add(key); return generated.getOrPut(key, make) }

    private fun linesOf(m: ParaMetrics, keepWithNext: Boolean = false) = m.flowItem(keepWithNext)

    /** A body paragraph's flow item, with its own pagination options. */
    private fun linesOf(m: ParaMetrics, p: Paragraph) = m.flowItem(
        PageMetrics.keepsWithNext(p), p.format.keepTogether, p.format.widowControl, p.format.pageBreakBefore,
    )

    /** Row heights of [table] laid out [widthPx] wide, and the footnotes each row anchors. */
    fun tableRows(table: Table, widthPx: Int): Pair<FloatArray, List<IntArray>?> {
        val padH = px(PageMetrics.CELL_PAD_H) * 2
        val padV = px(PageMetrics.CELL_PAD_V) * 2
        val imgPad = px(PageMetrics.IMAGE_PAD) * 2
        val minRow = px(PageMetrics.ROW_MIN)
        var anyNotes = false
        val notes = ArrayList<IntArray>()
        val heights = FloatArray(table.rows.size) { ri ->
            val row = table.rows[ri]
            val widths = PageMetrics.cellWidths(table, row, widthPx)
            var rowH = maxOf(minRow, px(PageMetrics.rowMin(row)))
            val rowNotes = ArrayList<Int>()
            row.cells.forEachIndexed { ci, cell ->
                val inner = (widths[ci] - padH).coerceAtLeast(1)
                var h = padV
                if (!cell.vMerged) {
                    cell.content.forEach { p ->
                        val m = measure(p, inner)
                        h += m.heightPx
                        m.notes?.forEach { it.forEach(rowNotes::add) }
                    }
                    cell.images.forEach { img ->
                        val (_, ih) = PageMetrics.fitImage(img.widthPt, img.heightPt, inner / density.density)
                        h += imgPad + px(ih)
                    }
                }
                rowH = maxOf(rowH, h)
            }
            if (rowNotes.isNotEmpty()) anyNotes = true
            notes.add(rowNotes.toIntArray())
            // An exact height is the row's, whatever its content.
            (if (row.exactHeight && row.heightPt != null) px(row.heightPt!!) else rowH).toFloat()
        }
        return heights to (if (anyNotes) notes else null)
    }

    /** Height of a picture block in a column [widthPx] wide. */
    fun imageHeight(img: ImageBlock, widthPx: Int): Int {
        val (_, h) = PageMetrics.fitImage(img.widthPt, img.heightPt, widthPx / density.density)
        return px(PageMetrics.IMAGE_PAD) * 2 + px(h)
    }

    private class Typeset(val math: com.therealityteam.aeropages.model.MathNode, val size: Float, val width: Int, val box: com.therealityteam.aeropages.format.MathBox)
    private val equations = IdentityHashMap<com.therealityteam.aeropages.model.EquationBlock, Typeset>()
    private val mathFont by lazy { com.therealityteam.aeropages.format.PaintMathFont() }

    /** A displayed equation typeset for a column [widthPx] wide: at its size in the canvas's pixels, smaller when it would not fit. */
    fun equationBox(block: com.therealityteam.aeropages.model.EquationBlock, widthPx: Int): com.therealityteam.aeropages.format.MathBox {
        val size = with(density) { block.sizePt.sp.toPx() }
        equations[block]?.let { if (it.math === block.math && it.size == size && it.width == widthPx) return it.box }
        val box = com.therealityteam.aeropages.format.MathLayout.layoutFitting(block.math, mathFont, size, widthPx.toFloat())
        equations[block] = Typeset(block.math, size, widthPx, box)
        return box
    }

    /** Height of a displayed equation in a column [widthPx] wide, with the room above and below it. */
    fun equationHeight(block: com.therealityteam.aeropages.model.EquationBlock, widthPx: Int): Int =
        px(PageMetrics.EQUATION_PAD) * 2 + kotlin.math.ceil(equationBox(block, widthPx).height).toInt()

    /** Height of a chart block in a column [widthPx] wide: its size, narrowed to the column in proportion, and the room around it. */
    fun chartHeight(block: com.therealityteam.aeropages.model.ChartBlock, widthPx: Int): Int {
        val (_, h) = PageMetrics.fitImage(block.widthPt, block.heightPt, widthPx / density.density)
        return px(PageMetrics.IMAGE_PAD) * 2 + px(h)
    }

    /** Width of a text box in a column [widthPx] wide. */
    fun boxWidth(box: TextBoxBlock, widthPx: Int): Int = box.widthPt?.let { minOf(px(it), widthPx) } ?: widthPx

    /**
     * Pages of [doc]. [pageWidthPx] is how wide a page of a given setup is drawn (its own width, or the
     * window's in full-width mode; never more than the window).
     */
    private var tBlocks = 0L
    private var tFlow = 0L

    private var rowWidthNow = 0
    private var lastDoc: Document? = null
    private var fastHits = 0
    private var lastRevision = -1
    private var lastGeometry: Any? = null

    /** Paragraphs measured (not served from the cache) by the last [layout]. */
    private var measuredThisPass = 0

    /**
     * Lays out [doc]. [inView] are the page indexes on screen (from the previous layout): their
     * paragraphs are measured whatever the time budget.
     */
    fun layout(
        doc: Document, measurer: TextMeasurer, density: Density, inView: IntRange,
        geometry: Any, revision: Int, typing: com.therealityteam.aeropages.editor.DocumentEditor.TypingRun?,
        pageWidthPx: (PageSetup) -> Int,
    ): List<LaidOutPage> {
        this.measurer = measurer
        this.density = density
        this.defaultTabPt = doc.defaultTabPt
        if (doc !== lastDoc) {
            // Another document: its first pass gets the first-pass budget and nothing of the old one is kept.
            lastDoc = doc
            lastPages = emptyList()
            incomplete = false
            estimates.clear()
            cache.clear()
            wrapped.clear()
        }
        // Typing that keeps the paragraph's lines as they were changes no page: re-measure just that
        // paragraph and keep the pages (most keystrokes; a new line or anything else lays out again).
        if (!incomplete && lastPages.isNotEmpty() && geometry == lastGeometry && typing != null && typing.to == revision &&
            lastRevision >= typing.from && lastRevision <= typing.to && typing.paragraph !in wrapped) {
            val old = cache[typing.paragraph]
            if (lastRevision == revision) return lastPages
            if (old != null) {
                budgetNanos = Long.MAX_VALUE
                val t = System.nanoTime()
                val m = measure(typing.paragraph, old.rowWidth)
                if (sameShape(old.metrics, m)) {
                    lastRevision = revision
                    if (com.therealityteam.aeropages.BuildConfig.DEBUG && fastHits++ % 20 == 0) {
                        android.util.Log.i("AeroPerf", "typing kept the pages: ${(System.nanoTime() - t) / 1000} µs (hit $fastHits)")
                    }
                    return lastPages
                }
            }
        }
        lastGeometry = geometry
        lastRevision = revision
        val t0 = android.os.SystemClock.elapsedRealtime()
        measuredThisPass = 0
        tBlocks = 0L; tFlow = 0L
        incomplete = false
        // The first pass of a document may take a little longer (it sits behind the open anyway).
        budgetNanos = (if (lastPages.isEmpty()) FIRST_BUDGET_MS else BUDGET_MS) * 1_000_000L
        spentNanos = 0L
        pending.clear()
        pendingNext = 0
        priority.clear()
        for (i in inView) lastPages.getOrNull(i)?.frames?.forEach { frame -> frame.forEach { item -> priorityOf(item) } }
        val pages = layoutPages(doc, measurer, density, pageWidthPx)
        lastPages = pages
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        lastPassMs = ms
        if (com.therealityteam.aeropages.BuildConfig.DEBUG && (ms >= 16 || incomplete)) {
            android.util.Log.i("AeroPerf", "page layout $ms ms (blocks ${tBlocks / 1_000_000} ms, flow ${tFlow / 1_000_000} ms): ${pages.size} pages, $measuredThisPass paragraphs measured, ${cache.size} cached${if (incomplete) ", refining" else ""}")
        }
        return pages
    }

    /** Whether two measurements of a paragraph take the same room line by line (then no page changes). */
    private fun sameShape(a: ParaMetrics, b: ParaMetrics): Boolean {
        if (a.beforePx != b.beforePx || a.afterPx != b.afterPx || !a.bottoms.contentEquals(b.bottoms)) return false
        val an = a.notes; val bn = b.notes
        if (an == null || bn == null) return an == null && bn == null
        return an.size == bn.size && an.indices.all { an[it].contentEquals(bn[it]) }
    }

    private fun priorityOf(item: PageItem) {
        when (item) {
            is PageItem.Para -> priority.add(item.block.paragraph)
            is PageItem.TablePart -> item.block.table.rows.forEach { r -> r.cells.forEach { c -> priority.addAll(c.content) } }
            is PageItem.Box -> priority.addAll(item.block.paragraphs)
            is PageItem.Generated -> priority.add(item.paragraph)
            is PageItem.Picture, is PageItem.TocLine, is PageItem.IndexLine, is PageItem.Formula, is PageItem.Chart, is PageItem.Gap -> Unit
        }
    }

    private fun layoutPages(doc: Document, measurer: TextMeasurer, density: Density, pageWidthPx: (PageSetup) -> Int): List<LaidOutPage> {
        this.measurer = measurer
        this.density = density
        this.footnoteCount = doc.footnotes.size
        touched.clear()
        generatedUsed.clear()
        labels = PageMetrics.listLabels(doc)
        edges.clear(); edges.putAll(ParagraphBoxes.edges(doc))
        val out = ArrayList<LaidOutPage>()
        val sections = doc.sections()
        sections.forEachIndexed { si, (blocks, pg) ->
            val bodyW = (pageWidthPx(pg) - px(pg.marginLeftPt) - px(pg.marginRightPt)).coerceAtLeast(1)
            val bodyH = (px(pg.heightPt) - px(pg.marginTopPt) - px(pg.marginBottomPt)).coerceAtLeast(1)
            val cols = PageMetrics.columnsOf(pg)
            val gap = px(PageMetrics.columnGapOf(pg))
            val colW = ((bodyW - gap * (cols - 1)) / cols).coerceAtLeast(1)

            val items = ArrayList<PageFlow.Item>()
            val sources = ArrayList<(PageFlow.Piece) -> PageItem?>()
            // The body paragraphs by item, which the flow may lay out again beside floating objects.
            val paraItems = HashMap<Int, Paragraph>()
            fun add(item: PageFlow.Item, source: (PageFlow.Piece) -> PageItem?) {
                if (item is PageFlow.Lines) (source as? ParaSource)?.let { paraItems[items.size] = it.paragraph }
                items.add(item); sources.add(source)
            }
            fun addGenerated(p: Paragraph, keepWithNext: Boolean = false) {
                val m = measure(p, colW)
                add(linesOf(m, keepWithNext)) { pc -> PageItem.Generated(p, pc.from, if (pc.to >= m.lineCount) -1 else pc.to) }
            }

            val tb = System.nanoTime()
            // What is drawn over the page takes no room in the text: a mark just before the paragraph (or table) it
            // is anchored to — the one before it in the blocks — so it lands on the page that paragraph starts on,
            // its top what a position from the paragraph counts from.
            val floatItems = HashMap<Int, Block>()
            val marked = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Block, Boolean>())
            fun mark(b: Block) { floatItems[items.size] = b; marked.add(b); add(PageFlow.Solid(0f, keepWithNext = true)) { null } }
            blocks.forEachIndexed { i, block ->
                if (floatOf(block)?.onPage == true) { if (block !in marked) mark(block); return@forEachIndexed }
                if (block is ParagraphBlock || block is TableBlock) {
                    var j = i + 1
                    while (j < blocks.size && floatOf(blocks[j])?.onPage == true) { mark(blocks[j]); j++ }
                    // An in-flow float between: the marks after it still belong to this paragraph.
                    while (j < blocks.size && floatOf(blocks[j]) != null) { j++; while (j < blocks.size && floatOf(blocks[j])?.onPage == true) { mark(blocks[j]); j++ } }
                }
                addBlock(block, doc, colW, pg.linePitchPt, ::add, ::addGenerated)
            }
            tBlocks += System.nanoTime() - tb
            if (si == sections.lastIndex && doc.endnotes.isNotEmpty()) {
                addGenerated(gen("endnotes-heading:" + Texts.get(TextId.ENDNOTES_HEADING)) { PageMetrics.endnotesHeading(Texts.get(TextId.ENDNOTES_HEADING)) }, keepWithNext = true)
                doc.endnotes.forEachIndexed { i, e -> addGenerated(gen("endnote:$i:${e.text}") { PageMetrics.endnote(i, e.text) }) }
            }

            val noteArea = (px(PageMetrics.NOTE_GAP_ABOVE) + px(PageMetrics.NOTE_RULE) + px(PageMetrics.NOTE_GAP_BELOW)).toFloat()
            // What each page's header and footer take from its body (its number counts the sections' pages before).
            val firstPage = out.size + 1
            val geometry = PageFlow.Geometry(
                height = bodyH.toFloat(), columns = cols,
                noteHeight = { n -> footnoteParagraph(doc, n)?.let { measure(it, bodyW).heightPx.toFloat() } ?: 0f },
                noteArea = noteArea,
                room = { pi -> roomOf(com.therealityteam.aeropages.model.HeaderFooters.room(doc, firstPage + pi, pg)) },
                reflow = { item, top, sides -> paraItems[item]?.let { p -> wrap(p, colW, top, sides)?.let { e -> linesOf(e.metrics, p) } } },
            )
            val tp = System.nanoTime()
            val pageW = pageWidthPx(pg)
            val bodyTop = px(pg.marginTopPt)
            fun colXOf(frame: Int) = px(pg.marginLeftPt) + frame * (colW + gap)
            // What floats over the page takes from the text: the height it steps over, or the room beside it its lines
            // leave (see FloatPlacement.textKeepsClear / textBeside), placed from where its mark stands.
            val flowed = PageFlow.paginateAround(items, geometry, floatItems.keys) { item, at ->
                placeFloat(floatItems.getValue(item), pg, pageW, colXOf(at.frame), colW, bodyTop + at.y)?.let { f -> roomFor(f, pg, cols, colW, gap) }
            }
            // Each paragraph as the flow placed it: beside floating objects, where it starts so.
            wrapped.clear()
            flowed.pages.forEach { page ->
                page.wraps.forEach { (item, w) -> paraItems[item]?.let { p -> wrap(p, colW, w.top, w.sides)?.let { wrapped[p] = it } } }
            }
            wrapTries.clear()
            tFlow += System.nanoTime() - tp
            flowed.pages.forEachIndexed { pi, page ->
                val floats = ArrayList<PlacedFloat>()
                val frames = page.frames.mapIndexed { fi, frame ->
                    val colX = px(pg.marginLeftPt) + fi * (colW + gap)
                    // Each piece's top down the page: a mark's is its paragraph's top, which follows it. (What the
                    // text steps over is placed on its band's page, below.)
                    var y = px(pg.marginTopPt).toFloat()
                    frame.forEach { pc ->
                        if (pc.item !in flowed.bandPages) floatItems[pc.item]?.let { b -> placeFloat(b, pg, pageW, colX, colW, y)?.let(floats::add) }
                        y += pc.height
                    }
                    frame.mapNotNull { pc ->
                        if (pc.item == PageFlow.GAP) PageItem.Gap(kotlin.math.round(pc.height).toInt())
                        else sources[pc.item](pc)?.let { across(it, pg, pageW, colX, colW) }
                    }
                }
                flowed.bandPages.forEach { (item, p) ->
                    if (p == pi) floatItems[item]?.let { b ->
                        val at = flowed.marks[item]
                        placeFloat(b, pg, pageW, colXOf(at?.frame ?: 0), colW, bodyTop + (at?.y ?: 0f))?.let(floats::add)
                    }
                }
                out.add(LaidOutPage(pg, frames, page.notes, colW, gap, floats))
            }
        }
        if (out.isEmpty()) out.add(LaidOutPage(doc.page, listOf(emptyList()), emptyList(), 1, 0))
        // Forget paragraphs that left the document (deleted, or replaced by an undo snapshot), and
        // generated text that changed (an endnote being typed makes a new entry per keystroke).
        if (cache.size > touched.size * 2 + 64) cache.keys.retainAll(touched.keys)
        if (grids.size > touched.size * 2 + 64) grids.keys.retainAll(touched.keys)
        if (!incomplete) estimates.clear() else if (estimates.size > touched.size * 2 + 64) estimates.keys.retainAll(touched.keys)
        if (generated.size > generatedUsed.size * 2 + 32) generated.keys.retainAll(generatedUsed)
        countLineNumbers(doc, out)
        return out
    }

    /** Word's line numbers of a body paragraph: each line's number shown (0: none), how far from the text and how big. */
    class LineNos(val numbers: IntArray, val distancePt: Float, val sizePt: Float)

    private val lineNos = IdentityHashMap<Paragraph, LineNos>()

    /** Changes when the line numbers do (the rows read it while drawing them, so they draw again). */
    val lineNumbersVersion = androidx.compose.runtime.mutableIntStateOf(0)

    /** [p]'s line numbers as of the last [layout], or null (no numbered section, suppressed, not a body paragraph). */
    fun lineNumbersOf(p: Paragraph): LineNos? = lineNos[p]

    /** Counts the laid-out pages' lines as Word does (LineNumbers.Counter): column by column, page by page. */
    private fun countLineNumbers(doc: Document, pages: List<LaidOutPage>) {
        val before = lineNos.entries.sumOf { (p, n) -> System.identityHashCode(p) * 31 + n.numbers.contentHashCode() + n.distancePt.hashCode() }
        lineNos.clear()
        if (pages.none { it.setup.lineNumbers != null }) { if (before != 0) lineNumbersVersion.intValue++; return }
        val counter = com.therealityteam.aeropages.model.LineNumbers.Counter()
        for (page in pages) {
            counter.page(page.setup)
            val numbering = page.setup.lineNumbers ?: continue
            for (frame in page.frames) for (item in frame) {
                if (item !is PageItem.Para) continue
                val p = item.block.paragraph
                if (p.format.suppressLineNumbers || p.isPageBreak()) continue
                val count = metricsOf(p)?.lineCount ?: continue
                val entry = lineNos.getOrPut(p) { LineNos(IntArray(count), com.therealityteam.aeropages.model.LineNumbers.distanceOf(numbering), doc.styles.defaultSizePt) }
                val to = if (item.to < 0) count else item.to.coerceAtMost(count)
                for (i in item.from until to) if (i < entry.numbers.size) entry.numbers[i] = counter.line()
            }
        }
        val after = lineNos.entries.sumOf { (p, n) -> System.identityHashCode(p) * 31 + n.numbers.contentHashCode() + n.distancePt.hashCode() }
        if (after != before) lineNumbersVersion.intValue++
    }

    /** A header's and footer's room on a page, in pixels (null: none). */
    private fun roomOf(r: com.therealityteam.aeropages.model.HeaderFooters.Room): PageFlow.PageRoom? = if (r.none) null else
        PageFlow.PageRoom(px(r.top).toFloat(), px(r.foot).toFloat(), r.spans.map { PageFlow.Band(px(it.top).toFloat(), px(it.bottom).toFloat(), it.columns) })

    /** A picture's, text box's or table's floating position, if it has one. */
    private fun floatOf(b: Block) = when (b) {
        is ImageBlock -> b.floating
        is TextBoxBlock -> b.floating
        is TableBlock -> b.table.floating
        else -> null
    }

    /** A table's width in a column [colW] wide: its own (no wider than the column), else the column's. */
    private fun tableWidth(t: com.therealityteam.aeropages.model.Table, colW: Int): Int =
        t.widthPt?.let { w -> px(w).coerceIn(1, colW) } ?: colW

    /** A floating picture or text box over a page drawn [pageW] wide, its column at [colX] [colW] wide, its paragraph's top at [paraTop]. */
    private fun placeFloat(b: Block, pg: PageSetup, pageW: Int, colX: Int, colW: Int, paraTop: Float): PlacedFloat? {
        val pos = floatOf(b) ?: return null
        val (w, h) = when (b) {
            is ImageBlock -> px(b.widthPt).coerceAtLeast(1) to px(b.heightPt).coerceAtLeast(1)
            is TextBoxBlock -> {
                val w = b.widthPt?.let { px(it) } ?: colW
                val inner = PageMetrics.boxTextWidth(b, (w - px(PageMetrics.BOX_INSET) * 2).coerceAtLeast(1).toFloat()).toInt()
                val text = b.paragraphs.sumOf { measure(it, inner).heightPx }
                w to PageMetrics.boxFrameHeight(b, text.toFloat(), density.density).toInt().coerceAtLeast(1)
            }
            is TableBlock -> {
                val w = b.table.widthPt?.let { px(it) } ?: colW
                w to tableRows(b.table, w).first.sum().toInt().coerceAtLeast(1)
            }
            else -> return null
        }
        val unit = density.density
        val (x, y) = FloatPlacement.place(pos, w.toFloat(), h.toFloat(), pg, pageW.toFloat(), unit, colX.toFloat(), colW.toFloat(), paraTop)
        return PlacedFloat(b, kotlin.math.round(x).toInt(), kotlin.math.round(y).toInt(), w, h, pos.wrap == FloatWrap.BEHIND) { nx, ny ->
            FloatPlacement.movedTo(pos, nx, ny, pg, pageW.toFloat(), unit, colX.toFloat(), colW.toFloat(), paraTop)
        }
    }

    /**
     * The band a floating object placed on the page makes when the text keeps clear of it: its height down
     * the body, in the columns it stands over; null when the text does not (behind or in front of it, or
     * room enough beside it).
     */
    private fun roomFor(f: PlacedFloat, pg: PageSetup, cols: Int, colW: Int, gap: Int): PageFlow.Room? {
        val pos = floatOf(f.block) ?: return null
        val top = px(pg.marginTopPt)
        val frames = HashSet<Int>()
        var widest = 0
        for (c in 0 until cols) {
            val cx = px(pg.marginLeftPt) + c * (colW + gap)
            val overlap = minOf(f.x + f.w, cx + colW) - maxOf(f.x, cx)
            if (overlap > 0) { frames.add(c); widest = maxOf(widest, overlap) }
        }
        if (frames.isEmpty()) return null
        if (FloatPlacement.textKeepsClear(pos, widest.toFloat(), colW.toFloat()))
            return PageFlow.Room(bands = listOf(PageFlow.Band((f.y - top).toFloat(), (f.y + f.h - top).toFloat(), frames)))
        if (!FloatPlacement.textBeside(pos, widest.toFloat(), colW.toFloat())) return null
        // The lines beside it, column by column, leave it room and Word's distance on the side the text takes.
        val sides = frames.map { c ->
            val cx = px(pg.marginLeftPt) + c * (colW + gap)
            val (l, r) = FloatPlacement.beside(f.x.toFloat(), f.w.toFloat(), cx.toFloat(), colW.toFloat(), px(FloatPlacement.WRAP_GAP_PT).toFloat())
            PageFlow.Side((f.y - top).toFloat(), (f.y + f.h - top).toFloat(), setOf(c), l, r)
        }
        return PageFlow.Room(sides = sides)
    }

    /** A picture, text box or table in the flow that keeps its floating place across its column (at [colX], [colW] wide). */
    private fun across(item: PageItem, pg: PageSetup, pageW: Int, colX: Int, colW: Int): PageItem {
        val (pos, w) = when (item) {
            is PageItem.Picture -> (item.block.floating ?: return item) to px(PageMetrics.fitImage(item.block.widthPt, item.block.heightPt, colW / density.density).first)
            is PageItem.Box -> (item.block.floating ?: return item) to boxWidth(item.block, colW)
            is PageItem.TablePart -> (item.block.table.floating ?: return item) to tableWidth(item.block.table, colW)
            else -> return item
        }
        val x = FloatPlacement.acrossColumn(pos, w.toFloat(), pg, pageW.toFloat(), density.density, colX.toFloat(), colW.toFloat()).toInt()
        return when (item) {
            is PageItem.Picture -> PageItem.Picture(item.block, x)
            is PageItem.TablePart -> PageItem.TablePart(item.block, item.slice, x, w)
            else -> PageItem.Box((item as PageItem.Box).block, x)
        }
    }

    private companion object {
        /** New-measurement time per pass: the first pass of a document, and each refining pass after it. */
        const val FIRST_BUDGET_MS = 50L
        const val BUDGET_MS = 12L
    }

    /** The footnote paragraph printed for footnote [n] (1-based). */
    fun footnoteParagraph(doc: Document, n: Int): Paragraph? {
        val note = doc.footnotes.getOrNull(n - 1) ?: return null
        return gen("footnote:$n:${note.text}") { PageMetrics.footnote(n, note.text) }
    }

    private fun addBlock(
        block: Block, doc: Document, colW: Int, grid: Float?,
        add: (PageFlow.Item, (PageFlow.Piece) -> PageItem?) -> Unit,
        addGenerated: (Paragraph, Boolean) -> Unit,
    ) {
        when (block) {
            is ParagraphBlock -> {
                val p = block.paragraph
                if (p.isPageBreak()) { add(PageFlow.Break) { null }; return }
                if (grid != null) grids[p] = grid else grids.remove(p)
                val m = measure(p, colW)
                add(linesOf(m, p), ParaSource(p) { pc -> PageItem.Para(block, pc.from, if (pc.to >= (metricsOf(p) ?: m).lineCount) -1 else pc.to) })
            }
            is TableBlock -> {
                val t = block.table
                val w = tableWidth(t, colW)
                val (heights, notes) = tableRows(t, w)
                val pad = px(PageMetrics.TABLE_PAD).toFloat()
                // A table of its own width stands where it is aligned or indented across its column (a floating one: see across).
                val x = if (t.floating == null) kotlin.math.round(PageMetrics.tableOffset(t, w.toFloat(), colW.toFloat(), density.density)).toInt() else 0
                val placed = t.floating == null && (w != colW || x != 0)
                add(PageFlow.Rows(heights, t.headerRowCount(), pad, pad, notes)) { pc -> if (placed) PageItem.TablePart(block, pc.slice, x, w) else PageItem.TablePart(block, pc.slice) }
            }
            is ImageBlock -> add(PageFlow.Solid(imageHeight(block, colW).toFloat())) { PageItem.Picture(block) }
            is com.therealityteam.aeropages.model.EquationBlock -> add(PageFlow.Solid(equationHeight(block, colW).toFloat())) { PageItem.Formula(block) }
            is com.therealityteam.aeropages.model.ChartBlock -> add(PageFlow.Solid(chartHeight(block, colW).toFloat())) { PageItem.Chart(block) }
            is TextBoxBlock -> {
                val inner = PageMetrics.boxTextWidth(block, (boxWidth(block, colW) - px(PageMetrics.BOX_INSET) * 2).coerceAtLeast(1).toFloat()).toInt()
                var text = 0
                val notes = ArrayList<Int>()
                block.paragraphs.forEach { p -> val m = measure(p, inner); text += m.heightPx; m.notes?.forEach { it.forEach(notes::add) } }
                val h = px(PageMetrics.BOX_MARGIN) * 2 + PageMetrics.boxFrameHeight(block, text.toFloat(), with(density) { 1.dp.toPx() }).toInt()
                add(PageFlow.Solid(h.toFloat(), notes.toIntArray())) { PageItem.Box(block) }
            }
            is TocBlock -> {
                // A table of figures has no heading of its own (Word's has none).
                if (block.caption == null) {
                    val title = Texts.get(TextId.TOC_HEADING)
                    addGenerated(gen("toc-heading:$title") { PageMetrics.generatedHeading(title) }, true)
                }
                val entries = doc.tocEntries(block)
                if (entries.isEmpty()) {
                    val hint = Texts.get(if (block.caption == null) TextId.TOC_EMPTY else TextId.TOF_EMPTY)
                    addGenerated(gen("toc-empty:$hint") { PageMetrics.generatedHint(hint) }, false)
                } else {
                    val lineH = px(PageMetrics.TOC_LINE).toFloat()
                    entries.forEach { e -> add(PageFlow.Solid(lineH)) { PageItem.TocLine(e) } }
                }
            }
            is com.therealityteam.aeropages.model.IndexBlock -> {
                // Word's index has no heading of its own: its entries, or its note that none is marked yet.
                val lines = com.therealityteam.aeropages.model.IndexEntries.lines(doc, block.headings)
                if (lines.isEmpty()) {
                    val hint = Texts.get(TextId.INDEX_EMPTY)
                    addGenerated(gen("index-empty:$hint") { PageMetrics.generatedHint(hint) }, false)
                } else {
                    val lineH = px(PageMetrics.TOC_LINE).toFloat()
                    // A letter heading stays with the entry after it.
                    lines.forEachIndexed { i, l -> add(PageFlow.Solid(lineH, keepWithNext = l.heading)) { PageItem.IndexLine(block, l, "$i:${l.level}:${l.text}") } }
                }
            }
            is BibliographyBlock -> {
                val title = Texts.get(TextId.BIBLIOGRAPHY_HEADING)
                addGenerated(gen("bib-heading:$title") { PageMetrics.generatedHeading(title) }, true)
                if (doc.sources.isEmpty()) {
                    val hint = Texts.get(TextId.BIBLIOGRAPHY_EMPTY)
                    addGenerated(gen("bib-empty:$hint") { PageMetrics.generatedHint(hint) }, false)
                } else doc.sources.forEach { s ->
                    val text = "${s.author}. ${s.title}. ${s.year}."
                    addGenerated(gen("bib:$text") { PageMetrics.generatedEntry(text) }, false)
                }
            }
        }
    }

}
