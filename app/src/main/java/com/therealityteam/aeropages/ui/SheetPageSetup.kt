package com.therealityteam.aeropages.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.Paper
import com.therealityteam.aeropages.model.SheetPrint
import com.therealityteam.aeropages.model.Workbook

/**
 * Excel's Page Layout tab for a sheet: the Margins, Orientation, Size and Scaling galleries — each choice drawn on a small
 * page — and Page Setup (page, margins, header and footer, sheet: print area, print titles, gridlines and headings).
 */

private fun SheetEditor.pageSize(): Pair<Float, Float> =
    if (sheet.landscape) sheet.paper.heightPt.toFloat() to sheet.paper.widthPt.toFloat() else sheet.paper.widthPt.toFloat() to sheet.paper.heightPt.toFloat()

/** Page Layout ▸ Margins: Normal, Wide, Narrow (Excel's), drawn and measured, the current one marked; Custom Margins opens Page Setup. */
@Composable
internal fun SheetMarginsPanel(editor: SheetEditor, onPicked: () -> Unit, onCustom: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val pr = editor.sheet.print
    val (w, h) = editor.pageSize()
    val accent = LocalAccent.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(SheetPrint.NORMAL to R.string.margins_normal, SheetPrint.WIDE to R.string.wide, SheetPrint.NARROW to R.string.narrow).forEach { (m, name) ->
            val current = pr.marginTop == m.marginTop && pr.marginBottom == m.marginBottom && pr.marginLeft == m.marginLeft && pr.marginRight == m.marginRight &&
                pr.marginHeader == m.marginHeader && pr.marginFooter == m.marginFooter
            Tile(stringResource(name), stringResource(R.string.sheet_margins_desc, Lengths.label(m.marginTop), Lengths.label(m.marginBottom), Lengths.label(m.marginLeft),
                Lengths.label(m.marginRight), Lengths.label(m.marginHeader), Lengths.label(m.marginFooter)), current, {
                editor.setPrint { it.copy(marginTop = m.marginTop, marginBottom = m.marginBottom, marginLeft = m.marginLeft, marginRight = m.marginRight,
                    marginHeader = m.marginHeader, marginFooter = m.marginFooter) }
                onPicked()
            }, width = 170.dp) {
                Canvas(Modifier.size(56.dp, 64.dp)) { page(w, h, m.marginTop, m.marginBottom, m.marginLeft, m.marginRight, 1, accent, headerFrom = m.marginHeader, footerFrom = m.marginFooter) }
            }
        }
        DialogButton(stringResource(R.string.custom_margins), { onCustom(); onPicked() })
    }
}

/** Page Layout ▸ Orientation: portrait and landscape. */
@Composable
internal fun SheetOrientationPanel(editor: SheetEditor, onPicked: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val pr = editor.sheet.print
    val p = editor.sheet.paper
    val accent = LocalAccent.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(false, true).forEach { landscape ->
            Tile(stringResource(if (landscape) R.string.landscape else R.string.portrait), null, editor.sheet.landscape == landscape,
                { editor.setPrint(landscape = landscape); onPicked() }, width = 96.dp) {
                Canvas(Modifier.size(64.dp, 64.dp)) {
                    if (landscape) page(p.heightPt.toFloat(), p.widthPt.toFloat(), pr.marginLeft, pr.marginRight, pr.marginTop, pr.marginBottom, 1, accent)
                    else page(p.widthPt.toFloat(), p.heightPt.toFloat(), pr.marginTop, pr.marginBottom, pr.marginLeft, pr.marginRight, 1, accent)
                }
            }
        }
    }
}

/** Page Layout ▸ Size: the papers to one scale with their measurements, the current one marked. */
@Composable
internal fun SheetPaperPanel(editor: SheetEditor, onPicked: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Paper.entries.forEach { p ->
            Tile(p.label, Lengths.number(p.widthPt.toFloat()) + " × " + Lengths.label(p.heightPt.toFloat()), editor.sheet.paper == p,
                { editor.setPrint(paper = p); onPicked() }, width = 104.dp) {
                Canvas(Modifier.size(48.dp, 52.dp)) {
                    val s = size.height / 1191f
                    val pw = p.widthPt * s; val ph = p.heightPt * s
                    val o = Offset((size.width - pw) / 2f, size.height - ph)
                    drawRect(Color.White, o, Size(pw, ph))
                    drawRect(Color(0xFFB8BEC6), o, Size(pw, ph), style = Stroke(1f))
                }
            }
        }
    }
}

