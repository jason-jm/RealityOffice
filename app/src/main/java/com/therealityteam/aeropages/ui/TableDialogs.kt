package com.therealityteam.aeropages.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.AlertDialog
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.FormulaLocale
import com.therealityteam.aeropages.editor.SortKind
import com.therealityteam.aeropages.editor.TableText
import com.therealityteam.aeropages.editor.TextSortKey
import androidx.compose.runtime.mutableStateListOf
import com.therealityteam.aeropages.editor.TableSeparator
import com.therealityteam.aeropages.model.LocaleCurrency
import com.therealityteam.aeropages.model.NumberSymbols

/** Word's table grid: point at a size — "4×3 表格" (columns × rows) — and click it to insert a table that size. */
@Composable
internal fun TableGridPicker(onPick: (rows: Int, cols: Int) -> Unit) {
    val cols = 10
    val rows = 8
    val cell = 22.dp
    var hover by remember { mutableStateOf(0 to 0) }   // rows × columns pointed at (0: none)
    val accent = PicoTheme.colorScheme.interaction
    val line = Color(0xFFB8BCC4)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (hover.first > 0) stringResource(R.string.table_grid_size, hover.second, hover.first) else stringResource(R.string.insert_table),
            style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary)
        val label = stringResource(R.string.insert_table)
        Canvas(
            Modifier.size(cell * cols, cell * rows).semantics { contentDescription = label }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            val p = e.changes.firstOrNull()?.position ?: continue
                            hover = if (e.type == PointerEventType.Exit) 0 to 0
                                else ((p.y / cell.toPx()).toInt() + 1).coerceIn(1, rows) to ((p.x / cell.toPx()).toInt() + 1).coerceIn(1, cols)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { p -> onPick(((p.y / cell.toPx()).toInt() + 1).coerceIn(1, rows), ((p.x / cell.toPx()).toInt() + 1).coerceIn(1, cols)) }
                },
        ) {
            val c = cell.toPx()
            val gap = 2.dp.toPx()
            for (r in 0 until rows) for (k in 0 until cols) {
                val on = r < hover.first && k < hover.second
                val tl = Offset(k * c + gap / 2, r * c + gap / 2)
                val sz = Size(c - gap, c - gap)
                drawRoundRect(if (on) accent.copy(alpha = 0.25f) else Color.White, tl, sz, CornerRadius(2.dp.toPx()))
                drawRoundRect(if (on) accent else line, tl, sz, CornerRadius(2.dp.toPx()), style = Stroke(1.dp.toPx()))
            }
        }
    }
}

/** The separators the dialogs offer, with their names. */
@Composable
private fun separators(paragraph: Boolean): List<Pair<TableSeparator, String>> = listOfNotNull(
    (TableSeparator.PARAGRAPH to stringResource(R.string.sep_paragraph)).takeIf { paragraph },
    TableSeparator.TAB to stringResource(R.string.sep_tab),
    TableSeparator.COMMA to stringResource(R.string.delim_comma),
    TableSeparator.SPACE to stringResource(R.string.delim_space),
    TableSeparator.OTHER to stringResource(R.string.sep_other),
)

/** Where text is split ([sep], with its character for "other"). */
@Composable
private fun SeparatorChoice(sep: TableSeparator, other: String, paragraph: Boolean, onSep: (TableSeparator) -> Unit, onOther: (String) -> Unit) {
    DialogLabel(stringResource(R.string.tt_separate_at))
    WrapRow(spacing = 16.dp, lineSpacing = 0.dp) {
        separators(paragraph).forEach { (s, name) -> RadioOption(name, sep == s, { onSep(s) }) }
    }
    if (sep == TableSeparator.OTHER) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sep_other_char), style = AeroType.body)
        InputBox(other, { onOther(it.takeLast(1)) }, Modifier.width(56.dp))
    }
}

/**
 * Convert Text to Table: where the selected paragraphs split into cells — tabs (Word's choice when they have them),
 * commas, spaces, paragraph marks or a character — the columns that makes (more or fewer), and the rows.
 */
