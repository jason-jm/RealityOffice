package com.therealityteam.aeropages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.Workbook

/**
 * Excel's Comments pane, beside the window: New Comment (for the active cell, or its comment to edit), then every comment
 * on this sheet as a card — the cell, the words — in Excel's order (row by row). The active cell's card is marked; a click
 * goes to its cell; each card can be edited ([onEdit], the comment dialog) or deleted. None yet: a line on how to add one.
 */
@Composable
internal fun SheetCommentsPane(editor: SheetEditor, modifier: Modifier, onEdit: () -> Unit, onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val notes = editor.commentCells()
    val accent = LocalAccent.current
    Column(modifier.paneSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaneHeader(stringResource(R.string.comments), onClose)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(stringResource(if (editor.activeHasComment) R.string.edit_comment else R.string.new_comment), onEdit, primary = true, small = true)
            Box(Modifier.weight(1f))
            if (notes.isNotEmpty()) Text(stringResource(R.string.sheet_comment_count, notes.size), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
        }
        if (notes.isEmpty()) {
            PaneEmpty("ic_comment", stringResource(R.string.sheet_comments_empty), Modifier.fillMaxWidth().weight(1f))
            return@Column
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in notes) {
                val here = n.row == editor.selRow && n.col == editor.selCol
                val cell = Workbook.colLabel(n.col) + (n.row + 1)
                PaneCard(selected = here, onClick = { editor.select(n.row, n.col) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Who wrote it (an unknown author: no avatar), the cell and when — then the note.
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            n.author?.let { Avatar(it) }
                            Column(Modifier.weight(1f)) {
                                Text(n.author ?: cell, style = AeroType.label, color = if (n.author != null) PicoTheme.colorScheme.labelPrimary else accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val sub = listOfNotNull(cell.takeIf { n.author != null }, n.dateMs.takeIf { it > 0 }?.let { noteWhen(it) })
                                if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = AeroType.caption, color = if (n.author != null) accent else PicoTheme.colorScheme.labelSecondary, maxLines = 1)
                            }
                            TitleIconButton("ic_edit", stringResource(R.string.edit_comment), { editor.select(n.row, n.col); onEdit() }, size = 28.dp)
                            TitleIconButton("ic_delete", stringResource(R.string.delete_comment), { editor.select(n.row, n.col); editor.clearComment() }, size = 28.dp)
                        }
                        Text(n.text, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 6, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = if (n.author != null) 36.dp else 0.dp))
                    }
                }
            }
        }
    }
}

/** A note's date as the comments panes show it (month-day, hour:minute, local time). */
private fun noteWhen(ms: Long): String = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

/**
 * A note kept shown beside its cell (Excel's Show/Hide Note, or every note with Show All Notes): the pale yellow box Excel
 * draws — its author in bold, then the note — up and to the right of the cell, a line from the cell's top-right corner to
 * it. It is paper: sized by the sheet's zoom ([pxPerPt]), fixed colours. [cornerX]/[cornerY] are the corner in the body's
 * coordinates; a tap goes to the cell.
 */
@Composable
internal fun SheetNoteBox(note: String, author: String?, cornerX: Float, cornerY: Float, pxPerPt: Float, onClick: () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gap = NOTE_GAP_PT * pxPerPt
    val top = cornerY - NOTE_RAISE_PT * pxPerPt
    val ink = androidx.compose.ui.graphics.Color(0xFF1F1F1F)
    with(density) {
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(kotlin.math.round(cornerX).toInt(), kotlin.math.round(top).toInt()) }
                .drawBehind {
                    // The line from the cell's corner (at its top-right) to the box's top-left.
                    val from = androidx.compose.ui.geometry.Offset(0f, cornerY - top)
                    val to = androidx.compose.ui.geometry.Offset(gap, 2 * pxPerPt)
                    drawLine(ink, from, to, strokeWidth = maxOf(1f, 0.75f * pxPerPt))
                },
        ) {
            Column(
                Modifier
                    .padding(start = gap.toDp())
                    .width((NOTE_WIDTH_PT * pxPerPt).toDp())
                    .heightIn(min = (NOTE_HEIGHT_PT * pxPerPt).toDp(), max = (NOTE_HEIGHT_PT * 4 * pxPerPt).toDp())
                    .shadow(2.dp, androidx.compose.ui.graphics.RectangleShape)
                    .background(androidx.compose.ui.graphics.Color(0xFFFFFFE1))
                    .border(maxOf(1f, 0.75f * pxPerPt).toDp(), ink.copy(alpha = 0.6f))
                    .press(onClick = onClick)
                    .padding((3 * pxPerPt).toDp()),
            ) {
                val size = (9 * pxPerPt).toSp()
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        author?.let {
                            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                            append("$it:"); pop(); append("\n")
                        }
                        append(note)
                    },
                    style = androidx.compose.ui.text.TextStyle(fontSize = size, lineHeight = size * 1.25f, color = ink),
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Excel's note box: 108 × 59.25 pt, 10.5 pt right of the cell, its top 7.5 pt above the cell's. */
private const val NOTE_WIDTH_PT = 108f
private const val NOTE_HEIGHT_PT = 59.25f
private const val NOTE_GAP_PT = 10.5f
private const val NOTE_RAISE_PT = 7.5f