/** Page Layout ▸ Scaling: Excel's print scaling choices, the current one marked, and the scale stepped by 10 %. */
@Composable
internal fun SheetScalePanel(editor: SheetEditor, onPicked: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val pr = editor.sheet.print
    // Excel's Scaling menu (Print Preview's): No Scaling, Fit Sheet / All Columns / All Rows on One Page, then the scale.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlyoutMenu(width = 320.dp) {
            MenuCommand(stringResource(R.string.no_scaling), { editor.setPrint { it.copy(scale = 100, fitWidth = null, fitHeight = null) }; onPicked() }, icon = "ic_actual_size",
                checked = !pr.fits && pr.scale == 100)
            MenuCommand(stringResource(R.string.fit_sheet_one_page), { editor.setPrint { it.copy(fitWidth = 1, fitHeight = 1) }; onPicked() }, icon = "ic_fit_page",
                checked = pr.fitWidth == 1 && pr.fitHeight == 1)
            MenuCommand(stringResource(R.string.fit_columns_one_page), { editor.setPrint { it.copy(fitWidth = 1, fitHeight = 0) }; onPicked() }, icon = "ic_width_decrease",
                checked = pr.fitWidth == 1 && pr.fitHeight == 0)
            MenuCommand(stringResource(R.string.fit_rows_one_page), { editor.setPrint { it.copy(fitWidth = 0, fitHeight = 1) }; onPicked() }, icon = "ic_height_decrease",
                checked = pr.fitWidth == 0 && pr.fitHeight == 1)
        }
        MenuDivider()
        ScaleRow(editor)
    }
}

/** The scale: − / its percentage / + by 10 % (Excel's 10–400 %), turning any fitting off. */
@Composable
private fun ScaleRow(editor: SheetEditor) {
    val pr = editor.sheet.print
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.scaling), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(96.dp))
        SpinBox("${pr.scale}%", { d -> editor.setPrint { it.copy(scale = (it.scale + 10 * d).coerceIn(10, 400), fitWidth = null, fitHeight = null) } },
            description = stringResource(R.string.scaling), canLess = pr.scale > 10, canMore = pr.scale < 400, dim = pr.fits)
    }
}

/** A header's or footer's left, centre and right parts as they print on the first page, on a strip of paper (Excel's preview). */
@Composable
private fun HeaderPreview(parts: List<String>, sheet: String) {
    Box(Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(6.dp)).background(Color.White)
        .then(Modifier.padding(horizontal = 10.dp))) {
        listOf(Alignment.CenterStart, Alignment.Center, Alignment.CenterEnd).forEachIndexed { i, at ->
            val text = SheetPrint.fill(parts.getOrElse(i) { "" }, 1, 1, sheet, "")
            if (text.isNotEmpty()) Text(text, style = AeroType.label, color = Color(0xFF1A1A1A), maxLines = 1, modifier = Modifier.align(at))
        }
    }
}

/** Page Setup's tabs, in Excel's order. */
enum class SheetSetupTab(val label: Int) { PAGE(R.string.sp_page), MARGINS(R.string.margins), HEADER_FOOTER(R.string.sp_header_footer), SHEET(R.string.sp_sheet) }

/**
 * Excel's Page Setup for the sheet: orientation, scaling (a percentage, or so many pages wide and tall) and paper; the six
 * margins and centring; the header's and footer's left, centre and right parts from Excel's presets; the print area,
 * the rows and columns repeated on every page, and whether gridlines and headings print — with a preview of the page;
 * applied as one step back.
 */
