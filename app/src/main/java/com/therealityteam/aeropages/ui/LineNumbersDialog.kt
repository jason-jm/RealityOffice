package com.therealityteam.aeropages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.LineNumberRestart
import com.therealityteam.aeropages.model.LineNumbering

/**
 * Word's Line Numbers dialog (Layout ▸ Line Numbers ▸ Line Numbering Options…): add line numbering, start at, from text
 * (Auto, or a distance), count by, restart each page or section or continuous, and which sections. OK sets them, one
 * undo step.
 */
@Composable
internal fun LineNumbersDialog(editor: DocumentEditor, onDismiss: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val current = editor.lineNumbering
    // Taken afresh when the caret's section's numbering changes under it (a file still loading when it opened).
    var on by remember(current) { mutableStateOf(current != null) }
    var start by remember(current) { mutableIntStateOf(current?.start ?: 1) }
    var countBy by remember(current) { mutableIntStateOf(current?.countBy ?: 1) }
    var distance by remember(current) { mutableStateOf(current?.distancePt) }
    var restart by remember(current) { mutableStateOf(current?.restart ?: LineNumberRestart.PAGE) }
    var whole by remember { mutableStateOf(false) }
    AeroDialog(
        title = stringResource(R.string.line_numbers),
        onDismiss = onDismiss,
        width = 400.dp,
        buttons = {
            DialogButton(stringResource(R.string.cancel), onDismiss)
            DialogButton(stringResource(R.string.btn_ok), {
                editor.setLineNumbers(if (on) LineNumbering(start, countBy, restart, distance) else null, whole)
                onDismiss()
            }, primary = true)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CheckOption(stringResource(R.string.ln_add), on, { on = !on })
            Column(Modifier.alpha(if (on) 1f else 0.4f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val labels = labelWidth(stringResource(R.string.ln_start_at), stringResource(R.string.ln_from_text), stringResource(R.string.ln_count_by), min = 64.dp, max = 160.dp)
                FieldRow(stringResource(R.string.ln_start_at), labelWidth = labels) {
                    SpinBox(start.toString(), { d -> if (on) start = (start + d).coerceIn(1, 32767) }, description = stringResource(R.string.ln_start_at),
                        canLess = on && start > 1, canMore = on, valueWidth = 64.dp)
                }
                FieldRow(stringResource(R.string.ln_from_text), labelWidth = labels) {
                    // Auto below the first step: Word's own (a quarter inch).
                    SpinBox(distance?.let { Lengths.label(it) } ?: stringResource(R.string.ln_auto), { d ->
                        if (on) {
                            val next = (distance ?: 0f) + d * Lengths.step
                            distance = if (next < Lengths.step / 2f) null else next.coerceAtMost(144f)
                        }
                    }, description = stringResource(R.string.ln_from_text), canLess = on && distance != null, canMore = on, valueWidth = 80.dp)
                }
                FieldRow(stringResource(R.string.ln_count_by), labelWidth = labels) {
                    SpinBox(countBy.toString(), { d -> if (on) countBy = (countBy + d).coerceIn(1, 100) }, description = stringResource(R.string.ln_count_by),
                        canLess = on && countBy > 1, canMore = on, valueWidth = 64.dp)
                }
                DialogLabel(stringResource(R.string.ln_numbering), Modifier.padding(top = 4.dp))
                RadioOption(stringResource(R.string.ln_restart_page), restart == LineNumberRestart.PAGE, { if (on) restart = LineNumberRestart.PAGE })
                RadioOption(stringResource(R.string.ln_restart_section), restart == LineNumberRestart.SECTION, { if (on) restart = LineNumberRestart.SECTION })
                RadioOption(stringResource(R.string.ln_continuous), restart == LineNumberRestart.CONTINUOUS, { if (on) restart = LineNumberRestart.CONTINUOUS })
            }
            FieldRow(stringResource(R.string.ps_apply_to), labelWidth = labelWidth(stringResource(R.string.ps_apply_to), min = 64.dp, max = 160.dp)) {
                val label: @Composable (Boolean) -> String = { stringResource(if (it) R.string.ps_whole_document else R.string.ps_this_section) }
                ComboField(label(whole), listOf(false, true), { label(it) }, whole, { whole = it }, width = 200.dp)
            }
        }
    }
}
