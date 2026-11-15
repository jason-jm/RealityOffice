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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.LineStyle
import com.therealityteam.aeropages.model.SheetTable
import com.therealityteam.aeropages.model.TableLooks

/** What to tell the user when a table command did nothing; [name] is the name that was typed, if one was. */
internal fun tableRefusalText(refusal: SheetEditor.TableRefusal?, name: String = ""): String = when (refusal) {
    SheetEditor.TableRefusal.OVERLAPS_TABLE -> Strings.get(R.string.err_table_overlaps)
    SheetEditor.TableRefusal.HAS_MERGES -> Strings.get(R.string.err_table_merged)
    SheetEditor.TableRefusal.BAD_NAME -> Strings.get(R.string.err_table_name_invalid, name)
    SheetEditor.TableRefusal.NAME_TAKEN -> Strings.get(R.string.err_table_name_taken, name)
    SheetEditor.TableRefusal.FILTER_ELSEWHERE -> Strings.get(R.string.err_table_filter_elsewhere)
    SheetEditor.TableRefusal.NO_DATA, null -> Strings.get(R.string.status_select_data_first)
}

private fun familyName(f: TableLooks.Family): String = Strings.get(
    when (f) {
        TableLooks.Family.LIGHT -> R.string.table_style_light
        TableLooks.Family.MEDIUM -> R.string.table_style_medium
        TableLooks.Family.DARK -> R.string.table_style_dark
    },
)

/** A total row function's name, as Excel's drop-down lists it. */
private fun totalName(fn: SheetEditor.TotalFunction?): String = Strings.get(
    when (fn) {
        null -> R.string.none
        SheetEditor.TotalFunction.AVERAGE -> R.string.total_fn_average
        SheetEditor.TotalFunction.COUNT -> R.string.total_fn_count
        SheetEditor.TotalFunction.COUNT_NUMBERS -> R.string.total_fn_count_numbers
        SheetEditor.TotalFunction.MAX -> R.string.total_fn_max
        SheetEditor.TotalFunction.MIN -> R.string.total_fn_min
        SheetEditor.TotalFunction.SUM -> R.string.total_fn_sum
        SheetEditor.TotalFunction.STD_DEV -> R.string.total_fn_stddev
        SheetEditor.TotalFunction.VAR -> R.string.total_fn_var
    },
)

/**
 * Excel's Table Design tab, there while the active cell is in [table]: its name (typed over to
 * rename it), Convert to Range, the Table Style Options, the style gallery ([onStyles]) and — on
 * the total row — what the total under the active column shows ([onTotals] opens the list).
 */
@Composable
internal fun TableDesignTab(
    editor: SheetEditor, table: SheetTable, onStatus: (String) -> Unit,
    stylesOpen: Boolean, onStyles: () -> Unit, totalsOpen: Boolean, onTotals: () -> Unit,
) {
    XGroup(stringResource(R.string.group_properties)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(stringResource(R.string.table_name), fontSize = 11.sp, color = PicoTheme.colorScheme.labelSecondary)
            TableNameField(editor, table, onStatus)
        }
        RibbonButton("x_torange", stringResource(R.string.convert_to_range), {
            val name = table.name
            if (editor.convertTableToRange(table)) onStatus(Strings.get(R.string.status_table_converted, name))
        })
    }
    XGroup(stringResource(R.string.group_table_style_options)) {
        val options = listOf(
            SheetEditor.TableOption.HEADER_ROW to R.string.table_header_row, SheetEditor.TableOption.TOTAL_ROW to R.string.table_total_row,
            SheetEditor.TableOption.BANDED_ROWS to R.string.table_banded_rows, SheetEditor.TableOption.FIRST_COLUMN to R.string.table_first_column,
            SheetEditor.TableOption.LAST_COLUMN to R.string.table_last_column, SheetEditor.TableOption.BANDED_COLUMNS to R.string.table_banded_columns,
            SheetEditor.TableOption.FILTER_BUTTON to R.string.table_filter_button,
        )
        // Excel's three columns of tick boxes.
        options.chunked(3).forEach { column ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                column.forEach { (option, label) ->
                    val on = editor.tableOption(table, option)
                    OptionCheck(stringResource(label), on, enabled = option != SheetEditor.TableOption.FILTER_BUTTON || table.headerRow) {
                        if (!editor.setTableOption(table, option, !on)) editor.tableRefusal?.let { onStatus(tableRefusalText(it)) }
                    }
                }
            }
        }
    }
    XGroup(stringResource(R.string.group_table_styles)) {
        RibbonButton("x_table", stringResource(R.string.tip_table_styles), onStyles, selected = stylesOpen, dropdown = true)
    }
    if (table.totalsRow && editor.selRow == table.r2 && editor.selCol in table.c1..table.c2) XGroup(stringResource(R.string.table_total_row)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(stringResource(R.string.table_total_row), fontSize = 11.sp, color = PicoTheme.colorScheme.labelSecondary)
            RibbonCombo(totalName(editor.totalFunctionAt(table, editor.selCol)), onTotals, width = 120.dp, selected = totalsOpen,
                tooltip = stringResource(R.string.table_total_row))
        }
    }
}

