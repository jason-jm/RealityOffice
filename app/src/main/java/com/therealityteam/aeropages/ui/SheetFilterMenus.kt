package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.EntryParser
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.ColorFilter
import com.therealityteam.aeropages.model.ColumnFilter
import com.therealityteam.aeropages.model.DynamicFilter
import com.therealityteam.aeropages.model.FilterCondition
import com.therealityteam.aeropages.model.FilterCondition.Op
import com.therealityteam.aeropages.model.FilterStats
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.NumberSymbols
import com.therealityteam.aeropages.model.TopFilter

/**
 * What Excel's Custom AutoFilter opens with: the first and second comparison and how they join — Between, for one, is
 * "greater than or equal to" And "less than or equal to".
 */
data class FilterSeed(val op1: Op? = Op.EQUALS, val op2: Op? = null, val or: Boolean = false)

/** The comparisons Custom AutoFilter lists, in Excel's order. */
private val CUSTOM_OPS = listOf(Op.EQUALS, Op.NOT_EQUALS, Op.GREATER, Op.GREATER_OR_EQUAL, Op.LESS, Op.LESS_OR_EQUAL,
    Op.BEGINS_WITH, Op.NOT_BEGINS_WITH, Op.ENDS_WITH, Op.NOT_ENDS_WITH, Op.CONTAINS, Op.NOT_CONTAINS)

/** A comparison's menu name (Excel's, with its "…"), dates saying Before and After for less and greater. */
@Composable
internal fun opLabel(op: Op, dates: Boolean = false): String = stringResource(when (op) {
    Op.EQUALS -> R.string.fop_equals
    Op.NOT_EQUALS -> R.string.fop_not_equal
    Op.GREATER -> if (dates) R.string.fop_after else R.string.fop_greater
    Op.GREATER_OR_EQUAL -> R.string.fop_greater_equal
    Op.LESS -> if (dates) R.string.fop_before else R.string.fop_less
    Op.LESS_OR_EQUAL -> R.string.fop_less_equal
    Op.BEGINS_WITH -> R.string.fop_begins
    Op.NOT_BEGINS_WITH -> R.string.fop_not_begins
    Op.ENDS_WITH -> R.string.fop_ends
    Op.NOT_ENDS_WITH -> R.string.fop_not_ends
    Op.CONTAINS -> R.string.fop_contains
    Op.NOT_CONTAINS -> R.string.fop_not_contains
})

/** The name without the menu's trailing ellipsis (a dialog's list says "equals", not "Equals…"). */
private fun bare(label: String) = label.trimEnd('…', '.', ' ')

/**
 * The Number / Text / Date Filters submenu of a filter button's menu, as Excel lists it for [kind]: the comparisons
 * (opening Custom AutoFilter on [onCustom]), Top 10 ([onTop10]), above and below the average, and for dates the periods
 * against today — those apply at once ([onApply]); the one in force is ticked.
 */
