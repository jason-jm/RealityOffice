package com.therealityteam.aeropages.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.TextAlign

// --- Word's Font dialog ----------------------------------------------------------------------------

private enum class FontTab(val label: Int) { FONT(R.string.font), ADVANCED(R.string.font_tab_advanced) }

/** Word's Font style list: Regular, Italic, Bold, Bold Italic. */
private enum class FontStyleChoice(val bold: Boolean, val italic: Boolean, val label: Int) {
    REGULAR(false, false, R.string.font_regular), ITALIC(false, true, R.string.italic),
    BOLD(true, false, R.string.bold), BOLD_ITALIC(true, true, R.string.font_bold_italic);
    companion object { fun of(bold: Boolean, italic: Boolean) = entries.first { it.bold == bold && it.italic == italic } }
}

/** Character spacing as Word's Advanced tab offers it: Normal, Expanded or Condensed, by so many points. */
private enum class SpacingChoice(val label: Int) { NORMAL(R.string.standard), EXPANDED(R.string.spacing_expanded), CONDENSED(R.string.spacing_condensed) }

/** The paper box Word shows its previews in. */
@Composable
private fun PreviewPaper(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(Color.White)
            .border(BorderStroke(1.dp, PicoTheme.colorScheme.dividerLine), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Word's Font dialog for Pages: the selection's format, applied through [DocumentEditor.formatCharacters]. */
@Composable
internal fun FontDialog(editor: DocumentEditor, onDismiss: () -> Unit) =
    FontDialog(editor.currentFormat, editor.selectedText(), { change -> editor.formatCharacters(change) }, onDismiss)

/**
 * Office's Font dialog (the Font group's launcher in Word and PowerPoint): on the Font tab the font, its style and size,
 * colour and underline, and the effects this app keeps (strikethrough, superscript, subscript, small caps); on Advanced
 * the character spacing; a preview of the selection ([selected]) — or the font's name — under both. [current] is the
 * format it starts from; OK hands [onApply] a change holding only what was changed here, so a selection of mixed fonts
 * keeps them unless the font itself was picked (one undo step).
 */
@Composable
internal fun FontDialog(current: CharacterFormat, selected: String, onApply: ((CharacterFormat) -> Unit) -> Unit, onDismiss: () -> Unit) {
    val start = remember { current.copy() }
    var tab by remember { mutableStateOf(FontTab.FONT) }
    var family by remember { mutableStateOf(start.fontFamily ?: "Calibri") }
    var style by remember { mutableStateOf(FontStyleChoice.of(start.bold, start.italic)) }
    var size by remember { mutableFloatStateOf(start.fontSizePt ?: 11f) }
    var color by remember { mutableStateOf(start.color) }
    var underline by remember { mutableStateOf(start.underline) }
    var strike by remember { mutableStateOf(start.strikethrough) }
    var sup by remember { mutableStateOf(start.superscript) }
    var sub by remember { mutableStateOf(start.subscript) }
    var smallCaps by remember { mutableStateOf(start.smallCaps) }
    val startSpacing = start.letterSpacingPt ?: 0f
    var spacing by remember { mutableStateOf(if (startSpacing > 0f) SpacingChoice.EXPANDED else if (startSpacing < 0f) SpacingChoice.CONDENSED else SpacingChoice.NORMAL) }
    var spacingBy by remember { mutableFloatStateOf(kotlin.math.abs(startSpacing).takeIf { it > 0f } ?: 1f) }
    val sample = remember { selected.lineSequence().firstOrNull().orEmpty().trim().take(40) }

    fun apply() {
        val spacingPt = when (spacing) { SpacingChoice.NORMAL -> null; SpacingChoice.EXPANDED -> spacingBy; SpacingChoice.CONDENSED -> -spacingBy }
        onApply { f ->
            if (family != (start.fontFamily ?: "Calibri")) f.fontFamily = family
            if (style.bold != start.bold) f.bold = style.bold
            if (style.italic != start.italic) f.italic = style.italic
            if (size != (start.fontSizePt ?: 11f)) f.fontSizePt = size
            if (color != start.color) f.color = color
            if (underline != start.underline) f.underline = underline
            if (strike != start.strikethrough) f.strikethrough = strike
            if (sup != start.superscript) f.superscript = sup
            if (sub != start.subscript) f.subscript = sub
            if (smallCaps != start.smallCaps) f.smallCaps = smallCaps
            if (spacingPt != start.letterSpacingPt) f.letterSpacingPt = spacingPt
        }
        onDismiss()
    }

    AeroDialog(stringResource(R.string.font), onDismiss, width = 520.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), ::apply, primary = true)
    }) {
        Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DialogTabs(FontTab.entries, tab, { stringResource(it.label) }, { tab = it }, width = 520.dp)
            when (tab) {
                FontTab.FONT -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DialogLabel(stringResource(R.string.font))
                            val fonts = (listOf(family) + TextStyling.FONT_FAMILIES).distinct()
                            ComboField(family, fonts, { it }, family, { family = it }, width = 220.dp)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DialogLabel(stringResource(R.string.fmt_style))
                            ComboField(stringResource(style.label), FontStyleChoice.entries, { stringResource(it.label) }, style, { style = it }, width = 160.dp)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DialogLabel(stringResource(R.string.font_size))
                            val sizes = (TextStyling.FONT_SIZES + size).distinct().sorted()
                            ComboField(sizeText(size), sizes, { sizeText(it) }, size, { size = it }, width = 116.dp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DialogLabel(stringResource(R.string.font_color))
                            ColorField(color, { color = it }, noneLabel = stringResource(R.string.automatic), description = stringResource(R.string.font_color), width = 220.dp)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DialogLabel(stringResource(R.string.underline_style))
                            ComboField(stringResource(if (underline) R.string.underline_single else R.string.none), listOf(false, true),
                                { stringResource(if (it) R.string.underline_single else R.string.none) }, underline, { underline = it }, width = 160.dp)
                        }
                    }
                    DialogLabel(stringResource(R.string.effects))
                    // Wraps to a second row where the language's names are long (Durchgestrichen, Kapitälchen).
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CheckOption(stringResource(R.string.strikethrough), strike, { strike = !strike })
                        // Superscript and subscript exclude each other, as in Word.
                        CheckOption(stringResource(R.string.superscript), sup, { sup = !sup; if (sup) sub = false })
                        CheckOption(stringResource(R.string.subscript), sub, { sub = !sub; if (sub) sup = false })
                        CheckOption(stringResource(R.string.small_caps), smallCaps, { smallCaps = !smallCaps })
                    }
                }
                FontTab.ADVANCED -> {
                    DialogLabel(stringResource(R.string.character_spacing))
                    FieldRow(stringResource(R.string.spacing_group), labelWidth = 80.dp) {
                        ComboField(stringResource(spacing.label), SpacingChoice.entries, { stringResource(it.label) }, spacing, { spacing = it }, width = 160.dp)
                        Text(stringResource(R.string.spacing_by), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
                        SpinBox(stringResource(R.string.unit_pt, pointText(spacingBy)), { d -> spacingBy = (spacingBy + 0.5f * d).coerceIn(0.5f, 12f) },
                            description = stringResource(R.string.spacing_by), canLess = spacingBy > 0.5f, valueWidth = 64.dp, dim = spacing == SpacingChoice.NORMAL)
                    }
                    Spacer(Modifier.height(36.dp))
                }
            }
            DialogLabel(stringResource(R.string.preview))
            PreviewPaper(Modifier.fillMaxWidth().height(72.dp)) {
                val spacingPt = when (spacing) { SpacingChoice.NORMAL -> 0f; SpacingChoice.EXPANDED -> spacingBy; SpacingChoice.CONDENSED -> -spacingBy }
                val text = sample.ifEmpty { family }
                Text(
                    if (smallCaps) text.uppercase() else text,
                    fontFamily = TextStyling.familyOf(family),
                    fontSize = (size * 1.33f).coerceIn(9f, 40f).let { if (sup || sub) it * 0.7f else it }.sp,
                    fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
                    color = Color(color ?: 0xFF1A1A1A),
                    letterSpacing = (spacingPt * 1.33f).sp,
                    textDecoration = TextDecoration.combine(listOfNotNull(TextDecoration.Underline.takeIf { underline }, TextDecoration.LineThrough.takeIf { strike })),
                    style = AeroType.body.copy(baselineShift = if (sup) BaselineShift.Superscript else if (sub) BaselineShift.Subscript else BaselineShift.None),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun sizeText(pt: Float): String = if (pt == kotlin.math.floor(pt)) pt.toInt().toString() else pt.toString()
private fun pointText(pt: Float): String = if (pt == kotlin.math.floor(pt)) pt.toInt().toString() else String.format(java.util.Locale.US, "%.1f", pt)

// --- Word's Paragraph dialog -----------------------------------------------------------------------

private enum class ParagraphTab(val label: Int) { INDENTS(R.string.indents_and_spacing), BREAKS(R.string.line_and_page_breaks) }

/** Word's Special indent: none, the first line in, or the first line hanging out. */
private enum class SpecialIndent(val label: Int) { NONE(R.string.none), FIRST_LINE(R.string.first_line_indent), HANGING(R.string.hanging_indent) }

/** Word's Line spacing list; [multiple] the multiplier the three fixed ones stand for. */
private enum class LineChoice(val label: Int, val multiple: Float?) {
    SINGLE(R.string.line_single, 1f), ONE_HALF(R.string.line_one_half, 1.5f), DOUBLE(R.string.line_double, 2f),
    AT_LEAST(R.string.line_at_least, null), EXACTLY(R.string.line_exactly, null), MULTIPLE(R.string.line_multiple, null),
}

/**
 * Word's Paragraph dialog (the Paragraph group's launcher, on Home and on Layout): on Indents and Spacing the
 * alignment, the left and right indents and the first line's (first line or hanging, by how much), the space before and
 * after and the line spacing (single, 1.5 lines, double, at least, exactly, multiple — with its value); on Line and Page
 * Breaks the pagination switches; a drawn preview of the paragraph among its neighbours; Tabs… opens Word's Tabs dialog.
 * Indents are in characters (as on the Layout tab), spacing in points. OK applies what was changed, to every
 * selected paragraph (one undo step).
 */
@Composable
internal fun ParagraphDialog(editor: DocumentEditor, onDismiss: () -> Unit, onTabs: () -> Unit) {
    val start = remember { editor.activeParagraph.format.copy() }
    var tab by remember { mutableStateOf(ParagraphTab.INDENTS) }
    var align by remember { mutableStateOf(start.align) }
    // Indents in characters of the paragraph's text, as the ribbon's (and Chinese Word's): the left one where the paragraph's
    // edge is — its first line when that hangs out — so a hanging indent does not move it.
    val ch = remember { editor.charWidthPt(editor.activeParagraph).coerceAtLeast(1f) }
    val startEdge = start.indentLeftPt + minOf(start.firstLineIndentPt, 0f)
    var left by remember { mutableFloatStateOf(startEdge) }
    var right by remember { mutableFloatStateOf(start.indentRightPt) }
    var special by remember { mutableStateOf(if (start.firstLineIndentPt > 0f) SpecialIndent.FIRST_LINE else if (start.firstLineIndentPt < 0f) SpecialIndent.HANGING else SpecialIndent.NONE) }
    var specialBy by remember { mutableFloatStateOf(kotlin.math.abs(start.firstLineIndentPt).takeIf { it > 0f } ?: (2 * ch)) }
    var before by remember { mutableFloatStateOf(start.spaceBeforePt) }
    var after by remember { mutableFloatStateOf(start.spaceAfterPt) }
    val startLine = lineChoiceOf(start)
    var line by remember { mutableStateOf(startLine) }
    var lineAt by remember { mutableFloatStateOf(start.lineHeightPt ?: start.lineSpacing) }
    var widow by remember { mutableStateOf(start.widowControl) }
    var keepNext by remember { mutableStateOf(com.therealityteam.aeropages.model.PageMetrics.keepsWithNext(editor.activeParagraph)) }
    var keepLines by remember { mutableStateOf(start.keepTogether) }
    var breakBefore by remember { mutableStateOf(start.pageBreakBefore) }

    fun pickLine(c: LineChoice) {
        // Moving between points and multiples starts the value where Word does: 12 pt, or 3 lines.
        val wasPoints = line == LineChoice.AT_LEAST || line == LineChoice.EXACTLY
        val isPoints = c == LineChoice.AT_LEAST || c == LineChoice.EXACTLY
        if (isPoints && !wasPoints) lineAt = 12f
        if (c == LineChoice.MULTIPLE && line != LineChoice.MULTIPLE) lineAt = if (wasPoints) 3f else (line.multiple ?: lineAt)
        line = c
    }

    fun apply() {
        val first = when (special) { SpecialIndent.NONE -> 0f; SpecialIndent.FIRST_LINE -> specialBy; SpecialIndent.HANGING -> -specialBy }
        editor.formatParagraphs { f ->
            if (align != start.align) f.align = align
            if (left != startEdge || first != start.firstLineIndentPt) {
                f.indentLeftPt = left + maxOf(-first, 0f)
                f.firstLineIndentPt = first
            }
            if (right != start.indentRightPt) f.indentRightPt = right
            if (before != start.spaceBeforePt) f.spaceBeforePt = before
            if (after != start.spaceAfterPt) f.spaceAfterPt = after
            if (line != startLine || lineAt != (start.lineHeightPt ?: start.lineSpacing)) when (line) {
                LineChoice.AT_LEAST, LineChoice.EXACTLY -> { f.lineHeightPt = lineAt; f.lineExact = line == LineChoice.EXACTLY }
                LineChoice.MULTIPLE -> { f.lineHeightPt = null; f.lineSpacing = lineAt }
                else -> { f.lineHeightPt = null; f.lineSpacing = line.multiple ?: 1f }
            }
            if (widow != start.widowControl) f.widowControl = widow
            if (keepLines != start.keepTogether) f.keepTogether = keepLines
            if (breakBefore != start.pageBreakBefore) f.pageBreakBefore = breakBefore
        }
        if (keepNext != com.therealityteam.aeropages.model.PageMetrics.keepsWithNext(editor.activeParagraph)) editor.formatParagraphs { it.keepWithNext = keepNext }
        onDismiss()
    }

    AeroDialog(stringResource(R.string.paragraph), onDismiss, width = 540.dp, buttons = {
        DialogButton(stringResource(R.string.tabs_more), { onDismiss(); onTabs() })
        Spacer(Modifier.weight(1f))
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), ::apply, primary = true)
    }) {
        Column(Modifier.width(540.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogTabs(ParagraphTab.entries, tab, { stringResource(it.label) }, { tab = it }, width = 540.dp)
            when (tab) {
                ParagraphTab.INDENTS -> {
                    FieldRow(stringResource(R.string.tabs_alignment), labelWidth = labelWidth(stringResource(R.string.tabs_alignment), min = 96.dp, max = 150.dp)) {
                        ComboField(stringResource(alignLabel(align)), TextAlign.entries, { stringResource(alignLabel(it)) }, align, { align = it }, width = 160.dp)
                    }
                    // The two label columns as wide as the language needs (Слева / Справа, Междустрочный интервал).
                    val leftW = labelWidth(stringResource(R.string.indent_left), stringResource(R.string.indent_right),
                        stringResource(R.string.spacing_before), stringResource(R.string.spacing_after), min = 48.dp, max = 76.dp)
                    val rightW = labelWidth(stringResource(R.string.special_indent), stringResource(R.string.indent_by),
                        stringResource(R.string.line_spacing), stringResource(R.string.line_spacing_at), min = 92.dp, max = 124.dp)
                    DialogLabel(stringResource(R.string.indentation))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldRow(stringResource(R.string.indent_left), labelWidth = leftW) { CharSpin(left, ch, stringResource(R.string.indent_left)) { left = it } }
                            FieldRow(stringResource(R.string.indent_right), labelWidth = leftW) { CharSpin(right, ch, stringResource(R.string.indent_right)) { right = it } }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldRow(stringResource(R.string.special_indent), labelWidth = rightW) {
                                ComboField(stringResource(special.label), SpecialIndent.entries, { stringResource(it.label) }, special, { special = it }, width = 132.dp)
                            }
                            FieldRow(stringResource(R.string.indent_by), labelWidth = rightW) {
                                CharSpin(specialBy, ch, stringResource(R.string.indent_by), dim = special == SpecialIndent.NONE, min = ch / 2) { specialBy = it }
                            }
                        }
                    }
                    DialogLabel(stringResource(R.string.spacing_group))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldRow(stringResource(R.string.spacing_before), labelWidth = leftW) { PointSpin(before, stringResource(R.string.spacing_before)) { before = it } }
                            FieldRow(stringResource(R.string.spacing_after), labelWidth = leftW) { PointSpin(after, stringResource(R.string.spacing_after)) { after = it } }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldRow(stringResource(R.string.line_spacing), labelWidth = rightW) {
                                ComboField(stringResource(line.label), LineChoice.entries, { stringResource(it.label) }, line, { pickLine(it) }, width = 132.dp)
                            }
                            FieldRow(stringResource(R.string.line_spacing_at), labelWidth = rightW) {
                                when (line) {
                                    LineChoice.AT_LEAST, LineChoice.EXACTLY -> PointSpin(lineAt, stringResource(R.string.line_spacing_at), step = 1f, min = 1f) { lineAt = it }
                                    LineChoice.MULTIPLE -> SpinBox(multipleText(lineAt), { d -> lineAt = (lineAt + 0.25f * d).coerceIn(0.5f, 5f) },
                                        description = stringResource(R.string.line_spacing_at), canLess = lineAt > 0.5f, canMore = lineAt < 5f, valueWidth = 72.dp)
                                    else -> SpinBox("", {}, description = stringResource(R.string.line_spacing_at), canLess = false, canMore = false, valueWidth = 72.dp, dim = true)
                                }
                            }
                        }
                    }
                }
                ParagraphTab.BREAKS -> {
                    DialogLabel(stringResource(R.string.pagination))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CheckOption(stringResource(R.string.widow_control), widow, { widow = !widow })
                        CheckOption(stringResource(R.string.keep_with_next), keepNext, { keepNext = !keepNext })
                        CheckOption(stringResource(R.string.keep_lines_together), keepLines, { keepLines = !keepLines })
                        CheckOption(stringResource(R.string.page_break_before), breakBefore, { breakBefore = !breakBefore })
                    }
                    Spacer(Modifier.height(54.dp))
                }
            }
            DialogLabel(stringResource(R.string.preview))
            PreviewPaper(Modifier.fillMaxWidth().height(120.dp)) {
                val first = when (special) { SpecialIndent.NONE -> 0f; SpecialIndent.FIRST_LINE -> specialBy; SpecialIndent.HANGING -> -specialBy }
                val gap = when (line) {
                    LineChoice.AT_LEAST, LineChoice.EXACTLY -> (lineAt / 12f).coerceIn(0.6f, 3f)
                    LineChoice.MULTIPLE -> lineAt.coerceIn(0.5f, 3f)
                    else -> line.multiple ?: 1f
                }
                ParagraphPreview(align, left + maxOf(-first, 0f), right, first, before, after, gap, Modifier.fillMaxWidth().height(104.dp))
            }
        }
    }
}

