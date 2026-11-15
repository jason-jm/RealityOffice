package com.therealityteam.aeropages.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.therealityteam.aeropages.model.AnimDir
import com.therealityteam.aeropages.model.AnimEffect
import com.therealityteam.aeropages.model.AnimStart
import com.therealityteam.aeropages.model.AnimTarget
import com.therealityteam.aeropages.model.AnimTimeline
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.ElementAnim
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.SlideLinks
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextId
import com.therealityteam.aeropages.model.Texts

/** SmartArt diagram layout for [SlideEditor.insertSmartArt]. */
enum class SmartArtKind { LIST, PROCESS, CYCLE }

/**
 * State holder for the Slides (PowerPoint) module. Mirrors the spirit of
 * [DocumentEditor] but for a presentation: a current slide, a focused text box
 * (title / body / a shape), and selection-aware formatting. Compose observes [revision].
 */
class SlideEditor {
    var revision by mutableIntStateOf(0)
        private set
    /** Bumped when a shape is inserted (Insert ▸ Shapes, Text Box, WordArt, Line, SmartArt): PowerPoint then opens Shape Format. */
    var shapesInserted by mutableIntStateOf(0)
        private set
    var presentation by mutableStateOf(Presentation.blank())
        private set
    var current by mutableIntStateOf(0)
        private set
    /** The text box currently being edited (title, body, or a shape's box). */
    var focusedRef by mutableStateOf<SlideBox?>(null)
        private set
    /** The free shape currently selected (for move/delete); null if none. */
    var selectedShape by mutableStateOf<SlideShape?>(null)
        private set
    /** The table currently selected (drives the 表格设计/布局 contextual tabs). */
    var selectedTable by mutableStateOf<com.therealityteam.aeropages.model.SlideTable?>(null)
        private set
    /** The chart currently selected (drives the 图表设计 contextual tab). */
    var selectedChart by mutableStateOf<com.therealityteam.aeropages.model.SlideChart?>(null)
        private set
    /** The picture currently selected (drives the 图片格式 contextual tab). */
    var selectedImage by mutableStateOf<com.therealityteam.aeropages.model.SlideImage?>(null)
        private set
    var selStart by mutableIntStateOf(0)
        private set
    var selEnd by mutableIntStateOf(0)
        private set
    internal val saveState = EditState()
    var dirty by saveState::dirty
        private set

    /** File → Info: the file's properties, a change to save (not an undo step, as in Office). */
    fun setProps(p: com.therealityteam.aeropages.model.DocProps) {
        if (p == presentation.props) return
        presentation.props = p; dirty = true; bump()
    }

    private fun bump() { revision++; verifyIfPending() }
    /** Public recompose trigger for UI that mutated the model directly (e.g. the anim pane); not undoable. */
    fun touch() { verifyPending = null; dirty = true; bump() }

    // --- undo / redo (scoped deltas, see UndoHistory.kt / SlideDeltas.kt) ---------
    private val history = History<Presentation, Int>(80)
    private var lastKey: String? = null
    /** Test-only ([VERIFY_UNDO]): the latest step's delta plus a full pre-edit copy, dry-run on every [bump]. */
    private var verifyPending: Pair<Delta<Presentation>, Presentation>? = null
    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo
    /** Undoable steps recorded so far (tests). */
    internal val undoDepth: Int get() = history.size

    /**
     * Record [delta] — the part of the deck the coming mutation may change — as an undo step.
     * Consecutive calls sharing a non-null [key] (typing in one box, dragging a shape) coalesce
     * into the first call's step.
     */
    private fun pushUndo(delta: Delta<Presentation>, key: String? = null) {
        edits++
        if (key != null && key == lastKey) return
        history.push(delta, current); lastKey = key
        if (VERIFY_UNDO) verifyPending = delta to presentation.copyOf()
    }
    /** Most edits touch only the current slide. */
    private fun pushUndo(key: String? = null) = pushUndo(SlideDelta(current, slide.copyOf()), key)
    private fun deckMeta() = DeckMetaDelta.capture(presentation)
    private fun allSlides() = SlidesRangeDelta(0, presentation.slides.size, presentation.slides.map { it.copyOf() })
    private fun both(a: Delta<Presentation>, b: Delta<Presentation>): Delta<Presentation> = CompositeDelta(listOf(a, b))

    private fun restore(cur: Int) {
        current = cur.coerceIn(0, presentation.slides.lastIndex)
        focusedRef = null; selectedShape = null; selectedTable = null; selectedChart = null; selectedImage = null; selectedAnim = null; selStart = 0; selEnd = 0
    }
    fun undo() { val cur = history.undo(presentation, current) ?: return; edits++; verifyPending = null; restore(cur); lastKey = null; dirty = true; bump() }
    fun redo() { val cur = history.redo(presentation, current) ?: return; edits++; verifyPending = null; restore(cur); lastKey = null; dirty = true; bump() }

    private fun verifyIfPending() {
        val (delta, before) = verifyPending ?: return
        verifyDelta("slide edit", delta, before, presentation, { it.copyOf() }, ModelDiff::samePresentation)
    }

    fun load(p: Presentation, recovered: Boolean = false) { edits++; presentation = p; current = 0; focusedRef = null; tableRange = null; selectedShape = null; selectedTable = null; selectedChart = null; selectedImage = null; selectedAnim = null; history.clear(); verifyPending = null; lastKey = null; dirty = recovered; bump() }
    fun markSaved() { dirty = false; bump() }
    fun markSaved(path: String, clean: Boolean = true) {
        presentation.sourcePath = path
        if (clean) dirty = false
        bump()
    }

    val slide: Slide get() = presentation.slides[current.coerceIn(0, presentation.slides.lastIndex)]
    val slideCount: Int get() = presentation.slides.size

    fun select(index: Int) { current = index.coerceIn(0, presentation.slides.lastIndex); focusedRef = null; tableRange = null; selectedShape = null; selectedTable = null; selectedChart = null; selectedImage = null; selectedAnim = null; lastKey = null; bump() }
    fun focus(box: SlideBox) { if (box !== focusedRef) tableRange = null; focusedRef = box; cellTyped = true; bump() }
    /** A box was chosen to type in (rather than focused by picking a table): Table Design commands act on that cell. */
    private var cellTyped = false
    fun focusedBox(): SlideBox? = focusedRef

    // --- equations -----------------------------------------------------------------

    /** The equation the focused box holds alone (an equation text box), or null. */
    val focusedEquation: com.therealityteam.aeropages.model.InlineMath?
        get() = focusedRef?.let { com.therealityteam.aeropages.model.MathText.soleEquation(it.paragraph) }

    /** Insert → Equation: a text box holding [math] alone, typeset, across the middle of the slide; it comes up selected. */
    fun insertEquation(math: com.therealityteam.aeropages.model.MathNode) {
        pushUndo()
        val runs = com.therealityteam.aeropages.model.MathText.runs(com.therealityteam.aeropages.model.InlineMath(math),
            com.therealityteam.aeropages.model.CharacterFormat(fontSizePt = EQUATION_SIZE))
        val box = SlideBox(com.therealityteam.aeropages.model.Paragraph(runs.toMutableList(), com.therealityteam.aeropages.model.ParagraphFormat(align = com.therealityteam.aeropages.model.TextAlign.CENTER)))
        // No outline, as PowerPoint's equation box has none.
        val s = SlideShape(ShapeType.TEXTBOX, box, border = null).apply { xPct = 0.15f; yPct = 0.38f; wPct = 0.7f; hPct = 0.24f }
        slide.shapes.add(s); selectedShape = s; focusedRef = s.box; dirty = true; bump()
    }

    /** [box]'s equation now reads [math]: its runs set anew, at its size and colour. */
    fun setEquation(box: SlideBox, math: com.therealityteam.aeropages.model.MathNode) {
        val old = com.therealityteam.aeropages.model.MathText.soleEquation(box.paragraph) ?: return
        if (old.math == math) return
        pushUndo()
        val first = box.paragraph.runs.first { it.format.math != null }.format
        val runs = com.therealityteam.aeropages.model.MathText.runs(com.therealityteam.aeropages.model.InlineMath(math),
            com.therealityteam.aeropages.model.CharacterFormat(fontSizePt = first.fontSizePt ?: EQUATION_SIZE, color = first.color))
        box.paragraph.runs.clear(); box.paragraph.runs.addAll(runs)
        dirty = true; bump()
    }

    // --- free shapes / text boxes ----------------------------------------------

    fun addShape(type: ShapeType) {
        if (type == ShapeType.LINE) { addLine(); return }
        pushUndo()
        val s = SlideShape(type)
        slide.shapes.add(s); selectedShape = s; focusedRef = s.box; dirty = true; shapesInserted++; bump()
    }

    /**
     * Insert → Shapes: PowerPoint's preset [name] (a hexagon, a callout, a flowchart shape …) an inch
     * and a half square in the middle of the slide — the app's own shape where it has one — selected
     * to type in. Its outline is worked out again at whatever size it is given.
     */
    fun addPreset(name: String) {
        val own = OWN_PRESETS[name]
        if (own == null && !com.therealityteam.aeropages.format.pptx.PresetGeometries.has(name)) return
        pushUndo()
        val size = presentation.slideSize
        val w = (PRESET_SIDE_PT / size.widthPt).coerceAtMost(0.9f)
        val h = (PRESET_SIDE_PT / size.heightPt).coerceAtMost(0.9f)
        val s = SlideShape(own ?: ShapeType.FREEFORM, xPct = 0.5f - w / 2f, yPct = 0.5f - h / 2f, wPct = w, hPct = h)
        if (own == null) {
            s.preset = name
            s.path = com.therealityteam.aeropages.format.pptx.PresetGeometries.freeform(name, null, (w * size.widthPt * 12700f).toDouble(), (h * size.heightPt * 12700f).toDouble())
        }
        // PowerPoint's action buttons come with the action their picture says (Back goes to the previous slide…), and
        // open Action Settings.
        ACTION_BUTTONS[name]?.let { a -> s.action = a.ifEmpty { null }; actionButtonsInserted++ }
        slide.shapes.add(s); selectedShape = s; focusedRef = s.box; dirty = true; shapesInserted++; bump()
    }

    /** Bumped when an action button is inserted: PowerPoint then opens Action Settings. */
    var actionButtonsInserted by mutableIntStateOf(0)
        private set

    /** The shape or picture Action Settings is for: the one picked (not a table or chart), or null. */
    val actionTarget: com.therealityteam.aeropages.model.SlideObject? get() = selectedShape ?: selectedImage

    /** The picked shape's or picture's click and mouse-over actions. */
    val targetActions: Pair<String?, String?>? get() = when (val t = actionTarget) {
        is SlideShape -> t.action to t.hoverAction
        is com.therealityteam.aeropages.model.SlideImage -> t.action to t.hoverAction
        else -> null
    }

    /**
     * PowerPoint's Action Settings: what a click on the picked shape or picture does in the show, and what pointing at it
     * does ([click] / [hover]: an address, a place in the show, or null for none). One step back; false when nothing
     * is picked or nothing changes.
     */
    fun setObjectActions(click: String?, hover: String?): Boolean {
        val c = click?.ifBlank { null }; val h = hover?.ifBlank { null }
        if (targetActions == null || targetActions == (c to h)) return false
        pushUndo()
        when (val t = actionTarget) {
            is SlideShape -> { t.action = c; t.hoverAction = h }
            is com.therealityteam.aeropages.model.SlideImage -> { t.action = c; t.hoverAction = h }
            else -> Unit
        }
        dirty = true; bump()
        return true
    }

    /**
     * Insert → Line (Line Arrow, Double Arrow): a level line across the slide's middle in the theme's
     * accent, with arrowheads [begin] and [end]; it becomes the selection.
     */
    fun addLine(begin: com.therealityteam.aeropages.model.LineEnd = com.therealityteam.aeropages.model.LineEnd.NONE,
                end: com.therealityteam.aeropages.model.LineEnd = com.therealityteam.aeropages.model.LineEnd.NONE) {
        pushUndo()
        val s = SlideShape(ShapeType.LINE, xPct = 0.3f, yPct = 0.5f, wPct = 0.4f, hPct = 0f, fill = null, border = LINE_ACCENT, borderWidthPt = 1.5f,
            beginArrow = begin, endArrow = end)
        slide.shapes.add(s); selectedShape = s; dirty = true; shapesInserted++; bump()
    }
    /** Secondary selection (多选): shapes selected besides [selectedShape]; they move, align, group and delete together. */
    val extraShapes = androidx.compose.runtime.mutableStateListOf<SlideShape>()
    /** When on, tapping another shape adds it to the selection instead of replacing it. */
    var multiSelect by mutableStateOf(false)
        private set
    fun toggleMultiSelect() { multiSelect = !multiSelect; bump() }
    /** Every selected shape, primary first. */
    val selectedShapes: List<SlideShape> get() = listOfNotNull(selectedShape) + extraShapes.filter { it !== selectedShape }

    class Guide(val vertical: Boolean, val pos: Float)
    /** Smart guides of the drag in progress (slide fractions). */
    val guides = androidx.compose.runtime.mutableStateListOf<Guide>()
    /** The drag is over: its guides go, and the next drag starts from where the object is. */
    fun clearGuides() { drag = null; if (guides.isNotEmpty()) guides.clear() }

    fun selectShape(shape: SlideShape?) {
        if (shape != null && multiSelect && selectedShape != null && shape !== selectedShape) {
            // Toggle membership in the secondary selection (a grouped shape brings its group).
            val members = shape.groupId?.let { g -> slide.shapes.filter { it.groupId == g } } ?: listOf(shape)
            if (shape in extraShapes) extraShapes.removeAll(members) else members.forEach { if (it !in extraShapes && it !== selectedShape) extraShapes.add(it) }
            bump(); return
        }
        selectedShape = shape; extraShapes.clear()
        if (shape != null) {
            focusedRef = shape.box; selectedTable = null; selectedChart = null; selectedImage = null
            shape.groupId?.let { g -> slide.shapes.filter { it.groupId == g && it !== shape }.forEach { extraShapes.add(it) } }
        }
        bump()
    }
    /** Home ▸ Select ▸ Select All: every shape shown on the slide selected together (the first primary); false when there is none. */
    fun selectAllShapes(): Boolean {
        val shown = slide.shapes.filter { !it.hidden }
        if (shown.isEmpty()) return false
        selectedShape = shown.first(); extraShapes.clear(); extraShapes.addAll(shown.drop(1))
        focusedRef = null; selectedTable = null; selectedChart = null; selectedImage = null
        bump(); return true
    }

    // --- Home ▸ Replace ▸ Replace Fonts -------------------------------------------------------

    /** Every font the presentation uses — in its slides' text, its master and its designs — by name, in order. */
    fun fontsUsed(): List<String> {
        val fonts = java.util.TreeSet(String.CASE_INSENSITIVE_ORDER)
        fun box(b: com.therealityteam.aeropages.model.SlideBox) = b.paragraph.runs.forEach { r -> r.format.fontFamily?.takeIf { it.isNotBlank() }?.let(fonts::add) }
        presentation.slides.forEach { sl -> boxesOf(sl).forEach(::box) }
        presentation.master.titleFont?.let(fonts::add); presentation.master.bodyFont?.let(fonts::add)
        for (d in presentation.designs) {
            d.titleText?.font?.let(fonts::add); d.bodyText?.font?.let(fonts::add)
            d.items.filterIsInstance<com.therealityteam.aeropages.model.DesignShape>().forEach { box(it.shape.box) }
        }
        return fonts.toList()
    }

    /**
     * PowerPoint's Replace Fonts: every use of [from] (any case) — in the slides' text, the master's title and body fonts
     * and the designs — becomes [to], one undo step; how many places changed (0: nothing done).
     */
    fun replaceFont(from: String, to: String): Int {
        val target = to.trim()
        if (target.isEmpty() || from.equals(target, ignoreCase = false)) return 0
        fun hit(f: String?) = f != null && f.equals(from, ignoreCase = true)
        fun boxHits(b: com.therealityteam.aeropages.model.SlideBox) = b.paragraph.runs.count { hit(it.format.fontFamily) }
        val designHits = presentation.designs.sumOf { d ->
            (if (hit(d.titleText?.font)) 1 else 0) + (if (hit(d.bodyText?.font)) 1 else 0) +
                d.items.filterIsInstance<com.therealityteam.aeropages.model.DesignShape>().sumOf { boxHits(it.shape.box) }
        }
        val total = presentation.slides.sumOf { sl -> boxesOf(sl).sumOf(::boxHits) } + designHits +
            (if (hit(presentation.master.titleFont)) 1 else 0) + (if (hit(presentation.master.bodyFont)) 1 else 0)
        if (total == 0) return 0
        pushUndo(both(deckMeta(), allSlides()))
        for (sl in presentation.slides) for (b in boxesOf(sl)) b.paragraph.runs.forEach { if (hit(it.format.fontFamily)) it.format.fontFamily = target }
        if (hit(presentation.master.titleFont)) presentation.master.titleFont = target
        if (hit(presentation.master.bodyFont)) presentation.master.bodyFont = target
        // A design is never changed in place (slides find it by name, undo keeps the old one): a changed copy replaces it.
        val designs = presentation.designs.toList()
        for ((i, d) in designs.withIndex()) {
            fun text(t: com.therealityteam.aeropages.model.MasterText?) =
                if (t != null && hit(t.font)) com.therealityteam.aeropages.model.MasterText(target, t.sizePt, t.color, t.bold) else t
            val shapesHit = d.items.any { it is com.therealityteam.aeropages.model.DesignShape && boxHits(it.shape.box) > 0 }
            if (!hit(d.titleText?.font) && !hit(d.bodyText?.font) && !shapesHit) continue
            val items = d.items.map { item ->
                if (item is com.therealityteam.aeropages.model.DesignShape && boxHits(item.shape.box) > 0) {
                    val copy = item.shape.copyOf()
                    copy.box.paragraph.runs.forEach { if (hit(it.format.fontFamily)) it.format.fontFamily = target }
                    com.therealityteam.aeropages.model.DesignShape(copy)
                } else item
            }.toMutableList()
            presentation.designs[i] = com.therealityteam.aeropages.model.SlideDesign(d.name, d.background, d.picture, items,
                text(d.titleText), text(d.bodyText), d.chrome.toMutableMap(), d.backgroundShade)
        }
        dirty = true; bump()
        return total
    }

    fun clearSelection() { tableRange = null; selectedShape = null; extraShapes.clear(); selectedTable = null; selectedChart = null; selectedImage = null; focusedRef = null; clearGuides(); bump() }

    // --- the Selection Pane ----------------------------------------------------------
    /** The current slide's objects as the Selection Pane lists them: the one drawn on top first (charts over tables over shapes over pictures). */
    fun paneObjects(): List<com.therealityteam.aeropages.model.SlideObject> =
        slide.charts.reversed() + slide.tables.reversed() + slide.shapes.reversed() + slide.images.reversed()

    /** The object selected on the slide, if it is one the pane lists. */
    val selectedObject: com.therealityteam.aeropages.model.SlideObject?
        get() = selectedChart ?: selectedTable ?: selectedShape ?: selectedImage

    /** Selects [o] from the pane (a hidden object is not selectable, as in PowerPoint). */
    fun selectObject(o: com.therealityteam.aeropages.model.SlideObject) {
        if (o.hidden) return
        when (o) {
            is SlideShape -> selectShape(o)
            is com.therealityteam.aeropages.model.SlideImage -> selectImage(o)
            is com.therealityteam.aeropages.model.SlideTable -> selectTable(o)
            is com.therealityteam.aeropages.model.SlideChart -> selectChart(o)
        }
    }