/** The total row's choices for the active column: nothing, or one of Excel's SUBTOTALs. */
@Composable
internal fun TotalFunctionsMenu(editor: SheetEditor, onDone: () -> Unit) {
    val table = editor.selectedTable ?: return
    val current = editor.totalFunctionAt(table, editor.selCol)
    // Excel's total row drop-down: None, Average, Count…
    FlyoutMenu(width = 220.dp) {
        (listOf<SheetEditor.TotalFunction?>(null) + SheetEditor.TotalFunction.entries).forEach { fn ->
            MenuCommand(totalName(fn), { editor.setTotalFunction(table, editor.selCol, fn); onDone() }, checked = fn == current)
        }
    }
}

/** A Table Style Option's tick box, small enough for three to a ribbon column. */
@Composable
private fun OptionCheck(label: String, on: Boolean, enabled: Boolean = true, toggle: () -> Unit) =
    CheckOption(label, on, toggle, Modifier.height(24.dp), enabled = enabled, small = true)

/** The table's name, typed over and committed with Enter or on leaving the field; Escape gives up. */
@Composable
private fun TableNameField(editor: SheetEditor, table: SheetTable, onStatus: (String) -> Unit) {
    var value by remember(table, table.name) { mutableStateOf(TextFieldValue(table.name)) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val label = stringResource(R.string.table_name)
    fun commit() {
        val name = value.text.trim()
        if (name == table.name) return
        if (editor.renameTable(table, name)) onStatus(Strings.get(R.string.status_table_renamed, name))
        else { onStatus(tableRefusalText(editor.tableRefusal, name)); value = TextFieldValue(table.name) }
    }
    BasicTextField(
        value = value, onValueChange = { value = it }, singleLine = true,
        textStyle = AeroType.body.copy(color = PicoTheme.colorScheme.labelPrimary),
        cursorBrush = SolidColor(PicoTheme.colorScheme.interaction),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focusManager.clearFocus() }),
        modifier = Modifier.width(120.dp).clip(RoundedCornerShape(4.dp)).background(PicoTheme.colorScheme.fillTertiary)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .onFocusChanged { if (focused && !it.isFocused) commit(); focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false
                else when (e.key) {
                    Key.Enter, Key.NumPadEnter -> { commit(); focusManager.clearFocus(); true }
                    Key.Escape -> { value = TextFieldValue(table.name); focusManager.clearFocus(); true }
                    else -> false
                }
            }
            .semantics { contentDescription = label },
    )
}

/**
 * Excel's Format as Table gallery: every built-in style — Light, Medium, Dark — drawn as it will
 * look. Outside a table a pick asks Excel's Create Table ([onCreate] with the style: where the data is,
 * whether it has headers); inside one it restyles it, and Clear takes the style off.
 */
