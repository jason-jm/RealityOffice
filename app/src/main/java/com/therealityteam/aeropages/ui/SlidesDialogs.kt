package com.therealityteam.aeropages.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.windows.AlertDialog
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.NumberSymbols
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.TextAlign
import androidx.compose.ui.res.stringResource
import com.therealityteam.aeropages.R
/** PowerPoint's Comments pane for the current slide: each comment a card (Delete in it), a box to start a new one under them. */
@Composable
internal fun CommentsPanel(editor: SlideEditor, onClose: () -> Unit, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    var draft by remember { mutableStateOf("") }
    Column(
        modifier = modifier.fillMaxHeight().paneSurface(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PaneHeader(stringResource(R.string.comment), onClose)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editor.slide.comments.isEmpty()) PaneEmpty("ic_comment", stringResource(R.string.msg_no_comments_2))
            editor.slide.comments.forEachIndexed { i, c ->
                SlideCommentCard(editor, i, c, current = i == editor.pickedComment, onPick = { editor.pickedComment = i })
            }
        }
        InputBox(draft, { draft = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.comment_placeholder), minLines = 2, maxLines = 6)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DialogButton(stringResource(R.string.add_comment_btn), { if (draft.isNotBlank()) { editor.addComment(draft); draft = "" } },
                primary = draft.isNotBlank(), enabled = draft.isNotBlank(), small = true)
        }
    }
}


/**
 * One comment thread as PowerPoint's Comments pane shows it: the author's avatar, name and when, the text, its replies
 * under it, a ⋯ menu (resolve or reopen, delete the thread) and, on the current thread, a box to reply. A resolved thread dims.
 */