@Composable
internal fun TextToTableDialog(editor: DocumentEditor, onDismiss: () -> Unit) {
    // What is selected now (the document may still be opening when the dialog is).
    val paras = remember(editor.revision) { editor.selectedBodyParagraphs().orEmpty() }
    val initial = remember(paras) {
        val text = paras.joinToString("\n") { it.plainText() }
        when { '\t' in text -> TableSeparator.TAB; text.any { it == ',' || it == '，' } -> TableSeparator.COMMA; paras.size > 1 -> TableSeparator.PARAGRAPH; else -> TableSeparator.SPACE }
    }
    var sep by remember(initial) { mutableStateOf(initial) }
    var other by remember { mutableStateOf("-") }
    val found = editor.textToTableColumns(sep, other.firstOrNull())
    var columns by remember(sep, other) { mutableIntStateOf(found.coerceAtLeast(if (sep == TableSeparator.PARAGRAPH) 2 else 1)) }
    val rows = if (sep == TableSeparator.PARAGRAPH) (paras.size + columns - 1) / columns.coerceAtLeast(1) else paras.size
    AeroDialog(title = stringResource(R.string.text_to_table_title), onDismiss = onDismiss, width = 440.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.btn_ok), { editor.convertTextToTable(sep, other.firstOrNull(), columns); onDismiss() }, primary = true, enabled = paras.isNotEmpty())
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(440.dp)) {
                SeparatorChoice(sep, other, paragraph = true, { sep = it }, { other = it })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.tt_columns), style = AeroType.body, modifier = Modifier.width(64.dp))
                    SpinBox("$columns", { d -> columns = (columns + d).coerceIn(1, 63) }, description = stringResource(R.string.tt_columns),
                        canLess = columns > 1, canMore = columns < 63, valueWidth = 36.dp)
                    Text(stringResource(R.string.tt_rows, rows), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                }
            }
        }
}

/** Convert Table to Text: what goes between the cells — a paragraph mark, a tab, a comma, a space or a character. */
@Composable
internal fun TableToTextDialog(editor: DocumentEditor, onDismiss: () -> Unit) {
    var sep by remember { mutableStateOf(TableSeparator.TAB) }
    var other by remember { mutableStateOf("-") }
    AeroDialog(title = stringResource(R.string.table_to_text_title), onDismiss = onDismiss, width = 420.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.btn_ok), { editor.convertTableToText(sep, other.firstOrNull()); onDismiss() }, primary = true)
            }
        }) { Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(420.dp)) { SeparatorChoice(sep, other, paragraph = true, { sep = it }, { other = it }) } }
}

/**
 * Sort (Word's Table Tools ▸ Sort): by which column — named by its header — as Pinyin text, numbers or dates,
 * ascending or descending, the first row a header or not; a table with cells spanning rows cannot be sorted.
 */
@Composable
internal fun TableSortDialog(editor: DocumentEditor, onDismiss: () -> Unit, onStatus: (String) -> Unit) {
    val names = remember(editor.revision) { editor.activeTableColumns }
    var header by remember(names) { mutableStateOf(names.isNotEmpty() && names.all { it.isNotEmpty() && TableTextNumbers.notNumber(it) }) }
    var column by remember { mutableIntStateOf(0) }
    var kind by remember { mutableStateOf(SortKind.TEXT) }
    var descending by remember { mutableStateOf(false) }
    val sortable = remember(editor.revision) { editor.activeTableSortable }
    val cannot = stringResource(R.string.sort_merged)
    AeroDialog(title = stringResource(R.string.table_sort), onDismiss = onDismiss, width = 460.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.btn_ok), {
                    if (!editor.sortTable(column, kind, descending, header)) onStatus(cannot)
                    onDismiss()
                }, primary = true, enabled = sortable)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(460.dp)) {
                if (!sortable) Text(cannot, style = AeroType.caption, color = PicoTheme.colorScheme.error)
                // Word's Sort: Sort by [column ▾], Type [Text ▾], Ascending / Descending.
                val columnName: @Composable (Int) -> String = { i -> names.getOrNull(i)?.takeIf { header && it.isNotEmpty() }?.take(24) ?: stringResource(R.string.column_n, i + 1) }
                FieldRow(stringResource(R.string.sort_key), labelWidth = 96.dp) {
                    ComboField(columnName(column), names.indices.toList(), columnName, column, { column = it }, width = 200.dp)
                }
                val kinds = listOf(SortKind.TEXT to R.string.sort_kind_text, SortKind.NUMBER to R.string.sort_kind_number, SortKind.DATE to R.string.sort_kind_date)
                FieldRow(stringResource(R.string.sort_type), labelWidth = 96.dp) {
                    ComboField(stringResource(kinds.first { it.first == kind }.second), kinds, { stringResource(it.second) }, kinds.first { it.first == kind }, { kind = it.first }, width = 200.dp)
                }
                Row(Modifier.padding(start = 108.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RadioOption(stringResource(R.string.sort_ascending), !descending, { descending = false })
                    RadioOption(stringResource(R.string.sort_descending), descending, { descending = true })
                }
                CheckRow(stringResource(R.string.sort_header_row), header) { header = !header }
            }
        }
}

