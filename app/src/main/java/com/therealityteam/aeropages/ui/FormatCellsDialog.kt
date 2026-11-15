package com.therealityteam.aeropages.ui

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.drawText
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.FormatChoices
import com.therealityteam.aeropages.editor.FormatChoices.Category
import com.therealityteam.aeropages.editor.NumberFormatting
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.BorderLine
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.FormatCode
import com.therealityteam.aeropages.model.LineStyle
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.VAlign

/** Custom codes offered as starting points (Excel's list, with the Chinese ones people reach for). */
private val CODE_PRESETS = listOf(
    "#,##0.00;[Red]-#,##0.00", "0.0,,\"M\"", "#,##0,\"K\"", "0.00%", "\"¥\"#,##0.00;[Red]\"¥\"-#,##0.00",
    "yyyy\"年\"m\"月\"d\"日\"", "yyyy/m/d", "dddd, mmmm d, yyyy", "m\"月\"d\"日\" aaaa", "h:mm AM/PM", "[h]:mm:ss",
    "# ?/?", "0.00E+00", "000000", "[DBNum2][\$-804]General", ";;;",
)

/** A border edge's state in the dialog: as it is, none, or a line. */
private sealed interface Edge {
    data object Keep : Edge
    data object Off : Edge
    data class On(val line: BorderLine) : Edge
}

private enum class FormatTab(val label: Int) {
    NUMBER(R.string.fmt_tab_number), ALIGNMENT(R.string.fmt_tab_alignment), FONT(R.string.fmt_tab_font),
    BORDER(R.string.fmt_tab_border), FILL(R.string.fmt_tab_fill), PROTECTION(R.string.fmt_tab_protection)
}

private fun categoryLabel(c: Category): Int = when (c) {
    Category.GENERAL -> R.string.general_2; Category.NUMBER -> R.string.fmt_cat_number; Category.CURRENCY -> R.string.fmt_cat_currency
    Category.ACCOUNTING -> R.string.fmt_cat_accounting; Category.DATE -> R.string.fmt_cat_date; Category.TIME -> R.string.fmt_cat_time
    Category.PERCENT -> R.string.fmt_cat_percent; Category.FRACTION -> R.string.fmt_cat_fraction; Category.SCIENTIFIC -> R.string.fmt_cat_scientific
    Category.TEXT -> R.string.numfmt_text; Category.CUSTOM -> R.string.numfmt_custom
}

/**
 * Excel's Format Cells: Number (a category and its options — decimal places, thousands separator, negatives,
 * currency symbol, date / time / fraction types — or a custom code), Alignment, Font, Border, Fill and
 * Protection, each tab opening on the active cell's settings; OK applies what was changed, one undo step.
 */