    /** The pane's eye: [o] hidden or shown, one undo step; a hidden object leaves the selection. */
    fun setObjectHidden(o: com.therealityteam.aeropages.model.SlideObject, hidden: Boolean) {
        if (o.hidden == hidden) return
        pushUndo(); o.hidden = hidden
        if (hidden && selectedObject === o) clearSelection()
        dirty = true; bump()
    }

    /** Show All / Hide All, one undo step (nothing when every object already is). */
    fun setAllObjectsHidden(hidden: Boolean) {
        val objs = paneObjects()
        if (objs.none { it.hidden != hidden }) return
        pushUndo(); objs.forEach { it.hidden = hidden }
        if (hidden) clearSelection()
        dirty = true; bump()
    }

    /** Renames [o] (the pane's name; blank: back to the pane's own), one undo step. */
    fun renameObject(o: com.therealityteam.aeropages.model.SlideObject, name: String) {
        val n = name.trim().takeIf { it.isNotEmpty() }?.take(255)
        if (o.objectName == n) return
        pushUndo(); o.objectName = n; dirty = true; bump()
    }

    /** The list [o] is drawn in, in drawing order. */
    private fun layerOf(o: com.therealityteam.aeropages.model.SlideObject): MutableList<out com.therealityteam.aeropages.model.SlideObject>? = when (o) {
        is SlideShape -> slide.shapes
        is com.therealityteam.aeropages.model.SlideImage -> slide.images
        is com.therealityteam.aeropages.model.SlideTable -> slide.tables
        is com.therealityteam.aeropages.model.SlideChart -> slide.charts
        else -> null
    }

    /** Whether the pane can move [o] up (Bring Forward: drawn later) or down (Send Backward) among objects of its kind. */
    fun canMoveInPane(o: com.therealityteam.aeropages.model.SlideObject, up: Boolean): Boolean {
        val list = layerOf(o) ?: return false
        val i = list.indexOfFirst { it === o }
        return i >= 0 && if (up) i < list.lastIndex else i > 0
    }

    /** The pane's up / down arrows: [o] one step forward or backward among objects of its kind, one undo step. */
    fun moveInPane(o: com.therealityteam.aeropages.model.SlideObject, up: Boolean) {
        if (!canMoveInPane(o, up)) return
        @Suppress("UNCHECKED_CAST") val list = layerOf(o) as MutableList<Any>
        val i = list.indexOfFirst { it === o }
        pushUndo()
        java.util.Collections.swap(list, i, if (up) i + 1 else i - 1)
        dirty = true; bump()
    }
    fun deleteSelectedShape() {
        val all = selectedShapes
        if (all.isEmpty()) return
        pushUndo(); slide.shapes.removeAll(all); selectedShape = null; extraShapes.clear(); focusedRef = null; dirty = true; bump()
    }
    /**
     * Drag every selected shape by a pointer step: the primary shape snaps to the slide's centre and edges and to the
     * other objects' edges and centres (PowerPoint's smart guides), the rest move with it.
     */
    fun moveSelectedShape(dxPct: Float, dyPct: Float) {
        val primary = selectedShape ?: return
        pushUndo("moveShape")
        val all = selectedShapes
        val (nx, ny) = dragTo(primary, primary.xPct, primary.yPct, primary.wPct, primary.hPct, dxPct, dyPct, all)
        val mx = nx - primary.xPct; val my = ny - primary.yPct
        all.forEach {
            it.xPct = (it.xPct + mx).within(0f, 1f - it.wPct)
            it.yPct = (it.yPct + my).within(0f, 1f - it.hPct)
        }
        dirty = true; bump()
    }

    /** The drag in progress: the object, where the pointer has taken it (unsnapped), and where it was put. */
    private class Drag(val key: Any, var rawX: Float, var rawY: Float, var lastX: Float, var lastY: Float)
    private var drag: Drag? = null

    /**
     * Where a dragged object ([key], at [x], [y], [w] × [h]) goes for a pointer step ([dx], [dy]): the pointer's own
     * position — kept across the drag, so a guide holds the object only while the pointer is near it, never sticks —
     * snapped to the guides, on the slide. [moving] are the objects going with it (not guides to themselves).
     */
    private fun dragTo(key: Any, x: Float, y: Float, w: Float, h: Float, dx: Float, dy: Float, moving: Collection<Any>): Pair<Float, Float> {
        val d = drag?.takeIf { it.key === key && kotlin.math.abs(it.lastX - x) < 1e-5f && kotlin.math.abs(it.lastY - y) < 1e-5f }
            ?: Drag(key, x, y, x, y)
        d.rawX += dx; d.rawY += dy
        val rx = d.rawX.within(0f, 1f - w); val ry = d.rawY.within(0f, 1f - h)
        val (sx, sy) = snapAdjustment(rx, ry, w, h, moving)
        val nx = (rx + sx).within(0f, 1f - w); val ny = (ry + sy).within(0f, 1f - h)
        d.lastX = nx; d.lastY = ny; drag = d
        return nx to ny
    }

    /** Every object on the slide with its frame (x, y, w, h): shapes, pictures, tables, charts, framed title and body. */
    private fun frames(): List<Pair<Any, FloatArray>> = buildList {
        slide.shapes.forEach { add(it to floatArrayOf(it.xPct, it.yPct, it.wPct, it.hPct)) }
        slide.images.forEach { add(it to floatArrayOf(it.xPct, it.yPct, it.wPct, it.hPct)) }
        slide.tables.forEach { add(it to floatArrayOf(it.xPct, it.yPct, it.wPct, it.hPct)) }
        slide.charts.forEach { add(it to floatArrayOf(it.xPct, it.yPct, it.wPct, it.hPct)) }
        listOf(slide.title, slide.body).forEach { b -> b.frame?.let { f -> add(b to floatArrayOf(f.xPct, f.yPct, f.wPct, f.hPct)) } }
    }

    private fun snapAdjustment(nx: Float, ny: Float, w: Float, h: Float, moving: Collection<Any>): Pair<Float, Float> {
        val tol = 0.012f
        val xs = mutableListOf(0f, 0.5f, 1f); val ys = mutableListOf(0f, 0.5f, 1f)
        frames().filter { (o, _) -> moving.none { it === o } }.forEach { (_, f) ->
            xs += listOf(f[0], f[0] + f[2] / 2f, f[0] + f[2]); ys += listOf(f[1], f[1] + f[3] / 2f, f[1] + f[3])
        }
        var bestX: Float? = null; var guideX: Float? = null
        for (edge in listOf(nx, nx + w / 2f, nx + w)) for (t in xs) { val d = t - edge; if (kotlin.math.abs(d) < tol && (bestX == null || kotlin.math.abs(d) < kotlin.math.abs(bestX))) { bestX = d; guideX = t } }
        var bestY: Float? = null; var guideY: Float? = null
        for (edge in listOf(ny, ny + h / 2f, ny + h)) for (t in ys) { val d = t - edge; if (kotlin.math.abs(d) < tol && (bestY == null || kotlin.math.abs(d) < kotlin.math.abs(bestY))) { bestY = d; guideY = t } }
        guides.clear()
        guideX?.let { guides.add(Guide(true, it)) }; guideY?.let { guides.add(Guide(false, it)) }
        return (bestX ?: 0f) to (bestY ?: 0f)
    }

    /** A pointer drag of the picked picture, table or chart (else the shapes), with smart guides as shapes have. */
    fun dragSelected(dx: Float, dy: Float) {
        val img = selectedImage; val tbl = selectedTable; val ch = selectedChart
        when {
            img != null -> { pushUndo("moveEl"); dragTo(img, img.xPct, img.yPct, img.wPct, img.hPct, dx, dy, listOf(img)).let { (x, y) -> img.xPct = x; img.yPct = y } }
            tbl != null -> { pushUndo("moveEl"); dragTo(tbl, tbl.xPct, tbl.yPct, tbl.wPct, tbl.hPct, dx, dy, listOf(tbl)).let { (x, y) -> tbl.xPct = x; tbl.yPct = y } }
            ch != null -> { pushUndo("moveEl"); dragTo(ch, ch.xPct, ch.yPct, ch.wPct, ch.hPct, dx, dy, listOf(ch)).let { (x, y) -> ch.xPct = x; ch.yPct = y } }
            else -> { moveSelectedShape(dx, dy); return }
        }
        dirty = true; bump()
    }

    /** A pointer drag of a framed title or body, with smart guides. */
    fun dragBox(box: SlideBox, dx: Float, dy: Float) {
        val f = box.frame ?: return
        pushUndo("moveBox")
        dragTo(box, f.xPct, f.yPct, f.wPct, f.hPct, dx, dy, listOf(box)).let { (x, y) -> f.xPct = x; f.yPct = y }
        dirty = true; bump()
    }
    /** Give the selected shapes (two or more) one group id; they select and move together from now on. */
    fun groupSelected() {
        val all = selectedShapes
        if (all.size < 2) return
        pushUndo()
        val id = (presentation.slides.flatMap { it.shapes }.mapNotNull { it.groupId }.maxOrNull() ?: 0) + 1
        all.forEach { it.groupId = id }
        dirty = true; bump()
    }
    fun ungroupSelected() {
        val all = selectedShapes.filter { it.groupId != null }
        if (all.isEmpty()) return
        pushUndo(); all.forEach { it.groupId = null }; dirty = true; bump()
    }
    /** Spread three or more selected shapes evenly between the first and the last one. */
    fun distributeSelected(horizontal: Boolean) {
        val all = selectedShapes
        if (all.size < 3) return
        pushUndo()
        val sorted = if (horizontal) all.sortedBy { it.xPct } else all.sortedBy { it.yPct }
        val first = sorted.first(); val last = sorted.last()
        val span = if (horizontal) (last.xPct + last.wPct) - first.xPct else (last.yPct + last.hPct) - first.yPct
        val total = sorted.sumOf { (if (horizontal) it.wPct else it.hPct).toDouble() }.toFloat()
        val gap = ((span - total) / (sorted.size - 1)).coerceAtLeast(0f)
        var pos = if (horizontal) first.xPct + first.wPct + gap else first.yPct + first.hPct + gap
        for (s in sorted.drop(1).dropLast(1)) {
            if (horizontal) { s.xPct = pos.within(0f, 1f - s.wPct); pos += s.wPct + gap } else { s.yPct = pos.within(0f, 1f - s.hPct); pos += s.hPct + gap }
        }
        dirty = true; bump()
    }
    fun setShapeGradient(color: Long?, angle: Float? = null) {
        val all = selectedShapes
        if (all.isEmpty()) return
        pushUndo()
        all.forEach { s -> s.fillGradient = color; angle?.let { s.gradientAngle = it }; if (color != null && s.fill == null) s.fill = 0xFFDDE6F2 }
        dirty = true; bump()
    }
    /** The selected shapes' gradients along [angle] — a radial or rectangular one becomes linear; an axial one stays axial. */
    fun setShapeGradientAngle(angle: Float) {
        val all = selectedShapes.filter { it.fillGradient != null }
        if (all.isEmpty()) return
        pushUndo()
        all.forEach {
            it.gradientAngle = angle
            if (it.gradientKind != com.therealityteam.aeropages.model.GradientKind.AXIAL) it.gradientKind = com.therealityteam.aeropages.model.GradientKind.LINEAR
        }
        dirty = true; bump()
    }

    /** The selected shapes' outer shadow — one of Shape Effects' presets, or none. */
    fun setShapeShadow(shadow: com.therealityteam.aeropages.model.ShapeShadow?) {
        val all = selectedShapes
        if (all.isEmpty()) return
        pushUndo(); all.forEach { it.shadow = shadow }; dirty = true; bump()
    }

    /** The selected shapes' gradients run as [kind] (a radial or rectangular one from the centre). */
    fun setShapeGradientKind(kind: com.therealityteam.aeropages.model.GradientKind) {
        val all = selectedShapes.filter { it.fillGradient != null }
        if (all.isEmpty()) return
        pushUndo()
        all.forEach { it.gradientKind = kind; it.gradientCx = 0.5f; it.gradientCy = 0.5f }
        dirty = true; bump()
    }
    fun resizeSelectedShape(dwPct: Float, dhPct: Float) {
        pushUndo("resizeShape")
        selectedShape?.let {
            // A line may lie flat either way; a shape keeps some size.
            val line = it.type == ShapeType.LINE
            it.wPct = (it.wPct + dwPct).within(if (line) 0f else 0.08f, 1f - it.xPct)
            it.hPct = (it.hPct + dhPct).within(if (line) 0f else 0.06f, 1f - it.yPct)
            dirty = true; bump()
        }
    }

    /** A new slide of [layout] after the current one, drawn as the deck's others of its kind (PowerPoint's New Slide gallery). */
    fun addSlide(layout: com.therealityteam.aeropages.model.SlideLayout = com.therealityteam.aeropages.model.SlideLayout.TITLE_CONTENT) {
        pushUndo(SlidesRangeDelta((current + 1).coerceAtMost(presentation.slides.size), 1, emptyList()))
        // Empty placeholders: the canvas shows their prompts ("Click to add title"), and none of it is saved.
        val s = Slide(
            title = SlideBox("", CharacterFormat(fontSizePt = 40f, bold = true), TextAlign.CENTER),
            body = SlideBox("", CharacterFormat(fontSizePt = 22f),
                // A title slide's subtitle is centred, as its layout sets it.
                if (layout == com.therealityteam.aeropages.model.SlideLayout.TITLE) TextAlign.CENTER else TextAlign.LEFT),
            layout = layout,
        )
        takeDesign(s)
        presentation.slides.add((current + 1).coerceAtMost(presentation.slides.size), s)
        current = (current + 1).coerceAtMost(presentation.slides.lastIndex)
        dirty = true; bump()
    }

    /** Inserts one slide per outline entry after the current slide (one undo step); returns how many were added. */
    fun insertSlidesFromOutline(text: String): Int {
        val entries = OutlineImport.parse(text)
        if (entries.isEmpty()) return 0
        val at = (current + 1).coerceAtMost(presentation.slides.size)
        pushUndo(SlidesRangeDelta(at, entries.size, emptyList()))
        entries.forEachIndexed { i, e ->
            val body = e.lines.joinToString("\n")
            val s = Slide(
                title = SlideBox(e.title, CharacterFormat(fontSizePt = 40f, bold = true), TextAlign.CENTER),
                body = SlideBox(body, CharacterFormat(fontSizePt = 22f)),
                layout = if (body.isEmpty()) com.therealityteam.aeropages.model.SlideLayout.TITLE else com.therealityteam.aeropages.model.SlideLayout.TITLE_CONTENT,
            )
            if (body.isNotEmpty()) s.body.listStyle = com.therealityteam.aeropages.model.SlideListStyle.BULLET
            takeDesign(s)
            presentation.slides.add(at + i, s)
        }
        current = at
        dirty = true; bump()
        return entries.size
    }

    fun duplicateSlide() {
        pushUndo(SlidesRangeDelta(current + 1, 1, emptyList()))
        // A slide of its own: links to the original still go to the original.
        presentation.slides.add(current + 1, slide.copyOf(com.therealityteam.aeropages.model.Slide.nextId()))
        current += 1; dirty = true; bump()
    }

    /**
     * PowerPoint's Reuse Slides: slides [indices] of [source] inserted after the current slide, one undo step; returns how
     * many. Each is a slide of its own — a link to another of the slides brought along follows it, one to a slide left
     * behind is dropped. With [keepSource] a slide keeps its look, as PowerPoint brings the source's master along: its
     * design comes along with what the source's master gives it (background, title and body text, footer places) folded
     * in, renamed when this deck has another of that name; a slide without one gets a design made of the source's master,
     * named [sourceName]. Otherwise it takes this deck's theme, PowerPoint's default.
     */
    fun reuseSlides(source: Presentation, indices: List<Int>, keepSource: Boolean, sourceName: String = "Reused"): Int {
        val picks = indices.distinct().sorted().mapNotNull { source.slides.getOrNull(it) }
        if (picks.isEmpty()) return 0
        val at = (current + 1).coerceAtMost(presentation.slides.size)
        pushUndo(both(deckMeta(), SlidesRangeDelta(at, picks.size, emptyList())))
        val copies = picks.map { it.copyOf(Slide.nextId()) }
        val byOld = picks.zip(copies).associate { (src, copy) -> src.id to copy }
        val adopted = HashMap<Any, String>()  // a source design (or the source master, for slides without one) → its name here
        for ((src, copy) in picks.zip(copies)) {
            if (keepSource) {
                val design = source.designOf(src)
                copy.design = when {
                    design != null -> adopted.getOrPut(design) { adoptDesign(design, source.master) }
                    source.master.isDefault -> null
                    else -> adopted.getOrPut(source.master) {
                        adoptDesign(com.therealityteam.aeropages.model.SlideDesign(sourceName), source.master)
                    }
                }
            } else {
                copy.design = null; copy.designObjects = true
                takeDesign(copy)
            }
        }
        com.therealityteam.aeropages.model.SlideLinks.remap(Presentation(copies.toMutableList())) { link ->
            val old = com.therealityteam.aeropages.model.SlideLinks.slideId(link) ?: return@remap link
            byOld[old]?.let { com.therealityteam.aeropages.model.SlideLinks.toSlide(it) }
        }
        presentation.slides.addAll(at, copies)
        current = at + copies.size - 1
        dirty = true; bump()
        return copies.size
    }

    /** [d] among this deck's designs: itself when it is one, else a copy under a name of its own. */
    /**
     * [d] as a design of this deck, with what [master] (the source deck's) gives the slides on it folded in where [d]
     * says nothing — a design here sits over this deck's master — under its name, or "Name 2", "Name 3" when taken.
     */
    private fun adoptDesign(d: com.therealityteam.aeropages.model.SlideDesign, master: com.therealityteam.aeropages.model.SlideMaster): String {
        if (presentation.designs.any { it === d }) return d.name
        var name = d.name; var n = 2
        while (presentation.designs.any { it.name == name }) name = "${d.name} ${n++}"
        fun over(own: com.therealityteam.aeropages.model.MasterText?, base: com.therealityteam.aeropages.model.MasterText) =
            com.therealityteam.aeropages.model.MasterText(own?.font ?: base.font, own?.sizePt ?: base.sizePt, own?.color ?: base.color, own?.bold ?: base.bold)
                .takeIf { it.font != null || it.sizePt != null || it.color != null || it.bold != null }
        val ownBackground = d.background != null || d.picture != null
        presentation.designs += com.therealityteam.aeropages.model.SlideDesign(name,
            if (ownBackground) d.background else master.background, d.picture, d.items.toMutableList(),
            over(d.titleText, master.titleText()), over(d.bodyText, master.bodyText()),
            (master.chrome + d.chrome).toMutableMap(), if (ownBackground) d.backgroundShade else master.backgroundShade)
        return name
    }

    fun deleteSlide() {
        if (presentation.slides.size <= 1) return
        pushUndo(SlidesRangeDelta(current, 0, listOf(slide.copyOf())))
        presentation.slides.removeAt(current)
        current = current.coerceIn(0, presentation.slides.lastIndex)
        dirty = true; bump()
    }

    fun moveSlide(delta: Int) {
        val to = (current + delta).coerceIn(0, presentation.slides.lastIndex)
        if (to == current) return
        pushUndo(SlideMoveDelta(current, to))
        val s = presentation.slides.removeAt(current)
        presentation.slides.add(to, s)
        current = to; dirty = true; bump()
    }

    // --- rich text editing (selection-aware, reuses ParagraphOps) --------------

