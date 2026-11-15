package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.model.FracKind
import com.therealityteam.aeropages.model.MathNode
import com.therealityteam.aeropages.model.MathText

/**
 * A formula in StarMath, LibreOffice Math's own language (`{a} over {b}`, `sum from{i=1} to{n}`,
 * `left ( … right )`): the annotation LibreOffice sets an ODF formula object from, and shows in its
 * formula editor.
 */
internal object StarMath {
    /** [n] as StarMath. */
    fun of(n: MathNode): String = StringBuilder().apply { node(n) }.toString().trim().replace(Regex(" {2,}"), " ")

    private val FUNCTIONS = setOf("sin", "cos", "tan", "cot", "sinh", "cosh", "tanh", "coth", "arcsin", "arccos", "arctan", "arccot",
        "arsinh", "arcosh", "artanh", "arcoth", "ln", "log", "exp")
    private val LIMITS = setOf("lim", "liminf", "limsup")

    private val OPERATORS = mapOf(
        '+' to "+", '-' to "-", '−' to "-", '±' to "+-", '∓' to "-+", '×' to "times", '·' to "cdot", '⋅' to "cdot", '÷' to "div", '∗' to "*",
        '=' to "=", '≠' to "<>", '<' to "<", '>' to ">", '≤' to "<=", '≥' to ">=", '≪' to "<<", '≫' to ">>", '≈' to "approx", '≡' to "equiv",
        '∼' to "sim", '≃' to "simeq", '∝' to "prop", '→' to "toward", '⇒' to "drarrow", '⇔' to "dlrarrow", '∈' to "in", '∉' to "notin",
        '∋' to "owns", '⊂' to "subset", '⊃' to "supset", '⊆' to "subseteq", '⊇' to "supseteq", '∪' to "union", '∩' to "intersection",
        '∖' to "setminus", '∧' to "and", '∨' to "or", '¬' to "neg", '∥' to "parallel", '⊥' to "ortho", '∣' to "divides", '∘' to "circ",
        '∞' to "infinity", '∂' to "partial", '∇' to "nabla", '∃' to "exists", '∀' to "forall", '∅' to "emptyset", '…' to "dotslow", '⋯' to "dotsaxis",
        ',' to ",", ';' to ";", ':' to ":", '(' to "\\(", ')' to "\\)", '[' to "\\[", ']' to "\\]", '{' to "\\{", '}' to "\\}",
    )
    private val NARY = mapOf("∑" to "sum", "∏" to "prod", "∐" to "coprod", "∫" to "int", "∬" to "iint", "∭" to "iiint", "∮" to "lint", "∯" to "llint", "∰" to "lllint")
    private val FENCES = mapOf("(" to "(", ")" to ")", "[" to "[", "]" to "]", "{" to "lbrace", "}" to "rbrace", "⟨" to "langle", "⟩" to "rangle",
        "〈" to "langle", "〉" to "rangle", "|" to "lline", "‖" to "ldline", "⌊" to "lfloor", "⌋" to "rfloor", "⌈" to "lceil", "⌉" to "rceil")
    private val CLOSING = mapOf("|" to "rline", "‖" to "rdline")
    private val ACCENTS = mapOf("̂" to "hat", "̃" to "tilde", "̄" to "bar", "̅" to "bar", "̇" to "dot", "̈" to "ddot",
        "⃛" to "dddot", "́" to "acute", "̀" to "grave", "̆" to "breve", "̌" to "check", "̊" to "circle", "⃗" to "vec")

    private fun StringBuilder.group(n: MathNode?) {
        append(" {")
        n?.let { node(it) }
        append(" } ")
    }

    private fun quoted(s: String) = "\"" + s.replace("\"", "'") + "\""

