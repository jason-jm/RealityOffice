package com.therealityteam.aeropages.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.AlertDialog
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.CondEval
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.pdf.CondIcons
import com.therealityteam.aeropages.model.CondFormat
import com.therealityteam.aeropages.model.CondStyle
import com.therealityteam.aeropages.model.CondType
import com.therealityteam.aeropages.model.DatePeriod
import com.therealityteam.aeropages.model.IconSetKind
import com.therealityteam.aeropages.model.ScaleKind
import com.therealityteam.aeropages.model.ScalePoint
import com.therealityteam.aeropages.model.Workbook

/** What the conditional-format dialog shows: Excel's menu of rules, and its rule manager. */
internal enum class CondCategory { HIGHLIGHT, TOP_BOTTOM, DATA_BARS, COLOR_SCALES, ICON_SETS, FORMULA, MANAGE }

/** Excel's colour-scale presets, low to high (two or three colours). */
private val SCALE_PRESETS: List<List<Long>> = listOf(
    listOf(0xFFF8696B, 0xFFFFEB84, 0xFF63BE7B), listOf(0xFF63BE7B, 0xFFFFEB84, 0xFFF8696B),
    listOf(0xFFF8696B, 0xFFFCFCFF, 0xFF63BE7B), listOf(0xFF63BE7B, 0xFFFCFCFF, 0xFFF8696B),
    listOf(0xFFF8696B, 0xFFFCFCFF, 0xFF5A8AC6), listOf(0xFF5A8AC6, 0xFFFCFCFF, 0xFFF8696B),
    listOf(0xFFFCFCFF, 0xFFF8696B), listOf(0xFFF8696B, 0xFFFCFCFF), listOf(0xFFFCFCFF, 0xFF63BE7B),
    listOf(0xFF63BE7B, 0xFFFCFCFF), listOf(0xFFFFEF9C, 0xFF63BE7B), listOf(0xFF63BE7B, 0xFFFFEF9C),
)

/** Excel's data-bar colours: blue, green, red, orange, light blue, purple. */
private val BAR_COLORS = listOf(0xFF638EC6, 0xFF63C384, 0xFFFF555A, 0xFFFFB628, 0xFF008AEF, 0xFFD6007B)

/**
 * Conditional formatting, as Excel's Home → Conditional Formatting lays it out: rules that highlight cells
 * (values compared, text, dates, duplicates, blanks, errors), top and bottom rules, data bars, colour
 * scales, icon sets and formulas — each over the selection, in one of Excel's looks — and the rules of the
 * selection or the sheet to reorder, stop, delete or clear.
 */
