package com.therealityteam.aeropages.ui

import com.therealityteam.aeropages.editor.FormulaLocale
import com.therealityteam.aeropages.model.NumberSymbols
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.AlertDialog
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.ValidationForm
import com.therealityteam.aeropages.model.ValidationAlertStyle
import com.therealityteam.aeropages.model.ValidationOperator
import com.therealityteam.aeropages.model.ValidationType
import com.therealityteam.aeropages.model.Workbook
import androidx.compose.ui.res.stringResource
import com.therealityteam.aeropages.R
/** Pivot-table builder: pick the row/column/value fields and an aggregation (数据透视表). */
@Composable
internal fun PivotDialog(
    headers: List<String>,
    onDismiss: () -> Unit,
    onApply: (rowField: Int, colField: Int?, valueField: Int, agg: com.therealityteam.aeropages.editor.PivotAgg) -> Unit,
) {
    var rowField by remember { mutableIntStateOf(0) }
    var colField by remember { mutableStateOf<Int?>(null) }
    var valueField by remember { mutableIntStateOf(if (headers.size > 1) headers.lastIndex else 0) }
    var agg by remember { mutableStateOf(com.therealityteam.aeropages.editor.PivotAgg.SUM) }

    @Composable
    fun fieldRow(label: String, selected: Int?, allowNone: Boolean, onPick: (Int?) -> Unit) {
        val none = stringResource(R.string.none)
        val options: List<Int?> = (if (allowNone) listOf<Int?>(null) else emptyList()) + headers.indices.toList()
        FieldRow(label, labelWidth = 120.dp) {
            ComboField(selected?.let { headers[it] } ?: none, options, { i -> i?.let { headers[it] } ?: none }, selected, onPick, width = 260.dp)
        }
    }

    AeroDialog(title = stringResource(R.string.title_create_pivot), onDismiss = onDismiss, width = 520.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.btn_create), { onApply(rowField, colField, valueField, agg) }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(520.dp)) {
                Text(stringResource(R.string.desc_pivot_table), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                fieldRow(stringResource(R.string.pivot_field_row), rowField, allowNone = false) { it?.let { v -> rowField = v } }
                fieldRow(stringResource(R.string.pivot_field_column), colField, allowNone = true) { colField = it }
                fieldRow(stringResource(R.string.pivot_field_value), valueField, allowNone = false) { it?.let { v -> valueField = v } }
                FieldRow(stringResource(R.string.pivot_field_aggregate), labelWidth = 120.dp) {
                    ComboField(agg.display, com.therealityteam.aeropages.editor.PivotAgg.entries.toList(), { it.display }, agg, { agg = it }, width = 260.dp)
                }
            }
        }
}

@Composable
internal fun NumField(value: String, onChange: (String) -> Unit) {
    InputBox(value, onChange, Modifier.width(120.dp))
}

/** 分列: split the active column's cells by a delimiter into adjacent columns. */
@Composable
internal fun TextToColumnsDialog(onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var delim by remember { mutableStateOf(",") }
    val presets = listOf("," to stringResource(R.string.delim_comma), "\t" to "Tab", " " to stringResource(R.string.delim_space), ";" to stringResource(R.string.delim_semicolon), "|" to stringResource(R.string.delim_pipe))
    AeroDialog(title = stringResource(R.string.title_text_to_columns), onDismiss = onDismiss, width = 420.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.btn_ok), { onApply(delim) }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(420.dp)) {
                Text(stringResource(R.string.desc_text_to_columns), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                // Excel's Delimiters, as option buttons (one separator at a time here).
                WrapRow(spacing = 16.dp, lineSpacing = 0.dp) {
                    presets.forEach { (d, label) -> RadioOption(label, delim == d, { delim = d }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.delim_custom), style = AeroType.body); NumField(delim) { delim = it }
                }
            }
        }
}

/** Cell comment / note editor. */
@Composable
internal fun CommentDialog(initial: String, onDismiss: () -> Unit, author: String? = null, onApply: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AeroDialog(title = stringResource(R.string.comment), onDismiss = onDismiss, width = 420.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.btn_ok), { onApply(text) }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(420.dp)) {
                // Excel's note opens with its author's name; a new note's is the reviewer's.
                author?.let { name ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Avatar(name, size = 24.dp)
                        Text(name, style = AeroType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
                    }
                }
                Text(stringResource(R.string.desc_comment_field), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                InputBox(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3)
            }
        }
}