/**
 * Sort Text (Word's Home ▸ Paragraph ▸ Sort outside a table): up to three keys — the whole paragraph or a field of it,
 * as text, a number or a date, ascending or descending —, whether the list's first paragraph is its header, where
 * fields split (tabs, commas, spaces or a character) and whether case counts. The selected paragraphs, else the run
 * the caret is in.
 */
@Composable
internal fun SortTextDialog(editor: DocumentEditor, onDismiss: () -> Unit, onStatus: (String) -> Unit) {
    var sep by remember { mutableStateOf(TableSeparator.TAB) }
    var other by remember { mutableStateOf("-") }
    var header by remember { mutableStateOf(false) }
    var caseSensitive by remember { mutableStateOf(false) }
    // Each key: its field (-1 the whole paragraph, -2 none for a "then by"), type and order.
    val fields = remember { mutableStateListOf(-1, -2, -2) }
    val kinds = remember { mutableStateListOf(SortKind.TEXT, SortKind.TEXT, SortKind.TEXT) }
    val descending = remember { mutableStateListOf(false, false, false) }
    val count = remember(editor.revision, sep, other) { editor.sortTextParagraphs()?.let { TableText.columnsFor(it, sep, other.firstOrNull()) } ?: 0 }
    val nothing = stringResource(R.string.sort_nothing)
    val paragraphs = stringResource(R.string.paragraph)
    val none = stringResource(R.string.sort_none)
    AeroDialog(title = stringResource(R.string.sort_text), onDismiss = onDismiss, width = 500.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), {
            val keys = (0 until 3).filter { fields[it] != -2 }.map { TextSortKey(fields[it], kinds[it], descending[it]) }
            if (!editor.sortParagraphs(keys, sep, other.firstOrNull(), header, caseSensitive)) onStatus(nothing)
            onDismiss()
        }, primary = true, enabled = count > 0)
    }) {
        Column(Modifier.width(470.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (count == 0) Text(nothing, style = AeroType.caption, color = PicoTheme.colorScheme.error)
            val kindNames = listOf(SortKind.TEXT to R.string.sort_kind_text, SortKind.NUMBER to R.string.sort_kind_number, SortKind.DATE to R.string.sort_kind_date)
            for (k in 0 until 3) {
                // The whole paragraph, or one of its fields once they split into more than one; a "then by" can be none.
                val choices = (if (k > 0) listOf(-2) else emptyList()) + listOf(-1) + (if (count > 1) (0 until count).toList() else emptyList())
                val label: @Composable (Int) -> String = { f -> when (f) { -2 -> none; -1 -> paragraphs; else -> stringResource(R.string.sort_field_n, f + 1) } }
                val off = fields[k] == -2
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (k == 0) R.string.sort_key else R.string.sort_then_by_level), style = AeroType.body, modifier = Modifier.width(84.dp))
                    ComboField(label(fields[k]), choices, { label(it) }, fields[k], { fields[k] = it }, width = 150.dp)
                    ComboField(stringResource(kindNames.first { it.first == kinds[k] }.second), kindNames, { stringResource(it.second) }, kindNames.first { it.first == kinds[k] },
                        { kinds[k] = it.first }, width = 150.dp, enabled = !off)
                }
                Row(Modifier.padding(start = 92.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RadioOption(stringResource(R.string.sort_ascending), !descending[k], { descending[k] = false }, enabled = !off)
                    RadioOption(stringResource(R.string.sort_descending), descending[k], { descending[k] = true }, enabled = !off)
                }
            }
            CheckOption(stringResource(R.string.sort_list_header), header, { header = !header })
            DialogLabel(stringResource(R.string.sort_text_options))
            SeparatorChoice(sep, other, paragraph = false, onSep = { sep = it }, onOther = { other = it })
            CheckOption(stringResource(R.string.match_case), caseSensitive, { caseSensitive = !caseSensitive })
        }
    }
}