@Composable
private fun SlideCommentCard(editor: SlideEditor, index: Int, c: com.therealityteam.aeropages.model.SlideComment, current: Boolean, onPick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var reply by remember(index) { mutableStateOf("") }
    PaneCard(selected = current, onClick = onPick, modifier = Modifier.alpha(if (c.resolved) 0.6f else 1f)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar(c.author.ifBlank { "?" })
                Column(Modifier.weight(1f)) {
                    Text(c.author.ifBlank { stringResource(R.string.unknown_author) }, style = AeroType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
                    val whenText = if (c.dateMs > 0) slideCommentWhen(c.dateMs) else ""
                    val state = if (c.resolved) stringResource(R.string.resolved) else ""
                    if (whenText.isNotEmpty() || state.isNotEmpty())
                        Text(listOf(whenText, state).filter { it.isNotEmpty() }.joinToString(" · "), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
                }
                Box {
                    TitleIconButton("ic_more", stringResource(R.string.more_actions), { menu = true }, size = 32.dp)
                    if (menu) AnchoredFlyout(onDismiss = { menu = false }, alignEnd = true) {
                        FlyoutMenu(width = 220.dp) {
                            MenuCommand(stringResource(if (c.resolved) R.string.reopen else R.string.resolve), { menu = false; editor.resolveComment(index, !c.resolved) }, icon = "ic_check")
                            MenuDivider()
                            MenuCommand(stringResource(R.string.delete_thread), { menu = false; editor.deleteComment(index) }, icon = "ic_delete")
                        }
                    }
                }
            }
            Text(c.text, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.padding(start = 36.dp))
            c.replies.forEach { r ->
                Row(Modifier.padding(start = 36.dp, top = 4.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Avatar(r.author.ifBlank { "?" }, size = 24.dp)
                    Column(Modifier.weight(1f)) {
                        Text(r.author.ifBlank { stringResource(R.string.unknown_author) } + (if (r.dateMs > 0) " · " + slideCommentWhen(r.dateMs) else ""),
                            style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
                        Text(r.text, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
                    }
                }
            }
            if (current && !c.resolved) Row(Modifier.padding(start = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InputBox(reply, { reply = it }, Modifier.weight(1f), placeholder = stringResource(R.string.reply), maxLines = 4)
                DialogButton(stringResource(R.string.reply), { editor.replyToComment(index, reply); reply = "" }, primary = reply.isNotBlank(), enabled = reply.isNotBlank(), small = true)
            }
        }
    }
}

private fun slideCommentWhen(ms: Long): String = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

/**
 * 幻灯片母版: the deck-wide background and the title / body text defaults every slide inherits.
 * Each chip is an undoable editor command, so the canvas behind the dialog follows along.
 */
@Composable
internal fun SlideMasterDialog(editor: SlideEditor, onDismiss: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val m = editor.master
    val fonts = listOf("Calibri", "Arial", "Times New Roman", "Georgia", "Courier New", "微软雅黑", "宋体", "黑体")

    @Composable
    fun MasterRow(label: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) =
        FieldRow(label, labelWidth = labelWidth(stringResource(R.string.master_background), stringResource(R.string.font), stringResource(R.string.font_color),
            min = 104.dp, max = 140.dp), content = content)

    @Composable
    fun ColorRow(label: String, current: Long?, onPick: (Long?) -> Unit, @Suppress("UNUSED_PARAMETER") colors: List<Long>) {
        MasterRow(label) { ColorField(current, onPick, stringResource(R.string.master_default), label, width = 160.dp) }
    }

    AeroDialog(title = stringResource(R.string.slide_master), onDismiss = onDismiss, width = 600.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.master_reset), editor::resetMaster)
                DialogButton(stringResource(R.string.done_button), onDismiss, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(600.dp)) {
                Text(stringResource(R.string.msg_master_desc), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                ColorRow(stringResource(R.string.master_background), m.background, editor::setMasterBackground, SLIDE_BG_COLORS)
                HorizontalDivider()
                DialogLabel(stringResource(R.string.master_title_style))
                val byDefault = stringResource(R.string.master_default)
                MasterRow(stringResource(R.string.font)) {
                    ComboField(m.titleFont ?: byDefault, listOf<String?>(null) + fonts, { it ?: byDefault }, m.titleFont, { editor.setMasterTitleStyle(font = it) }, width = 190.dp)
                    val sizes = listOf(32f, 36f, 40f, 44f, 48f, 54f)
                    ComboField(m.titleSizePt?.toInt()?.toString() ?: byDefault, sizes, { it.toInt().toString() }, m.titleSizePt, { editor.setMasterTitleStyle(sizePt = it) }, width = 120.dp)
                }
                // Bold under the font, not at the end of its row: the row is full in languages with long names (Полужирный).
                MasterRow("") {
                    CheckOption(stringResource(R.string.bold), m.titleBold == true, { editor.setMasterTitleStyle(bold = if (m.titleBold == true) null else true) })
                }
                ColorRow(stringResource(R.string.font_color), m.titleColor, { editor.setMasterTitleStyle(color = it) }, TextStyling.TEXT_COLORS)
                HorizontalDivider()
                DialogLabel(stringResource(R.string.master_body_style))
                MasterRow(stringResource(R.string.font)) {
                    ComboField(m.bodyFont ?: byDefault, listOf<String?>(null) + fonts, { it ?: byDefault }, m.bodyFont, { editor.setMasterBodyStyle(font = it) }, width = 190.dp)
                    val sizes = listOf(16f, 18f, 20f, 22f, 24f, 28f)
                    ComboField(m.bodySizePt?.toInt()?.toString() ?: byDefault, sizes, { it.toInt().toString() }, m.bodySizePt, { editor.setMasterBodyStyle(sizePt = it) }, width = 120.dp)
                }
                ColorRow(stringResource(R.string.font_color), m.bodyColor, { editor.setMasterBodyStyle(color = it) }, TextStyling.TEXT_COLORS)
            }
        }
}

/** Insert Table: the table grid Pages uses (point at a size, click to insert a table that size). */
@Composable
internal fun TableInsertDialog(onDismiss: () -> Unit, onInsert: (Int, Int) -> Unit) {
    AeroDialog(title = stringResource(R.string.insert_table), onDismiss = onDismiss, width = 300.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
    }) {
        TableGridPicker { r, c -> onInsert(r, c) }
    }
}

@Composable
internal fun ChartTypeDialog(onDismiss: () -> Unit, onPick: (com.therealityteam.aeropages.model.ChartType) -> Unit) {
    // PowerPoint's Insert Chart: the types in a list, the picked one previewed on its sample data; Insert (or a
    // second click on the picked type) puts it on the slide.
    val types = CHART_TYPES
    var picked by remember { mutableStateOf(types.first().first) }
    val sample = remember(picked) { com.therealityteam.aeropages.model.SlideChart.sample(picked) }
    AeroDialog(title = stringResource(R.string.insert_chart), onDismiss = onDismiss, width = 640.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.insert), { onPick(picked) }, primary = true)
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.width(640.dp)) {
            ListBox(Modifier.width(184.dp), height = 300.dp) {
                types.forEach { (type, lbl) ->
                    val on = type == picked
                    ListRow(on, { if (on) onPick(type) else picked = type }) {
                        RibbonGlyph(lbl.second, if (on) LocalAccent.current else PicoTheme.colorScheme.labelSecondary, 20.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(lbl.first, style = if (on) AeroType.body.copy(fontWeight = FontWeight.SemiBold) else AeroType.body,
                            color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(types.first { it.first == picked }.second.first, style = AeroType.heading, color = PicoTheme.colorScheme.labelPrimary)
                // The preview on paper, as it will look on a white slide.
                Box(Modifier.fillMaxWidth().height(264.dp).clip(RoundedCornerShape(10.dp)).background(Color.White)
                    .border(0.5.dp, PicoTheme.colorScheme.dividerLine, RoundedCornerShape(10.dp)).padding(16.dp)) {
                    ChartBody(sample, Modifier.fillMaxSize(), ink = Color(0xFF404040), surface = Color.White)
                }
            }
        }
    }
}

/**
 * A document's chart data: the category names, series names and values in a grid, each change
 * made through [edit] (one undo step each), a category or series added at the end.
 */
@Composable
internal fun ChartDataGridDialog(chart: com.therealityteam.aeropages.model.SlideChart, revision: Int, edit: ((com.therealityteam.aeropages.model.SlideChart) -> Unit) -> Unit, onDismiss: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = revision
    val accent = LocalAccent.current
    // Office's chart data sheet: a small spreadsheet on paper — series across the top, categories down the side, the
    // header cells grey like a sheet's headings, values right-aligned.
    @Composable
    fun cell(text: String, key: Any, width: androidx.compose.ui.unit.Dp, head: Boolean, numeric: Boolean, onChange: (String) -> Unit) {
        var txt by remember(key) { mutableStateOf(text) }
        BasicTextField(
            value = txt, onValueChange = { txt = it; onChange(it) }, singleLine = true,
            textStyle = AeroType.body.copy(color = DATA_INK, textAlign = if (numeric) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
                fontWeight = if (head) FontWeight.SemiBold else FontWeight.Normal),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
            modifier = Modifier.width(width).fillMaxHeight().background(if (head) DATA_HEAD else Color.White)
                .border(0.5.dp, DATA_LINE).padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
    AeroDialog(title = stringResource(R.string.edit_chart_data), onDismiss = onDismiss, width = 560.dp, buttons = { DialogButton(stringResource(R.string.done_button), onDismiss, primary = true) }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(560.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(max = 340.dp).clip(RoundedCornerShape(8.dp)).background(Color.White)
                .border(1.dp, DATA_LINE, RoundedCornerShape(8.dp)).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                Column {
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(Modifier.width(120.dp).fillMaxHeight().background(DATA_HEAD).border(0.5.dp, DATA_LINE))
                        chart.series.forEachIndexed { si, s -> cell(s.name, "s$si", 104.dp, head = true, numeric = false) { t -> edit { c -> c.series.getOrNull(si)?.name = t } } }
                    }
                    chart.categories.forEachIndexed { ci, cat ->
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            cell(cat, "c$ci", 120.dp, head = true, numeric = false) { t -> edit { c -> if (ci in c.categories.indices) c.categories[ci] = t } }
                            chart.series.forEachIndexed { si, s ->
                                val v = s.values.getOrElse(ci) { 0f }
                                cell(if (v == v.toLong().toFloat()) v.toLong().toString() else NumberSymbols.current().local(v.toString()), "v$si:$ci", 104.dp, head = false, numeric = true) { t ->
                                    t.trim().replace(',', '.').toFloatOrNull()?.let { value -> edit { c -> c.series.getOrNull(si)?.values?.let { vs -> while (vs.size <= ci) vs.add(0f); vs[ci] = value } } }
                                }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RibbonSmallButton("ic_plus", stringResource(R.string.add_category_btn), {
                    edit { c -> c.categories.add(Strings.get(R.string.label_category_n, c.categories.size + 1)); c.series.forEach { it.values.add(0f) } }
                })
                RibbonSmallButton("ic_plus", stringResource(R.string.add_series), {
                    edit { c -> c.series.add(com.therealityteam.aeropages.model.ChartSeries(Strings.get(R.string.series_named, c.series.size + 1), MutableList(c.categories.size) { 0f })) }
                })
            }
        }
    }
}

/** The chart data sheet's paper: heading cells, gridlines and ink (Excel's own). */
private val DATA_HEAD = Color(0xFFF3F3F3)
private val DATA_LINE = Color(0xFFD4D4D4)
private val DATA_INK = Color(0xFF1A1A1A)

/** Edit Data for the selected chart on a slide: the same data sheet as a document's chart. */
@Composable
internal fun ChartDataDialog(editor: SlideEditor, onDismiss: () -> Unit) {
    val chart = editor.selectedChart ?: return
    ChartDataGridDialog(chart, editor.revision, edit = editor::editSelectedChart, onDismiss = onDismiss)
}

/**
 * Office's Chart Elements for one chart (Slides and Sheets share it): the chart title, the category
 * and value axis titles (charts with axes only) and where the legend goes, if anywhere.
 */
@Composable
internal fun ChartElementsDialog(
    chart: com.therealityteam.aeropages.model.SlideChart,
    onDismiss: () -> Unit,
    onApply: (title: String, catAxis: String, valAxis: String, showLegend: Boolean, pos: com.therealityteam.aeropages.model.LegendPos,
              axis: com.therealityteam.aeropages.model.AxisFormat) -> Unit,
) {
    var title by remember(chart) { mutableStateOf(chart.title) }
    var cat by remember(chart) { mutableStateOf(chart.catAxisTitle) }
    var value by remember(chart) { mutableStateOf(chart.valAxisTitle) }
    var legend by remember(chart) { mutableStateOf(chart.showLegend) }
    var pos by remember(chart) { mutableStateOf(chart.legendPos) }
    // Format Axis: bounds and unit as typed (empty = Auto), the number format as a code.
    fun typed(v: Double?) = v?.let { java.math.BigDecimal(it).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString() } ?: ""
    var minText by remember(chart) { mutableStateOf(typed(chart.valMin)) }
    var maxText by remember(chart) { mutableStateOf(typed(chart.valMax)) }
    var majorText by remember(chart) { mutableStateOf(typed(chart.valMajor)) }
    var format by remember(chart) { mutableStateOf(chart.valFormat) }
    var problem by remember(chart) { mutableStateOf<com.therealityteam.aeropages.model.AxisFormat.Problem?>(null) }
    @Composable
    fun field(label: String, text: String, onChange: (String) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            InputBox(text, onChange, Modifier.fillMaxWidth(), description = label)
        }
    }
    AeroDialog(title = stringResource(R.string.chart_elements), onDismiss = onDismiss, width = 440.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.apply), {
                    val (axis, bad) = com.therealityteam.aeropages.model.AxisFormat.parse(minText, maxText, majorText, format)
                    if (axis != null) onApply(title.trim(), cat.trim(), value.trim(), legend, pos, axis) else problem = bad
                }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(440.dp)) {
                field(stringResource(R.string.chart_title_label), title) { title = it }
                if (chart.hasAxes) {
                    field(stringResource(R.string.category_axis_title), cat) { cat = it }
                    field(stringResource(R.string.value_axis_title), value) { value = it }
                    // Office's Format Axis for the value axis: fixed bounds and unit (blank = Auto), number format.
                    Text(stringResource(R.string.value_axis), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            Triple(R.string.axis_minimum, minText) { t: String -> minText = t; problem = null },
                            Triple(R.string.axis_maximum, maxText) { t: String -> maxText = t; problem = null },
                            Triple(R.string.axis_major_unit, majorText) { t: String -> majorText = t; problem = null },
                        ).forEach { (label, text, onChange) ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val name = stringResource(label)
                                Text(name, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                                val auto = stringResource(R.string.axis_auto)
                                InputBox(text, onChange, Modifier.fillMaxWidth(), placeholder = auto, description = name)
                            }
                        }
                    }
                    Text(stringResource(R.string.number_format), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                    // The common codes; a code read from a file that is none of them stays offered as it is.
                    val codes = listOf(com.therealityteam.aeropages.model.SlideChart.GENERAL, "0", "0.0", "0.00", "#,##0", "0%", "0.0%", "\"¥\"#,##0", "\"$\"#,##0.00")
                    val general = stringResource(R.string.general)
                    val shownOf: (String) -> String = { code -> if (code == com.therealityteam.aeropages.model.SlideChart.GENERAL) general else com.therealityteam.aeropages.model.ChartStyle.format(12345.678, code) }
                    ComboField(shownOf(format), if (format in codes) codes else codes + format, { shownOf(it) }, format, { format = it }, width = 200.dp)
                    problem?.let { p ->
                        Text(stringResource(when (p) {
                            com.therealityteam.aeropages.model.AxisFormat.Problem.NOT_A_NUMBER -> R.string.err_axis_number
                            com.therealityteam.aeropages.model.AxisFormat.Problem.BOUNDS -> R.string.err_axis_bounds
                            com.therealityteam.aeropages.model.AxisFormat.Problem.UNIT -> R.string.err_axis_unit
                        }), style = AeroType.caption, color = PicoTheme.colorScheme.error)
                    }
                }
                Text(stringResource(R.string.chart_legend), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                // PowerPoint's Legend: None, or where it stands.
                val places = listOf<Pair<com.therealityteam.aeropages.model.LegendPos?, Int>>(
                    null to R.string.none,
                    com.therealityteam.aeropages.model.LegendPos.BOTTOM to R.string.legend_bottom,
                    com.therealityteam.aeropages.model.LegendPos.TOP to R.string.legend_top,
                    com.therealityteam.aeropages.model.LegendPos.LEFT to R.string.legend_left,
                    com.therealityteam.aeropages.model.LegendPos.RIGHT to R.string.legend_right,
                )
                val placed = places.first { it.first == (if (legend) pos else null) }
                ComboField(stringResource(placed.second), places, { stringResource(it.second) }, placed, { (p, _) -> if (p == null) legend = false else { legend = true; pos = p } }, width = 200.dp)
            }
        }
}

/** Name (or rename) the section that starts at the current slide. */
@Composable
internal fun SectionNameDialog(initial: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AeroDialog(title = stringResource(R.string.title_section_name), onDismiss = onDismiss, width = 380.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.apply), { if (name.isNotBlank()) onApply(name.trim()) }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(380.dp)) {
                InputBox(name, { name = it }, Modifier.fillMaxWidth())
            }
        }
}

/**
 * Insert → Header & Footer, as PowerPoint's: the date and time (updated each time the slides show,
 * in one of the formats — each shown as today's date — or fixed text), the slide number, the footer
 * text, and whether title slides go without them; applied to all slides.
 */
@Composable
internal fun HeaderFooterDialog(p: com.therealityteam.aeropages.model.Presentation, onDismiss: () -> Unit,
                                onApply: (footer: String, showNumber: Boolean, date: com.therealityteam.aeropages.model.SlideDate?, hideOnTitle: Boolean) -> Unit) {
    val formats = com.therealityteam.aeropages.model.SlideDate.FORMATS
    var footer by remember(p) { mutableStateOf(p.footer) }
    var showNum by remember(p) { mutableStateOf(p.showSlideNumber) }
    var showDate by remember(p) { mutableStateOf(p.date != null) }
    var fixed by remember(p) { mutableStateOf(p.date?.fixed != null) }
    var format by remember(p) { mutableStateOf(p.date?.format ?: com.therealityteam.aeropages.model.SlideDate.DEFAULT_FORMAT) }
    var fixedText by remember(p) { mutableStateOf(p.date?.fixed ?: com.therealityteam.aeropages.model.SlideDate(format).text()) }
    var hideOnTitle by remember(p) { mutableStateOf(p.hideFooterOnTitle) }
    AeroDialog(title = stringResource(R.string.title_header_footer), onDismiss = onDismiss, width = 460.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.apply_all_header_footer), {
                    val date = when {
                        !showDate -> null
                        fixed -> com.therealityteam.aeropages.model.SlideDate(format, fixedText.trim())
                        else -> com.therealityteam.aeropages.model.SlideDate(format)
                    }
                    onApply(footer, showNum, date, hideOnTitle)
                }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(460.dp)) {
                CheckRow(stringResource(R.string.hf_date_time), showDate) { showDate = !showDate }
                if (showDate) Column(Modifier.padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RadioRow(stringResource(R.string.hf_update_auto), !fixed) { fixed = false }
                    // Each format as it shows today.
                    if (!fixed) ComboField(com.therealityteam.aeropages.model.SlideDate(format).text(), formats, { com.therealityteam.aeropages.model.SlideDate(it).text() }, format,
                        { format = it }, Modifier.padding(start = 34.dp), width = 260.dp)
                    RadioRow(stringResource(R.string.hf_fixed), fixed) { fixed = true }
                    if (fixed) InputBox(fixedText, { fixedText = it }, Modifier.padding(start = 34.dp).fillMaxWidth())
                }
                CheckRow(stringResource(R.string.slide_number), showNum) { showNum = !showNum }
                DialogLabel(stringResource(R.string.footer_text_label))
                InputBox(footer, { footer = it }, Modifier.fillMaxWidth())
                CheckRow(stringResource(R.string.hf_hide_on_title), hideOnTitle) { hideOnTitle = !hideOnTitle }
            }
        }
}

