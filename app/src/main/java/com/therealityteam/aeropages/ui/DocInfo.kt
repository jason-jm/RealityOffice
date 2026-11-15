package com.therealityteam.aeropages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.model.DocProps
import java.io.File

/**
 * File → Info, as Word, Excel and PowerPoint show it: the file's properties to edit (title, subject, author, keywords,
 * comments) beside where the file is, its size, when it was made and last saved and by whom, and the module's
 * [statistics] (label, value); OK puts the properties into the document with [onApply].
 */
@Composable
internal fun DocInfoDialog(props: DocProps, path: String?, statistics: List<Pair<String, String>>, onApply: (DocProps) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var title by remember(props) { mutableStateOf(props.title) }
    var subject by remember(props) { mutableStateOf(props.subject) }
    var author by remember(props) { mutableStateOf(props.author) }
    var keywords by remember(props) { mutableStateOf(props.keywords) }
    var comments by remember(props) { mutableStateOf(props.description) }
    val file = path?.let { File(it) }?.takeIf { it.isFile }
    val dates = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
    WideDialog(title = stringResource(R.string.file_info).trimEnd('…', '.'), onDismiss = onDismiss, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(stringResource(R.string.cancel), onDismiss)
            DialogButton(stringResource(R.string.btn_ok), {
                onApply(props.copy(title = title.trim(), subject = subject.trim(), author = author.trim(), keywords = keywords.trim(), description = comments.trim()))
                onDismiss()
            }, primary = true)
        }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1.2f)) {
                SectionLabel(stringResource(R.string.info_properties))
                PropField(stringResource(R.string.prop_title), title) { title = it }
                PropField(stringResource(R.string.prop_subject), subject) { subject = it }
                PropField(stringResource(R.string.prop_author), author) { author = it }
                PropField(stringResource(R.string.prop_keywords), keywords, hint = stringResource(R.string.prop_keywords_hint)) { keywords = it }
                PropField(stringResource(R.string.prop_comments), comments, lines = 3) { comments = it }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                SectionLabel(stringResource(R.string.info_file))
                if (file == null) InfoRow(stringResource(R.string.info_location), stringResource(R.string.info_not_saved))
                else {
                    InfoRow(stringResource(R.string.info_location), file.name + "\n" + (file.parentFile?.path ?: ""))
                    InfoRow(stringResource(R.string.info_size), android.text.format.Formatter.formatShortFileSize(context, file.length()))
                }
                props.created?.let { InfoRow(stringResource(R.string.info_created), dates.format(java.util.Date(it))) }
                (props.modified ?: file?.lastModified())?.let { InfoRow(stringResource(R.string.info_modified), dates.format(java.util.Date(it))) }
                props.lastModifiedBy.takeIf { it.isNotBlank() }?.let { InfoRow(stringResource(R.string.info_last_saved_by), it) }
                props.revision?.let { InfoRow(stringResource(R.string.info_revision), it.toString()) }
                if (statistics.isNotEmpty()) {
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    SectionLabel(stringResource(R.string.info_statistics))
                    statistics.forEach { (label, value) -> InfoRow(label, value) }
                }
            }
        }
    }
}

/** A property: its name, and a field for it ([lines] tall). */
@Composable
private fun PropField(label: String, value: String, hint: String? = null, lines: Int = 1, onChange: (String) -> Unit) {
    Row(verticalAlignment = if (lines > 1) Alignment.Top else Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(88.dp))
        InputBox(value, onChange, Modifier.weight(1f), placeholder = hint, description = label, minLines = lines)
    }
}

/** A fact about the file: its name and value. */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(120.dp))
        Text(value, style = AeroType.caption, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f))
    }
}