private fun lineChoiceOf(f: ParagraphFormat): LineChoice = when {
    f.lineHeightPt != null -> if (f.lineExact) LineChoice.EXACTLY else LineChoice.AT_LEAST
    f.lineSpacing == 1f -> LineChoice.SINGLE
    f.lineSpacing == 1.5f -> LineChoice.ONE_HALF
    f.lineSpacing == 2f -> LineChoice.DOUBLE
    else -> LineChoice.MULTIPLE
}

private fun alignLabel(a: TextAlign): Int = when (a) {
    TextAlign.LEFT -> R.string.align_left; TextAlign.CENTER -> R.string.center
    TextAlign.RIGHT -> R.string.align_right; TextAlign.JUSTIFY -> R.string.justify
}

private fun multipleText(v: Float): String = if (v == kotlin.math.floor(v)) v.toInt().toString() else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

/** An indent of [pt] points shown in characters [ch] points wide, half a character a press (Word's), never negative. */
@Composable
private fun CharSpin(pt: Float, ch: Float, description: String, dim: Boolean = false, min: Float = 0f, onChange: (Float) -> Unit) {
    val chars = Math.round(pt / ch * 2f) / 2f
    SpinBox(stringResource(R.string.indent_chars, multipleText(chars)), { d -> onChange(((chars + 0.5f * d) * ch).coerceIn(min, 360f)) }, description = description,
        canLess = pt > min + 0.01f, canMore = pt < 360f, valueWidth = 72.dp, dim = dim)
}

