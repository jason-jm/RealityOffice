package com.therealityteam.aeropages.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.DropCap

/** Word's three drop cap positions: none, dropped into the text, in the margin. */
internal enum class DropCapPlace { NONE, DROPPED, MARGIN }

/**
 * Word's Drop Cap menu (Insert ▸ Text ▸ Drop Cap): None, Dropped, In Margin — the caret's paragraph's own checked —, and
 * Drop Cap Options…. Choosing a place keeps the paragraph's lines and distance (Word's three lines when it had none).
 */
@Composable
internal fun DropCapMenu(editor: DocumentEditor, onDone: () -> Unit, onMore: () -> Unit) {
    val cap = editor.dropCap
    val can = editor.canDropCap
    fun put(margin: Boolean) { editor.setDropCap((cap ?: DropCap()).copy(inMargin = margin)); onDone() }
    FlyoutMenu(width = 280.dp) {
        MenuCommand(stringResource(R.string.drop_cap_none), { editor.setDropCap(null); onDone() }, icon = "ic_drop_cap_none", checked = cap == null, enabled = cap != null)
        MenuCommand(stringResource(R.string.drop_cap_dropped), { put(false) }, icon = "ic_drop_cap", checked = cap != null && !cap.inMargin, enabled = can)
        MenuCommand(stringResource(R.string.drop_cap_in_margin), { put(true) }, icon = "ic_drop_cap_margin", checked = cap?.inMargin == true, enabled = can)
        MenuDivider()
        MenuCommand(stringResource(R.string.drop_cap_options) + "…", onMore, icon = "ic_drop_cap", enabled = can || cap != null)
    }
}

/**
 * Word's Drop Cap dialog: Position (None, Dropped, In Margin — drawn as Word draws them) and Options: the letters' font,
 * the lines to drop (1 to 10) and the distance from the text. OK sets the caret's paragraph's drop cap, one undo step.
 */
@Composable
internal fun DropCapDialog(editor: DocumentEditor, onDismiss: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val cap = editor.dropCap
    // The text's own font, or the one the ribbon shows for text without one.
    val bodyFont = editor.activeParagraph.runs.firstOrNull { it.text.isNotEmpty() }?.format?.fontFamily ?: "Calibri"
    var place by remember(cap) { mutableStateOf(when { cap == null -> DropCapPlace.NONE; cap.inMargin -> DropCapPlace.MARGIN; else -> DropCapPlace.DROPPED }) }
    var font by remember(cap, bodyFont) { mutableStateOf(cap?.fontFamily ?: bodyFont) }
    var lines by remember(cap) { mutableIntStateOf(cap?.lines ?: 3) }
    var distance by remember(cap) { mutableFloatStateOf(cap?.distancePt ?: 0f) }
    val measurer = rememberTextMeasurer()
    val accent = LocalAccent.current
    val ink = com.pico.spatial.ui.design.PicoTheme.colorScheme.labelTertiary
    val off = place == DropCapPlace.NONE
    AeroDialog(
        title = stringResource(R.string.drop_cap),
        onDismiss = onDismiss,
        width = 440.dp,
        buttons = {
            DialogButton(stringResource(R.string.cancel), onDismiss)
            DialogButton(stringResource(R.string.btn_ok), {
                editor.setDropCap(if (off) null else DropCap(inMargin = place == DropCapPlace.MARGIN, lines = lines, distancePt = distance,
                    fontFamily = font.takeIf { it != bodyFont }))
                onDismiss()
            }, primary = true, enabled = off || editor.canDropCap)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogLabel(stringResource(R.string.dc_position))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(DropCapPlace.NONE to R.string.drop_cap_none, DropCapPlace.DROPPED to R.string.drop_cap_dropped, DropCapPlace.MARGIN to R.string.drop_cap_in_margin).forEach { (k, name) ->
                    Tile(stringResource(name), null, place == k, { place = k }, width = 112.dp) {
                        Canvas(Modifier.size(72.dp, 60.dp)) { dropCapArt(k, measurer, accent, ink) }
                    }
                }
            }
            DialogLabel(stringResource(R.string.dc_options))
            val labels = labelWidth(stringResource(R.string.font), stringResource(R.string.dc_lines), stringResource(R.string.dc_distance), min = 72.dp, max = 160.dp)
            FieldRow(stringResource(R.string.font), labelWidth = labels) {
                ComboField(font, (listOf(bodyFont) + TextStyling.FONT_FAMILIES).distinct(), { it }, font, { font = it }, width = 220.dp)
            }
            FieldRow(stringResource(R.string.dc_lines), labelWidth = labels) {
                SpinBox(lines.toString(), { d -> lines = (lines + d).coerceIn(1, DropCap.MAX_LINES) }, description = stringResource(R.string.dc_lines),
                    canLess = !off && lines > 1, canMore = !off && lines < DropCap.MAX_LINES, valueWidth = 40.dp, dim = off)
            }
            FieldRow(stringResource(R.string.dc_distance), labelWidth = labels) {
                SpinBox(Lengths.label(distance), { d -> distance = (distance + d * Lengths.step).coerceIn(0f, 72f) }, description = stringResource(R.string.dc_distance),
                    canLess = !off && distance > 0f, canMore = !off && distance < 72f, valueWidth = 72.dp, dim = off)
            }
        }
    }
}

/** A small page of grey lines with a large "A" as [place] puts it: Word's pictures of the three positions. */
internal fun DrawScope.dropCapArt(place: DropCapPlace, measurer: TextMeasurer, accent: Color, ink: Color) {
    val pitch = size.height / 6f
    val stroke = 2.5.dp.toPx()
    val left = 4.dp.toPx(); val right = size.width - 4.dp.toPx()
    val cap = measurer.measure("A", TextStyle(fontSize = (pitch * 3.1f / density / fontScale).sp, fontWeight = FontWeight.Bold, color = accent))
    val capW = cap.size.width.toFloat()
    val textLeft = when (place) { DropCapPlace.NONE -> left; DropCapPlace.DROPPED -> left; DropCapPlace.MARGIN -> left + capW + 3.dp.toPx() }
    for (i in 0 until 6) {
        val y = pitch * (i + 0.5f)
        val start = if (place == DropCapPlace.DROPPED && i < 3) left + capW + 3.dp.toPx() else textLeft
        drawLine(ink, Offset(start, y), Offset(if (i == 5) (start + right) / 2f else right, y), stroke, cap = StrokeCap.Round)
    }
    if (place == DropCapPlace.NONE) return
    // The letter's foot on the third line, as the drop sets it.
    drawText(cap, topLeft = Offset(if (place == DropCapPlace.MARGIN) left - 1.dp.toPx() else left, pitch * 2.75f - cap.firstBaseline))
}
