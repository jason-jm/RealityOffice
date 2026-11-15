package com.therealityteam.aeropages.model

/**
 * A formula — Word's Office Math (OMML), ODF's MathML — as a tree of the constructs the two share.
 * Nodes are immutable, so copies of a document (undo, the clipboard) share them.
 */
sealed class MathNode {
    /** Math text: letters italic unless [plain] (Word's `m:sty p`, MathML's `mathvariant="normal"`), [bold] as marked. */
    data class Run(val text: String, val plain: Boolean = false, val bold: Boolean = false) : MathNode()

    /** Items side by side. */
    data class Row(val items: List<MathNode>) : MathNode()

    /** [num] over [den]: a bar between them, skewed (a⁄b), linear (a/b), or stacked with no bar. */
    data class Frac(val num: MathNode, val den: MathNode, val kind: FracKind = FracKind.BAR) : MathNode()

    /** [base] with a subscript and / or a superscript after it — before it when [pre] (Word's `m:sPre`). */
    data class Scripts(val base: MathNode, val sub: MathNode?, val sup: MathNode?, val pre: Boolean = false) : MathNode()

    /** √[body], or its [degree]th root. */
    data class Radical(val body: MathNode, val degree: MathNode? = null) : MathNode()

    /**
     * A big operator ([op]: ∑ ∫ ∏ ⋃ …) with its limits — under and over it when [under], else as
     * scripts at its right, as integrals take them — and its operand.
     */
    data class Nary(val op: String, val sub: MathNode?, val sup: MathNode?, val body: MathNode, val under: Boolean) : MathNode()

    /** [items] between [open] and [close] (empty for none), [separator] between them. */
    data class Delimited(val open: String, val close: String, val items: List<MathNode>, val separator: String = "|") : MathNode()

    /** A matrix: rows of cells. */
    data class Matrix(val rows: List<List<MathNode>>) : MathNode()

    /** A function applied: [name] (upright — sin, log, lim) and its [arg]. */
    data class Func(val name: MathNode, val arg: MathNode) : MathNode()

    /** [body] with an accent [mark] over it (a combining character: ̂ ̃ ̄ ̇ ⃗ …). */
    data class Accent(val body: MathNode, val mark: String) : MathNode()

    /** A line over [body] (or under it). */
    data class Bar(val body: MathNode, val over: Boolean = true) : MathNode()

    /** [base] with [limit] under it (or over it): "lim" and what tends to what, a label on a brace … */
    data class Limit(val base: MathNode, val limit: MathNode, val under: Boolean = true) : MathNode()

    /** [body] with a character stretched under or over it: a brace ⏟ ⏞, an arrow. */
    data class GroupChar(val body: MathNode, val char: String, val under: Boolean = true) : MathNode()

    /** Equations one above another (Word's equation array, MathML's `mtable` of one column). */
    data class Stack(val rows: List<MathNode>) : MathNode()

    /** [body] in a frame ([border]), or just kept together. */
    data class Boxed(val body: MathNode, val border: Boolean) : MathNode()

    companion object {
        /** [items] as one node: nothing, the only item, or a row (nested rows flattened). */
        fun row(items: List<MathNode>): MathNode {
            val flat = ArrayList<MathNode>(items.size)
            for (i in items) if (i is Row) flat.addAll(i.items) else flat.add(i)
            return when (flat.size) { 1 -> flat[0]; else -> Row(flat) }
        }
    }
}

enum class FracKind { BAR, SKEWED, LINEAR, NO_BAR }

/**
 * An equation standing in the text (Word's inline `m:oMath`): the runs that show it all carry the
 * same instance in their [CharacterFormat.math], so the writers can put the formula back. [text]
 * is what those runs spell ([MathText.runs]); once an edit changes it, the runs are just text.
 */
class InlineMath(val math: MathNode) {
    val text: String by lazy { MathText.runs(this, CharacterFormat()).joinToString("") { it.text } }
}

/**
 * A displayed equation on its own line (Word's `m:oMathPara`, a formula object in a paragraph of
 * its own in ODF), typeset in two dimensions at [sizePt].
 */
class EquationBlock(var math: MathNode, var align: TextAlign = TextAlign.CENTER, var sizePt: Float = DEFAULT_SIZE) : Block {
    override fun copyOf(): Block = EquationBlock(math, align, sizePt)

    companion object {
        /** Word's size for an equation in a Normal paragraph. */
        const val DEFAULT_SIZE = 11f
    }
}

