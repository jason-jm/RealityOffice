package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.model.BorderLine
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.FormatCode
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.VAlign

/**
 * The style tables of an OpenDocument package: `office:automatic-styles` (the per-document
 * `P1`/`T1`/`ce1` styles a producer generates) and `office:styles` (the named ones), read from
 * both `content.xml` and `styles.xml`.
 *
 * A style may inherit through `style:parent-style-name`, so lookups resolve the chain; anything a
 * child does not set comes from its parent.
 */
internal class OdfStyles private constructor() {

    /** ODF's drop cap: [lines] tall, the first [length] characters ([WORD]: the first word), [distancePt] from the text, its letters in text style [style]. */
    class DropCapProps(val lines: Int, val length: Int, val distancePt: Float, val style: String?) {
        companion object { const val WORD = 0 }
    }

    class Paragraph(
        val align: TextAlign? = null,
        val spaceBeforePt: Float? = null,
        val spaceAfterPt: Float? = null,
        val indentLeftPt: Float? = null,
        val lineSpacing: Float? = null,
        val parent: String? = null,
        val text: CharacterFormat = CharacterFormat(),
        val outlineLevel: Int? = null,
        /** `fo:keep-with-next`, `fo:keep-together`, `fo:break-before="page"`, widows/orphans ≠ 0; null = inherit. */
        val keepWithNext: Boolean? = null,
        val keepTogether: Boolean? = null,
        val breakBefore: Boolean? = null,
        val widowControl: Boolean? = null,
        /** `fo:text-indent` (negative: hanging) and `fo:margin-right`; LibreOffice writes a first line's characters as `loext:text-indent="2ic"`. */
        val firstLineIndent: FontLength? = null,
        val indentRight: FontLength? = null,
        /** `style:tab-stops`, their positions as the file has them (from the paragraph's indent unless the document says otherwise). */
        val tabStops: List<com.therealityteam.aeropages.model.TabStop>? = null,
        /** `style:snap-to-layout-grid`: whether its lines keep to the page's line grid; null = inherit. */
        val snapToGrid: Boolean? = null,
        /** An exact line height (`fo:line-height` as a length) or a least one (`style:line-height-at-least`), points; null = none. */
        val lineHeightPt: Float? = null,
        val lineExact: Boolean = true,
        /**
         * Its borders as ODF has them (`fo:border-*`, each side's space its `fo:padding-*`; standing at the paragraph's
         * margin, the padding inside them) — none when it says none; null: its parent's.
         */
        val borders: com.therealityteam.aeropages.model.ParagraphBorders? = null,
        /** `fo:background-color` (transparent: 0 — none, stopping its parent's); null: its parent's. */
        val background: Long? = null,
        /** `text:number-lines`: whether its lines are numbered (false: Word's "suppress line numbers"); null: its parent's. */
        val numberLines: Boolean? = null,
        /** `style:drop-cap` (lines 2 or more; null: its parent's). */
        val dropCap: DropCapProps? = null,
    ) {
        /** This style with [t] as its text. */
        fun withText(t: CharacterFormat): Paragraph =
            Paragraph(align, spaceBeforePt, spaceAfterPt, indentLeftPt, lineSpacing, parent, t, outlineLevel, keepWithNext, keepTogether, breakBefore, widowControl, firstLineIndent, indentRight, tabStops,
                snapToGrid, lineHeightPt, lineExact, borders, background, numberLines, dropCap)

        /** The room each side's line and padding take beside the text: left and right (ODF's margin is outside them). */
        val boxLeft: Float get() = borders?.left?.let { it.thicknessPt + (borders.spaceLeft) } ?: 0f
        val boxRight: Float get() = borders?.right?.let { it.thicknessPt + (borders.spaceRight) } ?: 0f
    }

    /** `style:tab-stops`: each stop's position, alignment (a `char` stop on its decimal character) and leader. */
    private fun tabStops(node: OdfNode): List<com.therealityteam.aeropages.model.TabStop> = node.children("tab-stop").mapNotNull { t ->
        val pos = OdfValue.twipsPt(t.attr("position")) ?: return@mapNotNull null
        val align = when (t.attr("type")) {
            "center" -> com.therealityteam.aeropages.model.TabAlign.CENTER
            "right" -> com.therealityteam.aeropages.model.TabAlign.RIGHT
            "char" -> com.therealityteam.aeropages.model.TabAlign.DECIMAL
            else -> com.therealityteam.aeropages.model.TabAlign.LEFT
        }
        val text = t.attr("leader-text")?.trim()
        val leader = when {
            text == "." || text == "…" -> com.therealityteam.aeropages.model.TabLeader.DOT
            text == "-" || text == "–" -> com.therealityteam.aeropages.model.TabLeader.HYPHEN
            text == "_" -> com.therealityteam.aeropages.model.TabLeader.UNDERSCORE
            text == "·" -> com.therealityteam.aeropages.model.TabLeader.MIDDLE_DOT
            else -> when (t.attr("leader-style")) {
                "dotted" -> com.therealityteam.aeropages.model.TabLeader.DOT
                "dash", "long-dash" -> com.therealityteam.aeropages.model.TabLeader.HYPHEN
                "solid" -> com.therealityteam.aeropages.model.TabLeader.UNDERSCORE
                else -> com.therealityteam.aeropages.model.TabLeader.NONE
            }
        }
        com.therealityteam.aeropages.model.TabStop(pos, align, leader)
    }

    /** A length in points or — `ic`, `em` — so many times the paragraph's font size. */
    class FontLength(val value: Float, val ofFont: Boolean) {
        fun pt(fontPt: Float): Float = if (ofFont) value * fontPt else value

        companion object {
            fun of(v: String?): FontLength? {
                val s = v?.trim()?.lowercase() ?: return null
                if (s.endsWith("ic") || s.endsWith("em")) return s.dropLast(2).trim().toFloatOrNull()?.let { FontLength(it, true) }
                return OdfValue.twipsPt(s)?.let { FontLength(it, false) }
            }
        }
    }

    class CellStyle(
        val text: CharacterFormat = CharacterFormat(),
        val align: TextAlign? = null,
        val vAlign: VAlign? = null,
        val wrap: Boolean = false,
        val background: Long? = null,
        val borderTop: BorderLine? = null,
        val borderBottom: BorderLine? = null,
        val borderLeft: BorderLine? = null,
        val borderRight: BorderLine? = null,
        val dataStyle: String? = null,
        val parent: String? = null,
        val unlocked: Boolean = false,
        /** Its `fo:border-*` values as written (top, bottom, left, right; `fo:border` for any not named), for a document's table cells. */
        val edges: List<String?> = listOf(null, null, null, null),
        /** `style:rotation-angle` as Excel turns text (−90…90); null = not set here. */
        val rotation: Int? = null,
        /** `style:direction="ttb"`: one character under another; null = not set here. */
        val stacked: Boolean? = null,
        /** `fo:margin-left` in indent levels of 7.5 pt; null = not set here. */
        val indent: Int? = null,
        /** `style:repeat-content` (Fill) / `css3t:text-justify="distribute"` (Distributed); null = not set here. */
        val spread: com.therealityteam.aeropages.model.CellSpread? = null,
        /** `style:shrink-to-fit`; null = not set here. */
        val shrink: Boolean? = null,
    )