/** A space in points, [step] a press (Word's 6 pt for space before and after). */
@Composable
private fun PointSpin(pt: Float, description: String, step: Float = 6f, min: Float = 0f, onChange: (Float) -> Unit) {
    SpinBox(stringResource(R.string.unit_pt, pointText(pt)), { d -> onChange((pt + step * d).coerceIn(min, 1584f)) }, description = description,
        canLess = pt > min, valueWidth = 72.dp)
}

/**
 * Word's paragraph preview: grey lines of the paragraph before, this paragraph's lines in dark grey — indented, aligned
 * and spaced as set — and grey lines of the next one. Lengths in points; the column is taken as 432 pt (six inches).
 */
@Composable
private fun ParagraphPreview(align: TextAlign, left: Float, right: Float, first: Float, before: Float, after: Float, lineGap: Float, modifier: Modifier) {
    val near = Color(0xFFD0D3D8)
    val ink = Color(0xFF6B6F76)
    Canvas(modifier) {
        val column = 432f
        val scale = size.width / column
        val bar = 3.dp.toPx()
        val step = 7.dp.toPx()
        var y = 2.dp.toPx()
        fun lineAt(x0: Float, width: Float, color: Color) {
            drawRoundRect(color, Offset(x0, y), Size(width.coerceAtLeast(bar), bar), CornerRadius(bar / 2))
        }
        // The paragraph before.
        repeat(2) { lineAt(0f, size.width, near); y += step }
        lineAt(0f, size.width * 0.55f, near); y += step
        y += (before * scale * 0.5f).coerceAtMost(14.dp.toPx())
        // This paragraph: four full lines and a short last one, its lines spaced by the line spacing.
        val textLeft = left * scale
        val textRight = size.width - right * scale
        val lengths = listOf(1f, 1f, 1f, 1f, 0.6f)
        lengths.forEachIndexed { i, share ->
            val lineLeft = (if (i == 0) textLeft + first * scale else textLeft).coerceIn(0f, textRight - bar)
            val full = textRight - lineLeft
            val width = if (align == TextAlign.JUSTIFY && i < lengths.lastIndex) full else full * (if (i == lengths.lastIndex) share else 0.94f)
            val x0 = when (align) {
                TextAlign.CENTER -> lineLeft + (full - width) / 2
                TextAlign.RIGHT -> textRight - width
                else -> lineLeft
            }
            lineAt(x0, width, ink)
            y += step * lineGap
        }
        y += (after * scale * 0.5f).coerceAtMost(14.dp.toPx())
        // The paragraph after.
        while (y < size.height - bar) { lineAt(0f, size.width, near); y += step }
    }
}