@Composable
internal fun TableStylesGallery(editor: SheetEditor, onStatus: (String) -> Unit, onCreate: (String) -> Unit, onDone: () -> Unit) {
    val table = editor.selectedTable
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TableLooks.Family.entries.forEach { family ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(familyName(family), style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary)
                    TableLooks.ALL.filter { it.family == family }.chunked(7).forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            line.forEach { style ->
                                val name = familyName(family) + " " + style.number
                                TableSwatch(style, selected = table?.style == style.name, label = name) {
                                    if (table != null) {
                                        editor.setTableStyle(table, style.name)
                                        onStatus(Strings.get(R.string.status_table_styled, table.name, name))
                                        onDone()
                                    } else { onDone(); onCreate(style.name) }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (table != null) {
            MenuDivider()
            FlyoutMenu(width = 240.dp) { MenuCommand(stringResource(R.string.clear), { editor.setTableStyle(table, null); onDone() }, icon = "ic_eraser") }
        }
    }
}

/** A style's look on a little table — a header and four banded rows of five columns — as Excel's gallery shows it. */
@Composable
private fun TableSwatch(style: TableLooks.Style, selected: Boolean, label: String, onClick: () -> Unit) {
    val sample = remember(style.name) { SheetTable("", 0, 0, 4, 4, style = style.name) }
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier.size(44.dp, 32.dp).clip(shape).background(Color.White)
            .border(if (selected) BorderStroke(2.dp, PicoTheme.colorScheme.interaction) else BorderStroke(0.5.dp, PicoTheme.colorScheme.dividerLine), shape)
            .semantics { contentDescription = label }
            .press(onClick = onClick)
            .padding(3.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cw = size.width / 5
            val rh = size.height / 5
            for (r in 0..4) for (c in 0..4) {
                val look = TableLooks.lookAt(sample, r, c) ?: continue
                val x = c * cw
                val y = r * rh
                look.fill?.let { drawRect(Color(it), Offset(x, y), Size(cw, rh)) }
                // A dash of text in the cell's colour and weight.
                drawLine(Color(look.color ?: 0xFF404040).copy(alpha = 0.6f), Offset(x + cw * 0.22f, y + rh * 0.5f), Offset(x + cw * 0.78f, y + rh * 0.5f),
                    strokeWidth = if (look.bold) 1.6f else 0.9f)
                fun side(line: com.therealityteam.aeropages.model.BorderLine?, a: Offset, b: Offset) {
                    line ?: return
                    drawLine(Color(line.color ?: 0xFF000000), a, b, strokeWidth = if (line.style == LineStyle.MEDIUM || line.style == LineStyle.DOUBLE) 1.4f else 0.8f)
                }
                side(look.top, Offset(x, y), Offset(x + cw, y))
                side(look.bottom, Offset(x, y + rh), Offset(x + cw, y + rh))
                side(look.left, Offset(x, y), Offset(x, y + rh))
                side(look.right, Offset(x + cw, y), Offset(x + cw, y + rh))
            }
        }
    }
}

/**
 * Excel's Create Table (Insert ▸ Table, or a style picked from Format as Table outside a table): where the table's data
 * is — the selection or the data around the active cell, editable — and whether its first row holds headers (guessed as
 * Excel does); OK makes it a table in [style] and [onCreated] follows (to Table Design).
 */
@Composable
internal fun CreateTableDialog(editor: SheetEditor, style: String, onDismiss: () -> Unit, onStatus: (String) -> Unit, onCreated: () -> Unit) {
    val guess = remember { editor.tableGuess() }
    var range by remember { mutableStateOf("=" + (guess?.first ?: "")) }
    var headers by remember { mutableStateOf(guess?.second ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    val where = stringResource(R.string.table_data_where)
    val badRange = stringResource(R.string.table_bad_range)
    AeroDialog(stringResource(R.string.create_table), onDismiss, width = 380.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), {
            if (editor.createTable(range, style, headers)) {
                onStatus(Strings.get(R.string.status_table_formatted, editor.selectionRef())); onCreated(); onDismiss()
            } else error = if (com.therealityteam.aeropages.model.A1.parseRange(range.trim().removePrefix("=")) == null) badRange else tableRefusalText(editor.tableRefusal)
        }, primary = true, enabled = range.isNotBlank())
    }) {
        Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(where, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
            InputBox(range, { range = it; error = null }, Modifier.width(380.dp), description = where, monospace = true)
            CheckOption(stringResource(R.string.table_has_headers), headers, { headers = !headers })
            error?.let { FieldError(it) }
        }
    }
}