@Composable
internal fun CondFormatDialog(editor: SheetEditor, onDismiss: () -> Unit, onApplied: (String) -> Unit, start: CondCategory = CondCategory.HIGHLIGHT) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    var category by remember { mutableStateOf(start) }
    // Highlight and top/bottom choices.
    var kind by remember { mutableStateOf(CondType.GREATER) }
    var rankKind by remember { mutableIntStateOf(0) }   // 0 top N, 1 top %, 2 bottom N, 3 bottom %, 4 above average, 5 below average
    var v1 by remember { mutableStateOf("") }
    var v2 by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("10") }
    var text by remember { mutableStateOf("") }
    var period by remember { mutableStateOf(DatePeriod.TODAY) }
    var look by remember { mutableIntStateOf(0) }
    var formula by remember { mutableStateOf("=") }
    // Scales.
    var barColor by remember { mutableLongStateOf(BAR_COLORS[0]) }
    var barOnly by remember { mutableStateOf(false) }
    var scale by remember { mutableIntStateOf(0) }
    var icons by remember { mutableStateOf(IconSetKind.ARROWS_3) }
    var reverse by remember { mutableStateOf(false) }
    var iconsOnly by remember { mutableStateOf(false) }
    // The manager: the selection's rules or the sheet's.
    var wholeSheet by remember { mutableStateOf(false) }
    val applied = stringResource(R.string.status_cond_format_applied)
    val cleared = stringResource(R.string.cf_rules_cleared)

    /** A value typed for a comparison: a number, a formula (=…), or text (Excel puts it in quotes). */
    fun operand(input: String): Pair<Double, String?>? {
        val t = input.trim()
        if (t.isEmpty()) return null
        // In the language's writing (1,5, =ROUND(A1;2)); the rule keeps the standard form.
        if (editor.symbols.canonical) t.toDoubleOrNull()?.let { return it to null }
        else (com.therealityteam.aeropages.editor.EntryParser.parse(t, symbols = editor.symbols)?.value ?: t).toDoubleOrNull()?.let { return it to null }
        if (t.startsWith("=")) return com.therealityteam.aeropages.editor.FormulaLocale.toCanonical(t, editor.symbols).removePrefix("=").trim().takeIf { it.isNotEmpty() }?.let { 0.0 to it }
        val quoted = if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t else "\"" + t.replace("\"", "\"\"") + "\""
        return 0.0 to quoted
    }

    /** The rule the choices make (its cells are the selection's when it is applied); null while something is missing. */
    fun rule(): CondFormat? {
        val style = CondEval.PRESETS[look]
        fun base(type: CondType) = CondFormat(0, 0, 0, 0, type, style = style)
        return when (category) {
            CondCategory.HIGHLIGHT -> when (kind) {
                CondType.BETWEEN, CondType.NOT_BETWEEN -> {
                    val (a, fa) = operand(v1) ?: return null
                    val (b, fb) = operand(v2) ?: return null
                    base(kind).copy(v1 = a, v2 = b, formula1 = fa, formula2 = fb)
                }
                CondType.GREATER, CondType.LESS, CondType.EQUAL, CondType.NOT_EQUAL, CondType.GREATER_EQUAL, CondType.LESS_EQUAL -> {
                    val (a, fa) = operand(v1) ?: return null
                    base(kind).copy(v1 = a, formula1 = fa)
                }
                CondType.TEXT_CONTAINS, CondType.TEXT_NOT_CONTAINS, CondType.TEXT_BEGINS, CondType.TEXT_ENDS ->
                    text.takeIf { it.isNotEmpty() }?.let { base(kind).copy(text = it) }
                CondType.DATE -> base(kind).copy(period = period)
                else -> base(kind)
            }
            CondCategory.TOP_BOTTOM -> {
                val n = count.trim().toDoubleOrNull()?.takeIf { it >= 1.0 }
                when (rankKind) {
                    0 -> base(CondType.TOP).copy(v1 = n ?: return null)
                    1 -> base(CondType.TOP).copy(v1 = n?.coerceAtMost(100.0) ?: return null, percent = true)
                    2 -> base(CondType.BOTTOM).copy(v1 = n ?: return null)
                    3 -> base(CondType.BOTTOM).copy(v1 = n?.coerceAtMost(100.0) ?: return null, percent = true)
                    4 -> base(CondType.ABOVE_AVERAGE)
                    else -> base(CondType.BELOW_AVERAGE)
                }
            }
            CondCategory.DATA_BARS -> CondFormat(0, 0, 0, 0, CondType.DATA_BAR, color = barColor, barMin = 0, barMax = 100, showValue = !barOnly)
            CondCategory.COLOR_SCALES -> {
                val colors = SCALE_PRESETS[scale]
                if (colors.size == 2) CondFormat(0, 0, 0, 0, CondType.COLOR_SCALE, color = colors[1], color2 = colors[0])
                else CondFormat(0, 0, 0, 0, CondType.COLOR_SCALE, color = colors[2], color2 = colors[0], stops = listOf(
                    ScalePoint(ScaleKind.MIN) to colors[0], ScalePoint(ScaleKind.PERCENTILE, 50.0) to colors[1], ScalePoint(ScaleKind.MAX) to colors[2]))
            }
            CondCategory.ICON_SETS -> CondFormat(0, 0, 0, 0, CondType.ICON_SET, icons = icons, reverse = reverse, showValue = !iconsOnly)
            CondCategory.FORMULA -> com.therealityteam.aeropages.editor.FormulaLocale.toCanonical(formula.trim(), editor.symbols).removePrefix("=").trim()
                .takeIf { it.isNotEmpty() }?.let { base(CondType.FORMULA).copy(formula1 = it) }
            CondCategory.MANAGE -> null
        }
    }

    AeroDialog(title = stringResource(R.string.title_cond_format), onDismiss = onDismiss, width = 600.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (category == CondCategory.MANAGE) DialogButton(stringResource(R.string.close), onDismiss, primary = true)
                else {
                    DialogButton(stringResource(R.string.cancel), onDismiss)
                    val made = rule()
                    DialogButton(stringResource(R.string.apply), { made?.let { editor.addCondRule(it); onApplied(applied) } }, primary = true, enabled = made != null)
                }
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(600.dp)) {
                // Excel's Select a Rule Type, as a drop-down at the top: the rest of the dialog is the rule's.
                val categories = listOf(
                    CondCategory.HIGHLIGHT to R.string.cf_highlight, CondCategory.TOP_BOTTOM to R.string.cf_top_bottom,
                    CondCategory.DATA_BARS to R.string.cond_data_bars, CondCategory.COLOR_SCALES to R.string.cond_color_scales,
                    CondCategory.ICON_SETS to R.string.cf_icon_sets, CondCategory.FORMULA to R.string.cf_use_formula,
                    CondCategory.MANAGE to R.string.cf_manage,
                )
                FieldRow(stringResource(R.string.cf_rule_type), labelWidth = 132.dp) {
                    val picked = categories.first { it.first == category }
                    ComboField(stringResource(picked.second), categories, { stringResource(it.second) }, picked, { category = it.first }, width = 320.dp)
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp)) {
                    when (category) {
                        CondCategory.HIGHLIGHT -> {
                            // Excel's Highlight Cells Rules: the rule as a drop-down.
                            val kinds = listOf(
                                CondType.GREATER to R.string.cond_greater_than, CondType.LESS to R.string.cond_less_than,
                                    CondType.BETWEEN to R.string.cond_between, CondType.EQUAL to R.string.cond_equal_to,
                                    CondType.GREATER_EQUAL to R.string.cf_greater_equal, CondType.LESS_EQUAL to R.string.cf_less_equal,
                                    CondType.NOT_EQUAL to R.string.cf_not_equal, CondType.NOT_BETWEEN to R.string.cf_not_between,
                                    CondType.TEXT_CONTAINS to R.string.cf_text_contains, CondType.TEXT_NOT_CONTAINS to R.string.cf_text_not_contains,
                                    CondType.TEXT_BEGINS to R.string.cf_text_begins, CondType.TEXT_ENDS to R.string.cf_text_ends,
                                    CondType.DATE to R.string.cf_date_occurring, CondType.DUPLICATES to R.string.cf_duplicates,
                                    CondType.UNIQUES to R.string.cf_uniques, CondType.BLANKS to R.string.cf_blanks, CondType.ERRORS to R.string.cf_errors,
                            )
                            FieldRow(stringResource(R.string.cf_rule), labelWidth = 132.dp) {
                                ComboField(stringResource(kinds.first { it.first == kind }.second), kinds, { stringResource(it.second) }, kinds.first { it.first == kind }, { kind = it.first }, width = 260.dp)
                            }
                            when (kind) {
                                CondType.BETWEEN, CondType.NOT_BETWEEN -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        RuleField(v1, { v1 = it }, 150)
                                        Text(stringResource(R.string.conn_and), style = AeroType.body)
                                        RuleField(v2, { v2 = it }, 150)
                                    }
                                    Hint(stringResource(R.string.cf_value_hint))
                                }
                                CondType.GREATER, CondType.LESS, CondType.EQUAL, CondType.NOT_EQUAL, CondType.GREATER_EQUAL, CondType.LESS_EQUAL ->
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { RuleField(v1, { v1 = it }, 240); Hint(stringResource(R.string.cf_value_hint)) }
                                CondType.TEXT_CONTAINS, CondType.TEXT_NOT_CONTAINS, CondType.TEXT_BEGINS, CondType.TEXT_ENDS ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(stringResource(R.string.cf_text), style = AeroType.body)
                                        RuleField(text, { text = it }, 240)
                                    }
                                CondType.DATE -> {
                                    val periods = listOf(
                                        DatePeriod.YESTERDAY to R.string.cf_yesterday, DatePeriod.TODAY to R.string.cf_today, DatePeriod.TOMORROW to R.string.cf_tomorrow,
                                        DatePeriod.LAST_7_DAYS to R.string.cf_last_7_days, DatePeriod.LAST_WEEK to R.string.cf_last_week,
                                        DatePeriod.THIS_WEEK to R.string.cf_this_week, DatePeriod.NEXT_WEEK to R.string.cf_next_week,
                                        DatePeriod.LAST_MONTH to R.string.cf_last_month, DatePeriod.THIS_MONTH to R.string.cf_this_month, DatePeriod.NEXT_MONTH to R.string.cf_next_month,
                                    )
                                    ComboField(stringResource(periods.first { it.first == period }.second), periods, { stringResource(it.second) }, periods.first { it.first == period }, { period = it.first }, width = 220.dp)
                                }
                                else -> {}
                            }
                            LookChoice(look) { look = it }
                        }
                        CondCategory.TOP_BOTTOM -> {
                            val ranks = listOf(R.string.cf_top_n, R.string.cf_top_pct, R.string.cf_bottom_n, R.string.cf_bottom_pct, R.string.cf_above_average, R.string.cf_below_average)
                            FieldRow(stringResource(R.string.cf_rule), labelWidth = 132.dp) {
                                ComboField(stringResource(ranks[rankKind]), ranks.indices.toList(), { stringResource(ranks[it]) }, rankKind, { rankKind = it }, width = 260.dp)
                            }
                            if (rankKind < 4) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.cf_count), style = AeroType.body)
                                RuleField(count, { count = it }, 90)
                                if (rankKind == 1 || rankKind == 3) Text("%", style = AeroType.body)
                            }
                            LookChoice(look) { look = it }
                        }
                        CondCategory.DATA_BARS -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { BAR_COLORS.forEach { c -> ColorSwatch(c, { barColor = c }, selected = barColor == c) } }
                            // A preview: four bars from short to long.
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                listOf(0.25f, 0.5f, 0.75f, 1f).forEach { f ->
                                    Box(Modifier.width(220.dp).height(14.dp).background(Color.White)) {
                                        Box(Modifier.width(220.dp * f).height(14.dp).background(Brush.horizontalGradient(listOf(Color(barColor), Color(barColor).copy(alpha = 0.15f))))
                                            .border(BorderStroke(0.75.dp, Color(barColor))))
                                    }
                                }
                            }
                            CheckRow(stringResource(R.string.cf_bar_only), barOnly) { barOnly = !barOnly }
                        }
                        CondCategory.COLOR_SCALES -> WrapRow(spacing = 8.dp) {
                            SCALE_PRESETS.forEachIndexed { i, colors ->
                                val label = colors.joinToString(" → ") { String.format(java.util.Locale.ROOT, "#%06X", it and 0xFFFFFF) }
                                Box(
                                    Modifier.size(72.dp, 36.dp).clip(RoundedCornerShape(6.dp))
                                        .border(BorderStroke(if (scale == i) 2.dp else 0.5.dp, if (scale == i) LocalAccent.current else PicoTheme.colorScheme.dividerLine), RoundedCornerShape(6.dp))
                                        .background(Brush.horizontalGradient(colors.map { Color(it) }))
                                        .semantics { contentDescription = label }
                                        .press { scale = i },
                                )
                            }
                        }
                        CondCategory.ICON_SETS -> {
                            WrapRow(spacing = 8.dp) {
                                IconSetKind.entries.forEach { set ->
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                            .background(if (icons == set) Aero.tint(LocalAccent.current) else Color.White)
                                            .border(BorderStroke(if (icons == set) 1.5.dp else 1.dp, if (icons == set) LocalAccent.current else Aero.barEdge), RoundedCornerShape(6.dp))
                                            .semantics { contentDescription = set.xlsx }
                                            .press { icons = set }.padding(horizontal = 6.dp, vertical = 4.dp),
                                    ) {
                                        androidx.compose.foundation.Canvas(Modifier.size((18 * set.count).dp, 18.dp)) {
                                            val s = size.height
                                            drawIntoCanvas { c -> for (k in 0 until set.count) CondIcons.draw(c.nativeCanvas, set, set.count - 1 - k, k * s, 0f, s) }
                                        }
                                    }
                                }
                            }
                            CheckRow(stringResource(R.string.cf_icons_reverse), reverse) { reverse = !reverse }
                            CheckRow(stringResource(R.string.cf_icons_only), iconsOnly) { iconsOnly = !iconsOnly }
                        }
                        CondCategory.FORMULA -> {
                            RuleField(formula, { formula = it }, 400)
                            // A formula that cannot be worked out at the selection's top-left (it would never be true).
                            val f = com.therealityteam.aeropages.editor.FormulaLocale.toCanonical(formula.trim(), editor.symbols).removePrefix("=").trim()
                            if (f.isNotEmpty() && !editor.formulaWorksHere(f))
                                Text(stringResource(R.string.cf_bad_formula), style = AeroType.caption, color = PicoTheme.colorScheme.error)
                            Hint(com.therealityteam.aeropages.editor.FormulaLocale.inText(stringResource(R.string.cf_formula_hint), editor.symbols))
                            LookChoice(look) { look = it }
                        }
                        CondCategory.MANAGE -> {
                            // Excel's Show formatting rules for: Current Selection / This Worksheet.
                            FieldRow(stringResource(R.string.cf_show_rules_for), labelWidth = 180.dp) {
                                ComboField(stringResource(if (wholeSheet) R.string.cf_whole_sheet else R.string.cf_selection), listOf(false, true),
                                    { stringResource(if (it) R.string.cf_whole_sheet else R.string.cf_selection) }, wholeSheet, { wholeSheet = it }, width = 200.dp)
                            }
                            val rules = editor.condRules(wholeSheet)
                            val count = editor.condRules(true).size
                            ListBox(height = 260.dp, empty = if (rules.isEmpty()) stringResource(R.string.cf_no_rules) else null, emptyIcon = "ic_conditional_format") {
                                rules.forEach { (i, r) -> RuleRow(editor, i, r, last = i >= count - 1) }
                            }
                            WrapRow(spacing = 8.dp) {
                                DialogButton(stringResource(R.string.cf_clear_selection), { editor.clearCondFormats(); onApplied(cleared) })
                                DialogButton(stringResource(R.string.cf_clear_sheet), { editor.clearSheetCondFormats(); onApplied(cleared) })
                            }
                        }
                    }
                }
            }
        }
}

