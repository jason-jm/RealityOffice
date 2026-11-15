package com.therealityteam.aeropages.ui

import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.text.withStyle
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.combinedClickable
import com.therealityteam.aeropages.R
/**
 * PowerPoint's Slide Sorter as a view in the window (the ribbon and status bar stay, the status bar's view buttons
 * switch back): every slide in rows on the window's glass, the current one framed in the accent; [onPick] gets the
 * slide clicked.
 */
@Composable
internal fun SlideOverview(editor: SlideEditor, onPick: (Int) -> Unit, modifier: Modifier = Modifier, onSectionName: () -> Unit = {}) {
    val accent = LocalAccent.current
    var menuFor by remember { mutableStateOf<Int?>(null) }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            // As many slides a row as fit at about 260 dp each, sharing the window's width.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f)) {
            val gap = 14.dp
            val cols = ((maxWidth + gap) / (260.dp + gap)).toInt().coerceIn(2, 8)
            val cellW = (maxWidth - gap * (cols - 1)) / cols
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) {
                editor.presentation.slides.chunked(cols).forEachIndexed { rowIdx, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEachIndexed { colIdx, slide ->
                            val index = rowIdx * cols + colIdx
                            val selected = index == editor.current
                            Column(Modifier.width(cellW), horizontalAlignment = Alignment.CenterHorizontally) {
                                // The section's name over its first slide; the line kept on every slide, so the row stays level.
                                Text(slide.sectionName ?: "", style = AeroType.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                                val label = shownBreaks(slide.title.text).ifBlank { stringResource(R.string.label_blank) }
                                Box(
                                    modifier = Modifier.fillMaxWidth().aspectRatio(slideAspect(editor)).clip(RoundedCornerShape(6.dp))
                                        .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) accent else Aero.barEdge), RoundedCornerShape(6.dp))
                                        .semantics { contentDescription = label }
                                        .combinedClickable(onLongClick = { editor.select(index); menuFor = index }) { onPick(index) },
                                ) {
                                    SlideThumbnail(editor, slide, index + 1, editor.revision, Modifier.fillMaxSize())
                                    if (slide.hidden) HiddenWash()
                                    if (menuFor == index) AnchoredFlyout({ menuFor = null }) { SlideThumbMenu(editor, onSectionName) { menuFor = null } }
                                    if (slide.comments.isNotEmpty()) Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { RibbonGlyph("ic_comment", accent, 14.dp) }
                                }
                                SlideNumberTag(index + 1, slide.hidden, PicoTheme.colorScheme.labelSecondary, Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * A hidden slide's thumbnail, faded as PowerPoint fades it: a wash of white over the picture (not
 * transparency, which would show what lies behind the pane through it).
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.HiddenWash() {
    Box(Modifier.matchParentSize().background(Color.White.copy(alpha = 1f - HIDDEN_ALPHA)))
}

/** How faint a hidden slide's thumbnail is drawn. */
private const val HIDDEN_ALPHA = 0.45f

/**
 * A slide's number beside its thumbnail. A hidden slide's (one the show skips) is boxed and struck
 * through with a diagonal, as PowerPoint marks it, and read out as hidden.
 */
@Composable
internal fun SlideNumberTag(number: Int, hidden: Boolean, color: Color, modifier: Modifier = Modifier) {
    val label = if (hidden) stringResource(R.string.a11y_slide_hidden_n, number) else number.toString()
    Box(modifier.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Text(
            "$number", style = AeroType.caption, color = color,
            modifier = if (!hidden) Modifier else Modifier
                .border(BorderStroke(1.dp, color), RoundedCornerShape(2.dp))
                .drawWithContent {
                    drawContent()
                    drawLine(color, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx())
                }
                .padding(horizontal = 2.dp),
        )
    }
}

@Composable
internal fun SlideNavigator(editor: SlideEditor, onSectionName: () -> Unit = {}) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    // PowerPoint's thumbnail menu: a press and hold on a slide (its index while open).
    var menuFor by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { if (com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "thumbmenu") { kotlinx.coroutines.delay(4_000); editor.select(1.coerceAtMost(editor.presentation.slides.lastIndex)); menuFor = editor.current } }
    // Pictures of slides that are gone (deleted, or replaced by an undo) are let go.
    LocalSlideThumbs.current?.prune(editor.presentation)
    Column(
        modifier = Modifier.width(180.dp).fillMaxHeight().background(PicoTheme.colorScheme.fillLight).verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val accent = LocalAccent.current
        editor.presentation.slides.forEachIndexed { i, slide ->
            val selected = i == editor.current
            slide.sectionName?.let { SectionHeader(it, i) }
            // PowerPoint's thumbnail pane: the current slide's row in the accent's tint, its picture framed in the accent.
            Row(
                Modifier.clip(RoundedCornerShape(Aero.commandRadius)).background(if (selected) Aero.tint(accent) else Color.Transparent).padding(4.dp),
                verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SlideNumberTag(i + 1, slide.hidden, if (selected) accent else PicoTheme.colorScheme.labelTertiary, Modifier.width(16.dp))
                // The slide as it prints, its title read out for accessibility.
                val label = shownBreaks(slide.title.text).ifBlank { stringResource(R.string.label_blank) }
                Box(
                    modifier = Modifier.weight(1f).aspectRatio(slideAspect(editor)).clip(RoundedCornerShape(4.dp))
                        .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else Aero.barEdge), RoundedCornerShape(4.dp))
                        .semantics { contentDescription = label }
                        .combinedClickable(onLongClick = { editor.select(i); menuFor = i }) { editor.select(i) },
                ) {
                    SlideThumbnail(editor, slide, i + 1, editor.revision, Modifier.fillMaxSize())
                    if (slide.hidden) HiddenWash()
                    if (menuFor == i) AnchoredFlyout({ menuFor = null }) { SlideThumbMenu(editor, onSectionName) { menuFor = null } }
                }
            }
        }
    }
}

/**
 * Outline view (PowerPoint's 大纲视图): every slide's title and text as editable fields, in
 * order, so a deck can be written or restructured as text. Focusing or editing an entry
 * selects its slide, which is what keeps undo pointed at the right slide.
 */
@Composable
internal fun OutlinePane(editor: SlideEditor) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    Column(
        modifier = Modifier.width(320.dp).fillMaxHeight().background(PicoTheme.colorScheme.fillLight).verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PaneSection(stringResource(R.string.outline_pane), Modifier.padding(bottom = 4.dp))
        val accent = LocalAccent.current
        editor.presentation.slides.forEachIndexed { i, slide ->
            val selected = i == editor.current
            slide.sectionName?.let { SectionHeader(it, i) }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Aero.commandRadius))
                    .background(if (selected) Aero.tint(accent) else Color.Transparent)
                    .clickable { editor.select(i) }.padding(6.dp),
                verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("${i + 1}", style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OutlineField(editor, i, slide.title, bold = true, hint = stringResource(R.string.placeholder_add_title), label = stringResource(R.string.outline_slide_title, i + 1))
                    val bodyShown = (slide.layout != SlideLayout.TITLE && slide.layout != SlideLayout.BLANK) || slide.body.text.isNotBlank()
                    if (bodyShown) OutlineField(editor, i, slide.body, bold = false, hint = stringResource(R.string.placeholder_add_text), label = stringResource(R.string.outline_slide_body, i + 1), indent = 12.dp)
                }
            }
        }
    }
}

@Composable
private fun OutlineField(editor: SlideEditor, index: Int, box: SlideBox, bold: Boolean, hint: String, label: String, indent: androidx.compose.ui.unit.Dp = 0.dp) {
    var value by remember(box) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(box.text)) }
    LaunchedEffect(editor.revision) {
        val t = box.text
        if (t != value.text) value = value.copy(text = t, selection = androidx.compose.ui.text.TextRange(value.selection.end.coerceIn(0, t.length)))
    }
    val style = AeroType.caption.copy(color = PicoTheme.colorScheme.labelPrimary, fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else null)
    Box(Modifier.fillMaxWidth().padding(start = indent)) {
        if (value.text.isEmpty()) Text(hint, style = style, color = PicoTheme.colorScheme.labelTertiary)
        BasicTextField(
            value = value,
            onValueChange = { nv ->
                val old = value.text
                value = nv
                if (nv.text != old) {
                    if (editor.current != index) editor.select(index)
                    editor.replaceText(box, old, nv.text)
                }
            },
            textStyle = style,
            cursorBrush = SolidColor(PicoTheme.colorScheme.interaction),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }
                .onFocusChanged { if (it.isFocused && editor.current != index) editor.select(index) },
        )
    }
}

/** A section's name above the slide that starts it (PowerPoint's 节 header in the thumbnail pane). */
@Composable
private fun SectionHeader(name: String, index: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else 6.dp)) {
        // PowerPoint's section header: a caret and the section's name.
        DropCaret(PicoTheme.colorScheme.labelSecondary, width = 8.dp)
        Text(name, style = AeroType.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.weight(1f))
    }
    HorizontalDivider()
}

@Composable
internal fun SlideCanvas(editor: SlideEditor, modifier: Modifier, showGridlines: Boolean = false, showGuides: Boolean = false, zoomPct: Int = 100,
    /** PowerPoint's animation tags: each animated element's click numbers at its top-left (while the Animations tab is open). */
    animTags: Boolean = false,
    /** A comment marker tapped (its thread's index): PowerPoint opens it in the Comments pane. Null: no markers. */
    onCommentMarker: ((Int) -> Unit)? = null) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val slide = editor.slide
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        val canvasLabel = stringResource(R.string.a11y_slide_canvas, editor.current + 1)
        // As large as the zoom asks of the room's width, never taller than the room: an open picker
        // above leaves less, and the slide must not run up under it.
        val aspect = slideAspect(editor)
        val slideWidth = minOf(maxWidth, maxHeight * aspect) * (zoomPct.coerceIn(40, 100) / 100f)
        Box(
            modifier = Modifier.width(slideWidth).aspectRatio(aspect).clip(RoundedCornerShape(6.dp))
                .slideBackground(editor.presentation, slide)
                .border(BorderStroke(1.dp, PicoTheme.colorScheme.dividerLine), RoundedCornerShape(6.dp))
                .semantics { contentDescription = canvasLabel }
                .clickable { editor.clearSelection() },
        ) { AtSlideScale(editor.presentation.slideSize.widthPt, slideWidth) {
        // The slide's design (its template's master page) under everything on it.
        SlideDesignLayer(editor.presentation, slide)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val slideW = maxWidth
            val slideH = maxHeight
            val centerTitle = slide.layout == SlideLayout.TITLE || slide.layout == SlideLayout.SECTION
            // Placeholders flow per the layout unless they carry a frame (imported geometry or a
            // dragged box), in which case they sit at that frame like a shape.
            val titleFrame = slide.title.frame
            val bodyFrame = slide.body.frame
            Column(
                Modifier.fillMaxSize().padding(36.dp),
                verticalArrangement = if (centerTitle) Arrangement.Center else Arrangement.spacedBy(16.dp),
            ) {
                // An unframed, empty sibling of a framed placeholder (a "Title Only" slide's body) has
                // nothing to show but its hint, which would sit oddly next to the framed box: skipped. A
                // blank slide has no title to add, as in PowerPoint, unless it has one already.
                if (titleFrame == null && (bodyFrame == null || slide.title.text.isNotBlank()) && (slide.layout != SlideLayout.BLANK || slide.title.text.isNotBlank())) {
                    RichBoxEditor(editor, slide.title, stringResource(R.string.placeholder_add_title),
                        Modifier.fillMaxWidth().emptyPlaceholderFrame(slide.title.text.isEmpty() && editor.focusedRef !== slide.title).padding(horizontal = 6.dp, vertical = 4.dp), defaults = editor.presentation.titleTextOn(slide), onFocus = { editor.selectShape(null) })
                }
                // A title slide's subtitle too, as PowerPoint shows it; a blank slide's text once it has some.
                // A content placeholder a table, chart or picture has taken asks for nothing (PowerPoint's is gone).
                val taken = slide.contentTaken
                if (bodyFrame == null && !taken && (slide.layout != SlideLayout.BLANK || slide.body.text.isNotBlank()) && (titleFrame == null || slide.body.text.isNotBlank())) {
                    val hint = stringResource(if (slide.layout == SlideLayout.TITLE) R.string.placeholder_add_subtitle else R.string.placeholder_add_text)
                    RichBoxEditor(editor, slide.body, hint,
                        Modifier.fillMaxWidth().emptyPlaceholderFrame(slide.body.text.isEmpty() && editor.focusedRef !== slide.body).padding(horizontal = 6.dp, vertical = 4.dp),
                        defaults = editor.presentation.bodyTextOn(slide), onFocus = { editor.selectShape(null) })
                }
            }
            if (titleFrame != null) PlaceholderView(editor, slide.title, titleFrame, stringResource(R.string.placeholder_add_title), editor.presentation.titleTextOn(slide), slideW, slideH, title = true)
            if (bodyFrame != null && !slide.contentTaken) PlaceholderView(editor, slide.body, bodyFrame, stringResource(if (slide.layout == SlideLayout.TITLE) R.string.placeholder_add_subtitle else R.string.placeholder_add_text),
                editor.presentation.bodyTextOn(slide), slideW, slideH, title = false)
            // Pictures: above the placeholders, below the free shapes (spTree order).
            slide.images.forEach { img -> if (!img.hidden) PictureView(editor, img, slideW, slideH) }
            // Free shapes / text boxes, positioned over the slide.
            slide.shapes.forEach { sh -> if (!sh.hidden) ShapeView(editor, sh, slideW, slideH) }
            slide.tables.forEach { tb -> if (!tb.hidden) TableView(editor, tb, slideW, slideH, editable = true) }
            slide.charts.forEach { ch -> if (!ch.hidden) ChartView(editor, ch, slideW, slideH, editable = true, background = editor.presentation.backgroundOf(slide)) }
            GuideOverlay(editor)
            if (animTags) AnimTags(slide, slideW, slideH)
            SlideFooter(editor.presentation, slide, editor.current + 1)
            if (onCommentMarker != null) CommentMarkers(slide, slideW, slideH, editor.pickedComment, onCommentMarker)
            if (showGridlines || showGuides) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val gc = Color(0x33000000)
                    if (showGridlines) {
                        val stepX = size.width / 12f; val stepY = size.height / 8f
                        var gx = stepX; while (gx < size.width) { drawLine(gc, androidx.compose.ui.geometry.Offset(gx, 0f), androidx.compose.ui.geometry.Offset(gx, size.height), 1f); gx += stepX }
                        var gy = stepY; while (gy < size.height) { drawLine(gc, androidx.compose.ui.geometry.Offset(0f, gy), androidx.compose.ui.geometry.Offset(size.width, gy), 1f); gy += stepY }
                    }
                    if (showGuides) {
                        val gd = Color(0x55E8004C)
                        drawLine(gd, androidx.compose.ui.geometry.Offset(size.width / 2f, 0f), androidx.compose.ui.geometry.Offset(size.width / 2f, size.height), 1.5f)
                        drawLine(gd, androidx.compose.ui.geometry.Offset(0f, size.height / 2f), androidx.compose.ui.geometry.Offset(size.width, size.height / 2f), 1.5f)
                    }
                }
            }
        }
        } }
    }
}