/** A formula as text: Word's linear format (UnicodeMath) for alt text and search, and as formatted runs for inline display. */
object MathText {
    /** Upright function names Word recognises in linear input and shows as functions. */
    val FUNCTIONS = setOf("sin", "cos", "tan", "cot", "sec", "csc", "sinh", "cosh", "tanh", "coth", "arcsin", "arccos", "arctan",
        "log", "ln", "lg", "exp", "lim", "max", "min", "sup", "inf", "det", "dim", "ker", "deg", "gcd", "arg", "mod", "Pr")

    /** The spacing-free form a script or argument needs round it to be read unambiguously. */
    private fun wrapped(n: MathNode): String {
        val s = linear(n)
        return if (atomic(n)) s else "($s)"
    }

    /** One symbol, a number or a word, a bracketed group, a function, a radical: needs no brackets round it. */
    private fun atomic(n: MathNode): Boolean = when (n) {
        is MathNode.Run -> n.text.length <= 1 || n.text.all { it.isLetterOrDigit() || it == '.' }
        is MathNode.Delimited, is MathNode.Radical, is MathNode.Accent, is MathNode.Bar -> true
        is MathNode.Scripts -> !n.pre
        else -> false
    }

    /** Linear format: `x=(-b±√(b^2-4ac))/(2a)`, `∑_(i=1)^n i`, `(■(a&b@c&d))`. */
    fun linear(n: MathNode): String = when (n) {
        is MathNode.Run -> n.text
        is MathNode.Row -> n.items.joinToString("") { linear(it) }
        is MathNode.Frac -> when (n.kind) {
            FracKind.SKEWED -> wrapped(n.num) + "⁄" + wrapped(n.den)
            FracKind.NO_BAR -> "(" + linear(n.num) + "¦" + linear(n.den) + ")"
            else -> wrapped(n.num) + "/" + wrapped(n.den)
        }
        is MathNode.Scripts -> {
            val scripts = (n.sub?.let { "_" + wrapped(it) } ?: "") + (n.sup?.let { "^" + wrapped(it) } ?: "")
            if (n.pre) "(_" + (n.sub?.let { wrapped(it) } ?: "") + "^" + (n.sup?.let { wrapped(it) } ?: "") + ")" + wrapped(n.base)
            else wrapped(n.base) + scripts
        }
        is MathNode.Radical -> when (val d = n.degree?.let { linear(it) }) {
            null, "" -> "√" + wrapped(n.body)
            "3" -> "∛" + wrapped(n.body)
            "4" -> "∜" + wrapped(n.body)
            else -> "√($d&" + linear(n.body) + ")"
        }
        is MathNode.Nary -> n.op + (n.sub?.let { "_" + wrapped(it) } ?: "") + (n.sup?.let { "^" + wrapped(it) } ?: "") + "▒" + wrapped(n.body)
        is MathNode.Delimited -> n.open + n.items.joinToString(n.separator) { linear(it) } + n.close
        is MathNode.Matrix -> "(■(" + n.rows.joinToString("@") { r -> r.joinToString("&") { linear(it) } } + "))"
        is MathNode.Func -> linear(n.name) + "⁡" + wrapped(n.arg)
        is MathNode.Accent -> wrapped(n.body) + n.mark
        is MathNode.Bar -> if (n.over) "¯" + wrapped(n.body) else "▁" + wrapped(n.body)
        is MathNode.Limit -> wrapped(n.base) + (if (n.under) "┬" else "┴") + wrapped(n.limit)
        is MathNode.GroupChar -> n.char + wrapped(n.body)
        is MathNode.Stack -> "█(" + n.rows.joinToString("@") { linear(it) } + ")"
        is MathNode.Boxed -> (if (n.border) "▭" else "□") + wrapped(n.body)
    }