/** A text box for a rule's value, text or formula, [widthDp] wide. */
@Composable
private fun RuleField(value: String, onChange: (String) -> Unit, widthDp: Int) {
    InputBox(value, onChange, Modifier.width(widthDp.dp))
}

@Composable
private fun Hint(text: String) = Text(text, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)

/** Excel's five highlight looks, each shown as it looks. */
@Composable
private fun LookChoice(look: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.cf_format_with), style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary)
        WrapRow(spacing = 6.dp) {
            listOf(R.string.cf_look_red, R.string.cf_look_yellow, R.string.cf_look_green, R.string.cf_look_light_red, R.string.cf_look_red_text).forEachIndexed { i, label ->
                val st = CondEval.PRESETS[i]
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.height(36.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(st.fill ?: 0xFFFFFFFF))
                        .border(BorderStroke(if (look == i) 2.dp else 0.5.dp, if (look == i) LocalAccent.current else PicoTheme.colorScheme.dividerLine), RoundedCornerShape(8.dp))
                        .press { onPick(i) }.padding(horizontal = 10.dp),
                ) {
                    Text(stringResource(label), style = AeroType.caption, color = Color(st.font ?: 0xFF1A1A1A), maxLines = 1)
                }
            }
        }
    }
}

/** One of the manager's rules, a line in its list: how it looks, what it tests and where, Stop If True, its moves and delete. */
@Composable
private fun RuleRow(editor: SheetEditor, index: Int, rule: CondFormat, last: Boolean) {
    val range = Workbook.colLabel(rule.c1) + (rule.r1 + 1) + ":" + Workbook.colLabel(rule.c2) + (rule.r2 + 1)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 10.dp, end = 4.dp),
    ) {
        // Its look: a chip in its fill and text colour (a scale: its colours).
        val st = rule.look
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(44.dp, 26.dp).clip(RoundedCornerShape(6.dp)).background(
                when (rule.type) {
                    CondType.COLOR_SCALE -> Brush.horizontalGradient((rule.stops.map { it.second }.ifEmpty { listOf(rule.color2, rule.color) }).map { Color(it) })
                    CondType.DATA_BAR -> Brush.horizontalGradient(listOf(Color(rule.color), Color(rule.color).copy(alpha = 0.15f)))
                    else -> SolidColor(Color(st.fill ?: 0xFFFFFFFF))
                },
            ).border(0.5.dp, PicoTheme.colorScheme.dividerLine, RoundedCornerShape(6.dp)),
        ) {
            if (rule.type == CondType.ICON_SET) rule.icons?.let { set ->
                androidx.compose.foundation.Canvas(Modifier.size(36.dp, 12.dp)) {
                    val s = size.height
                    drawIntoCanvas { c -> for (k in 0 until set.count.coerceAtMost(3)) CondIcons.draw(c.nativeCanvas, set, set.count - 1 - k, k * s, 0f, s) }
                }
            } else if (!rule.type.scale) Text("Aa", style = AeroType.caption.copy(fontWeight = if (st.bold == true) FontWeight.Bold else FontWeight.Normal), color = Color(st.font ?: 0xFF1A1A1A))
        }
        Column(Modifier.weight(1f)) {
            Text(describe(rule), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
            Text(range, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
        }
        if (!rule.type.scale) CheckOption(stringResource(R.string.cf_stop_if_true), rule.stopIfTrue, { editor.setCondRuleStop(index, !rule.stopIfTrue) }, small = true)
        Row {
            TitleIconButton("ic_arrow_up", stringResource(R.string.cf_move_up), { editor.moveCondRule(index, -1) }, enabled = index > 0, size = 32.dp)
            TitleIconButton("ic_arrow_down", stringResource(R.string.cf_move_down), { editor.moveCondRule(index, 1) }, enabled = !last, size = 32.dp)
            TitleIconButton("ic_delete", stringResource(R.string.cf_delete_rule), { editor.removeCondRule(index) }, size = 32.dp)
        }
    }
}