    fun setSelection(box: SlideBox, start: Int, end: Int) {
        // Typing or moving in a cell ends a picked block of cells (as clicking into one does in PowerPoint).
        if (box !== focusedRef || start != selStart || end != selEnd) tableRange = null
        focusedRef = box; cellTyped = true
        val len = ParagraphOps.length(box.paragraph)
        selStart = start.coerceIn(0, len); selEnd = end.coerceIn(0, len); bump()
    }

    /** Apply a text change (diff-based) to [box]'s paragraph, preserving run formatting. */
    fun replaceText(box: SlideBox, oldText: String, newText: String) {
        if (oldText == newText) return
        pushUndo("text:" + System.identityHashCode(box))
        val para = box.paragraph
        var p = 0
        val minLen = minOf(oldText.length, newText.length)
        while (p < minLen && oldText[p] == newText[p]) p++
        var s = 0
        while (s < minLen - p && oldText[oldText.length - 1 - s] == newText[newText.length - 1 - s]) s++
        val removedStart = p
        val removedEnd = oldText.length - s
        val inserted = newText.substring(p, newText.length - s)
        val fmt = ParagraphOps.formatAt(para, (removedStart - 1).coerceAtLeast(0))
        ParagraphOps.replace(para, removedStart, removedEnd, inserted, fmt)
        dirty = true; bump()
    }

    private fun selRange(): Pair<Int, Int>? {
        val a = minOf(selStart, selEnd); val b = maxOf(selStart, selEnd)
        return if (a < b) a to b else null
    }

    fun setBackground(color: Long?) { pushUndo(); slide.background = color; slide.backgroundShade = null; dirty = true; bump() }

    /** Format Background → Gradient fill: the slide's own background shading from [color] as [shade] says. */
    fun setBackgroundShade(color: Long, shade: com.therealityteam.aeropages.model.BackgroundShade?) {
        pushUndo(); slide.background = color; slide.backgroundShade = shade; dirty = true; bump()
    }
    /** Format Background → Hide background graphics: whether the slide shows its design's pictures and shapes (its picture fill stays). */
    fun setDesignObjects(show: Boolean) { if (slide.designObjects == show) return; pushUndo(); slide.designObjects = show; dirty = true; bump() }
    /** "Apply to all": the colour moves onto the master and every slide inherits it again. */
    fun setBackgroundAll(color: Long?) {
        pushUndo(both(deckMeta(), allSlides()))
        presentation.master.background = color
        presentation.master.backgroundShade = if (color == slide.background) slide.backgroundShade else null
        presentation.slides.forEach { it.background = null; it.backgroundShade = null }
        dirty = true; bump()
    }

    // --- slide master (deck-wide defaults) ---------------------------------------
    val master: com.therealityteam.aeropages.model.SlideMaster get() = presentation.master

    /** Master background: what slides without a background of their own show. */
    fun setMasterBackground(color: Long?) { pushUndo(deckMeta()); presentation.master.background = color; presentation.master.backgroundShade = null; dirty = true; bump() }

    /**
     * Master title style. Font, colour and bold are defaults for runs that do not set their own;
     * a size is stamped onto every title run, because run sizes are always explicit.
     */
    fun setMasterTitleStyle(font: String? = master.titleFont, sizePt: Float? = master.titleSizePt, color: Long? = master.titleColor, bold: Boolean? = master.titleBold) {
        val restamp = sizePt != null && sizePt != master.titleSizePt
        pushUndo(if (restamp) both(deckMeta(), allSlides()) else deckMeta())
        master.titleFont = font; master.titleSizePt = sizePt; master.titleColor = color; master.titleBold = bold
        if (restamp) presentation.slides.forEach { sl -> sl.title.paragraph.runs.forEach { it.format.fontSizePt = sizePt } }
        dirty = true; bump()
    }

    /** Master body style; see [setMasterTitleStyle]. */
    fun setMasterBodyStyle(font: String? = master.bodyFont, sizePt: Float? = master.bodySizePt, color: Long? = master.bodyColor) {
        val restamp = sizePt != null && sizePt != master.bodySizePt
        pushUndo(if (restamp) both(deckMeta(), allSlides()) else deckMeta())
        master.bodyFont = font; master.bodySizePt = sizePt; master.bodyColor = color
        if (restamp) presentation.slides.forEach { sl -> sl.body.paragraph.runs.forEach { it.format.fontSizePt = sizePt } }
        dirty = true; bump()
    }

    /** Back to the built-in defaults (white background, black text). */
    fun resetMaster() { pushUndo(deckMeta()); master.assignFrom(com.therealityteam.aeropages.model.SlideMaster()); dirty = true; bump() }

    /**
     * Choosing a layout re-flows the placeholders: any dragged / imported frames are dropped. Text
     * set as its old layout sets it follows the new one, as PowerPoint's layouts align it: a title
     * slide centres its subtitle, the others set their text from the left.
     */
    fun setLayout(layout: com.therealityteam.aeropages.model.SlideLayout) {
        pushUndo()
        val title = com.therealityteam.aeropages.model.SlideLayout.TITLE
        val body = slide.body.paragraph.format
        if (slide.layout != title && layout == title && body.align == TextAlign.LEFT) body.align = TextAlign.CENTER
        else if (slide.layout == title && layout != title && body.align == TextAlign.CENTER) body.align = TextAlign.LEFT
        slide.layout = layout; slide.title.frame = null; slide.body.frame = null
        takeDesign(slide)
        dirty = true; bump()
    }

    /** A cover (title or section slide) rather than a content page: the two take different designs in a designed deck. */
    private fun isCover(l: com.therealityteam.aeropages.model.SlideLayout) =
        l == com.therealityteam.aeropages.model.SlideLayout.TITLE || l == com.therealityteam.aeropages.model.SlideLayout.SECTION

    /**
     * [s] drawn as the deck's other slides of its kind are: the design of one laid out the same (else of the same kind —
     * cover or content), and — from one laid out the same — where its title and body sit. A deck without designs is left as it is.
     */
    private fun takeDesign(s: Slide) {
        val others = presentation.slides.filter { it !== s && it.design != null }
        val ref = others.firstOrNull { it.layout == s.layout } ?: others.firstOrNull { isCover(it.layout) == isCover(s.layout) } ?: return
        s.design = ref.design; s.designObjects = ref.designObjects
        if (ref.layout == s.layout && s.title.frame == null && s.body.frame == null) {
            s.title.frame = ref.title.frame?.copyOf(); s.body.frame = ref.body.frame?.copyOf()
            if (ref.title.frame != null) s.title.paragraph.format.align = ref.title.paragraph.format.align
            if (ref.body.frame != null) s.body.paragraph.format.align = ref.body.paragraph.format.align
        }
    }

    /**
     * A placeholder where a layout puts it — flowing, or a box across the slide's middle as a file's title layout has
     * it — rather than one moved aside by hand: a designed look may put it where its cover wants it.
     */
    private fun standardPlace(f: com.therealityteam.aeropages.model.BoxFrame?): Boolean =
        f == null || (f.wPct >= 0.6f && kotlin.math.abs(f.xPct + f.wPct / 2f - 0.5f) < 0.04f)

    /**
     * A designed look (a theme with master pages of its own): [cover] under title and section slides, [content] under the
     * rest, the master's background and text colours its own; title and section slides whose placeholders flow take the
     * cover's places ([coverTitle], [coverBody]), set left as its drawing expects. One undo step.
     */
    fun applyDeckDesign(index: Int, cover: com.therealityteam.aeropages.model.SlideDesign, content: com.therealityteam.aeropages.model.SlideDesign,
                        background: Long, titleColor: Long, bodyColor: Long,
                        coverTitle: com.therealityteam.aeropages.model.BoxFrame, coverBody: com.therealityteam.aeropages.model.BoxFrame, dropDesigns: Set<String>) {
        pushUndo(both(deckMeta(), allSlides()))
        presentation.themeIndex = index
        presentation.designs.removeAll { it.name in dropDesigns || it.name == cover.name || it.name == content.name }
        presentation.designs += cover; presentation.designs += content
        master.background = background; master.backgroundShade = null; master.titleColor = titleColor; master.bodyColor = bodyColor; master.titleBold = true
        presentation.slides.forEach { sl ->
            val coverSlide = isCover(sl.layout)
            sl.design = if (coverSlide) cover.name else content.name; sl.designObjects = true
            sl.background = null; sl.backgroundShade = null
            sl.title.paragraph.runs.forEach { it.format.color = null }
            sl.body.paragraph.runs.forEach { it.format.color = null }
            if (coverSlide && standardPlace(sl.title.frame) && standardPlace(sl.body.frame)) {
                sl.title.frame = coverTitle.copyOf(); sl.body.frame = coverBody.copyOf()
                sl.title.paragraph.format.align = TextAlign.LEFT; sl.body.paragraph.format.align = TextAlign.LEFT
            }
        }
        dirty = true; bump()
    }

    /** True when both of the current slide's placeholders carry frames (free layout) rather than flowing. */
    val placeholdersFree: Boolean get() = slide.title.frame != null && slide.body.frame != null
    /** Pin the placeholders at their flow positions so they can be dragged and resized like shapes (frames already set are kept). */
    fun freePlaceholders() {
        pushUndo()
        if (slide.title.frame == null) slide.title.frame = com.therealityteam.aeropages.model.BoxFrame.defaultFor(slide.layout, true)
        if (slide.body.frame == null) slide.body.frame = com.therealityteam.aeropages.model.BoxFrame.defaultFor(slide.layout, false)
        dirty = true; bump()
    }
    /** Drop the placeholder frames: the slide's layout arranges the title / body again. */
    fun autoPlaceholders() { pushUndo(); slide.title.frame = null; slide.body.frame = null; dirty = true; bump() }
    /** Move a framed placeholder (title / body); a box in the flow layout ignores it. */
    fun moveBox(box: SlideBox, dxPct: Float, dyPct: Float) {
        val f = box.frame ?: return
        pushUndo("moveBox")
        f.xPct = (f.xPct + dxPct).within(0f, 1f - f.wPct); f.yPct = (f.yPct + dyPct).within(0f, 1f - f.hPct)
        dirty = true; bump()
    }
    fun resizeBox(box: SlideBox, dwPct: Float, dhPct: Float) {
        val f = box.frame ?: return
        pushUndo("resizeBox")
        f.wPct = (f.wPct + dwPct).within(0.08f, 1f - f.xPct); f.hPct = (f.hPct + dhPct).within(0.06f, 1f - f.yPct)
        dirty = true; bump()
    }
    fun setNotes(text: String) { pushUndo("notes"); slide.notes = text; dirty = true; bump() }

    /** Reset the current slide's placeholders to default formatting (PowerPoint 重置). */
    fun resetSlide() {
        pushUndo()
        val s = slide
        ParagraphOps.applyFormat(s.title.paragraph, 0, ParagraphOps.length(s.title.paragraph)) {
            it.fontSizePt = 40f; it.bold = true; it.italic = false; it.underline = false; it.color = null; it.highlight = null
        }
        s.title.paragraph.format.align = TextAlign.CENTER; s.title.listStyle = com.therealityteam.aeropages.model.SlideListStyle.NONE
        ParagraphOps.applyFormat(s.body.paragraph, 0, ParagraphOps.length(s.body.paragraph)) {
            it.fontSizePt = 22f; it.bold = false; it.italic = false; it.underline = false; it.color = null; it.highlight = null
        }
        s.body.paragraph.format.align = TextAlign.LEFT; s.body.paragraph.format.indentLeftPt = 0f
        s.body.listStyle = com.therealityteam.aeropages.model.SlideListStyle.NONE
        dirty = true; bump()
    }

    /** Adjust the focused box's left indent (paragraph), in points. */
    /**
     * Links the selected text to [url] — an address, or a place in the show ([com.therealityteam.aeropages.model.SlideLinks]) —
     * or, with no text selected, gives the selected shape or picture that click (PowerPoint's link on an
     * object); a title or body without a selection links all its text. Blank takes it off. One undo step.
     */
    fun applyLink(url: String?) {
        val target = url?.ifBlank { null }
        val shape = selectedShape
        val image = selectedImage
        when {
            selRange() == null && shape != null -> { pushUndo(); shape.action = target; dirty = true; bump() }
            focusedRef == null && image != null -> { pushUndo(); image.action = target; dirty = true; bump() }
            else -> applyChar { it.link = target }
        }
    }

    /** Where Insert → Link would change: the selected shape's or picture's click, else the link of the text at the caret; null: none. */
    val currentLink: String?
        get() {
            val shape = selectedShape
            if (selRange() == null && shape != null) return shape.action
            if (focusedRef == null) return selectedImage?.action
            return currentFormat.link
        }

    /** Generate a SmartArt-style diagram from the body's lines (or 3 placeholders), as positioned shapes. */
    fun insertSmartArt(kind: SmartArtKind) {
        pushUndo()
        val lines = slide.body.text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { List(3) { Texts.get(TextId.SMARTART_TEXT) } }
        val n = lines.size
        val palette = listOf(0xFF2B579A, 0xFF217346, 0xFFC43E1C, 0xFFE8A33D, 0xFF8064A2, 0xFF4BACC6)
        lines.forEachIndexed { i, text ->
            val s = SlideShape(ShapeType.ROUNDED)
            s.box.paragraph.runs.clear()
            s.box.paragraph.runs.add(com.therealityteam.aeropages.model.TextRun(text, CharacterFormat(fontSizePt = 18f, bold = true, color = 0xFFFFFFFF)))
            s.fill = palette[i % palette.size]; s.border = null
            when (kind) {
                SmartArtKind.LIST -> { s.xPct = 0.18f; s.wPct = 0.64f; s.hPct = (0.6f / n).coerceAtMost(0.16f); s.yPct = 0.22f + i * (0.62f / n) }
                SmartArtKind.PROCESS -> { val w = 0.84f / n; s.xPct = 0.08f + i * w; s.wPct = w * 0.82f; s.hPct = 0.2f; s.yPct = 0.4f }
                SmartArtKind.CYCLE -> {
                    val ang = Math.toRadians(-90.0 + i * 360.0 / n)
                    s.wPct = 0.2f; s.hPct = 0.16f
                    s.xPct = (0.4f + 0.3f * Math.cos(ang)).toFloat().coerceIn(0f, 0.8f)
                    s.yPct = (0.42f + 0.3f * Math.sin(ang)).toFloat().coerceIn(0f, 0.84f)
                }
            }
            slide.shapes.add(s)
        }
        selectShape(slide.shapes.lastOrNull()); shapesInserted++
        dirty = true; bump()
    }

    fun addWordArt() {
        pushUndo()
        val s = SlideShape(ShapeType.TEXTBOX)
        s.box.paragraph.runs.clear()
        s.box.paragraph.runs.add(com.therealityteam.aeropages.model.TextRun(Texts.get(TextId.WORDART_TEXT),
            CharacterFormat(fontSizePt = 40f, bold = true, color = 0xFF2B579A)))
        s.wPct = 0.42f; s.hPct = 0.18f; s.fill = null; s.border = null
        slide.shapes.add(s); selectedShape = s; dirty = true; shapesInserted++
        // PowerPoint's: the words come selected, so the first keystroke replaces them.
        setSelection(s.box, 0, ParagraphOps.length(s.box.paragraph))
    }

    fun insertAtCaretOrTitle(text: String) {
        if (focusedRef == null) focusedRef = slide.title
        insertAtCaret(text)
    }

    // --- design: theme / slide size --------------------------------------------
    fun applyTheme(bg: Long?, titleColor: Long?) = applyThemeFull(presentation.themeIndex, bg, titleColor, master.bodyColor, null)
    /**
     * Design → Slide Size: the deck's slides at [size] and numbered from [firstNumber], one step back. What is on them is
     * scaled as PowerPoint scales it ([com.therealityteam.aeropages.model.SlideResize]: by one factor and centred, never stretched) — as large as the new
     * slide allows ([com.therealityteam.aeropages.model.SlideScale.MAXIMIZE]) or kept on it (ENSURE_FIT).
     */
    fun setSlideSize(size: com.therealityteam.aeropages.model.SlideSize,
                     scale: com.therealityteam.aeropages.model.SlideScale = com.therealityteam.aeropages.model.SlideScale.ENSURE_FIT,
                     firstNumber: Int = presentation.firstSlideNumber) {
        val resize = presentation.slideSize != size
        if (!resize && presentation.firstSlideNumber == firstNumber) return
        pushUndo(if (resize) both(deckMeta(), allSlides()) else deckMeta())
        if (resize) {
            com.therealityteam.aeropages.model.SlideResize.apply(presentation, size, scale)
            presentation.slideSize = size
        }
        presentation.firstSlideNumber = firstNumber.coerceIn(0, 9999)
        dirty = true; bump()
    }

    /**
     * 页眉和页脚 → Apply to All: the footer text, the slide number, the date ([date], or none) and
     * whether title slides go without them — on the canvas, the show and the PDF; saved to pptx and ODP.
     */
    fun setFooter(text: String, showSlideNumber: Boolean, date: com.therealityteam.aeropages.model.SlideDate? = presentation.date,
                  hideOnTitle: Boolean = presentation.hideFooterOnTitle) {
        pushUndo(deckMeta())
        presentation.footer = text.trim()
        presentation.showSlideNumber = showSlideNumber
        presentation.date = date
        presentation.hideFooterOnTitle = hideOnTitle
        dirty = true; bump()
    }

    // --- find & replace (查找替换) ------------------------------------------------

    /** Every editable text box on [s], in reading order: title, body, shapes, table cells. */
    private fun boxesOf(s: Slide): List<SlideBox> = buildList {
        add(s.title); add(s.body)
        s.shapes.forEach { add(it.box) }
        s.tables.forEach { t -> t.cells.forEach { row -> row.forEach { add(it) } } }
    }

    /**
     * Review ▸ Chinese Conversion (PowerPoint's 繁转简 / 简转繁): the text selected in a box — or, with none, every box
     * and the speaker notes on every slide — in Traditional ([toTraditional]) or Simplified characters; each run keeps
     * its look; one undo step. How many characters changed; -1 when the device cannot convert.
     */
    fun convertChinese(toTraditional: Boolean, vocabulary: Boolean = true): Int {
        if (!com.therealityteam.aeropages.model.ChineseConversion.available) return -1
        val convert = { s: String -> com.therealityteam.aeropages.model.ChineseConversion.convert(s, toTraditional, vocabulary) }
        val box = focusedBox()
        val a = minOf(selStart, selEnd); val b = maxOf(selStart, selEnd)
        var changed = 0
        val whole = box == null || b <= a
        val any = if (!whole) box!!.text.substring(a, b.coerceAtMost(box.text.length)).let { convert(it) != it }
            else presentation.slides.any { s -> boxesOf(s).any { convert(it.text) != it.text } || convert(s.notes) != s.notes }
        if (!any) return 0
        if (!whole) {
            pushUndo()
            changed = ParagraphOps.mapRuns(box!!.paragraph, a, b, convert)
            val len = ParagraphOps.length(box.paragraph)
            selStart = selStart.coerceIn(0, len); selEnd = selEnd.coerceIn(0, len)
        } else {
            pushUndo(allSlides())
            presentation.slides.forEach { s ->
                boxesOf(s).forEach { bx -> changed += ParagraphOps.mapRuns(bx.paragraph, 0, ParagraphOps.length(bx.paragraph), convert) }
                val notes = convert(s.notes)
                if (notes != s.notes) { changed += kotlin.math.abs(notes.length - s.notes.length) + (0 until minOf(notes.length, s.notes.length)).count { notes[it] != s.notes[it] }; s.notes = notes }
            }
            selStart = 0; selEnd = 0
        }
        if (changed > 0) { dirty = true; bump() }
        return changed
    }

