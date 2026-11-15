package com.therealityteam.aeropages.model

/**
 * PowerPoint (Slides) model. Kept deliberately simple and reusing [CharacterFormat] /
 * [TextAlign] from the Pages model: a slide is a title box + a body box (bullet lines)
 * plus a background, which is enough for real authoring while reusing the Word
 * formatting/rendering stack. Per-box formatting (not per-run) keeps editing tractable.
 */
/**
 * A slide text box, backed by a rich [Paragraph] so it supports per-character
 * (selection-range) formatting via the shared [ParagraphOps], exactly like the Pages
 * editor. The [text]/[align]/[defaultSizePt] getters keep older call sites + the pptx
 * readers/writer working unchanged.
 */
/** Bullet / numbered list rendering for a slide box's lines (each '\n'-line is an item). */
enum class SlideListStyle { NONE, BULLET, NUMBER }

class SlideBox(
    val paragraph: Paragraph,
    var listStyle: SlideListStyle = SlideListStyle.NONE,
    /** The box's animation effects (title, body or a shape's text; table cells keep none). */
    override val anims: MutableList<ElementAnim> = mutableListOf(),
    /**
     * Where the box sits on the slide (title / body placeholders only). Null = the slide's
     * [SlideLayout] flows it; set from a deck's own placeholder geometry or by dragging the box.
     */
    var frame: BoxFrame? = null,
    /**
     * Line spacing as PowerPoint's multiple of single spacing (1 = single, 1.5, 2 …; `a:lnSpc/a:spcPct`,
     * ODF's proportional `fo:line-height`), for the whole box.
     */
    var lineSpacing: Float = 1f,
) : AnimTarget {
    constructor(text: String = "", format: CharacterFormat = CharacterFormat(), align: TextAlign = TextAlign.LEFT)
        : this(Paragraph(mutableListOf(TextRun(text, format)), ParagraphFormat(align = align)))

    val text: String get() = paragraph.plainText()
    val align: TextAlign get() = paragraph.format.align
    val defaultSizePt: Float get() = paragraph.runs.firstOrNull()?.format?.fontSizePt ?: 22f

    fun copyOf(): SlideBox = SlideBox(paragraph.copyOf(), listStyle, anims.mapTo(mutableListOf()) { it.copyOf() }, frame?.copyOf(), lineSpacing).also { it.fill = fill; it.borders = borders; it.cellAnchor = cellAnchor; it.colSpan = colSpan; it.rowSpan = rowSpan }

    /**
     * A table cell's own shading (PowerPoint's Table Design ▸ Shading): its colour, fully transparent for No Fill;
     * null takes its table style's. Kept with the cell, so it moves with inserted and deleted rows and columns.
     */
    var fill: Long? = null

    /**
     * A table cell's own borders (Table Design ▸ Borders): each side's line, null for none; null as a whole takes its
     * table style's grid — on a side it shares, its neighbour's own borders, if it has them, draw instead.
     */
    var borders: CellBorders? = null

    /** A table cell's text from the top, middle or bottom (Table Layout ▸ Alignment); null: in the middle, as the app makes tables. */
    var cellAnchor: ShapeAnchor? = null

    /**
     * A merged table cell: how many columns and rows it spans from its own place (PowerPoint's `gridSpan`/`rowSpan`, ODF's
     * `number-columns-spanned`/`number-rows-spanned`); the cells it covers stay in the grid, hidden (see [SlideTable.coverage]).
     */
    var colSpan: Int = 1
    var rowSpan: Int = 1
}

/**
 * A placeholder's position and size as fractions (0..1) of the slide. PowerPoint keeps this on
 * the slide's shape, else on its layout's / master's placeholder of the same idx or type; ODF
 * writes it on every frame. A box without a frame follows AeroDesk's flow layout, and the
 * writers emit [defaultFor] for it so the file shows the same arrangement elsewhere.
 */
class BoxFrame(var xPct: Float, var yPct: Float, var wPct: Float, var hPct: Float) {
    fun copyOf(): BoxFrame = BoxFrame(xPct, yPct, wPct, hPct)
    fun sameAs(o: BoxFrame?): Boolean = o != null && xPct == o.xPct && yPct == o.yPct && wPct == o.wPct && hPct == o.hPct

    companion object {
        /** The flow layout's geometry for the title (or body) box of a slide using [layout]. */
        fun defaultFor(layout: SlideLayout, title: Boolean): BoxFrame = when (layout) {
            SlideLayout.TITLE -> if (title) BoxFrame(0.05625f, 0.30f, 0.8875f, 0.22f) else BoxFrame(0.10f, 0.55f, 0.80f, 0.18f)
            SlideLayout.SECTION -> if (title) BoxFrame(0.05625f, 0.34f, 0.8875f, 0.20f) else BoxFrame(0.05625f, 0.56f, 0.8875f, 0.15f)
            else -> if (title) BoxFrame(0.05625f, 0.06667f, 0.8875f, 0.16667f) else BoxFrame(0.05625f, 0.26667f, 0.8875f, 0.63451f)
        }
    }
}

enum class SlideLayout { TITLE, TITLE_CONTENT, SECTION, BLANK }

/** A shape's geometry: Office's presets the app draws, a line, a text box — or a [Freeform] outline of its own (see [SlideShape.path]). */
enum class ShapeType { TEXTBOX, RECT, ROUNDED, ELLIPSE, TRIANGLE, STAR, ARROW, LINE, FREEFORM }

/**
 * How a shape's gradient runs between its two colours — [SlideShape.fill] (the first) and
 * [SlideShape.fillGradient] (the second): DrawingML's `a:lin` and `a:path`, ODF's gradient styles.
 */
enum class GradientKind {
    /** Along its angle, the first colour to the second. */
    LINEAR,
    /** Across its angle both ways from its middle: the second colour on the centre line, the first at the edges (ODF's axial). */
    AXIAL,
    /** In circles out from its centre: the first colour at the centre, the second half the box's diagonal away (`path="circle"`, ODF's radial). */
    RADIAL,
    /** In rectangles out from its centre: the first colour at the centre, the second at the edges (`path="rect"`, ODF's rectangular). */
    RECTANGULAR,
}

/**
 * A shape's outer shadow (DrawingML `a:outerShdw`, ODF `draw:shadow`): its silhouette in [color]
 * (ARGB, its alpha the shadow's opacity), moved [dxPt], [dyPt] on the page — whatever the shape's
 * turn — and blurred [blurPt].
 */
data class ShapeShadow(val color: Long = 0x66000000, val dxPt: Float = 2.12f, val dyPt: Float = 2.12f, val blurPt: Float = 4f) {
    /** Whether [other] is this shadow as a file gives it back (its lengths rounded to the file's units). */
    fun sameAs(other: ShapeShadow?): Boolean = other != null && color == other.color &&
        kotlin.math.abs(dxPt - other.dxPt) < 0.05f && kotlin.math.abs(dyPt - other.dyPt) < 0.05f && kotlin.math.abs(blurPt - other.blurPt) < 0.05f