    private fun StringBuilder.node(n: MathNode) {
        when (n) {
            is MathNode.Run -> run(n)
            is MathNode.Row -> { append(" {"); n.items.forEach { node(it) }; append(" } ") }
            is MathNode.Frac -> when (n.kind) {
                FracKind.BAR -> { group(n.num); append("over"); group(n.den) }
                FracKind.NO_BAR -> { append(" stack {"); node(n.num); append(" # "); node(n.den); append(" } ") }
                FracKind.SKEWED -> { group(n.num); append("wideslash"); group(n.den) }
                FracKind.LINEAR -> { group(n.num); append("/"); group(n.den) }
            }
            is MathNode.Scripts -> {
                group(n.base)
                val (sub, sup) = if (n.pre) "lsub" to "lsup" else "rsub" to "rsup"
                n.sub?.let { append(sub); group(it) }
                n.sup?.let { append(sup); group(it) }
            }
            is MathNode.Radical -> if (n.degree == null) { append(" sqrt"); group(n.body) } else { append(" nroot"); group(n.degree); group(n.body) }
            is MathNode.Nary -> {
                val op = NARY[n.op]
                if (op != null) {
                    // A known operator takes its limits where LibreOffice puts them: under / over a sum, beside an integral.
                    append(" ").append(op)
                    n.sub?.let { append(" from"); group(it) }
                    n.sup?.let { append(" to"); group(it) }
                } else {
                    append(" ").append(quoted(n.op)).append(" ")
                    val (sub, sup) = if (n.under) "csub" to "csup" else "rsub" to "rsup"
                    n.sub?.let { append(sub); group(it) }
                    n.sup?.let { append(sup); group(it) }
                }
                group(n.body)
            }
            is MathNode.Delimited -> {
                append(" left ").append(if (n.open.isEmpty()) "none" else FENCES[n.open] ?: "none")
                n.items.forEachIndexed { k, it ->
                    if (k > 0) append(if (n.separator == "|") " mline " else " " + quoted(n.separator) + " ")
                    group(it)
                }
                append(" right ").append(if (n.close.isEmpty()) "none" else CLOSING[n.close] ?: FENCES[n.close] ?: "none").append(" ")
            }
            is MathNode.Matrix -> {
                append(" matrix {")
                n.rows.forEachIndexed { r, row ->
                    if (r > 0) append(" ## ")
                    row.forEachIndexed { c, cell -> if (c > 0) append(" # "); group(cell) }
                }
                append(" } ")
            }
            is MathNode.Func -> {
                val name = (n.name as? MathNode.Run)?.text
                val limit = (n.name as? MathNode.Limit)?.takeIf { l -> l.under && (l.base as? MathNode.Run)?.text in LIMITS }
                when {
                    // Keywords take the operand that follows them: `sin {x}`, `lim from {x toward 0} {…}`.
                    name != null && name in FUNCTIONS -> append(" ").append(name)
                    limit != null -> { append(" ").append((limit.base as MathNode.Run).text).append(" from"); group(limit.limit) }
                    name != null && name.all { it.isLetter() } -> append(" func ").append(name)
                    else -> group(n.name)
                }
                group(n.arg)
            }
            is MathNode.Accent -> {
                val body = MathText.linear(n.body)
                val key = ACCENTS[n.mark]
                if (key == null) { group(n.body); append("csup"); group(MathNode.Run(n.mark, plain = true)) }
                else { append(" ").append(if (body.length > 1 && key in setOf("hat", "tilde", "vec")) "wide$key" else key); group(n.body) }
            }
            is MathNode.Bar -> { append(if (n.over) " overline" else " underline"); group(n.body) }
            is MathNode.Limit -> { group(n.base); append(if (n.under) "csub" else "csup"); group(n.limit) }
            is MathNode.GroupChar -> {
                val brace = n.char == "⏟" || n.char == "⏞"
                group(n.body)
                if (brace) { append(if (n.under) "underbrace" else "overbrace"); append(" { } ") }
                else { append(if (n.under) "csub" else "csup"); append(" ").append(quoted(n.char)).append(" ") }
            }
            is MathNode.Stack -> {
                append(" stack {")
                n.rows.forEachIndexed { k, r -> if (k > 0) append(" # "); node(r) }
                append(" } ")
            }
            is MathNode.Boxed -> node(n.body)
        }
    }

    /** A run: italic letters one by one (so no name reads as a keyword), upright words quoted, numbers, operators. */
    private fun StringBuilder.run(r: MathNode.Run) {
        val t = r.text
        if (r.bold) append(" bold {")
        var i = 0
        while (i < t.length) {
            val c = t[i]
            when {
                c.isDigit() || (c == '.' && i + 1 < t.length && t[i + 1].isDigit()) -> {
                    var j = i + 1
                    while (j < t.length && (t[j].isDigit() || t[j] == '.')) j++
                    append(" ").append(t, i, j).append(" "); i = j
                }
                c.isLetter() -> {
                    var j = i + 1
                    while (j < t.length && t[j].isLetter()) j++
                    val word = t.substring(i, j)
                    when {
                        // A function's name on its own is text (as a keyword it would want an operand).
                        word in FUNCTIONS || word in LIMITS -> append(" ").append(quoted(word)).append(" ")
                        r.plain && word.length > 1 -> append(" ").append(quoted(word)).append(" ")
                        r.plain -> append(" nitalic ").append(word).append(" ")
                        else -> word.forEach { ch -> append(" ").append(ch).append(" ") }
                    }
                    i = j
                }
                c == ' ' -> { append(" ~ "); i++ }
                else -> { append(" ").append(OPERATORS[c] ?: quoted(c.toString())).append(" "); i++ }
            }
        }
        if (r.bold) append(" } ")
    }
}