/** Right-docked chart panel: each chart from the sheet rendered with the shared ChartPlot. */
@Composable
internal fun SheetChartPanel(editor: SheetEditor, onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val clipboard = rememberTextClipboard()
    var spatial3D by remember { mutableStateOf(false) }
    var elementsOf by remember { mutableStateOf<Int?>(null) }
    elementsOf?.let { i ->
        editor.sheet.charts.getOrNull(i)?.let { chart ->
            ChartElementsDialog(chart, onDismiss = { elementsOf = null }) { t, cat, v, legend, pos, axis ->
                editor.updateChart(i) { it.title = t; it.catAxisTitle = cat; it.valAxisTitle = v; it.showLegend = legend; it.legendPos = pos; it.axisFormat = axis }
                elementsOf = null
            }
        } ?: run { elementsOf = null }
    }
    Column(
        modifier = Modifier.width(300.dp).fillMaxHeight().paneSurface(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PaneHeader(stringResource(R.string.chart_label), onClose) {
            TitleIconButton("ic_cube", stringResource(R.string.chart_3d_spatial_view), { spatial3D = !spatial3D }, selected = spatial3D, size = 36.dp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            editor.sheet.charts.forEachIndexed { i, chart ->
                // Each chart on a white card, as the sheet draws it.
                Column(
                    Modifier.fillMaxWidth().barSurface().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(chart.title, style = AeroType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Aero.ink, maxLines = 1)
                            chart.sourceRef?.let { ref ->
                                Text(stringResource(R.string.label_chart_source, ref), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
                            }
                        }
                        RibbonMiniButton("ic_delete", stringResource(R.string.delete), { editor.deleteChart(i) })
                    }
                    if (spatial3D) Spatial3DChart(chart, Modifier.fillMaxWidth(), plotHeight = 170)
                    else ChartPlot(chart, Modifier.fillMaxWidth().height(150.dp), ink = Color(com.therealityteam.aeropages.model.ChartStyle.DARK_INK), surface = Color.White)
                    ChartLegend(chart, Color(com.therealityteam.aeropages.model.ChartStyle.DARK_INK), max = 4)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(
                            Triple(com.therealityteam.aeropages.model.ChartType.COLUMN, R.string.chart_type_column, "ic_chart"),
                            Triple(com.therealityteam.aeropages.model.ChartType.LINE, R.string.chart_type_line, "ic_chart_line"),
                            Triple(com.therealityteam.aeropages.model.ChartType.PIE, R.string.chart_type_pie, "ic_chart_pie"),
                            Triple(com.therealityteam.aeropages.model.ChartType.AREA, R.string.chart_type_area, "ic_chart_area"),
                        ).forEach { (t, label, icon) ->
                            RibbonMiniButton(icon, stringResource(label), { editor.updateChart(i) { it.type = t } }, selected = chart.type == t)
                        }
                        BarDivider()
                        // Bind the chart to the currently selected block (Excel's Select Data).
                        RibbonMiniButton("ic_select_all", stringResource(R.string.select_data), { editor.setChartSource(i) })
                        RibbonMiniButton("ic_layout", stringResource(R.string.chart_elements), { elementsOf = i })
                        // Copy it for a slide or a page (with its data as it stands).
                        RibbonMiniButton("ic_copy", stringResource(R.string.copy), { if (editor.copyChart(i) != null) clipboard.copyCurrentObject() })
                    }
                }
            }
        }
        Text(stringResource(R.string.desc_chart_hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
    }
}

/**
 * Excel's Data Validation dialog on the selection, filled in from the active cell's rule: Settings
 * (what the cells allow, compared how, with which bounds, list or formula), Input Message (shown on
 * selecting a cell) and Error Alert (what an invalid entry raises). Clear All takes the rules off.
 */
@Composable
internal fun ValidationDialog(editor: SheetEditor, onDismiss: () -> Unit, onStatus: (String) -> Unit) {
    val defaultList = stringResource(R.string.default_yes_no_list)
    var f by remember { mutableStateOf(ValidationForm.fieldsOf(editor.validationAt(editor.selRow, editor.selCol), defaultList, editor.symbols)) }
    var tab by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf<ValidationForm.Problem?>(null) }
    AeroDialog(title = stringResource(R.string.title_data_validation), onDismiss = onDismiss, width = 500.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.clear_all), {
                    editor.clearValidations(); onStatus(Strings.get(R.string.status_validation_cleared)); onDismiss()
                })
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.btn_ok), {
                    val (rule, why) = ValidationForm.ruleOf(f, editor.symbols)
                    if (rule == null) { problem = why; tab = 0 }
                    else {
                        // Any value with no messages is no rule at all, as in Excel.
                        if (rule.type == ValidationType.ANY && !rule.hasInputMessage) editor.clearValidations() else editor.setValidation(rule)
                        onStatus(Strings.get(R.string.status_validation_set)); onDismiss()
                    }
                }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(500.dp)) {
                DialogTabs(listOf(R.string.validation_tab_settings, R.string.validation_tab_input, R.string.validation_tab_error).indices.toList(), tab,
                    { stringResource(listOf(R.string.validation_tab_settings, R.string.validation_tab_input, R.string.validation_tab_error)[it]) }, { tab = it }, width = 480.dp)
                when (tab) {
                    0 -> ValidationSettings(f) { f = it; problem = null }
                    1 -> {
                        CheckRow(stringResource(R.string.validation_show_input), f.showInput) { f = f.copy(showInput = !f.showInput) }
                        FormField(stringResource(R.string.label_title), f.inputTitle) { f = f.copy(inputTitle = it) }
                        FormField(stringResource(R.string.label_input_message), f.inputMessage, lines = 3) { f = f.copy(inputMessage = it) }
                    }
                    else -> {
                        CheckRow(stringResource(R.string.validation_show_error), f.showError) { f = f.copy(showError = !f.showError) }
                        val styles = listOf(
                            ValidationAlertStyle.STOP to R.string.alert_stop, ValidationAlertStyle.WARNING to R.string.alert_warning,
                            ValidationAlertStyle.INFORMATION to R.string.alert_information,
                        )
                        FieldRow(stringResource(R.string.label_style), labelWidth = 96.dp) {
                            ComboField(stringResource(styles.first { it.first == f.errorStyle }.second), styles, { stringResource(it.second) }, styles.first { it.first == f.errorStyle },
                                { f = f.copy(errorStyle = it.first) }, width = 200.dp)
                        }
                        FormField(stringResource(R.string.label_title), f.errorTitle) { f = f.copy(errorTitle = it) }
                        FormField(stringResource(R.string.label_error_message), f.errorMessage, lines = 3) { f = f.copy(errorMessage = it) }
                    }
                }
                problem?.let { p ->
                    Text(stringResource(when (p) {
                        ValidationForm.Problem.BOUND -> R.string.err_validation_bound
                        ValidationForm.Problem.MIN_OVER_MAX -> R.string.err_validation_min_max
                        ValidationForm.Problem.LIST -> R.string.err_validation_list
                        ValidationForm.Problem.LIST_TOO_LONG -> R.string.err_validation_list_long
                        ValidationForm.Problem.FORMULA -> R.string.err_validation_formula
                    }), style = AeroType.caption, color = PicoTheme.colorScheme.error)
                }
            }
        }
}