    /** A match: on slide [slide], in [box] (its place [boxIndex] in [boxesOf]), characters [start] until [end]. */
    private class TextHit(val slide: Int, val boxIndex: Int, val box: SlideBox, val start: Int, val end: Int)

    /** Every match of [regex] in the deck's boxes, slide by slide, box by box (the speaker notes are not boxes). */
    private fun textHits(regex: Regex): List<TextHit> = buildList {
        presentation.slides.forEachIndexed { si, s ->
            boxesOf(s).forEachIndexed { bi, box ->
                for (m in regex.findAll(box.text)) if (m.range.last >= m.range.first) add(TextHit(si, bi, box, m.range.first, m.range.last + 1))
            }
        }
    }

    /** Whether [h] is what is selected now. */
    private fun isSelected(h: TextHit) = h.slide == current && focusedRef === h.box && minOf(selStart, selEnd) == h.start && maxOf(selStart, selEnd) == h.end

    /**
     * PowerPoint's Find Next (or, [back], Previous) for [query] with [options] (Word's: case, whole words, wildcards):
     * the match after the selection (before it) — in its box, then the next boxes and slides, round the deck — selected
     * in its box. Which match of how many, or null when there is none.
     */
    fun findText(query: String, options: SearchOptions, back: Boolean = false): Pair<Int, Int>? {
        val regex = DocSearch.pattern(query, options) ?: return null
        val hits = textHits(regex)
        if (hits.isEmpty()) return null
        val boxAt = focusedRef?.let { f -> boxesOf(slide).indexOfFirst { it === f } } ?: -1
        val a = minOf(selStart, selEnd); val b = maxOf(selStart, selEnd)
        fun after(h: TextHit) = h.slide > current || (h.slide == current && (h.boxIndex > boxAt || (h.boxIndex == boxAt && h.start >= b && !isSelected(h))))
        fun before(h: TextHit) = h.slide < current || (h.slide == current && (h.boxIndex < boxAt || (h.boxIndex == boxAt && h.end <= a && !isSelected(h))))
        val i = if (!back) hits.indexOfFirst(::after).takeIf { it >= 0 } ?: 0 else hits.indexOfLast(::before).takeIf { it >= 0 } ?: hits.lastIndex
        val h = hits[i]
        current = h.slide
        lastKey = null
        selectedShape = null; selectedTable = null; selectedChart = null; selectedImage = null
        setSelection(h.box, h.start, h.end)
        return i to hits.size
    }

    /** Replace: the selected match becomes [replacement] (`^&`, groups and codes as Word's), then the next is found. */
    fun replaceFound(query: String, replacement: String, options: SearchOptions): Pair<Int, Int>? {
        val regex = DocSearch.pattern(query, options) ?: return null
        val cur = textHits(regex).firstOrNull(::isSelected)
        if (cur != null) {
            val m = regex.find(cur.box.text, cur.start)?.takeIf { it.range.first == cur.start }
            if (m != null) {
                pushUndo()
                val text = DocSearch.expand(replacement, m, options)
                ParagraphOps.replace(cur.box.paragraph, cur.start, cur.end, text, ParagraphOps.formatAt(cur.box.paragraph, cur.start))
                selStart = cur.start + text.length; selEnd = selStart
                dirty = true; bump()
            }
        }
        return findText(query, options)
    }

    /** Replace All: every match in titles, bodies, shapes, tables and speaker notes on every slide — one undo step; how many. */
    fun replaceAllFound(query: String, replacement: String, options: SearchOptions): Int {
        val regex = DocSearch.pattern(query, options) ?: return 0
        val hits = textHits(regex)
        val inNotes = presentation.slides.sumOf { s -> regex.findAll(s.notes).count { it.range.last >= it.range.first } }
        if (hits.isEmpty() && inNotes == 0) return 0
        pushUndo(allSlides())
        // From the last back, so the offsets before each stay right.
        for (h in hits.asReversed()) {
            val m = regex.find(h.box.text, h.start)?.takeIf { it.range.first == h.start } ?: continue
            ParagraphOps.replace(h.box.paragraph, h.start, h.end, DocSearch.expand(replacement, m, options), ParagraphOps.formatAt(h.box.paragraph, h.start))
        }
        presentation.slides.forEach { s -> s.notes = regex.replace(s.notes) { DocSearch.expand(replacement, it, options) } }
        selStart = 0; selEnd = 0
        dirty = true; bump()
        return hits.size + inNotes
    }

    // The old calls (plain text, case only): the slide the next match is on; how many replaced.
    fun findNext(query: String, matchCase: Boolean = false): Int? = findText(query, SearchOptions(matchCase))?.let { current }
    fun replaceAll(query: String, replacement: String, matchCase: Boolean = false): Int = replaceAllFound(query, replacement, SearchOptions(matchCase))

    // --- proofing (校对) ------------------------------------------------------------

    /** A text of the deck to proof: [box] — the slide's [part] — on slide [slide], or (no box) that slide's speaker notes. */
    class ProofTarget(val slide: Int, val box: SlideBox?, val part: ProofPart)

    enum class ProofPart { TITLE, BODY, SHAPE, TABLE, NOTES }

    /** Every text to proof, in reading order: each slide's title, body, shapes and table cells, then its notes. */
    fun proofTargets(): List<ProofTarget> = buildList {
        presentation.slides.forEachIndexed { i, s ->
            add(ProofTarget(i, s.title, ProofPart.TITLE)); add(ProofTarget(i, s.body, ProofPart.BODY))
            s.shapes.forEach { add(ProofTarget(i, it.box, ProofPart.SHAPE)) }
            s.tables.forEach { t -> t.cells.forEach { row -> row.forEach { add(ProofTarget(i, it, ProofPart.TABLE)) } } }
            add(ProofTarget(i, null, ProofPart.NOTES))
        }
    }

    /** What [t] says now. */
    fun proofText(t: ProofTarget): String = t.box?.text ?: presentation.slides.getOrNull(t.slide)?.notes.orEmpty()

    /** AeroDesk's own checks on [targets] (the spell checker's typos come on top): words written twice, double spaces. */
    fun proofread(targets: List<ProofTarget>): List<ProofIssue> = targets.flatMapIndexed { i, t -> ownProofIssues(i, proofText(t)) }

    /**
     * A spelling suggestion taken: [word] at [start] of [t] becomes [replacement], in the word's own
     * formatting, one undo step. False when the text there has changed since it was checked.
     */
    fun applySpelling(t: ProofTarget, start: Int, word: String, replacement: String): Boolean {
        val s = presentation.slides.getOrNull(t.slide) ?: return false
        val text = proofText(t)
        if (!wordAt(text, start, word)) return false
        pushUndo(SlideDelta(t.slide, s.copyOf()))
        if (t.box != null) ParagraphOps.replace(t.box.paragraph, start, start + word.length, replacement, ParagraphOps.formatAt(t.box.paragraph, start))
        else s.notes = text.replaceRange(start, start + word.length, replacement)
        dirty = true; bump()
        return true
    }

    /** Shows [t]: its slide, its box focused (the notes: the slide alone). */
    fun showProofTarget(t: ProofTarget) {
        if (t.slide !in presentation.slides.indices) return
        select(t.slide)
        if (t.box != null) { focusedRef = t.box; bump() }
    }

    // --- review comments --------------------------------------------------------
    /** The comment thread picked (a marker on the slide or its card in the pane): the one a reply goes to; -1 none. */
    var pickedComment by mutableIntStateOf(-1)

    /** Name stamped on new comments and replies (PowerPoint's user name); blank = the localised "Me". */
    var reviewer: String = ""
    val reviewerName: String get() = reviewer.ifBlank { com.therealityteam.aeropages.model.Texts.get(com.therealityteam.aeropages.model.TextId.COMMENT_AUTHOR_ME) }

    /** A new comment on the slide by the reviewer, pinned as PowerPoint pins new ones: at the top left, each next one a step down and right. */
    fun addComment(text: String) {
        if (text.isBlank()) return
        pushUndo()
        val k = slide.comments.size % 10
        slide.comments.add(com.therealityteam.aeropages.model.SlideComment(reviewerName, text.trim(), x = 0.02f + 0.025f * k, y = 0.03f + 0.04f * k))
        dirty = true; bump()
    }
    /** A reply to comment [index] by the reviewer. */
    fun replyToComment(index: Int, text: String) {
        val c = slide.comments.getOrNull(index) ?: return
        if (text.isBlank()) return
        pushUndo()
        slide.comments[index] = c.copy(replies = c.replies + com.therealityteam.aeropages.model.SlideComment(reviewerName, text.trim(), x = c.x, y = c.y))
        dirty = true; bump()
    }
    /** Comment [index] resolved (or reopened). */
    fun resolveComment(index: Int, resolved: Boolean = true) {
        val c = slide.comments.getOrNull(index) ?: return
        if (c.resolved == resolved) return
        pushUndo(); slide.comments[index] = c.copy(resolved = resolved); dirty = true; bump()
    }
    fun deleteComment(index: Int) { if (index in slide.comments.indices) { pushUndo(); slide.comments.removeAt(index); dirty = true; bump() } }

    // --- shape fill / outline / arrange (selected shape) -----------------------
    fun setShapeFill(color: Long?) { val all = selectedShapes; if (all.isNotEmpty()) { pushUndo(); all.forEach { it.fill = color; if (color == null) it.fillGradient = null }; dirty = true; bump() } }
    /** Vertical text anchor of the selected shapes (PowerPoint's Align Text: top / middle / bottom). */
    fun setShapeAnchor(anchor: com.therealityteam.aeropages.model.ShapeAnchor) {
        val all = selectedShapes; if (all.isEmpty()) return
        pushUndo(); all.forEach { it.anchor = anchor }; dirty = true; bump()
    }
    fun cycleShapeAnchor() {
        val cur = selectedShape?.anchor ?: return
        val values = com.therealityteam.aeropages.model.ShapeAnchor.values()
        setShapeAnchor(values[(cur.ordinal + 1) % values.size])
    }
    fun setShapeBorder(color: Long?) { val all = selectedShapes; if (all.isNotEmpty()) { pushUndo(); all.forEach { it.border = color }; dirty = true; bump() } }
    /** PowerPoint's Bring Forward / Send Backward: the selected shape one place up ([up]) or down the slide's stack. */
    fun stepShapeLayer(up: Boolean) { selectedShape?.let { if (stepIn(slide.shapes, it, up)) { dirty = true; bump() } } }
    /** Bring Forward / Send Backward for the selected picture, among the slide's pictures. */
    fun stepImageLayer(up: Boolean) { selectedImage?.let { if (stepIn(slide.images, it, up)) { dirty = true; bump() } } }
    private fun <T> stepIn(list: MutableList<T>, item: T, up: Boolean): Boolean {
        val i = list.indexOf(item)
        val j = if (up) i + 1 else i - 1
        if (i < 0 || j !in list.indices) return false
        pushUndo()
        list.removeAt(i); list.add(j, item)
        return true
    }
    fun bringShapeToFront() { selectedShape?.let { pushUndo(); slide.shapes.remove(it); slide.shapes.add(it); dirty = true; bump() } }
    fun sendShapeToBack() { selectedShape?.let { pushUndo(); slide.shapes.remove(it); slide.shapes.add(0, it); dirty = true; bump() } }

    // --- pictures (图片) ----------------------------------------------------------
    /** Slide aspect (width / height): fractions are relative to different edges, so a picture's aspect has to be corrected by it. */
    private val slideAspect: Float get() = presentation.slideSize.aspect

    /**
     * Insert a picture: the largest size within 50% × 42% of the slide that keeps its aspect
     * ratio, centred horizontally under the body area, cascaded when the slide already has pictures.
     */
    fun setImage(data: ByteArray, mime: String, widthPt: Float, heightPt: Float) {
        pushUndo()
        val img = com.therealityteam.aeropages.model.SlideImage(data, mime, widthPt, heightPt)
        if (!intoContent(img)) {
            fitImage(img, 0.5f, 0.42f)
            val k = slide.images.size
            img.xPct = ((1f - img.wPct) / 2f + k * 0.03f).within(0f, 1f - img.wPct)
            img.yPct = (0.5f + k * 0.03f).within(0f, 1f - img.hPct)
        }
        slide.images.add(img); dirty = true; selectImage(img)
    }
    /**
     * Insert → Video / Audio: [media] on the current slide as the picture that stands for it — [poster], its first frame
     * or a speaker — a video large in the middle, a sound's icon small in the middle (PowerPoint's places), picked.
     */
    fun insertMedia(media: com.therealityteam.aeropages.model.SlideMedia, poster: ByteArray, posterMime: String, widthPt: Float, heightPt: Float) {
        pushUndo()
        val img = com.therealityteam.aeropages.model.SlideImage(poster, posterMime, widthPt, heightPt, media = media)
        val audio = media.kind == com.therealityteam.aeropages.model.SlideMedia.Kind.AUDIO
        if (audio || !intoContent(img)) {
            if (audio) fitImage(img, 0.08f, 0.14f) else fitImage(img, 0.6f, 0.6f)
            img.xPct = ((1f - img.wPct) / 2f).within(0f, 1f - img.wPct)
            img.yPct = ((1f - img.hPct) / 2f).within(0f, 1f - img.hPct)
        }
        slide.images.add(img); dirty = true; selectImage(img)
    }

    /**
     * PowerPoint's content placeholder: on a Title and Content slide whose body is empty and not yet taken, [img] goes in
     * it — the largest size within the placeholder that keeps its aspect, in its middle. False when the slide has no free one.
     */
    private fun intoContent(img: com.therealityteam.aeropages.model.SlideImage): Boolean {
        if (!slide.contentFree) return false
        val f = slide.contentFrame
        fitImage(img, f.wPct, f.hPct)
        img.xPct = f.xPct + (f.wPct - img.wPct) / 2f; img.yPct = f.yPct + (f.hPct - img.hPct) / 2f
        return true
    }

    /** Set [img]'s frame to the largest size within [maxW] × [maxH] (slide fractions) that keeps its aspect ratio. */
    private fun fitImage(img: com.therealityteam.aeropages.model.SlideImage, maxW: Float, maxH: Float) {
        // The part a crop leaves is what the frame shows, so its aspect is the one to keep.
        val aspect = img.visibleAspect
        var w = maxW
        var h = w * slideAspect / aspect
        if (h > maxH) { h = maxH; w = h * aspect / slideAspect }
        img.wPct = w.coerceIn(0.02f, 1f); img.hPct = h.coerceIn(0.02f, 1f)
    }
    fun selectImage(img: com.therealityteam.aeropages.model.SlideImage?) {
        selectedImage = img
        if (img != null) { selectedShape = null; selectedTable = null; selectedChart = null; focusedRef = null }
        bump()
    }
    /** Delete the selected picture, or the last one inserted when none is selected. */
    fun removeImage() {
        val target = selectedImage ?: slide.images.lastOrNull() ?: return
        pushUndo(); slide.images.remove(target); if (selectedImage === target) selectedImage = null; dirty = true; bump()
    }
    fun deleteSelectedImage() { if (selectedImage != null) removeImage() }
    /** Restore the selected picture's own aspect ratio (after a free resize), keeping its width where it fits. */
    fun resetImageAspect() {
        selectedImage?.let { img -> pushUndo(); fitImage(img, img.wPct, (1f - img.yPct).coerceAtLeast(0.02f)); dirty = true; bump() }
    }
    /** Scale the selected picture about its top-left corner by [factor]. */
    fun scaleImage(factor: Float) {
        selectedImage?.let { img ->
            pushUndo()
            val f = pictureFactor(img.wPct, img.hPct, factor, 1f - img.xPct, 1f - img.yPct)
            img.wPct *= f; img.hPct *= f
            dirty = true; bump()
        }
    }

    /**
     * [factor] limited so a picture [w] × [h] (slide fractions) scaled by it keeps its proportions, fits
     * [roomW] × [roomH] and stays at least 2% of the slide each way (one already past a limit only
     * moves back toward it). Pictures stretch to their frame, as in PowerPoint, so every resize of
     * one goes through here.
     */
    private fun pictureFactor(w: Float, h: Float, factor: Float, roomW: Float, roomH: Float): Float {
        val cw = w.coerceAtLeast(1e-4f); val ch = h.coerceAtLeast(1e-4f)
        val most = minOf(roomW / cw, roomH / ch).coerceAtLeast(1f)
        val least = maxOf(0.02f / cw, 0.02f / ch).coerceAtMost(1f)
        return factor.coerceIn(least, most)
    }
    /**
     * PowerPoint's Crop on the selected picture: [l], [t], [r], [b] of the source cut away (fractions,
     * the sides together leaving at least 5% each way). The picture keeps its scale — what stays
     * visible stays where it is and the frame shrinks or grows to the new edges. One undo step.
     */
    fun cropImage(l: Float, t: Float, r: Float, b: Float) {
        val img = selectedImage ?: return
        val (nl, nt, nr, nb) = com.therealityteam.aeropages.model.Croppable.clamp(l, t, r, b)
        if (nl == img.cropL && nt == img.cropT && nr == img.cropR && nb == img.cropB) return
        pushUndo()
        // The whole picture's extent on the slide at its current scale, then the part the new crop leaves.
        val (fullW, fullH) = img.uncroppedSize(img.wPct, img.hPct)
        val fullX = img.xPct - img.cropL * fullW
        val fullY = img.yPct - img.cropT * fullH
        img.setCrop(nl, nt, nr, nb)
        img.xPct = fullX + nl * fullW; img.yPct = fullY + nt * fullH
        img.wPct = (fullW * (1f - nl - nr)).coerceAtLeast(0.02f)
        img.hPct = (fullH * (1f - nt - nb)).coerceAtLeast(0.02f)
        dirty = true; bump()
    }

    /** Crop to Aspect Ratio: the largest centred part of the whole picture [ratio] (width / height) wide. */
    fun cropImageToAspect(ratio: Float) {
        val img = selectedImage ?: return
        if (ratio <= 0f) return
        if (img.aspect > ratio) { val cut = (1f - ratio / img.aspect) / 2f; cropImage(cut, 0f, cut, 0f) }
        else { val cut = (1f - img.aspect / ratio) / 2f; cropImage(0f, cut, 0f, cut) }
    }

    /** Reset the crop: the whole picture again, at the scale it had. */
    fun resetImageCrop() = cropImage(0f, 0f, 0f, 0f)

    /** Alt Text on the selected picture: what a screen reader says for it, or ([decorative]) that it passes it by. One undo step. */
    fun setImageAltText(text: String, decorative: Boolean) {
        val img = selectedImage ?: return
        val t = if (decorative) "" else text.trim()
        if (img.altText == t && img.decorative == decorative) return
        pushUndo(); img.altText = t; img.decorative = decorative; dirty = true; bump()
    }

    /** The selected picture's outer shadow — one of the Shadow presets, or none. */
    fun setImageShadow(shadow: com.therealityteam.aeropages.model.ShapeShadow?) {
        selectedImage?.let { pushUndo(); it.shadow = shadow; dirty = true; bump() }
    }

    fun bringImageToFront() { selectedImage?.let { pushUndo(); slide.images.remove(it); slide.images.add(it); dirty = true; bump() } }
    fun sendImageToBack() { selectedImage?.let { pushUndo(); slide.images.remove(it); slide.images.add(0, it); dirty = true; bump() } }

    fun setTransition(t: com.therealityteam.aeropages.model.SlideTransition) { pushUndo(); slide.transition = t; dirty = true; bump() }
    fun setTransitionAll(t: com.therealityteam.aeropages.model.SlideTransition) {
        pushUndo(allSlides()); presentation.slides.forEach { it.transition = t }; dirty = true; bump()
    }
    fun setTransitionSpeed(speed: com.therealityteam.aeropages.model.TransitionSpeed) { pushUndo(); slide.transitionSpeed = speed; dirty = true; bump() }