/** PowerPoint's Designer: design ideas for the slide, each a small slide in its colours with its name under it — pick one to apply it. */
@Composable
internal fun DesignerPanel(editor: SlideEditor, onClose: () -> Unit, modifier: Modifier = Modifier) {
    // PowerPoint's Designer: a task pane docked beside the slide, which stays in view while an idea is tried.
    Column(
        modifier.fillMaxHeight().paneSurface(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PaneHeader(stringResource(R.string.designer_panel_title), onClose)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val ideas = listOf(
                Triple(SLIDE_THEMES[7], SlideLayout.TITLE, stringResource(R.string.idea_centered_title)),
                Triple(SLIDE_THEMES[2], SlideLayout.TITLE_CONTENT, stringResource(R.string.layout_title_content)),
                Triple(SLIDE_THEMES[3], SlideLayout.SECTION, stringResource(R.string.layout_section_header)),
                Triple(SLIDE_THEMES[8], SlideLayout.TITLE_CONTENT, stringResource(R.string.idea_cyan_content)),
                Triple(SLIDE_THEMES[9], SlideLayout.TITLE, stringResource(R.string.idea_dark_cover)),
                Triple(SLIDE_THEMES[1], SlideLayout.BLANK, stringResource(R.string.idea_minimal_blank)),
            )
            ideas.forEach { (th, layout, name) ->
                val interaction = remember { MutableInteractionSource() }
                val shape = RoundedCornerShape(10.dp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f).shadow(2.dp, shape).clip(shape).background(Color(th.bg))
                            .border(BorderStroke(1.dp, Aero.barEdge), shape)
                            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                                editor.applyThemeFull(SLIDE_THEMES.indexOf(th), th.bg, th.title, th.body, if (layout == SlideLayout.TITLE) TextAlign.CENTER else null, DeckDesigns.NAMES)
                                editor.setLayout(layout)
                            }
                            .controllerHapticFeedback(interactionSource = interaction).padding(16.dp),
                    ) {
                        GazeFill(shape)
                        IdeaSketch(th, layout)
                    }
                    Text(name, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
                }
            }
        }
    }
}

