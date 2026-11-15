package com.therealityteam.aeropages.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.core.graphics.withTranslation
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.windows.AlertDialog
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.format.MathLayout
import com.therealityteam.aeropages.format.MathPainter
import com.therealityteam.aeropages.format.PaintMathFont
import com.therealityteam.aeropages.model.MathLatex
import com.therealityteam.aeropages.model.MathNode

/** A palette entry: what its key shows, and the LaTeX it puts at the caret (the caret lands at the first `{}`). */
private class Template(val label: String, val latex: String)

private val STRUCTURES = listOf(
    Template("a⁄b", "\\frac{}{}"), Template("xⁿ", "^{}"), Template("xᵢ", "_{}"), Template("√x", "\\sqrt{}"), Template("ⁿ√x", "\\sqrt[]{}"),
    Template("∑", "\\sum_{}^{}"), Template("∫", "\\int_{}^{}"), Template("lim", "\\lim_{x \\to }"), Template("( )", "\\left( \\right)"),
    Template("[⋮]", "\\begin{pmatrix}  &  \\\\  &  \\end{pmatrix}"), Template("{⋮", "\\begin{cases}  & \\text{if }  \\\\  & \\text{otherwise} \\end{cases}"),
    Template("x̂", "\\hat{}"), Template("x⃗", "\\vec{}"), Template("x̄", "\\overline{}"), Template("sin", "\\sin "), Template("log", "\\log "),
)
private val SYMBOLS = listOf(
    "α" to "\\alpha ", "β" to "\\beta ", "γ" to "\\gamma ", "δ" to "\\delta ", "θ" to "\\theta ", "λ" to "\\lambda ", "μ" to "\\mu ",
    "π" to "\\pi ", "σ" to "\\sigma ", "φ" to "\\varphi ", "ω" to "\\omega ", "Δ" to "\\Delta ", "Σ" to "\\Sigma ", "Ω" to "\\Omega ",
    "±" to "\\pm ", "×" to "\\times ", "÷" to "\\div ", "·" to "\\cdot ", "≤" to "\\leq ", "≥" to "\\geq ", "≠" to "\\neq ",
    "≈" to "\\approx ", "→" to "\\to ", "∞" to "\\infty ", "∂" to "\\partial ", "∇" to "\\nabla ", "∈" to "\\in ", "∀" to "\\forall ",
).map { (label, latex) -> Template(label, latex) }

/**
 * Insert → Equation (or Edit on a picked one): the equation typed in LaTeX, with keys for the
 * structures and symbols, and set as it will look while it is typed. [allowInline] offers the
 * choice between a displayed equation and one in the text (a new one only). [onApply] gets the
 * formula and whether it is displayed; nothing is applied while the input is empty.
 */
@Composable
internal fun EquationDialog(
    initial: String, editing: Boolean, allowInline: Boolean, initialDisplay: Boolean,
    onDismiss: () -> Unit, onApply: (MathNode, Boolean) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var display by remember { mutableStateOf(initialDisplay) }
    val focus = remember { FocusRequester() }
    val math = remember(value.text) { MathLatex.parse(value.text) }
    val empty = math is MathNode.Row && math.items.isEmpty()

    fun put(t: Template) {
        val start = value.selection.min; val end = value.selection.max
        val text = value.text.substring(0, start) + t.latex + value.text.substring(end)
        val inside = t.latex.indexOf("{}").let { if (it >= 0) it + 1 else t.latex.indexOf("[]").let { b -> if (b >= 0) b + 1 else t.latex.length } }
        value = TextFieldValue(text, TextRange(start + inside))
        runCatching { focus.requestFocus() }
    }

    AeroDialog(title = stringResource(if (editing) R.string.title_edit_equation else R.string.title_insert_equation), onDismiss = onDismiss, width = 600.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(if (editing) R.string.apply else R.string.insert), { if (!empty) onApply(math, display) }, primary = !empty)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(600.dp)) {
                Text(stringResource(R.string.equation_hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                val label = stringResource(R.string.equation_latex)
                InputBox(value, { value = it }, Modifier.fillMaxWidth().focusRequester(focus), description = label, minLines = 2, maxLines = 5, monospace = true)
                PaletteRow(STRUCTURES, ::put)
                PaletteRow(SYMBOLS, ::put)
                // The formula as it will be set.
                Box(Modifier.fillMaxWidth().height(120.dp).background(Color.White, RoundedCornerShape(8.dp))
                    .border(BorderStroke(1.dp, PicoTheme.colorScheme.dividerLine), RoundedCornerShape(8.dp))) {
                    if (empty) Text(stringResource(R.string.equation_preview_empty), style = AeroType.caption,
                        color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.padding(12.dp))
                    else EquationPreview(math)
                }
                if (allowInline) ChoiceSegments(listOf(true, false), display, { stringResource(if (it) R.string.equation_display else R.string.equation_inline) }, { display = it }, width = 280.dp)
            }
        }
}