@Composable
internal fun FilterCriteriaMenu(kind: SheetEditor.FilterKind, current: ColumnFilter?, onCustom: (FilterSeed) -> Unit, onTop10: () -> Unit, onApply: (ColumnFilter) -> Unit) {
    val dates = kind == SheetEditor.FilterKind.DATE
    @Composable fun op(op: Op) = MenuCommand(opLabel(op, dates), { onCustom(FilterSeed(op)) },
        checked = current?.condition?.op == op && current.condition2 == null)
    @Composable fun dynamic(d: DynamicFilter, label: Int) = MenuCommand(stringResource(label), { onApply(ColumnFilter(blanks = false, dynamic = d)) }, checked = current?.dynamic == d)
    val between = FilterSeed(Op.GREATER_OR_EQUAL, Op.LESS_OR_EQUAL)
    val isBetween = current?.condition?.op == Op.GREATER_OR_EQUAL && current.condition2?.op == Op.LESS_OR_EQUAL && !current.or
    when (kind) {
        SheetEditor.FilterKind.NUMBER -> {
            op(Op.EQUALS); op(Op.NOT_EQUALS); op(Op.GREATER); op(Op.GREATER_OR_EQUAL); op(Op.LESS); op(Op.LESS_OR_EQUAL)
            MenuCommand(stringResource(R.string.fop_between), { onCustom(between) }, checked = isBetween)
            MenuCommand(stringResource(R.string.fop_top10), onTop10, checked = current?.top != null)
            dynamic(DynamicFilter.ABOVE_AVERAGE, R.string.cf_above_average)
            dynamic(DynamicFilter.BELOW_AVERAGE, R.string.cf_below_average)
        }
        SheetEditor.FilterKind.TEXT -> {
            op(Op.EQUALS); op(Op.NOT_EQUALS)
            MenuDivider()
            op(Op.BEGINS_WITH); op(Op.ENDS_WITH)
            MenuDivider()
            op(Op.CONTAINS); op(Op.NOT_CONTAINS)
        }
        SheetEditor.FilterKind.DATE -> {
            op(Op.EQUALS); op(Op.LESS); op(Op.GREATER)
            MenuCommand(stringResource(R.string.fop_between), { onCustom(between) }, checked = isBetween)
            MenuDivider()
            dynamic(DynamicFilter.TOMORROW, R.string.cf_tomorrow); dynamic(DynamicFilter.TODAY, R.string.cf_today); dynamic(DynamicFilter.YESTERDAY, R.string.cf_yesterday)
            MenuDivider()
            dynamic(DynamicFilter.NEXT_WEEK, R.string.cf_next_week); dynamic(DynamicFilter.THIS_WEEK, R.string.cf_this_week); dynamic(DynamicFilter.LAST_WEEK, R.string.cf_last_week)
            MenuDivider()
            dynamic(DynamicFilter.NEXT_MONTH, R.string.cf_next_month); dynamic(DynamicFilter.THIS_MONTH, R.string.cf_this_month); dynamic(DynamicFilter.LAST_MONTH, R.string.cf_last_month)
            MenuDivider()
            dynamic(DynamicFilter.NEXT_QUARTER, R.string.date_next_quarter); dynamic(DynamicFilter.THIS_QUARTER, R.string.date_this_quarter); dynamic(DynamicFilter.LAST_QUARTER, R.string.date_last_quarter)
            MenuDivider()
            dynamic(DynamicFilter.NEXT_YEAR, R.string.date_next_year); dynamic(DynamicFilter.THIS_YEAR, R.string.date_this_year); dynamic(DynamicFilter.LAST_YEAR, R.string.date_last_year)
            MenuDivider()
            dynamic(DynamicFilter.YEAR_TO_DATE, R.string.date_year_to_date)
        }
    }
    MenuDivider()
    MenuCommand(stringResource(R.string.fop_custom), {
        val c = current?.condition
        onCustom(if (c != null) FilterSeed(c.op, current.condition2?.op, current.or) else FilterSeed())
    }, checked = current?.condition != null && !isBetween && current.condition2 != null)
}

/**
 * One colour in a list of them, as Excel's colour menus show it: a fill as a swatch bar, a font colour as "Aa" in it, none
 * ([color] null) by name — No Fill, or Automatic for a font — ticked when [on].
 */
@Composable
internal fun ColorChoiceRow(color: Long?, font: Boolean, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp))
            .background(if (on) LocalAccent.current.copy(alpha = 0.14f) else Color.Transparent)
            .spatialHoverEffect().press(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ColorChoiceLook(color, font, Modifier.weight(1f))
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { if (on) RibbonGlyph("ic_check", LocalAccent.current, 16.dp) }
    }
}

/** A colour's look in a menu or a field: a swatch bar, "Aa" in a font colour, or No Fill / Automatic by name. */
@Composable
private fun ColorChoiceLook(color: Long?, font: Boolean, modifier: Modifier = Modifier) {
    if (color == null) Text(stringResource(if (font) R.string.automatic else R.string.no_fill), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = modifier)
    else if (font) Text("Aa", style = AeroType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Color(color), modifier = modifier)
    else Box(modifier.height(18.dp).clip(RoundedCornerShape(4.dp)).background(Color(color)).border(1.dp, Color(0x22000000), RoundedCornerShape(4.dp)))
}

