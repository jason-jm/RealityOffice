package com.therealityteam.aeropages.format.pdf

import android.graphics.Paint
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.MasterText
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideLevels
import com.therealityteam.aeropages.model.SlideListStyle
import com.therealityteam.aeropages.model.TextAlign

/**
 * A slide text box laid out for the PDF as the canvas shows it: each run in its face, size and
 * colour with its marks (underline, strike, super- and subscript, highlight), wrapped to the box,
 * and — in a list, or with lines at deeper levels — each line indented a step per level with its
 * bullet or number hanging before its text, wrapped lines lined up under the text.
 */
internal object SlideTextLayout {

    /** A run's point size (StaticLayout's own size span takes whole pixels). */
    private class SizeSpan(val size: Float) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) { tp.textSize = size }
        override fun updateMeasureState(tp: TextPaint) { tp.textSize = size }
    }

    fun of(box: SlideBox, width: Float, defaults: MasterText?, fallbackColor: Long): StaticLayout {
        val raw = box.text
        val styled = SpannableStringBuilder(raw)
        var at = 0
        for (run in box.paragraph.runs) {
            val end = at + run.text.length
            if (end > at) style(styled, at, end, run.format, defaults)
            at = end
        }
        val base = box.defaultSizePt
        val paint = TextPaint(PdfFaces.TEXT_FLAGS).apply {
            if (PdfFaces.plain(styled)) fontFeatureSettings = PdfFaces.TEXT_FEATURES
            textSize = base
            color = (defaults?.color ?: fallbackColor).toInt()
            typeface = PdfFaces.of(defaults?.font, defaults?.bold == true, false)
        }
        val listed = box.listStyle != SlideListStyle.NONE || SlideLevels.hasLevels(raw)
        val text: CharSequence = if (listed) listed(styled, raw, box.listStyle, paint) else breaks(styled, 0, styled.length)
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(when (box.align) {
                TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                TextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            })
            .setIncludePad(false)
            .setLineSpacing(0f, box.lineSpacing)
        if (box.align == TextAlign.JUSTIFY) builder.setJustificationMode(android.graphics.text.LineBreaker.JUSTIFICATION_MODE_INTER_WORD)
        return builder.build()
    }

    /** [s] with the line breaks in [start]..[end] as new lines, where StaticLayout breaks (it does not at U+2028). */
    private fun breaks(s: SpannableStringBuilder, start: Int, end: Int): SpannableStringBuilder {
        for (i in start until end) if (s[i] == SlideLevels.LINE_BREAK) s.replace(i, i + 1, "\n")
        return s
    }

    /** A run's look over [start]..[end]; [defaults] give the face, colour and weight a run leaves unset. */
    private fun style(s: SpannableStringBuilder, start: Int, end: Int, f: CharacterFormat, defaults: MasterText?) {
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        s.setSpan(TypefaceSpan(PdfFaces.of(f.fontFamily ?: defaults?.font, f.bold || defaults?.bold == true, f.italic)), start, end, flags)
        f.fontSizePt?.let { s.setSpan(SizeSpan(it), start, end, flags) }
        (f.color ?: defaults?.color)?.let { s.setSpan(ForegroundColorSpan(it.toInt()), start, end, flags) }
        f.highlight?.let { s.setSpan(BackgroundColorSpan(it.toInt()), start, end, flags) }
        if (f.underline || f.link != null) s.setSpan(UnderlineSpan(), start, end, flags)
        if (f.strikethrough) s.setSpan(StrikethroughSpan(), start, end, flags)
        if (f.superscript || f.subscript) {
            s.setSpan(if (f.superscript) SuperscriptSpan() else SubscriptSpan(), start, end, flags)
            s.setSpan(SizeSpan((f.fontSizePt ?: 18f) * 0.65f), start, end, flags)
        }
    }

    /**
     * [styled] line by line: each line without its level tabs, after its marker (in the colour and
     * size of the line's first character), under a margin of a step per level with the marker hanging.
     */
    private fun listed(styled: SpannableStringBuilder, raw: String, style: SlideListStyle, paint: TextPaint): CharSequence {
        val out = SpannableStringBuilder()
        val starts = SlideLevels.lineStarts(raw)
        val levels = IntArray(starts.size) { SlideLevels.levelAt(raw, starts[it]) }
        val numbers = SlideLevels.numbers(levels)
        val step = SlideLevels.STEP_EMS * paint.textSize
        // Margins go on once the text is whole: a paragraph span grows with text appended at its end.
        val margins = ArrayList<Triple<Int, Int, LeadingMarginSpan>>()
        for (i in starts.indices) {
            val lineEnd = if (i + 1 < starts.size) starts[i + 1] - 1 else raw.length
            val content = starts[i] + levels[i]
            val marker = SlideLevels.marker(style, levels[i], numbers[i])
            val paragraph = out.length
            var hang = 0f
            if (marker.isNotEmpty()) {
                val m = out.length
                out.append(marker).append(' ')
                val size = styled.getSpans(content, content + 1, SizeSpan::class.java).lastOrNull()?.size ?: paint.textSize
                styled.getSpans(content, content + 1, ForegroundColorSpan::class.java).lastOrNull()?.let {
                    out.setSpan(ForegroundColorSpan(it.foregroundColor), m, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                out.setSpan(SizeSpan(size), m, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                hang = TextPaint(paint).apply { textSize = size }.measureText(marker + ' ')
            }
            val text = out.length
            out.append(styled, content, lineEnd)
            if (i + 1 < starts.size) out.append('\n')
            val indent = levels[i] * step
            // Pieces after a line break are paragraphs of their own for StaticLayout, set under the text.
            var piece = paragraph
            var first = true
            for (k in text until out.length) {
                if (out[k] != SlideLevels.LINE_BREAK) continue
                out.replace(k, k + 1, "\n")
                margins.add(Triple(piece, k + 1, if (first) LeadingMarginSpan.Standard(indent.toInt(), (indent + hang).toInt()) else LeadingMarginSpan.Standard((indent + hang).toInt())))
                piece = k + 1
                first = false
            }
            margins.add(Triple(piece, out.length, if (first) LeadingMarginSpan.Standard(indent.toInt(), (indent + hang).toInt()) else LeadingMarginSpan.Standard((indent + hang).toInt())))
        }
        for ((start, end, span) in margins) out.setSpan(span, start, end, Spanned.SPAN_PARAGRAPH)
        return out
    }
}