    /** A number style: the plain format closest to it, and ([code]) the format code it shows when it is not one of those. */
    class DataStyle(val format: NumberFormat, val decimals: Int, val code: String? = null)

    /**
     * A `graphic` / `presentation` family style's shape properties (fill, outline, text
     * anchor); every field null = not set at this level (inherits from the parent style).
     */
    class GraphicStyle(
        /** true = solid fill, false = `draw:fill="none"`, null = unset. */
        val filled: Boolean? = null,
        val fillColor: Long? = null,
        /** `draw:opacity`: how opaque the fill is (0..1). */
        val opacity: Float? = null,
        /** `draw:opacity-name`: the `draw:opacity` gradient its fill fades by. */
        val opacityName: String? = null,
        val stroked: Boolean? = null,
        val strokeColor: Long? = null,
        val strokeWidthPt: Float? = null,
        /** `draw:textarea-vertical-align`: top / middle / bottom. */
        val vAlign: String? = null,
        val parent: String? = null,
        /** A picture's crop, `fo:clip="rect(top, right, bottom, left)"` in lengths of its natural size. */
        val clip: String? = null,
        /** The `draw:gradient` this level names — what fills when the style's `draw:fill` is "gradient" (see [activeGradient]). */
        val gradient: String? = null,
        /** The `draw:stroke-dash` this level names — what dashes when the style's `draw:stroke` is "dash" (see [activeDash]). */
        val strokeDash: String? = null,
        /** `fo:wrap-option`: "no-wrap" keeps a shape's lines unbroken. */
        val wrapOption: String? = null,
        /** A line's markers (`draw:marker-start` / `-end`) and how wide each is drawn. */
        val markerStart: String? = null,
        val markerStartWidthPt: Float? = null,
        val markerEnd: String? = null,
        val markerEndWidthPt: Float? = null,
        /** `draw:fill` and `draw:stroke` as written: none, solid, gradient, bitmap … / none, solid, dash. */
        val fill: String? = null,
        val stroke: String? = null,
        /** `draw:shadow` (visible / hidden) and its offset, colour, opacity and (LibreOffice's) blur. */
        val shadow: Boolean? = null,
        val shadowDxPt: Float? = null,
        val shadowDyPt: Float? = null,
        val shadowColor: Long? = null,
        val shadowOpacity: Float? = null,
        val shadowBlurPt: Float? = null,
        /** A picture's mirroring (`style:mirror`: none, horizontal, vertical or both). */
        val mirror: String? = null,
        /** LibreOffice's decorative mark on a picture (`loext:decorative`). */
        val decorative: Boolean? = null,
        /**
         * Where a frame anchored to a paragraph or the page stands and how the text goes by it:
         * `style:wrap` (none, left, right, parallel, dynamic, run-through …) and `style:run-through`
         * (background, foreground); `style:horizontal-pos` / `-rel`, `style:vertical-pos` / `-rel`.
         */
        val wrap: String? = null,
        val runThrough: String? = null,
        val hPos: String? = null,
        val hRel: String? = null,
        val vPos: String? = null,
        val vRel: String? = null,
    ) {
        /** The gradient that fills: the named one when the fill is a gradient — a level that fills solid does not take its parent's. */
        val activeGradient: String? get() = gradient.takeIf { fill == "gradient" }

        /** The dashes the outline is drawn in, when it is dashed. */
        val activeDash: String? get() = strokeDash.takeIf { stroke == "dash" }
    }

    /** A `draw:marker` (an arrowhead): its display name and the box its outline is drawn in. */
    class Marker(val name: String, val viewW: Float, val viewH: Float)

    /**
     * A `draw:stroke-dash`: [dots1] dashes [dots1Length] long, then [dots2] of [dots2Length], with
     * [distance] between — lengths, or percentages of the line's width.
     */
    class StrokeDash(val dots1: Int, val dots1Length: String?, val dots2: Int, val dots2Length: String?, val distance: String?)

    /** A `draw:gradient`: its [style] (linear, axial, radial …), colours and [angleDeg] (ODF's: 0 runs top to bottom, turning anticlockwise). */
    /** A `draw:gradient`: its kind (linear, axial, radial, ellipsoid, square, rectangular), colours, angle and centre (fractions). */
    class Gradient(val style: String?, val start: Long?, val end: Long?, val angleDeg: Float, val cx: Float = 0.5f, val cy: Float = 0.5f)

    private val paragraphs = HashMap<String, Paragraph>()
    /**
     * The document's named paragraph styles (`office:styles`, not the automatic ones), in file order: name → its
     * display name and `style:class` (LibreOffice's own pool styles have a class — text, list, extra, index —
     * a style someone made has none).
     */
    val namedParagraphs = LinkedHashMap<String, Pair<String?, String?>>()
    /** Font declarations (`style:font-face`): a style's `style:font-name` → the family it names. */
    private val fontFaces = HashMap<String, String>()
    /** Paragraph styles sized relatively (`fo:font-size="130%"`): the factor of their parent's size. */
    private val sizePercent = HashMap<String, Float>()
    /** Paragraph and table styles that start a page of another master page (`style:master-page-name`). */
    private val masterPages = HashMap<String, String>()
    /** Section styles' text columns (`style:columns fo:column-count`). */
    private val sectionColumns = HashMap<String, Int>()
    /** Table styles of hidden sheets (`table:display="false"`), and the tab colours table styles give (ODF 1.3 `table:tab-color`, LibreOffice's older `tableooo:tab-color`). */
    private val hiddenTables = HashSet<String>()
    private val tabColors = HashMap<String, Long>()
    /** Line numbers (`text:linenumbering-configuration` with number-lines on), or null. */
    var lineNumbers: com.therealityteam.aeropages.model.LineNumbering? = null

