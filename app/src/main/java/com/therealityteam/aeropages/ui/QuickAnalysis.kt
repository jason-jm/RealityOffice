package com.therealityteam.aeropages.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.CondEval
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.ChartType
import com.therealityteam.aeropages.model.CondFormat
import com.therealityteam.aeropages.model.CondType
import com.therealityteam.aeropages.model.IconSetKind
import com.therealityteam.aeropages.model.ScaleKind
import com.therealityteam.aeropages.model.ScalePoint
import com.therealityteam.aeropages.model.SheetTable
import com.therealityteam.aeropages.model.SparklineType

/** Quick Analysis's tabs, in Excel's order, each with the line Excel shows under its tiles. */
internal enum class QuickTab(val label: Int, val hint: Int) {
    FORMATTING(R.string.qa_formatting, R.string.qa_formatting_hint),
    CHARTS(R.string.group_charts, R.string.qa_charts_hint),
    TOTALS(R.string.qa_totals, R.string.qa_totals_hint),
    TABLES(R.string.group_tables, R.string.qa_tables_hint),
    SPARKLINES(R.string.group_sparklines, R.string.qa_sparklines_hint),
}

/** What Quick Analysis hands to the app around the grid: the dialogs it opens and what follows a chart or a table. */
internal class QuickActions(
    val onGreaterThan: () -> Unit = {},
    val onChartMade: () -> Unit = {},
    val onTableMade: () -> Unit = {},
    val onPivot: () -> Unit = {},
    val onStatus: (String) -> Unit = {},
)

/**
 * Excel's Quick Analysis for the selection: five tabs — Formatting (data bars, a colour scale, an icon set, Greater
 * Than…, the top 10 %, clearing the selection's rules), Charts (column, bar, line, pie, scatter), Totals (a sum, an
 * average or a count under each column or beside each row), Tables (a table, a PivotTable) and Sparklines — each a row
 * of tiles with a small picture of what it does, and Excel's line about the tab under them. A tile does its command on
 * the selection at once (one undo step) and closes the panel.
 */
