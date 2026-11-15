package com.therealityteam.aeropages.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.NumberSymbols
import com.therealityteam.aeropages.model.PagePresets
import com.therealityteam.aeropages.model.PageSetup
import kotlin.math.abs

/**
 * Word's Layout tab: the Margins, Orientation, Size, Columns and Breaks galleries — each choice drawn on a small page,
 * with its measurements — and the Page Setup dialog (margins, paper, headers and footers, the document grid).
 */

/** Lengths as the reader's Word shows them: inches in the United States, centimetres elsewhere. */
internal object Lengths {
    val inches: Boolean get() = java.util.Locale.getDefault().let { it.country == "US" || (it.language == "en" && it.country.isEmpty()) }
    private val perPt: Float get() = if (inches) 1f / 72f else 2.54f / 72f
    /** [pt] in the unit, two decimals at most, with the language's decimal symbol ("2.54", "2,54", "3"). */
    fun number(pt: Float): String {
        val v = pt * perPt
        val r = Math.round(v * 100f) / 100f
        return if (abs(r - Math.round(r)) < 0.001f) Math.round(r).toString()
            else NumberSymbols.current().local(String.format(java.util.Locale.US, "%.2f", r).trimEnd('0').trimEnd('.'))
    }
    fun fromUnit(v: Float): Float = v / perPt
    /** One press of − / +: a tenth of a centimetre or of an inch. */
    val step: Float get() = if (inches) 7.2f else 72f / 25.4f
    @Composable fun label(pt: Float): String = stringResource(if (inches) R.string.unit_in else R.string.unit_cm, number(pt))
    @Composable fun unit(): String = stringResource(if (inches) R.string.unit_in else R.string.unit_cm, "").trim()
}

private val cjk: Boolean get() = java.util.Locale.getDefault().language.let { it == "zh" || it == "ja" }

/** A gallery choice: [content] (a small page) over its [title] and [detail], framed when [selected]. */
@Composable
internal fun Tile(title: String, detail: String?, selected: Boolean, onClick: () -> Unit, width: androidx.compose.ui.unit.Dp = 112.dp, content: @Composable () -> Unit) {
    val accent = LocalAccent.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(width).clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.10f) else Color.Transparent)
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else Color(0x1F000000)), RoundedCornerShape(10.dp))
            .press(onClick = onClick).semantics { contentDescription = title }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content()
        Text(title, style = AeroType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
        if (detail != null) Text(detail, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/**
 * A page [w] × [h] (points, drawn to fit the canvas) with its body — the box inside its margins — shaded and set with
 * lines of text in [columns] columns; [headerFrom] / [footerFrom]: where its header and footer stand, if shown.
 */
internal fun DrawScope.page(w: Float, h: Float, top: Float, bottom: Float, left: Float, right: Float, columns: Int = 1, accent: Color,
                           headerFrom: Float? = null, footerFrom: Float? = null, gridPt: Float? = null, gapPt: Float? = null, columnLine: Boolean = false) {
    val scale = minOf(size.width / w, size.height / h) * 0.94f
    val pw = w * scale; val ph = h * scale
    val ox = (size.width - pw) / 2f; val oy = (size.height - ph) / 2f
    drawRect(Color(0x22000000), Offset(ox + 2f, oy + 2f), Size(pw, ph))
    drawRect(Color.White, Offset(ox, oy), Size(pw, ph))
    drawRect(Color(0xFFB8BEC6), Offset(ox, oy), Size(pw, ph), style = Stroke(1f))
    val bx = ox + left * scale; val by = oy + top * scale
    val bw = (pw - (left + right) * scale).coerceAtLeast(2f); val bh = (ph - (top + bottom) * scale).coerceAtLeast(2f)
    drawRect(accent.copy(alpha = 0.08f), Offset(bx, by), Size(bw, bh))
    drawRect(accent.copy(alpha = 0.55f), Offset(bx, by), Size(bw, bh), style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 2f))))
    val cols = columns.coerceIn(1, com.therealityteam.aeropages.model.PageMetrics.MAX_COLUMNS)
    val gap = if (cols > 1) gapPt?.let { it * scale } ?: (bw * 0.06f) else 0f
    val cw = (bw - gap * (cols - 1)) / cols
    val line = (bh / 12f).coerceAtLeast(2f)
    for (c in 0 until cols) {
        var y = by + line * 0.8f
        var i = 0
        while (y + line * 0.35f < by + bh) {
            drawRect(Color(0xFFC9CED6), Offset(bx + c * (cw + gap) + 2f, y), Size((cw - 4f) * (if (i % 4 == 3) 0.6f else 1f), line * 0.35f))
            y += line; i++
        }
    }
    // The line between columns.
    if (columnLine && cols > 1) for (c in 1 until cols) {
        val x = bx + c * (cw + gap) - gap / 2f
        drawLine(Color(0xFF555555), Offset(x, by + line * 0.5f), Offset(x, by + bh - line * 0.5f), 1f)
    }
    // The line grid: a rule at every pitch down the body.
    gridPt?.takeIf { it * scale >= 1.5f }?.let { g ->
        var y = by + g * scale
        while (y < by + bh) { drawLine(accent.copy(alpha = 0.35f), Offset(bx, y), Offset(bx + bw, y), 0.8f); y += g * scale }
    }
    headerFrom?.let { drawLine(accent, Offset(bx, oy + it * scale), Offset(bx + bw, oy + it * scale), 1.5f) }
    footerFrom?.let { drawLine(accent, Offset(bx, oy + ph - it * scale), Offset(bx + bw, oy + ph - it * scale), 1.5f) }
}