    companion object {
        /** The shadows Shape Effects offers, as PowerPoint names them: offset to the lower right, below, to the right, and all round (soft). */
        val PRESETS: List<ShapeShadow> = listOf(
            ShapeShadow(),
            ShapeShadow(0x66000000, 0f, 3f, 4f),
            ShapeShadow(0x66000000, 3f, 0f, 4f),
            ShapeShadow(0x80000000, 0f, 0f, 8f),
        )
    }
}

/** Where a shape's text sits vertically (OOXML `a:bodyPr@anchor`, ODF `draw:textarea-vertical-align`). */
enum class ShapeAnchor { TOP, CENTER, BOTTOM }

/**
 * A thing on a slide that the Selection Pane lists: the name it goes by (PowerPoint's `cNvPr@name`, Impress's `draw:name`;
 * null: the pane's own "Picture 2"…) and whether it is hidden (`cNvPr@hidden`, `drawooo:display="none"`) — kept in the
 * file but not drawn on the slide, in the show or in the PDF.
 */
interface SlideObject {
    var objectName: String?
    var hidden: Boolean
}

/** A free-positioned element on a slide. Position/size are fractions (0..1) of the slide. */
class SlideShape(
    val type: ShapeType,
    val box: SlideBox = SlideBox("", CharacterFormat(fontSizePt = 18f), TextAlign.CENTER),
    var xPct: Float = 0.30f,
    var yPct: Float = 0.35f,
    var wPct: Float = 0.40f,
    var hPct: Float = 0.22f,
    var fill: Long? = if (type == ShapeType.TEXTBOX) null else 0xFFDDE6F2,
    var border: Long? = 0xFF8AA0BF,
    var borderWidthPt: Float = 1f,
    var rotationDeg: Float = 0f,
    var flipH: Boolean = false,
    var flipV: Boolean = false,
    /** Second gradient colour (OOXML `a:gradFill`); null = solid [fill]. */
    var fillGradient: Long? = null,
    /** Gradient direction in degrees: 0 = left→right, 90 = top→bottom (OOXML `a:lin@ang`). */
    var gradientAngle: Float = 90f,
    /** Shapes sharing a group id select and move together (OOXML `p:grpSp`). */
    var groupId: Int? = null,
    /** Vertical anchor of the text inside the shape; PowerPoint text boxes default to the top. */
    var anchor: ShapeAnchor = ShapeAnchor.CENTER,
    /** How the outline (a line) is dashed (`a:prstDash`). */
    var dash: LineDash = LineDash.SOLID,
    /** A line's arrowheads: at its first point (`a:headEnd`, PowerPoint's Begin Arrow) and its last (`a:tailEnd`, End Arrow). */
    var beginArrow: LineEnd = LineEnd.NONE,
    var endArrow: LineEnd = LineEnd.NONE,
    /** A [ShapeType.FREEFORM] shape's outline in its box (immutable, so copies share it); null draws its box. */
    var path: Freeform? = null,
    /** How its gradient runs (with a [fillGradient]). */
    var gradientKind: GradientKind = GradientKind.LINEAR,
    /** A radial or rectangular gradient's centre, as fractions of the shape's box. */
    var gradientCx: Float = 0.5f,
    var gradientCy: Float = 0.5f,
    /** Its outer shadow, or none. */
    var shadow: ShapeShadow? = null,
    /**
     * The DrawingML preset a [ShapeType.FREEFORM] outline is drawn from (`hexagon`, `chevron` …) and
     * its adjust values as the file gave them (`name=formula|…`, null for the preset's own): the
     * outline is worked out again at whatever size the shape takes, and a pptx keeps the preset.
     */
    var preset: String? = null,
    var presetAdjust: String? = null,
    /** What a click on it does in the slide show: an address, or a place in the show ([SlideLinks]); null: nothing (the show goes on). */
    var action: String? = null,
    /**
     * Its text does not wrap (pptx `wrap="none"`, ODF `fo:wrap-option="no-wrap"`, Office's wrapText none —
     * PowerPoint's click-to-type text boxes): each line as long as it is, placed by its alignment in the box.
     */
    var noWrap: Boolean = false,
) : SlideObject {
    override var objectName: String? = null
    override var hidden: Boolean = false
    /** What pointing at it does in the slide show (PowerPoint's Action Settings ▸ Mouse Over; pptx `a:hlinkHover`), as [action]. */
    var hoverAction: String? = null

    fun copyOf(): SlideShape = SlideShape(type, box.copyOf(), xPct, yPct, wPct, hPct, fill, border, borderWidthPt, rotationDeg, flipH, flipV, fillGradient, gradientAngle, groupId, anchor,
        dash, beginArrow, endArrow, path, gradientKind, gradientCx, gradientCy, shadow, preset, presetAdjust, action, noWrap).also { it.objectName = objectName; it.hidden = hidden; it.hoverAction = hoverAction }

    /**
     * Its text run down ([turn] 90) or up (270) the box, as a box turned so: a shape that looks the same turned a
     * quarter (a rectangle, a text box, an ellipse) changes width and height about its centre and turns — on a
     * slide [aspect] (width ÷ height) wide. Office's vertical text (pptx `vert`, `vert270`; the binary text flow).
     */
    fun turnText(turn: Float, aspect: Float) {
        if (box.text.isBlank() || type !in setOf(ShapeType.TEXTBOX, ShapeType.RECT, ShapeType.ROUNDED, ShapeType.ELLIPSE) || aspect <= 0f) return
        val cx = xPct + wPct / 2f; val cy = yPct + hPct / 2f
        val w = hPct / aspect; val h = wPct * aspect
        wPct = w; hPct = h; xPct = cx - w / 2f; yPct = cy - h / 2f
        rotationDeg = ((rotationDeg + turn) % 360f + 360f) % 360f
    }

    /** Whether it may have arrowheads: a line, or a freeform with open ends. */
    val hasEnds: Boolean get() = type == ShapeType.LINE || (type == ShapeType.FREEFORM && path?.openEnds() != null)

    /**
     * What its text sits on: its fill (a gradient's middle, a see-through fill laid on [under]) — or,
     * a line, a text box or a shape without fill, [under] (what is behind it; null: unknown).
     */
    fun textBackdrop(under: Long?): Long? {
        val f = fill ?: return under
        if (type == ShapeType.LINE) return under
        val solid = fillGradient?.let { g -> TextColors.blend((g and 0x00FFFFFF) or 0x80000000, f or 0xFF000000) } ?: f
        return if ((solid ushr 24) >= 0xFF) solid else under?.let { TextColors.blend(solid, it) } ?: (solid or 0xFF000000)
    }

    companion object {
        /**
         * The least a shape other than a line or a freeform is read at, as a fraction of its slide
         * (about a point): a dot in a template's icon keeps its size, and nothing is quite nothing.
         */
        const val MIN_FRACTION = 0.001f
    }
}