@Composable
internal fun CellFormatDialog(editor: SheetEditor, onDismiss: () -> Unit, onStatus: (String) -> Unit, startTab: Int = 0) {
    val cell = editor.sheet.cell(editor.selRow, editor.selCol) ?: Cell()
    var tab by remember { mutableStateOf(FormatTab.entries.getOrElse(startTab) { FormatTab.NUMBER }) }
    // Number.
    var choice by remember { mutableStateOf(FormatChoices.choiceOf(cell.formatCode, cell.numberFormat, cell.decimals)) }
    var numberTouched by remember { mutableStateOf(false) }
    fun pick(c: FormatChoices.Choice) { choice = c; numberTouched = true }
    // Alignment.
    var hAlign by remember { mutableStateOf(cell.align) }
    var vAlign by remember { mutableStateOf(cell.vAlign) }
    var wrap by remember { mutableStateOf(cell.wrap) }
    var rotation by remember { mutableIntStateOf(cell.rotation) }
    var stacked by remember { mutableStateOf(cell.stacked) }
    var indent by remember { mutableIntStateOf(cell.indent) }
    var spread by remember { mutableStateOf(cell.spread) }
    var shrink by remember { mutableStateOf(cell.shrink) }
    var merged by remember { mutableStateOf(editor.isMergedSelection) }
    var alignTouched by remember { mutableStateOf(false) }
    var mergeTouched by remember { mutableStateOf(false) }
    // Font.
    val f = cell.format
    var family by remember { mutableStateOf(f.fontFamily) }
    var size by remember { mutableStateOf(f.fontSizePt) }
    var bold by remember { mutableStateOf(f.bold) }
    var italic by remember { mutableStateOf(f.italic) }
    var underline by remember { mutableStateOf(f.underline) }
    var strike by remember { mutableStateOf(f.strikethrough) }
    var color by remember { mutableStateOf(f.color) }
    var fontTouched by remember { mutableStateOf(false) }
    // Border.
    var pen by remember { mutableStateOf(editor.borderPen) }
    val edges = remember { mutableStateOf(List<Edge>(6) { Edge.Keep }) }   // top, bottom, left, right, inside horizontal, inside vertical
    // Fill and protection.
    var fill by remember { mutableStateOf(f.highlight) }
    var fillTouched by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(!cell.unlocked) }
    var lockTouched by remember { mutableStateOf(false) }

    val code = FormatChoices.code(choice)
    val codeOk = code == null || FormatCode.isValid(code)

    WideDialog(title = stringResource(R.string.format_cells), onDismiss = onDismiss, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(stringResource(R.string.cancel), onDismiss)
            DialogButton(stringResource(R.string.apply), {
                if (!codeOk) return@DialogButton
                val e = edges.value
                editor.applyCellFormat(merge = if (mergeTouched) merged else null) { c, top, bottom, left, right ->
                    if (numberTouched) {
                        if (code == null) { c.numberFormat = NumberFormat.GENERAL; c.formatCode = null } else { c.formatCode = code; c.numberFormat = FormatCode.category(code) }
                        c.decimals = -1
                    }
                    if (alignTouched) {
                        c.align = hAlign; c.vAlign = vAlign; c.wrap = wrap
                        c.rotation = if (stacked) 0 else rotation; c.stacked = stacked; c.indent = indent
                        c.spread = spread; c.shrink = shrink && !wrap
                    }
                    if (fontTouched) {
                        val change = { x: com.therealityteam.aeropages.model.CharacterFormat ->
                            x.fontFamily = family; x.fontSizePt = size; x.bold = bold; x.italic = italic; x.underline = underline; x.strikethrough = strike; x.color = color
                        }
                        change(c.format); c.richRuns()?.forEach { change(it.format) }
                    }
                    fun side(edge: Edge, set: (BorderLine?) -> Unit) { when (edge) { Edge.Keep -> {}; Edge.Off -> set(null); is Edge.On -> set(edge.line) } }
                    side(if (top) e[0] else e[4]) { c.borderTop = it }
                    side(if (bottom) e[1] else e[4]) { c.borderBottom = it }
                    side(if (left) e[2] else e[5]) { c.borderLeft = it }
                    side(if (right) e[3] else e[5]) { c.borderRight = it }
                    if (fillTouched) c.format.highlight = fill
                    if (lockTouched) c.unlocked = !locked
                }
                editor.borderPen = pen
                onStatus(Strings.get(R.string.status_cell_format_applied, code ?: Strings.get(categoryLabel(choice.category))))
                onDismiss()
            }, primary = codeOk)
        }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            DialogTabs(FormatTab.entries, tab, { stringResource(it.label) }, { tab = it }, width = 600.dp)
            Box(Modifier.fillMaxWidth().height(380.dp)) {
                when (tab) {
                    FormatTab.NUMBER -> NumberTab(editor, choice, ::pick, code, codeOk)
                    FormatTab.ALIGNMENT -> Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                            // Excel's Text alignment: Horizontal (with its Indent) and Vertical drop-downs.
                            DialogLabel(stringResource(R.string.fmt_text_alignment))
                            // Excel's eight: General, Left, Center, Right, Fill, Justify, Center Across Selection, Distributed.
                            val hs = listOf<Triple<TextAlign?, com.therealityteam.aeropages.model.CellSpread?, Int>>(
                                Triple(null, null, R.string.general_2), Triple(TextAlign.LEFT, null, R.string.align_left), Triple(TextAlign.CENTER, null, R.string.center),
                                Triple(TextAlign.RIGHT, null, R.string.align_right), Triple(TextAlign.LEFT, com.therealityteam.aeropages.model.CellSpread.FILL, R.string.halign_fill),
                                Triple(TextAlign.JUSTIFY, null, R.string.justify), Triple(TextAlign.CENTER, com.therealityteam.aeropages.model.CellSpread.CENTER_ACROSS, R.string.halign_center_across),
                                Triple(TextAlign.JUSTIFY, com.therealityteam.aeropages.model.CellSpread.DISTRIBUTED, R.string.halign_distributed),
                            )
                            val current = hs.firstOrNull { it.first == hAlign && it.second == spread } ?: hs[0]
                            FieldRow(stringResource(R.string.fmt_horizontal), labelWidth = 96.dp) {
                                ComboField(stringResource(current.third), hs, { stringResource(it.third) }, current,
                                    { hAlign = it.first; spread = it.second; alignTouched = true; if (it.first == TextAlign.CENTER || it.second == com.therealityteam.aeropages.model.CellSpread.FILL) indent = 0 }, width = 200.dp)
                            }
                            // Indent: as in Excel, indenting General or centred text makes it left aligned.
                            val indentLabel = stringResource(R.string.cell_indent)
                            FieldRow(indentLabel, labelWidth = 96.dp) {
                                SpinBox(indent.toString(), { d ->
                                    indent = (indent + d).coerceIn(0, Cell.MAX_INDENT); alignTouched = true
                                    if (indent > 0 && hAlign != TextAlign.RIGHT && spread != com.therealityteam.aeropages.model.CellSpread.DISTRIBUTED) { hAlign = TextAlign.LEFT; spread = null }
                                }, description = indentLabel, canLess = indent > 0, canMore = indent < Cell.MAX_INDENT)
                            }
                            val vs = listOf(VAlign.TOP to R.string.top_align, VAlign.MIDDLE to R.string.middle_align, VAlign.BOTTOM to R.string.bottom_align)
                            FieldRow(stringResource(R.string.fmt_vertical), labelWidth = 96.dp) {
                                ComboField(stringResource(vs.firstOrNull { it.first == vAlign }?.second ?: R.string.bottom_align), vs, { stringResource(it.second) }, vs.firstOrNull { it.first == vAlign },
                                    { vAlign = it.first; alignTouched = true }, width = 180.dp)
                            }
                            DialogLabel(stringResource(R.string.fmt_text_control))
                            CheckRow(stringResource(R.string.wrap_text), wrap) { wrap = !wrap; alignTouched = true }
                            // Shrink to fit is for text on one line: Excel greys it out while Wrap text is on.
                            CheckOption(stringResource(R.string.shrink_to_fit), shrink && !wrap, { shrink = !shrink; alignTouched = true }, enabled = !wrap)
                            CheckRow(stringResource(R.string.fmt_merge_cells), merged) { merged = !merged; mergeTouched = true }
                        }
                        // Excel's Orientation: the stacked "Text" beside the half dial, and the degrees.
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            DialogLabel(stringResource(R.string.text_orientation))
                            OrientationDial(rotation, stacked, { rotation = it; stacked = false; alignTouched = true }, { stacked = !stacked; alignTouched = true })
                            val degreesLabel = stringResource(R.string.cell_degrees)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SpinBox(if (stacked) "0" else rotation.toString(), { d -> rotation = (rotation + d).coerceIn(-90, 90); stacked = false; alignTouched = true },
                                    description = degreesLabel, canLess = !stacked && rotation > -90, canMore = !stacked && rotation < 90, dim = stacked)
                                Text(degreesLabel, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
                            }
                        }
                    }
                    FormatTab.FONT -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                        // Excel's Font tab: Font and Size drop-downs, the styles and effects as check boxes, Office's colours.
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                DialogLabel(stringResource(R.string.font))
                                ComboField(family ?: "Calibri", TextStyling.FONT_FAMILIES, { it }, family ?: "Calibri", { family = it; fontTouched = true }, width = 240.dp)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                DialogLabel(stringResource(R.string.font_size))
                                ComboField((size ?: 11f).toInt().toString(), TextStyling.FONT_SIZES, { it.toInt().toString() }, size ?: 11f, { size = it; fontTouched = true }, width = 96.dp)
                            }
                        }
                        DialogLabel(stringResource(R.string.fmt_style))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CheckOption(stringResource(R.string.bold), bold, { bold = !bold; fontTouched = true })
                            CheckOption(stringResource(R.string.italic), italic, { italic = !italic; fontTouched = true })
                            CheckOption(stringResource(R.string.underline), underline, { underline = !underline; fontTouched = true })
                            CheckOption(stringResource(R.string.strikethrough), strike, { strike = !strike; fontTouched = true })
                        }
                        DialogLabel(stringResource(R.string.fmt_color))
                        ColorPalette(color, { color = it; fontTouched = true }, noneLabel = stringResource(R.string.fmt_automatic))
                        Sample(Modifier.fillMaxWidth().height(56.dp)) {
                            Text(stringResource(R.string.fmt_sample), fontSize = ((size ?: 11f) * 1.4f).coerceAtMost(40f).sp, color = Color(color ?: 0xFF1A1A1A),
                                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                                textDecoration = TextDecoration.combine(listOfNotNull(TextDecoration.Underline.takeIf { underline }, TextDecoration.LineThrough.takeIf { strike })))
                        }
                    }
                    FormatTab.BORDER -> BorderTab(pen, { pen = it }, edges.value) { edges.value = it }
                    FormatTab.FILL -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            DialogLabel(stringResource(R.string.fmt_background))
                            ColorPalette(fill, { fill = it; fillTouched = true }, noneLabel = stringResource(R.string.fmt_no_color))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            DialogLabel(stringResource(R.string.preview))
                            Box(Modifier.width(220.dp).height(64.dp).clip(RoundedCornerShape(8.dp)).background(Color(fill ?: 0xFFFFFFFF))
                                .border(BorderStroke(1.dp, PicoTheme.colorScheme.dividerLine), RoundedCornerShape(8.dp)))
                        }
                    }
                    FormatTab.PROTECTION -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CheckRow(stringResource(R.string.fmt_locked), locked) { locked = !locked; lockTouched = true }
                        Text(stringResource(R.string.fmt_protect_hint), style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun Sample(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).border(BorderStroke(1.dp, PicoTheme.colorScheme.dividerLine), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart) { content() }
}