/**
 * The screen's own density, for what sits on a slide but is not of it (handles, tags): the slide
 * itself is laid out at its own size and scaled (see [AtSlideScale]).
 */
internal val LocalUiDensity = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.ui.unit.Density?> { null }

/**
 * [content] laid out at the slide's own size — a dp to each of its [slideWidthPt] points — and
 * drawn [shownWidth] wide: its text, lines and gaps take the size the slide is shown at, as in
 * PowerPoint's view and its PDF, however large the room.
 */
@Composable
internal fun AtSlideScale(slideWidthPt: Float, shownWidth: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    val screen = androidx.compose.ui.platform.LocalDensity.current
    val scaled = remember(screen, slideWidthPt, shownWidth) {
        androidx.compose.ui.unit.Density(screen.density * shownWidth.value / slideWidthPt.coerceAtLeast(1f), screen.fontScale)
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides scaled,
        LocalUiDensity provides (LocalUiDensity.current ?: screen),
        content = content,
    )
}

/**
 * The Animations tab's tags: for each animated element, the steps its effects play in (0 for what
 * starts with the slide, else the click), in a small grey box just left of its top-left corner —
 * where PowerPoint puts them. An unframed title or body is tagged where the layout puts it.
 */
@androidx.compose.runtime.NonSkippableComposable
@Composable
private fun androidx.compose.foundation.layout.BoxScope.AnimTags(slide: com.therealityteam.aeropages.model.Slide, slideW: androidx.compose.ui.unit.Dp, slideH: androidx.compose.ui.unit.Dp) {
    val steps = LinkedHashMap<com.therealityteam.aeropages.model.AnimTarget, MutableList<Int>>()
    com.therealityteam.aeropages.model.AnimTimeline.of(slide).steps.forEachIndexed { step, timed ->
        timed.forEach { t -> steps.getOrPut(t.target) { mutableListOf() }.let { if (step !in it) it.add(step) } }
    }
    steps.forEach { (target, list) ->
        val at: Pair<Float, Float> = when {
            target === slide.title || target === slide.body -> {
                val box = target as SlideBox
                val f = box.frame ?: com.therealityteam.aeropages.model.BoxFrame.defaultFor(slide.layout, title = target === slide.title)
                f.xPct to f.yPct
            }
            target is SlideImage -> target.xPct to target.yPct
            target is com.therealityteam.aeropages.model.SlideTable -> target.xPct to target.yPct
            target is com.therealityteam.aeropages.model.SlideChart -> target.xPct to target.yPct
            else -> slide.shapes.firstOrNull { it.box === target }?.let { it.xPct to it.yPct } ?: return@forEach
        }
        // Placed on the slide, labelled at the screen's size (the slide is scaled, its tags are not).
        val density = androidx.compose.ui.platform.LocalDensity.current
        val ui = LocalUiDensity.current ?: density
        val x = with(density) { (slideW * at.first).toPx() } - with(ui) { 22.dp.toPx() }
        val y = with(density) { (slideH * at.second).toPx() }
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides ui) {
            Text(
                list.joinToString(","), style = AeroType.caption, color = Color.White,
                modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(x.coerceAtLeast(0f).toInt(), y.toInt()) }
                    .background(Color(0xD0505050), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
internal fun NotesEditor(editor: SlideEditor) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val slide = editor.slide
    var value by remember(editor.current) { mutableStateOf(slide.notes) }
    LaunchedEffect(slide.notes) { if (slide.notes != value) value = slide.notes }
    // PowerPoint's notes pane: the notes field itself, "Click to add notes" until there are some.
    val notesLabel = stringResource(R.string.notes)
    Column(Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 140.dp).background(PicoTheme.colorScheme.fillLight).padding(horizontal = 16.dp, vertical = 10.dp)) {
        InputBox(value, { value = it; editor.setNotes(it) }, Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.notes_placeholder), description = notesLabel, minLines = 2, maxLines = 4)
    }
}