    /** Effect Options: which way the slide's transition goes. */
    fun setTransitionDir(dir: com.therealityteam.aeropages.model.TransitionDir) { pushUndo(); slide.transitionDir = dir; dirty = true; bump() }

    /** Advance Slide ▸ After: the slide moves on after [ms] in the show (0: on a click only). */
    fun setAdvanceAfter(ms: Int) { pushUndo(); slide.advanceMs = ms.coerceIn(0, 3_600_000); dirty = true; bump() }

    /** PowerPoint's Apply To All: every slide takes this slide's transition, its options, duration and advance timing (one step back). */
    fun applyTransitionToAll() {
        val s = slide
        pushUndo(allSlides())
        presentation.slides.forEach { it.transition = s.transition; it.transitionDir = s.transitionDir; it.transitionSpeed = s.transitionSpeed; it.advanceMs = s.advanceMs }
        dirty = true; bump()
    }

    // --- sections (节) ------------------------------------------------------------------

    /** Start a section at the current slide (or rename the one it already starts). */
    fun setSectionName(name: String) { pushUndo(); slide.sectionName = name.trim().ifEmpty { null }; dirty = true; bump() }
    fun removeSection() { if (slide.sectionName == null) return; pushUndo(); slide.sectionName = null; dirty = true; bump() }
    /** The name of the section slide [index] belongs to, null before the first section. */
    fun sectionOf(index: Int): String? {
        for (i in index.coerceIn(0, presentation.slides.lastIndex) downTo 0) presentation.slides[i].sectionName?.let { return it }
        return null
    }
    /** The sections in order: name and the index of the slide that starts it. */
    fun sections(): List<Pair<String, Int>> = presentation.slides.mapIndexedNotNull { i, s -> s.sectionName?.let { it to i } }

    fun setAlign(align: TextAlign) { focusedBox()?.let { pushUndo(); it.paragraph.format.align = align; dirty = true; bump() } }

    /** Apply a character-format change to the focused box's selection (or whole box). */
    private fun applyChar(change: (CharacterFormat) -> Unit) {
        val box = focusedBox() ?: return
        pushUndo()
        val (s, e) = selRange() ?: (0 to ParagraphOps.length(box.paragraph).coerceAtLeast(1))
        ParagraphOps.applyFormat(box.paragraph, s, e) { change(it) }
        dirty = true; bump()
    }

    fun toggleBold() { val on = !currentFormat.bold; applyChar { it.bold = on } }
    fun toggleItalic() { val on = !currentFormat.italic; applyChar { it.italic = on } }
    fun toggleUnderline() { val on = !currentFormat.underline; applyChar { it.underline = on } }
    fun toggleStrike() { val on = !currentFormat.strikethrough; applyChar { it.strikethrough = on } }
    fun toggleSuperscript() { val on = !currentFormat.superscript; applyChar { it.superscript = on; if (on) it.subscript = false } }
    fun toggleSubscript() { val on = !currentFormat.subscript; applyChar { it.subscript = on; if (on) it.superscript = false } }
    fun toggleSmallCaps() { val on = !currentFormat.smallCaps; applyChar { it.smallCaps = on } }
    fun setFontSize(pt: Float) = applyChar { it.fontSizePt = pt }
    fun setFontFamily(name: String) = applyChar { it.fontFamily = name }
    fun setColor(color: Long?) = applyChar { it.color = color }
    fun setHighlight(color: Long?) = applyChar { it.highlight = color }
    fun setLetterSpacing(pt: Float?) = applyChar { it.letterSpacingPt = pt }

    /** PowerPoint's Font dialog: [change] made to the box being edited — its selection, else all its text — one undo step. */
    fun formatCharacters(change: (CharacterFormat) -> Unit) = applyChar(change)

    /** The box being edited's alignment and line spacing as PowerPoint's Paragraph dialog sets them; null leaves one as it is. One undo step. */
    fun formatParagraph(align: TextAlign?, lineSpacing: Float?) {
        val box = focusedBox() ?: return
        if ((align == null || align == box.paragraph.format.align) && (lineSpacing == null || lineSpacing == box.lineSpacing)) return
        pushUndo()
        align?.let { box.paragraph.format.align = it }
        lineSpacing?.let { box.lineSpacing = it.coerceIn(0.5f, 5f) }
        dirty = true; bump()
    }
    fun clearFormatting() = applyChar {
        it.bold = false; it.italic = false; it.underline = false; it.strikethrough = false
        it.superscript = false; it.subscript = false; it.smallCaps = false
        it.color = null; it.highlight = null; it.fontSizePt = null; it.fontFamily = null; it.letterSpacingPt = null
    }

    /** Line spacing of the box being edited, as a multiple of single spacing. One undo step. */
    fun setLineSpacing(multiplier: Float) {
        focusedBox()?.let { pushUndo(); it.lineSpacing = multiplier.coerceIn(0.5f, 5f); dirty = true; bump() }
    }

    /**
     * PowerPoint's Increase / Decrease List Level (Tab / Shift+Tab at a line's start): every line the
     * selection [start]..[end] of [box] touches goes a level deeper ([delta] > 0) or back out, within
     * 0–8, its text and formatting kept — a level is the tabs a line starts with. One undo step; the
     * selection moves with the text.
     */
    fun changeListLevel(box: SlideBox, start: Int, end: Int, delta: Int) {
        val text = box.text
        val a = minOf(start, end).coerceIn(0, text.length)
        val b = maxOf(start, end).coerceIn(0, text.length)
        val starts = com.therealityteam.aeropages.model.SlideLevels.lineStarts(text)
        val first = com.therealityteam.aeropages.model.SlideLevels.lineOf(text, a)
        // A selection ending just after a line break leaves the next line alone.
        val last = com.therealityteam.aeropages.model.SlideLevels.lineOf(text, if (b > a && text[b - 1] == '\n') b - 1 else b)
        val lines = (first..last).filter { i ->
            val level = com.therealityteam.aeropages.model.SlideLevels.levelAt(text, starts[i])
            if (delta > 0) level < com.therealityteam.aeropages.model.SlideLevels.MAX else level > 0
        }
        if (lines.isEmpty()) return
        pushUndo()
        val para = box.paragraph
        for (i in lines.reversed()) {
            val at = starts[i]
            if (delta > 0) ParagraphOps.replace(para, at, at, "\t", ParagraphOps.formatAt(para, at))
            else ParagraphOps.replace(para, at, at + 1, "", ParagraphOps.formatAt(para, at))
        }
        // Offsets past a changed line's start move with its tab (a caret at the start goes after a new one).
        fun moved(pos: Int): Int = pos + lines.sumOf { i -> val at = starts[i]; if (delta > 0) (if (pos >= at) 1 else 0).toInt() else (if (pos > at) -1 else 0).toInt() }
        focusedRef = box
        val len = ParagraphOps.length(para)
        selStart = moved(start).coerceIn(0, len); selEnd = moved(end).coerceIn(0, len)
        dirty = true; bump()
    }

    /** Increase / Decrease List Level for the lines of the box being edited. */
    fun changeListLevel(delta: Int) { focusedBox()?.let { changeListLevel(it, selStart, selEnd, delta) } }

    fun toggleBullet() {
        focusedBox()?.let {
            pushUndo()
            it.listStyle = if (it.listStyle == com.therealityteam.aeropages.model.SlideListStyle.BULLET)
                com.therealityteam.aeropages.model.SlideListStyle.NONE else com.therealityteam.aeropages.model.SlideListStyle.BULLET
            dirty = true; bump()
        }
    }

    fun toggleNumber() {
        focusedBox()?.let {
            pushUndo()
            it.listStyle = if (it.listStyle == com.therealityteam.aeropages.model.SlideListStyle.NUMBER)
                com.therealityteam.aeropages.model.SlideListStyle.NONE else com.therealityteam.aeropages.model.SlideListStyle.NUMBER
            dirty = true; bump()
        }
    }

    fun changeCase(mode: CaseMode) {
        val box = focusedBox() ?: return
        pushUndo()
        val (s, e) = selRange() ?: (0 to ParagraphOps.length(box.paragraph))
        ParagraphOps.mapText(box.paragraph, s, e) { str ->
            when (mode) {
                CaseMode.UPPER -> str.uppercase()
                CaseMode.LOWER -> str.lowercase()
                CaseMode.TITLE -> buildString {
                    var boundary = true
                    for (c in str) { append(if (boundary && c.isLetter()) c.uppercaseChar() else c.lowercaseChar()); boundary = !c.isLetterOrDigit() }
                }
                CaseMode.TOGGLE -> str.map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.joinToString("")
            }
        }
        dirty = true; bump()
    }

    /** Format at the caret of the focused box (drives ribbon toggle state). */
    val currentFormat: CharacterFormat
        get() = focusedBox()?.let { ParagraphOps.formatAt(it.paragraph, selStart) } ?: CharacterFormat()

    // --- clipboard / format painter (focused box) ------------------------------

    fun selectedText(): String {
        val box = focusedBox() ?: return ""
        val t = box.text
        val (s, e) = selRange() ?: return ""
        return t.substring(s.coerceIn(0, t.length), e.coerceIn(0, t.length))
    }

    fun deleteSelection() {
        val box = focusedBox() ?: return
        val (s, e) = selRange() ?: return
        pushUndo()
        ParagraphOps.replace(box.paragraph, s, e, "", ParagraphOps.formatAt(box.paragraph, (s - 1).coerceAtLeast(0)))
        selStart = s; selEnd = s; dirty = true; bump()
    }

    fun insertAtCaret(text: String) {
        val box = focusedBox() ?: return
        pushUndo("text:" + System.identityHashCode(box))
        val (s, e) = selRange() ?: (selStart to selStart)
        ParagraphOps.replace(box.paragraph, s, e, text, ParagraphOps.formatAt(box.paragraph, (s - 1).coerceAtLeast(0)))
        val caret = s + text.length; selStart = caret; selEnd = caret; dirty = true; bump()
    }

    // --- PowerPoint's AutoCorrect as you type -------------------------------------------------------

    /** A correction just made in a text box: what, in which box (by its place in reading order) and what it was. */
    class AutoFix internal constructor(
        val kind: AutoCorrect.Kind, val was: String, val now: String, internal val slide: Int, internal val box: Int, internal val edits: Int,
    )

    private var lastAutoFix: AutoFix? = null

    /** The correction the AutoCorrect Options button by [box] is for: the last one made in it, while nothing changed since. */
    fun autoFixFor(box: SlideBox): AutoFix? =
        lastAutoFix?.takeIf { it.edits == edits && it.slide == current && boxesOf(slide).getOrNull(it.box) === box }

    /**
     * After a character typed at the caret in [box]: what AutoCorrect makes of it (lists aside — a box has its own
     * bullets), as its own undo step. True when anything changed.
     */
    fun autoCorrectTyped(box: SlideBox): Boolean {
        if (selStart != selEnd) return false
        val para = box.paragraph
        val text = para.plainText()
        val fixes = AutoCorrect.doable(para, AutoCorrect.after(text, selStart, AutoCorrect.languageOf(text)).filter { it !is AutoCorrect.MakeList })
        if (fixes.isEmpty()) return false
        val (main, was, now) = AutoCorrect.named(text, fixes)
        pushUndo()
        var at = selStart
        for (fx in fixes) at += AutoCorrect.make(para, fx)
        selStart = at; selEnd = at
        lastKey = null; dirty = true; bump()
        lastAutoFix = AutoFix(main.kind, was, now, current, boxesOf(slide).indexOf(box), edits)
        return true
    }

    /** The AutoCorrect Options button's "Undo …": the correction undone, what was typed back. */
    fun undoAutoFix(): Boolean {
        if (lastAutoFix?.edits != edits) return false
        lastAutoFix = null
        undo()
        return true
    }

    // --- formatted copy and PowerPoint's Paste Options ----------------------------------------------

    /**
     * A paste just made into a text box: how, where (the slide, the box by its place in reading order — undo gives the
     * slide back as copies — and the pasted text's start and end), the formatting where it landed, and the clip.
     */
    class PasteMark internal constructor(
        val mode: DocumentEditor.PasteMode, internal val slide: Int, internal val box: Int, internal val start: Int, val end: Int,
        internal val base: CharacterFormat, internal val clip: DocClip, internal val edits: Int,
    )

    /** Counts every edit (each is recorded for undo first), undo, redo and load: a paste knows whether anything came after it. */
    private var edits = 0
    private var lastPaste: PasteMark? = null

    /** The paste PowerPoint's Paste Options button is for: the last formatted paste, while nothing has changed since. */
    val pasteOptions: PasteMark? get() = lastPaste?.takeIf { it.edits == edits && it.slide == current }

    /** [pasteOptions] when its paste went into [box]. */
    fun pasteOptionsFor(box: SlideBox): PasteMark? = pasteOptions?.takeIf { boxesOf(slide).getOrNull(it.box) === box }

    /**
     * The focused box's selected text as a [DocClip] — its runs with their formats, each line a paragraph (so a paste
     * in Pages makes paragraphs of them); fields and revision marks dropped. Null with nothing selected.
     */
    fun copiedClip(): DocClip? {
        val box = focusedBox() ?: return null
        val (s, e) = selRange() ?: return null
        val piece = com.therealityteam.aeropages.editor.TableText.slice(box.paragraph, s until e)
        piece.runs.removeAll { it.format.deleted }
        piece.runs.forEach { r -> r.format.field = null; r.format.inserted = false; r.format.formatChanged = false; r.format.before = null; r.format.revAuthor = null; r.format.revDate = null }
        val lines = ArrayList<com.therealityteam.aeropages.model.Paragraph>()
        var rest = piece
        while (true) {
            val nl = rest.plainText().indexOf('\n')
            if (nl < 0) break
            val tail = ParagraphOps.splitAt(rest, nl)
            ParagraphOps.replace(tail, 0, 1, "", ParagraphOps.formatAt(tail, 0))
            lines.add(rest); rest = tail
        }
        lines.add(rest)
        return DocClip(selectedText(), lines, null)
    }

    /**
     * Pastes [clip] over the focused box's selection [mode]'s way — as copied (PowerPoint's Keep Source Formatting),
     * the box's own formatting with the copied emphasis (Use Destination Theme), or plain (Keep Text Only); its
     * paragraphs become lines. One undo step; false with no box focused.
     */
    fun pasteClip(clip: DocClip, mode: DocumentEditor.PasteMode = DocumentEditor.PasteMode.KEEP_SOURCE): Boolean {
        val box = focusedBox() ?: return false
        val index = boxesOf(slide).indexOfFirst { it === box }
        if (index < 0) return false
        val (s, e) = selRange() ?: (selStart to selStart)
        pushUndo()
        val para = box.paragraph
        val base = ParagraphOps.formatAt(para, (s - 1).coerceAtLeast(0)).copyOf()
        ParagraphOps.replace(para, s, e, "", base)
        val end = putClip(para, s, clip, mode, base)
        selStart = end; selEnd = end; dirty = true; bump()
        lastPaste = PasteMark(mode, current, index, s, end, base, clip, edits)
        return true
    }

    /**
     * Paste Options: the last paste put again [mode]'s way, in place — still the paste's one undo step (nothing has
     * changed since it, so its undo still takes the box back to before it). False when there is no such paste.
     */
    fun repaste(mode: DocumentEditor.PasteMode): Boolean {
        val m = pasteOptions ?: return false
        if (m.mode == mode) return true
        val box = boxesOf(slide).getOrNull(m.box) ?: return false
        val para = box.paragraph
        ParagraphOps.replace(para, m.start, m.end.coerceAtMost(ParagraphOps.length(para)), "", m.base)
        val end = putClip(para, m.start, m.clip, mode, m.base)
        focusedRef = box; selStart = end; selEnd = end; dirty = true; bump()
        lastPaste = PasteMark(mode, m.slide, m.box, m.start, end, m.base, m.clip, edits)
        return true
    }

    /** [clip]'s runs into [para] at [at], [mode]'s way over [base] (the formatting there), its paragraphs as lines; the end. */
    private fun putClip(para: com.therealityteam.aeropages.model.Paragraph, at: Int, clip: DocClip, mode: DocumentEditor.PasteMode, base: CharacterFormat): Int {
        fun formatOf(src: CharacterFormat) = when (mode) {
            DocumentEditor.PasteMode.KEEP_SOURCE -> src.copyOf()
            DocumentEditor.PasteMode.MERGE -> base.copyOf().also {
                it.bold = src.bold; it.italic = src.italic; it.underline = src.underline; it.strikethrough = src.strikethrough
                it.superscript = src.superscript; it.subscript = src.subscript; it.link = src.link
            }
            DocumentEditor.PasteMode.TEXT_ONLY -> base.copyOf().also { it.math = null }
        }.also { it.deleted = false; it.formatChanged = false; it.before = null; it.field = null; it.inserted = false; it.revAuthor = null; it.revDate = null }
        var o = at
        clip.paragraphs.forEachIndexed { i, p ->
            if (i > 0) { ParagraphOps.replace(para, o, o, "\n", formatOf(p.runs.firstOrNull()?.format ?: base)); o++ }
            for (r in p.runs) if (r.text.isNotEmpty()) { ParagraphOps.replace(para, o, o, r.text, formatOf(r.format)); o += r.text.length }
        }
        return o
    }

    var painterArmed by mutableStateOf(false)
        private set
    private var painterFormat: CharacterFormat? = null

    fun formatPainter() {
        if (!painterArmed) {
            painterFormat = currentFormat.copyOf(); painterArmed = true; bump()
        } else {
            val src = painterFormat
            painterArmed = false; painterFormat = null
            if (src != null) applyChar { d ->
                d.bold = src.bold; d.italic = src.italic; d.underline = src.underline; d.strikethrough = src.strikethrough
                d.superscript = src.superscript; d.subscript = src.subscript; d.smallCaps = src.smallCaps
                d.fontSizePt = src.fontSizePt; d.fontFamily = src.fontFamily; d.color = src.color; d.highlight = src.highlight
            }
        }
    }

    // --- tables ----------------------------------------------------------------
    fun addTable(rows: Int, cols: Int) {
        pushUndo()
        val t = com.therealityteam.aeropages.model.SlideTable.create(rows.coerceIn(1, 20), cols.coerceIn(1, 20))
        // In a free content placeholder: its left, top and width, each row PowerPoint's 0.4 inch (within the placeholder).
        if (slide.contentFree) slide.contentFrame.let { f ->
            t.xPct = f.xPct; t.yPct = f.yPct; t.wPct = f.wPct
            t.hPct = (t.rows * 28.8f / presentation.slideSize.heightPt).coerceAtMost(f.hPct)
        }
        slide.tables.add(t); selectTable(t); dirty = true; bump()
    }
    fun selectTable(t: com.therealityteam.aeropages.model.SlideTable?) {
        cellTyped = false; tableRange = null
        selectedTable = t; if (t != null) { selectedShape = null; selectedChart = null; selectedImage = null; focusedRef = t.cells.firstOrNull()?.firstOrNull() }
        bump()
    }
    fun deleteSelectedTable() { selectedTable?.let { pushUndo(); slide.tables.remove(it); selectedTable = null; focusedRef = null; dirty = true; bump() } }
    fun setTableStyle(index: Int) { selectedTable?.let { pushUndo(); it.styleIndex = index; dirty = true; bump() } }
    fun toggleTableOpt(which: String) {
        selectedTable?.let {
            pushUndo()
            when (which) {
                "header" -> it.headerRow = !it.headerRow
                "bandedRows" -> it.bandedRows = !it.bandedRows
                "firstCol" -> it.firstColumn = !it.firstColumn
                "lastCol" -> it.lastColumn = !it.lastColumn
                "total" -> it.totalRow = !it.totalRow
                "bandedCols" -> it.bandedColumns = !it.bandedColumns
            }
            dirty = true; bump()
        }
    }
    /** The shading Table Design ▸ Shading shows: the cell being typed in's own, else (the table picked) its first cell's. */
    val tableShading: Long? get() = selectedTable?.let { t -> targetRect(t).let { t.cells.getOrNull(it[0])?.getOrNull(it[1])?.fill } }