@Composable
internal fun QuickAnalysisPanel(editor: SheetEditor, actions: QuickActions, onDismiss: () -> Unit, start: QuickTab = QuickTab.FORMATTING) {
    var tab by remember { mutableStateOf(start) }
    fun run(action: () -> Unit): () -> Unit = { onDismiss(); action() }
    val sumLabel = stringResource(R.string.figure_sum)
    val averageLabel = stringResource(R.string.figure_average)
    val countLabel = stringResource(R.string.figure_count)
    val below = stringResource(R.string.qa_below)
    val beside = stringResource(R.string.qa_right)
    val accent = LocalAccent.current
    // Wide enough for the longest tab name in five equal segments (Formatierung, Форматирование).
    val names = QuickTab.entries.map { stringResource(it.label) }
    val width = maxOf(QUICK_WIDTH, (labelWidth(*names.toTypedArray(), min = 0.dp, max = 400.dp) + 36.dp) * QuickTab.entries.size + 24.dp)
    Column(Modifier.width(width).padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DialogTabs(QuickTab.entries, tab, { stringResource(it.label) }, { tab = it }, width = width - 24.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 2.dp)) {
            when (tab) {
                QuickTab.FORMATTING -> {
                    QuickTile(stringResource(R.string.cond_data_bars), run { editor.addCondRule(CondFormat(0, 0, 0, 0, CondType.DATA_BAR, color = 0xFF638EC6, barMin = 0, barMax = 100)) }) { dataBars() }
                    QuickTile(stringResource(R.string.cond_color_scales), run {
                        editor.addCondRule(CondFormat(0, 0, 0, 0, CondType.COLOR_SCALE, color = 0xFF63BE7B, color2 = 0xFFF8696B, stops = listOf(
                            ScalePoint(ScaleKind.MIN) to 0xFFF8696B, ScalePoint(ScaleKind.PERCENTILE, 50.0) to 0xFFFFEB84, ScalePoint(ScaleKind.MAX) to 0xFF63BE7B)))
                    }) { colorScale() }
                    QuickTile(stringResource(R.string.cf_icon_sets), run { editor.addCondRule(CondFormat(0, 0, 0, 0, CondType.ICON_SET, icons = IconSetKind.ARROWS_3)) }) { iconSet() }
                    QuickTile(stringResource(R.string.cond_greater_than), run(actions.onGreaterThan)) { highlighted(top = false) }
                    QuickTile(stringResource(R.string.qa_top_10), run {
                        editor.addCondRule(CondFormat(0, 0, 0, 0, CondType.TOP, v1 = 10.0, percent = true, style = CondEval.PRESETS[0]))
                    }) { highlighted(top = true) }
                    QuickTile(stringResource(R.string.clear_formats), run { editor.clearCondFormats() }, icon = "ic_eraser")
                }
                QuickTab.CHARTS -> CHART_TYPES.filter { it.first in QUICK_CHARTS }.sortedBy { QUICK_CHARTS.indexOf(it.first) }.forEach { (type, look) ->
                    QuickTile(look.first, run {
                        if (editor.insertChartFromSelection(type)) { actions.onChartMade(); actions.onStatus(Strings.get(R.string.status_chart_generated)) }
                        else actions.onStatus(Strings.get(R.string.status_need_range_2d))
                    }, icon = look.second)
                }
                QuickTab.TOTALS -> {
                    listOf("SUM" to sumLabel, "AVERAGE" to averageLabel, "COUNT" to countLabel).forEach { (fn, name) ->
                        QuickTile(name, run { if (!editor.quickTotals(fn, label = name)) actions.onStatus(Strings.get(R.string.sort_no_data)) },
                            description = "$name · $below") { totals(right = false, accent) }
                    }
                    listOf("SUM" to sumLabel, "AVERAGE" to averageLabel, "COUNT" to countLabel).forEach { (fn, name) ->
                        QuickTile(name, run { if (!editor.quickTotals(fn, right = true, label = name)) actions.onStatus(Strings.get(R.string.sort_no_data)) },
                            description = "$name · $beside") { totals(right = true, accent) }
                    }
                }
                QuickTab.TABLES -> {
                    QuickTile(stringResource(R.string.table), run {
                        val guess = editor.tableGuess()
                        if (guess != null && editor.createTable(guess.first, SheetTable.DEFAULT_STYLE, guess.second)) actions.onTableMade()
                        else actions.onStatus(Strings.get(R.string.sort_no_data))
                    }, icon = "ic_table")
                    QuickTile(stringResource(R.string.pivot_table), run(actions.onPivot), icon = "ic_pivot", enabled = editor.rangeRowMax() > editor.rangeRowMin())
                }
                QuickTab.SPARKLINES -> listOf(
                    Triple(SparklineType.LINE, R.string.sparkline_line, "ic_spark_line"),
                    Triple(SparklineType.COLUMN, R.string.sparkline_column, "ic_spark_column"),
                    Triple(SparklineType.WIN_LOSS, R.string.sparkline_winloss, "ic_spark_winloss"),
                ).forEach { (type, name, icon) ->
                    QuickTile(stringResource(name), run { editor.insertSparklines(type); actions.onStatus(Strings.get(R.string.status_sparklines_inserted)) }, icon = icon)
                }
            }
        }
        Text(stringResource(tab.hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
    }
}

/** Room for the six Totals tiles. */
private val QUICK_WIDTH = 500.dp
private val QUICK_CHARTS = listOf(ChartType.COLUMN, ChartType.LINE, ChartType.PIE, ChartType.BAR, ChartType.SCATTER)

/**
 * One tile: a white card with [icon] or the [picture] of what it does, its [label] under it (two lines at most). The
 * screen reader hears [description] when the label alone would not tell two tiles apart (a sum under the columns or
 * beside the rows).
 */
@Composable
private fun QuickTile(
    label: String, onClick: () -> Unit, icon: String? = null, enabled: Boolean = true, description: String = label,
    picture: (DrawScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.width(76.dp).clip(shape).press(enabled = enabled, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }.padding(vertical = 6.dp),
    ) {
        Box(
            Modifier.size(52.dp, 44.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, Aero.barEdge, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                picture != null -> Canvas(Modifier.fillMaxSize().padding(7.dp)) { picture() }
                icon != null -> RibbonGlyph(icon, if (enabled) LocalAccent.current else PicoTheme.colorScheme.labelTertiary, 26.dp)
            }
        }
        // Two balanced lines, long words hyphenated in their language (Gisto-/grammy), as the ribbon's big buttons.
        Text(label, style = AeroType.caption.copy(hyphens = androidx.compose.ui.text.style.Hyphens.Auto, lineBreak = androidx.compose.ui.text.style.LineBreak.Heading),
            color = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelTertiary,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

// The tiles' pictures: four rows of a column of numbers, as Excel's own tiles draw them.

private val QUICK_GRID = Color(0xFFD4D4D4)
private val QUICK_CELL = Color(0xFFF3F3F3)

private fun DrawScope.rows(each: DrawScope.(top: Float, height: Float, i: Int) -> Unit) {
    val h = size.height / 4f
    for (i in 0 until 4) each(i * h + 1f, h - 2f, i)
}

private fun DrawScope.dataBars() = rows { top, height, i ->
    drawRoundRect(Color(0xFF638EC6), Offset(0f, top), Size(size.width * floatArrayOf(0.9f, 0.45f, 0.7f, 0.25f)[i], height), CornerRadius(1.5f))
}

private fun DrawScope.colorScale() = rows { top, height, i ->
    drawRect(Color(longArrayOf(0xFF63BE7B, 0xFFFFEB84, 0xFFFBAA77, 0xFFF8696B)[i]), Offset(0f, top), Size(size.width, height))
}

private fun DrawScope.iconSet() = rows { top, height, i ->
    val color = Color(longArrayOf(0xFF00B050, 0xFFFFC000, 0xFFFFC000, 0xFFFF0000)[i])
    val r = height / 2f
    drawCircle(color, r * 0.9f, Offset(r, top + r))
    drawRect(QUICK_CELL, Offset(r * 2.6f, top + r * 0.4f), Size(size.width - r * 2.6f, r * 1.2f))
}

/** Greater Than ([top] false: two cells of the four) or the top 10 % (the first): Excel's light red fill. */
private fun DrawScope.highlighted(top: Boolean) = rows { y, height, i ->
    val on = if (top) i == 0 else i == 0 || i == 2
    drawRect(if (on) Color(0xFFFFC7CE) else QUICK_CELL, Offset(0f, y), Size(size.width, height))
    drawRect(if (on) Color(0xFF9C0006) else QUICK_GRID, Offset(size.width * 0.15f, y + height * 0.4f), Size(size.width * 0.5f, height * 0.2f))
}

/** A three-by-three block with the totals' row under it ([right] false) or their column beside it, in the accent tint. */
private fun DrawScope.totals(right: Boolean, accent: Color) {
    val n = 4
    val w = size.width / n; val h = size.height / n
    for (r in 0 until n) for (c in 0 until n) {
        val total = if (right) c == n - 1 else r == n - 1
        drawRect(if (total) accent else QUICK_CELL, Offset(c * w + 1f, r * h + 1f), Size(w - 2f, h - 2f))
    }
}