/** The Settings tab: Allow, then the comparison and its bounds, the list's source, or the Custom formula. */
@Composable
private fun ValidationSettings(f: ValidationForm.Fields, onChange: (ValidationForm.Fields) -> Unit) {
    // Excel's Allow and Data drop-downs.
    val types = listOf(
        ValidationType.ANY to R.string.validation_any, ValidationType.INTEGER to R.string.validation_whole_number,
        ValidationType.DECIMAL to R.string.validation_decimal, ValidationType.LIST to R.string.validation_list,
        ValidationType.DATE to R.string.validation_date, ValidationType.TIME to R.string.validation_time,
        ValidationType.TEXT_LENGTH to R.string.validation_text_length, ValidationType.CUSTOM to R.string.validation_custom,
    )
    FieldRow(stringResource(R.string.label_allow), labelWidth = 96.dp) {
        ComboField(stringResource(types.first { it.first == f.type }.second), types, { stringResource(it.second) }, types.first { it.first == f.type },
            { onChange(f.copy(type = it.first)) }, width = 220.dp)
    }
    when {
        f.type.compares -> {
            val ops = listOf(
                ValidationOperator.BETWEEN to R.string.op_between, ValidationOperator.NOT_BETWEEN to R.string.op_not_between,
                ValidationOperator.EQUAL to R.string.op_equal, ValidationOperator.NOT_EQUAL to R.string.op_not_equal,
                ValidationOperator.GREATER to R.string.op_greater, ValidationOperator.LESS to R.string.op_less,
                ValidationOperator.GREATER_OR_EQUAL to R.string.op_greater_or_equal, ValidationOperator.LESS_OR_EQUAL to R.string.op_less_or_equal,
            )
            FieldRow(stringResource(R.string.label_data), labelWidth = 96.dp) {
                ComboField(stringResource(ops.first { it.first == f.operator }.second), ops, { stringResource(it.second) }, ops.first { it.first == f.operator },
                    { onChange(f.copy(operator = it.first)) }, width = 220.dp)
            }
            if (f.operator.twoBounds) {
                FormField(stringResource(R.string.label_minimum), f.minimum) { onChange(f.copy(minimum = it)) }
                FormField(stringResource(R.string.label_maximum), f.maximum) { onChange(f.copy(maximum = it)) }
            } else FormField(stringResource(R.string.label_value), f.minimum) { onChange(f.copy(minimum = it)) }
            Hint(FormulaLocale.inText(stringResource(R.string.hint_validation_bounds), NumberSymbols.current()))
            CheckRow(stringResource(R.string.validation_ignore_blank), f.ignoreBlank) { onChange(f.copy(ignoreBlank = !f.ignoreBlank)) }
        }
        f.type == ValidationType.LIST -> {
            FormField(stringResource(R.string.label_source), f.source) { onChange(f.copy(source = it)) }
            // Where the comma is the decimal symbol, a list's values go apart with ; (as in that language's Excel).
            Hint(stringResource(if (NumberSymbols.current().argument == ';') R.string.hint_validation_source_semicolons else R.string.hint_validation_source))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CheckRow(stringResource(R.string.validation_ignore_blank), f.ignoreBlank) { onChange(f.copy(ignoreBlank = !f.ignoreBlank)) }
                CheckRow(stringResource(R.string.validation_in_cell_dropdown), f.dropdown) { onChange(f.copy(dropdown = !f.dropdown)) }
            }
        }
        f.type == ValidationType.CUSTOM -> {
            FormField(stringResource(R.string.label_formula), f.formula, monospace = true) { onChange(f.copy(formula = it)) }
            Hint(FormulaLocale.inText(stringResource(R.string.hint_validation_formula), NumberSymbols.current()))
            CheckRow(stringResource(R.string.validation_ignore_blank), f.ignoreBlank) { onChange(f.copy(ignoreBlank = !f.ignoreBlank)) }
        }
    }
}

/** The mark on each error alert style (Excel's stop sign, warning triangle and information bubble). */
internal fun alertIcon(style: ValidationAlertStyle): Pair<String, Color> = when (style) {
    ValidationAlertStyle.STOP -> "ic_minus_circle" to Aero.danger
    ValidationAlertStyle.WARNING -> "ic_warning" to Color(0xFFC77C02) // design-style: warning ink
    ValidationAlertStyle.INFORMATION -> "ic_info" to Color(0xFF185ABD) // design-style: information ink
}

/** A labelled text box across the dialog; [lines] > 1 grows it for a message. */
@Composable
private fun FormField(label: String, value: String, lines: Int = 1, monospace: Boolean = false, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DialogLabel(label)
        InputBox(value, onChange, Modifier.fillMaxWidth(), description = label, minLines = lines, maxLines = if (lines == 1) 1 else 6, monospace = monospace)
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)

/**
 * The alert an invalid entry raised, in its rule's style: Stop (Retry / Cancel — the cell is back
 * as it was), Warning (Continue? Yes / No / Cancel) or Information (OK / Cancel). [onRetry] goes
 * back into the cell to put the entry right.
 */
@Composable
internal fun ValidationAlertDialog(editor: SheetEditor, alert: SheetEditor.ValidationAlert, onRetry: () -> Unit) {
    val title = alert.rule.errorTitle.ifBlank { stringResource(R.string.title_data_validation) }
    AlertDialog(
        onDismissRequest = { editor.cancelEntry() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val (icon, tint) = alertIcon(alert.style)
                RibbonGlyph(icon, tint, 24.dp)
                Text(title)
            }
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(400.dp)) {
                Text(alert.message, style = AeroType.body)
                if (alert.style == ValidationAlertStyle.WARNING) Text(stringResource(R.string.msg_validation_continue), style = AeroType.body)
            }
        },
        buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (alert.style) {
                    ValidationAlertStyle.STOP -> {
                        DialogButton(stringResource(R.string.btn_retry), onRetry, primary = true)
                        DialogButton(stringResource(R.string.cancel), { editor.cancelEntry() })
                    }
                    ValidationAlertStyle.WARNING -> {
                        DialogButton(stringResource(R.string.btn_yes), { editor.keepEntry() }, primary = true)
                        DialogButton(stringResource(R.string.btn_no), onRetry)
                        DialogButton(stringResource(R.string.cancel), { editor.cancelEntry() })
                    }
                    ValidationAlertStyle.INFORMATION -> {
                        DialogButton(stringResource(R.string.btn_ok), { editor.keepEntry() }, primary = true)
                        DialogButton(stringResource(R.string.cancel), { editor.cancelEntry() })
                    }
                }
            }
        },
    )
}

/** Keyword filter: hide rows whose selected-column text doesn't contain the query. */
@Composable
internal fun KeywordFilterDialog(onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    AeroDialog(title = stringResource(R.string.title_keyword_filter), onDismiss = onDismiss, width = 380.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.btn_filter), { if (q.isNotBlank()) onApply(q.trim()) }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(380.dp)) {
                Text(stringResource(R.string.desc_keyword_filter), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                NumField(q) { q = it }
            }
        }
}

/**
 * A filter button's menu (Excel's AutoFilter drop-down) for column [col]: sort A→Z / Z→A, clear
 * the column's filter, Filter by Color and Number / Text / Date Filters (each a page of its own,
 * as Excel's submenus — [onCustom] and [onTop10] open their dialogs), search, and the values to
 * show — those the other columns' filters let through, blanks included — with Select All.
 */