    /** A block of cells picked in the picked table (Table Layout ▸ Select ▾): rows [0]..[2], columns [1]..[3]; null: none. */
    var tableRange by mutableStateOf<IntArray?>(null)
        private set

    /** What a Table Design / Layout command works on: the picked block, else the cell typed in, else the whole table — grown to whole merged cells. */
    private fun targetRect(t: com.therealityteam.aeropages.model.SlideTable): IntArray =
        overMerges(t, tableRange?.copyOf() ?: typedCellOf(t)?.let { (r, c) -> intArrayOf(r, c, r, c) } ?: intArrayOf(0, 0, t.rows - 1, t.cols - 1))

    /** [rect] grown until no merged cell sticks out of it (PowerPoint picks a merged cell whole). */
    private fun overMerges(t: com.therealityteam.aeropages.model.SlideTable, rect: IntArray): IntArray {
        rect[0] = rect[0].coerceIn(0, t.rows - 1); rect[2] = rect[2].coerceIn(rect[0], t.rows - 1)
        rect[1] = rect[1].coerceIn(0, t.cols - 1); rect[3] = rect[3].coerceIn(rect[1], t.cols - 1)
        var grew = true
        while (grew) {
            grew = false
            for (r in 0 until t.rows) for (c in 0 until t.cols) {
                val (rs, cs) = t.spanOf(r, c)
                if (rs == 1 && cs == 1) continue
                val r2 = r + rs - 1; val c2 = c + cs - 1
                val meets = r <= rect[2] && r2 >= rect[0] && c <= rect[3] && c2 >= rect[1]
                if (!meets) continue
                if (r < rect[0]) { rect[0] = r; grew = true }; if (r2 > rect[2]) { rect[2] = r2; grew = true }
                if (c < rect[1]) { rect[1] = c; grew = true }; if (c2 > rect[3]) { rect[3] = c2; grew = true }
            }
        }
        return rect
    }

    /** The cells shown in [rect] (not the places a merged cell covers). */
    private fun cellsIn(t: com.therealityteam.aeropages.model.SlideTable, rect: IntArray): List<com.therealityteam.aeropages.model.SlideBox> {
        val cover = t.coverage()
        return (rect[0]..rect[2]).flatMap { r -> (rect[1]..rect[3]).filter { c -> !t.covered(cover, r, c) }.map { c -> t.cells[r][c] } }
    }

    /** Table Layout ▸ Select ▾'s choices. */
    enum class TableSelect { CELL, COLUMN, ROW, TABLE }

    /** Select Cell / Column / Row / Table: around the cell typed in (else the picked block's first, else the first). False with no table. */
    fun selectTableCells(kind: TableSelect): Boolean {
        val t = selectedTable ?: return false
        val (r, c) = tableRange?.let { it[0] to it[1] } ?: typedCellOf(t) ?: (0 to 0)
        tableRange = overMerges(t, when (kind) {
            TableSelect.CELL -> intArrayOf(r, c, r, c)
            TableSelect.COLUMN -> intArrayOf(0, c, t.rows - 1, c)
            TableSelect.ROW -> intArrayOf(r, 0, r, t.cols - 1)
            TableSelect.TABLE -> intArrayOf(0, 0, t.rows - 1, t.cols - 1)
        })
        bump()
        return true
    }

    /** Merge Cells is possible: a picked block shows more than one cell. */
    val canMergeTableCells: Boolean get() = selectedTable?.let { t -> tableRange != null && cellsIn(t, targetRect(t)).size > 1 } == true

    /**
     * Table Layout ▸ Merge Cells: the picked block becomes one cell — its first cell spanning all of it, the others' text
     * after its own, line by line (as PowerPoint keeps it), their places covered. One undo step; the merged cell is then typed in.
     */
    fun mergeTableCells(): Boolean {
        val t = selectedTable ?: return false
        if (!canMergeTableCells) return false
        val rect = targetRect(t)
        val cells = cellsIn(t, rect)
        pushUndo()
        val anchor = t.cells[rect[0]][rect[1]]
        for (b in cells) {
            if (b === anchor) continue
            if (b.text.isNotBlank()) {
                val runs = anchor.paragraph.runs
                if (anchor.text.isNotEmpty()) runs += com.therealityteam.aeropages.model.TextRun("\n", (runs.lastOrNull() ?: b.paragraph.runs.first()).format.copyOf())
                if (anchor.text.isBlank()) runs.clear()
                b.paragraph.runs.forEach { runs += com.therealityteam.aeropages.model.TextRun(it.text, it.format.copyOf()) }
                val keep = b.paragraph.runs.first().format.copyOf()
                b.paragraph.runs.clear(); b.paragraph.runs += com.therealityteam.aeropages.model.TextRun("", keep)
            }
            b.colSpan = 1; b.rowSpan = 1
        }
        anchor.colSpan = rect[3] - rect[1] + 1; anchor.rowSpan = rect[2] - rect[0] + 1
        tableRange = null
        focusedRef = anchor; cellTyped = true
        dirty = true; bump()
        return true
    }

    /** The cell being typed in — chosen by a click in it, not just the first cell a picked table focuses. */
    private fun typedCellOf(t: com.therealityteam.aeropages.model.SlideTable): Pair<Int, Int>? = if (cellTyped) focusedCellOf(t) else null

    /**
     * Table Design ▸ Shading: [color] (transparent for No Fill, null back to the table style's) on the cell being typed
     * in, else — the table picked — on every cell. One undo step; false when no table is picked.
     */
    fun setTableShading(color: Long?): Boolean {
        val t = selectedTable ?: return false
        pushUndo()
        cellsIn(t, targetRect(t)).forEach { it.fill = color }
        dirty = true; bump()
        return true
    }

    /** A table row or column at its smallest: PowerPoint keeps them at least this many points. */
    private val MIN_CELL_PT = 7.2f

    /** The cell being typed in (else the first) — the row and column Table Layout ▸ Cell Size works on. */
    private fun sizedCellOf(t: com.therealityteam.aeropages.model.SlideTable): Pair<Int, Int> = typedCellOf(t) ?: (0 to 0)

    /** Table Layout ▸ Cell Size: the height of the row being typed in, in points; null without a picked table. */
    val tableRowHeightPt: Float? get() = selectedTable?.let { t -> t.rowShare(sizedCellOf(t).first) * t.hPct * presentation.slideSize.heightPt }

    /** The width of the column being typed in, in points. */
    val tableColWidthPt: Float? get() = selectedTable?.let { t -> t.colShare(sizedCellOf(t).second) * t.wPct * presentation.slideSize.widthPt }

    /**
     * Table Layout ▸ Cell Size ▸ Height: the row being typed in (else the first) [pt] high, the other rows as they were —
     * the table grows or shrinks (within the slide). One undo step; false when it would not change.
     */
    fun setTableRowHeight(pt: Float): Boolean {
        val t = selectedTable ?: return false
        val slideH = presentation.slideSize.heightPt
        val heights = MutableList(t.rows) { t.rowShare(it) * t.hPct * slideH }
        val r = sizedCellOf(t).first
        val want = pt.coerceIn(MIN_CELL_PT, slideH)
        if (kotlin.math.abs(heights[r] - want) < 0.05f) return false
        pushUndo()
        heights[r] = want
        val total = heights.sum().coerceAtMost(slideH * (1f - t.yPct).coerceAtLeast(0.05f))
        t.rowWeights.clear(); t.rowWeights.addAll(heights)
        t.hPct = total / slideH
        dirty = true; bump()
        return true
    }

    /** Table Layout ▸ Cell Size ▸ Width: the column being typed in (else the first) [pt] wide, the others as they were. */
    fun setTableColWidth(pt: Float): Boolean {
        val t = selectedTable ?: return false
        val slideW = presentation.slideSize.widthPt
        val widths = MutableList(t.cols) { t.colShare(it) * t.wPct * slideW }
        val c = sizedCellOf(t).second
        val want = pt.coerceIn(MIN_CELL_PT, slideW)
        if (kotlin.math.abs(widths[c] - want) < 0.05f) return false
        pushUndo()
        widths[c] = want
        val total = widths.sum().coerceAtMost(slideW * (1f - t.xPct).coerceAtLeast(0.05f))
        t.colWeights.clear(); t.colWeights.addAll(widths)
        t.wPct = total / slideW
        dirty = true; bump()
        return true
    }

    /** Table Layout ▸ Distribute Rows / Distribute Columns: all alike in the table's height (width). One undo step; false when they already are. */
    fun distributeTable(rows: Boolean): Boolean {
        val t = selectedTable ?: return false
        val n = if (rows) t.rows else t.cols
        val alike = (0 until n).all { kotlin.math.abs((if (rows) t.rowShare(it) else t.colShare(it)) - 1f / n) < 0.0005f }
        if (alike) return false
        pushUndo()
        if (rows) t.rowWeights.clear() else t.colWeights.clear()
        dirty = true; bump()
        return true
    }

    /** Table Layout ▸ Alignment: where the text sits in the cell being typed in (else the first); null with no picked table. */
    val tableCellAnchor: com.therealityteam.aeropages.model.ShapeAnchor? get() = selectedTable?.let { t ->
        targetRect(t).let { t.cells.getOrNull(it[0])?.getOrNull(it[1])?.cellAnchor ?: com.therealityteam.aeropages.model.ShapeAnchor.CENTER }
    }

    /** Align Top / Center Vertically / Align Bottom: the cell being typed in, else every cell of the picked table. One undo step; false when nothing changes. */
    fun setTableCellAnchor(anchor: com.therealityteam.aeropages.model.ShapeAnchor): Boolean {
        val t = selectedTable ?: return false
        val want = anchor.takeUnless { it == com.therealityteam.aeropages.model.ShapeAnchor.CENTER }
        val cells = cellsIn(t, targetRect(t))
        if (cells.all { it.cellAnchor == want }) return false
        pushUndo()
        cells.forEach { it.cellAnchor = want }
        dirty = true; bump()
        return true
    }

    /** Borders ▾'s presets for a slide table (PowerPoint's Table Design ▸ Borders). */
    enum class TableBorders { NONE, ALL, OUTSIDE, INSIDE, TOP, BOTTOM, LEFT, RIGHT, INSIDE_H, INSIDE_V }

    /** Table Design ▸ Draw Borders: the line Borders ▾ draws (Pen Style, Pen Weight, Pen Color). */
    var tablePen by mutableStateOf(com.therealityteam.aeropages.model.CellBorder(com.therealityteam.aeropages.model.LineStyle.THIN, 1f, 0xFF000000))

    /**
     * Borders ▾: [preset]'s edges drawn with [tablePen] — on the cell being typed in (its neighbours' facing sides
     * with it), else on the whole picked table. A one-edge preset whose edges all have the pen already takes them
     * away, as PowerPoint toggles them. One undo step; false when nothing would change.
     */
    fun applyTableBorders(preset: TableBorders): Boolean {
        val t = selectedTable ?: return false
        val look = com.therealityteam.aeropages.model.SlideTableLook
        val side = com.therealityteam.aeropages.model.SlideTableLook.Side.entries   // TOP, BOTTOM, LEFT, RIGHT
        // The picked block, else the cell typed in — all of it when it is a merged one — else the whole table.
        val rect = targetRect(t)
        val r1 = rect[0]; val c1 = rect[1]; val r2 = rect[2]; val c2 = rect[3]
        val edges = mutableListOf<Triple<Int, Int, com.therealityteam.aeropages.model.SlideTableLook.Side>>()
        fun top() { for (c in c1..c2) edges += Triple(r1, c, side[0]) }
        fun bottom() { for (c in c1..c2) edges += Triple(r2, c, side[1]) }
        fun left() { for (r in r1..r2) edges += Triple(r, c1, side[2]) }
        fun right() { for (r in r1..r2) edges += Triple(r, c2, side[3]) }
        fun insideH() { for (r in r1 until r2) for (c in c1..c2) edges += Triple(r, c, side[1]) }
        fun insideV() { for (r in r1..r2) for (c in c1 until c2) edges += Triple(r, c, side[3]) }
        when (preset) {
            TableBorders.TOP -> top(); TableBorders.BOTTOM -> bottom(); TableBorders.LEFT -> left(); TableBorders.RIGHT -> right()
            TableBorders.INSIDE_H -> insideH(); TableBorders.INSIDE_V -> insideV()
            TableBorders.OUTSIDE -> { top(); bottom(); left(); right() }
            TableBorders.INSIDE -> { insideH(); insideV() }
            TableBorders.ALL, TableBorders.NONE -> { top(); bottom(); left(); right(); insideH(); insideV() }
        }
        // No edge inside a merged cell: both its sides are the same cell.
        val cover = look.coverOf(t)
        edges.retainAll { (r, c, side) ->
            val (nr, nc, _) = look.across(r, c, side)
            nr !in 0 until t.rows || nc !in 0 until t.cols || look.ownerOf(t, r, c, cover) != look.ownerOf(t, nr, nc, cover)
        }
        if (edges.isEmpty()) return false
        val pen = tablePen
        val toggles = preset in setOf(TableBorders.TOP, TableBorders.BOTTOM, TableBorders.LEFT, TableBorders.RIGHT, TableBorders.INSIDE_H, TableBorders.INSIDE_V)
        val drawn: com.therealityteam.aeropages.model.CellBorder? = when {
            preset == TableBorders.NONE -> null
            toggles && edges.all { (r, c, s) -> look.line(t, r, c, s) == pen } -> null
            else -> pen
        }
        if (edges.all { (r, c, s) -> look.line(t, r, c, s) == drawn }) return false
        pushUndo()
        for ((r, c, s) in edges) setTableEdge(t, r, c, s, drawn)
        dirty = true; bump()
        return true
    }

    /** Cell ([r], [c])'s side [s] and its neighbour's facing one drawn [line]: each takes its own borders (as they drew) first. */
    private fun setTableEdge(t: com.therealityteam.aeropages.model.SlideTable, r: Int, c: Int, s: com.therealityteam.aeropages.model.SlideTableLook.Side,
                             line: com.therealityteam.aeropages.model.CellBorder?) {
        val look = com.therealityteam.aeropages.model.SlideTableLook
        val cover = look.coverOf(t)
        // A place a merged cell covers: its edge is the merged cell's.
        fun own(rr: Int, cc: Int, side: com.therealityteam.aeropages.model.SlideTableLook.Side) {
            if (rr !in 0 until t.rows || cc !in 0 until t.cols) return
            val (or, oc) = look.ownerOf(t, rr, cc, cover)
            val cell = t.cells.getOrNull(or)?.getOrNull(oc) ?: return
            val b = cell.borders ?: look.sides(t, or, oc, cover)
            cell.borders = with(look) { b.with(side, line) }
        }
        val (nr, nc, facing) = look.across(r, c, s)
        // The neighbour first: its sides as they draw still read the cell's old line on the edge they share.
        own(nr, nc, facing)
        own(r, c, s)
    }

    /** The selected table's cell being typed in, as (row, column), or null. */
    private fun focusedCellOf(t: com.therealityteam.aeropages.model.SlideTable): Pair<Int, Int>? {
        val f = focusedRef ?: return null
        t.cells.forEachIndexed { r, row -> val c = row.indexOfFirst { it === f }; if (c >= 0) return r to c }
        return null
    }

    /** A new cell set as [like] is: its size, colour and font, its shading and borders (as PowerPoint copies the row's look), empty. */
    private fun cellLike(like: com.therealityteam.aeropages.model.SlideBox?, bold: Boolean): com.therealityteam.aeropages.model.SlideBox {
        val f = like?.paragraph?.runs?.firstOrNull()?.format?.copyOf()?.also { it.bold = bold } ?: CharacterFormat(fontSizePt = 16f, bold = bold)
        return com.therealityteam.aeropages.model.SlideBox("", f, like?.align ?: TextAlign.LEFT).also { it.fill = like?.fill; it.borders = like?.borders; it.cellAnchor = like?.cellAnchor }
    }

    /** PowerPoint's Insert Above / Below: a row beside the cell being typed in (without one, at the table's top or bottom). */
    fun addTableRow(below: Boolean = true) {
        selectedTable?.let { t ->
            if (t.rows >= 20) return
            pushUndo()
            val at = focusedCellOf(t)?.let { (r, _) -> if (below) r + 1 else r } ?: if (below) t.rows else 0
            val like = t.cells.getOrNull(if (below) at - 1 else at) ?: t.cells.lastOrNull()
            growMerges(t, rows = true, at)
            t.cells.add(at, MutableList(t.cols) { c -> cellLike(like?.getOrNull(c), bold = false) })
            if (t.rowWeights.size == t.rows - 1) t.rowWeights.add(at, t.rowWeights.getOrElse(if (below) at - 1 else at) { t.rowWeights.lastOrNull() ?: 1f })
            dirty = true; bump()
        }
    }

    /** A row (column) is put in at [at]: a merged cell it lands inside grows over it, as PowerPoint's does. */
    private fun growMerges(t: com.therealityteam.aeropages.model.SlideTable, rows: Boolean, at: Int) {
        for (r in 0 until t.rows) for (c in 0 until t.cols) {
            val (rs, cs) = t.spanOf(r, c)
            val b = t.cells[r][c]
            if (rows && rs > 1 && at in r + 1 until r + rs) b.rowSpan = rs + 1
            if (!rows && cs > 1 && at in c + 1 until c + cs) b.colSpan = cs + 1
        }
    }

    /** Row (column) [gone] is about to go: a merged cell across it shrinks; one starting in it moves its content to the next. */
    private fun shrinkMerges(t: com.therealityteam.aeropages.model.SlideTable, rows: Boolean, gone: Int) {
        for (r in 0 until t.rows) for (c in 0 until t.cols) {
            val (rs, cs) = t.spanOf(r, c)
            val b = t.cells[r][c]
            if (rows && rs > 1) when {
                gone in r + 1 until r + rs -> b.rowSpan = rs - 1
                gone == r -> { t.cells[r + 1][c] = b; b.rowSpan = rs - 1 }
            }
            if (!rows && cs > 1) when {
                gone in c + 1 until c + cs -> b.colSpan = cs - 1
                gone == c -> { t.cells[r][c + 1] = b; b.colSpan = cs - 1 }
            }
        }
    }

    /** Table Layout ▸ Split Cells is possible: the cell being typed in is a merged one. */
    val canSplitTableCell: Boolean get() = selectedTable?.let { t -> typedCellOf(t)?.let { (r, c) -> t.spanOf(r, c) != (1 to 1) } } == true

    /** Split Cells: the merged cell being typed in becomes its places again (their own cells, as they were). One undo step. */
    fun splitTableCell(): Boolean {
        val t = selectedTable ?: return false
        val (r, c) = typedCellOf(t) ?: return false
        if (t.spanOf(r, c) == (1 to 1)) return false
        pushUndo()
        t.cells[r][c].let { it.colSpan = 1; it.rowSpan = 1 }
        dirty = true; bump()
        return true
    }