/** Layout ▸ Margins: Normal, Narrow, Moderate, Wide — drawn and measured — the current one marked; Custom Margins opens Page Setup. */
@Composable
internal fun MarginsPanel(editor: DocumentEditor, onPicked: () -> Unit, onCustom: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val pg = editor.currentPage
    val current = PagePresets.marginsOf(pg, cjk)
    val accent = LocalAccent.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        PagePresets.margins(cjk).forEach { m ->
            val name = stringResource(when (m.kind) {
                PagePresets.MarginKind.NORMAL -> R.string.margins_normal
                PagePresets.MarginKind.NARROW -> R.string.narrow
                PagePresets.MarginKind.MODERATE -> R.string.moderate
                PagePresets.MarginKind.WIDE -> R.string.wide
            })
            Tile(name, stringResource(R.string.margins_desc, Lengths.label(m.top), Lengths.label(m.bottom), Lengths.label(m.left), Lengths.label(m.right)),
                current == m.kind, { editor.setMargins(m.top, m.bottom, m.left, m.right); onPicked() }, width = 150.dp) {
                Canvas(Modifier.size(56.dp, 64.dp)) { page(pg.widthPt, pg.heightPt, m.top, m.bottom, m.left, m.right, pg.columns, accent) }
            }
        }
    }
    // Word's command under the gallery.
    MenuDivider()
    FlyoutMenu(width = 280.dp) { MenuCommand(stringResource(R.string.custom_margins), { onCustom(); onPicked() }, icon = "ic_margins", checked = current == null) }
    }
}

/** Layout ▸ Orientation: portrait and landscape, each a small page. */
@Composable
internal fun OrientationPanel(editor: DocumentEditor, onPicked: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val pg = editor.currentPage
    val accent = LocalAccent.current
    val short = minOf(pg.widthPt, pg.heightPt); val long = maxOf(pg.widthPt, pg.heightPt)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(false, true).forEach { landscape ->
            Tile(stringResource(if (landscape) R.string.landscape else R.string.portrait), null, (pg.widthPt > pg.heightPt) == landscape,
                { editor.setOrientationLandscape(landscape); onPicked() }, width = 96.dp) {
                Canvas(Modifier.size(64.dp, 64.dp)) {
                    if (landscape) page(long, short, pg.marginLeftPt, pg.marginRightPt, pg.marginTopPt, pg.marginBottomPt, pg.columns, accent)
                    else page(short, long, pg.marginTopPt, pg.marginBottomPt, pg.marginLeftPt, pg.marginRightPt, pg.columns, accent)
                }
            }
        }
    }
}