@Composable
internal fun FilterMenuDialog(editor: SheetEditor, col: Int, onDismiss: () -> Unit, onStatus: (String) -> Unit,
                              onCustom: (FilterSeed) -> Unit = {}, onTop10: () -> Unit = {}) {
    val listed = remember(col) { editor.filterValues(col) }
    val values = listed.first
    val hasBlanks = listed.second
    val current = editor.columnFilter(col)
    val checked = remember(col) {
        androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply {
            values.forEach { v -> put(v, current?.passes(v, v.replace(",", "").toDoubleOrNull()) ?: true) }
        }
    }
    var blanks by remember(col) { mutableStateOf(current?.passes("", null) ?: true) }
    var search by remember { mutableStateOf("") }
    val header = editor.autoFilter?.let { editor.display(it.r1, col) }?.ifBlank { null } ?: Workbook.colLabel(col)
    val shown = values.filter { search.isBlank() || it.contains(search.trim(), ignoreCase = true) }
    fun rowsShownStatus() {
        val af = editor.autoFilter ?: return
        val total = af.r2 - af.r1
        onStatus(Strings.get(R.string.status_rows_shown, total - editor.hiddenRows.count { it in af.r1 + 1..af.r2 }, total))
    }
    // The menu, or one of its submenus (Excel's flyouts) in its place.
    var page by remember(col) { androidx.compose.runtime.mutableIntStateOf(when (devDialogProp()) { "filtercriteria" -> 2; "filtercolor" -> 1; "sortcolor" -> 3; else -> 0 }) }
    val kind = remember(col) { editor.filterKind(col) }
    val colors = remember(col) { editor.filterColors(col) }
    val kindLabel = stringResource(when (kind) {
        SheetEditor.FilterKind.NUMBER -> R.string.number_filters; SheetEditor.FilterKind.TEXT -> R.string.text_filters; SheetEditor.FilterKind.DATE -> R.string.date_filters
    })
    if (page != 0) {
        AeroDialog(title = stringResource(R.string.title_filter_menu, header), onDismiss = onDismiss, width = 360.dp, buttons = {
            DialogButton(stringResource(R.string.cancel), onDismiss)
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.width(360.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TitleIconButton("ic_chevron_left", stringResource(R.string.back), { page = 0 }, size = 32.dp)
                    Text(when (page) { 1 -> stringResource(R.string.filter_by_color); 3 -> stringResource(R.string.sort_by_color); else -> kindLabel },
                        style = AeroType.label, color = PicoTheme.colorScheme.labelPrimary)
                }
                MenuDivider()
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (page == 1) FilterColorMenu(colors.first, colors.second, current?.color) { c ->
                        editor.setColumnFilter(col, com.therealityteam.aeropages.model.ColumnFilter(blanks = false, color = c)); rowsShownStatus(); onDismiss()
                    } else if (page == 3) FilterColorMenu(colors.first, colors.second, null, sorting = true) { c ->
                        editor.sortFilterColumnByColor(col, c.color, c.font)
                        onStatus(Strings.get(R.string.status_sorted_by, header, "", Strings.get(if (c.font) R.string.sort_on_font_color else R.string.sort_on_cell_color)))
                        onDismiss()
                    } else FilterCriteriaMenu(kind, current, onCustom = { onDismiss(); onCustom(it) }, onTop10 = { onDismiss(); onTop10() }) { f ->
                        editor.setColumnFilter(col, f); rowsShownStatus(); onDismiss()
                    }
                }
            }
        }
        return
    }
    AeroDialog(title = stringResource(R.string.title_filter_menu, header), onDismiss = onDismiss, width = 360.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.btn_ok), {
                    // With a search, only the matching values that are ticked stay (Excel's search box).
                    val allowed = (if (search.isBlank()) values else shown).filter { checked[it] == true }.toSet()
                    val showBlanks = blanks && search.isBlank()
                    val everything = search.isBlank() && allowed.size == values.size && (!hasBlanks || showBlanks)
                    editor.setColumnFilter(col, if (everything) null else com.therealityteam.aeropages.model.ColumnFilter(values = allowed, blanks = showBlanks))
                    rowsShownStatus()
                    onDismiss()
                }, primary = true)
            }
        }) {
            // Excel's drop-down: the sorts and Clear Filter as menu commands, then the search and the values' list.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(360.dp)) {
                MenuCommand(stringResource(R.string.sort_a_to_z), { editor.sortFilterColumn(col, true); onDismiss() }, icon = "ic_sort_asc")
                MenuCommand(stringResource(R.string.sort_z_to_a), { editor.sortFilterColumn(col, false); onDismiss() }, icon = "ic_sort_desc")
                MenuCommand(stringResource(R.string.sort_by_color), { page = 3 }, icon = "x_sortmulti", trailing = "›",
                    enabled = colors.first.isNotEmpty() || colors.second.isNotEmpty())
                MenuCommand(stringResource(R.string.clear_filter_from, header), { editor.setColumnFilter(col, null); rowsShownStatus(); onDismiss() },
                    icon = "ic_filter_clear", enabled = current != null)
                // Excel's two submenus: by colour (when the column has two fills or font colours), and by the kind of value it holds.
                MenuCommand(stringResource(R.string.filter_by_color), { page = 1 }, icon = "ic_fill", checked = current?.color != null,
                    trailing = "›", enabled = colors.first.isNotEmpty() || colors.second.isNotEmpty())
                MenuCommand(kindLabel, { page = 2 }, icon = "ic_filter", checked = current?.isCriteria == true, trailing = "›")
                MenuDivider()
                val searchLabel = stringResource(R.string.filter_search)
                // Excel's filter search: SpatialUI's search field over the values.
                com.pico.spatial.ui.design.SearchField(
                    value = search, onValueChange = { search = it }, onSearch = { search = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), placeholder = { Text(searchLabel, maxLines = 1) },
                )
                // (Select All) ticks or clears what the search shows; it is ticked when all of that is.
                val blanksListed = hasBlanks && search.isBlank()
                val allOn = shown.all { checked[it] == true } && (!blanksListed || blanks)
                // Short enough that the menu, with its sorts and submenus, keeps its buttons in a small window (the list scrolls).
                ListBox(height = 180.dp, empty = if (shown.isEmpty() && !blanksListed) stringResource(R.string.home_no_matches) else null) {
                    CheckOption(stringResource(R.string.filter_select_all), allOn, {
                        shown.forEach { checked[it] = !allOn }; if (blanksListed) blanks = !allOn
                    }, Modifier.fillMaxWidth())
                    shown.forEach { v -> CheckOption(v, checked[v] == true, { checked[v] = checked[v] != true }, Modifier.fillMaxWidth()) }
                    if (blanksListed) CheckOption(stringResource(R.string.filter_blanks), blanks, { blanks = !blanks }, Modifier.fillMaxWidth())
                }
            }
        }
}