/** A table element on a slide: a grid of rich text cells + a style preset. Position is fractional. */
class SlideTable(
    val cells: MutableList<MutableList<SlideBox>>,
    var styleIndex: Int = 1,
    var headerRow: Boolean = true,
    var bandedRows: Boolean = true,
    var firstColumn: Boolean = false,
    var lastColumn: Boolean = false,
    var totalRow: Boolean = false,
    var bandedColumns: Boolean = false,
    var xPct: Float = 0.10f,
    var yPct: Float = 0.32f,
    var wPct: Float = 0.80f,
    var hPct: Float = 0.42f,
    override val anims: MutableList<ElementAnim> = mutableListOf(),
) : AnimTarget, SlideObject {
    override var objectName: String? = null
    override var hidden: Boolean = false
    val rows: Int get() = cells.size
    val cols: Int get() = cells.firstOrNull()?.size ?: 0

    /** Each column's width as a share of the table's (PowerPoint's `a:gridCol`); empty, or not one per column: all alike. */
    val colWeights: MutableList<Float> = mutableListOf()

    /** Each row's height as a share of the table's (`a:tr h`); empty, or not one per row: all alike. */
    val rowWeights: MutableList<Float> = mutableListOf()

    /** Column [c]'s share (0..1) of the table's width. */
    fun colShare(c: Int): Float = share(colWeights, c, cols)

    /** Row [r]'s share (0..1) of the table's height. */
    fun rowShare(r: Int): Float = share(rowWeights, r, rows)

    /** Where column [c] starts, as a share of the table's width ([cols] for its right edge). */
    fun colStart(c: Int): Float = (0 until c.coerceAtMost(cols)).fold(0f) { a, i -> a + colShare(i) }

    /** Where row [r] starts, as a share of the table's height. */
    fun rowStart(r: Int): Float = (0 until r.coerceAtMost(rows)).fold(0f) { a, i -> a + rowShare(i) }

    /** Some cell is merged with others. */
    val hasMerges: Boolean get() = cells.any { row -> row.any { it.colSpan > 1 || it.rowSpan > 1 } }

    /** Cell ([r], [c])'s span (rows, columns), clipped to the grid. */
    fun spanOf(r: Int, c: Int): Pair<Int, Int> {
        val b = cells.getOrNull(r)?.getOrNull(c) ?: return 1 to 1
        return b.rowSpan.coerceIn(1, (rows - r).coerceAtLeast(1)) to b.colSpan.coerceIn(1, (cols - c).coerceAtLeast(1))
    }

    /**
     * Which cell each grid place shows: its own (r * cols + c), or the merged cell covering it — so a merged cell's places
     * share one number. The first merged cell to claim a place keeps it.
     */
    fun coverage(): Array<IntArray> {
        val owner = Array(rows) { r -> IntArray(cols) { c -> r * cols + c } }
        val claimed = Array(rows) { BooleanArray(cols) }
        for (r in 0 until rows) for (c in 0 until cols) {
            if (claimed[r][c]) continue
            val (rs, cs) = spanOf(r, c)
            if (rs == 1 && cs == 1) continue
            for (rr in r until r + rs) for (cc in c until c + cs) if (rr != r || cc != c) { owner[rr][cc] = r * cols + c; claimed[rr][cc] = true }
        }
        return owner
    }

    /** Cell ([r], [c]) lies under a merged cell (shown by another), per [coverage]. */
    fun covered(cover: Array<IntArray>, r: Int, c: Int): Boolean = cover[r][c] != r * cols + c

    private fun share(weights: List<Float>, i: Int, n: Int): Float {
        if (n <= 0) return 0f
        if (weights.size != n) return 1f / n
        val sum = weights.sum().takeIf { it > 0f } ?: return 1f / n
        return weights[i] / sum
    }

    fun copyOf(): SlideTable = SlideTable(
        cells.map { row -> row.map { it.copyOf() }.toMutableList() }.toMutableList(),
        styleIndex, headerRow, bandedRows, firstColumn, lastColumn, totalRow, bandedColumns, xPct, yPct, wPct, hPct, anims.mapTo(mutableListOf()) { it.copyOf() })
        .also { it.objectName = objectName; it.hidden = hidden; it.colWeights.addAll(colWeights); it.rowWeights.addAll(rowWeights) }

    companion object {
        fun create(rows: Int, cols: Int): SlideTable = SlideTable(
            MutableList(rows) { r -> MutableList(cols) { _ ->
                SlideBox("", CharacterFormat(fontSizePt = 16f, bold = r == 0), TextAlign.LEFT)
            } })
    }
}

enum class ChartType { COLUMN, BAR, LINE, PIE, AREA, SCATTER, DOUGHNUT, RADAR }

/** Where a chart's legend sits around the plot (Office's legend positions, `c:legendPos`). */
enum class LegendPos { BOTTOM, TOP, LEFT, RIGHT }

class ChartSeries(var name: String, val values: MutableList<Float>) {
    fun copyOf(): ChartSeries = ChartSeries(name, values.toMutableList())
}

/** A data chart on a slide rendered with Compose Canvas. Position is fractional. */
class SlideChart(
    var type: ChartType = ChartType.COLUMN,
    var title: String = Texts.get(TextId.CHART_TITLE_DEFAULT),
    val categories: MutableList<String> = mutableListOf(),
    val series: MutableList<ChartSeries> = mutableListOf(),
    var colorSet: Int = 0,
    var showLegend: Boolean = true,
    var showGrid: Boolean = true,
    var showValues: Boolean = false,
    var xPct: Float = 0.14f,
    var yPct: Float = 0.20f,
    var wPct: Float = 0.72f,
    var hPct: Float = 0.62f,
    override val anims: MutableList<ElementAnim> = mutableListOf(),
    /**
     * Sheets only: the worksheet block (A1 range, e.g. `A1:C5`) this chart is bound to. Its
     * categories/series are a cache recomputed from the cells; null = static data (Slides).
     */
    var sourceRef: String? = null,
    /** Whether the block's first row holds the series names. */
    var sourceHeader: Boolean = true,
    /** Whether the block's first column holds the category labels. */
    var sourceCats: Boolean = true,
    /** Where the legend sits when [showLegend] is on. */
    var legendPos: LegendPos = LegendPos.BOTTOM,
    /** Axis titles: the category (x) axis and the value (y) axis; empty = none. A bar chart's category axis is the vertical one. */
    var catAxisTitle: String = "",
    var valAxisTitle: String = "",
    /** Office's Format Axis for the value axis: fixed bounds and major unit (null = automatic). */
    var valMin: Double? = null,
    var valMax: Double? = null,
    var valMajor: Double? = null,
    /** The value axis' number format — an Excel format code ("0%", "#,##0.00", "¥#,##0"); "General" = automatic. */
    var valFormat: String = GENERAL,
) : AnimTarget, SlideObject {
    override var objectName: String? = null
    override var hidden: Boolean = false
    /** Whether this type draws axes (and so axis titles). */
    val hasAxes: Boolean get() = type != ChartType.PIE && type != ChartType.DOUGHNUT && type != ChartType.RADAR

    /** The value axis' Format Axis settings as one value (what the Chart Elements dialog edits). */
    var axisFormat: AxisFormat
        get() = AxisFormat(valMin, valMax, valMajor, valFormat)
        set(v) { valMin = v.min; valMax = v.max; valMajor = v.major; valFormat = v.format }

    /** Office's Switch Row/Column: the series become the categories and the categories the series. */
    fun switchRowColumn() {
        val oldCats = categories.toList()
        val oldSeries = series.map { it.copyOf() }
        categories.clear(); categories.addAll(oldSeries.map { it.name })
        series.clear()
        oldCats.forEachIndexed { ci, cat -> series.add(ChartSeries(cat, oldSeries.map { it.values.getOrElse(ci) { 0f } }.toMutableList())) }
    }

    fun copyOf(): SlideChart = SlideChart(type, title, categories.toMutableList(),
        series.map { it.copyOf() }.toMutableList(), colorSet, showLegend, showGrid, showValues, xPct, yPct, wPct, hPct, anims.mapTo(mutableListOf()) { it.copyOf() },
        sourceRef, sourceHeader, sourceCats, legendPos, catAxisTitle, valAxisTitle, valMin, valMax, valMajor, valFormat)
        .also { it.objectName = objectName; it.hidden = hidden }

    companion object {
        /** The automatic number format. */
        const val GENERAL = "General"

        fun sample(type: ChartType = ChartType.COLUMN): SlideChart = SlideChart(
            type = type,
            categories = MutableList(4) { Texts.get(TextId.CATEGORY_N, it + 1) },
            series = mutableListOf(
                ChartSeries(Texts.get(TextId.SERIES_N, 1), mutableListOf(4.3f, 2.5f, 3.5f, 4.5f)),
                ChartSeries(Texts.get(TextId.SERIES_N, 2), mutableListOf(2.4f, 4.4f, 1.8f, 2.8f)),
                ChartSeries(Texts.get(TextId.SERIES_N, 3), mutableListOf(2f, 2f, 3f, 5f)),
            ))
    }
}