/** Layout ▸ Size: the usual papers with their measurements, the current one marked; More Paper Sizes opens Page Setup. */
@Composable
internal fun PaperPanel(editor: DocumentEditor, onPicked: () -> Unit, onMore: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val pg = editor.currentPage
    val current = PagePresets.paperOf(pg)
    val landscape = pg.widthPt > pg.heightPt
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.horizontalScroll(rememberScrollState())) {
        PagePresets.PAPERS.forEach { p ->
            Tile(p.name, Lengths.number(p.widthPt) + " × " + Lengths.label(p.heightPt), current === p, {
                if (landscape) editor.setPageSize(p.heightPt, p.widthPt) else editor.setPageSize(p.widthPt, p.heightPt); onPicked()
            }, width = 104.dp) {
                // The papers to one scale, so A3 looks bigger than A5.
                Canvas(Modifier.size(48.dp, 52.dp)) {
                    val s = size.height / PagePresets.mm(420f)
                    val w = p.widthPt * s; val h = p.heightPt * s
                    val o = Offset((size.width - w) / 2f, size.height - h)
                    drawRect(Color.White, o, Size(w, h))
                    drawRect(Color(0xFFB8BEC6), o, Size(w, h), style = Stroke(1f))
                }
            }
        }
    }
    MenuDivider()
    FlyoutMenu(width = 280.dp) { MenuCommand(stringResource(R.string.more_paper_sizes), { onMore(); onPicked() }, icon = "ic_paper_size", checked = current == null) }
    }
}

/** Layout ▸ Columns: one, two or three, each a small page; More Columns opens Word's Columns dialog. */
@Composable
internal fun ColumnsPanel(editor: DocumentEditor, onPicked: () -> Unit, onMore: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val pg = editor.currentPage
    val accent = LocalAccent.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(1 to R.string.columns_one, 2 to R.string.columns_two, 3 to R.string.columns_three).forEach { (n, name) ->
            Tile(stringResource(name), null, pg.columns.coerceAtLeast(1) == n, { editor.setColumns(n); onPicked() }, width = 96.dp) {
                Canvas(Modifier.size(56.dp, 64.dp)) { page(pg.widthPt, pg.heightPt, pg.marginTopPt, pg.marginBottomPt, pg.marginLeftPt, pg.marginRightPt, n, accent) }
            }
        }
    }
    MenuDivider()
    FlyoutMenu(width = 300.dp) { MenuCommand(stringResource(R.string.more_columns), { onMore(); onPicked() }, icon = "x_pagecolumns", checked = pg.columns > 3) }
}

/** Layout ▸ Breaks: a page break, a section break starting the next page, and taking out the caret's section break. */
@Composable
internal fun BreaksPanel(editor: DocumentEditor, onPicked: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    // Word's Breaks menu: each break with what it does.
    FlyoutMenu(width = 380.dp) {
        MenuHeading(stringResource(R.string.breaks_page_heading))
        MenuCommand(stringResource(R.string.page_break), { editor.insertPageBreak(); onPicked() }, icon = "ic_breaks", detail = stringResource(R.string.breaks_page_detail))
        MenuHeading(stringResource(R.string.breaks_section_heading))
        MenuCommand(stringResource(R.string.section_break_next_page), { editor.insertSectionBreak(); onPicked() }, icon = "ic_page_width", detail = stringResource(R.string.breaks_section_detail))
        if (editor.activeParagraph.format.sectionBreak != null)
            MenuCommand(stringResource(R.string.remove_section_break), { editor.removeSectionBreak(); onPicked() }, icon = "ic_delete")
    }
}