    /** The paragraphs' default text (`style:default-style style:family="paragraph"`), or null. */
    var defaultParagraphText: CharacterFormat? = null
        private set
    /** How far apart the default tab stops are (`style:tab-stop-distance` of the paragraphs' default style), or null. */
    var defaultTabPt: Float? = null
        private set
    private val texts = HashMap<String, CharacterFormat>()
    /** Text styles that explicitly switch a trait OFF ("normal" / "none"), so a span can undo its paragraph's bold etc. */
    private val textsOff = HashMap<String, Set<String>>()
    private val cells = HashMap<String, CellStyle>()
    private val dataNodes = HashMap<String, OdfNode>()
    private val dataStyles = HashMap<String, DataStyle?>()
    private val columnWidthPt = HashMap<String, Float>()
    private val rowHeightPt = HashMap<String, Float>()
    /** A text table's own width, alignment and left margin (`style:width`, `table:align`, `fo:margin-left`), by its style. */
    class TableLayout(val widthPt: Float?, val align: String?, val marginLeftPt: Float?)
    private val tableLayouts = HashMap<String, TableLayout>()
    /** A text table row's least height (`style:min-row-height`), by its style; exact ones are [rowHeightPt]. */
    private val rowMinHeightPt = HashMap<String, Float>()
    private val lists = HashMap<String, ListKind>()
    private val listDefs = HashMap<String, com.therealityteam.aeropages.model.ListDef>()
    /** The headings' numbering (`text:outline-style`), when it numbers them. */
    var outline: com.therealityteam.aeropages.model.ListDef? = null
        private set
    private val pageBackground = HashMap<String, Long>()
    private val graphics = HashMap<String, GraphicStyle>()
    /** Impress table cells' fills by their `table-cell` style (LibreOffice writes them as `loext:graphic-properties`). */
    private val tableCellFills = HashMap<String, Long>()

    /** The fill colour [name], a `table-cell` style, gives a slide table's cell (transparent for no fill); null when it says none. */
    fun tableCellFill(name: String?): Long? = name?.let { tableCellFills[it] }
    private val tableCellSides = HashMap<String, Array<String?>>()
    private val tableCellAnchors = HashMap<String, String>()

    /** Where [name], a `table-cell` style, puts a slide table cell's text: top, middle, bottom (`draw:textarea-vertical-align`); null when it says nothing. */
    fun tableCellAnchor(name: String?): String? = name?.let { tableCellAnchors[it] }

    /** The `fo:border` value [name], a `table-cell` style, gives a slide table cell's side [side] (top, bottom, left, right); null when it says nothing. */
    fun tableCellSide(name: String?, side: Int): String? = name?.let { tableCellSides[it]?.getOrNull(side) }
    private val markers = HashMap<String, Marker>()
    private val strokeDashes = HashMap<String, StrokeDash>()
    private val gradients = HashMap<String, Gradient>()
    /** drawing-page style → (smil:type, presentation:transition-speed). */
    private val pageTransition = HashMap<String, Pair<String, String?>>()
    private val pageTransitionWay = HashMap<String, Pair<String, Boolean>>()
    /** Drawing-page styles of hidden slides (`presentation:visibility="hidden"`). */
    private val hiddenPages = HashSet<String>()
    /** Drawing-page styles filled with a picture (the `draw:fill-image` they name) or a gradient (its name). */
    private val pageFillImage = HashMap<String, String>()
    private val pageGradient = HashMap<String, String>()
    /** Drawing-page styles that hide the master's objects (`presentation:background-objects-visible="false"`). */
    private val pagesWithoutObjects = HashSet<String>()
    /** Drawing-page styles' header / footer switches (`presentation:display-footer` …), as written, by style and attribute. */
    private val pageDisplays = HashMap<String, Map<String, Boolean>>()
    /** Named pictures (`draw:fill-image`): name → the package path of its bytes. */
    private val fillImages = HashMap<String, String>()

    fun paragraph(name: String?): Paragraph? = name?.let { paragraphs[it] }
    fun text(name: String?): CharacterFormat? = name?.let { texts[it] }
    /** Traits ("bold", "italic", "underline", "strikethrough") the text style turns off explicitly. */
    fun textTurnsOff(name: String?): Set<String> = name?.let { textsOff[it] } ?: emptySet()
    fun listKind(name: String?): ListKind? = name?.let { lists[it] }
    /** A list style's levels. */
    fun listDef(name: String?): com.therealityteam.aeropages.model.ListDef? = name?.let { listDefs[it] }
    fun columnWidth(name: String?): Float? = name?.let { columnWidthPt[it] }

    /** Sheet row and column styles that start a printed page (`fo:break-before="page"`: a manual page break). */
    private val pageBreakStyles = HashSet<String>()

    /** Whether the row or column style [name] starts a page. */
    fun breaksPage(name: String?): Boolean = name != null && name in pageBreakStyles
    fun rowHeight(name: String?): Float? = name?.let { rowHeightPt[it] }
    fun rowMinHeight(name: String?): Float? = name?.let { rowMinHeightPt[it] }
    fun tableLayout(name: String?): TableLayout? = name?.let { tableLayouts[it] }
    /** A drawing-page style's fill colour — a gradient's first colour standing in for it. */
    fun pageFill(name: String?): Long? = name?.let { pageBackground[it] ?: pageGradient[it]?.let { g -> pageGradientColors(g)?.first } }

    /**
     * A page's gradient as the app keeps a background's: from its first colour (an ODF radial or axial
     * gradient's end colour is at its centre — the app's first) to its second, along its angle.
     */
    fun pageShade(name: String?): com.therealityteam.aeropages.model.BackgroundShade? {
        val g = name?.takeIf { pageBackground[it] == null }?.let { pageGradient[it] }?.let { gradients[it] } ?: return null
        val (_, second) = pageGradientColors(pageGradient[name]!!) ?: return null
        val kind = OdfLines.gradientKind(g.style)
        val angle = if (kind == com.therealityteam.aeropages.model.GradientKind.RADIAL) 0f else OdfLines.odfAngle(g.angleDeg)
        return com.therealityteam.aeropages.model.BackgroundShade(second, angle, kind, g.cx, g.cy)
    }

