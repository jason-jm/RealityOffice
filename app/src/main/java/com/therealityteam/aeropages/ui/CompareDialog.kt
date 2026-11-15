package com.therealityteam.aeropages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.DocCompare

/**
 * Word's Review ▸ Compare ▸ Compare Documents: this document (the original), the revised one picked with Browse…, and the
 * name its differences are labelled with; Compare opens the result — the revised document with the changes tracked.
 * More shows Word's comparison settings ([options]: formatting, case changes, white space, tables; character or word level).
 */
@Composable
internal fun CompareDialog(
    original: String, revised: String?, label: String, onLabel: (String) -> Unit,
    options: DocCompare.Options, onOptions: (DocCompare.Options) -> Unit,
    onBrowse: () -> Unit, onCompare: () -> Unit, onDismiss: () -> Unit,
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
) {
    val labels = listOf(stringResource(R.string.compare_original), stringResource(R.string.compare_revised), stringResource(R.string.compare_label))
    val labelW = labelWidth(*labels.toTypedArray(), min = 96.dp, max = 160.dp)
    AeroDialog(
        title = stringResource(R.string.compare_documents), onDismiss = onDismiss, width = 500.dp,
        buttons = {
            DialogButton(stringResource(R.string.cancel), onDismiss)
            DialogButton(stringResource(R.string.compare), onCompare, primary = true, enabled = revised != null)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldRow(labels[0], labelW) {
                Text(original, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            FieldRow(labels[1], labelW) {
                Text(revised?.substringAfterLast('/') ?: stringResource(R.string.compare_pick), style = AeroType.body,
                    color = if (revised == null) PicoTheme.colorScheme.labelSecondary else PicoTheme.colorScheme.labelPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                DialogButton(stringResource(R.string.compare_browse), onBrowse, small = true)
            }
            FieldRow(labels[2], labelW) {
                InputBox(label, onLabel, Modifier.width(220.dp), description = labels[2])
            }
            // Word's More >> / << Less: the comparison settings, remembered.
            DialogButton(stringResource(if (expanded) R.string.compare_less else R.string.compare_more), { onExpandedChange(!expanded) }, small = true)
            if (expanded) {
                DialogLabel(stringResource(R.string.compare_settings))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        CheckOption(stringResource(R.string.markup_formatting), options.formatting, { onOptions(options.copy(formatting = !options.formatting)) })
                        CheckOption(stringResource(R.string.compare_case), options.caseChanges, { onOptions(options.copy(caseChanges = !options.caseChanges)) })
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        CheckOption(stringResource(R.string.compare_whitespace), options.whiteSpace, { onOptions(options.copy(whiteSpace = !options.whiteSpace)) })
                        CheckOption(stringResource(R.string.compare_tables), options.tables, { onOptions(options.copy(tables = !options.tables)) })
                    }
                }
                DialogLabel(stringResource(R.string.compare_level))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    RadioOption(stringResource(R.string.compare_char_level), options.characterLevel, { onOptions(options.copy(characterLevel = true)) })
                    RadioOption(stringResource(R.string.compare_word_level), !options.characterLevel, { onOptions(options.copy(characterLevel = false)) })
                }
            }
        }
    }
}