/** What a rule tests or draws, in a few words. */
@Composable
private fun describe(rule: CondFormat): String {
    // As the reader writes them: =ROUND(A1;2), 1,5 where the comma is the decimal symbol.
    val sym = com.therealityteam.aeropages.model.NumberSymbols.current()
    fun value(n: Double, f: String?) = f?.let { com.therealityteam.aeropages.editor.FormulaLocale.toLocal("=$it", sym) }
        ?: sym.local(if (n == Math.floor(n) && kotlin.math.abs(n) < 1e15) n.toLong().toString() else n.toString())
    val and = stringResource(R.string.conn_and)
    return when (rule.type) {
        CondType.GREATER -> stringResource(R.string.cond_greater_than) + " " + value(rule.v1, rule.formula1)
        CondType.LESS -> stringResource(R.string.cond_less_than) + " " + value(rule.v1, rule.formula1)
        CondType.EQUAL -> stringResource(R.string.cond_equal_to) + " " + value(rule.v1, rule.formula1)
        CondType.NOT_EQUAL -> stringResource(R.string.cf_not_equal) + " " + value(rule.v1, rule.formula1)
        CondType.GREATER_EQUAL -> stringResource(R.string.cf_greater_equal) + " " + value(rule.v1, rule.formula1)
        CondType.LESS_EQUAL -> stringResource(R.string.cf_less_equal) + " " + value(rule.v1, rule.formula1)
        CondType.BETWEEN -> stringResource(R.string.cond_between) + " " + value(rule.v1, rule.formula1) + " " + and + " " + value(rule.v2, rule.formula2)
        CondType.NOT_BETWEEN -> stringResource(R.string.cf_not_between) + " " + value(rule.v1, rule.formula1) + " " + and + " " + value(rule.v2, rule.formula2)
        CondType.TEXT_CONTAINS -> stringResource(R.string.cf_text_contains) + " “" + rule.text + "”"
        CondType.TEXT_NOT_CONTAINS -> stringResource(R.string.cf_text_not_contains) + " “" + rule.text + "”"
        CondType.TEXT_BEGINS -> stringResource(R.string.cf_text_begins) + " “" + rule.text + "”"
        CondType.TEXT_ENDS -> stringResource(R.string.cf_text_ends) + " “" + rule.text + "”"
        CondType.BLANKS -> stringResource(R.string.cf_blanks)
        CondType.NO_BLANKS -> "≠ " + stringResource(R.string.cf_blanks)
        CondType.ERRORS -> stringResource(R.string.cf_errors)
        CondType.NO_ERRORS -> "≠ " + stringResource(R.string.cf_errors)
        CondType.DUPLICATES -> stringResource(R.string.cf_duplicates)
        CondType.UNIQUES -> stringResource(R.string.cf_uniques)
        CondType.TOP -> stringResource(if (rule.percent) R.string.cf_top_pct else R.string.cf_top_n) + " · " + rule.v1.toInt() + (if (rule.percent) "%" else "")
        CondType.BOTTOM -> stringResource(if (rule.percent) R.string.cf_bottom_pct else R.string.cf_bottom_n) + " · " + rule.v1.toInt() + (if (rule.percent) "%" else "")
        CondType.ABOVE_AVERAGE -> stringResource(R.string.cf_above_average)
        CondType.BELOW_AVERAGE -> stringResource(R.string.cf_below_average)
        CondType.DATE -> stringResource(R.string.cf_date_occurring) + " · " + stringResource(when (rule.period ?: DatePeriod.TODAY) {
            DatePeriod.TODAY -> R.string.cf_today; DatePeriod.YESTERDAY -> R.string.cf_yesterday; DatePeriod.TOMORROW -> R.string.cf_tomorrow
            DatePeriod.LAST_7_DAYS -> R.string.cf_last_7_days; DatePeriod.THIS_WEEK -> R.string.cf_this_week; DatePeriod.LAST_WEEK -> R.string.cf_last_week
            DatePeriod.NEXT_WEEK -> R.string.cf_next_week; DatePeriod.THIS_MONTH -> R.string.cf_this_month; DatePeriod.LAST_MONTH -> R.string.cf_last_month
            DatePeriod.NEXT_MONTH -> R.string.cf_next_month
        })
        CondType.FORMULA -> stringResource(R.string.cf_formula_rule) + ": " + com.therealityteam.aeropages.editor.FormulaLocale.toLocal("=" + (rule.formula1 ?: ""), sym)
        CondType.DATA_BAR -> stringResource(R.string.cond_data_bars)
        CondType.COLOR_SCALE -> stringResource(R.string.cond_color_scales)
        CondType.ICON_SET -> stringResource(R.string.cf_icon_sets)
    }
}