/** A design idea drawn small: the title, an accent rule and body lines where [layout] puts them, in [th]'s colours. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.IdeaSketch(th: SlideTheme, layout: SlideLayout) {
    val title = stringResource(R.string.placeholder_add_title)
    when (layout) {
        SlideLayout.TITLE, SlideLayout.SECTION -> Column(Modifier.align(if (layout == SlideLayout.TITLE) Alignment.Center else Alignment.CenterStart),
            horizontalAlignment = if (layout == SlideLayout.TITLE) Alignment.CenterHorizontally else Alignment.Start, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color(th.title), fontSize = 17.sp, maxLines = 1, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Box(Modifier.width(64.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(th.accent)))
        }
        SlideLayout.BLANK -> Box(Modifier.align(Alignment.BottomEnd).width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(th.accent)))
        else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, color = Color(th.title), fontSize = 15.sp, maxLines = 1, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Box(Modifier.width(48.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(th.accent)))
            listOf(1f, 0.85f, 0.7f).forEach { f -> Box(Modifier.fillMaxWidth(f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(th.body).copy(alpha = 0.35f))) }
        }
    }
}

/**
 * Right-docked 动画窗格: the slide's effects in order, each with the click it plays on (the number
 * on an On Click entry; 0 for what starts with the slide), how it starts, its class colour, element,
 * effect and duration. Picking an entry makes it the Animations tab's current effect and selects its
 * element; ▲ ▼ reorder and ✕ removes, each one undo step.
 */