@Composable
internal fun SheetPageSetupDialog(editor: SheetEditor, startTab: SheetSetupTab, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(startTab) }
    // Started from the sheet as it is (again if a workbook loads while the dialog is open).
    val book = editor.workbook
    var landscape by remember(book) { mutableStateOf(editor.sheet.landscape) }
    var paper by remember(book) { mutableStateOf(editor.sheet.paper) }
    var pr by remember(book) { mutableStateOf(editor.sheet.print) }
    val accent = LocalAccent.current
    val pageNo = stringResource(R.string.sp_page_x); val pageOf = stringResource(R.string.sp_page_x_of_y)
    WideDialog(title = stringResource(R.string.page_setup), onDismiss = onDismiss, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(stringResource(R.string.cancel), onDismiss)
            DialogButton(stringResource(R.string.apply), { editor.setPrint(landscape, paper) { pr }; onDismiss() }, primary = true)
        }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            DialogTabs(SheetSetupTab.entries, tab, { stringResource(it.label) }, { tab = it }, width = 480.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxWidth().height(384.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    when (tab) {
                        SheetSetupTab.PAGE -> {
                            SectionLabel(stringResource(R.string.orientation))
                            ChoiceSegments(listOf(false, true), landscape, { stringResource(if (it) R.string.landscape else R.string.portrait) }, { landscape = it }, width = 240.dp)
                            // Excel's Scaling: Adjust to N % or Fit to W pages wide by T tall, as option buttons.
                            SectionLabel(stringResource(R.string.scaling))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioOption(stringResource(R.string.scale_adjust_to), !pr.fits, { pr = pr.copy(fitWidth = null, fitHeight = null) }, Modifier.width(140.dp))
                                SpinBox("${pr.scale}%", { d -> pr = pr.copy(scale = (pr.scale + 10 * d).coerceIn(10, 400), fitWidth = null, fitHeight = null) },
                                    description = stringResource(R.string.scaling), canLess = pr.scale > 10, canMore = pr.scale < 400, dim = pr.fits)
                            }
                            val wide = pr.fitWidth ?: 1; val tall = pr.fitHeight ?: 1
                            fun pages(n: Int) = if (n == 0) "–" else n.toString()
                            RadioOption(stringResource(R.string.fit_pages, pages(wide), pages(tall)), pr.fits, { pr = pr.copy(fitWidth = wide, fitHeight = tall) })
                            if (pr.fits) {
                                for ((label, value, set) in listOf<Triple<Int, Int, (Int) -> Unit>>(
                                    Triple(R.string.fit_wide, wide) { v -> pr = pr.copy(fitWidth = v) },
                                    Triple(R.string.fit_tall, tall) { v -> pr = pr.copy(fitHeight = v) },
                                )) {
                                    Row(Modifier.padding(start = 34.dp)) {
                                        FieldRow(stringResource(label), labelWidth = 96.dp) {
                                            SpinBox(if (value == 0) stringResource(R.string.automatic) else value.toString(), { d -> set((value + d).coerceIn(0, 99)) },
                                                description = stringResource(label), canLess = value > 0, canMore = value < 99, valueWidth = 56.dp)
                                        }
                                    }
                                }
                            }
                            FieldRow(stringResource(R.string.ps_paper_size), labelWidth = 96.dp) {
                                ComboField(paper.label, Paper.entries.toList(), { it.label }, paper, { paper = it }, width = 220.dp)
                            }
                        }
                        SheetSetupTab.MARGINS -> {
                            LengthRow(stringResource(R.string.ps_top), pr.marginTop, { pr = pr.copy(marginTop = it) })
                            LengthRow(stringResource(R.string.ps_bottom), pr.marginBottom, { pr = pr.copy(marginBottom = it) })
                            LengthRow(stringResource(R.string.ps_left), pr.marginLeft, { pr = pr.copy(marginLeft = it) })
                            LengthRow(stringResource(R.string.ps_right), pr.marginRight, { pr = pr.copy(marginRight = it) })
                            LengthRow(stringResource(R.string.margin_header), pr.marginHeader, { pr = pr.copy(marginHeader = it) })
                            LengthRow(stringResource(R.string.margin_footer), pr.marginFooter, { pr = pr.copy(marginFooter = it) })
                            SectionLabel(stringResource(R.string.center_on_page))
                            CheckRow(stringResource(R.string.center_h), pr.centerH) { pr = pr.copy(centerH = !pr.centerH) }
                            CheckRow(stringResource(R.string.center_v), pr.centerV) { pr = pr.copy(centerV = !pr.centerV) }
                        }
                        SheetSetupTab.HEADER_FOOTER -> {
                            // Excel's presets: none, the page number, page N of M, the sheet's name, the file's name — centred.
                            val presets = listOf("" to stringResource(R.string.hf_none), pageNo to pageNo.replace("&P", "1"), pageOf to pageOf.replace("&P", "1").replace("&N", "?"),
                                "&A" to stringResource(R.string.sp_sheet_name), "&F" to stringResource(R.string.sp_file_name))
                            for ((label, header) in listOf(R.string.hf_header to true, R.string.hf_footer to false)) {
                                SectionLabel(stringResource(label))
                                val current = if (header) pr.header else pr.footer
                                // Excel's Header / Footer drop-downs.
                                val picked = presets.firstOrNull { (code, _) -> current == listOf("", code, "") || (code.isEmpty() && current.all { it.isEmpty() }) }
                                ComboField(picked?.second ?: stringResource(R.string.hf_custom), presets, { it.second }, picked,
                                    { (code, _) -> val parts = listOf("", code, ""); pr = if (header) pr.copy(header = parts) else pr.copy(footer = parts) }, width = 300.dp)
                                HeaderPreview(current, editor.sheet.name)
                            }
                        }
                        SheetSetupTab.SHEET -> {
                            val area = editor.sheet.printArea
                            SectionLabel(stringResource(R.string.print_area))
                            Text(area?.let { "${Workbook.colLabel(it.c1)}${it.r1 + 1}:${Workbook.colLabel(it.c2)}${it.r2 + 1}" } ?: stringResource(R.string.range_none),
                                style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
                            SectionLabel(stringResource(R.string.print_titles))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.rows_repeat_top), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(150.dp))
                                Text(pr.titleRows?.let { stringResource(R.string.rows_range, it.first + 1, it.last + 1) } ?: stringResource(R.string.range_none),
                                    style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(110.dp))
                                DialogButton(stringResource(R.string.use_selection), { pr = pr.copy(titleRows = editor.rangeRowMin()..editor.rangeRowMax()) }, small = true)
                                if (pr.titleRows != null) TitleIconButton("ic_close", stringResource(R.string.clear), { pr = pr.copy(titleRows = null) }, size = 32.dp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.cols_repeat_left), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(150.dp))
                                Text(pr.titleCols?.let { stringResource(R.string.cols_range, Workbook.colLabel(it.first), Workbook.colLabel(it.last)) } ?: stringResource(R.string.range_none),
                                    style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(110.dp))
                                DialogButton(stringResource(R.string.use_selection), { pr = pr.copy(titleCols = editor.rangeColMin()..editor.rangeColMax()) }, small = true)
                                if (pr.titleCols != null) TitleIconButton("ic_close", stringResource(R.string.clear), { pr = pr.copy(titleCols = null) }, size = 32.dp)
                            }
                            // Print beside Excel's Page order, and under that the Comments and Cell errors rows — all in view, no scrolling.
                            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SectionLabel(stringResource(R.string.print_word))
                                    // Excel's order: Gridlines, Black and white, Draft quality, Row and column headings.
                                    CheckOption(stringResource(R.string.gridlines), pr.gridlines, { pr = pr.copy(gridlines = !pr.gridlines) }, small = true)
                                    CheckOption(stringResource(R.string.print_black_white), pr.blackAndWhite, { pr = pr.copy(blackAndWhite = !pr.blackAndWhite) }, small = true)
                                    CheckOption(stringResource(R.string.print_draft), pr.draft, { pr = pr.copy(draft = !pr.draft) }, small = true)
                                    CheckOption(stringResource(R.string.headings), pr.headings, { pr = pr.copy(headings = !pr.headings) }, small = true)
                                }
                                // Excel's Page order, with its picture of the order the pages go in.
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SectionLabel(stringResource(R.string.page_order))
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            RadioOption(stringResource(R.string.page_order_down_over), !pr.overThenDown, { pr = pr.copy(overThenDown = false) })
                                            RadioOption(stringResource(R.string.page_order_over_down), pr.overThenDown, { pr = pr.copy(overThenDown = true) })
                                        }
                                        val ink = PicoTheme.colorScheme.labelTertiary
                                        Canvas(Modifier.size(56.dp, 48.dp)) {
                                            val w = size.width / 2f - 4.dp.toPx(); val h = size.height / 2f - 4.dp.toPx()
                                            val corners = listOf(Offset(0f, 0f), Offset(size.width - w, 0f), Offset(0f, size.height - h), Offset(size.width - w, size.height - h))
                                            for (c in corners) drawRect(Color.White, c, androidx.compose.ui.geometry.Size(w, h))
                                            for (c in corners) drawRect(ink, c, androidx.compose.ui.geometry.Size(w, h), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                                            val mid = corners.map { Offset(it.x + w / 2, it.y + h / 2) }
                                            val order = if (pr.overThenDown) listOf(0, 1, 2, 3) else listOf(0, 2, 1, 3)
                                            val path = androidx.compose.ui.graphics.Path().apply { moveTo(mid[order[0]].x, mid[order[0]].y); for (k in order.drop(1)) lineTo(mid[k].x, mid[k].y) }
                                            drawPath(path, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                                            drawCircle(accent, 3.dp.toPx(), mid[order.last()])
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    // Excel's Comments: not printed, at the end of the sheet, or as shown beside their cells. The two
                                    // rows' labels share a width that fits the longer one in this language.
                                    val noteLabelW = labelWidth(stringResource(R.string.print_comments), stringResource(R.string.print_cell_errors), min = 64.dp, max = 150.dp)
                                    FieldRow(stringResource(R.string.print_comments), labelWidth = noteLabelW) {
                                        val kinds = com.therealityteam.aeropages.model.NotePrint.entries
                                        @Composable fun name(k: com.therealityteam.aeropages.model.NotePrint) = stringResource(when (k) {
                                            com.therealityteam.aeropages.model.NotePrint.NONE -> R.string.print_comments_none
                                            com.therealityteam.aeropages.model.NotePrint.AT_END -> R.string.print_comments_at_end
                                            com.therealityteam.aeropages.model.NotePrint.AS_DISPLAYED -> R.string.print_comments_as_displayed
                                        })
                                        ComboField(name(pr.comments), kinds, { name(it) }, pr.comments, { pr = pr.copy(comments = it) }, width = 180.dp)
                                    }
                                    // Excel's Cell errors as: displayed, <blank>, "--" or "#N/A".
                                    FieldRow(stringResource(R.string.print_cell_errors), labelWidth = noteLabelW) {
                                        val kinds = com.therealityteam.aeropages.model.ErrorPrint.entries
                                        @Composable fun name(k: com.therealityteam.aeropages.model.ErrorPrint) = when (k) {
                                            com.therealityteam.aeropages.model.ErrorPrint.DISPLAYED -> stringResource(R.string.print_errors_displayed)
                                            com.therealityteam.aeropages.model.ErrorPrint.BLANK -> stringResource(R.string.print_errors_blank)
                                            else -> k.text.orEmpty()
                                        }
                                        ComboField(name(pr.errors), kinds, { name(it) }, pr.errors, { pr = pr.copy(errors = it) }, width = 180.dp)
                                    }
                                }
                            }
                        }
                    }
                }
                // The page as set: its margins, its header's and footer's places, the cells centred where they are.
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel(stringResource(R.string.ps_preview))
                    Box(Modifier.size(200.dp, 240.dp).clip(RoundedCornerShape(10.dp)).background(Aero.field), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(188.dp, 228.dp)) {
                            val w = (if (landscape) paper.heightPt else paper.widthPt).toFloat(); val h = (if (landscape) paper.widthPt else paper.heightPt).toFloat()
                            page(w, h, pr.marginTop, pr.marginBottom, pr.marginLeft, pr.marginRight, 1, accent,
                                headerFrom = pr.marginHeader.takeIf { tab == SheetSetupTab.MARGINS || tab == SheetSetupTab.HEADER_FOOTER },
                                footerFrom = pr.marginFooter.takeIf { tab == SheetSetupTab.MARGINS || tab == SheetSetupTab.HEADER_FOOTER })
                        }
                    }
                    Text(paper.label + " · " + (if (pr.fits) stringResource(R.string.fit_pages, pr.fitWidth?.takeIf { it > 0 }?.toString() ?: "–", pr.fitHeight?.takeIf { it > 0 }?.toString() ?: "–") else "${pr.scale}%"),
                        style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                }
            }
        }
    }
}