/** A length's label, − / its value (typed in the reader's unit) / +; [onChange] in points, kept in [min]..[max]. */
@Composable
internal fun LengthRow(label: String, pt: Float, onChange: (Float) -> Unit, min: Float = 0f, max: Float = 1584f) {
    var text by remember { mutableStateOf(Lengths.number(pt)) }
    LaunchedEffect(pt) { if (text.replace(',', '.').toFloatOrNull()?.let { abs(Lengths.fromUnit(it) - pt) < 0.05f } != true) text = Lengths.number(pt) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(136.dp))
        // Office's measurement box: the number typed in place, the unit after it, − / + either side.
        SpinFrame({ d -> onChange((pt + d * Lengths.step).coerceIn(min, max)) }, description = label, canLess = pt > min, canMore = pt < max) {
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { t -> text = t; t.replace(',', '.').toFloatOrNull()?.let { v -> onChange(Lengths.fromUnit(v).coerceIn(min, max)) } },
                singleLine = true,
                textStyle = AeroType.command.copy(color = PicoTheme.colorScheme.labelPrimary, textAlign = androidx.compose.ui.text.style.TextAlign.End),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalAccent.current),
                modifier = Modifier.width(56.dp).semantics { contentDescription = label },
            )
            Text(" " + Lengths.unit(), style = AeroType.command, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.padding(end = 4.dp))
        }
    }
}

@Composable
internal fun SectionLabel(text: String) = DialogLabel(text, Modifier.padding(top = 4.dp))

/** Page Setup's tabs, in Word's order. */
enum class PageSetupTab(val label: Int) { MARGINS(R.string.margins), PAPER(R.string.ps_paper), LAYOUT(R.string.ps_layout), GRID(R.string.ps_grid) }

/**
 * Word's Page Setup: margins and orientation, paper size, the header's and footer's distances and different odd/even and
 * first pages, the document grid (lines per page); a preview of the page beside them; applied to the caret's section or
 * the whole document as one step back.
 */