/**
 * Excel's Sort dialog (Custom Sort) over what a sort orders — the selection, or the data around the active cell — with "My
 * data has headers" as Excel guesses it (a header names the columns): levels to Add, Delete, Copy and move up or down, each
 * a column, what to sort on — its values, cell colour or font colour — and the order: ascending or descending, or which
 * colour goes on top or at the bottom (the colours that column shows). OK sorts, one undo step.
 */
@Composable
internal fun MultiSortDialog(editor: SheetEditor, onDismiss: () -> Unit, onStatus: (String) -> Unit) {
    val area = remember { editor.sortArea() }
    if (area == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onStatus(Strings.get(R.string.sort_no_data)); onDismiss() }
        return
    }
    val cols = (area.c1..area.c2).toList()
    var header by remember { mutableStateOf(area.header) }
    // `sortlevels`: three levels to show — values descending, then yellow cells on top, then values ascending.
    val levels = remember {
        if (devDialogProp() == "sortlevels" && area.c2 - area.c1 >= 2) androidx.compose.runtime.mutableStateListOf(
            SheetEditor.SortLevel(area.c1 + 1, ascending = false),
            SheetEditor.SortLevel(area.c1, on = SheetEditor.SortOn.CELL_COLOR, color = 0xFFFFFF00),
            SheetEditor.SortLevel(area.c1 + 2),
        ) else androidx.compose.runtime.mutableStateListOf(SheetEditor.SortLevel(editor.selCol.coerceIn(area.c1, area.c2)))
    }
    var picked by remember { mutableIntStateOf(0) }
    // Which level's Order ▸ Custom List… is open (its dialog picks the list; drawn after this one, so on top of it).
    var customFor by remember { mutableStateOf<Int?>(if (devDialogProp() == "sortcustomlist") 0 else null) }
    val dataRows = (area.r1 + (if (header) 1 else 0))..area.r2
    // A column by its header's name when the first row is one, else by its letter.
    fun keyName(c: Int): String = (if (header) editor.sheet.cell(area.r1, c)?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length > 14) it.take(13) + "…" else it } else null)
        ?: Workbook.colLabel(c)
    fun colors(l: SheetEditor.SortLevel) = editor.sortColors(l.col, dataRows, l.on == SheetEditor.SortOn.FONT_COLOR)
    // A level's colour when it sorts on one: its own if that column shows it, else the first the column shows.
    fun withColor(l: SheetEditor.SortLevel) = if (l.on == SheetEditor.SortOn.VALUES) l.copy(color = null) else l.copy(color = colors(l).let { cs -> if (l.color in cs) l.color else cs.firstOrNull() })
    val onLabel: @Composable (SheetEditor.SortOn) -> String = {
        stringResource(when (it) { SheetEditor.SortOn.VALUES -> R.string.sort_on_values; SheetEditor.SortOn.CELL_COLOR -> R.string.sort_on_cell_color; SheetEditor.SortOn.FONT_COLOR -> R.string.sort_on_font_color })
    }
    AeroDialog(title = stringResource(R.string.custom_sort), onDismiss = onDismiss, width = 600.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), {
            if (editor.sortLevels(levels.toList(), header)) {
                val first = levels.first()
                val how = if (first.on == SheetEditor.SortOn.VALUES) Strings.get(if (first.ascending) R.string.ascending else R.string.descending)
                    else Strings.get(if (first.on == SheetEditor.SortOn.FONT_COLOR) R.string.sort_on_font_color else R.string.sort_on_cell_color)
                onStatus(Strings.get(R.string.status_sorted_by, keyName(first.col), if (levels.size > 1) " +" + (levels.size - 1) else "", how))
            }
            onDismiss()
        }, primary = true)
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(560.dp)) {
            // Excel's toolbar: Add, Delete and Copy Level, move the chosen level up or down; My data has headers on the right.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DialogButton(stringResource(R.string.sort_add_level), {
                    val next = cols.firstOrNull { c -> levels.none { it.col == c } } ?: levels[picked].col
                    levels.add(picked + 1, SheetEditor.SortLevel(next)); picked++
                }, enabled = levels.size < 64, small = true)
                DialogButton(stringResource(R.string.sort_delete_level), {
                    levels.removeAt(picked); picked = picked.coerceAtMost(levels.size - 1)
                }, enabled = levels.size > 1, small = true)
                DialogButton(stringResource(R.string.sort_copy_level), { levels.add(picked + 1, levels[picked]); picked++ }, enabled = levels.size < 64, small = true)
                TitleIconButton("ic_chevron_up", stringResource(R.string.cf_move_up), {
                    val l = levels.removeAt(picked); picked--; levels.add(picked, l)
                }, enabled = picked > 0, size = 32.dp)
                TitleIconButton("ic_chevron_down", stringResource(R.string.cf_move_down), {
                    val l = levels.removeAt(picked); picked++; levels.add(picked, l)
                }, enabled = picked < levels.size - 1, size = 32.dp)
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                CheckOption(stringResource(R.string.sort_has_headers), header, { header = !header; levels.indices.forEach { i -> levels[i] = withColor(levels[i]) } })
            }
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.layout.Spacer(Modifier.width(82.dp))
                Text(stringResource(R.string.sort_column), style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(136.dp))
                Text(stringResource(R.string.sort_on), style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(118.dp))
                Text(stringResource(R.string.sort_order), style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary)
            }
            ListBox(height = 230.dp) {
                levels.forEachIndexed { i, l ->
                    Row(
                        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .background(if (i == picked) LocalAccent.current.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
                            .press { picked = i }.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(if (i == 0) R.string.sort_by else R.string.sort_then_by_level), style = AeroType.body,
                            color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.width(82.dp))
                        ComboField(keyName(l.col), cols, { keyName(it) }, l.col, { c -> levels[i] = withColor(l.copy(col = c)); picked = i }, width = 136.dp)
                        ComboField(onLabel(l.on), SheetEditor.SortOn.entries.toList(), { onLabel(it) }, l.on, { on -> levels[i] = withColor(l.copy(on = on)); picked = i }, width = 118.dp)
                        if (l.on == SheetEditor.SortOn.VALUES) {
                            // Excel's Order: ascending, descending, the level's custom list either way, and Custom List….
                            val custom = l.custom
                            fun clip(t: String) = if (t.length > 22) t.take(21) + "…" else t
                            val orderLabel: @Composable (Int) -> String = { o ->
                                when (o) {
                                    0 -> stringResource(R.string.ascending)
                                    1 -> stringResource(R.string.descending)
                                    2 -> clip(com.therealityteam.aeropages.editor.CustomLists.label(custom.orEmpty()))
                                    3 -> clip(com.therealityteam.aeropages.editor.CustomLists.label(custom.orEmpty().reversed()))
                                    else -> stringResource(R.string.sort_custom_list) + "…"
                                }
                            }
                            val options = if (custom == null) listOf(0, 1, 4) else listOf(0, 1, 2, 3, 4)
                            val current = if (custom == null) (if (l.ascending) 0 else 1) else (if (l.ascending) 2 else 3)
                            ComboField(orderLabel(current), options, { orderLabel(it) }, current, { o ->
                                picked = i
                                when (o) {
                                    0, 1 -> levels[i] = l.copy(ascending = o == 0, custom = null)
                                    2, 3 -> levels[i] = l.copy(ascending = o == 2)
                                    else -> customFor = i
                                }
                            }, width = 164.dp)
                        } else {
                            ColorChoiceField(colors(l), l.color, l.on == SheetEditor.SortOn.FONT_COLOR, { c -> levels[i] = l.copy(color = c); picked = i }, width = 56.dp)
                            val sideLabel: @Composable (Boolean) -> String = { stringResource(if (it) R.string.sort_on_top else R.string.sort_on_bottom) }
                            ComboField(sideLabel(l.onTop), listOf(true, false), { sideLabel(it) }, l.onTop, { levels[i] = l.copy(onTop = it); picked = i }, width = 100.dp)
                        }
                    }
                }
            }
        }
    }
    customFor?.let { i ->
        CustomListsDialog(editor, onDismiss = { customFor = null }, onPick = { list -> levels.getOrNull(i)?.let { levels[i] = it.copy(custom = list, ascending = true) } })
    }
}