/** How a value looks in [code] (General: as typed). */
private fun shown(value: String, code: String?) =
    NumberFormatting.shown(value, Cell(text = value, numberFormat = if (code == null) NumberFormat.GENERAL else FormatCode.category(code), formatCode = code),
        com.therealityteam.aeropages.model.NumberSymbols.current(), FormatCode.Lang.current())

@Composable
private fun NumberTab(editor: SheetEditor, choice: FormatChoices.Choice, pick: (FormatChoices.Choice) -> Unit, code: String?, codeOk: Boolean) {
    val raw = editor.rawValue(editor.selRow, editor.selCol)
    val number = raw.toDoubleOrNull()
    // What the options are shown on: the cell's number, else a sample fitting the category.
    val sample = when (choice.category) {
        Category.DATE, Category.TIME -> number?.toString() ?: (java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.of(1899, 12, 30), java.time.LocalDate.now()) + 0.5625).toString()
        Category.FRACTION -> number?.toString() ?: "1.25"
        else -> number?.toString() ?: raw.ifEmpty { "1234.5" }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // Excel's Category list box.
        Column(Modifier.width(160.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DialogLabel(stringResource(R.string.fmt_category), Modifier.padding(start = 12.dp, bottom = 4.dp))
            Category.entries.forEach { c ->
                ListRow(stringResource(categoryLabel(c)), choice.category == c, {
                    pick(if (c == Category.CUSTOM) choice.copy(category = c, custom = FormatChoices.code(choice) ?: "General") else choice.copy(category = c, type = 0))
                })
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val preview = if (codeOk) shown(raw.ifEmpty { sample }, code) else null
            Sample(Modifier.fillMaxWidth().height(44.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.preview), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                    Text(preview?.text ?: "—", style = PicoTheme.typography.bodyLarge, color = preview?.color?.let { Color(it) } ?: Color(0xFF1A1A1A))
                }
            }
            val c = choice
            if (c.category in setOf(Category.NUMBER, Category.CURRENCY, Category.ACCOUNTING, Category.PERCENT, Category.SCIENTIFIC)) {
                FieldRow(stringResource(R.string.decimal_places), labelWidth = 96.dp) {
                    SpinBox("${c.decimals}", { d -> pick(c.copy(decimals = (c.decimals + d).coerceIn(0, FormatChoices.MAX_DECIMALS))) },
                        description = stringResource(R.string.decimal_places), canLess = c.decimals > 0, canMore = c.decimals < FormatChoices.MAX_DECIMALS, valueWidth = 36.dp)
                }
            }
            if (c.category == Category.NUMBER) CheckRow(stringResource(R.string.fmt_thousands), c.thousands) { pick(c.copy(thousands = !c.thousands)) }
            if (c.category == Category.CURRENCY || c.category == Category.ACCOUNTING) {
                val none = stringResource(R.string.none)
                FieldRow(stringResource(R.string.fmt_symbol), labelWidth = 96.dp) {
                    ComboField(FormatChoices.SYMBOLS[c.symbol].ifEmpty { none }, FormatChoices.SYMBOLS.indices.toList(), { i -> FormatChoices.SYMBOLS[i].ifEmpty { none } }, c.symbol,
                        { i -> pick(c.copy(symbol = i)) }, width = 140.dp)
                }
            }
            if (c.category == Category.NUMBER || c.category == Category.CURRENCY) {
                DialogLabel(stringResource(R.string.fmt_negative))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (n in 0 until FormatChoices.NEGATIVE_STYLES) {
                        val look = shown("-1234.1", FormatChoices.code(c.copy(negative = n)))
                        OptionRow(look.text, look.color, c.negative == n) { pick(c.copy(negative = n)) }
                    }
                }
            }
            val types = when (c.category) { Category.DATE -> FormatChoices.DATE_CODES; Category.TIME -> FormatChoices.TIME_CODES; Category.FRACTION -> FormatChoices.FRACTION_CODES; else -> null }
            if (types != null) {
                DialogLabel(stringResource(R.string.fmt_type))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    types.forEachIndexed { i, t -> val look = shown(sample, t); OptionRow(look.text, look.color, c.type == i) { pick(c.copy(type = i)) } }
                }
            }
            if (c.category == Category.CUSTOM) {
                val codeLabel = stringResource(R.string.format_code)
                // The kit's input box, its label on the editable field itself (readers and tests find it by name).
                InputBox(c.custom, { pick(c.copy(custom = it)) }, Modifier.fillMaxWidth(), placeholder = codeLabel, description = codeLabel, monospace = true)
                Text(if (codeOk) stringResource(R.string.format_code_hint) else stringResource(R.string.format_code_invalid), style = AeroType.caption,
                    color = if (codeOk) PicoTheme.colorScheme.labelSecondary else Aero.danger)
                DialogLabel(stringResource(R.string.format_code_presets) + " · " + codeLabel)
                // Excel's Type list: the codes in a list box, the one in use marked.
                Column(Modifier.fillMaxWidth().heightIn(max = 168.dp).clip(RoundedCornerShape(10.dp)).background(Aero.field).verticalScroll(rememberScrollState()).padding(4.dp)) {
                    CODE_PRESETS.forEach { p ->
                        ListRow(c.custom == p, { pick(c.copy(custom = p)) }) {
                            Text(p, style = AeroType.body.copy(fontFamily = FontFamily.Monospace), color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** One of a list of looks (a negative style, a date type): the look itself, picked or not. */
@Composable
private fun OptionRow(text: String, color: Long?, selected: Boolean, onClick: () -> Unit) =
    ListRow(text, selected, onClick, color = color?.let { Color(it) } ?: Aero.ink)

@Composable
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    return this.clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick).controllerHapticFeedback(interactionSource = interaction)
}

/** The line styles the Border tab offers, thin to double. */
private val PEN_STYLES = listOf(LineStyle.THIN, LineStyle.HAIR, LineStyle.DOTTED, LineStyle.DASHED, LineStyle.DASH_DOT, LineStyle.MEDIUM, LineStyle.MEDIUM_DASHED, LineStyle.THICK, LineStyle.DOUBLE)

/**
 * The Border tab: presets (none, outline, inside), each line of the selection to switch on or off — the outer four and
 * the inside horizontal and vertical ones — drawn in the chosen style and colour, and a picture of the result.
 */
@Composable
private fun BorderTab(pen: BorderLine, onPen: (BorderLine) -> Unit, edges: List<Edge>, onEdges: (List<Edge>) -> Unit) {
    fun with(vararg set: Pair<Int, Edge>) = onEdges(edges.toMutableList().also { l -> set.forEach { (i, e) -> l[i] = e } })
    val on = Edge.On(pen)
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogLabel(stringResource(R.string.fmt_presets))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RibbonSmallButton("ic_no_color", stringResource(R.string.none), { with(0 to Edge.Off, 1 to Edge.Off, 2 to Edge.Off, 3 to Edge.Off, 4 to Edge.Off, 5 to Edge.Off) })
                RibbonSmallButton("ic_shape_rect", stringResource(R.string.fmt_border_outline), { with(0 to on, 1 to on, 2 to on, 3 to on) })
                RibbonSmallButton("ic_borders", stringResource(R.string.fmt_border_inside), { with(4 to on, 5 to on) })
            }
            DialogLabel(stringResource(R.string.fmt_border_sides))
            WrapRow(spacing = 12.dp, lineSpacing = 0.dp) {
                listOf(R.string.fmt_side_top, R.string.fmt_side_bottom, R.string.fmt_side_left, R.string.fmt_side_right, R.string.fmt_side_inside_h, R.string.fmt_side_inside_v)
                    .forEachIndexed { i, label -> CheckOption(stringResource(label), edges[i] is Edge.On, { with(i to if (edges[i] is Edge.On) Edge.Off else on) }) }
            }
            DialogLabel(stringResource(R.string.fmt_line_style))
            WrapRow {
                PEN_STYLES.forEach { st ->
                    Box(
                        Modifier.width(56.dp).height(30.dp).clip(RoundedCornerShape(Aero.commandRadius))
                            .background(if (pen.style == st) Aero.tint(LocalAccent.current) else Aero.field)
                            .clickableRow { onPen(pen.copy(style = st)) },
                        contentAlignment = Alignment.Center,
                    ) { Canvas(Modifier.width(40.dp).height(10.dp)) { drawPen(BorderLine(st, 0xFF1A1A1A), Offset(0f, size.height / 2), Offset(size.width, size.height / 2)) } }
                }
            }
            DialogLabel(stringResource(R.string.fmt_color))
            ColorPalette(pen.color, { onPen(pen.copy(color = it)) }, noneLabel = stringResource(R.string.fmt_automatic))
        }
        // The selection as two by two cells with the lines it will have (lines left as they are: faint).
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DialogLabel(stringResource(R.string.preview))
            Canvas(Modifier.size(200.dp, 140.dp).background(Color.White)) {
                val l = 20f; val t = 20f; val r = size.width - 20f; val b = size.height - 20f
                val mx = (l + r) / 2; val my = (t + b) / 2
                val faint = Color(0xFFDADCE0)
                fun line(e: Edge, a: Offset, z: Offset) = when (e) {
                    is Edge.On -> drawPen(e.line, a, z)
                    Edge.Keep -> drawLine(faint, a, z, 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    Edge.Off -> Unit
                }
                listOf(Offset(l + 14, (t + my) / 2), Offset(mx + 14, (t + my) / 2), Offset(l + 14, (my + b) / 2), Offset(mx + 14, (my + b) / 2)).forEach {
                    drawRect(Color(0xFFB0B4BA), it.copy(y = it.y - 3f), androidx.compose.ui.geometry.Size((mx - l) * 0.5f, 6f))
                }
                line(edges[0], Offset(l, t), Offset(r, t)); line(edges[1], Offset(l, b), Offset(r, b))
                line(edges[2], Offset(l, t), Offset(l, b)); line(edges[3], Offset(r, t), Offset(r, b))
                line(edges[4], Offset(l, my), Offset(r, my)); line(edges[5], Offset(mx, t), Offset(mx, b))
            }
        }
    }
}

/** [line] from [a] to [z] as the grid draws it: its width, dashes, a double line as two. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPen(line: BorderLine, a: Offset, z: Offset) {
    val c = Color(line.color ?: 0xFF000000)
    val w = line.style.width.dp.toPx()
    if (line.style == LineStyle.DOUBLE) {
        val gap = 1.5.dp.toPx()
        val horizontal = a.y == z.y
        val d = if (horizontal) Offset(0f, gap) else Offset(gap, 0f)
        drawLine(c, a - d, z - d, 1.dp.toPx()); drawLine(c, a + d, z + d, 1.dp.toPx())
        return
    }
    drawLine(c, a, z, w, pathEffect = line.style.dash?.let { d -> PathEffect.dashPathEffect(d.map { it.dp.toPx() }.toFloatArray()) })
}

/**
 * Excel's Orientation box: a tall tile with "Text" one letter under another (Vertical Text, framed in the accent when on)
 * and a half dial whose hand, labelled "Text", shows the angle — tapped or dragged anywhere on it to turn the text
 * −90…90 degrees, whole degrees.
 */
@Composable
private fun OrientationDial(degrees: Int, stacked: Boolean, onDegrees: (Int) -> Unit, onStacked: () -> Unit) {
    val accent = LocalAccent.current
    val ink = PicoTheme.colorScheme.labelSecondary
    val word = stringResource(R.string.orientation_text_sample)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.width(36.dp).height(132.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .background(if (stacked) accent.copy(alpha = 0.12f) else Aero.field)
                .then(if (stacked) Modifier.border(androidx.compose.foundation.BorderStroke(1.5.dp, accent), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) else Modifier)
                .press(onClick = onStacked).semantics { contentDescription = word; selected = stacked; role = androidx.compose.ui.semantics.Role.Checkbox },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) { Cell.stackedLetters(word).forEach { ch -> Text(ch, style = AeroType.body, color = if (stacked) accent else PicoTheme.colorScheme.labelPrimary) } }
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val label = measurer.measure(word, AeroType.caption.copy(color = if (stacked) ink else accent))
        fun angleAt(o: androidx.compose.ui.geometry.Offset, size: androidx.compose.ui.unit.IntSize): Int {
            val cx = size.width * 0.12f; val cy = size.height / 2f
            val deg = Math.toDegrees(kotlin.math.atan2((cy - o.y).toDouble(), (o.x - cx).toDouble().coerceAtLeast(0.0)))
            return Math.round(deg).toInt().coerceIn(-90, 90)
        }
        androidx.compose.foundation.Canvas(
            Modifier.width(132.dp).height(132.dp)
                .pointerInput(Unit) { detectTapGestures { onDegrees(angleAt(it, size)) } }
                .pointerInput(Unit) { detectDragGestures { change, _ -> onDegrees(angleAt(change.position, size)) } }
                .semantics { contentDescription = word },
        ) {
            val cx = size.width * 0.12f; val cy = size.height / 2f
            val r = minOf(size.width - cx - 6.dp.toPx(), cy - 6.dp.toPx())
            // The half circle and its marks: a dot every 15 degrees, larger at 0, ±45 and ±90.
            drawArc(ink.copy(alpha = 0.35f), -90f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(cx - r, cy - r),
                size = androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            for (d in -90..90 step 15) {
                val a = Math.toRadians(d.toDouble())
                val big = d % 45 == 0
                drawCircle(if (!stacked && d == degrees) accent else ink.copy(alpha = if (big) 0.8f else 0.45f), (if (big) 3f else 2f) * density,
                    androidx.compose.ui.geometry.Offset(cx + r * kotlin.math.cos(a).toFloat(), cy - r * kotlin.math.sin(a).toFloat()))
            }
            if (!stacked) {
                // The hand: "Text" along it, then a line to the rim.
                rotate(-degrees.toFloat(), pivot = androidx.compose.ui.geometry.Offset(cx, cy)) {
                    drawText(label, topLeft = androidx.compose.ui.geometry.Offset(cx + 4.dp.toPx(), cy - label.size.height / 2f))
                    drawLine(accent, androidx.compose.ui.geometry.Offset(cx + 8.dp.toPx() + label.size.width, cy), androidx.compose.ui.geometry.Offset(cx + r - 6.dp.toPx(), cy), 1.5.dp.toPx())
                }
            }
        }
    }
}