/**
 * Office's Format Axis for a chart's value axis: fixed bounds and major unit (null = automatic)
 * and a number format code ([SlideChart.GENERAL] = automatic).
 */
data class AxisFormat(val min: Double?, val max: Double?, val major: Double?, val format: String) {
    /** What is wrong with typed settings. */
    enum class Problem { NOT_A_NUMBER, BOUNDS, UNIT }

    companion object {
        /**
         * Reads the dialog's fields — empty means automatic, thousands commas are ignored and a
         * trailing % divides by 100 (50% = 0.5, as a percent axis counts). Returns the settings,
         * or the first problem: a field that is no number, a maximum not above the minimum, a
         * major unit not above zero.
         */
        fun parse(min: String, max: String, major: String, format: String): Pair<AxisFormat?, Problem?> {
            var bad = false
            fun num(text: String): Double? {
                val t = text.trim().replace(",", "").replace(" ", "")
                if (t.isEmpty()) return null
                val percent = t.endsWith("%")
                val v = (if (percent) t.dropLast(1) else t).toDoubleOrNull()?.takeIf { it.isFinite() }
                if (v == null) { bad = true; return null }
                return if (percent) v / 100.0 else v
            }
            val lo = num(min); val hi = num(max); val unit = num(major)
            return when {
                bad -> null to Problem.NOT_A_NUMBER
                lo != null && hi != null && hi <= lo -> null to Problem.BOUNDS
                unit != null && unit <= 0.0 -> null to Problem.UNIT
                else -> AxisFormat(lo, hi, unit, format.ifBlank { SlideChart.GENERAL }) to null
            }
        }
    }
}

/** Per-slide transition used when advancing in the slideshow. */
enum class SlideTransition { NONE, FADE, PUSH, ZOOM, COVER, WIPE, SPLIT, DISSOLVE, CUT, WHEEL, UNCOVER, RANDOM_BARS, SHAPE }

/**
 * Which way a transition goes (PowerPoint's Effect Options): the side a push, wipe, cover or uncover comes from; a split's
 * orientation and whether it opens out or closes in; random bars across or down; a zoom or a shape in or out. [DEFAULT]: the
 * transition's first option ([TransitionDir.of]).
 */
enum class TransitionDir {
    DEFAULT, FROM_RIGHT, FROM_LEFT, FROM_TOP, FROM_BOTTOM, IN, OUT, HORIZONTAL_OUT, HORIZONTAL_IN, VERTICAL_OUT, VERTICAL_IN;

    companion object {
        /** The options [t] has, its own first (none: it has no direction). */
        fun optionsOf(t: SlideTransition): List<TransitionDir> = when (t) {
            SlideTransition.PUSH, SlideTransition.COVER, SlideTransition.UNCOVER -> listOf(FROM_RIGHT, FROM_LEFT, FROM_TOP, FROM_BOTTOM)
            SlideTransition.WIPE -> listOf(FROM_LEFT, FROM_RIGHT, FROM_TOP, FROM_BOTTOM)
            SlideTransition.SPLIT -> listOf(HORIZONTAL_OUT, HORIZONTAL_IN, VERTICAL_OUT, VERTICAL_IN)
            SlideTransition.RANDOM_BARS -> listOf(HORIZONTAL_OUT, VERTICAL_OUT)
            SlideTransition.ZOOM, SlideTransition.SHAPE -> listOf(IN, OUT)
            else -> emptyList()
        }

        /** [dir] as [t] goes: its own when it is one of [t]'s options, else [t]'s first; DEFAULT for one without directions. */
        fun of(t: SlideTransition, dir: TransitionDir): TransitionDir = optionsOf(t).let { o -> if (dir in o) dir else o.firstOrNull() ?: DEFAULT }
    }
}

/** How long a slide transition takes: `p:transition@spd`, the schema's fast 0.5 s, medium 0.75 s and slow 1 s. */
enum class TransitionSpeed(val ms: Int) { SLOW(1000), MEDIUM(750), FAST(500) }

/**
 * A picture placed on a slide. [xPct]/[yPct]/[wPct]/[hPct] are fractions of the slide (the
 * PresentationML `a:xfrm`, like shapes); [widthPt]/[heightPt] are the source bitmap's pixel
 * size, kept so the aspect ratio can be restored after a free resize.
 */
class SlideImage(
    val data: ByteArray, val mime: String, var widthPt: Float, var heightPt: Float,
    var xPct: Float = 0.25f, var yPct: Float = 0.5f, var wPct: Float = 0.5f, var hPct: Float = 0.4f,
    override val anims: MutableList<ElementAnim> = mutableListOf(),
    override var cropL: Float = 0f, override var cropT: Float = 0f, override var cropR: Float = 0f, override var cropB: Float = 0f,
    /** Its outer shadow (PowerPoint's picture styles cast one), or none. */
    var shadow: ShapeShadow? = null,
    /** Turned clockwise about its centre, in degrees; mirrored left to right / top to bottom (Arrange → Rotate). */
    var rotationDeg: Float = 0f,
    var flipH: Boolean = false,
    var flipV: Boolean = false,
    /** The video or sound this picture stands for (its poster frame or icon), or none for a plain picture. */
    var media: SlideMedia? = null,
    /** What a click on it does in the slide show (see [SlideShape.action]). */
    var action: String? = null,
    /** Its alternative text (PowerPoint's Alt Text): what a screen reader says for it; or [decorative]: passed by. */
    var altText: String = "",
    var decorative: Boolean = false,
) : Croppable, AnimTarget, SlideObject {
    override var objectName: String? = null
    override var hidden: Boolean = false
    /** Source bitmap aspect (width / height); 4:3 when unknown. */
    val aspect: Float get() = if (widthPt > 0f && heightPt > 0f) widthPt / heightPt else 4f / 3f
    /** The aspect of the part the crop leaves. */
    val visibleAspect: Float get() = aspect * keptWidth() / keptHeight()

    /** What pointing at it does in the slide show (see [SlideShape.hoverAction]). */
    var hoverAction: String? = null

    fun copyOf(): SlideImage = SlideImage(data, mime, widthPt, heightPt, xPct, yPct, wPct, hPct, anims.mapTo(mutableListOf()) { it.copyOf() }, cropL, cropT, cropR, cropB, shadow,
        rotationDeg, flipH, flipV, media, action, altText, decorative).also { it.objectName = objectName; it.hidden = hidden; it.hoverAction = hoverAction }
}