/**
 * Excel's Remove Duplicates over what a sort orders — the selection, or the data around the active cell — with "My data
 * has headers" as Excel guesses it (a header names the columns): the columns to compare, each ticked at first, Select All
 * and Unselect All. OK removes the rows whose values in those columns repeat an earlier row's ([onResult] gets Excel's
 * message: how many went and how many unique rows stay).
 */
@Composable
internal fun RemoveDuplicatesDialog(editor: SheetEditor, onDismiss: () -> Unit, onResult: (String) -> Unit) {
    val area = remember { editor.sortArea() }
    if (area == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onResult(Strings.get(R.string.sort_no_data)); onDismiss() }
        return
    }
    var header by remember { mutableStateOf(area.header) }
    val cols = (area.c1..area.c2).toList()
    val ticked = remember { androidx.compose.runtime.mutableStateMapOf<Int, Boolean>().apply { cols.forEach { put(it, true) } } }
    fun name(c: Int): String = (if (header) editor.sheet.cell(area.r1, c)?.text?.trim()?.takeIf { it.isNotEmpty() } else null)
        ?: Strings.get(R.string.rd_column_letter, Workbook.colLabel(c))
    AeroDialog(title = stringResource(R.string.remove_duplicates), onDismiss = onDismiss, width = 460.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), {
            val (removed, kept) = editor.removeDuplicates(area.r1, area.c1, area.r2, area.c2, cols.filter { ticked[it] == true }, header)
            onResult(if (removed > 0) Strings.get(R.string.rd_result, removed, kept) else Strings.get(R.string.status_no_duplicates))
            onDismiss()
        }, primary = true, enabled = cols.any { ticked[it] == true })
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(420.dp)) {
            Text(stringResource(R.string.rd_hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DialogButton(stringResource(R.string.select_all), { cols.forEach { ticked[it] = true } }, small = true)
                DialogButton(stringResource(R.string.rd_unselect_all), { cols.forEach { ticked[it] = false } }, small = true)
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                CheckOption(stringResource(R.string.sort_has_headers), header, { header = !header })
            }
            DialogLabel(stringResource(R.string.sort_column))
            ListBox(height = 200.dp) {
                cols.forEach { c -> CheckOption(name(c), ticked[c] == true, { ticked[c] = ticked[c] != true }, Modifier.fillMaxWidth()) }
            }
        }
    }
}


/**
 * Excel's Paste Special: what of the copied cells to paste, an operation with the numbers pasted
 * onto, skip blanks and transpose — or Paste Link, references to the copied cells.
 */