    /** A gradient's colours in the app's order: first (a linear one's start, a centred or axial one's end) and second. */
    private fun pageGradientColors(gradient: String): Pair<Long, Long>? {
        val g = gradients[gradient] ?: return null
        val start = g.start ?: return null
        val end = g.end ?: start
        return if (OdfLines.gradientKind(g.style) == com.therealityteam.aeropages.model.GradientKind.LINEAR) start to end else end to start
    }
    /** The package path of the picture a drawing-page style is filled with, or null. */
    fun pageFillPicture(name: String?): String? = name?.let { pageFillImage[it] }?.let { fillImages[it] }
    /** Whether a drawing-page style hides the master page's objects. */
    fun pageHidesObjects(name: String?): Boolean = name != null && name in pagesWithoutObjects
    fun pageTransition(name: String?): Pair<String, String?>? = name?.let { pageTransition[it] }
    /** A drawing-page style's transition subtype (`smil:subtype`) and whether it runs in reverse (`smil:direction`). */
    fun pageTransitionWay(name: String?): Pair<String, Boolean>? = name?.let { pageTransitionWay[it] }
    fun pageHidden(name: String?): Boolean = name != null && name in hiddenPages
    /** Whether a table style hides its sheet from the tabs. */
    fun tableHidden(name: String?): Boolean = name != null && name in hiddenTables
    /** The tab colour a table style gives its sheet, or null. */
    fun tabColor(name: String?): Long? = name?.let { tabColors[it] }
    /** A drawing-page style's `presentation:[what]` switch (display-footer, display-page-number, display-date-time), or null when it says nothing. */
    fun pageDisplays(name: String?, what: String): Boolean? = name?.let { pageDisplays[it] }?.get(what)
    /** The format code the data style [name] spells (a date's `yyyy/m/d` …), or null. */
    fun dataCode(name: String?): String? = name?.let { dataNodes[it] }?.let { OdfNumberStyles.codeOf(it, dataNodes) }?.takeIf { FormatCode.isValid(it) }
    /** The data style [name]'s title (`number:title`), or null. */
    fun dataTitle(name: String?): String? = name?.let { dataNodes[it] }?.attr("title")?.takeIf { it.isNotBlank() }
    fun marker(name: String?): Marker? = name?.let { markers[it] }
    fun strokeDash(name: String?): StrokeDash? = name?.let { strokeDashes[it] }
    fun gradient(name: String?): Gradient? = name?.let { gradients[it] }

    /** A `draw:opacity` gradient: how opaque a fill is at its start and its end (0..1), along its style and angle as a colour gradient runs. */
    class Opacity(val style: String?, val start: Float, val end: Float, val angleDeg: Float, val cx: Float = 0.5f, val cy: Float = 0.5f)

    val opacities = HashMap<String, Opacity>()

    fun opacity(name: String?): Opacity? = name?.let { opacities[it] }

    /**
     * A cell style's own look as a conditional format gives it (its parent's left out): its fill, text
     * colour, and the font traits it turns on — found by its name or, as LibreOffice's conditions name
     * it, its display name (`ConditionalStyle_1` for `ConditionalStyle_5f_1`).
     */
    fun condStyle(name: String?): com.therealityteam.aeropages.model.CondStyle? {
        val n = name ?: return null
        val s = cells[n] ?: cells[encodedName(n)] ?: return null
        return com.therealityteam.aeropages.model.CondStyle(
            fill = s.background, font = s.text.color,
            bold = true.takeIf { s.text.bold }, italic = true.takeIf { s.text.italic },
            underline = true.takeIf { s.text.underline }, strike = true.takeIf { s.text.strikethrough },
        )
    }


    /** The cell style with its parent chain resolved, plus the number format its data style names. */
    fun cell(name: String?): CellStyle? {
        var s = name?.let { cells[it] } ?: return null
        var parent = s.parent
        var guard = 0
        while (parent != null && guard++ < 8) {
            val p = cells[parent] ?: break
            s = CellStyle(
                text = mergeText(p.text, s.text),
                align = s.align ?: p.align,
                vAlign = s.vAlign ?: p.vAlign,
                wrap = s.wrap || p.wrap,
                background = s.background ?: p.background,
                borderTop = s.borderTop ?: p.borderTop,
                borderBottom = s.borderBottom ?: p.borderBottom,
                borderLeft = s.borderLeft ?: p.borderLeft,
                borderRight = s.borderRight ?: p.borderRight,
                dataStyle = s.dataStyle ?: p.dataStyle,
                parent = p.parent,
                unlocked = s.unlocked || p.unlocked,
                edges = s.edges.zip(p.edges) { a, b -> a ?: b },
                rotation = s.rotation ?: p.rotation, stacked = s.stacked ?: p.stacked, indent = s.indent ?: p.indent,
                spread = s.spread ?: p.spread, shrink = s.shrink ?: p.shrink,
            )
            parent = s.parent
        }
        return s
    }

    /** Number style [name], read when first asked for (its maps may name styles in the other part). */
    fun numberFormat(name: String?): DataStyle? {
        if (name == null) return null
        if (name in dataStyles) return dataStyles[name]
        return dataNodes[name]?.let { readDataStyle(it) }.also { dataStyles[name] = it }
    }

    /**
     * A style and the styles it derives from, nearest first — `P1`, `Heading_20_1`, `Standard`.
     * The generated name says nothing; the ancestry is where the document's meaning lives.
     */
    fun ancestry(name: String?): List<String> {
        val out = ArrayList<String>()
        var current = name ?: return out
        var guard = 0
        while (guard++ < 8) {
            out.add(current)
            current = paragraphs[current]?.parent ?: break
        }
        return out
    }

    /** The graphic style with its parent chain resolved (a shape's `draw:style-name`). */
    fun resolvedGraphic(name: String?): GraphicStyle? {
        var s = graphics[name ?: return null] ?: return null
        var parent = s.parent
        var guard = 0
        while (parent != null && guard++ < 8) {
            val p = graphics[parent] ?: break
            s = GraphicStyle(
                filled = s.filled ?: p.filled,
                fillColor = s.fillColor ?: p.fillColor,
                opacity = s.opacity ?: p.opacity,
                opacityName = s.opacityName ?: p.opacityName,
                stroked = s.stroked ?: p.stroked,
                strokeColor = s.strokeColor ?: p.strokeColor,
                strokeWidthPt = s.strokeWidthPt ?: p.strokeWidthPt,
                vAlign = s.vAlign ?: p.vAlign,
                parent = p.parent,
                clip = s.clip ?: p.clip,
                gradient = s.gradient ?: p.gradient,
                strokeDash = s.strokeDash ?: p.strokeDash,
                wrapOption = s.wrapOption ?: p.wrapOption,
                markerStart = s.markerStart ?: p.markerStart,
                markerStartWidthPt = s.markerStartWidthPt ?: p.markerStartWidthPt,
                markerEnd = s.markerEnd ?: p.markerEnd,
                markerEndWidthPt = s.markerEndWidthPt ?: p.markerEndWidthPt,
                fill = s.fill ?: p.fill,
                stroke = s.stroke ?: p.stroke,
                shadow = s.shadow ?: p.shadow,
                shadowDxPt = s.shadowDxPt ?: p.shadowDxPt,
                shadowDyPt = s.shadowDyPt ?: p.shadowDyPt,
                shadowColor = s.shadowColor ?: p.shadowColor,
                shadowOpacity = s.shadowOpacity ?: p.shadowOpacity,
                shadowBlurPt = s.shadowBlurPt ?: p.shadowBlurPt,
                mirror = s.mirror ?: p.mirror,
                decorative = s.decorative ?: p.decorative,
                wrap = s.wrap ?: p.wrap,
                runThrough = s.runThrough ?: p.runThrough,
                hPos = s.hPos ?: p.hPos,
                hRel = s.hRel ?: p.hRel,
                vPos = s.vPos ?: p.vPos,
                vRel = s.vRel ?: p.vRel,
            )
            parent = s.parent
        }
        return s
    }