/**
 * A video or sound on a slide, as PowerPoint keeps it: a picture (its poster frame or a speaker icon)
 * whose media is [data] in the file, or a file or address it links to ([link]) — named [name], of type
 * [mime]. AeroDesk shows it as that picture marked as media, and writes it back as it came.
 */
class SlideMedia(val kind: Kind, val mime: String, val data: ByteArray?, val link: String?, val name: String) {
    enum class Kind { VIDEO, AUDIO }
}

class Slide(
    val title: SlideBox = SlideBox(format = CharacterFormat(fontSizePt = 40f, bold = true), align = TextAlign.CENTER),
    val body: SlideBox = SlideBox(format = CharacterFormat(fontSizePt = 22f)),
    var background: Long? = null,
    var layout: SlideLayout = SlideLayout.TITLE_CONTENT,
    /** Pictures on the slide, in z/reveal order (multiple supported). */
    val images: MutableList<SlideImage> = mutableListOf(),
    var notes: String = "",
    var transition: SlideTransition = SlideTransition.NONE,
    var transitionSpeed: TransitionSpeed = TransitionSpeed.MEDIUM,
    /**
     * When set, this slide starts a named section (PowerPoint's 节): the section runs until the
     * next slide that carries a name. Riding on the slide keeps it right through reordering and
     * deletion, and one SlideDelta undoes it.
     */
    var sectionName: String? = null,
    /** Free-positioned text boxes / shapes layered over the slide. */
    val shapes: MutableList<SlideShape> = mutableListOf(),
    /** Review comments on this slide (PowerPoint's comments, LibreOffice's annotations). */
    val comments: MutableList<SlideComment> = mutableListOf(),
    /** Tables layered over the slide. */
    val tables: MutableList<SlideTable> = mutableListOf(),
    /** Charts layered over the slide. */
    val charts: MutableList<SlideChart> = mutableListOf(),
    /** Hidden slides are skipped during the slideshow. */
    var hidden: Boolean = false,
    /** Auto-advance after this many ms during the show (排练计时; OOXML p:transition@advTm). 0 = manual. */
    var advanceMs: Int = 0,
    /** The name of the deck's [SlideDesign] (a template's master page) drawn behind the slide; null = none. */
    var design: String? = null,
    /** Whether the design's pictures and shapes show (a slide can hide them, keeping its background). */
    var designObjects: Boolean = true,
    /** Its own background's gradient from [background], when it is one. */
    var backgroundShade: BackgroundShade? = null,
    /** Which way its transition goes (Effect Options); DEFAULT: the transition's own. */
    var transitionDir: TransitionDir = TransitionDir.DEFAULT,
    /** Who it is, whatever its place: what a link to it names (`#slide:id`, [SlideLinks]); copies (undo) keep it, a duplicate gets its own. */
    val id: Long = nextId(),
) {
    /** Back-compat: the first picture (most call sites predate multi-image support). */
    val image: SlideImage? get() = images.firstOrNull()

    /** Where its content placeholder (a Title and Content slide's body) is: the body's own frame, else the layout's. */
    val contentFrame: BoxFrame get() = body.frame ?: BoxFrame.defaultFor(layout, false)

    /** The slide offers its content placeholder to a table, chart or picture put on it: Title and Content, no text, not taken yet. */
    val contentFree: Boolean get() = layout == SlideLayout.TITLE_CONTENT && body.text.isBlank() && !contentTaken

    /**
     * Its content placeholder is taken, as PowerPoint's is once a table, chart, picture or video goes in it: the slide is
     * Title and Content, its body has no text, and a shown table, chart or picture (not a sound's icon) has its middle inside
     * the placeholder. It then asks for no text, and the writers leave it out.
     */
    val contentTaken: Boolean get() = contentTaker != null

    /** What has taken its content placeholder (see [contentTaken]): pptx writes it as the placeholder (`p:ph idx="1"`). */
    val contentTaker: SlideObject? get() {
        if (layout != SlideLayout.TITLE_CONTENT || body.text.isNotBlank()) return null
        val f = contentFrame
        fun inside(x: Float, y: Float, w: Float, h: Float) = (x + w / 2f) in f.xPct..(f.xPct + f.wPct) && (y + h / 2f) in f.yPct..(f.yPct + f.hPct)
        return tables.firstOrNull { !it.hidden && inside(it.xPct, it.yPct, it.wPct, it.hPct) }
            ?: charts.firstOrNull { !it.hidden && inside(it.xPct, it.yPct, it.wPct, it.hPct) }
            ?: images.firstOrNull { !it.hidden && it.media?.kind != SlideMedia.Kind.AUDIO && inside(it.xPct, it.yPct, it.wPct, it.hPct) }
    }

    /** A deep copy — the same slide ([id]) unless given another (a duplicate is a slide of its own). */
    fun copyOf(id: Long = this.id): Slide = Slide(title.copyOf(), body.copyOf(), background, layout, images.map { it.copyOf() }.toMutableList(), notes, transition, transitionSpeed, sectionName,
        shapes.map { it.copyOf() }.toMutableList(), comments.toMutableList(),
        tables.map { it.copyOf() }.toMutableList(), charts.map { it.copyOf() }.toMutableList(), hidden, advanceMs, design, designObjects, backgroundShade, transitionDir, id)

    companion object {
        private val counter = java.util.concurrent.atomic.AtomicLong()
        fun nextId(): Long = counter.incrementAndGet()
    }
}

/**
 * A background that shades from its colour to [second] — PowerPoint's Format Background → Gradient
 * fill, Impress's gradient area: linear along [angleDeg] (0 left → right, 90 top → bottom), axial
 * (the background colour at the edges), radial or rectangular out from ([cx], [cy]), as a shape's
 * gradient runs.
 */
data class BackgroundShade(val second: Long, val angleDeg: Float = 90f, val kind: GradientKind = GradientKind.LINEAR, val cx: Float = 0.5f, val cy: Float = 0.5f)

/**
 * The slide master (幻灯片母版): deck-wide defaults every slide inherits unless it sets its own —
 * a background plus the title and body text styles. Null fields mean "built-in default".
 * PowerPoint keeps these on `p:sldMaster` (`p:bg`, `p:txStyles`); a slide carrying its own
 * background overrides the master's.
 */