@Composable
internal fun PageSetupDialog(editor: DocumentEditor, startTab: PageSetupTab, onDismiss: () -> Unit) {
    // Started from the document as it is (again if another one loads while the dialog is open).
    val doc = editor.document
    val draft = remember(doc) { mutableStateOf(editor.currentPage.copy()) }
    val d = draft.value
    fun change(edit: PageSetup.() -> Unit) { draft.value = draft.value.copy().apply(edit) }
    var tab by remember { mutableStateOf(startTab) }
    var whole by remember(doc) { mutableStateOf(editor.sectionPosition().second <= 1) }
    var oddEven by remember(doc) { mutableStateOf(doc.differentOddEven) }
    var firstPage by remember(doc) { mutableStateOf(doc.differentFirstPage) }
    val accent = LocalAccent.current
    WideDialog(title = stringResource(R.string.page_setup), onDismiss = onDismiss, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(stringResource(R.string.cancel), onDismiss)
            DialogButton(stringResource(R.string.apply), {
                val s = draft.value
                editor.applyPageSetup(whole, oddEven, firstPage) { pg ->
                    pg.widthPt = s.widthPt; pg.heightPt = s.heightPt
                    pg.marginTopPt = s.marginTopPt; pg.marginBottomPt = s.marginBottomPt; pg.marginLeftPt = s.marginLeftPt; pg.marginRightPt = s.marginRightPt
                    pg.headerPt = s.headerPt; pg.footerPt = s.footerPt; pg.linePitchPt = s.linePitchPt
                }
                onDismiss()
            }, primary = true)
        }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            DialogTabs(PageSetupTab.entries, tab, { stringResource(it.label) }, { tab = it }, width = 480.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxWidth().height(360.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                // The tab's settings scroll; what they apply to stays at the foot.
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    when (tab) {
                        PageSetupTab.MARGINS -> {
                            LengthRow(stringResource(R.string.ps_top), d.marginTopPt, { v -> change { marginTopPt = v } })
                            LengthRow(stringResource(R.string.ps_bottom), d.marginBottomPt, { v -> change { marginBottomPt = v } })
                            LengthRow(stringResource(R.string.ps_left), d.marginLeftPt, { v -> change { marginLeftPt = v } })
                            LengthRow(stringResource(R.string.ps_right), d.marginRightPt, { v -> change { marginRightPt = v } })
                            SectionLabel(stringResource(R.string.orientation))
                            ChoiceSegments(listOf(false, true), d.widthPt > d.heightPt, { stringResource(if (it) R.string.landscape else R.string.portrait) }, { landscape ->
                                change {
                                    val w = minOf(widthPt, heightPt); val h = maxOf(widthPt, heightPt)
                                    if (landscape) { widthPt = h; heightPt = w } else { widthPt = w; heightPt = h }
                                }
                            }, width = 240.dp)
                        }
                        PageSetupTab.PAPER -> {
                            val paper = PagePresets.paperOf(d)
                            FieldRow(stringResource(R.string.ps_paper_size)) {
                                ComboField(paper?.name ?: stringResource(R.string.ps_custom_size), PagePresets.PAPERS, { it.name }, paper, { p ->
                                    change { if (widthPt > heightPt) { widthPt = p.heightPt; heightPt = p.widthPt } else { widthPt = p.widthPt; heightPt = p.heightPt } }
                                }, width = 220.dp)
                            }
                            LengthRow(stringResource(R.string.ps_width), d.widthPt, { v -> change { widthPt = v } }, min = 72f)
                            LengthRow(stringResource(R.string.ps_height), d.heightPt, { v -> change { heightPt = v } }, min = 72f)
                        }
                        PageSetupTab.LAYOUT -> {
                            SectionLabel(stringResource(R.string.ps_headers))
                            CheckRow(stringResource(R.string.different_odd_even), oddEven) { oddEven = !oddEven }
                            CheckRow(stringResource(R.string.different_first_page), firstPage) { firstPage = !firstPage }
                            LengthRow(stringResource(R.string.ps_header_edge), d.headerPt, { v -> change { headerPt = v } }, max = d.marginTopPt.coerceAtLeast(0f) + 144f)
                            LengthRow(stringResource(R.string.ps_footer_edge), d.footerPt, { v -> change { footerPt = v } }, max = d.marginBottomPt.coerceAtLeast(0f) + 144f)
                        }
                        PageSetupTab.GRID -> {
                            RadioOption(stringResource(R.string.ps_grid_none), d.linePitchPt == null, { change { linePitchPt = null } })
                            // Word's default pitch: 15.6 pt (44 lines on an A4 page with its usual margins).
                            RadioOption(stringResource(R.string.ps_grid_lines), d.linePitchPt != null, { change { if (linePitchPt == null) linePitchPt = 15.6f } })
                            val lines = PagePresets.linesOf(d)
                            if (lines != null) {
                                FieldRow(stringResource(R.string.ps_lines_per_page)) {
                                    SpinBox(lines.toString(), { d -> change { linePitchPt = PagePresets.pitchFor(this, (lines + d).coerceIn(1, 200)) } },
                                        description = stringResource(R.string.ps_lines_per_page), canLess = lines > 1, canMore = lines < 200)
                                }
                                Text(stringResource(R.string.ps_pitch, String.format(java.util.Locale.getDefault(), "%.1f", d.linePitchPt ?: 0f)),
                                    style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary)
                            }
                            Text(stringResource(R.string.ps_grid_hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                        }
                    }
                }
                    FieldRow(stringResource(R.string.ps_apply_to)) {
                        ComboField(stringResource(if (whole) R.string.ps_whole_document else R.string.ps_this_section), listOf(false, true),
                            { stringResource(if (it) R.string.ps_whole_document else R.string.ps_this_section) }, whole, { whole = it }, width = 200.dp)
                    }
                }
                // The page as set: its body inside the margins, its header's and footer's places, its grid's lines.
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel(stringResource(R.string.ps_preview))
                    Box(Modifier.size(200.dp, 240.dp).clip(RoundedCornerShape(10.dp)).background(Aero.field), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(188.dp, 228.dp)) {
                            page(d.widthPt, d.heightPt, d.marginTopPt, d.marginBottomPt, d.marginLeftPt, d.marginRightPt, d.columns, accent,
                                headerFrom = d.headerPt.takeIf { tab == PageSetupTab.LAYOUT }, footerFrom = d.footerPt.takeIf { tab == PageSetupTab.LAYOUT },
                                gridPt = d.linePitchPt.takeIf { tab == PageSetupTab.GRID })
                        }
                    }
                    Text((PagePresets.paperOf(d)?.name?.let { "$it · " } ?: "") + Lengths.number(d.widthPt) + " × " + Lengths.label(d.heightPt),
                        style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                }
            }
        }
    }
}