/** Keys in lines as wide as the dialog, every one in view. */
@Composable
private fun PaletteRow(items: List<Template>, put: (Template) -> Unit) {
    WrapRow(Modifier.fillMaxWidth(), spacing = 4.dp, lineSpacing = 2.dp) {
        items.forEach { t -> RibbonMiniButton(t.label, t.latex.trim(), { put(t) }) }
    }
}

/** [math] typeset at 20 sp, centred, smaller when it would not fit. */
@Composable
private fun EquationPreview(math: MathNode) {
    val density = LocalDensity.current
    val font = remember { PaintMathFont() }
    BoxWithConstraints(Modifier.fillMaxWidth().height(120.dp)) {
        val maxW = with(density) { maxWidth.toPx() } - with(density) { 24.dp.toPx() }
        val maxH = with(density) { maxHeight.toPx() } - with(density) { 16.dp.toPx() }
        val size = with(density) { 20.sp.toPx() }
        val box = remember(math, maxW) { MathLayout.layoutFitting(math, font, size, maxW) }
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            // A tall formula shrinks to the box too.
            val scale = if (box.height > maxH && box.height > 0f) maxH / box.height else 1f
            val x = (this.size.width - box.width * scale) / 2
            val top = (this.size.height - box.height * scale) / 2
            drawIntoCanvas { c ->
                c.nativeCanvas.withTranslation(x, top) {
                    scale(scale, scale)
                    MathPainter.draw(this, box, 0f, box.ascent, 0xFF1A1A1A.toInt(), font)
                }
            }
        }
    }
}

/**
 * [math] typeset at [sizePt] (in sp, as the text round it) across the width, placed as [align]
 * says and set smaller when it would not fit — a slide's equation text box on the canvas and in
 * the show.
 */
@Composable
internal fun EquationText(math: MathNode, sizePt: Float, align: com.therealityteam.aeropages.model.TextAlign, color: Int, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val font = remember { PaintMathFont() }
    BoxWithConstraints(modifier) {
        val maxW = with(density) { maxWidth.toPx() }
        val size = with(density) { sizePt.sp.toPx() }
        val box = remember(math, maxW, size) { MathLayout.layoutFitting(math, font, size, maxW) }
        val place = when (align) {
            com.therealityteam.aeropages.model.TextAlign.LEFT -> androidx.compose.ui.Alignment.TopStart
            com.therealityteam.aeropages.model.TextAlign.RIGHT -> androidx.compose.ui.Alignment.TopEnd
            else -> androidx.compose.ui.Alignment.TopCenter
        }
        Canvas(Modifier.align(place).width(with(density) { box.width.toDp() }).height(with(density) { box.height.toDp() })) {
            drawIntoCanvas { c -> MathPainter.draw(c.nativeCanvas, box, 0f, box.ascent, color, font) }
        }
    }
}