class SlideMaster(
    var background: Long? = null,
    var titleFont: String? = null,
    var titleSizePt: Float? = null,
    var titleColor: Long? = null,
    var titleBold: Boolean? = null,
    var bodyFont: String? = null,
    var bodySizePt: Float? = null,
    var bodyColor: Long? = null,
    /** Where the master puts the date, footer and number and how they read, where it is not PowerPoint's (see [SlideChrome]). */
    val chrome: MutableMap<SlideChrome.Part, ChromeLook> = mutableMapOf(),
    /** The master background's gradient from [background], when it is one. */
    var backgroundShade: BackgroundShade? = null,
) {
    val isDefault: Boolean
        get() = background == null && titleFont == null && titleSizePt == null && titleColor == null && titleBold == null &&
            bodyFont == null && bodySizePt == null && bodyColor == null && chrome.isEmpty() && backgroundShade == null

    /** Text defaults for the title boxes. */
    fun titleText(): MasterText = MasterText(titleFont, titleSizePt, titleColor, titleBold)
    /** Text defaults for the body boxes. */
    fun bodyText(): MasterText = MasterText(bodyFont, bodySizePt, bodyColor, null)

    fun copyOf(): SlideMaster = SlideMaster(background, titleFont, titleSizePt, titleColor, titleBold, bodyFont, bodySizePt, bodyColor, chrome.toMutableMap(), backgroundShade)
    fun assignFrom(o: SlideMaster) {
        background = o.background; titleFont = o.titleFont; titleSizePt = o.titleSizePt; titleColor = o.titleColor; titleBold = o.titleBold
        bodyFont = o.bodyFont; bodySizePt = o.bodySizePt; bodyColor = o.bodyColor
        chrome.clear(); chrome.putAll(o.chrome)
        backgroundShade = o.backgroundShade
    }
    fun sameAs(o: SlideMaster): Boolean =
        background == o.background && titleFont == o.titleFont && titleSizePt == o.titleSizePt && titleColor == o.titleColor &&
            titleBold == o.titleBold && bodyFont == o.bodyFont && bodySizePt == o.bodySizePt && bodyColor == o.bodyColor && chrome == o.chrome &&
            backgroundShade == o.backgroundShade
}

/**
 * A master page's own drawing — the design a template gives the slides on it: a background (a
 * colour, or a picture stretched over the slide) and the pictures and shapes drawn on the master (a
 * photograph, a band, a logo), in the order they are drawn, all behind the slide's own content.
 * Positions are fractions of the slide, as a slide's own elements'.
 */
class SlideDesign(
    val name: String,
    var background: Long? = null,
    var picture: SlideImage? = null,
    val items: MutableList<DesignItem> = mutableListOf(),
    /** Its master page's own title and body text defaults (a template's title slide in white …), over the deck's; null: the deck's. */
    var titleText: MasterText? = null,
    var bodyText: MasterText? = null,
    /** Where its master puts the date, footer and number and how they read, over the deck master's (see [SlideChrome]). */
    val chrome: MutableMap<SlideChrome.Part, ChromeLook> = mutableMapOf(),
    /** Its background's gradient from [background], when it is one. */
    var backgroundShade: BackgroundShade? = null,
) {
    /** Whether it draws anything but a colour. */
    val drawsSomething: Boolean get() = picture != null || items.isNotEmpty()
}

/** One thing a [SlideDesign] draws: a picture or a shape (a text box, a line …). */
sealed interface DesignItem
class DesignPicture(val image: SlideImage) : DesignItem
class DesignShape(val shape: SlideShape) : DesignItem

/** A master text style: what a title / body run shows for the attributes it does not set itself. */
class MasterText(val font: String?, val sizePt: Float?, val color: Long?, val bold: Boolean?)

/**
 * A deck's slide size in points — PowerPoint's Design → Slide Size: widescreen 13.333 × 7.5 in,
 * standard 10 × 7.5 in, on-screen 16:10 and A4; any other size a file brings is kept as it is.
 * Text is set in points, so the size is what text is sized against: a 40 pt title fills more of a
 * 10-inch slide than of a 13.333-inch one.
 */
/**
 * A review comment on a slide (PowerPoint's comment, LibreOffice's annotation): who wrote it, what, when, where it is pinned
 * ([x], [y]: fractions of the slide's width and height, its top left), its replies (a reply's own replies are not kept) and
 * whether it was resolved. Immutable: a change is a new comment.
 */
data class SlideComment(
    val author: String, val text: String, val dateMs: Long = System.currentTimeMillis(),
    val x: Float = 0f, val y: Float = 0f, val replies: List<SlideComment> = emptyList(), val resolved: Boolean = false,
)

data class SlideSize(val widthPt: Float, val heightPt: Float) {
    val aspect: Float get() = widthPt / heightPt

    companion object {
        val WIDESCREEN = SlideSize(960f, 540f)
        val STANDARD = SlideSize(720f, 540f)
        val SCREEN_16_10 = SlideSize(720f, 450f)
        val A4 = SlideSize(780f, 540f)
        val SCREEN_16_9 = SlideSize(720f, 405f)
        val LEDGER = SlideSize(959f, 719.25f)
        val A3 = SlideSize(1008f, 756f)
        val B4_ISO = SlideSize(852.5f, 639.375f)
        val B5_ISO = SlideSize(564.5f, 423.375f)
        val SLIDES_35MM = SlideSize(810f, 540f)
        val BANNER = SlideSize(576f, 72f)
        /** Every size Custom Slide Size names, each once (what a file's size snaps to). */
        val PRESETS: List<SlideSize> get() = SlideSizePreset.entries.map { it.size }.distinct()

        /** The smallest and largest side PowerPoint takes: 1 in to 56 in. */
        const val MIN_PT = 72f
        const val MAX_PT = 4032f

        /** [widthPt] × [heightPt], or the preset it is (within a point — files round). */
        fun of(widthPt: Float, heightPt: Float): SlideSize =
            PRESETS.firstOrNull { kotlin.math.abs(it.widthPt - widthPt) < 1f && kotlin.math.abs(it.heightPt - heightPt) < 1f }
                ?: SlideSize(widthPt.coerceIn(MIN_PT, MAX_PT), heightPt.coerceIn(MIN_PT, MAX_PT))
    }

    /** Turned to [portrait] (taller than wide) or landscape, the long side kept. */
    fun turned(portrait: Boolean): SlideSize = if ((heightPt > widthPt) == portrait || widthPt == heightPt) this else SlideSize(heightPt, widthPt)
}

/**
 * Design → Slide Size → Custom Slide Size → "Slides sized for": PowerPoint's sizes in its order, each with the name a pptx
 * gives it (`p:sldSz@type`; widescreen, like a size of one's own, has none). Letter paper and overhead are the 4:3 slide.
 */