/** Whether a header guess's cell reads as a label ("Q1", "姓名") rather than being a number or an amount ("1,200", "35%"). */
private object TableTextNumbers {
    private val NUMBER = Regex("""^[-+−]?[¥$€£₩₽₹฿]?\s*[\d,，.\u00A0\u202F ]+\s*[%€₽zł]*$""")
    fun notNumber(s: String) = !NUMBER.matches(s.trim())
}

/**
 * Formula (Word's Table Tools ▸ Data): the formula — Word's guess first, =SUM(ABOVE) under numbers or =SUM(LEFT)
 * beside them —, how the result is written, functions and positions to paste in, and what it comes to here.
 */
@Composable
internal fun TableFormulaDialog(editor: DocumentEditor, onDismiss: () -> Unit) {
    // Typed and shown with the language's separators (=SUM(A1;B1)*1,5 in German), kept in the file in the standard form.
    val symbols = remember { NumberSymbols.current() }
    var formula by remember(editor.revision) { mutableStateOf(FormulaLocale.toLocal(editor.suggestedFormula, symbols)) }
    var picture by remember { mutableStateOf("") }
    val canonical = FormulaLocale.toCanonical(formula, symbols)
    val preview = editor.formulaPreview(canonical, picture)
    AeroDialog(title = stringResource(R.string.formula_title), onDismiss = onDismiss, width = 460.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.btn_ok), { editor.insertTableFormula(canonical, picture.ifBlank { null }); onDismiss() }, primary = true, enabled = preview != null)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(460.dp)) {
                DialogLabel(stringResource(R.string.formula_formula))
                InputBox(formula, { formula = it }, Modifier.width(440.dp), monospace = true)
                // Word's Formula: Paste function and Number format drop-downs (and the positions a function sums).
                DialogLabel(stringResource(R.string.formula_paste))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val fns = listOf("SUM", "AVERAGE", "COUNT", "MAX", "MIN", "PRODUCT")
                    ComboField(stringResource(R.string.formula_function), fns, { it }, null,
                        { fn -> formula = (if (formula.startsWith("=")) formula else "=$formula") + "$fn()" }, width = 180.dp)
                    val places = listOf("ABOVE", "LEFT", "BELOW", "RIGHT")
                    ComboField(stringResource(R.string.formula_position), places, { it }, null,
                        { pos -> formula = if (formula.endsWith("()")) formula.dropLast(1) + "$pos)" else formula + pos }, width = 180.dp)
                }
                // The pictures as the file keeps them, shown with the language's symbols; money in the language's currency, where it writes it.
                val money = remember { LocaleCurrency.of() }
                val currency = if (money.code.startsWith("\"")) money.symbol + "#,##0.00" else "#,##0.00 " + money.symbol
                val pictures = listOf("" to stringResource(R.string.formula_format_general)) +
                    listOf("0", "0.00", "#,##0", "#,##0.00", currency).map { it to symbols.local(it) }
                FieldRow(stringResource(R.string.formula_number_format), labelWidth = 120.dp) {
                    ComboField(pictures.first { it.first == picture }.second, pictures, { it.second }, pictures.first { it.first == picture }, { picture = it.first }, width = 200.dp)
                }
                Text(if (preview != null) stringResource(R.string.formula_preview, preview) else stringResource(R.string.txt_field_formula_error),
                    style = AeroType.body, color = if (preview != null) PicoTheme.colorScheme.labelPrimary else Aero.danger)
            }
        }
}