@Composable
internal fun AnimPane(editor: SlideEditor, onClose: () -> Unit, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val s = editor.slide
    val entries = editor.animationList
    val current = editor.currentEffect
    fun elementName(t: com.therealityteam.aeropages.model.AnimTarget): String = when {
        t === s.title -> Strings.get(R.string.source_title)
        t === s.body -> Strings.get(R.string.anim_entry_content)
        t is com.therealityteam.aeropages.model.SlideImage -> Strings.get(R.string.anim_entry_picture_n, s.images.indexOfFirst { it === t } + 1)
        t is com.therealityteam.aeropages.model.SlideTable -> Strings.get(R.string.anim_entry_table_n, s.tables.indexOfFirst { it === t } + 1)
        t is com.therealityteam.aeropages.model.SlideChart -> Strings.get(R.string.anim_entry_chart_n, s.charts.indexOfFirst { it === t } + 1)
        else -> Strings.get(R.string.anim_entry_shape_n, s.shapes.indexOfFirst { it.box === t } + 1)
    }
    Column(
        modifier.fillMaxHeight().paneSurface(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The pane's own Re-Order and Remove act on the current entry (highlighted below).
        val at = current?.let { c -> entries.indexOfFirst { it.anim === c } } ?: -1
        PaneHeader(stringResource(R.string.animation_pane), onClose) {
            RibbonMiniButton("ic_arrow_up", stringResource(R.string.anim_move_earlier), { current?.let { editor.moveAnimation(it, -1) } }, enabled = at > 0)
            RibbonMiniButton("ic_arrow_down", stringResource(R.string.anim_move_later), { current?.let { editor.moveAnimation(it, 1) } }, enabled = at in 0 until entries.lastIndex)
            RibbonMiniButton("ic_delete", stringResource(R.string.anim_remove), { current?.let { editor.removeAnimation(it) } }, enabled = at >= 0)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (entries.isEmpty()) PaneEmpty("ic_shape_star", stringResource(R.string.msg_no_animations_yet))
            // The timing bars share one scale: the longest delay + duration on the slide (at least two seconds).
            val span = (entries.maxOfOrNull { it.anim.delayMs + it.anim.durationMs } ?: 0).coerceAtLeast(2000).toFloat()
            var click = 0
            entries.forEachIndexed { i, e ->
                val a = e.anim
                // The click an On Click entry waits for; the first entry shows 0 when it starts with the slide.
                val tag = when {
                    a.start == com.therealityteam.aeropages.model.AnimStart.ON_CLICK -> (++click).toString()
                    i == 0 -> "0"
                    else -> ""
                }
                val startName = when (a.start) {
                    com.therealityteam.aeropages.model.AnimStart.ON_CLICK -> stringResource(R.string.anim_on_click)
                    com.therealityteam.aeropages.model.AnimStart.WITH_PREVIOUS -> stringResource(R.string.anim_with_previous)
                    com.therealityteam.aeropages.model.AnimStart.AFTER_PREVIOUS -> stringResource(R.string.anim_after_previous)
                }
                ListRow(a === current, { editor.selectAnim(a) }, Modifier.semantics { contentDescription = startName }) {
                    // PowerPoint's row: the click number, After Previous's clock, the effect's star in its class colour.
                    Text(tag, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, modifier = Modifier.width(16.dp))
                    Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                        if (a.start == com.therealityteam.aeropages.model.AnimStart.AFTER_PREVIOUS) RibbonGlyph("ic_clock", PicoTheme.colorScheme.labelTertiary, 14.dp)
                    }
                    RibbonGlyph("ic_shape_star", animClassColor(a.effect.cls), 16.dp)
                    Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(elementName(e.target), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
                        // Appear and Disappear take no time: just their name.
                        val seconds = stringResource(R.string.anim_delay_seconds, (a.durationMs / 1000.0).toString().trimEnd('0').trimEnd('.'))
                        val byParagraph = if (a.byParagraph && com.therealityteam.aeropages.model.AnimTimeline.paragraphsOf(e.target).size >= 2) " · ${stringResource(R.string.anim_by_paragraph)}" else ""
                        Text((if (a.effect.instant) animName(a.effect) else "${animName(a.effect)} · $seconds") + byParagraph,
                            style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
                        // The advanced timeline's bar: where the effect waits (its delay) and how long it plays.
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(4.dp)) {
                            val w = maxWidth
                            Box(Modifier.padding(start = w * (a.delayMs / span)).width((w * (a.durationMs.coerceAtLeast(100) / span)).coerceAtLeast(4.dp)).fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp)).background(animClassColor(a.effect.cls).copy(alpha = 0.7f)))
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.anim_pane_hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
    }
}

/** Export as PDF: one slide per page, PowerPoint-style handouts (2 / 3 / 4 / 6 per page) or notes pages. */
@Composable
internal fun PdfLayoutDialog(onDismiss: () -> Unit, onPick: (com.therealityteam.aeropages.format.pdf.SlidesPdfExporter.Layout) -> Unit) {
    val layouts = com.therealityteam.aeropages.format.pdf.SlidesPdfExporter.Layout.values()
    fun labelOf(l: com.therealityteam.aeropages.format.pdf.SlidesPdfExporter.Layout) = when (l) {
        com.therealityteam.aeropages.format.pdf.SlidesPdfExporter.Layout.SLIDES -> R.string.pdf_layout_slides
        com.therealityteam.aeropages.format.pdf.SlidesPdfExporter.Layout.HANDOUT_2 -> R.string.pdf_layout_handout2
        com.therealityteam.aeropages.format.pdf.SlidesPdfExporter.Layout.HANDOUT_3 -> R.string.pdf_layout_handout3
        com.therealityteam.aeropages.format.pdf.SlidesPdfExporter.Layout.HANDOUT_4 -> R.string.pdf_layout_handout4
        com.therealityteam.aeropages.format.pdf.SlidesPdfExporter.Layout.HANDOUT_6 -> R.string.pdf_layout_handout6
        com.therealityteam.aeropages.format.pdf.SlidesPdfExporter.Layout.NOTES -> R.string.pdf_layout_notes
    }
    AeroDialog(title = stringResource(R.string.pdf_layout_title), onDismiss = onDismiss, width = 380.dp, buttons = { DialogButton(stringResource(R.string.cancel), onDismiss) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(380.dp)) {
                Text(stringResource(R.string.pdf_layout_desc), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                layouts.forEach { l -> ListRow(stringResource(labelOf(l)), l == com.therealityteam.aeropages.format.pdf.SlidesPdfExporter.Layout.SLIDES, { onPick(l); onDismiss() }) }
            }
        }
}

/** Slides from Outline: paste or type an outline (titles, indented lines as slide text). */
@Composable
internal fun SlidesFromOutlineDialog(onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AeroDialog(title = stringResource(R.string.slides_from_outline), onDismiss = onDismiss, width = 460.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.btn_ok), { if (text.isNotBlank()) onApply(text) }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(460.dp)) {
                Text(stringResource(R.string.desc_slides_from_outline), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                InputBox(text, { text = it }, Modifier.fillMaxWidth().height(220.dp), placeholder = stringResource(R.string.outline_hint), minLines = 8, maxLines = 40)
            }
        }
}