enum class SlideSizePreset(val size: SlideSize, val pptxType: String?) {
    STANDARD(SlideSize.STANDARD, "screen4x3"),
    LETTER(SlideSize.STANDARD, "letter"),
    LEDGER(SlideSize.LEDGER, "ledger"),
    A3(SlideSize.A3, "A3"),
    A4(SlideSize.A4, "A4"),
    B4_ISO(SlideSize.B4_ISO, "B4ISO"),
    B5_ISO(SlideSize.B5_ISO, "B5ISO"),
    SLIDES_35MM(SlideSize.SLIDES_35MM, "35mm"),
    OVERHEAD(SlideSize.STANDARD, "overhead"),
    BANNER(SlideSize.BANNER, "banner"),
    SCREEN_16_9(SlideSize.SCREEN_16_9, "screen16x9"),
    SCREEN_16_10(SlideSize.SCREEN_16_10, "screen16x10"),
    WIDESCREEN(SlideSize.WIDESCREEN, null);

    companion object {
        /** The preset [size] is in either orientation (the first of those of that size), or null: a size of its own. */
        fun of(size: SlideSize): SlideSizePreset? = entries.firstOrNull { it.size == size || it.size == size.turned(false) }

        /** The preset a file's `p:sldSz@type` names, when it is the size given. */
        fun ofType(type: String?, size: SlideSize): SlideSizePreset? =
            entries.firstOrNull { it.pptxType == type && (it.size == size || it.size == size.turned(false)) } ?: of(size)
    }
}

/**
 * What the content does when the slides change shape (PowerPoint asks when scaling to a new slide size): [MAXIMIZE] keeps it
 * as large as the new slide allows, what no longer fits running past its edges; [ENSURE_FIT] scales it down to stay on it.
 */
enum class SlideScale { MAXIMIZE, ENSURE_FIT }

/**
 * Scaling a deck to a new slide size as PowerPoint does: everything on the slides, their designs and the master's text and
 * chrome scaled by one factor and centred on the new slide, so nothing is stretched — positions are fractions of the
 * slide, so without this a 16:9 deck turned 4:3 would squeeze every picture and shape.
 */
object SlideResize {
    /** Whether going from [from] to [to] changes the slide's shape (and so asks Maximize or Ensure Fit). */
    fun reshapes(from: SlideSize, to: SlideSize): Boolean = kotlin.math.abs(from.aspect / to.aspect - 1f) > 0.005f

    /** The factor everything grows (or shrinks) by. */
    fun factor(from: SlideSize, to: SlideSize, scale: SlideScale): Float {
        val wr = to.widthPt / from.widthPt; val hr = to.heightPt / from.heightPt
        return if (scale == SlideScale.MAXIMIZE) maxOf(wr, hr) else minOf(wr, hr)
    }

    /** [p]'s content scaled for [to] (its size not yet set). */
    fun apply(p: Presentation, to: SlideSize, scale: SlideScale) {
        val t = Transform(p.slideSize, to, factor(p.slideSize, to, scale))
        p.slides.forEach { sl ->
            listOf(sl.title, sl.body).forEach { b -> b.frame?.let { t.frame(it) }; t.text(b) }
            sl.shapes.forEach { t.shape(it) }
            sl.images.forEach { t.image(it) }
            sl.tables.forEach { tb -> t.place(tb.xPct, tb.yPct, tb.wPct, tb.hPct) { x, y, w, h -> tb.xPct = x; tb.yPct = y; tb.wPct = w; tb.hPct = h }; tb.cells.flatten().forEach { t.text(it) } }
            sl.charts.forEach { c -> t.place(c.xPct, c.yPct, c.wPct, c.hPct) { x, y, w, h -> c.xPct = x; c.yPct = y; c.wPct = w; c.hPct = h } }
        }
        val m = p.master
        m.titleSizePt = m.titleSizePt?.let { t.size(it) }; m.bodySizePt = m.bodySizePt?.let { t.size(it) }
        t.chrome(m.chrome)
        // A design is the deck's and never edited in place (thumbnails tell them apart by identity): a scaled copy.
        p.designs.replaceAll { d ->
            SlideDesign(d.name, d.background, d.picture, d.items.mapTo(mutableListOf()) { item ->
                when (item) {
                    is DesignPicture -> DesignPicture(item.image.copyOf().also { t.image(it) })
                    is DesignShape -> DesignShape(item.shape.copyOf().also { t.shape(it) })
                }
            }, d.titleText?.let { t.master(it) }, d.bodyText?.let { t.master(it) }, d.chrome.toMutableMap().also { t.chrome(it) }, d.backgroundShade)
        }
    }

    /** Fractions of the [from] slide to fractions of the [to] slide, everything [s] times its size, centred. */
    private class Transform(val from: SlideSize, val to: SlideSize, val s: Float) {
        private val dx = (to.widthPt - from.widthPt * s) / 2f
        private val dy = (to.heightPt - from.heightPt * s) / 2f
        fun x(f: Float) = (dx + f * from.widthPt * s) / to.widthPt
        fun y(f: Float) = (dy + f * from.heightPt * s) / to.heightPt
        fun w(f: Float) = f * from.widthPt * s / to.widthPt
        fun h(f: Float) = f * from.heightPt * s / to.heightPt
        fun place(x: Float, y: Float, w: Float, h: Float, set: (Float, Float, Float, Float) -> Unit) = set(x(x), y(y), w(w), h(h))
        /** Formats already scaled (runs may share one). */
        private val scaled = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<CharacterFormat, Boolean>())
        /** A point size scaled, to a tenth of a point. */
        fun size(pt: Float) = (Math.round(pt * s * 10f) / 10f).coerceAtLeast(1f)
        fun frame(f: BoxFrame) = place(f.xPct, f.yPct, f.wPct, f.hPct) { x, y, w, h -> f.xPct = x; f.yPct = y; f.wPct = w; f.hPct = h }
        fun text(b: SlideBox) = b.paragraph.runs.forEach { r -> if (scaled.add(r.format)) r.format.fontSizePt?.let { r.format.fontSizePt = size(it) } }
        fun shape(sh: SlideShape) {
            place(sh.xPct, sh.yPct, sh.wPct, sh.hPct) { x, y, w, h -> sh.xPct = x; sh.yPct = y; sh.wPct = w; sh.hPct = h }
            sh.borderWidthPt = (sh.borderWidthPt * s).coerceAtLeast(0.25f)
            text(sh.box)
        }
        fun image(img: SlideImage) = place(img.xPct, img.yPct, img.wPct, img.hPct) { x, y, w, h -> img.xPct = x; img.yPct = y; img.wPct = w; img.hPct = h }
        fun master(m: MasterText) = MasterText(m.font, m.sizePt?.let { size(it) }, m.color, m.bold)
        fun chrome(map: MutableMap<SlideChrome.Part, ChromeLook>) = map.replaceAll { _, c ->
            c.copy(xPct = x(c.xPct), yPct = y(c.yPct), wPct = w(c.wPct), hPct = h(c.hPct), sizePt = size(c.sizePt))
        }
    }
}

/** Set Up Slide Show → Show type: presented by a speaker (full screen), browsed by an individual (in a window), browsed at a kiosk (looping, no clicks). */
enum class ShowType { SPEAKER, WINDOW, KIOSK }