    /**
     * How an equation in the text reads as formatted runs: letters in italic (words Word shows
     * upright — functions, `m:sty p` text — upright), scripts raised or lowered, fractions and roots
     * written out on the line. Every run gets [math] and [base]'s size and colour, in Cambria Math.
     */
    fun runs(math: InlineMath, base: CharacterFormat): List<TextRun> {
        val out = ArrayList<TextRun>()
        fun fmt(italic: Boolean, sup: Boolean, sub: Boolean, bold: Boolean) = CharacterFormat(
            italic = italic, bold = bold, superscript = sup, subscript = sub,
            fontSizePt = base.fontSizePt, fontFamily = "Cambria Math", color = base.color, math = math,
        )
        fun text(s: String, plain: Boolean, bold: Boolean, sup: Boolean, sub: Boolean) {
            // Latin and Greek letters lean unless the run is plain; digits, operators and brackets stand.
            var i = 0
            while (i < s.length) {
                val italic = !plain && isMathLetter(s[i])
                var j = i + 1
                while (j < s.length && (!plain && isMathLetter(s[j])) == italic) j++
                out.add(TextRun(s.substring(i, j), fmt(italic, sup, sub, bold)))
                i = j
            }
        }
        fun emit(n: MathNode, sup: Boolean, sub: Boolean) {
            fun plainText(s: String) = text(s, true, false, sup, sub)
            fun group(node: MathNode) { if (atomic(node)) emit(node, sup, sub) else { plainText("("); emit(node, sup, sub); plainText(")") } }
            when (n) {
                is MathNode.Run -> text(n.text, n.plain || n.text in FUNCTIONS, n.bold, sup, sub)
                is MathNode.Row -> n.items.forEach { emit(it, sup, sub) }
                is MathNode.Frac -> { group(n.num); plainText(if (n.kind == FracKind.SKEWED) "⁄" else if (n.kind == FracKind.NO_BAR) "¦" else "/"); group(n.den) }
                is MathNode.Scripts -> {
                    // A script inside a script stays at the one raised (or lowered) level the text has.
                    val nested = sup || sub
                    if (n.pre) { n.sub?.let { emit(it, sup, if (nested) sub else true) }; n.sup?.let { emit(it, if (nested) sup else true, sub) }; group(n.base) }
                    else { group(n.base); n.sub?.let { emit(it, sup, if (nested) sub else true) }; n.sup?.let { emit(it, if (nested) sup else true, sub) } }
                }
                is MathNode.Radical -> {
                    val d = n.degree?.let { linear(it) }
                    plainText(when (d) { "3" -> "∛"; "4" -> "∜"; else -> "√" })
                    if (d != "3" && d != "4" && !d.isNullOrEmpty()) n.degree?.let { plainText("("); emit(it, sup, sub); plainText(")") }
                    group(n.body)
                }
                is MathNode.Nary -> {
                    plainText(n.op)
                    val nested = sup || sub
                    n.sub?.let { emit(it, sup, if (nested) sub else true) }
                    n.sup?.let { emit(it, if (nested) sup else true, sub) }
                    plainText(" ")
                    emit(n.body, sup, sub)
                }
                is MathNode.Delimited -> {
                    plainText(n.open)
                    n.items.forEachIndexed { k, it -> if (k > 0) plainText(n.separator); emit(it, sup, sub) }
                    plainText(n.close)
                }
                is MathNode.Matrix -> {
                    plainText("[")
                    n.rows.forEachIndexed { r, row -> if (r > 0) plainText("; "); row.forEachIndexed { c, cell -> if (c > 0) plainText(", "); emit(cell, sup, sub) } }
                    plainText("]")
                }
                is MathNode.Func -> { emit(n.name, sup, sub); plainText(" "); group(n.arg) }
                is MathNode.Accent -> { group(n.body); plainText(n.mark) }
                is MathNode.Bar -> { group(n.body); plainText(if (n.over) "̅" else "̲") }
                is MathNode.Limit -> {
                    emit(n.base, sup, sub)
                    if (sup || sub) emit(n.limit, sup, sub) else if (n.under) emit(n.limit, false, true) else emit(n.limit, true, false)
                }
                is MathNode.GroupChar -> emit(n.body, sup, sub)
                is MathNode.Stack -> n.rows.forEachIndexed { k, it -> if (k > 0) plainText("; "); emit(it, sup, sub) }
                is MathNode.Boxed -> emit(n.body, sup, sub)
            }
        }
        emit(math.math, sup = false, sub = false)
        // Neighbours with one format are one run.
        val merged = ArrayList<TextRun>(out.size)
        for (r in out) {
            val last = merged.lastOrNull()
            if (last != null && last.format == r.format) last.text += r.text else merged.add(r)
        }
        return merged
    }

    /**
     * The equation [p] holds alone (blank runs aside), still as it was read — a text box that is an
     * equation, set as one — or null.
     */
    fun soleEquation(p: Paragraph): InlineMath? {
        var eq: InlineMath? = null
        val text = StringBuilder()
        for (r in p.runs) {
            val m = r.format.math
            if (m == null) { if (r.text.isNotBlank()) return null; continue }
            if (eq == null) eq = m else if (eq !== m) return null
            text.append(r.text)
        }
        return eq?.takeIf { text.toString() == it.text }
    }

    /** A letter that leans in math: Latin and Greek, not digits, operators or CJK. */
    fun isMathLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in 'α'..'ω' || c in 'Α'..'Ω' || c == 'ϕ' || c == 'ϵ' || c == 'ϑ'
}