    /** Deletes the row of the cell being typed in (without one, the last row). */
    fun deleteTableRow() {
        selectedTable?.let { t ->
            if (t.rows <= 1) return
            pushUndo()
            val at = focusedCellOf(t)
            val gone = at?.first ?: (t.rows - 1)
            if (t.rowWeights.size == t.rows) t.rowWeights.removeAt(gone)
            shrinkMerges(t, rows = true, gone)
            t.cells.removeAt(gone)
            if (at != null) focusedRef = t.cells.getOrNull(at.first.coerceAtMost(t.rows - 1))?.getOrNull(at.second)
            dirty = true; bump()
        }
    }

    /** PowerPoint's Insert Left / Right: a column beside the cell being typed in (without one, at the table's right). */
    fun addTableColumn(left: Boolean = false) {
        selectedTable?.let { t ->
            if (t.cols >= 20) return
            pushUndo()
            val at = focusedCellOf(t)?.let { (_, c) -> if (left) c else c + 1 } ?: t.cols
            val weighted = t.colWeights.size == t.cols
            growMerges(t, rows = false, at)
            t.cells.forEachIndexed { r, row -> row.add(at, cellLike(row.getOrNull(if (left) at else at - 1), bold = r == 0 && t.headerRow)) }
            if (weighted) t.colWeights.add(at, t.colWeights.getOrElse(if (left) at else at - 1) { t.colWeights.lastOrNull() ?: 1f })
            dirty = true; bump()
        }
    }

    /** Deletes the column of the cell being typed in (without one, the last column). */
    fun deleteTableColumn() {
        selectedTable?.let { t ->
            if (t.cols <= 1) return
            pushUndo()
            val at = focusedCellOf(t)
            val c = at?.second ?: (t.cols - 1)
            if (t.colWeights.size == t.cols) t.colWeights.removeAt(c)
            shrinkMerges(t, rows = false, c)
            t.cells.forEach { it.removeAt(c) }
            if (at != null) focusedRef = t.cells.getOrNull(at.first)?.getOrNull(c.coerceAtMost(t.cols - 1))
            dirty = true; bump()
        }
    }

    // --- charts ----------------------------------------------------------------
    fun addChart(type: com.therealityteam.aeropages.model.ChartType) {
        pushUndo()
        val c = com.therealityteam.aeropages.model.SlideChart.sample(type)
        // In a free content placeholder it takes the placeholder's place and size, as PowerPoint's chart does.
        if (slide.contentFree) slide.contentFrame.let { f -> c.xPct = f.xPct; c.yPct = f.yPct; c.wPct = f.wPct; c.hPct = f.hPct }
        slide.charts.add(c); selectChart(c); dirty = true; bump()
    }
    fun selectChart(c: com.therealityteam.aeropages.model.SlideChart?) {
        selectedChart = c; if (c != null) { selectedShape = null; selectedTable = null; selectedImage = null; focusedRef = null }
        bump()
    }
    fun deleteSelectedChart() { selectedChart?.let { pushUndo(); slide.charts.remove(it); selectedChart = null; dirty = true; bump() } }
    fun setChartType(type: com.therealityteam.aeropages.model.ChartType) { selectedChart?.let { pushUndo(); it.type = type; dirty = true; bump() } }
    fun setChartColorSet(i: Int) { selectedChart?.let { pushUndo(); it.colorSet = i; dirty = true; bump() } }
    fun setChartTitle(t: String) { selectedChart?.let { pushUndo("chartTitle"); it.title = t; dirty = true; bump() } }

    /** Sets the selected chart's title, axis titles and legend (Office's Chart Elements) as one undo step. */
    fun setChartElements(
        title: String, catAxisTitle: String, valAxisTitle: String,
        showLegend: Boolean, legendPos: com.therealityteam.aeropages.model.LegendPos,
        axis: com.therealityteam.aeropages.model.AxisFormat? = null,
    ) {
        val c = selectedChart ?: return
        val newAxis = axis ?: c.axisFormat
        if (c.title == title && c.catAxisTitle == catAxisTitle && c.valAxisTitle == valAxisTitle &&
            c.showLegend == showLegend && c.legendPos == legendPos && c.axisFormat == newAxis) return
        pushUndo()
        c.title = title; c.catAxisTitle = catAxisTitle; c.valAxisTitle = valAxisTitle
        c.showLegend = showLegend; c.legendPos = legendPos
        c.axisFormat = newAxis
        dirty = true; bump()
    }
    fun toggleChartLegend() { selectedChart?.let { pushUndo(); it.showLegend = !it.showLegend; dirty = true; bump() } }
    fun toggleChartGrid() { selectedChart?.let { pushUndo(); it.showGrid = !it.showGrid; dirty = true; bump() } }
    fun toggleChartValues() { selectedChart?.let { pushUndo(); it.showValues = !it.showValues; dirty = true; bump() } }
    /** Swap series<->categories (PowerPoint 切换行/列). */
    fun switchChartRowColumn() {
        selectedChart?.let { c -> pushUndo(); c.switchRowColumn(); dirty = true; bump() }
    }
    /** A change to the selected chart's data from Edit Data's grid (names, values, an added category or series). */
    fun editSelectedChart(change: (com.therealityteam.aeropages.model.SlideChart) -> Unit) {
        selectedChart?.let { c -> pushUndo("chartData"); change(c); dirty = true; bump() }
    }

    fun setChartCell(seriesIndex: Int, categoryIndex: Int, value: Float) {
        selectedChart?.let { c ->
            pushUndo("chartData")
            c.series.getOrNull(seriesIndex)?.values?.let { if (categoryIndex in it.indices) { it[categoryIndex] = value; dirty = true; bump() } }
        }
    }

    // --- animations (动画) ------------------------------------------------------
    /** The effect picked in the Animation Pane (the timing controls act on it); cleared with the slide's selection. */
    var selectedAnim by mutableStateOf<ElementAnim?>(null)
        private set

    /** The element the Animations tab acts on: the selected chart, table, picture or shape, else the box being edited, else the body. */
    val animTarget: AnimTarget
        get() = selectedChart ?: selectedTable ?: selectedImage ?: selectedShape?.box ?: focusedRef ?: slide.body

    /** The effect the ribbon's timing controls act on: the one picked in the pane when it is the target's, else the target's first. */
    val currentEffect: ElementAnim?
        get() = animTarget.anims.let { own -> selectedAnim?.takeIf { a -> own.any { it === a } } ?: own.firstOrNull() }

    /** The current slide's effects in list order (the Animation Pane). */
    val animationList: List<AnimTimeline.Entry> get() = AnimTimeline.entriesOf(slide)

    private fun nextOrder(): Int = (animationList.maxOfOrNull { it.anim.order } ?: -1) + 1

    /** An animation edit: one undo step, the slide's list renumbered after it. */
    private inline fun animEdit(block: () -> Unit) {
        pushUndo()
        block()
        AnimTimeline.normalize(slide)
        dirty = true; bump()
    }

    /**
     * A gallery pick: [effect] replaces the target's current effect (the one picked in the pane, else
     * its first), keeping its timing; a target without effects gets it as a new On Click entry.
     * Null (None) removes the target's effects.
     */
    fun applyAnimation(effect: AnimEffect?) {
        val target = animTarget
        animEdit {
            if (effect == null) { target.anims.clear(); selectedAnim = null; return@animEdit }
            val cur = currentEffect
            if (cur == null) target.anims.add(newEffect(target, effect).also { selectedAnim = it })
            else cur.effect = effect
        }
    }

    /** Add Animation: [effect] as a further effect of the target, at the end of the slide's list. */
    fun addAnimation(effect: AnimEffect) {
        val target = animTarget
        animEdit { target.anims.add(newEffect(target, effect).also { selectedAnim = it }) }
    }

    /** A new effect for [target], last in the list; the body placeholder's text builds by paragraph, as PowerPoint's does. */
    private fun newEffect(target: AnimTarget, effect: AnimEffect): ElementAnim =
        ElementAnim(effect, order = nextOrder(), byParagraph = target === slide.body && AnimTimeline.paragraphsOf(target).size >= 2)

    /** Whether the Animations tab's target is text of two or more paragraphs, which Effect Options can build by paragraph. */
    val canBuildByParagraph: Boolean get() = AnimTimeline.paragraphsOf(animTarget).size >= 2

    /** Effect Options: the current effect plays the target's paragraphs one by one, or the target as one object. */
    fun setAnimByParagraph(on: Boolean) { val a = currentEffect ?: return; if (a.byParagraph != on) animEdit { a.byParagraph = on } }

    fun removeAnimation(anim: ElementAnim) {
        val owner = AnimTimeline.targets(slide).firstOrNull { t -> t.anims.any { it === anim } } ?: return
        animEdit { owner.anims.removeAll { it === anim }; if (selectedAnim === anim) selectedAnim = null }
    }