/** A custom slide show: its [name] and the slides it plays, in its own order, by [Slide.id]. */
data class CustomShow(val name: String, val slides: List<Long>)

/**
 * Set Up Slide Show (PowerPoint's `p:showPr`, Impress's `presentation:settings`): the show [type], whether
 * its [animations] play, and the slides it shows — slides [from]…[to] (numbers from 1; 0 = to the end, a [from]
 * of 0 all of them) or the custom show named [custom].
 */
data class ShowSetup(
    val type: ShowType = ShowType.SPEAKER,
    val animations: Boolean = true,
    val from: Int = 0,
    val to: Int = 0,
    val custom: String? = null,
)

/** The slides a show plays, as indices into the deck, in the order it plays them. */
object ShowPlan {
    /**
     * [p]'s show: from slide [fromSlide] on through the visible slides after it (From Current Slide), else the custom
     * show [custom] (or the one Set Up names) — hidden slides too, as a custom show names them —, else the
     * visible slides of the range Set Up gives, else all the visible slides. Never empty while there are slides.
     */
    fun of(p: Presentation, fromSlide: Int? = null, custom: String? = null): List<Int> {
        val slides = p.slides
        if (slides.isEmpty()) return emptyList()
        if (fromSlide != null) {
            val at = fromSlide.coerceIn(0, slides.lastIndex)
            return listOf(at) + (at + 1..slides.lastIndex).filter { !slides[it].hidden }
        }
        (custom ?: p.show.custom)?.let { name ->
            p.customShows.firstOrNull { it.name == name }?.let { cs ->
                val at = slides.withIndex().associate { (i, s) -> s.id to i }
                cs.slides.mapNotNull { at[it] }.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        val first = if (p.show.from > 0) (p.show.from - 1).coerceIn(0, slides.lastIndex) else 0
        val last = if (p.show.from > 0 && p.show.to > 0) (p.show.to - 1).coerceIn(first, slides.lastIndex) else slides.lastIndex
        return (first..last).filter { !slides[it].hidden }.ifEmpty { listOf(first) }
    }
}

class Presentation(
    val slides: MutableList<Slide> = mutableListOf(Slide()),
    var sourcePath: String? = null,
    /** The slides' size in points (PowerPoint's widescreen by default). */
    var slideSize: SlideSize = SlideSize.WIDESCREEN,
    /** Index into the design theme presets. */
    var themeIndex: Int = 0,
    /** Slideshow settings (放映). */
    var loopShow: Boolean = false,
    var useTimings: Boolean = true,
    var showNarration: Boolean = true,
    /** Footer text shown at the bottom of every slide (页眉和页脚 → 页脚); "" = none. */
    var footer: String = "",
    /** Show the slide number at the bottom-right of every slide. */
    var showSlideNumber: Boolean = false,
    /** Deck-wide defaults (background, title / body text) — see [SlideMaster]. */
    val master: SlideMaster = SlideMaster(),
    /** The designs a template's master pages give its slides (see [SlideDesign]); slides name theirs. */
    val designs: MutableList<SlideDesign> = mutableListOf(),
    /** Header & Footer → Date and time shown at the foot of every slide, or none (see [SlideChrome]). */
    var date: SlideDate? = null,
    /** Header & Footer → Don't show on title slide: title slides without the date, footer and number. */
    var hideFooterOnTitle: Boolean = false,
    /** Set Up Slide Show: how the show is given, which slides it plays, with or without its animations. */
    var show: ShowSetup = ShowSetup(),
    /** Custom Slide Show: named selections of the slides, each in an order of its own. */
    var customShows: List<CustomShow> = emptyList(),
    /** Slide Size → Number slides from: the number the first slide shows (`p:presentation@firstSlideNum`). */
    var firstSlideNumber: Int = 1,
) : HasProps {
    /** Wider than 3:2 — 16:9, 16:10 — rather than 4:3 or A4. */
    val widescreen: Boolean get() = slideSize.aspect > 1.5f

    /** The background a slide shows: its own, else its design's, else the master's (null = white). */
    fun backgroundOf(slide: Slide): Long? = slide.background ?: designOf(slide)?.background ?: master.background

    /** The gradient [slide]'s background runs from [backgroundOf]'s colour, when it is one: from wherever that colour comes. */
    fun backgroundShadeOf(slide: Slide): BackgroundShade? = when {
        slide.background != null -> slide.backgroundShade
        designOf(slide)?.background != null -> designOf(slide)?.backgroundShade
        else -> master.backgroundShade
    }

    /** The design [slide] is drawn on, or null. */
    fun designOf(slide: Slide): SlideDesign? = slide.design?.let { name -> designs.firstOrNull { it.name == name } }

    /**
     * What [slide]'s text sits on: its background colour — null (unknown) where its design's picture
     * or drawing shows, which a template's text colours are chosen to sit on.
     */
    fun backdropOf(slide: Slide): Long? {
        val d = designOf(slide)
        if (d != null && ((slide.background == null && d.picture != null) || (slide.designObjects && d.items.isNotEmpty()))) return null
        val bg = backgroundOf(slide) ?: 0xFFFFFFFF
        // A gradient: what lies halfway along it.
        return backgroundShadeOf(slide)?.let { TextColors.blend((it.second and 0xFFFFFF) or 0x80000000, bg or 0xFF000000) } ?: bg
    }

    /** The title's text defaults on [slide]: its design's over the master's, the colour readable over the slide ([TextColors]). */
    fun titleTextOn(slide: Slide): MasterText = master.titleText().under(designOf(slide)?.titleText).over(backdropOf(slide))

    /** The body's text defaults on [slide]: its design's over the master's, the colour readable over the slide. */
    fun bodyTextOn(slide: Slide): MasterText = master.bodyText().under(designOf(slide)?.bodyText).over(backdropOf(slide))

    /** A shape's text defaults on [slide]: a colour readable over its fill, or — unfilled — over the slide. */
    fun shapeTextOn(slide: Slide, sh: SlideShape): MasterText =
        MasterText(null, null, TextColors.over(null, sh.textBackdrop(backdropOf(slide))), null)

    // Designs come from the file and are not edited in the app: copies share them.
    fun copyOf(): Presentation = Presentation(slides.map { it.copyOf() }.toMutableList(), sourcePath, slideSize, themeIndex, loopShow, useTimings, showNarration, footer, showSlideNumber, master.copyOf(),
        designs.toMutableList(), date, hideFooterOnTitle, show, customShows, firstSlideNumber).also { it.props = props }

    /** The file's properties (File → Info). */
    override var props: DocProps = DocProps()

    companion object {
        /**
         * A new deck as PowerPoint starts one: a single Title Slide whose title and subtitle are empty placeholders (the
         * canvas shows their prompts; nothing of them is typed into the file).
         */
        fun blank(): Presentation = Presentation(mutableListOf(Slide(
            title = SlideBox("", CharacterFormat(fontSizePt = 40f, bold = true), TextAlign.CENTER),
            body = SlideBox("", CharacterFormat(fontSizePt = 22f), TextAlign.CENTER),
            layout = SlideLayout.TITLE,
        )))
    }
}
