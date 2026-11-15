package com.therealityteam.aeropages.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.PageMetrics

/**
 * Word's Columns dialog (Layout ▸ Columns ▸ More Columns): the presets one, two and three, how many columns, the space
 * between them and the width each then has, a line between them, a preview, and Apply to (this section or the whole
 * document). OK sets them, one undo step.
 */
@Composable
internal fun ColumnsDialog(editor: DocumentEditor, onDismiss: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val pg = editor.currentPage
    val accent = LocalAccent.current
    // Taken afresh when the section under the caret is another (a file still loading when it opened).
    var count by remember(pg) { mutableIntStateOf(PageMetrics.columnsOf(pg)) }
    var gap by remember(pg) { mutableFloatStateOf(pg.columnGapPt) }
    var line by remember(pg) { mutableStateOf(pg.columnLine) }
    var whole by remember { mutableStateOf(false) }
    val body = pg.widthPt - pg.marginLeftPt - pg.marginRightPt
    // As many columns as fit at Word's least width with this spacing.
    val most = (1..PageMetrics.MAX_COLUMNS).last { n -> n == 1 || (body - gap * (n - 1)) / n >= PageMetrics.MIN_COLUMN }
    val n = count.coerceAtMost(most)
    val width = (body - gap * (n - 1)) / n
    AeroDialog(
        title = stringResource(R.string.page_columns),
        onDismiss = onDismiss,
        width = 500.dp,
        buttons = {
            DialogButton(stringResource(R.string.cancel), onDismiss)
            DialogButton(stringResource(R.string.btn_ok), { editor.setColumnLayout(n, gap, line, whole); onDismiss() }, primary = true)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogLabel(stringResource(R.string.col_presets))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(1 to R.string.columns_one, 2 to R.string.columns_two, 3 to R.string.columns_three).forEach { (k, name) ->
                    Tile(stringResource(name), null, n == k, { count = k.coerceAtMost(most) }, width = 96.dp) {
                        Canvas(Modifier.size(56.dp, 64.dp)) { page(pg.widthPt, pg.heightPt, pg.marginTopPt, pg.marginBottomPt, pg.marginLeftPt, pg.marginRightPt, k, accent, gapPt = gap, columnLine = line) }
                    }
                }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val labels = labelWidth(stringResource(R.string.col_count), stringResource(R.string.col_spacing), stringResource(R.string.ps_width), min = 56.dp, max = 120.dp)
                    FieldRow(stringResource(R.string.col_count), labelWidth = labels) {
                        SpinBox(n.toString(), { d -> count = (n + d).coerceIn(1, most) }, description = stringResource(R.string.col_count),
                            canLess = n > 1, canMore = n < most, valueWidth = 40.dp)
                    }
                    FieldRow(stringResource(R.string.col_spacing), labelWidth = labels) {
                        SpinBox(Lengths.label(gap), { d -> gap = (gap + d * Lengths.step).coerceIn(0f, 288f) }, description = stringResource(R.string.col_spacing),
                            canLess = n > 1 && gap > 0f, canMore = n > 1, valueWidth = 72.dp, dim = n == 1)
                    }
                    FieldRow(stringResource(R.string.ps_width), labelWidth = labels) {
                        Text(Lengths.label(width), style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary)
                    }
                    CheckOption(stringResource(R.string.col_line_between), line, { line = !line }, enabled = n > 1)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DialogLabel(stringResource(R.string.ps_preview))
                    Canvas(Modifier.size(120.dp, 140.dp)) {
                        page(pg.widthPt, pg.heightPt, pg.marginTopPt, pg.marginBottomPt, pg.marginLeftPt, pg.marginRightPt, n, accent, gapPt = gap, columnLine = line)
                    }
                }
            }
            FieldRow(stringResource(R.string.ps_apply_to), labelWidth = labelWidth(stringResource(R.string.ps_apply_to), min = 72.dp, max = 160.dp)) {
                val label: @Composable (Boolean) -> String = { stringResource(if (it) R.string.ps_whole_document else R.string.ps_this_section) }
                ComboField(label(whole), listOf(false, true), { label(it) }, whole, { whole = it }, width = 200.dp)
            }
        }
    }
}