    /**
     * Paragraph style [name]'s text size: the nearest size set up its parent chain, times the
     * percentages on the way (LibreOffice's Heading 1 is 130% of its Heading's 14 pt), the factor of
     * [base] where none is set; null when nothing on the way says one.
     */
    fun resolvedSizePt(name: String?, base: Float): Float? {
        var factor = 1f
        var said = false
        for (style in ancestry(name)) {
            paragraphs[style]?.text?.fontSizePt?.let { return it * factor }
            sizePercent[style]?.let { factor *= it; said = true }
        }
        return if (said) base * factor else null
    }

    /** The master page a paragraph or table style starts a page of, or null (its own, else its parents'). */
    /** The page number a paragraph style starts its page from (`style:page-number`), when it says one. */
    fun pageNumberOf(name: String?): Int? = name?.let { pageNumbers[it] }
    private val pageNumbers = HashMap<String, Int>()

    fun masterPageOf(name: String?): String? = ancestry(name).firstNotNullOfOrNull { masterPages[it] } ?: name?.let { masterPages[it] }

    /** A section style's text columns, or null. */
    fun columnsOf(sectionStyle: String?): Int? = sectionStyle?.let { sectionColumns[it] }

    /** The paragraph style with its parent chain resolved. */
    fun resolvedParagraph(name: String?): Paragraph? {
        var s = paragraph(name) ?: return null
        var parent = s.parent
        var guard = 0
        while (parent != null && guard++ < 8) {
            val p = paragraphs[parent] ?: break
            s = Paragraph(
                align = s.align ?: p.align,
                spaceBeforePt = s.spaceBeforePt ?: p.spaceBeforePt,
                spaceAfterPt = s.spaceAfterPt ?: p.spaceAfterPt,
                indentLeftPt = s.indentLeftPt ?: p.indentLeftPt,
                // Line spacing is one property: a style that spaces its lines in any way does not take its parent's.
                lineSpacing = if (s.lineSpacing != null || s.lineHeightPt != null) s.lineSpacing else p.lineSpacing,
                lineHeightPt = if (s.lineSpacing != null || s.lineHeightPt != null) s.lineHeightPt else p.lineHeightPt,
                lineExact = if (s.lineSpacing != null || s.lineHeightPt != null) s.lineExact else p.lineExact,
                parent = p.parent,
                text = mergeText(p.text, s.text),
                outlineLevel = s.outlineLevel ?: p.outlineLevel,
                keepWithNext = s.keepWithNext ?: p.keepWithNext,
                keepTogether = s.keepTogether ?: p.keepTogether,
                breakBefore = s.breakBefore ?: p.breakBefore,
                widowControl = s.widowControl ?: p.widowControl,
                firstLineIndent = s.firstLineIndent ?: p.firstLineIndent,
                indentRight = s.indentRight ?: p.indentRight,
                tabStops = s.tabStops ?: p.tabStops,
                snapToGrid = s.snapToGrid ?: p.snapToGrid,
                borders = s.borders ?: p.borders,
                background = s.background ?: p.background,
                numberLines = s.numberLines ?: p.numberLines,
                dropCap = s.dropCap ?: p.dropCap,
            )
            parent = s.parent
        }
        // A size relative to its parent's (LibreOffice's 130%) scales the size the chain gives.
        resolvedSizePt(name, -1f)?.takeIf { it > 0f && it != s.text.fontSizePt }?.let { pt -> s = s.withText(s.text.copyOf().also { it.fontSizePt = pt }) }
        return s
    }

    companion object {

        /** A display name as ODF names a style: characters other than letters, digits, `-` and `.` as `_xx_` (hex). */
        fun encodedName(display: String): String = buildString {
            for (c in display) if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '.') append(c)
                else append('_').append(Integer.toHexString(c.code)).append('_')
        }

        /** A style's name as shown: ODF's `_xx_` (hex) back to its character — `Text_20_body` is "Text body". */
        fun decodedName(name: String): String =
            Regex("_([0-9a-fA-F]{1,6})_").replace(name) { m -> m.groupValues[1].toInt(16).let { if (Character.isValidCodePoint(it)) String(Character.toChars(it)) else m.value } }

        private val DATA_STYLES = listOf("number-style", "percentage-style", "currency-style", "date-style", "time-style", "text-style")
        /** A drawing page's header and footer switches. */
        private val DISPLAYS = listOf("display-footer", "display-page-number", "display-date-time", "display-header")

        /** The names this app gives its plain number styles (`N2_2`, `Nk_0`, `Ndate` …). */
        private val OWN_PLAIN = Regex("""N(2|k|p|cny|usd|sci)_\d+|Ndate|Ntime|Ndatetime""")