// --- PowerPoint's Paragraph dialog ---------------------------------------------------------------

/** PowerPoint's Line Spacing list for a text box: multiples only (a box's spacing is a multiple here). */
private enum class SlideLineChoice(val label: Int, val multiple: Float?) {
    SINGLE(R.string.line_single, 1f), ONE_HALF(R.string.line_one_half, 1.5f), DOUBLE(R.string.line_double, 2f), MULTIPLE(R.string.line_multiple, null),
}

/**
 * PowerPoint's Paragraph dialog (Home ▸ Paragraph's launcher) for the text box being edited: its alignment and its line
 * spacing — Single, 1.5 lines, Double or Multiple at so many lines — what a slide's text keeps here; a drawn preview.
 * One undo step; nothing when nothing was changed.
 */
@Composable
internal fun SlideParagraphDialog(editor: com.therealityteam.aeropages.editor.SlideEditor, onDismiss: () -> Unit) {
    val box = remember { editor.focusedBox() }
    val startAlign = remember { box?.paragraph?.format?.align ?: TextAlign.LEFT }
    val startSpacing = remember { box?.lineSpacing ?: 1f }
    var align by remember { mutableStateOf(startAlign) }
    var line by remember { mutableStateOf(SlideLineChoice.entries.firstOrNull { it.multiple == startSpacing } ?: SlideLineChoice.MULTIPLE) }
    var at by remember { mutableFloatStateOf(startSpacing) }
    AeroDialog(stringResource(R.string.paragraph), onDismiss, width = 440.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), {
            val spacing = line.multiple ?: at
            editor.formatParagraph(align.takeIf { it != startAlign }, spacing.takeIf { it != startSpacing })
            onDismiss()
        }, primary = true)
    }) {
        Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldRow(stringResource(R.string.tabs_alignment), labelWidth = labelWidth(stringResource(R.string.tabs_alignment), min = 96.dp, max = 150.dp)) {
                ComboField(stringResource(alignLabel(align)), TextAlign.entries, { stringResource(alignLabel(it)) }, align, { align = it }, width = 160.dp)
            }
            DialogLabel(stringResource(R.string.spacing_group))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                FieldRow(stringResource(R.string.line_spacing), labelWidth = labelWidth(stringResource(R.string.line_spacing), min = 92.dp, max = 124.dp)) {
                    ComboField(stringResource(line.label), SlideLineChoice.entries, { stringResource(it.label) }, line, { c ->
                        if (c == SlideLineChoice.MULTIPLE && line != SlideLineChoice.MULTIPLE) at = line.multiple ?: at
                        line = c
                    }, width = 132.dp)
                }
                FieldRow(stringResource(R.string.line_spacing_at), labelWidth = labelWidth(stringResource(R.string.line_spacing_at), min = 40.dp, max = 90.dp)) {
                    if (line == SlideLineChoice.MULTIPLE)
                        SpinBox(multipleText(at), { d -> at = (at + 0.1f * d).coerceIn(0.5f, 5f).let { Math.round(it * 100f) / 100f } },
                            description = stringResource(R.string.line_spacing_at), canLess = at > 0.5f, canMore = at < 5f, valueWidth = 64.dp)
                    else SpinBox("", {}, description = stringResource(R.string.line_spacing_at), canLess = false, canMore = false, valueWidth = 64.dp, dim = true)
                }
            }
            DialogLabel(stringResource(R.string.preview))
            PreviewPaper(Modifier.fillMaxWidth().height(110.dp)) {
                ParagraphPreview(align, 0f, 0f, 0f, 0f, 0f, (line.multiple ?: at).coerceIn(0.5f, 3f), Modifier.fillMaxWidth().height(94.dp))
            }
        }
    }
}