    /** Move Earlier / Move Later: [anim] moves [delta] places in the slide's list (stopping at its ends). */
    fun moveAnimation(anim: ElementAnim, delta: Int) {
        val list = animationList.map { it.anim }.toMutableList()
        val i = list.indexOfFirst { it === anim }
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i < 0 || j == i) return
        animEdit { list.add(j, list.removeAt(i)); list.forEachIndexed { k, a -> a.order = k } }
    }

    fun setAnimStart(start: AnimStart) { val a = currentEffect ?: return; if (a.start != start) animEdit { a.start = start } }

    fun setAnimDuration(ms: Int) {
        val a = currentEffect ?: return
        val v = ms.coerceIn(ElementAnim.MIN_MS, ElementAnim.MAX_MS)
        if (a.durationMs != v) animEdit { a.durationMs = v }
    }

    fun setAnimDelay(ms: Int) {
        val a = currentEffect ?: return
        val v = ms.coerceIn(0, ElementAnim.MAX_MS)
        if (a.delayMs != v) animEdit { a.delayMs = v }
    }

    /** Effect Options: the direction of a fly, float or wipe. */
    fun setAnimDirection(dir: AnimDir) { val a = currentEffect ?: return; if (a.dir != dir) animEdit { a.dir = dir } }

    /** Pick [anim] in the Animation Pane: it becomes the current effect and its element the selection. */
    fun selectAnim(anim: ElementAnim?) {
        val owner = anim?.let { a -> AnimTimeline.targets(slide).firstOrNull { t -> t.anims.any { it === a } } }
        if (anim != null && owner != null) {
            when (owner) {
                is com.therealityteam.aeropages.model.SlideImage -> selectImage(owner)
                is com.therealityteam.aeropages.model.SlideTable -> selectTable(owner)
                is com.therealityteam.aeropages.model.SlideChart -> selectChart(owner)
                is SlideBox -> slide.shapes.firstOrNull { it.box === owner }?.let { selectShape(it) }
                    ?: run { clearSelection(); focus(owner) }
            }
        }
        selectedAnim = anim?.takeIf { owner != null }
        bump()
    }

    /**
     * One by one: every element still without an effect gets an Appear On Click, in reading order
     * (the title stays put, as it shows with the slide), so a click brings each in turn.
     */
    fun animateOneByOne() {
        val targets = AnimTimeline.targets(slide).filter { t ->
            t.anims.isEmpty() && t !== slide.title && when (t) {
                slide.body -> slide.body.text.isNotBlank() && (slide.body.frame != null || (slide.layout != SlideLayout.TITLE && slide.layout != SlideLayout.BLANK))
                else -> true
            }
        }
        if (targets.isEmpty()) return
        // The body's bullets come one per click, as its items should.
        animEdit { var o = nextOrder(); targets.forEach { it.anims.add(ElementAnim(AnimEffect.APPEAR, order = o++, byParagraph = it === slide.body)) } }
    }

    // --- object clipboard: pictures, shapes, charts and tables, shared with the other modules ------
    val canPasteObject: Boolean get() = ObjectClipboard.item != null
    private fun selectedObject(): Any? = selectedChart ?: selectedTable ?: selectedImage ?: selectedShape
    val hasObjectSelected: Boolean get() = selectedObject() != null

    /** The picked object as a clipboard item — a copy, its size in points, where it sits — or null. */
    private fun itemOfSelected(): ObjectClipboard.Item? {
        val w = presentation.slideSize.widthPt; val h = presentation.slideSize.heightPt
        val (item, place) = when (val o = selectedObject()) {
            is SlideShape -> ObjectClipboard.Shape(o.copyOf(), o.wPct * w, o.hPct * h) to (o.xPct to o.yPct)
            is com.therealityteam.aeropages.model.SlideTable -> ObjectClipboard.Table(o.copyOf(), o.wPct * w, o.hPct * h) to (o.xPct to o.yPct)
            is com.therealityteam.aeropages.model.SlideChart -> ObjectClipboard.Chart(o.copyOf(), o.wPct * w, o.hPct * h) to (o.xPct to o.yPct)
            is com.therealityteam.aeropages.model.SlideImage -> ObjectClipboard.Picture(o.data, o.mime, o.wPct * w, o.hPct * h, o.cropL, o.cropT, o.cropR, o.cropB) to (o.xPct to o.yPct)
            else -> return null
        }
        item.slidePlace = place
        return item
    }

    /** Copy the picked object for any module's paste; the words it carries (for other apps), or null when none is picked. */
    fun copySelectedObject(): String? {
        val item = itemOfSelected() ?: return null
        ObjectClipboard.put(item)
        bump()
        return ObjectClipboard.textOf(item)
    }

    fun pasteObject() { ObjectClipboard.item?.let { pasteItem(it) } }

    /**
     * Paste [item] on the slide, picked: beside where it sat when it came from a slide, else centred
     * at the size it was shown at (shrunk to fit), as PowerPoint pastes from Excel or Word.
     */
    fun pasteItem(item: ObjectClipboard.Item) {
        val w = presentation.slideSize.widthPt; val h = presentation.slideSize.heightPt
        var wPct = (item.widthPt / w).coerceAtLeast(0.01f); var hPct = (item.heightPt / h).coerceAtLeast(0.01f)
        val fit = minOf(1f, 0.9f / wPct, 0.9f / hPct)
        wPct *= fit; hPct *= fit
        val (x, y) = item.slidePlace?.let { offsetEl(it.first, it.second) } ?: (((1f - wPct) / 2f) to ((1f - hPct) / 2f))
        pushUndo()
        when (item) {
            is ObjectClipboard.Shape -> { val c = item.shape.copyOf(); c.xPct = x; c.yPct = y; c.wPct = wPct; c.hPct = hPct; animsToEnd(c.box); slide.shapes.add(c); selectShape(c) }
            is ObjectClipboard.Table -> { val c = item.table.copyOf(); c.xPct = x; c.yPct = y; c.wPct = wPct; c.hPct = hPct; animsToEnd(c); slide.tables.add(c); selectTable(c) }
            is ObjectClipboard.Chart -> { val c = item.chart.copyOf(); c.sourceRef = null; c.xPct = x; c.yPct = y; c.wPct = wPct; c.hPct = hPct; animsToEnd(c); slide.charts.add(c); selectChart(c) }
            is ObjectClipboard.Picture -> {
                // The picture's own size (its aspect) from its pixels, else from how it was shown and cropped.
                val natural = com.therealityteam.aeropages.format.ImageSize.naturalPt(item.data)
                    ?: (item.widthPt / (1f - item.cropL - item.cropR).coerceAtLeast(0.01f) to item.heightPt / (1f - item.cropT - item.cropB).coerceAtLeast(0.01f))
                val c = com.therealityteam.aeropages.model.SlideImage(item.data, item.mime, natural.first, natural.second, x, y, wPct, hPct,
                    cropL = item.cropL, cropT = item.cropT, cropR = item.cropR, cropB = item.cropB)
                slide.images.add(c); selectImage(c)
            }
        }
        dirty = true; bump()
    }

    /** A pasted or duplicated element's effects join the end of the slide's list, as PowerPoint puts them. */
    private fun animsToEnd(t: AnimTarget) {
        var o = nextOrder()
        t.anims.sortedBy { it.order }.forEach { it.order = o++ }
    }

    private fun offsetEl(x: Float, y: Float): Pair<Float, Float> =
        ((x + 0.03f).coerceIn(0f, 0.92f)) to ((y + 0.03f).coerceIn(0f, 0.92f))
    /** `coerceIn` that tolerates an inverted range (elements wider than the slide in foreign decks). */
    private fun Float.within(lo: Float, hi: Float): Float = coerceIn(lo, maxOf(lo, hi))

    /** Cut the picked object: copied as [copySelectedObject] copies it, then removed. */
    fun cutSelectedObject(): String? {
        val text = copySelectedObject() ?: return null
        when (selectedObject()) {
            is SlideShape -> deleteSelectedShape()
            is com.therealityteam.aeropages.model.SlideTable -> deleteSelectedTable()
            is com.therealityteam.aeropages.model.SlideChart -> deleteSelectedChart()
            is com.therealityteam.aeropages.model.SlideImage -> deleteSelectedImage()
            else -> {}
        }
        return text
    }

    /** A copy of the picked object beside it (the clipboard is left as it was). */
    fun duplicateSelectedObject() { itemOfSelected()?.let { pasteItem(it) } }

    /** The picked object removed (nothing copied: the selection bar's Delete, the Delete key's). */
    fun deleteSelectedObject() {
        when (selectedObject()) {
            is SlideShape -> deleteSelectedShape()
            is com.therealityteam.aeropages.model.SlideTable -> deleteSelectedTable()
            is com.therealityteam.aeropages.model.SlideChart -> deleteSelectedChart()
            is com.therealityteam.aeropages.model.SlideImage -> deleteSelectedImage()
            else -> {}
        }
    }

    /** The picked object's frame in slide fractions — x, y, width, height — or null with nothing picked. */
    fun selectedFrame(): FloatArray? = when (val o = selectedObject()) {
        is SlideShape -> floatArrayOf(o.xPct, o.yPct, o.wPct, o.hPct)
        is com.therealityteam.aeropages.model.SlideTable -> floatArrayOf(o.xPct, o.yPct, o.wPct, o.hPct)
        is com.therealityteam.aeropages.model.SlideChart -> floatArrayOf(o.xPct, o.yPct, o.wPct, o.hPct)
        is com.therealityteam.aeropages.model.SlideImage -> floatArrayOf(o.xPct, o.yPct, o.wPct, o.hPct)
        else -> null
    }

    /**
     * The picked object made [wPct] wide or [hPct] tall (slide fractions; null leaves that side) — the Size group's
     * Height and Width. A picture keeps its proportions, as PowerPoint locks a picture's aspect ratio; the others
     * change only that side. Presses in a row are one undo step.
     */
    fun setSelectedSize(wPct: Float?, hPct: Float?) {
        val f = selectedFrame() ?: return
        resizeSelected((wPct ?: f[2]) - f[2], (hPct ?: f[3]) - f[3])
    }

    /** The selected shape's text all in [color] (WordArt Styles' Text Fill). */
    fun setShapeTextColor(color: Long?) {
        selectedShape?.let { s ->
            pushUndo()
            ParagraphOps.applyFormat(s.box.paragraph, 0, ParagraphOps.length(s.box.paragraph).coerceAtLeast(1)) { f -> f.color = color }
            dirty = true; bump()
        }
    }

    // --- shape format (形状格式) ------------------------------------------------
    fun toggleFlipH() { selectedShape?.let { pushUndo(); it.flipH = !it.flipH; dirty = true; bump() } }
    fun toggleFlipV() { selectedShape?.let { pushUndo(); it.flipV = !it.flipV; dirty = true; bump() } }

    /** Picture Format → Rotate: the selected picture turned [delta] degrees clockwise (kept within ±180°). */
    fun rotateSelectedImage(delta: Float) {
        val img = selectedImage ?: return
        pushUndo(); img.rotationDeg = normalAngle(img.rotationDeg + delta); dirty = true; bump()
    }
    /** Picture Format → Rotate → Flip Horizontal / Flip Vertical: the selected picture mirrored. */
    fun toggleImageFlipH() { selectedImage?.let { pushUndo(); it.flipH = !it.flipH; dirty = true; bump() } }
    fun toggleImageFlipV() { selectedImage?.let { pushUndo(); it.flipV = !it.flipV; dirty = true; bump() } }

    /** [deg] within ±180° (a turn back anticlockwise negative, as the files keep one), a whole 0 as 0. */
    private fun normalAngle(deg: Float): Float {
        var d = ((deg % 360f) + 360f) % 360f
        if (d > 180f) d -= 360f
        return if (kotlin.math.abs(d) < 0.01f) 0f else d
    }
    /** The selected shapes' outline weight, in points (¼–12). */
    fun setShapeBorderWidth(pt: Float) { val all = selectedShapes; if (all.isNotEmpty()) { pushUndo(); all.forEach { it.borderWidthPt = pt.coerceIn(0.25f, 12f) }; dirty = true; bump() } }
    /** The selected shapes' outline dashes (a line's too). */
    fun setShapeDash(dash: com.therealityteam.aeropages.model.LineDash) { val all = selectedShapes; if (all.isNotEmpty()) { pushUndo(); all.forEach { it.dash = dash }; dirty = true; bump() } }
    /** The selected lines' arrowheads at their [begin] and [end] (a closed shape has no ends). */
    fun setShapeArrows(begin: com.therealityteam.aeropages.model.LineEnd, end: com.therealityteam.aeropages.model.LineEnd) {
        val lines = selectedShapes.filter { it.hasEnds }
        if (lines.isEmpty()) return
        pushUndo(); lines.forEach { it.beginArrow = begin; it.endArrow = end }; dirty = true; bump()
    }
    fun rotateSelectedShape(delta: Float) { selectedShape?.let { pushUndo("rotateShape"); it.rotationDeg = (it.rotationDeg + delta) % 360f; dirty = true; bump() } }
    fun setShapeRotation(deg: Float) { selectedShape?.let { pushUndo(); it.rotationDeg = deg % 360f; dirty = true; bump() } }
    /** Align the selected shape within the slide. dir: L/C/R/T/M/B. */
    fun alignShape(dir: Char) {
        // Several shapes: align them to the selection's bounding box (PowerPoint's "align selected objects").
        val all = selectedShapes
        if (all.size > 1) {
            pushUndo()
            val l = all.minOf { it.xPct }; val r = all.maxOf { it.xPct + it.wPct }; val t = all.minOf { it.yPct }; val b = all.maxOf { it.yPct + it.hPct }
            all.forEach { s ->
                when (dir) {
                    'L' -> s.xPct = l; 'C' -> s.xPct = ((l + r) / 2f - s.wPct / 2f).within(0f, 1f - s.wPct); 'R' -> s.xPct = (r - s.wPct).coerceAtLeast(0f)
                    'T' -> s.yPct = t; 'M' -> s.yPct = ((t + b) / 2f - s.hPct / 2f).within(0f, 1f - s.hPct); 'B' -> s.yPct = (b - s.hPct).coerceAtLeast(0f)
                }
            }
            dirty = true; bump(); return
        }
        selectedImage?.let { img ->
            pushUndo()
            when (dir) {
                'L' -> img.xPct = 0f
                'C' -> img.xPct = ((1f - img.wPct) / 2f).coerceAtLeast(0f)
                'R' -> img.xPct = (1f - img.wPct).coerceAtLeast(0f)
                'T' -> img.yPct = 0f
                'M' -> img.yPct = ((1f - img.hPct) / 2f).coerceAtLeast(0f)
                'B' -> img.yPct = (1f - img.hPct).coerceAtLeast(0f)
            }
            dirty = true; bump(); return
        }
        selectedShape?.let {
            pushUndo()
            when (dir) {
                'L' -> it.xPct = 0f
                'C' -> it.xPct = ((1f - it.wPct) / 2f).coerceAtLeast(0f)
                'R' -> it.xPct = (1f - it.wPct).coerceAtLeast(0f)
                'T' -> it.yPct = 0f
                'M' -> it.yPct = ((1f - it.hPct) / 2f).coerceAtLeast(0f)
                'B' -> it.yPct = (1f - it.hPct).coerceAtLeast(0f)
            }
            dirty = true; bump()
        }
    }
    /** PowerPoint shape style preset: fill + border in one click. */
    fun applyShapeStyle(fill: Long?, border: Long?, textColor: Long?) {
        selectedShape?.let {
            pushUndo()
            it.fill = fill; it.border = border
            if (textColor != null) ParagraphOps.applyFormat(it.box.paragraph, 0, ParagraphOps.length(it.box.paragraph).coerceAtLeast(1)) { f -> f.color = textColor }
            dirty = true; bump()
        }
    }

    // --- generic move/resize (whichever element is selected) -------------------
    private fun moveElement(get: () -> FloatArray, set: (Float, Float) -> Unit, dx: Float, dy: Float) {
        val a = get(); set((a[0] + dx).coerceIn(0f, 1f - a[2]), (a[1] + dy).coerceIn(0f, 1f - a[3]))
    }
    /** Two-hand transform of the selected shape: pan, scale about its centre and rotate (one undo step per gesture). */
    fun transformSelectedShape(dxPct: Float, dyPct: Float, zoom: Float, rotationDeg: Float) {
        val s = selectedShape ?: return
        pushUndo("transformEl")
        val z = zoom.coerceIn(0.5f, 2f)
        val cx = s.xPct + s.wPct / 2f; val cy = s.yPct + s.hPct / 2f
        val line = s.type == ShapeType.LINE
        s.wPct = (s.wPct * z).coerceIn(if (line) 0f else 0.04f, 1f); s.hPct = (s.hPct * z).coerceIn(if (line) 0f else 0.03f, 1f)
        s.xPct = (cx - s.wPct / 2f + dxPct).within(0f, 1f - s.wPct); s.yPct = (cy - s.hPct / 2f + dyPct).within(0f, 1f - s.hPct)
        s.rotationDeg = ((s.rotationDeg + rotationDeg) % 360f + 360f) % 360f
        dirty = true; bump()
    }
    /** Two-hand pan + scale (about the centre) of whichever element is selected, and a picture's turn; tables and charts do not rotate. */
    fun transformSelected(dxPct: Float, dyPct: Float, zoom: Float, rotationDeg: Float = 0f) {
        if (selectedShape != null) { transformSelectedShape(dxPct, dyPct, zoom, 0f); return }
        val z = zoom.coerceIn(0.5f, 2f)
        fun scale(x: Float, y: Float, w: Float, h: Float, minW: Float, minH: Float, set: (Float, Float, Float, Float) -> Unit) {
            val cx = x + w / 2f; val cy = y + h / 2f
            val nw = (w * z).coerceIn(minW, 1f); val nh = (h * z).coerceIn(minH, 1f)
            set((cx - nw / 2f + dxPct).within(0f, 1f - nw), (cy - nh / 2f + dyPct).within(0f, 1f - nh), nw, nh)
        }
        selectedImage?.let { i ->
            pushUndo("transformEl")
            val f = pictureFactor(i.wPct, i.hPct, z, 1f, 1f)
            val cx = i.xPct + i.wPct / 2f; val cy = i.yPct + i.hPct / 2f
            i.wPct *= f; i.hPct *= f
            i.xPct = (cx - i.wPct / 2f + dxPct).within(0f, 1f - i.wPct); i.yPct = (cy - i.hPct / 2f + dyPct).within(0f, 1f - i.hPct)
            if (rotationDeg != 0f) i.rotationDeg = normalAngle(i.rotationDeg + rotationDeg)
            dirty = true; bump(); return
        }
        selectedTable?.let { t -> pushUndo("transformEl"); scale(t.xPct, t.yPct, t.wPct, t.hPct, 0.1f, 0.08f) { x, y, w, h -> t.xPct = x; t.yPct = y; t.wPct = w; t.hPct = h }; dirty = true; bump(); return }
        selectedChart?.let { c -> pushUndo("transformEl"); scale(c.xPct, c.yPct, c.wPct, c.hPct, 0.1f, 0.08f) { x, y, w, h -> c.xPct = x; c.yPct = y; c.wPct = w; c.hPct = h }; dirty = true; bump() }
    }

    fun moveSelected(dx: Float, dy: Float) {
        selectedImage?.let { pushUndo("moveEl"); it.xPct = (it.xPct + dx).within(0f, 1f - it.wPct); it.yPct = (it.yPct + dy).within(0f, 1f - it.hPct); dirty = true; bump(); return }
        selectedTable?.let { pushUndo("moveEl"); it.xPct = (it.xPct + dx).within(0f, 1f - it.wPct); it.yPct = (it.yPct + dy).within(0f, 1f - it.hPct); dirty = true; bump(); return }
        selectedChart?.let { pushUndo("moveEl"); it.xPct = (it.xPct + dx).within(0f, 1f - it.wPct); it.yPct = (it.yPct + dy).within(0f, 1f - it.hPct); dirty = true; bump(); return }
        moveSelectedShape(dx, dy)
    }
    fun resizeSelected(dw: Float, dh: Float) {
        selectedImage?.let { img ->
            // The corner grip keeps a picture's proportions, as PowerPoint's does: the larger pull sizes it.
            pushUndo("resizeEl")
            val fx = (img.wPct + dw) / img.wPct.coerceAtLeast(1e-4f); val fy = (img.hPct + dh) / img.hPct.coerceAtLeast(1e-4f)
            val f = pictureFactor(img.wPct, img.hPct, if (kotlin.math.abs(fx - 1f) >= kotlin.math.abs(fy - 1f)) fx else fy, 1f - img.xPct, 1f - img.yPct)
            img.wPct *= f; img.hPct *= f
            dirty = true; bump(); return
        }
        selectedTable?.let { pushUndo("resizeEl"); it.wPct = (it.wPct + dw).within(0.1f, 1f - it.xPct); it.hPct = (it.hPct + dh).within(0.08f, 1f - it.yPct); dirty = true; bump(); return }
        selectedChart?.let { pushUndo("resizeEl"); it.wPct = (it.wPct + dw).within(0.1f, 1f - it.xPct); it.hPct = (it.hPct + dh).within(0.08f, 1f - it.yPct); dirty = true; bump(); return }
        resizeSelectedShape(dw, dh)
    }

    /**
     * One step of dragging a selection handle at ([hx], [hy]) — −1 the left / top side, 1 the right /
     * bottom, 0 the middle — by ([dx], [dy]) slide fractions: that side follows the pointer and the
     * opposite one stays put (PowerPoint's eight sizing handles; a line's two ends). A picture's
     * corner keeps its proportions, its sides stretch it. [box] is a title or body frame being sized,
     * otherwise the selection. A whole drag is one undo step.
     */
    fun dragHandle(hx: Int, hy: Int, dx: Float, dy: Float, box: SlideBox? = null) {
        fun sized(x: Float, y: Float, w: Float, h: Float, minW: Float, minH: Float, set: (Float, Float, Float, Float) -> Unit) {
            val r = handleFrame(x, y, w, h, hx, hy, dx, dy, minW, minH)
            set(r[0], r[1], r[2], r[3])
        }
        val img = selectedImage
        when {
            box != null -> {
                val f = box.frame ?: return
                pushUndo("resizeEl")
                sized(f.xPct, f.yPct, f.wPct, f.hPct, 0.08f, 0.06f) { x, y, w, h -> f.xPct = x; f.yPct = y; f.wPct = w; f.hPct = h }
            }
            img != null -> {
                pushUndo("resizeEl")
                if (hx != 0 && hy != 0) {
                    val fx = (img.wPct + hx * dx) / img.wPct.coerceAtLeast(1e-4f)
                    val fy = (img.hPct + hy * dy) / img.hPct.coerceAtLeast(1e-4f)
                    val roomW = if (hx > 0) 1f - img.xPct else img.xPct + img.wPct
                    val roomH = if (hy > 0) 1f - img.yPct else img.yPct + img.hPct
                    val f = pictureFactor(img.wPct, img.hPct, if (kotlin.math.abs(fx - 1f) >= kotlin.math.abs(fy - 1f)) fx else fy, roomW, roomH)
                    val nw = img.wPct * f; val nh = img.hPct * f
                    if (hx < 0) img.xPct += img.wPct - nw
                    if (hy < 0) img.yPct += img.hPct - nh
                    img.wPct = nw; img.hPct = nh
                } else sized(img.xPct, img.yPct, img.wPct, img.hPct, 0.02f, 0.02f) { x, y, w, h -> img.xPct = x; img.yPct = y; img.wPct = w; img.hPct = h }
            }
            selectedTable != null -> selectedTable?.let { t ->
                pushUndo("resizeEl")
                sized(t.xPct, t.yPct, t.wPct, t.hPct, 0.1f, 0.08f) { x, y, w, h -> t.xPct = x; t.yPct = y; t.wPct = w; t.hPct = h }
            }
            selectedChart != null -> selectedChart?.let { c ->
                pushUndo("resizeEl")
                sized(c.xPct, c.yPct, c.wPct, c.hPct, 0.1f, 0.08f) { x, y, w, h -> c.xPct = x; c.yPct = y; c.wPct = w; c.hPct = h }
            }
            selectedShape != null -> selectedShape?.let { sh ->
                pushUndo("resizeEl")
                val line = sh.type == ShapeType.LINE
                sized(sh.xPct, sh.yPct, sh.wPct, sh.hPct, if (line) 0f else 0.08f, if (line) 0f else 0.06f) { x, y, w, h ->
                    sh.xPct = x; sh.yPct = y; sh.wPct = w; sh.hPct = h
                }
            }
            else -> return
        }
        dirty = true; bump()
    }

    // --- slideshow settings -----------------------------------------------------
    /** Manual auto-advance time for the current slide (0 = manual advance). */
    fun setAdvanceMs(ms: Int) { pushUndo(); slide.advanceMs = ms.coerceIn(0, 600_000); dirty = true; bump() }

    /** Apply per-slide rehearse timings (排练计时结果) and switch the show to 使用计时. */
    fun applyRehearseTimings(timings: List<Int>) {
        if (timings.none { it > 0 }) return
        pushUndo(both(deckMeta(), allSlides()))
        timings.forEachIndexed { i, ms ->
            if (ms > 0) presentation.slides.getOrNull(i)?.advanceMs = ms.coerceAtLeast(1000)
        }
        presentation.useTimings = true
        dirty = true; bump()
    }

    fun toggleHidden() { pushUndo(); slide.hidden = !slide.hidden; dirty = true; bump() }
    fun setLoopShow(v: Boolean) { pushUndo(deckMeta()); presentation.loopShow = v; dirty = true; bump() }
    fun setUseTimings(v: Boolean) { pushUndo(deckMeta()); presentation.useTimings = v; dirty = true; bump() }
    fun setShowNarration(v: Boolean) { pushUndo(deckMeta()); presentation.showNarration = v; dirty = true; bump() }

    /** Set Up Slide Show: the setup with its loop, timings and narration options, in one undo step. */
    fun setShowSetup(
        setup: com.therealityteam.aeropages.model.ShowSetup,
        loop: Boolean = presentation.loopShow, timings: Boolean = presentation.useTimings, narration: Boolean = presentation.showNarration,
    ) {
        pushUndo(deckMeta())
        presentation.show = setup; presentation.loopShow = loop; presentation.useTimings = timings; presentation.showNarration = narration
        dirty = true; bump()
    }

    /**
     * Custom Slide Show: the deck's custom shows replaced by [shows], in one undo step. Set Up's show follows one
     * [renamed] (old name → new) and plays all the slides again when its show is gone.
     */
    fun setCustomShows(shows: List<com.therealityteam.aeropages.model.CustomShow>, renamed: Map<String, String> = emptyMap()) {
        pushUndo(deckMeta())
        presentation.customShows = shows.toList()
        presentation.show.custom?.let { name ->
            val now = renamed[name] ?: name
            presentation.show = presentation.show.copy(custom = now.takeIf { n -> shows.any { it.name == n } })
        }
        dirty = true; bump()
    }

    // --- design theme (richer) --------------------------------------------------
    fun applyThemeFull(index: Int, bg: Long?, titleColor: Long?, bodyColor: Long?, align: TextAlign?, dropDesigns: Set<String> = emptySet()) {
        pushUndo(both(deckMeta(), allSlides()))
        presentation.themeIndex = index
        // A designed theme's master pages give way to the plain one (a file's own designs stay).
        if (dropDesigns.isNotEmpty()) {
            presentation.slides.forEach { if (it.design in dropDesigns) it.design = null }
            presentation.designs.removeAll { it.name in dropDesigns }
        }
        // The theme lives on the master; slides drop their own background and text colours so it shows through.
        master.background = bg; master.backgroundShade = null; master.titleColor = titleColor; master.bodyColor = bodyColor
        presentation.slides.forEach { sl ->
            sl.background = null; sl.backgroundShade = null
            sl.title.paragraph.runs.forEach { it.format.color = null }
            sl.body.paragraph.runs.forEach { it.format.color = null }
            if (align != null) sl.title.paragraph.format.align = align
        }
        dirty = true; bump()
    }
}

/** The size an equation text box sets its formula at. */
private const val EQUATION_SIZE = 28f

/** A new line's colour: Office's accent blue, as PowerPoint draws its lines. */
private const val LINE_ACCENT = 0xFF4472C4

/** The side of a shape Insert → Shapes puts on the slide: an inch and a half. */
private const val PRESET_SIDE_PT = 108f

/** The DrawingML presets the app draws as shapes of its own (Insert → Shapes puts those in). */
internal val OWN_PRESETS: Map<String, ShapeType> = mapOf(
    "rect" to ShapeType.RECT, "roundRect" to ShapeType.ROUNDED, "ellipse" to ShapeType.ELLIPSE,
    "triangle" to ShapeType.TRIANGLE, "star5" to ShapeType.STAR, "rightArrow" to ShapeType.ARROW,
)

/**
 * PowerPoint's action buttons and the click action each comes with: Back goes to the previous slide, Home to the first,
 * Return to the last one viewed (the previous here); the others come with none ("") and wait for Action Settings.
 */
internal val ACTION_BUTTONS: Map<String, String> = mapOf(
    "actionButtonBackPrevious" to SlideLinks.PREVIOUS, "actionButtonForwardNext" to SlideLinks.NEXT,
    "actionButtonBeginning" to SlideLinks.FIRST, "actionButtonEnd" to SlideLinks.LAST, "actionButtonHome" to SlideLinks.FIRST,
    "actionButtonReturn" to SlideLinks.PREVIOUS, "actionButtonInformation" to "", "actionButtonMovie" to "",
    "actionButtonDocument" to "", "actionButtonSound" to "", "actionButtonHelp" to "", "actionButtonBlank" to "",
)


/**
 * The frame ([x], [y], [w], [h], slide fractions) after its handle at ([hx], [hy]) moves by ([dx], [dy]):
 * the dragged sides follow, the opposite sides stay, no side leaves the slide and the frame keeps at
 * least [minW] × [minH]. Returns x, y, w, h.
 */
internal fun handleFrame(x: Float, y: Float, w: Float, h: Float, hx: Int, hy: Int, dx: Float, dy: Float, minW: Float, minH: Float): FloatArray {
    fun side(start: Float, len: Float, toward: Int, d: Float, min: Float): Pair<Float, Float> = when {
        toward > 0 -> start to (len + d).coerceIn(min, maxOf(min, 1f - start))
        toward < 0 -> { val end = start + len; val s = (start + d).coerceIn(0f, maxOf(0f, end - min)); s to end - s }
        else -> start to len
    }
    val (nx, nw) = side(x, w, hx, dx, minW)
    val (ny, nh) = side(y, h, hy, dy, minH)
    return floatArrayOf(nx, ny, nw, nh)
}