/** Selection-aware rich text editor for any slide box (title/body/shape). */
@Composable
internal fun RichBoxEditor(editor: SlideEditor, box: SlideBox, hint: String, modifier: Modifier, defaults: com.therealityteam.aeropages.model.MasterText? = null, onFocus: () -> Unit = {}) {
    var value by remember(box) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(box.text)) }
    val clipboard = rememberTextClipboard()
    // Copy, cut and paste ourselves (the field alone only knows plain text): the formatting goes with the text.
    fun clipboardKey(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (!(e.isCtrlPressed || e.isMetaPressed)) return false
        val copy = e.key == androidx.compose.ui.input.key.Key.C || e.key == androidx.compose.ui.input.key.Key.X
        val paste = e.key == androidx.compose.ui.input.key.Key.V && (clipboard.objectCopiedLast || com.therealityteam.aeropages.editor.DocClipboard.clip != null)
        if (!(copy && !value.selection.collapsed) && !paste) return false
        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return true
        editor.setSelection(box, value.selection.min, value.selection.max)
        when {
            paste -> pasteSlideText(editor, clipboard)
            else -> { copySlideText(editor, clipboard); if (e.key == androidx.compose.ui.input.key.Key.X) editor.deleteSelection() }
        }
        return true
    }
    LaunchedEffect(editor.revision) {
        val t = box.text
        if (t != value.text) {
            // The editor's selection when it changed this box (a level from the ribbon), else the caret where it was.
            val sel = if (editor.focusedRef === box) androidx.compose.ui.text.TextRange(editor.selStart.coerceIn(0, t.length), editor.selEnd.coerceIn(0, t.length))
                else androidx.compose.ui.text.TextRange(value.selection.end.coerceIn(0, t.length))
            value = value.copy(text = t, selection = sel)
        }
    }
    // Shift+Enter: a new line under the same bullet (PowerPoint's line break inside a paragraph).
    fun lineBreakKey(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (e.key != androidx.compose.ui.input.key.Key.Enter && e.key != androidx.compose.ui.input.key.Key.NumPadEnter) return false
        if (!e.isShiftPressed) return false
        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return true
        val sel = value.selection
        val text = value.text
        val t = text.substring(0, sel.min) + com.therealityteam.aeropages.model.SlideLevels.LINE_BREAK + text.substring(sel.max)
        editor.replaceText(box, text, t)
        value = androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(sel.min + 1))
        editor.setSelection(box, sel.min + 1, sel.min + 1)
        return true
    }
    // Tab / Shift+Tab: a line's level down or up at its start or over several lines (PowerPoint's); elsewhere Tab types a tab.
    fun tabKey(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (e.key != androidx.compose.ui.input.key.Key.Tab) return false
        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return true
        val sel = value.selection
        val text = value.text
        val lineStart = text.lastIndexOf('\n', (sel.min - 1).coerceAtLeast(-1)) + 1
        val contentStart = lineStart + com.therealityteam.aeropages.model.SlideLevels.levelAt(text, lineStart)
        if (!e.isShiftPressed && sel.collapsed && sel.min != contentStart) {
            val t = text.substring(0, sel.min) + "\t" + text.substring(sel.min)
            editor.replaceText(box, text, t)
            value = androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(sel.min + 1))
            editor.setSelection(box, sel.min + 1, sel.min + 1)
            return true
        }
        editor.changeListLevel(box, sel.start, sel.end, if (e.isShiftPressed) -1 else 1)
        value = androidx.compose.ui.text.input.TextFieldValue(box.text, androidx.compose.ui.text.TextRange(editor.selStart, editor.selEnd))
        return true
    }
    val base = slideBaseStyle(box, defaults)
    // A box that is an equation shows it typeset; a tap picks the box (Insert → Equation edits it).
    com.therealityteam.aeropages.model.MathText.soleEquation(box.paragraph)?.let { eq ->
        val first = box.paragraph.runs.first { it.format.math != null }.format
        EquationText(eq.math, first.fontSizePt ?: box.defaultSizePt, box.align, (first.color?.toInt() ?: base.color.toArgb()),
            modifier.clickable { onFocus(); editor.setSelection(box, 0, 0) })
        return
    }
    Box(modifier) {
        BasicTextField(
            value = value,
            onValueChange = { nv ->
                val old = value.text
                val next = keepLevel(old, nv)
                value = next
                if (next.text != old) editor.replaceText(box, old, next.text)
                editor.setSelection(box, next.selection.start, next.selection.end)
                // PowerPoint's AutoCorrect after one character typed at the caret (not while an input method composes).
                val at = next.selection.start
                if (next.text.length == old.length + 1 && next.selection.collapsed && at > 0 && next.composition == null &&
                    old.regionMatches(0, next.text, 0, at - 1) && old.regionMatches(at - 1, next.text, at, old.length - at + 1) &&
                    editor.autoCorrectTyped(box)
                ) value = androidx.compose.ui.text.input.TextFieldValue(box.text, androidx.compose.ui.text.TextRange(editor.selStart))
            },
            textStyle = base,
            visualTransformation = if (isListed(box)) listVisualTransformation(box) else {
                val runs = TextStyling.fieldTransformation(box.paragraph)
                androidx.compose.ui.text.input.VisualTransformation { t -> runs.filter(t).let { r -> androidx.compose.ui.text.input.TransformedText(shownBreaks(r.text), r.offsetMapping) } }
            },
            cursorBrush = SolidColor(PicoTheme.colorScheme.interaction),
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { clipboardKey(it) || tabKey(it) || lineBreakKey(it) }.onFocusChanged {
                if (it.isFocused) { onFocus(); editor.setSelection(box, value.selection.start, value.selection.end) }
            },
            // The prompt in the text's own colour, faint (light on a dark slide), inside the field so readers announce it with it.
            decorationBox = { inner ->
                Box {
                    if (value.text.isEmpty()) Text(hint, style = base.copy(fontWeight = if (box.paragraph.runs.firstOrNull()?.format?.bold == true)
                        androidx.compose.ui.text.font.FontWeight.Bold else base.fontWeight), color = base.color.copy(alpha = 0.4f), modifier = Modifier.fillMaxWidth())
                    inner()
                }
            },
        )
        // PowerPoint's AutoCorrect Options button: by the box's lower left corner after a correction in it, until the next key.
        editor.autoFixFor(box)?.takeIf { hasOptionsButton(it.kind) }?.let { fix ->
            var open by remember { mutableStateOf(false) }
            var options by remember { mutableStateOf(false) }
            val context = androidx.compose.ui.platform.LocalContext.current
            Box(Modifier.align(Alignment.BottomStart).offset(y = 34.dp)) {
                AtUiSize {
                    Row(Modifier.barSurface().padding(4.dp)) {
                        Box {
                            RibbonMiniButton("ic_lightning", stringResource(R.string.ac_options), { open = !open }, selected = open, dropdown = true)
                            if (open) AnchoredFlyout({ open = false }) {
                                AutoCorrectMenu(
                                    fix.kind, fix.was,
                                    onUndo = { open = false; editor.undoAutoFix() },
                                    onStop = {
                                        open = false
                                        AutoCorrectPrefs.save(context, stopped(com.therealityteam.aeropages.editor.AutoCorrect.options, fix.kind, fix.was))
                                        editor.undoAutoFix()
                                    },
                                    onOptions = { open = false; options = true },
                                )
                            }
                        }
                    }
                }
            }
            if (options) AutoCorrectOptionsDialog { options = false }
        }
        // PowerPoint's Paste Options button: by the box's lower right corner after a paste into it, until the next change.
        editor.pasteOptionsFor(box)?.let { mark ->
            var open by remember { mutableStateOf(com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "slidepasteoptions") }
            Box(Modifier.align(Alignment.BottomEnd).offset(y = 34.dp)) {
                AtUiSize {
                    Row(Modifier.barSurface().padding(4.dp)) {
                        Box {
                            RibbonMiniButton("📋", stringResource(R.string.paste_options), { open = !open }, selected = open, dropdown = true)
                            // Under the button, its right edge on the button's (the button sits at the box's right).
                            if (open) AnchoredFlyout({ open = false }, alignEnd = true) {
                                SlidePasteOptionsMenu(mark.mode) { mode -> open = false; editor.repaste(mode) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * PowerPoint's Paste Options for text pasted into a slide's box: Use Destination Theme (the box's formatting, keeping
 * the copied emphasis), Keep Source Formatting and Keep Text Only — [current] ticked; [onPick] pastes it again that way.
 */
@Composable
internal fun SlidePasteOptionsMenu(current: com.therealityteam.aeropages.editor.DocumentEditor.PasteMode, onPick: (com.therealityteam.aeropages.editor.DocumentEditor.PasteMode) -> Unit) {
    FlyoutMenu(width = 280.dp) {
        @Composable
        fun item(mode: com.therealityteam.aeropages.editor.DocumentEditor.PasteMode, label: Int, icon: String) =
            MenuCommand(stringResource(label), { onPick(mode) }, icon = icon, checked = current == mode)
        item(com.therealityteam.aeropages.editor.DocumentEditor.PasteMode.MERGE, R.string.paste_use_destination_theme, "ic_format_painter")
        item(com.therealityteam.aeropages.editor.DocumentEditor.PasteMode.KEEP_SOURCE, R.string.paste_keep_source, "📋")
        item(com.therealityteam.aeropages.editor.DocumentEditor.PasteMode.TEXT_ONLY, R.string.paste_text_only, "Aa")
    }
}

/** Copies the focused box's selected text — plain to the system clipboard, with its formatting for a paste in the app. */
internal fun copySlideText(editor: SlideEditor, clipboard: TextClipboard) {
    val clip = editor.copiedClip() ?: return
    com.therealityteam.aeropages.editor.DocClipboard.clip = clip
    clipboard.copy(clip.text)
}

/** Pastes what was copied last: an object, text copied in the app with its formatting (Paste Options after it), or plain text. */
internal fun pasteSlideText(editor: SlideEditor, clipboard: TextClipboard) {
    clipboard.pasteObjectOrText(onObject = { editor.pasteItem(it) }, onText = { text ->
        val rich = com.therealityteam.aeropages.editor.DocClipboard.matching(text)
        if (rich == null || !editor.pasteClip(rich)) editor.insertAtCaret(text)
    })
}

/**
 * Two-pointer transform of a selected element — both hands or both controllers: pinch to scale,
 * twist to rotate, move together to pan. Single-pointer input is left untouched, so taps, text
 * editing and the drag handles keep working. Deltas are slide-relative.
 */
internal fun Modifier.twoHandTransform(wPx: Float, hPx: Float, onTransform: (dx: Float, dy: Float, zoom: Float, rotation: Float) -> Unit): Modifier =
    pointerInput(wPx, hPx) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                    val zoom = event.calculateZoom()
                    val rotation = event.calculateRotation()
                    val pan = event.calculatePan()
                    if (zoom != 1f || rotation != 0f || pan != androidx.compose.ui.geometry.Offset.Zero) {
                        onTransform(pan.x / wPx.coerceAtLeast(1f), pan.y / hPx.coerceAtLeast(1f), zoom, rotation)
                        event.changes.forEach { it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }

/** Pixel size of the slide, for the gesture modifiers. */
@Composable
private fun slidePx(slideW: androidx.compose.ui.unit.Dp, slideH: androidx.compose.ui.unit.Dp): Pair<Float, Float> {
    val density = androidx.compose.ui.platform.LocalDensity.current
    return with(density) { slideW.toPx() } to with(density) { slideH.toPx() }
}

/** A line takes its touches this far round it — a level one has no height of its own. */
private val LINE_HIT = 12.dp

/**
 * A free-positioned shape/text box on the editing canvas: tap to select, drag its handle to move,
 * two hands to scale / rotate. Its geometry is drawn as the PDF draws it (outline dashes, a line's
 * arrowheads); flips mirror the geometry, never the text. The picked one shows a frame — a line,
 * a dot at each end.
 */
@Composable
internal fun ShapeView(editor: SlideEditor, shape: SlideShape, slideW: androidx.compose.ui.unit.Dp, slideH: androidx.compose.ui.unit.Dp) {
    val rev = editor.revision
    val selected = editor.selectedShape === shape || shape in editor.extraShapes
    val (wPx, hPx) = slidePx(slideW, slideH)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val line = shape.type == ShapeType.LINE
    // The touch margin is the screen's size, whatever size the slide is shown at.
    val padPx = if (line) with(LocalUiDensity.current ?: density) { LINE_HIT.toPx() } else 0f
    val pad = with(density) { padPx.toDp() }
    val frameColor = PicoTheme.colorScheme.interaction
    Box(
        modifier = Modifier
            .offset(x = slideW * shape.xPct - pad, y = slideH * shape.yPct - pad)
            .size(width = slideW * shape.wPct + pad * 2, height = slideH * shape.hPct + pad * 2)
            .then(if (selected) Modifier.twoHandTransform(wPx, hPx) { dx, dy, z, r -> editor.transformSelectedShape(dx, dy, z, r) } else Modifier),
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
                .graphicsLayer(rotationZ = shape.rotationDeg)
                .clickable { editor.selectShape(shape) },
            contentAlignment = anchorAlignment(shape.anchor),
        ) {
            ShapeGeometry(shape, density.density, rev, Modifier.fillMaxSize(), inset = padPx)
            if (selected) {
                val ui = LocalUiDensity.current ?: density
                val dot = with(ui) { 5.dp.toPx() }; val rim = with(ui) { 1.5.dp.toPx() }
                if (!line) Box(Modifier.fillMaxSize().border(BorderStroke(with(density) { rim.toDp() }, frameColor)))
            }
            if (!line || shape.box.text.isNotEmpty()) {
                // DrawingML's insets: a tenth of an inch at the sides, half that above and below. An
                // empty shape asks for its text only once picked — as in PowerPoint, a drawn arrow or
                // box is not covered in prompts.
                RichBoxEditor(editor, shape.box, if (selected) stringResource(R.string.text) else "", Modifier.fillMaxWidth().unwrapped(shape).padding(pad).padding(horizontal = 7.2.dp, vertical = 3.6.dp),
                    defaults = editor.presentation.shapeTextOn(editor.slide, shape), onFocus = { editor.selectShape(shape) })
            }
        }
        if (shape.action != null || shape.hoverAction != null) LinkMark()
        if (selected) {
            // A line's handles are its two ends (where it runs from and to); a shape's, its eight.
            val lineEnds = if (line) (if (shape.flipH) 1 else -1).let { sx -> (if (shape.flipV) 1 else -1).let { sy -> listOf(sx to sy, -sx to -sy) } } else null
            SelectionHandles(slideW, slideH, shape.yPct, { hx, hy, dx, dy -> editor.dragHandle(hx, hy, dx, dy) },
                { dx, dy -> editor.moveSelectedShape(dx, dy) }, onDragEnd = { editor.clearGuides() }, ends = lineEnds, inset = pad,
                // Several picked (Select All, Ctrl+click): each keeps its handles, without a bar of its own over it, as in PowerPoint.
                actions = if (editor.selectedShapes.size > 1) null else ({ SlideObjectActions(editor) }))
        }
    }
}

/**
 * The mark on a shape or picture whose click goes somewhere in the show (a link on the object): a
 * small arrow in a dot at its top right corner, at the screen's size whatever size the slide is shown at.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.LinkMark() {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val label = stringResource(R.string.a11y_has_link)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides (LocalUiDensity.current ?: density)) {
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(14.dp)
                .clip(androidx.compose.foundation.shape.CircleShape).background(PicoTheme.colorScheme.interaction)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(8.dp)) {
                val w = size.width; val h = size.height; val stroke = w * 0.16f
                drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.85f), androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.15f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.35f, h * 0.15f), androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.15f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.15f), androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.65f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

/**
 * A title / body placeholder with its own frame: positioned like a shape; while its text is
 * focused it shows an outline plus the move / resize handles (PowerPoint lets placeholders be
 * dragged too). Titles centre vertically in their frame, bodies start at the top.
 */
@androidx.compose.runtime.NonSkippableComposable
@Composable
private fun PlaceholderView(
    editor: SlideEditor, box: SlideBox, frame: com.therealityteam.aeropages.model.BoxFrame, hint: String,
    defaults: com.therealityteam.aeropages.model.MasterText?, slideW: androidx.compose.ui.unit.Dp, slideH: androidx.compose.ui.unit.Dp, title: Boolean,
) {
    val focused = editor.focusedRef === box
    Box(
        Modifier.offset(x = slideW * frame.xPct, y = slideH * frame.yPct)
            .size(width = slideW * frame.wPct, height = slideH * frame.hPct),
    ) {
        Box(
            Modifier.fillMaxSize()
                .then(when {
                    focused -> Modifier.border(BorderStroke(1.dp, PicoTheme.colorScheme.interaction), RoundedCornerShape(2.dp))
                    box.text.isEmpty() -> Modifier.emptyPlaceholderFrame(true)
                    else -> Modifier
                })
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = if (title) Alignment.CenterStart else Alignment.TopStart,
        ) {
            RichBoxEditor(editor, box, hint, Modifier.fillMaxWidth(), defaults = defaults, onFocus = { editor.selectShape(null) })
        }
        if (focused) {
            SelectionHandles(slideW, slideH, frame.yPct, { hx, hy, dx, dy -> editor.dragHandle(hx, hy, dx, dy, box) }, { dx, dy -> editor.dragBox(box, dx, dy) },
                onDragEnd = { editor.clearGuides() })
        }
    }
}

/** PowerPoint's dotted frame around an empty placeholder in Normal view ([show]: empty and not being typed in), so its prompt reads as a slot. */
internal fun Modifier.emptyPlaceholderFrame(show: Boolean): Modifier = if (!show) this else drawBehind {
    drawRect(Color(0xFFA6A6A6), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx(),
        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
}

/**
 * [slide]'s background behind what is drawn on it: its colour (white when none says), or its gradient
 * from that colour, drawn as a shape's gradient is (the PDF and the thumbnails draw it alike).
 */
internal fun Modifier.slideBackground(p: com.therealityteam.aeropages.model.Presentation, slide: com.therealityteam.aeropages.model.Slide): Modifier {
    val color = p.backgroundOf(slide) ?: 0xFFFFFFFF
    val shade = p.backgroundShadeOf(slide) ?: return background(Color(color))
    return drawBehind {
        drawIntoCanvas { c ->
            val paint = android.graphics.Paint().apply {
                shader = com.therealityteam.aeropages.format.pdf.GradientShaders.shader(shade.kind, color, shade.second, shade.angleDeg, shade.cx, shade.cy, 0f, 0f, size.width, size.height)
            }
            c.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
        }
    }
}

/** Compose alignment for a shape's vertical text anchor. */
/** A shape whose text does not wrap: its text as wide as its lines, placed by its alignment (beyond the box if it must). */
internal fun Modifier.unwrapped(shape: com.therealityteam.aeropages.model.SlideShape): Modifier = if (!shape.noWrap) this else wrapContentWidth(
    when (shape.box.align) {
        com.therealityteam.aeropages.model.TextAlign.CENTER -> Alignment.CenterHorizontally
        com.therealityteam.aeropages.model.TextAlign.RIGHT -> Alignment.End
        else -> Alignment.Start
    }, unbounded = true)

internal fun anchorAlignment(anchor: com.therealityteam.aeropages.model.ShapeAnchor): Alignment = when (anchor) {
    com.therealityteam.aeropages.model.ShapeAnchor.TOP -> Alignment.TopCenter
    com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM -> Alignment.BottomCenter
    else -> Alignment.Center
}

/**
 * A picture on the editing canvas: tap to select, drag the top handle to move, the corner grip to
 * resize. A video or a sound, selected, has PowerPoint's play bar under it, and plays in its frame.
 */
@androidx.compose.runtime.NonSkippableComposable
@Composable
internal fun PictureView(editor: SlideEditor, img: SlideImage, slideW: androidx.compose.ui.unit.Dp, slideH: androidx.compose.ui.unit.Dp) {
    val selected = editor.selectedImage === img
    val (wPx, hPx) = slidePx(slideW, slideH)
    val deckDir = remember(editor.presentation.sourcePath) { editor.presentation.sourcePath?.let { java.io.File(it).parentFile } }
    val media = rememberMediaControl(img, deckDir)
    // What a screen reader says for a picture: its alt text, else "Picture" (decorative: so marked); media say their own.
    val pictureLabel = when {
        img.decorative -> stringResource(R.string.a11y_picture_decorative)
        img.altText.isNotEmpty() -> img.altText
        else -> stringResource(R.string.picture)
    }
    Box(
        modifier = Modifier
            .offset(x = slideW * img.xPct, y = slideH * img.yPct)
            .size(width = slideW * img.wPct, height = slideH * img.hPct)
            .then(if (selected) Modifier.twoHandTransform(wPx, hPx) { dx, dy, z, r -> editor.transformSelected(dx, dy, z, r) } else Modifier),
    ) {
        // Turned as the picture is, its selection frame with it.
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer(rotationZ = img.rotationDeg)
                .then(if (selected) Modifier.border(BorderStroke(2.dp, PicoTheme.colorScheme.interaction)) else Modifier)
                .then(if (media == null) Modifier.semantics { contentDescription = pictureLabel } else Modifier)
                .clickable { editor.selectImage(img) },
        ) {
            if (media != null) MediaFrame(img, media, Modifier.fillMaxSize(), progress = false, badgeLabel = mediaLabels(media).name)
            else SlideImageView(img, Modifier.fillMaxSize())
        }
        if ((img.action != null || img.hoverAction != null) && media == null) LinkMark()
        if (selected) {
            SelectionHandles(slideW, slideH, img.yPct, editor::dragHandle, editor::dragSelected, onDragEnd = { editor.clearGuides() }, actions = { SlideObjectActions(editor) })
            // The play bar just under the picture — or, with no room left under it on the slide, over its
            // foot — at the screen's size whatever size the slide is shown at.
            if (media != null) {
                val slideDensity = androidx.compose.ui.platform.LocalDensity.current
                val ui = LocalUiDensity.current ?: slideDensity
                val room = slideH * (1f - img.yPct - img.hPct)
                val below = room >= (MEDIA_BAR_ROOM.value * ui.density / slideDensity.density).dp
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides ui) {
                    MediaBar(media, Modifier.align(Alignment.BottomCenter).offset(y = if (below) MEDIA_BAR_ROOM else (-6).dp).wrapContentSize(unbounded = true))
                }
            }
        }
    }
}

/** How far under a selected video the play bar sits: its height and a gap (screen dp). */
private val MEDIA_BAR_ROOM = 42.dp

/** Where a selected element's sizing handles stand: its corners and the middle of each side (−1 / 0 / 1 across and down). */
private val SIZING_HANDLES = listOf(-1 to -1, 0 to -1, 1 to -1, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0)

/** A handle's touch area (the dot drawn in it is smaller), at the screen's size. */
private val HANDLE_TOUCH = 28.dp

/**
 * A selected element's handles, as PowerPoint draws them: white dots on its corners and the middle of
 * its sides that size it from that side ([onSize]: the side, −1 / 0 / 1 across and down, and the drag
 * in slide fractions), and a round grip to move it by ([onMove]) above its top — below its foot when it
 * stands at the slide's top. A grip rather than the body, so the text inside stays editable. [ends]
 * instead puts dots only on those points (a line's two ends), [inset] in from the box's edges.
 * [topPct] is where the element starts down the slide. Handles keep the screen's size at any zoom.
 */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.SelectionHandles(
    slideW: androidx.compose.ui.unit.Dp, slideH: androidx.compose.ui.unit.Dp, topPct: Float,
    onSize: (Int, Int, Float, Float) -> Unit, onMove: (Float, Float) -> Unit, onDragEnd: () -> Unit = {},
    ends: List<Pair<Int, Int>>? = null, inset: androidx.compose.ui.unit.Dp = 0.dp,
    actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val ui = LocalUiDensity.current ?: density
    val wPx = with(density) { slideW.toPx() }.coerceAtLeast(1f)
    val hPx = with(density) { slideH.toPx() }.coerceAtLeast(1f)
    val insetPx = with(density) { inset.toPx() }
    val gripBelow = with(density) { (slideH * topPct).toPx() } < with(ui) { (if (actions != null) 56.dp else 40.dp).toPx() }
    val size by androidx.compose.runtime.rememberUpdatedState(onSize)
    val move by androidx.compose.runtime.rememberUpdatedState(onMove)
    val end by androidx.compose.runtime.rememberUpdatedState(onDragEnd)
    val ring = PicoTheme.colorScheme.interaction
    val sizeLabel = stringResource(R.string.a11y_sizing_handle)
    val moveLabel = stringResource(R.string.move_object)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides ui) {
        val inDp = with(ui) { insetPx.toDp() }
        (ends ?: SIZING_HANDLES).forEach { (hx, hy) ->
            Box(
                Modifier.align(androidx.compose.ui.BiasAlignment(hx.toFloat(), hy.toFloat()))
                    .offset(x = (HANDLE_TOUCH / 2 - inDp) * hx, y = (HANDLE_TOUCH / 2 - inDp) * hy)
                    .size(HANDLE_TOUCH)
                    .semantics { contentDescription = sizeLabel }
                    .pointerInput(wPx, hPx, hx, hy) {
                        detectDragGestures(onDragEnd = { end() }, onDragCancel = { end() }) { change, drag ->
                            change.consume(); size(hx, hy, drag.x / wPx, drag.y / hPx)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { HandleDot() }
        }
        val drag = Modifier.pointerInput(wPx, hPx) {
            detectDragGestures(onDragEnd = { end() }, onDragCancel = { end() }) { change, d ->
                change.consume(); move(d.x / wPx, d.y / hPx)
            }
        }
        if (actions != null) {
            // The selection bar (Office's mini toolbar): a handle to move the object by, then its commands — above
            // it, or below its foot when it stands at the slide's top.
            val gap = with(ui) { 8.dp.roundToPx() }
            ContextBar(
                stringResource(R.string.a11y_object_actions),
                Modifier.align(if (gripBelow) Alignment.BottomCenter else Alignment.TopCenter)
                    .layout { m, _ -> val p = m.measure(androidx.compose.ui.unit.Constraints()); layout(p.width, 0) { p.place(0, if (gripBelow) gap else -p.height - gap) } },
            ) {
                Box(
                    Modifier.semantics { contentDescription = moveLabel }
                        .size(32.dp, Aero.commandHeight).clip(androidx.compose.foundation.shape.RoundedCornerShape(Aero.commandRadius)).then(drag),
                    contentAlignment = Alignment.Center,
                ) {
                    GazeFill(androidx.compose.foundation.shape.RoundedCornerShape(Aero.commandRadius))
                    RibbonGlyph("ic_move", PicoTheme.colorScheme.labelPrimary, 20.dp)
                }
                BarDivider()
                actions()
            }
        } else Box(
            Modifier.align(if (gripBelow) Alignment.BottomCenter else Alignment.TopCenter)
                .offset(y = if (gripBelow) 32.dp else (-32).dp)
                .size(26.dp)
                .semantics { contentDescription = moveLabel }
                .shadow(2.dp, androidx.compose.foundation.shape.CircleShape)
                .background(Color.White, androidx.compose.foundation.shape.CircleShape)
                .border(1.5.dp, ring, androidx.compose.foundation.shape.CircleShape)
                .then(drag),
            contentAlignment = Alignment.Center,
        ) { RibbonGlyph("ic_move", ring, 16.dp) }
    }
}

/**
 * The selection bar's commands for a picked shape, picture, table or chart: a copy beside it, or gone — and More (⋯),
 * PowerPoint's right-click menu for it ([SlideObjectMenu]), whose Link… and Alt Text… dialogs open from here.
 */
@Composable
internal fun androidx.compose.foundation.layout.RowScope.SlideObjectActions(editor: SlideEditor) {
    RibbonMiniButton("dup", stringResource(R.string.sheet_duplicate), editor::duplicateSelectedObject)
    RibbonMiniButton("ic_delete", stringResource(R.string.delete), editor::deleteSelectedObject)
    val clipboard = rememberTextClipboard()
    var menu by remember { mutableStateOf(com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "objectmenu") }
    var link by remember { mutableStateOf(false) }
    var altText by remember { mutableStateOf(false) }
    Box {
        RibbonMiniButton("ic_more", stringResource(R.string.more_actions), { menu = !menu }, selected = menu)
        if (menu) AnchoredFlyout({ menu = false }) { SlideObjectMenu(editor, clipboard, onLink = { link = true }, onAltText = { altText = true }) { menu = false } }
    }
    if (link) SlideLinkDialog(editor, onDismiss = { link = false })
    val img = editor.selectedImage
    if (altText && img != null) AltTextDialog(img.altText, img.decorative, onDismiss = { altText = false }) { t, d -> editor.setImageAltText(t, d); altText = false }
}

/**
 * PowerPoint's comment markers: each thread's speech bubble where it is pinned on the slide (at the room's size, not the
 * slide's), the picked one ringed, a resolved one grey; a tap opens it in the Comments pane.
 */
@Composable
@androidx.compose.runtime.NonSkippableComposable
private fun CommentMarkers(slide: com.therealityteam.aeropages.model.Slide, slideW: androidx.compose.ui.unit.Dp, slideH: androidx.compose.ui.unit.Dp,
                           picked: Int, onOpen: (Int) -> Unit) {
    val accent = LocalAccent.current
    slide.comments.forEachIndexed { i, c ->
        val label = stringResource(R.string.a11y_comment_marker, c.author.ifBlank { stringResource(R.string.unknown_author) })
        Box(Modifier.offset(slideW * c.x, slideH * c.y)) {
            AtUiSize {
                val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomEnd = 6.dp, bottomStart = 1.dp)
                Box(
                    Modifier.size(26.dp).shadow(3.dp, shape).clip(shape)
                        .background(if (c.resolved) Color(0xFF9AA0A6) else accent)
                        .then(if (i == picked) Modifier.border(2.dp, Color.White, shape) else Modifier)
                        .press(onClickLabel = label) { onOpen(i) }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) { RibbonGlyph("ic_comment", Color.White, 15.dp) }
            }
        }
    }
}

/** Smart guides: the slide-relative lines the dragged element snapped to. */
@androidx.compose.runtime.NonSkippableComposable
@Composable
internal fun GuideOverlay(editor: SlideEditor) {
    val guides = editor.guides
    if (guides.isEmpty()) return
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val gc = Color(0xFFE8004C)
        guides.forEach { g ->
            if (g.vertical) drawLine(gc, androidx.compose.ui.geometry.Offset(g.pos * size.width, 0f), androidx.compose.ui.geometry.Offset(g.pos * size.width, size.height), 1.5f)
            else drawLine(gc, androidx.compose.ui.geometry.Offset(0f, g.pos * size.height), androidx.compose.ui.geometry.Offset(size.width, g.pos * size.height), 1.5f)
        }
    }
}

/**
 * The deck's date, footer and number at the foot of [slide] (number [number]) — each in its frame
 * where [com.therealityteam.aeropages.model.SlideChrome] puts it, tinted for the slide's background.
 * Laid out in slide points (inside [AtSlideScale]).
 */
@androidx.compose.runtime.NonSkippableComposable
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.SlideFooter(p: Presentation, slide: com.therealityteam.aeropages.model.Slide, number: Int) {
    val placed = com.therealityteam.aeropages.model.SlideChrome.placed(p, slide, number)
    if (placed.isEmpty()) return
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
        placed.forEach { c ->
            val align = when (c.align) {
                com.therealityteam.aeropages.model.TextAlign.LEFT -> Alignment.CenterStart
                com.therealityteam.aeropages.model.TextAlign.RIGHT -> Alignment.CenterEnd
                else -> Alignment.Center
            }
            Box(Modifier.offset(maxWidth * c.xPct, maxHeight * c.yPct).size(maxWidth * c.wPct, maxHeight * c.hPct), contentAlignment = align) {
                Text(c.text, fontSize = c.sizePt.sp, color = Color(c.color), maxLines = 1, softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

/** A table element on the editing canvas / slideshow. */
@androidx.compose.runtime.NonSkippableComposable
@Composable
internal fun TableView(editor: SlideEditor, table: com.therealityteam.aeropages.model.SlideTable, slideW: androidx.compose.ui.unit.Dp, slideH: androidx.compose.ui.unit.Dp, editable: Boolean,
    /** False when the caller places the table (the show's animation wrapper sits at its frame). */
    atFrame: Boolean = true,
    /** In the show: where a link in a cell's text goes when clicked. */
    onLink: ((String) -> Unit)? = null) {
    val selected = editor.selectedTable === table
    val look = com.therealityteam.aeropages.model.SlideTableLook
    fun cellBg(r: Int, c: Int): Color = Color(look.fill(table, r, c))
    val (tablePxW, tablePxH) = slidePx(slideW, slideH)
    // Lines are in points of the slide: so many pixels each at this size.
    val pxPerPt = tablePxW / editor.presentation.slideSize.widthPt.coerceAtLeast(1f)
    Box(
        modifier = Modifier
            .then(if (atFrame) Modifier.offset(x = slideW * table.xPct, y = slideH * table.yPct) else Modifier)
            .size(width = slideW * table.wPct, height = slideH * table.hPct)
            .then(if (editable && selected) Modifier.twoHandTransform(tablePxW, tablePxH) { dx, dy, z, _ -> editor.transformSelected(dx, dy, z) } else Modifier),
    ) {
    // Which cell shows at each place: a merged cell covers the places it spans (PowerPoint's gridSpan / rowSpan).
    val cover = table.coverage()
    val tableW = slideW * table.wPct
    val tableH = slideH * table.hPct
    // A block of cells picked with Table Layout ▸ Select ▾: tinted as a selection.
    val pick = if (selected) editor.tableRange else null
    val pickTint = PicoTheme.colorScheme.interaction.copy(alpha = 0.22f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Its lines over the cells, each edge once and none inside a merged cell: the cells' own borders, else the
            // style's grid — as in the show and the PDF.
            .drawWithContent {
                drawContent()
                pick?.let { rg ->
                    drawRect(pickTint, topLeft = Offset(table.colStart(rg[1]) * size.width, table.rowStart(rg[0]) * size.height),
                        size = androidx.compose.ui.geometry.Size((table.colStart(rg[3] + 1) - table.colStart(rg[1])) * size.width,
                            (table.rowStart(rg[2] + 1) - table.rowStart(rg[0])) * size.height))
                }
                val sides = com.therealityteam.aeropages.model.SlideTableLook.Side.entries
                for (r in 0 until table.rows) for (c in 0 until table.cols) {
                    val x = table.colStart(c) * size.width; val y = table.rowStart(r) * size.height
                    val cw = table.colShare(c) * size.width; val ch = table.rowShare(r) * size.height
                    if (r == 0 || cover[r][c] != cover[r - 1][c]) look.line(table, r, c, sides[0], cover)?.let { drawTableSide(it, Offset(x, y), Offset(x + cw, y), true, pxPerPt) }
                    if (c == 0 || cover[r][c] != cover[r][c - 1]) look.line(table, r, c, sides[2], cover)?.let { drawTableSide(it, Offset(x, y), Offset(x, y + ch), false, pxPerPt) }
                    if (r == table.rows - 1) look.line(table, r, c, sides[1], cover)?.let { drawTableSide(it, Offset(x, y + ch), Offset(x + cw, y + ch), true, pxPerPt) }
                    if (c == table.cols - 1) look.line(table, r, c, sides[3], cover)?.let { drawTableSide(it, Offset(x + cw, y), Offset(x + cw, y + ch), false, pxPerPt) }
                }
            }
            .then(if (selected) Modifier.border(BorderStroke(2.dp, PicoTheme.colorScheme.interaction), RoundedCornerShape(2.dp)) else Modifier)
            .then(if (editable) Modifier.clickable { editor.selectTable(table) } else Modifier),
    ) {
        for (r in 0 until table.rows) for (c in 0 until table.cols) {
            if (table.covered(cover, r, c)) continue
            val (rs, cs) = table.spanOf(r, c)
            val box = table.cells[r][c]
            Box(
                Modifier.offset(x = tableW * table.colStart(c), y = tableH * table.rowStart(r))
                    .size(width = tableW * (table.colStart(c + cs) - table.colStart(c)), height = tableH * (table.rowStart(r + rs) - table.rowStart(r)))
                    .background(cellBg(r, c))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                contentAlignment = when (box.cellAnchor) {
                    com.therealityteam.aeropages.model.ShapeAnchor.TOP -> Alignment.TopStart
                    com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM -> Alignment.BottomStart
                    else -> Alignment.CenterStart
                },
            ) {
                // The cell's text with its own formatting (white and bold in a header or total row unless its runs say),
                // the same in the editor, the show and the PDF; in the show its links follow.
                val defaults = look.textDefaults(table, r)
                if (editable) {
                    RichBoxEditor(editor, box, "", Modifier.fillMaxWidth(), defaults = defaults, onFocus = { editor.selectTable(table) })
                } else {
                    Text(TextStyling.annotatedOf(box.paragraph, onLink = onLink), style = slideBaseStyle(box, defaults), modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (editable && selected) SelectionHandles(slideW, slideH, table.yPct, editor::dragHandle, editor::dragSelected, onDragEnd = { editor.clearGuides() }, actions = { SlideObjectActions(editor) })
    }
}

/**
 * A slide table cell's side from [from] to [to]: its width in the slide's points ([pxPerPt] pixels each, a pixel at
 * least), colour and kind — dashes scaled to its width, a double line as two [horizontal]ly or vertically apart.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTableSide(line: com.therealityteam.aeropages.model.CellBorder, from: Offset, to: Offset, horizontal: Boolean, pxPerPt: Float) {
    val w = (line.widthPt * pxPerPt).coerceAtLeast(1f)
    val color = Color(line.color ?: 0xFF000000)
    if (line.style == com.therealityteam.aeropages.model.LineStyle.DOUBLE) {
        val d = if (horizontal) Offset(0f, w) else Offset(w, 0f)
        drawLine(color, from - d, to - d, w)
        drawLine(color, from + d, to + d, w)
        return
    }
    val effect = line.style.dash?.let { d -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(FloatArray(d.size) { d[it] * w * 1.5f }) }
    drawLine(color, from, to, w, pathEffect = effect)
}

/**
 * A data chart element on the editing canvas / slideshow. [background] is the slide's colour
 * (null = white): the title, legend and axis labels pick the ink that contrasts with it.
 */
@androidx.compose.runtime.NonSkippableComposable
@Composable
internal fun ChartView(editor: SlideEditor, chart: com.therealityteam.aeropages.model.SlideChart, slideW: androidx.compose.ui.unit.Dp, slideH: androidx.compose.ui.unit.Dp, editable: Boolean, background: Long? = null,
    /** False when the caller places the chart (the show's animation wrapper sits at its frame). */
    atFrame: Boolean = true) {
    val selected = editor.selectedChart === chart
    val (chartPxW, chartPxH) = slidePx(slideW, slideH)
    val ink = Color(com.therealityteam.aeropages.model.ChartStyle.inkFor(background))
    val surface = Color(background ?: 0xFFFFFFFF)
    Box(
        modifier = Modifier
            .then(if (atFrame) Modifier.offset(x = slideW * chart.xPct, y = slideH * chart.yPct) else Modifier)
            .size(width = slideW * chart.wPct, height = slideH * chart.hPct)
            .then(if (editable && selected) Modifier.twoHandTransform(chartPxW, chartPxH) { dx, dy, z, _ -> editor.transformSelected(dx, dy, z) } else Modifier),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()   // transparent like an Office chart area; only the selection draws a frame
            .border(BorderStroke(if (selected) 2.dp else 0.dp, if (selected) PicoTheme.colorScheme.interaction else Color.Transparent), RoundedCornerShape(4.dp))
            .then(if (editable) Modifier.clickable { editor.selectChart(chart) } else Modifier)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (chart.title.isNotBlank()) Text(chart.title, fontSize = 14.sp, color = ink, maxLines = 1)
        ChartBody(chart, Modifier.fillMaxWidth().weight(1f), ink = ink, surface = surface)
    }
    if (editable && selected) SelectionHandles(slideW, slideH, chart.yPct, editor::dragHandle, editor::dragSelected, onDragEnd = { editor.clearGuides() }, actions = { SlideObjectActions(editor) })
    }
}

/** The plot with its legend on the side the chart asks for (below, above, left or right, as in Office). */
@androidx.compose.runtime.NonSkippableComposable
@Composable
internal fun ChartBody(chart: com.therealityteam.aeropages.model.SlideChart, modifier: Modifier, ink: Color, surface: Color) {
    if (!chart.showLegend) { ChartPlot(chart, modifier, ink = ink, surface = surface); return }
    when (chart.legendPos) {
        com.therealityteam.aeropages.model.LegendPos.BOTTOM -> Column(modifier) {
            ChartPlot(chart, Modifier.fillMaxWidth().weight(1f), ink = ink, surface = surface)
            ChartLegend(chart, ink)
        }
        com.therealityteam.aeropages.model.LegendPos.TOP -> Column(modifier) {
            ChartLegend(chart, ink, modifier = Modifier.padding(bottom = 2.dp))
            ChartPlot(chart, Modifier.fillMaxWidth().weight(1f), ink = ink, surface = surface)
        }
        com.therealityteam.aeropages.model.LegendPos.LEFT -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            ChartLegend(chart, ink, vertical = true)
            ChartPlot(chart, Modifier.fillMaxHeight().weight(1f), ink = ink, surface = surface)
        }
        com.therealityteam.aeropages.model.LegendPos.RIGHT -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            ChartPlot(chart, Modifier.fillMaxHeight().weight(1f), ink = ink, surface = surface)
            ChartLegend(chart, ink, vertical = true)
        }
    }
}

/** Legend: series names, or the slices' category names for a pie / doughnut (as Office does); a row, or a column beside the plot. */
@androidx.compose.runtime.NonSkippableComposable
@Composable
internal fun ChartLegend(chart: com.therealityteam.aeropages.model.SlideChart, ink: Color, modifier: Modifier = Modifier, max: Int = 6, vertical: Boolean = false) {
    val pal = com.therealityteam.aeropages.model.ChartStyle.palette(chart.colorSet)
    val pie = chart.type == com.therealityteam.aeropages.model.ChartType.PIE || chart.type == com.therealityteam.aeropages.model.ChartType.DOUGHNUT
    val names = if (pie) chart.categories else chart.series.map { it.name }
    val entry: @Composable (Int, String) -> Unit = { i, name ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 5.dp, vertical = if (vertical) 2.dp else 0.dp)) {
            Box(Modifier.size(9.dp).background(Color(pal[i % pal.size])))
            Text(" $name", fontSize = 10.sp, color = ink.copy(alpha = 0.85f), maxLines = 1)
        }
    }
    if (vertical) {
        Column(modifier.padding(horizontal = 2.dp)) { names.take(max * 2).forEachIndexed { i, n -> entry(i, n) } }
    } else {
        Row(modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.Center) { names.take(max).forEachIndexed { i, n -> entry(i, n) } }
    }
}

/**
 * Draws the chart body with Compose Canvas: columns, horizontal bars (first category at the
 * bottom, as in Office), lines / areas / points or pie slices, with a round-numbered value axis,
 * category labels and — when [SlideChart.showValues] is on — data labels. Text and gridlines use
 * [ink]; a doughnut's hole takes the [surface] colour. The defaults suit the dark chart cards of
 * the Sheets chart panel.
 */
@androidx.compose.runtime.NonSkippableComposable
@Composable
internal fun ChartPlot(
    chart: com.therealityteam.aeropages.model.SlideChart,
    modifier: Modifier,
    ink: Color = Color(com.therealityteam.aeropages.model.ChartStyle.LIGHT_INK),
    surface: Color = Color(0xFF2B2B3A),
) {
    val pal = com.therealityteam.aeropages.model.ChartStyle.palette(chart.colorSet).map { Color(it) }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = ink.copy(alpha = 0.8f))
    val valueStyle = TextStyle(fontSize = 9.sp, color = ink)
    androidx.compose.foundation.Canvas(modifier) {
        if (chart.series.isEmpty() || chart.categories.isEmpty()) return@Canvas
        val kit = ChartDraw(measurer, labelStyle, valueStyle, ink, surface, pal, com.therealityteam.aeropages.model.ChartStyle.palette(chart.colorSet))
        when (chart.type) {
            com.therealityteam.aeropages.model.ChartType.PIE,
            com.therealityteam.aeropages.model.ChartType.DOUGHNUT -> kit.pie(this, chart)
            com.therealityteam.aeropages.model.ChartType.BAR -> kit.bars(this, chart)
            com.therealityteam.aeropages.model.ChartType.COLUMN -> kit.columns(this, chart)
            com.therealityteam.aeropages.model.ChartType.RADAR -> kit.radar(this, chart)
            else -> kit.lines(this, chart)   // LINE / AREA / SCATTER
        }
    }
}

/** The drawing routines behind [ChartPlot]; one instance per draw pass. */
private class ChartDraw(
    val m: androidx.compose.ui.text.TextMeasurer,
    val labelStyle: TextStyle,
    val valueStyle: TextStyle,
    val ink: Color,
    val surface: Color,
    val pal: List<Color>,
    /** [pal] as 0xAARRGGBB, for picking a label ink per slice. */
    val palArgb: List<Long>,
) {
    private val grid = ink.copy(alpha = 0.16f)
    private val baseline = ink.copy(alpha = 0.45f)

    private fun width(text: String, style: TextStyle = labelStyle): Float = m.measure(text, style, maxLines = 1).size.width.toFloat()
    private val lineH: Float get() = m.measure("0", labelStyle, maxLines = 1).size.height.toFloat()

    /** Draws [text] so that the point ([x], [y]) sits at fraction ([ax], [ay]) of its box. */
    private fun androidx.compose.ui.graphics.drawscope.DrawScope.text(text: String, style: TextStyle, x: Float, y: Float, ax: Float, ay: Float) {
        if (text.isEmpty()) return
        val r = m.measure(text, style, maxLines = 1)
        drawText(r, topLeft = androidx.compose.ui.geometry.Offset(x - r.size.width * ax, y - r.size.height * ay))
    }

    private val titleStyle = TextStyle(fontSize = 10.sp, color = ink.copy(alpha = 0.9f))

    /** Room an axis title takes (its line height plus a gap), or 0 without one. */
    private fun titleRoom(title: String): Float = if (title.isBlank()) 0f else m.measure(title, titleStyle, maxLines = 1).size.height + 3f

    /** A title under the plot, centred at [cx]. */
    private fun androidx.compose.ui.graphics.drawscope.DrawScope.bottomTitle(title: String, cx: Float) {
        if (title.isNotBlank()) text(title, titleStyle, cx, size.height, 0.5f, 1f)
    }

    /** A title turned a quarter left, along the left edge, centred at [cy]. */
    private fun androidx.compose.ui.graphics.drawscope.DrawScope.leftTitle(title: String, cy: Float) {
        if (title.isBlank()) return
        val r = m.measure(title, titleStyle, maxLines = 1)
        val cx = r.size.height / 2f
        rotate(-90f, pivot = androidx.compose.ui.geometry.Offset(cx, cy)) {
            drawText(r, topLeft = androidx.compose.ui.geometry.Offset(cx - r.size.width / 2f, cy - r.size.height / 2f))
        }
    }

    /** The chart's value axis — round automatic bounds, or its own fixed ones (Format Axis). */
    private fun axisOf(chart: com.therealityteam.aeropages.model.SlideChart): com.therealityteam.aeropages.model.ChartStyle.Axis =
        com.therealityteam.aeropages.model.ChartStyle.valueAxis(chart)

    /** Category labels centred under their slots; every n-th one only when they would collide. */
    private fun androidx.compose.ui.graphics.drawscope.DrawScope.categoryLabels(chart: com.therealityteam.aeropages.model.SlideChart, slot: Float, y: Float, centerOf: (Int) -> Float) {
        val widest = chart.categories.maxOfOrNull { width(it) } ?: 0f
        val every = kotlin.math.ceil((widest + 4f) / slot.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        chart.categories.forEachIndexed { i, c -> if (i % every == 0) text(c, labelStyle, centerOf(i), y, 0.5f, 0f) }
    }

    fun columns(scope: androidx.compose.ui.graphics.drawscope.DrawScope, chart: com.therealityteam.aeropages.model.SlideChart) = with(scope) {
        val axis = axisOf(chart)
        val ticks = axis.ticks.map { com.therealityteam.aeropages.model.ChartStyle.valueLabel(chart, it, compact = true) }
        val yTitle = titleRoom(chart.valAxisTitle); val xTitle = titleRoom(chart.catAxisTitle)
        val padL = (ticks.maxOfOrNull { width(it) } ?: 0f) + 6f + yTitle
        val padT = if (chart.showValues) lineH + 2f else lineH / 2f
        val padB = lineH + 4f + xTitle
        val plotW = (size.width - padL - 4f).coerceAtLeast(1f); val plotH = (size.height - padT - padB).coerceAtLeast(1f)
        fun yOf(v: Double) = padT + plotH * (1f - axis.fraction(v))
        axis.ticks.forEachIndexed { i, t ->
            val y = yOf(t)
            if (chart.showGrid) drawLine(grid, androidx.compose.ui.geometry.Offset(padL, y), androidx.compose.ui.geometry.Offset(padL + plotW, y), 1f)
            text(ticks[i], labelStyle, padL - 4f, y, 1f, 0.5f)
        }
        // Bars grow from zero — or from the axis end nearer it when fixed bounds leave zero out.
        val zeroY = yOf(axis.baseline)
        val nCat = chart.categories.size; val nSer = chart.series.size
        val groupW = plotW / nCat; val barW = groupW * 0.7f / nSer
        for (ci in 0 until nCat) for (si in 0 until nSer) {
            val v = chart.series[si].values.getOrNull(ci) ?: continue
            val y = yOf(axis.clamp(v.toDouble()))
            val top = minOf(y, zeroY); val bh = kotlin.math.abs(zeroY - y)
            val bx = padL + groupW * ci + groupW * 0.15f + barW * si
            drawRect(pal[si % pal.size], androidx.compose.ui.geometry.Offset(bx, top), androidx.compose.ui.geometry.Size(barW * 0.92f, bh))
            if (chart.showValues) text(com.therealityteam.aeropages.model.ChartStyle.valueLabel(chart, v.toDouble()), valueStyle, bx + barW * 0.46f,
                if (v >= 0f) top - 1f else top + bh + 1f, 0.5f, if (v >= 0f) 1f else 0f)
        }
        drawLine(baseline, androidx.compose.ui.geometry.Offset(padL, zeroY), androidx.compose.ui.geometry.Offset(padL + plotW, zeroY), 1f)
        categoryLabels(chart, groupW, padT + plotH + 3f) { ci -> padL + groupW * (ci + 0.5f) }
        leftTitle(chart.valAxisTitle, padT + plotH / 2f)
        bottomTitle(chart.catAxisTitle, padL + plotW / 2f)
    }

    fun bars(scope: androidx.compose.ui.graphics.drawscope.DrawScope, chart: com.therealityteam.aeropages.model.SlideChart) = with(scope) {
        val axis = axisOf(chart)
        val ticks = axis.ticks.map { com.therealityteam.aeropages.model.ChartStyle.valueLabel(chart, it, compact = true) }
        val catTitle = titleRoom(chart.catAxisTitle); val valTitle = titleRoom(chart.valAxisTitle)
        val padL = (chart.categories.maxOfOrNull { width(it) } ?: 0f) + 6f + catTitle
        val maxValueW = if (chart.showValues) chart.series.flatMap { it.values }.maxOfOrNull { width(com.therealityteam.aeropages.model.ChartStyle.valueLabel(chart, it.toDouble()), valueStyle) } ?: 0f else 0f
        val padR = maxOf(8f, maxValueW + 4f, (ticks.lastOrNull()?.let { width(it) } ?: 0f) / 2f)
        val padB = lineH + 4f + valTitle
        val plotW = (size.width - padL - padR).coerceAtLeast(1f); val plotH = (size.height - 4f - padB).coerceAtLeast(1f)
        fun xOf(v: Double) = padL + plotW * axis.fraction(v)
        val tickSlot = plotW / (ticks.size - 1).coerceAtLeast(1)
        val every = kotlin.math.ceil(((ticks.maxOfOrNull { width(it) } ?: 0f) + 4f) / tickSlot.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        axis.ticks.forEachIndexed { i, t ->
            val x = xOf(t)
            if (chart.showGrid) drawLine(grid, androidx.compose.ui.geometry.Offset(x, 4f), androidx.compose.ui.geometry.Offset(x, 4f + plotH), 1f)
            if (i % every == 0) text(ticks[i], labelStyle, x, 4f + plotH + 3f, 0.5f, 0f)
        }
        val zeroX = xOf(axis.baseline)
        val nCat = chart.categories.size; val nSer = chart.series.size
        val groupH = plotH / nCat; val barH = groupH * 0.7f / nSer
        for (ci in 0 until nCat) {
            val row = nCat - 1 - ci                          // Office draws the first category at the bottom
            text(chart.categories[ci], labelStyle, padL - 4f, 4f + groupH * (row + 0.5f), 1f, 0.5f)
            for (si in 0 until nSer) {
                val v = chart.series[si].values.getOrNull(ci) ?: continue
                val x = xOf(axis.clamp(v.toDouble()))
                val left = minOf(x, zeroX); val bw = kotlin.math.abs(x - zeroX)
                val by = 4f + groupH * row + groupH * 0.15f + barH * (nSer - 1 - si)
                drawRect(pal[si % pal.size], androidx.compose.ui.geometry.Offset(left, by), androidx.compose.ui.geometry.Size(bw, barH * 0.92f))
                if (chart.showValues) text(com.therealityteam.aeropages.model.ChartStyle.valueLabel(chart, v.toDouble()), valueStyle,
                    if (v >= 0f) left + bw + 2f else left - 2f, by + barH * 0.46f, if (v >= 0f) 0f else 1f, 0.5f)
            }
        }
        drawLine(baseline, androidx.compose.ui.geometry.Offset(zeroX, 4f), androidx.compose.ui.geometry.Offset(zeroX, 4f + plotH), 1f)
        leftTitle(chart.catAxisTitle, 4f + plotH / 2f)
        bottomTitle(chart.valAxisTitle, padL + plotW / 2f)
    }

    fun lines(scope: androidx.compose.ui.graphics.drawscope.DrawScope, chart: com.therealityteam.aeropages.model.SlideChart) = with(scope) {
        val axis = axisOf(chart)
        val ticks = axis.ticks.map { com.therealityteam.aeropages.model.ChartStyle.valueLabel(chart, it, compact = true) }
        val yTitle = titleRoom(chart.valAxisTitle); val xTitle = titleRoom(chart.catAxisTitle)
        val padL = (ticks.maxOfOrNull { width(it) } ?: 0f) + 6f + yTitle
        val padT = if (chart.showValues) lineH + 2f else lineH / 2f
        val padB = lineH + 4f + xTitle
        val plotW = (size.width - padL - 4f).coerceAtLeast(1f); val plotH = (size.height - padT - padB).coerceAtLeast(1f)
        fun yOf(v: Double) = padT + plotH * (1f - axis.fraction(v))
        axis.ticks.forEachIndexed { i, t ->
            val y = yOf(t)
            if (chart.showGrid) drawLine(grid, androidx.compose.ui.geometry.Offset(padL, y), androidx.compose.ui.geometry.Offset(padL + plotW, y), 1f)
            text(ticks[i], labelStyle, padL - 4f, y, 1f, 0.5f)
        }
        val zeroY = yOf(axis.baseline)
        val n = chart.categories.size
        val slot = plotW / n
        fun xOf(ci: Int) = padL + slot * (ci + 0.5f)             // points sit mid-slot, like Office's "between tick marks"
        chart.series.forEachIndexed { si, s ->
            val col = pal[si % pal.size]
            val pts = s.values.take(n).mapIndexed { ci, v -> androidx.compose.ui.geometry.Offset(xOf(ci), yOf(axis.clamp(v.toDouble()))) }
            if (pts.isEmpty()) return@forEachIndexed
            when (chart.type) {
                com.therealityteam.aeropages.model.ChartType.SCATTER -> pts.forEach { drawCircle(col, 4f, it) }
                com.therealityteam.aeropages.model.ChartType.AREA -> {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(pts.first().x, zeroY); pts.forEach { lineTo(it.x, it.y) }; lineTo(pts.last().x, zeroY); close()
                    }
                    drawPath(path, col.copy(alpha = 0.35f))
                    for (i in 0 until pts.size - 1) drawLine(col, pts[i], pts[i + 1], 2.5f)
                }
                else -> {
                    for (i in 0 until pts.size - 1) drawLine(col, pts[i], pts[i + 1], 3f)
                    pts.forEach { drawCircle(col, 3f, it) }
                }
            }
            if (chart.showValues) s.values.take(n).forEachIndexed { ci, v ->
                text(com.therealityteam.aeropages.model.ChartStyle.valueLabel(chart, v.toDouble()), valueStyle, pts[ci].x, pts[ci].y - 4f, 0.5f, 1f)
            }
        }
        drawLine(baseline, androidx.compose.ui.geometry.Offset(padL, zeroY), androidx.compose.ui.geometry.Offset(padL + plotW, zeroY), 1f)
        categoryLabels(chart, slot, padT + plotH + 3f) { ci -> xOf(ci) }
        leftTitle(chart.valAxisTitle, padT + plotH / 2f)
        bottomTitle(chart.catAxisTitle, padL + plotW / 2f)
    }

    /**
     * A radar (spider) chart as Office draws it: one spoke per category clockwise from the top,
     * rings at the value ticks (when gridlines are on), each series a closed polygon with markers.
     */
    fun radar(scope: androidx.compose.ui.graphics.drawscope.DrawScope, chart: com.therealityteam.aeropages.model.SlideChart) = with(scope) {
        val n = chart.categories.size
        if (n < 3) { lines(scope, chart); return@with }   // two spokes make no polygon: show it as lines
        val axis = axisOf(chart)
        val labelW = (chart.categories.maxOfOrNull { width(it) } ?: 0f).coerceAtMost(size.width / 4f)
        val cx = size.width / 2f; val cy = size.height / 2f
        val r = minOf(size.width / 2f - labelW - 6f, size.height / 2f - lineH - 4f).coerceAtLeast(8f)
        fun angle(i: Int) = -Math.PI / 2 + 2 * Math.PI * i / n
        fun pt(i: Int, frac: Float) = androidx.compose.ui.geometry.Offset(
            cx + (r * frac * Math.cos(angle(i))).toFloat(), cy + (r * frac * Math.sin(angle(i))).toFloat())
        fun ring(frac: Float, color: Color, w: Float) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(pt(0, frac).x, pt(0, frac).y); for (i in 1 until n) lineTo(pt(i, frac).x, pt(i, frac).y); close()
            }
            drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        }
        if (chart.showGrid) axis.ticks.forEach { t -> val f = axis.fraction(t); if (f > 0f) ring(f, grid, 1f) }
        ring(1f, baseline, 1f)
        for (i in 0 until n) drawLine(grid, androidx.compose.ui.geometry.Offset(cx, cy), pt(i, 1f), 1f)
        // Value scale beside the top spoke, each label just under its ring (clear of the top category name).
        axis.ticks.forEach { t ->
            val y = pt(0, axis.fraction(t)).y
            text(com.therealityteam.aeropages.model.ChartStyle.valueLabel(chart, t, compact = true), labelStyle, cx + 3f, y + 1f, 0f, 0f)
        }
        chart.categories.forEachIndexed { i, cat ->
            val a = angle(i)
            val p = pt(i, 1f)
            text(cat, labelStyle, p.x + (4f * Math.cos(a)).toFloat(), p.y + (4f * Math.sin(a)).toFloat(),
                (0.5 - 0.5 * Math.cos(a)).toFloat(), (0.5 - 0.5 * Math.sin(a)).toFloat())
        }
        chart.series.forEachIndexed { si, s ->
            val col = pal[si % pal.size]
            val pts = (0 until n).map { i -> pt(i, axis.fraction(axis.clamp((s.values.getOrNull(i) ?: 0f).toDouble()))) }
            val path = androidx.compose.ui.graphics.Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            drawPath(path, col, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f))
            pts.forEach { drawCircle(col, 3f, it) }
            if (chart.showValues) s.values.take(n).forEachIndexed { i, v ->
                text(com.therealityteam.aeropages.model.ChartStyle.valueLabel(chart, v.toDouble()), valueStyle, pts[i].x, pts[i].y - 4f, 0.5f, 1f)
            }
        }
    }

    fun pie(scope: androidx.compose.ui.graphics.drawscope.DrawScope, chart: com.therealityteam.aeropages.model.SlideChart) = with(scope) {
        val vals = (chart.series.firstOrNull()?.values ?: emptyList()).map { it.coerceAtLeast(0f) }
        val total = vals.sum()
        if (total <= 0f) return@with
        val cx = size.width / 2f; val cy = size.height / 2f; val rad = minOf(size.width, size.height) / 2.2f
        val doughnut = chart.type == com.therealityteam.aeropages.model.ChartType.DOUGHNUT
        var start = -90f
        vals.forEachIndexed { i, v ->
            val sweep = v / total * 360f
            drawArc(color = pal[i % pal.size], startAngle = start, sweepAngle = sweep, useCenter = true,
                topLeft = androidx.compose.ui.geometry.Offset(cx - rad, cy - rad), size = androidx.compose.ui.geometry.Size(rad * 2, rad * 2))
            start += sweep
        }
        if (doughnut) drawCircle(color = surface, radius = rad * 0.55f, center = androidx.compose.ui.geometry.Offset(cx, cy))
        if (chart.showValues) {
            var a = -90.0
            vals.forEachIndexed { i, v ->
                val sweep = v / total * 360.0
                val mid = Math.toRadians(a + sweep / 2)
                val r = if (doughnut) rad * 0.78f else rad * 0.62f
                // Each label reads on its own slice.
                val onSlice = TextStyle(fontSize = valueStyle.fontSize, color = Color(com.therealityteam.aeropages.model.ChartStyle.inkFor(palArgb[i % palArgb.size])))
                if (sweep >= 12.0) text(com.therealityteam.aeropages.model.ChartStyle.valueLabel(chart, v.toDouble()), onSlice,
                    cx + (r * Math.cos(mid)).toFloat(), cy + (r * Math.sin(mid)).toFloat(), 0.5f, 0.5f)
                a += sweep
            }
        }
    }

}

/** A slide picture: the part its crop leaves, stretched to the frame as PowerPoint does (`a:stretch`). */
@androidx.compose.runtime.NonSkippableComposable
@Composable
internal fun SlideImageView(img: SlideImage, modifier: Modifier, badge: Boolean = true) {
    val bmp: ImageBitmap? = rememberDecodedImage(img.data)
    if (bmp != null) {
        // Decoding may downsample: the crop is a fraction of whatever size the bitmap came out.
        val (l, t, r, b) = img.visiblePixels(bmp.width, bmp.height)
        val painter = remember(bmp, l, t, r, b) {
            androidx.compose.ui.graphics.painter.BitmapPainter(bmp, androidx.compose.ui.unit.IntOffset(l, t), androidx.compose.ui.unit.IntSize(r - l, b - t))
        }
        val shadow = img.shadow
        Box(modifier) {
            // Its shadow under it (the slide is laid out a point to a dp, as its shapes' shadows are).
            if (shadow != null) androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                drawIntoCanvas { c ->
                    com.therealityteam.aeropages.format.pdf.PictureShadows.draw(c.nativeCanvas, bmp.asAndroidBitmap(), android.graphics.Rect(l, t, r, b),
                        android.graphics.RectF(0f, 0f, size.width, size.height), shadow, density)
                }
            }
            // Mirrored as it is flipped (the caller turns it).
            Image(painter = painter, contentDescription = null, contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize().graphicsLayer(scaleX = if (img.flipH) -1f else 1f, scaleY = if (img.flipV) -1f else 1f))
            if (badge) img.media?.let { m ->
                val audio = m.kind == com.therealityteam.aeropages.model.SlideMedia.Kind.AUDIO
                MediaBadge(if (audio) MediaGlyph.SPEAKER else MediaGlyph.PLAY,
                    stringResource(if (audio) R.string.a11y_media_audio else R.string.a11y_media_video, m.name), Modifier.matchParentSize())
            }
        }
    }
}

/** An angle going round once every 0.9 s. */
@Composable
private fun busyTurn(): Float {
    val turn by androidx.compose.animation.core.rememberInfiniteTransition(label = "media").animateFloat(
        0f, 360f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)),
        label = "busy")
    return turn
}

/** What a video's or sound's badge shows: what a tap does, or that it is getting ready or cannot play. */
internal enum class MediaGlyph { PLAY, SPEAKER, PAUSE, BUSY, ERROR }

/**
 * The mark on a picture that stands for a video or a sound — its poster frame or icon, which is
 * what AeroDesk shows of it: a round badge in the middle with [glyph] (▶, a speaker …), named
 * [label] for accessibility (null: its picture is named already).
 */
@Composable
internal fun MediaBadge(glyph: MediaGlyph, label: String?, modifier: Modifier) {
    // Getting ready: an arc going round the badge (only then a running animation).
    val turn = if (glyph != MediaGlyph.BUSY) 0f else busyTurn()
    androidx.compose.foundation.Canvas(if (label != null) modifier.semantics { contentDescription = label } else modifier) {
        val d = (minOf(size.width, size.height) * 0.26f).coerceIn(28.dp.toPx(), 72.dp.toPx())
        val c = center
        drawCircle(Color(0x8C000000), radius = d / 2f, center = c)
        val ring = androidx.compose.ui.graphics.drawscope.Stroke(width = d * 0.05f)
        if (glyph == MediaGlyph.BUSY) {
            drawCircle(Color.White.copy(alpha = 0.3f), radius = d / 2f, center = c, style = ring)
            drawArc(Color.White, turn, 90f, false, topLeft = androidx.compose.ui.geometry.Offset(c.x - d / 2f, c.y - d / 2f),
                size = androidx.compose.ui.geometry.Size(d, d), style = androidx.compose.ui.graphics.drawscope.Stroke(width = d * 0.08f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        } else {
            drawCircle(Color.White.copy(alpha = 0.9f), radius = d / 2f, center = c, style = ring)
        }
        val white = Color.White
        when (glyph) {
            MediaGlyph.PLAY -> drawPath(androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x - d * 0.13f, c.y - d * 0.22f); lineTo(c.x + d * 0.24f, c.y); lineTo(c.x - d * 0.13f, c.y + d * 0.22f); close()
            }, white)
            MediaGlyph.PAUSE -> for (x in listOf(-0.13f, 0.05f)) drawRect(white, androidx.compose.ui.geometry.Offset(c.x + d * x, c.y - d * 0.2f),
                androidx.compose.ui.geometry.Size(d * 0.08f, d * 0.4f))
            MediaGlyph.ERROR -> {
                drawRect(white, androidx.compose.ui.geometry.Offset(c.x - d * 0.04f, c.y - d * 0.24f), androidx.compose.ui.geometry.Size(d * 0.08f, d * 0.3f))
                drawCircle(white, radius = d * 0.05f, center = androidx.compose.ui.geometry.Offset(c.x, c.y + d * 0.17f))
            }
            MediaGlyph.SPEAKER -> {
                // A speaker — its box and flared cone — and two sound waves.
                val u = d * 0.5f
                drawPath(androidx.compose.ui.graphics.Path().apply {
                    moveTo(c.x - u * 0.55f, c.y - u * 0.18f); lineTo(c.x - u * 0.3f, c.y - u * 0.18f); lineTo(c.x, c.y - u * 0.45f)
                    lineTo(c.x, c.y + u * 0.45f); lineTo(c.x - u * 0.3f, c.y + u * 0.18f); lineTo(c.x - u * 0.55f, c.y + u * 0.18f); close()
                }, white)
                val wave = androidx.compose.ui.graphics.drawscope.Stroke(width = d * 0.045f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                for (r in listOf(0.28f, 0.5f)) drawArc(white, -50f, 100f, false, topLeft = androidx.compose.ui.geometry.Offset(c.x - u * r, c.y - u * r),
                    size = androidx.compose.ui.geometry.Size(u * r * 2f, u * r * 2f), style = wave)
            }
            MediaGlyph.BUSY -> Unit
        }
    }
}

internal fun slideAspect(editor: SlideEditor): Float = editor.presentation.slideSize.aspect

/**
 * Enter keeps the level of the line it breaks: the new line starts with as many level tabs, and
 * the caret after them — PowerPoint's new bullet at the same level. Two lines joined (Delete at a
 * line's end) join as text: the second line's level tabs go. Anything else passes as typed.
 */
internal fun keepLevel(old: String, nv: androidx.compose.ui.text.input.TextFieldValue): androidx.compose.ui.text.input.TextFieldValue {
    if (nv.text.length == old.length - 1 && nv.selection.collapsed) {
        val at = nv.selection.start
        if (at < old.length && old[at] == '\n' && old.removeRange(at, at + 1) == nv.text) {
            val tabs = com.therealityteam.aeropages.model.SlideLevels.levelAt(old, at + 1)
            if (tabs > 0) return androidx.compose.ui.text.input.TextFieldValue(nv.text.removeRange(at, at + tabs), androidx.compose.ui.text.TextRange(at))
        }
        return nv
    }
    if (nv.text.length != old.length + 1 || !nv.selection.collapsed) return nv
    val at = nv.selection.start - 1
    if (at < 0 || nv.text[at] != '\n' || nv.text.removeRange(at, at + 1) != old) return nv
    val level = com.therealityteam.aeropages.model.SlideLevels.levelAt(old, old.lastIndexOf('\n', at - 1) + 1)
    if (level == 0) return nv
    val t = nv.text.substring(0, at + 1) + "\t".repeat(level) + nv.text.substring(at + 1)
    return androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(at + 1 + level))
}

/** Single line spacing, in ems of the text: about what PowerPoint's 100% gives a sans-serif face. */
private const val SINGLE_SPACING_EMS = 1.2f

/** One line of a slide box as it shows: where it and its text start and where it ends in the box's text, its level and its marker ("" for none). */
internal class ShownLine(val start: Int, val contentStart: Int, val end: Int, val level: Int, val marker: String)

/** The lines of [raw] as a box in [style] shows them: level by level, bulleted or numbered. */
internal fun shownLines(raw: String, style: com.therealityteam.aeropages.model.SlideListStyle): List<ShownLine> {
    val starts = com.therealityteam.aeropages.model.SlideLevels.lineStarts(raw)
    val levels = IntArray(starts.size) { com.therealityteam.aeropages.model.SlideLevels.levelAt(raw, starts[it]) }
    val numbers = com.therealityteam.aeropages.model.SlideLevels.numbers(levels)
    return starts.indices.map { i ->
        val end = if (i + 1 < starts.size) starts[i + 1] - 1 else raw.length
        ShownLine(starts[i], starts[i] + levels[i], end, levels[i], com.therealityteam.aeropages.model.SlideLevels.marker(style, levels[i], numbers[i]))
    }
}

/** Whether a box's text is laid out line by line: a list, or lines at deeper levels. */
internal fun isListed(box: SlideBox): Boolean =
    box.listStyle != com.therealityteam.aeropages.model.SlideListStyle.NONE || com.therealityteam.aeropages.model.SlideLevels.hasLevels(box.text)

/** Closes each line but the last in a listed box's text: the lines are paragraphs of their own, and the caret keeps a place at a line's end. */
private const val LINE_CLOSE = '\u200B'
/** Between a marker and its line's text: an en space, half an em, as [com.therealityteam.aeropages.model.SlideLevels.GAP_EMS] counts it. */
private const val MARKER_GAP = '\u2002'

/** How many characters a line's marker takes in the listed text (with its gap). */
private fun markerLength(ln: ShownLine) = if (ln.marker.isEmpty()) 0 else ln.marker.length + 1

/** Whether a line ends in [LINE_CLOSE]: every line but the last, and an empty unmarked last line (a paragraph needs something to hold). */
private fun closes(lines: List<ShownLine>, i: Int, raw: CharSequence) = i < lines.lastIndex ||
    (lines[i].marker.isEmpty() && lines[i].contentStart == lines[i].end) ||
    // A last line ending in a line break ends in an empty piece, which needs a character to hold its place.
    (lines[i].end > lines[i].contentStart && raw[lines[i].end - 1] == com.therealityteam.aeropages.model.SlideLevels.LINE_BREAK)

/** A marker takes the colour and size of its line's first character, as PowerPoint's bullets do. */
private fun markerStyle(styled: androidx.compose.ui.text.AnnotatedString, at: Int): androidx.compose.ui.text.SpanStyle {
    var color = Color.Unspecified
    var size = androidx.compose.ui.unit.TextUnit.Unspecified
    for (r in styled.spanStyles) if (r.start <= at && at < r.end) {
        if (r.item.color != Color.Unspecified) color = r.item.color
        if (r.item.fontSize != androidx.compose.ui.unit.TextUnit.Unspecified) size = r.item.fontSize
    }
    return androidx.compose.ui.text.SpanStyle(color = color, fontSize = size)
}

/** One listed line as a paragraph: indented a step per level, its marker hanging before its text so wrapped lines line up under the text. */
/**
 * One listed line: a paragraph per piece its line breaks leave, indented a step per level; the
 * first with its marker hanging before its text, the others under the text. Each line break's
 * place is a [LINE_CLOSE] ending its piece (neither Compose nor Android breaks at U+2028).
 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendLine(styled: androidx.compose.ui.text.AnnotatedString, ln: ShownLine, close: Boolean) {
    val step = com.therealityteam.aeropages.model.SlideLevels.STEP_EMS
    val indent = ln.level * step
    val hang = if (ln.marker.isEmpty()) 0f else com.therealityteam.aeropages.model.SlideLevels.markerEms(ln.marker) + com.therealityteam.aeropages.model.SlideLevels.GAP_EMS
    var from = ln.contentStart
    var first = true
    while (true) {
        val brk = styled.text.indexOf(com.therealityteam.aeropages.model.SlideLevels.LINE_BREAK, from).takeIf { it in from until ln.end } ?: -1
        val to = if (brk >= 0) brk else ln.end
        val paragraph = androidx.compose.ui.text.ParagraphStyle(
            textIndent = if (first) androidx.compose.ui.text.style.TextIndent(firstLine = indent.em, restLine = (indent + hang).em)
                else androidx.compose.ui.text.style.TextIndent(firstLine = (indent + hang).em, restLine = (indent + hang).em),
        )
        withStyle(paragraph) {
            if (first && ln.marker.isNotEmpty()) withStyle(markerStyle(styled, ln.contentStart)) { append(ln.marker); append(MARKER_GAP) }
            append(styled.subSequence(from, to))
            if (brk >= 0 || close) append(LINE_CLOSE)
        }
        if (brk < 0) break
        from = brk + 1
        first = false
    }
}

/** [s] as a plain text shows it: its line breaks as new lines (Compose does not break at U+2028). */
internal fun shownBreaks(s: String): String = s.replace(com.therealityteam.aeropages.model.SlideLevels.LINE_BREAK, '\n')

/** A box's text with its line breaks shown as new lines — one character for one, so offsets map as they are. */
internal fun shownBreaks(t: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.AnnotatedString {
    if (t.text.indexOf(com.therealityteam.aeropages.model.SlideLevels.LINE_BREAK) < 0) return t
    // Piece by piece, so what the text carries besides its styles (a link a tap follows) stays on it.
    return androidx.compose.ui.text.buildAnnotatedString {
        var from = 0
        while (true) {
            val brk = t.text.indexOf(com.therealityteam.aeropages.model.SlideLevels.LINE_BREAK, from)
            if (brk < 0) { append(t.subSequence(from, t.length)); break }
            append(t.subSequence(from, brk)); append('\n')
            from = brk + 1
        }
    }
}

/**
 * [styled] (a box's text [raw] with its run styles) laid out as PowerPoint lays out its lines:
 * each a paragraph of its own, indented a step per level (the tabs it starts with), its bullet or
 * number hanging before its text.
 */
internal fun listedText(styled: androidx.compose.ui.text.AnnotatedString, raw: String, style: com.therealityteam.aeropages.model.SlideListStyle): androidx.compose.ui.text.AnnotatedString {
    val lines = shownLines(raw, style)
    return androidx.compose.ui.text.buildAnnotatedString { lines.forEachIndexed { i, ln -> appendLine(styled, ln, closes(lines, i, raw)) } }
}

/** The same lines one by one, for a text built paragraph by paragraph in the show. */
internal fun listedParagraphs(styled: androidx.compose.ui.text.AnnotatedString, raw: String, style: com.therealityteam.aeropages.model.SlideListStyle): List<androidx.compose.ui.text.AnnotatedString> =
    shownLines(raw, style).map { ln -> androidx.compose.ui.text.buildAnnotatedString { appendLine(styled, ln, close = ln.contentStart == ln.end && ln.marker.isEmpty()) } }

/**
 * The editing field's view of a listed box: [listedText], with the caret mapped past markers and
 * level tabs — a line's text starts where its marker ends, so what is typed at a line's start
 * goes after its tabs, and Backspace there takes a level off.
 */
internal fun listVisualTransformation(box: SlideBox): androidx.compose.ui.text.input.VisualTransformation =
    androidx.compose.ui.text.input.VisualTransformation { src ->
        val styled = TextStyling.fieldTransformation(box.paragraph).filter(src).text
        val raw = src.text
        val lines = shownLines(raw, box.listStyle)
        val transformed = listedText(styled, raw, box.listStyle)
        val tStart = IntArray(lines.size)
        var acc = 0
        lines.forEachIndexed { i, ln ->
            tStart[i] = acc
            acc += markerLength(ln) + (ln.end - ln.contentStart) + (if (closes(lines, i, raw)) 1 else 0)
        }
        fun lineOf(o: Int): Int { var line = 0; for (i in lines.indices) { if (lines[i].start <= o) line = i else break }; return line }
        val mapping = object : androidx.compose.ui.text.input.OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val o = offset.coerceIn(0, raw.length)
                val i = lineOf(o)
                val ln = lines[i]
                return (tStart[i] + markerLength(ln) + (o - ln.contentStart).coerceAtLeast(0)).coerceIn(0, transformed.length)
            }
            override fun transformedToOriginal(offset: Int): Int {
                val t = offset.coerceIn(0, transformed.length)
                var i = lines.lastIndex
                for (k in 0 until lines.lastIndex) {
                    if (t < tStart[k + 1]) { i = k; break }
                }
                val ln = lines[i]
                val local = t - tStart[i] - markerLength(ln)
                return if (local <= 0) ln.contentStart else (ln.contentStart + local).coerceAtMost(ln.end)
            }
        }
        androidx.compose.ui.text.input.TransformedText(transformed, mapping)
    }

/** Base style for a box (size + alignment + default colour, the slide master's [defaults] for
 *  what runs leave unset); per-run formatting is layered on via [TextStyling.fieldTransformation]
 *  (edit) / [TextStyling.annotatedOf] (read). */
internal fun slideBaseStyle(box: SlideBox, defaults: com.therealityteam.aeropages.model.MasterText? = null): TextStyle = TextStyle(
    color = defaults?.color?.let { Color(it) } ?: Color(0xFF1A1A1A),
    fontSize = box.defaultSizePt.sp,
    // Spacing other than single: a multiple of single spacing (1.2 em) of the box's largest text, so lines never overlap.
    lineHeight = if (box.lineSpacing == 1f) androidx.compose.ui.unit.TextUnit.Unspecified else {
        val base = box.defaultSizePt
        val largest = box.paragraph.runs.maxOfOrNull { it.format.fontSizePt ?: base } ?: base
        (box.lineSpacing * SINGLE_SPACING_EMS * largest / base).em
    },
    // Every line keeps its spacing, first and last too: each line of a list is a paragraph of its own.
    lineHeightStyle = if (box.lineSpacing == 1f) null
        else androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.None),
    fontFamily = defaults?.font?.let { TextStyling.familyOf(it) },
    fontWeight = if (defaults?.bold == true) androidx.compose.ui.text.font.FontWeight.Bold else null,
    textAlign = when (box.align) {
        TextAlign.CENTER -> androidx.compose.ui.text.style.TextAlign.Center
        TextAlign.RIGHT -> androidx.compose.ui.text.style.TextAlign.End
        TextAlign.JUSTIFY -> androidx.compose.ui.text.style.TextAlign.Justify
        else -> androidx.compose.ui.text.style.TextAlign.Start
    },
    // Chinese characters in their language's forms (Japanese text in Japanese glyphs), as Pages draws them.
    localeList = com.therealityteam.aeropages.model.HanLocale.of(box.paragraph)?.let { androidx.compose.ui.text.intl.LocaleList(it.toLanguageTag()) },
)