        /** Read every style container in the given parts (content.xml and styles.xml). */
        fun of(vararg roots: OdfNode?): OdfStyles {
            val out = OdfStyles()
            // Font declarations first: styles in either part name them.
            roots.filterNotNull().forEach { root ->
                root.descendants("font-face").forEach { f ->
                    val name = f.attr("name") ?: return@forEach
                    f.attr("font-family")?.trim()?.trim('\'', '"')?.takeIf { it.isNotEmpty() }?.let { out.fontFaces[name] = it }
                }
            }
            roots.filterNotNull().forEach { root ->
                root.descendants("default-style").firstOrNull { it.attr("family") == "paragraph" }?.let { d ->
                    d.child("text-properties")?.let { out.defaultParagraphText = out.characterFormat(it) }
                    OdfValue.twipsPt(d.child("paragraph-properties")?.attr("tab-stop-distance"))?.takeIf { it > 0f }?.let { out.defaultTabPt = it }
                }
                root.descendants("style").forEach { out.readStyle(it) }
                // On unless it says otherwise (LibreOffice leaves the attribute out when its lines are numbered, as the schema's default).
                root.descendants("linenumbering-configuration").firstOrNull()?.takeIf { it.attr("number-lines") != "false" }?.let { c ->
                    out.lineNumbers = com.therealityteam.aeropages.model.LineNumbering(
                        countBy = c.attr("increment")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                        restart = if (c.attr("restart-on-page") == "true") com.therealityteam.aeropages.model.LineNumberRestart.PAGE else com.therealityteam.aeropages.model.LineNumberRestart.CONTINUOUS,
                        distancePt = OdfValue.twipsPt(c.attr("offset")),
                    )
                }
                root.descendants("styles").forEach { named ->
                    named.children.forEach { c ->
                        if (c.name == "style" && c.attr("family") == "paragraph") c.attr("name")?.let { out.namedParagraphs[it] = c.attr("display-name") to c.attr("class") }
                    }
                }
                root.descendants("list-style").forEach { out.readListStyle(it) }
                root.descendants("outline-style").firstOrNull()?.let { o -> OdfLists.defOf(o)?.let { out.outline = it.copy(name = "outline") } }
                DATA_STYLES.forEach { kind -> root.descendants(kind).forEach { node -> node.attr("name")?.let { out.dataNodes[it] = node } } }
                root.descendants("marker").forEach { m ->
                    val name = m.attr("name") ?: return@forEach
                    val box = m.attr("viewBox")?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toFloatOrNull() }
                    out.markers[name] = Marker(m.attr("display-name") ?: name.replace("_20_", " "),
                        box?.getOrNull(2) ?: 1f, box?.getOrNull(3) ?: 1f)
                }
                root.descendants("stroke-dash").forEach { d ->
                    val name = d.attr("name") ?: return@forEach
                    out.strokeDashes[name] = StrokeDash(d.attr("dots1")?.toIntOrNull() ?: 0, d.attr("dots1-length"),
                        d.attr("dots2")?.toIntOrNull() ?: 0, d.attr("dots2-length"), d.attr("distance"))
                }
                root.descendants("fill-image").forEach { f ->
                    val name = f.attr("name") ?: return@forEach
                    f.attr("href")?.removePrefix("./")?.let { out.fillImages[name] = it }
                }
                root.descendants("opacity").forEach { o ->
                    val name = o.attr("name") ?: return@forEach
                    fun pct(v: String?, d: Float) = v?.trim()?.removeSuffix("%")?.toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: d
                    out.opacities[name] = Opacity(o.attr("style"), pct(o.attr("start"), 1f), pct(o.attr("end"), 1f),
                        OdfValue.angleDeg(o.attr("angle")) ?: 0f, pct(o.attr("cx"), 0.5f), pct(o.attr("cy"), 0.5f))
                }
                root.descendants("gradient").forEach { g ->
                    val name = g.attr("name") ?: return@forEach
                    fun centre(v: String?) = v?.trim()?.removeSuffix("%")?.toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: 0.5f
                    out.gradients[name] = Gradient(g.attr("style"), OdfValue.color(g.attr("start-color")), OdfValue.color(g.attr("end-color")),
                        OdfValue.angleDeg(g.attr("angle")) ?: 0f, centre(g.attr("cx")), centre(g.attr("cy")))
                }
            }
            return out
        }

        private fun mergeText(parent: CharacterFormat, child: CharacterFormat): CharacterFormat = CharacterFormat(
            bold = child.bold || parent.bold,
            italic = child.italic || parent.italic,
            underline = child.underline || parent.underline,
            strikethrough = child.strikethrough || parent.strikethrough,
            superscript = child.superscript || parent.superscript,
            subscript = child.subscript || parent.subscript,
            smallCaps = child.smallCaps || parent.smallCaps,
            letterSpacingPt = child.letterSpacingPt ?: parent.letterSpacingPt,
            fontSizePt = child.fontSizePt ?: parent.fontSizePt,
            fontFamily = child.fontFamily ?: parent.fontFamily,
            color = child.color ?: parent.color,
            highlight = child.highlight ?: parent.highlight,
        )
    }

    // --- parsing ---------------------------------------------------------------------------

    /** A paragraph style's borders (`fo:border`, `fo:border-*`, their padding); null when it says nothing of them. */
    private fun paragraphBorders(props: OdfNode): com.therealityteam.aeropages.model.ParagraphBorders? {
        val sides = listOf("top", "bottom", "left", "right")
        if (props.attr("border") == null && sides.none { props.attr("border-$it") != null }) return null
        fun line(side: String) = (props.attr("border-$side") ?: props.attr("border"))?.let { OdfBorders.cellLineOf(it) }
        fun pad(side: String) = OdfValue.twipsPt(props.attr("padding-$side") ?: props.attr("padding")) ?: 0f
        val t = line("top"); val b = line("bottom"); val l = line("left"); val r = line("right")
        // A side without a line keeps Word's distance for when one is set.
        return com.therealityteam.aeropages.model.ParagraphBorders(
            top = t, bottom = b, left = l, right = r,
            spaceTop = if (t != null) pad("top") else 1f, spaceBottom = if (b != null) pad("bottom") else 1f,
            spaceLeft = if (l != null) pad("left") else 4f, spaceRight = if (r != null) pad("right") else 4f,
        )
    }

    private fun readStyle(node: OdfNode) {
        val name = node.attr("name") ?: return
        val family = node.attr("family") ?: return
        val parent = node.attr("parent-style-name")
        val textProps = node.child("text-properties")
        val paraProps = node.child("paragraph-properties")
        // Shapes reference `graphic` styles (Impress placeholders use the `presentation` family
        // for both text and shape properties): fill, outline and text anchor.
        if (family == "table-cell") node.child("graphic-properties")?.let { g ->
            if (g.attr("fill") == "none") tableCellFills[name] = 0L
            else OdfValue.color(g.attr("fill-color"))?.let { tableCellFills[name] = it }
            g.attr("textarea-vertical-align")?.let { tableCellAnchors[name] = it }
        }
        if (family == "table-cell") paraProps?.let { pp ->
            val all = pp.attr("border")
            val sides = arrayOf(pp.attr("border-top") ?: all, pp.attr("border-bottom") ?: all, pp.attr("border-left") ?: all, pp.attr("border-right") ?: all)
            if (sides.any { it != null }) tableCellSides[name] = sides
        }
        node.child("graphic-properties")?.let { g ->
            if (family == "graphic" || family == "presentation") graphics[name] = GraphicStyle(
                filled = g.attr("fill")?.let { it != "none" },
                fillColor = OdfValue.color(g.attr("fill-color")),
                opacity = g.attr("opacity")?.trim()?.removeSuffix("%")?.toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f),
                opacityName = g.attr("opacity-name"),
                stroked = g.attr("stroke")?.let { it != "none" },
                strokeColor = OdfValue.color(g.attr("stroke-color")),
                strokeWidthPt = OdfValue.lengthPt(g.attr("stroke-width")),
                vAlign = g.attr("textarea-vertical-align"),
                parent = parent,
                clip = g.attr("clip"),
                gradient = g.attr("fill-gradient-name"),
                strokeDash = g.attr("stroke-dash"),
                wrapOption = g.attr("wrap-option"),
                markerStart = g.attr("marker-start"),
                markerStartWidthPt = OdfValue.lengthPt(g.attr("marker-start-width")),
                markerEnd = g.attr("marker-end"),
                markerEndWidthPt = OdfValue.lengthPt(g.attr("marker-end-width")),
                fill = g.attr("fill"),
                stroke = g.attr("stroke"),
                shadow = g.attr("shadow")?.let { it == "visible" },
                shadowDxPt = OdfValue.lengthPt(g.attr("shadow-offset-x")),
                shadowDyPt = OdfValue.lengthPt(g.attr("shadow-offset-y")),
                shadowColor = OdfValue.color(g.attr("shadow-color")),
                shadowOpacity = g.attr("shadow-opacity")?.trim()?.removeSuffix("%")?.toFloatOrNull()?.div(100f),
                shadowBlurPt = OdfValue.lengthPt(g.attr("shadow-blur")),
                mirror = g.attr("mirror"),
                decorative = g.attr("decorative")?.let { it == "true" },
                wrap = g.attr("wrap"),
                runThrough = g.attr("run-through"),
                hPos = g.attr("horizontal-pos"),
                hRel = g.attr("horizontal-rel"),
                vPos = g.attr("vertical-pos"),
                vRel = g.attr("vertical-rel"),
            )
        }
        if (family == "paragraph") textProps?.attr("font-size")?.trim()?.takeIf { it.endsWith("%") }?.removeSuffix("%")?.toFloatOrNull()?.let { sizePercent[name] = it / 100f }
        if (family == "paragraph" || family == "table") node.attr("master-page-name")?.takeIf { it.isNotEmpty() }?.let { masterPages[name] = it }
        if (family == "paragraph") node.child("paragraph-properties")?.attr("page-number")?.toIntOrNull()?.let { pageNumbers[name] = it }
        if (family == "section") node.child("section-properties")?.child("columns")?.attr("column-count")?.toIntOrNull()?.let { sectionColumns[name] = it }
        when (family) {
            "paragraph", "presentation" -> paragraphs[name] = Paragraph(
                align = align(paraProps?.attr("text-align")),
                spaceBeforePt = OdfValue.twipsPt(paraProps?.attr("margin-top")),
                spaceAfterPt = OdfValue.twipsPt(paraProps?.attr("margin-bottom")),
                indentLeftPt = OdfValue.twipsPt(paraProps?.attr("margin-left")),
                lineSpacing = OdfValue.lineHeight(paraProps?.attr("line-height")),
                parent = parent,
                text = characterFormat(textProps),
                outlineLevel = node.attr("default-outline-level")?.toIntOrNull(),
                keepWithNext = paraProps?.attr("keep-with-next")?.let { it == "always" },
                keepTogether = paraProps?.attr("keep-together")?.let { it == "always" },
                breakBefore = paraProps?.attr("break-before")?.let { it == "page" },
                widowControl = (paraProps?.attr("widows") ?: paraProps?.attr("orphans"))?.toIntOrNull()?.let { it > 0 },
                firstLineIndent = FontLength.of(paraProps?.attr("text-indent")),
                indentRight = FontLength.of(paraProps?.attr("margin-right")),
                tabStops = paraProps?.child("tab-stops")?.let { tabStops(it) },
                snapToGrid = paraProps?.attr("snap-to-layout-grid")?.let { it != "false" },
                lineHeightPt = paraProps?.attr("line-height")?.trim()?.takeIf { !it.endsWith("%") && it != "normal" }?.let { OdfValue.twipsPt(it) }
                    ?: OdfValue.twipsPt(paraProps?.attr("line-height-at-least")),
                lineExact = paraProps?.attr("line-height")?.trim()?.let { !it.endsWith("%") && it != "normal" } ?: (paraProps?.attr("line-height-at-least") == null),
                borders = paraProps?.let { paragraphBorders(it) },
                background = paraProps?.attr("background-color")?.trim()?.let { if (it == "transparent") 0L else OdfValue.color(it) },
                numberLines = paraProps?.attr("number-lines")?.let { it != "false" },
                dropCap = paraProps?.child("drop-cap")?.let { dc ->
                    val lines = dc.attr("lines")?.toIntOrNull() ?: 1
                    // One line is ODF's "no drop cap".
                    if (lines < 2) null else DropCapProps(lines.coerceAtMost(com.therealityteam.aeropages.model.DropCap.MAX_LINES),
                        dc.attr("length")?.let { if (it == "word") DropCapProps.WORD else it.toIntOrNull() } ?: 1,
                        OdfValue.twipsPt(dc.attr("distance")) ?: 0f, dc.attr("style-name"))
                },
            )
            "text" -> {
                texts[name] = characterFormat(textProps)
                val off = HashSet<String>()
                if (textProps?.attr("font-weight") == "normal") off.add("bold")
                if (textProps?.attr("font-style") == "normal") off.add("italic")
                if (textProps?.attr("text-underline-style") == "none") off.add("underline")
                if (textProps?.attr("text-line-through-style") == "none") off.add("strikethrough")
                if (off.isNotEmpty()) textsOff[name] = off
            }
            "table-cell" -> {
                val cellProps = node.child("table-cell-properties")
                cells[name] = CellStyle(
                    text = characterFormat(textProps),
                    align = cellAlign(cellProps?.attr("text-align-source"), align(paraProps?.attr("text-align"))),
                    vAlign = when (cellProps?.attr("vertical-align")) {
                        "top" -> VAlign.TOP; "bottom" -> VAlign.BOTTOM; "middle" -> VAlign.MIDDLE; else -> null
                    },
                    wrap = cellProps?.attr("wrap-option") == "wrap",
                    background = OdfValue.color(cellProps?.attr("background-color")),
                    borderTop = border(cellProps, "border-top"),
                    borderBottom = border(cellProps, "border-bottom"),
                    borderLeft = border(cellProps, "border-left"),
                    borderRight = border(cellProps, "border-right"),
                    dataStyle = node.attr("data-style-name"),
                    parent = parent,
                    unlocked = cellProps?.attr("cell-protect") == "none",
                    edges = listOf("border-top", "border-bottom", "border-left", "border-right").map { cellProps?.attr(it) ?: cellProps?.attr("border") },
                    rotation = cellProps?.attr("rotation-angle")?.let { cellRotation(it) },
                    stacked = cellProps?.attr("direction")?.let { it == "ttb" },
                    indent = OdfValue.lengthPt(paraProps?.attr("margin-left"))?.let { Math.round(it / com.therealityteam.aeropages.model.Cell.INDENT_PT).coerceIn(0, com.therealityteam.aeropages.model.Cell.MAX_INDENT) },
                    spread = when {
                        cellProps?.attr("repeat-content") == "true" -> com.therealityteam.aeropages.model.CellSpread.FILL
                        paraProps?.attr("text-justify") == "distribute" -> com.therealityteam.aeropages.model.CellSpread.DISTRIBUTED
                        else -> null
                    },
                    shrink = cellProps?.attr("shrink-to-fit")?.let { it == "true" },
                )
            }
            // A sheet's table style: whether it shows in the tabs, and LibreOffice's tab colour.
            "table" -> node.child("table-properties")?.let { tp ->
                if (tp.attr("display") == "false") hiddenTables.add(name)
                OdfValue.color(tp.attr("tab-color"))?.let { tabColors[name] = it }
                // A text table's own width and place across the text (a sheet's table has none of these).
                val align = tp.attr("align")
                val width = OdfValue.lengthPt(tp.attr("width"))
                if (align != null || width != null) tableLayouts[name] = TableLayout(width, align, OdfValue.lengthPt(tp.attr("margin-left")))
            }
            "table-column" -> node.child("table-column-properties")?.let { cp ->
                OdfValue.lengthPt(cp.attr("column-width"))?.let { columnWidthPt[name] = it }
                if (cp.attr("break-before") == "page") pageBreakStyles.add(name)
            }
            // A row fitted to its content (use-optimal-row-height) has no height of its own.
            "table-row" -> node.child("table-row-properties")?.let { rp ->
                if (rp.attr("use-optimal-row-height") != "true") OdfValue.lengthPt(rp.attr("row-height"))?.let { rowHeightPt[name] = it }
                if (rp.attr("break-before") == "page") pageBreakStyles.add(name)
                OdfValue.lengthPt(rp.attr("min-row-height"))?.let { rowMinHeightPt[name] = it }
            }
            "drawing-page" -> {
                val props = node.child("drawing-page-properties")
                if (props?.attr("fill") == "solid") OdfValue.color(props.attr("fill-color"))?.let { pageBackground[name] = it }
                if (props?.attr("fill") == "bitmap") props.attr("fill-image-name")?.let { pageFillImage[name] = it }
                if (props?.attr("fill") == "gradient") props.attr("fill-gradient-name")?.let { pageGradient[name] = it }
                if (props?.attr("background-objects-visible") == "false") pagesWithoutObjects.add(name)
                props?.attr("type")?.let { pageTransition[name] = it to props.attr("transition-speed"); pageTransitionWay[name] = (props.attr("subtype") ?: "") to (props.attr("direction") == "reverse") }
                if (props?.attr("visibility") == "hidden") hiddenPages.add(name)
                props?.let { pr ->
                    val shown = DISPLAYS.mapNotNull { d -> pr.attr(d)?.let { d to (it == "true") } }.toMap()
                    if (shown.isNotEmpty()) pageDisplays[name] = shown
                }
            }
        }
    }

    /** A cell side's border (`fo:border-top`, or `fo:border` for all four): its line style and colour; null when none. */
    private fun border(props: OdfNode?, name: String): BorderLine? {
        val v = props?.attr(name) ?: props?.attr("border") ?: return null
        return OdfBorders.lineOf(v)
    }

    private fun readListStyle(node: OdfNode) {
        val name = node.attr("name") ?: return
        lists[name] = if (node.child("list-level-style-number") != null) ListKind.NUMBER else ListKind.BULLET
        OdfLists.defOf(node)?.let { listDefs[name] = it }
    }

    private fun readDataStyle(node: OdfNode): DataStyle? {
        val name = node.attr("name") ?: return null
        val kind = node.name
        // The app's own plain styles are what their names say; any other shows the code it spells.
        if (!OWN_PLAIN.matches(name)) {
            OdfNumberStyles.codeOf(node, dataNodes)?.takeIf { FormatCode.isValid(it) }?.let { code ->
                val plain = FormatCode.plain(code)
                return if (plain != null) DataStyle(plain.first, plain.second) else DataStyle(FormatCode.category(code), -1, code)
            }
        }
        val number = node.child("number") ?: node.child("scientific-number")
        val decimals = number?.attr("decimal-places")?.toIntOrNull() ?: -1
        val grouping = number?.attr("grouping") == "true"
        val currency = node.child("currency-symbol")?.text?.trim()
        val format = when (kind) {
            "percentage-style" -> NumberFormat.PERCENT
            "currency-style" -> if (currency == "¥" || currency == "￥") NumberFormat.CURRENCY_CNY else NumberFormat.CURRENCY_USD
            "date-style" -> if (node.child("hours") != null) NumberFormat.DATE_TIME else NumberFormat.DATE
            "time-style" -> NumberFormat.TIME
            "text-style" -> return null
            else -> when {
                node.child("scientific-number") != null -> NumberFormat.SCIENTIFIC
                grouping -> NumberFormat.THOUSANDS
                else -> NumberFormat.NUMBER2
            }
        }
        return DataStyle(format, decimals)
    }

    private fun characterFormat(props: OdfNode?): CharacterFormat {
        if (props == null) return CharacterFormat()
        val position = props.attr("text-position")
        return CharacterFormat(
            bold = OdfValue.bold(props.attr("font-weight")),
            italic = props.attr("font-style") == "italic" || props.attr("font-style") == "oblique",
            underline = OdfValue.on(props.attr("text-underline-style")),
            strikethrough = OdfValue.on(props.attr("text-line-through-style")),
            superscript = position?.startsWith("super") == true,
            subscript = position?.startsWith("sub") == true,
            smallCaps = props.attr("font-variant") == "small-caps",
            letterSpacingPt = OdfValue.lengthPt(props.attr("letter-spacing")),
            fontSizePt = OdfValue.lengthPt(props.attr("font-size")),
            // A declared font by its family (`style:font-name` names a declaration), else as written.
            fontFamily = props.attr("font-name")?.let { fontFaces[it] ?: it } ?: props.attr("font-family")?.trim()?.trim('\'', '"'),
            color = OdfValue.color(props.attr("color")),
            highlight = OdfValue.color(props.attr("background-color")),
        )
    }

    /**
     * A cell style's horizontal alignment; null = General. `text-align-source="value-type"` is Calc's
     * Standard (General) alignment; a bare start / left with no source is what older writers —
     * AeroDesk's own among them — put on every cell for the default, so General too. Calc marks a
     * chosen alignment `fix`.
     */
    /** An ODF rotation angle (degrees counterclockwise, 0–360, a unit allowed) as Excel turns cell text: −90…90, the nearest upright turn. */
    private fun cellRotation(v: String): Int {
        val deg = Regex("-?[0-9.]+").find(v)?.value?.toDoubleOrNull() ?: return 0
        val a = ((Math.round(deg).toInt() % 360) + 360) % 360
        return when { a <= 90 -> a; a >= 270 -> a - 360; a < 180 -> 90; else -> -90 }
    }

    private fun cellAlign(source: String?, a: TextAlign?): TextAlign? = when {
        source == "value-type" -> null
        source == null && a == TextAlign.LEFT -> null
        else -> a
    }

    private fun align(v: String?): TextAlign? = when (v) {
        "start", "left" -> TextAlign.LEFT
        "center" -> TextAlign.CENTER
        "end", "right" -> TextAlign.RIGHT
        "justify" -> TextAlign.JUSTIFY
        else -> null
    }
}