/**
 * Excel's colour submenus of a filter button — Filter by Color, and Sort by Color ([sorting]) — the fills ([fills]) and
 * font colours ([fonts]) the column's cells show, the one in force ([current]) ticked; choosing one applies it ([onApply]).
 */
@Composable
internal fun FilterColorMenu(fills: List<Long?>, fonts: List<Long?>, current: ColorFilter?, sorting: Boolean = false, onApply: (ColorFilter) -> Unit) {
    if (fills.isNotEmpty()) {
        MenuHeading(stringResource(if (sorting) R.string.sort_by_cell_color else R.string.filter_by_cell_color))
        fills.forEach { c -> ColorChoiceRow(c, false, current?.font == false && current.color == c) { onApply(ColorFilter(c, false)) } }
    }
    if (fonts.isNotEmpty()) {
        MenuHeading(stringResource(if (sorting) R.string.sort_by_font_color else R.string.filter_by_font_color))
        fonts.forEach { c -> ColorChoiceRow(c, true, current?.font == true && current.color == c) { onApply(ColorFilter(c, true)) } }
    }
}

/** A field choosing one of [colors] (a sort level's colour): the chosen one's look, and the list in a menu under it. */
@Composable
internal fun ColorChoiceField(colors: List<Long?>, selected: Long?, font: Boolean, onPick: (Long?) -> Unit, width: androidx.compose.ui.unit.Dp = 120.dp) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.width(width)) {
        Row(
            Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(Aero.commandRadius)).background(Aero.field)
                .spatialHoverEffect().press { open = true }.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ColorChoiceLook(selected, font, Modifier.weight(1f))
            DropCaret(PicoTheme.colorScheme.labelSecondary)
        }
        if (open) AnchoredFlyout(onDismiss = { open = false }) {
            FlyoutMenu(width = 200.dp) { colors.forEach { c -> ColorChoiceRow(c, font, c == selected) { onPick(c); open = false } } }
        }
    }
}

/**
 * Excel's Custom AutoFilter for column [col] ([header] its name): one or two comparisons joined by And or Or, the values
 * typed or picked from the column's; Excel's wildcards in Equals. For a date column the dates typed (in any way the cells
 * take them) compare as dates. OK filters the column, one undo step.
 */
