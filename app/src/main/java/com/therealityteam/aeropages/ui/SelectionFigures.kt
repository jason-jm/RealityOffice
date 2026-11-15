package com.therealityteam.aeropages.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.NumberFormatting
import com.therealityteam.aeropages.editor.SelectionStats
import com.therealityteam.aeropages.editor.SelectionStats.Figure
import com.therealityteam.aeropages.editor.SheetEditor

/** What Excel calls each status bar figure. */
@Composable
private fun nameOf(f: Figure): String = stringResource(when (f) {
    Figure.AVERAGE -> R.string.figure_average
    Figure.COUNT -> R.string.figure_count
    Figure.NUMERICAL_COUNT -> R.string.figure_numerical_count
    Figure.MIN -> R.string.figure_min
    Figure.MAX -> R.string.figure_max
    Figure.SUM -> R.string.figure_sum
})

/** [f] of [stats] as the status bar shows it: counts plainly, the rest in the first number's format; null when Excel leaves it out. */
private fun shownValue(f: Figure, stats: SelectionStats): String? {
    val v = stats.valueOf(f) ?: return null
    return if (f == Figure.COUNT || f == Figure.NUMERICAL_COUNT) v.toLong().toString() else NumberFormatting.figure(v, stats.format, com.therealityteam.aeropages.model.NumberSymbols.current(), com.therealityteam.aeropages.model.FormatCode.Lang.current())
}

/** The figures Excel shows until told otherwise: Average, Count and Sum. */
private val SHOWN_AT_FIRST = setOf(Figure.AVERAGE, Figure.COUNT, Figure.SUM)

/**
 * Excel's status bar figures for the selection: each one that is on ("Average: 21", "Count: 5", "Sum: 105"), in the
 * first number's format, copied to the clipboard when clicked (Excel 365's click to copy, [onCopy]); and Customize
 * Status Bar — the six figures, each ticked when shown and with its value beside it — which Excel keeps between
 * sessions, as this does.
 */
@Composable
internal fun SelectionFigures(editor: SheetEditor, onCopy: (String) -> Unit) {
    val stats = editor.selectionStats()
    val on: Map<Figure, MutableState<Boolean>> = Figure.entries.associateWith { f ->
        rememberLayoutFlag("sheets.figure.${f.name.lowercase()}", f in SHOWN_AT_FIRST)
    }
    if (stats != null) for (f in Figure.entries) {
        if (!on.getValue(f).value) continue
        val value = shownValue(f, stats) ?: continue
        StatusLink(stringResource(R.string.figure_item, nameOf(f), value), { onCopy(value) })
    }
    // Customize Status Bar opens with the file for a screenshot (dev hook `dialog statusfigures`, which also picks B2:D5).
    var menu by remember { mutableStateOf(devDialogProp() == "statusfigures") }
    Box {
        val customize = stringResource(R.string.customize_status_bar)
        TitleIconButton("ic_chevron_up", customize, { menu = true }, size = 28.dp)
        if (menu) AnchoredFlyout({ menu = false }, above = true) {
            FlyoutMenu(width = 260.dp) {
                MenuHeading(customize)
                for (f in Figure.entries) {
                    val flag = on.getValue(f)
                    MenuCommand(nameOf(f), { flag.value = !flag.value }, checked = flag.value, trailing = stats?.let { shownValue(f, it) }.orEmpty())
                }
            }
        }
    }
}