@Composable
internal fun PasteSpecialDialog(editor: SheetEditor, onDismiss: () -> Unit, onStatus: (String) -> Unit) {
    var what by remember { mutableStateOf(SheetEditor.PasteWhat.ALL) }
    var op by remember { mutableStateOf(SheetEditor.PasteOp.NONE) }
    var skipBlanks by remember { mutableStateOf(false) }
    var transpose by remember { mutableStateOf(false) }
    val whats = listOf(
        SheetEditor.PasteWhat.ALL to R.string.paste_what_all, SheetEditor.PasteWhat.FORMULAS to R.string.paste_what_formulas,
        SheetEditor.PasteWhat.VALUES to R.string.paste_what_values, SheetEditor.PasteWhat.VALUES_AND_NUMBER_FORMATS to R.string.paste_what_values_formats,
        SheetEditor.PasteWhat.FORMATS to R.string.paste_what_formats, SheetEditor.PasteWhat.COMMENTS to R.string.paste_what_comments,
        SheetEditor.PasteWhat.VALIDATION to R.string.paste_what_validation,
    )
    val ops = listOf(
        SheetEditor.PasteOp.NONE to R.string.paste_op_none, SheetEditor.PasteOp.ADD to R.string.paste_op_add,
        SheetEditor.PasteOp.SUBTRACT to R.string.paste_op_subtract, SheetEditor.PasteOp.MULTIPLY to R.string.paste_op_multiply,
        SheetEditor.PasteOp.DIVIDE to R.string.paste_op_divide,
    )
    AeroDialog(title = stringResource(R.string.paste_special).trimEnd('…', '.'), onDismiss = onDismiss, width = 460.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.paste_link), {
                    if (editor.pasteLink()) { onStatus(Strings.get(R.string.status_pasted_as, Strings.get(R.string.paste_link))); onDismiss() }
                })
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.btn_ok), {
                    if (editor.pasteSpecial(what, op, skipBlanks, transpose)) {
                        onStatus(Strings.get(R.string.status_pasted_as, Strings.get(whats.first { it.first == what }.second)))
                        onDismiss()
                    }
                }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(460.dp)) {
                // Excel's Paste Special: Paste and Operation as two columns of option buttons, then two check boxes.
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) {
                        DialogLabel(stringResource(R.string.paste), Modifier.padding(bottom = 4.dp))
                        whats.forEach { (w, name) -> RadioOption(stringResource(name), what == w, { what = w }) }
                    }
                    Column(Modifier.weight(1f)) {
                        DialogLabel(stringResource(R.string.paste_operation), Modifier.padding(bottom = 4.dp))
                        ops.forEach { (o, name) -> RadioOption(stringResource(name), op == o, { op = o }) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    CheckOption(stringResource(R.string.paste_skip_blanks), skipBlanks, { skipBlanks = !skipBlanks })
                    CheckOption(stringResource(R.string.paste_transpose), transpose, { transpose = !transpose })
                }
            }
        }
}

/** Goal Seek (Data → What-If): set cell, target value, changing cell. */
@Composable
internal fun GoalSeekDialog(initialSet: String, onDismiss: () -> Unit, onApply: (String, String, String) -> Unit) {
    var setCell by remember { mutableStateOf(initialSet) }
    var target by remember { mutableStateOf("0") }
    var changing by remember { mutableStateOf("") }
    AeroDialog(title = stringResource(R.string.title_goal_seek), onDismiss = onDismiss, width = 420.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.btn_ok), { onApply(setCell.trim(), target.trim(), changing.trim()) }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(420.dp)) {
                Text(stringResource(R.string.desc_goal_seek), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.goal_set_cell), style = AeroType.body, modifier = Modifier.width(150.dp)); NumField(setCell) { setCell = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.goal_to_value), style = AeroType.body, modifier = Modifier.width(150.dp)); NumField(target) { target = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.goal_by_changing), style = AeroType.body, modifier = Modifier.width(150.dp)); NumField(changing) { changing = it }
                }
            }
        }
}

/** Data Table (What-If): the row / column input cells for the selected block. */
@Composable
internal fun DataTableDialog(onDismiss: () -> Unit, onApply: (String, String) -> Unit) {
    var rowInput by remember { mutableStateOf("") }
    var colInput by remember { mutableStateOf("") }
    AeroDialog(title = stringResource(R.string.title_data_table), onDismiss = onDismiss, width = 440.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.btn_ok), { onApply(rowInput.trim(), colInput.trim()) }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(440.dp)) {
                Text(stringResource(R.string.desc_data_table), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.data_table_row_input), style = AeroType.body, modifier = Modifier.width(190.dp)); NumField(rowInput) { rowInput = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.data_table_col_input), style = AeroType.body, modifier = Modifier.width(190.dp)); NumField(colInput) { colInput = it }
                }
            }
        }
}

/** Scenario Manager: named value sets for the input cells; show one, add the selection as one, delete, summarise. */
@Composable
internal fun ScenarioDialog(editor: SheetEditor, onDismiss: () -> Unit, onStatus: (String) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    val scenarios = editor.sheet.scenarios
    AeroDialog(title = stringResource(R.string.title_scenarios), onDismiss = onDismiss, width = 500.dp, buttons = {
            DialogButton(stringResource(R.string.close), onDismiss)
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(500.dp)) {
                Text(stringResource(R.string.desc_scenarios), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                // Excel's: the scenarios in a list, the commands for the picked one beside it.
                val current = scenarios.firstOrNull { it.name == picked }?.name ?: scenarios.firstOrNull()?.name
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ListBox(Modifier.weight(1f), height = 180.dp,
                        empty = if (scenarios.isEmpty()) stringResource(R.string.scenario_none) else null, emptyIcon = "x_target") {
                        scenarios.toList().forEach { sc ->
                            val on = sc.name == current
                            ListRow(on, { picked = sc.name }) {
                                Text(sc.name, style = if (on) AeroType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else AeroType.body,
                                    color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.weight(1f))
                                Text(stringResource(R.string.scenario_cells, sc.values.size), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(112.dp)) {
                        DialogButton(stringResource(R.string.scenario_show), {
                            current?.let { if (editor.showScenario(it)) onStatus(Strings.get(R.string.status_scenario_shown, it)) }
                        }, Modifier.fillMaxWidth(), primary = true, enabled = current != null)
                        DialogButton(stringResource(R.string.scenario_delete), { current?.let { editor.deleteScenario(it); picked = null } }, Modifier.fillMaxWidth(), enabled = current != null)
                        DialogButton(stringResource(R.string.scenario_summary), {
                            editor.scenarioSummary()?.let { onStatus(Strings.get(R.string.status_scenario_summary, it)); onDismiss() }
                        }, Modifier.fillMaxWidth(), enabled = scenarios.isNotEmpty())
                    }
                }
                FieldRow(stringResource(R.string.scenario_name), labelWidth = 96.dp) {
                    InputBox(name, { name = it }, Modifier.weight(1f))
                    DialogButton(stringResource(R.string.scenario_add), { if (editor.addScenario(name)) { picked = name.trim(); name = "" } }, enabled = name.isNotBlank())
                }
            }
        }
}

/** Subtotal (Data → 分类汇总): group column, function, target columns, header flag. */
@Composable
internal fun SubtotalDialog(onDismiss: () -> Unit, onApply: (String, Int, String, Boolean) -> Unit) {
    var groupCol by remember { mutableStateOf("A") }
    var targets by remember { mutableStateOf("B") }
    var fn by remember { mutableIntStateOf(9) }
    var header by remember { mutableStateOf(true) }
    val functions = listOf(9 to "SUM", 1 to "AVERAGE", 2 to "COUNT", 4 to "MAX", 5 to "MIN")
    AeroDialog(title = stringResource(R.string.title_subtotal), onDismiss = onDismiss, width = 460.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.btn_ok), { onApply(groupCol.trim(), fn, targets.trim(), header) }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(460.dp)) {
                Text(stringResource(R.string.desc_subtotal), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.subtotal_group_col), style = AeroType.body, modifier = Modifier.width(170.dp)); NumField(groupCol) { groupCol = it }
                }
                FieldRow(stringResource(R.string.subtotal_function), labelWidth = 170.dp) {
                    ComboField(functions.first { it.first == fn }.second, functions, { it.second }, functions.first { it.first == fn }, { fn = it.first }, width = 180.dp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.subtotal_target_cols), style = AeroType.body, modifier = Modifier.width(170.dp)); NumField(targets) { targets = it }
                }
                CheckOption(stringResource(R.string.subtotal_header_row), header, { header = !header })
            }
        }
}