@Composable
internal fun CustomAutoFilterDialog(editor: SheetEditor, col: Int, header: String, seed: FilterSeed, onDismiss: () -> Unit, onStatus: (String) -> Unit) {
    val kind = editor.filterKind(col)
    val dates = kind == SheetEditor.FilterKind.DATE
    val current = editor.columnFilter(col)
    // A date's serial shown back as a date, as the dialog takes it.
    fun shown(v: String?): String {
        v ?: return ""
        val n = v.toDoubleOrNull()
        return if (dates && n != null) FilterStats.EPOCH.plusDays(kotlin.math.floor(n).toLong()).toString() else v
    }
    var op1 by remember { mutableStateOf(seed.op1) }
    var op2 by remember { mutableStateOf(seed.op2) }
    var or by remember { mutableStateOf(seed.or) }
    var v1 by remember { mutableStateOf(shown(current?.condition?.takeIf { it.op == seed.op1 }?.value)) }
    var v2 by remember { mutableStateOf(shown(current?.condition2?.takeIf { it.op == seed.op2 }?.value)) }
    val values = remember(col) { editor.columnValues(col) }
    // What the cells hold for a typed value: a date's serial, a number in the file's form; text as typed.
    fun held(text: String): String {
        if (kind == SheetEditor.FilterKind.TEXT || text.contains('*') || text.contains('?')) return text
        val e = EntryParser.parse(text, symbols = NumberSymbols.current()) ?: return text
        return if (dates || e.format != NumberFormat.DATE) e.value else text
    }
    AeroDialog(title = stringResource(R.string.custom_autofilter), onDismiss = onDismiss, width = 520.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), {
            val first = op1?.takeIf { v1.isNotBlank() || it == Op.EQUALS || it == Op.NOT_EQUALS }?.let { FilterCondition(it, held(v1.trim())) }
            val second = op2?.takeIf { v2.isNotBlank() }?.let { FilterCondition(it, held(v2.trim())) }
            val c1 = first ?: second
            editor.setColumnFilter(col, c1?.let { ColumnFilter(blanks = false, condition = it, condition2 = if (first != null) second else null, or = or) })
            editor.autoFilter?.let { af ->
                val total = af.r2 - af.r1
                onStatus(Strings.get(R.string.status_rows_shown, total - editor.hiddenRows.count { it in af.r1 + 1..af.r2 }, total))
            }
            onDismiss()
        }, primary = true)
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(480.dp)) {
            Text(stringResource(R.string.show_rows_where, header), style = AeroType.label, color = PicoTheme.colorScheme.labelPrimary)
            @Composable fun criterion(op: Op?, onOp: (Op?) -> Unit, value: String, onValue: (String) -> Unit, optional: Boolean) {
                val ops: List<Op?> = (if (optional) listOf<Op?>(null) else emptyList()) + CUSTOM_OPS
                val opText: @Composable (Op?) -> String = { o -> if (o == null) "" else bare(opLabel(o, dates)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ComboField(opText(op), ops, { o -> opText(o) }, op, onOp, width = 200.dp)
                    InputBox(value, onValue, Modifier.weight(1f), description = opText(op))
                    var pick by remember { mutableStateOf(false) }
                    Box {
                        TitleIconButton("ic_chevron_down", stringResource(R.string.filter_select_all), { pick = true }, enabled = values.isNotEmpty(), size = 32.dp)
                        if (pick) AnchoredFlyout(onDismiss = { pick = false }, alignEnd = true) {
                            FlyoutMenu(width = 220.dp) { values.take(200).forEach { v -> MenuCommand(v, { onValue(v); pick = false }) } }
                        }
                    }
                }
            }
            criterion(op1, { op1 = it }, v1, { v1 = it }, optional = false)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(start = 8.dp)) {
                RadioOption(stringResource(R.string.filter_and), !or, { or = false })
                RadioOption(stringResource(R.string.filter_or), or, { or = true })
            }
            criterion(op2, { op2 = it }, v2, { v2 = it }, optional = true)
            Text(stringResource(R.string.filter_wildcards), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
        }
    }
}

/** Excel's Top 10 AutoFilter for column [col]: the largest or smallest so many items, or that percent of them. */
@Composable
internal fun Top10Dialog(editor: SheetEditor, col: Int, onDismiss: () -> Unit, onStatus: (String) -> Unit) {
    val current = editor.columnFilter(col)?.top
    var top by remember { mutableStateOf(current?.top ?: true) }
    var count by remember { androidx.compose.runtime.mutableIntStateOf((current?.count ?: 10.0).toInt()) }
    var percent by remember { mutableStateOf(current?.percent ?: false) }
    AeroDialog(title = stringResource(R.string.top10_title), onDismiss = onDismiss, width = 460.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), {
            editor.setColumnFilter(col, ColumnFilter(blanks = false, top = TopFilter(top, count.toDouble(), percent)))
            editor.autoFilter?.let { af ->
                val total = af.r2 - af.r1
                onStatus(Strings.get(R.string.status_rows_shown, total - editor.hiddenRows.count { it in af.r1 + 1..af.r2 }, total))
            }
            onDismiss()
        }, primary = true)
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogLabel(stringResource(R.string.top10_show))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val topLabel: @Composable (Boolean) -> String = { stringResource(if (it) R.string.top10_top else R.string.top10_bottom) }
                ComboField(topLabel(top), listOf(true, false), { topLabel(it) }, top, { top = it }, width = 120.dp)
                SpinBox(count.toString(), { d -> count = (count + d).coerceIn(1, if (percent) 100 else 500) }, description = stringResource(R.string.top10_show),
                    canLess = count > 1, canMore = count < (if (percent) 100 else 500), valueWidth = 56.dp)
                val unitLabel: @Composable (Boolean) -> String = { stringResource(if (it) R.string.style_percent else R.string.top10_items) }
                ComboField(unitLabel(percent), listOf(false, true), { unitLabel(it) }, percent, { percent = it; if (it) count = count.coerceAtMost(100) }, width = 140.dp)
            }
        }
    }
}