// --- Word's Date and Time dialog -------------------------------------------------------------------

/** Word's Available formats, as its Date and Time dialog lists them for the language: Word's date pictures. */
internal fun dateTimePictures(locale: java.util.Locale = java.util.Locale.getDefault()): List<String> = when (locale.language) {
    "zh" -> listOf("yyyy/M/d", "yyyy年M月d日", "yyyy年M月d日dddd", "yyyy-MM-dd", "yyyy年M月", "M月d日", "yyyy年M月d日 H:mm", "H:mm", "H时m分")
    "ja" -> listOf("yyyy/MM/dd", "yyyy年M月d日", "yyyy年M月d日(aaa)", "yyyy-MM-dd", "M月d日", "aaaa", "yyyy/MM/dd H:mm", "H:mm", "H時mm分")
    "ko" -> listOf("yyyy-MM-dd", "yyyy년 M월 d일", "yyyy년 M월 d일 dddd", "yy-MM-dd", "yyyy. M. d.", "M월 d일", "yyyy-MM-dd H:mm", "AM/PM h:mm", "H:mm")
    "de" -> listOf("dd.MM.yyyy", "dddd, d. MMMM yyyy", "d. MMMM yyyy", "dd.MM.yy", "yyyy-MM-dd", "d. MMM. yy", "dd.MM.yyyy HH:mm", "HH:mm", "HH:mm:ss")
    "fr" -> listOf("dd/MM/yyyy", "dddd d MMMM yyyy", "d MMMM yyyy", "dd/MM/yy", "yyyy-MM-dd", "d-MMM-yy", "dd.MM.yyyy", "dd/MM/yyyy HH:mm", "HH:mm", "HH:mm:ss")
    "es" -> listOf("dd/MM/yyyy", "dddd, d 'de' MMMM 'de' yyyy", "d 'de' MMMM 'de' yyyy", "dd/MM/yy", "yyyy-MM-dd", "d-MMM-yy", "dd/MM/yyyy H:mm", "H:mm", "H:mm:ss")
    "it" -> listOf("dd/MM/yyyy", "dddd d MMMM yyyy", "d MMMM yyyy", "dd/MM/yy", "yyyy-MM-dd", "d-MMM-yy", "dd/MM/yyyy HH:mm", "HH:mm", "HH:mm:ss")
    "pt" -> listOf("dd/MM/yyyy", "dddd, d 'de' MMMM 'de' yyyy", "d 'de' MMMM 'de' yyyy", "dd/MM/yy", "yyyy-MM-dd", "d-MMM-yy", "dd/MM/yyyy HH:mm", "HH:mm", "HH:mm:ss")
    "ru" -> listOf("dd.MM.yyyy", "dddd, d MMMM yyyy 'г.'", "d MMMM yyyy 'г.'", "dd.MM.yy", "yyyy-MM-dd", "dd.MM.yyyy H:mm", "H:mm", "H:mm:ss")
    "nl" -> listOf("d-M-yyyy", "dddd d MMMM yyyy", "d MMMM yyyy", "d-M-yy", "yyyy-MM-dd", "d-MMM-yy", "d-M-yyyy H:mm", "H:mm", "H:mm:ss")
    "pl" -> listOf("dd.MM.yyyy", "dddd, d MMMM yyyy", "d MMMM yyyy", "dd.MM.yy", "yyyy-MM-dd", "dd.MM.yyyy HH:mm", "HH:mm", "HH:mm:ss")
    "tr" -> listOf("dd.MM.yyyy", "d MMMM yyyy dddd", "d MMMM yyyy", "dd.MM.yy", "yyyy-MM-dd", "dd.MM.yyyy HH:mm", "HH:mm", "HH:mm:ss")
    "vi" -> listOf("dd/MM/yyyy", "dddd, 'ngày' d 'tháng' M 'năm' yyyy", "'ngày' d 'tháng' M 'năm' yyyy", "dd/MM/yy", "yyyy-MM-dd", "dd/MM/yyyy HH:mm", "HH:mm", "HH:mm:ss")
    "th" -> listOf("d/M/yyyy", "dddd'ที่' d MMMM yyyy", "d MMMM yyyy", "d/M/yy", "yyyy-MM-dd", "d MMM yy", "d/M/yyyy H:mm", "H:mm", "H:mm:ss")
    "in", "id" -> listOf("dd/MM/yyyy", "dddd, dd MMMM yyyy", "dd MMMM yyyy", "dd/MM/yy", "yyyy-MM-dd", "dd/MM/yyyy HH:mm", "HH:mm", "HH:mm:ss")
    "hi" -> listOf("dd-MM-yyyy", "dddd, d MMMM yyyy", "d MMMM yyyy", "dd-MM-yy", "yyyy-MM-dd", "dd-MM-yyyy HH:mm", "HH:mm", "HH:mm:ss")
    else -> listOf("M/d/yyyy", "dddd, MMMM d, yyyy", "MMMM d, yyyy", "M/d/yy", "yyyy-MM-dd", "d-MMM-yy", "M.d.yyyy", "MMM. d, yy",
        "d MMMM yyyy", "MMMM yy", "MMM-yy", "M/d/yyyy h:mm am/pm", "h:mm am/pm", "h:mm:ss am/pm", "HH:mm")
}

/**
 * Word's Date and Time dialog (Insert ▸ Text ▸ Date & Time): the date and time now in each of the language's formats, the
 * first picked; OK — or a click on the picked one — puts it in at the caret ([onInsert]).
 */
@Composable
internal fun DateTimeDialog(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    val now = remember { java.time.LocalDateTime.now() }
    val shown = remember { dateTimePictures().map { com.therealityteam.aeropages.model.FieldDates.format(it, now) }.distinct() }
    var picked by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    AeroDialog(stringResource(R.string.date_and_time), onDismiss, width = 380.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), { onInsert(shown[picked]); onDismiss() }, primary = true)
    }) {
        Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogLabel(stringResource(R.string.available_formats))
            ListBox(Modifier.fillMaxWidth(), height = 300.dp) {
                shown.forEachIndexed { i, text ->
                    ListRow(text, i == picked, { if (i == picked) { onInsert(text); onDismiss() } else picked = i })
                }
            }
        }
    }
}