/**
 * Excel's Row Height… and Column Width…: one number for the selected rows (points) or columns (characters of the
 * default font) — what they are now when they agree; 0 hides them, as in Excel.
 */
@Composable
internal fun CellSizeDialog(editor: SheetEditor, rows: Boolean, onDismiss: () -> Unit) {
    fun shown(v: Float) = editor.symbols.local(String.format(java.util.Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.'))
    var text by remember(rows) { mutableStateOf((if (rows) editor.selectedRowHeightPt() else editor.selectedColWidthChars())?.let(::shown) ?: "") }
    val min = if (rows) SheetEditor.MIN_ROW_FACTOR * SheetEditor.ROW_PT else SheetEditor.MIN_COL_FACTOR * SheetEditor.COL_CHARS
    val max = if (rows) SheetEditor.MAX_ROW_FACTOR * SheetEditor.ROW_PT else SheetEditor.MAX_COL_FACTOR * SheetEditor.COL_CHARS
    val v = text.trim().replace(',', '.').toFloatOrNull()
    val ok = v != null && (v == 0f || v in min..max)
    fun apply() {
        val n = v ?: return
        if (!ok) return
        if (rows) editor.setRowHeightPt(n) else editor.setColWidthChars(n)
        onDismiss()
    }
    AeroDialog(title = stringResource(if (rows) R.string.row_height else R.string.col_width), onDismiss = onDismiss, width = 360.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.btn_ok), ::apply, primary = true, enabled = ok)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(360.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(if (rows) R.string.row_height_label else R.string.col_width_label), style = AeroType.body)
                    NumField(text) { text = it }
                }
                Text(stringResource(R.string.cell_size_range, shown(min), shown(max)), style = AeroType.caption,
                    color = if (ok || text.isBlank()) PicoTheme.colorScheme.labelSecondary else Aero.danger)
            }
        }
}

/** Excel's name for an error and why it comes, as its Error Checking dialog gives them. */
private fun errorWhy(code: String): Pair<Int, Int> = when (code) {
    "#DIV/0!" -> R.string.err_div0 to R.string.err_div0_why
    "#NAME?" -> R.string.err_name to R.string.err_name_why
    "#VALUE!" -> R.string.err_value to R.string.err_value_why
    "#REF!" -> R.string.err_ref to R.string.err_ref_why
    "#N/A" -> R.string.err_na to R.string.err_na_why
    "#NUM!" -> R.string.err_num to R.string.err_num_why
    "#NULL!" -> R.string.err_null to R.string.err_null_why
    else -> R.string.err_other to R.string.err_other_why
}

/**
 * Excel's Error Checking: one formula error at a time — the cell, its formula, the error's name and why it comes — with
 * Edit in Formula Bar and Ignore Error beside it and Previous / Next under it; the cell it shows is picked on the sheet.
 * With none left (or all ignored): the check is complete. Ignored errors stay ignored while the dialog is open.
 */
@Composable
internal fun ErrorCheckingDialog(editor: SheetEditor, onEditInBar: () -> Unit, onDismiss: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    var ignored by remember { mutableStateOf(emptySet<Pair<Int, Int>>()) }
    val errors = editor.formulaErrors().filter { (r, c) -> (r to c) !in ignored }
    var at by remember { mutableIntStateOf(0) }
    val i = at.coerceIn(0, (errors.size - 1).coerceAtLeast(0))
    val current = errors.getOrNull(i)
    androidx.compose.runtime.LaunchedEffect(current?.first, current?.second) { current?.let { (r, c) -> editor.select(r, c) } }
    AeroDialog(stringResource(R.string.error_checking), onDismiss, width = 520.dp, buttons = {
        if (current != null) {
            DialogButton(stringResource(R.string.err_previous), { at = i - 1 }, enabled = i > 0)
            DialogButton(stringResource(R.string.err_next), { at = i + 1 }, enabled = i < errors.size - 1)
        }
        DialogButton(stringResource(R.string.close), onDismiss, primary = current == null)
    }) {
        if (current == null) {
            Column(Modifier.width(520.dp)) { PaneEmpty("ic_check", stringResource(R.string.err_check_done)) }
            return@AeroDialog
        }
        val (r, c, code) = current
        val (name, why) = errorWhy(code)
        Row(Modifier.width(520.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogLabel(stringResource(R.string.err_in_cell, "${Workbook.colLabel(c)}${r + 1}") + "  ·  " + stringResource(R.string.err_of_total, i + 1, errors.size))
                Text(editor.sheet.cell(r, c)?.text.orEmpty(), style = AeroType.body.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = PicoTheme.colorScheme.labelPrimary, maxLines = 2,
                    modifier = Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(Aero.commandRadius)).background(Aero.field).padding(horizontal = 10.dp, vertical = 7.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RibbonGlyph("ic_warning", Aero.danger, 16.dp)
                    Text("${stringResource(name)} ($code)", style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
                }
                Text(stringResource(why), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            }
            Column(Modifier.width(180.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.err_edit_in_bar), onEditInBar, Modifier.fillMaxWidth(), primary = true)
                DialogButton(stringResource(R.string.err_ignore), { ignored = ignored + (r to c) }, Modifier.fillMaxWidth())
            }
        }
    }
}
