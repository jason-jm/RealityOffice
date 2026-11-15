package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.model.FracKind
import com.therealityteam.aeropages.model.MathNode
import com.therealityteam.aeropages.model.MathText

/**
 * MathML — what an ODF formula object (`Object N/content.xml` of media type
 * `application/vnd.oasis.opendocument.formula`) holds — to and from [MathNode]s. Big operators
 * with their limits take the next item of their row as operand, as LibreOffice Math writes them.
 */
internal object MathMl {
    const val MEDIA_TYPE = "application/vnd.oasis.opendocument.formula"

    private val BIG_OPS = setOf("∑", "∏", "∐", "∫", "∬", "∭", "∮", "∯", "∰", "⋃", "⋂", "⋁", "⋀", "⨁", "⨂", "⨀")
    private val OPENS = setOf("(", "[", "{", "⟨", "〈", "⌊", "⌈", "|", "‖")
    private val CLOSES = setOf(")", "]", "}", "⟩", "〉", "⌋", "⌉", "|", "‖")
    private val OPERATORS = "+-−±∓×÷·∗∘∙∩∪∧∨⊕⊗=<>≤≥≠≈≡∼≃≅∝→←↔⇒⇐⇔∈∉∋⊂⊃⊆⊇∣∥⊥:,;!/∖⋅′″"
    private val SPACING_MARKS = mapOf(
        "̂" to "ˆ", "̃" to "˜", "̄" to "¯", "̅" to "¯", "̇" to "˙", "̈" to "¨",
        "́" to "´", "̀" to "`", "̆" to "˘", "̌" to "ˇ", "⃗" to "→",
    )
    private val COMBINING = SPACING_MARKS.entries.filter { it.key != "̄" }.associate { (k, v) -> v to k } +
        mapOf("^" to "\u0302", "~" to "\u0303")

    /**
     * The formula object's `content.xml`: a MathML `math` element, [display] block or inline, with
     * the same formula in StarMath as its annotation — LibreOffice sets a formula from that when it
     * is there (its MathML import loses accents, and pairs up a brace left open).
     */
    fun document(n: MathNode, display: Boolean): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<math xmlns="http://www.w3.org/1998/Math/MathML" display="${if (display) "block" else "inline"}"><semantics><mrow>""")
        node(n)
        append("</mrow>")
        append("""<annotation encoding="StarMath 5.0">""").append(esc(StarMath.of(n))).append("</annotation>")
        append("</semantics></math>")
    }

    private fun esc(s: String) = OdfPackage.escape(s)

    private fun StringBuilder.row(n: MathNode?) {
        if (n == null) { append("<mrow/>"); return }
        append("<mrow>"); node(n); append("</mrow>")
    }

    private fun StringBuilder.node(n: MathNode) {
        when (n) {
            is MathNode.Run -> run(n)
            is MathNode.Row -> n.items.forEach { node(it) }
            is MathNode.Frac -> when (n.kind) {
                FracKind.LINEAR -> { row(n.num); append("<mo>/</mo>"); row(n.den) }
                FracKind.NO_BAR -> { append("""<mfrac linethickness="0">"""); row(n.num); row(n.den); append("</mfrac>") }
                FracKind.SKEWED -> { append("""<mfrac bevelled="true">"""); row(n.num); row(n.den); append("</mfrac>") }
                FracKind.BAR -> { append("<mfrac>"); row(n.num); row(n.den); append("</mfrac>") }
            }
            is MathNode.Scripts -> when {
                n.pre -> {
                    append("<mmultiscripts>"); row(n.base); append("<mprescripts/>")
                    if (n.sub != null) row(n.sub) else append("<none/>")
                    if (n.sup != null) row(n.sup) else append("<none/>")
                    append("</mmultiscripts>")
                }
                n.sub != null && n.sup != null -> { append("<msubsup>"); row(n.base); row(n.sub); row(n.sup); append("</msubsup>") }
                n.sub != null -> { append("<msub>"); row(n.base); row(n.sub); append("</msub>") }
                n.sup != null -> { append("<msup>"); row(n.base); row(n.sup); append("</msup>") }
                else -> node(n.base)
            }
            is MathNode.Radical -> if (n.degree == null) { append("<msqrt>"); node(n.body); append("</msqrt>") }
                else { append("<mroot>"); row(n.body); row(n.degree); append("</mroot>") }
            is MathNode.Nary -> {
                val op = """<mo stretchy="false">${esc(n.op)}</mo>"""
                val tag = when {
                    n.sub != null && n.sup != null -> if (n.under) "munderover" else "msubsup"
                    n.sub != null -> if (n.under) "munder" else "msub"
                    n.sup != null -> if (n.under) "mover" else "msup"
                    else -> null
                }
                if (tag == null) append(op) else {
                    append("<$tag>").append(op)
                    n.sub?.let { row(it) }
                    n.sup?.let { row(it) }
                    append("</$tag>")
                }
                row(n.body)
            }
            is MathNode.Delimited -> {
                append("<mrow>")
                append("""<mo fence="true" form="prefix" stretchy="true">${esc(n.open)}</mo>""")
                n.items.forEachIndexed { k, it ->
                    if (k > 0) append("""<mo fence="true" separator="true" stretchy="true">${esc(n.separator)}</mo>""")
                    row(it)
                }
                // A side with no fence is an empty one: left out, LibreOffice would close the group with the same kind.
                append("""<mo fence="true" form="postfix" stretchy="true">${esc(n.close)}</mo>""")
                append("</mrow>")
            }
            is MathNode.Matrix -> {
                append("<mtable>")
                n.rows.forEach { r -> append("<mtr>"); r.forEach { c -> append("<mtd>"); node(c); append("</mtd>") }; append("</mtr>") }
                append("</mtable>")
            }
            is MathNode.Func -> { append("<mrow>"); funcName(n.name); append("<mo>\u2061</mo>"); row(n.arg); append("</mrow>") }
            is MathNode.Accent -> {
                append("""<mover accent="true">"""); row(n.body)
                append("""<mo stretchy="false">""").append(esc(n.mark)).append("</mo></mover>")
            }
            is MathNode.Bar -> if (n.over) { append("""<mover accent="true">"""); row(n.body); append("""<mo stretchy="true">¯</mo></mover>""") }
                else { append("""<munder accentunder="true">"""); row(n.body); append("""<mo stretchy="true">_</mo></munder>""") }
            is MathNode.Limit -> { val tag = if (n.under) "munder" else "mover"; append("<$tag>"); row(n.base); row(n.limit); append("</$tag>") }
            is MathNode.GroupChar -> {
                val tag = if (n.under) "munder" else "mover"
                append("<$tag>"); row(n.body); append("""<mo stretchy="true">""").append(esc(n.char)).append("</mo></$tag>")
            }
            is MathNode.Stack -> {
                append("<mtable>")
                n.rows.forEach { r -> append("<mtr><mtd>"); node(r); append("</mtd></mtr>") }
                append("</mtable>")
            }
            is MathNode.Boxed -> if (n.border) { append("""<menclose notation="box">"""); node(n.body); append("</menclose>") } else row(n.body)
        }
    }

    /** A function's name: a word is one upright identifier; a letter (f, g) keeps its own style. */
    private fun StringBuilder.funcName(n: MathNode) {
        if (n is MathNode.Run && n.text.length > 1 && n.text.all { it.isLetter() }) append("""<mi mathvariant="normal">""").append(esc(n.text)).append("</mi>")
        else row(n)
    }

    /** A run as MathML tokens: letters as identifiers (one each, italic unless plain), numbers, operators, other text. */
    private fun StringBuilder.run(r: MathNode.Run) {
        if (r.plain && r.text.any { it.isLetter() } && r.text.length > 1 && r.text !in MathText.FUNCTIONS) {
            append("<mtext>").append(esc(r.text)).append("</mtext>"); return
        }
        val variant = when { r.plain && r.bold -> """ mathvariant="bold""""; r.plain -> """ mathvariant="normal""""; r.bold -> """ mathvariant="bold-italic""""; else -> "" }
        var i = 0
        val t = r.text
        while (i < t.length) {
            val c = t[i]
            when {
                c.isDigit() || (c == '.' && i + 1 < t.length && t[i + 1].isDigit()) -> {
                    var j = i + 1
                    while (j < t.length && (t[j].isDigit() || t[j] == '.')) j++
                    append("<mn>").append(t, i, j).append("</mn>"); i = j
                }
                c.isLetter() -> {
                    var j = i + 1
                    while (j < t.length && t[j].isLetter()) j++
                    val word = t.substring(i, j)
                    if (word in MathText.FUNCTIONS) append("<mi>").append(esc(word)).append("</mi>")
                    else word.forEach { ch -> append("<mi$variant>").append(esc(ch.toString())).append("</mi>") }
                    i = j
                }
                c == ' ' -> { append("""<mspace width="0.25em"/>"""); i++ }
                c in OPERATORS || c in "()[]{}|‖⟨⟩" -> { append("<mo>").append(esc(if (c == '-') "−" else c.toString())).append("</mo>"); i++ }
                else -> { append("<mo>").append(esc(c.toString())).append("</mo>"); i++ }
            }
        }
    }

    // ---- reading ----

    /** A formula object's content (a `math` element anywhere in it) as a node; null when there is none. */
    fun read(content: OdfNode?): MathNode? {
        val math = if (content?.name == "math") content else content?.descendants("math")?.firstOrNull()
        return math?.let { MathNode.row(items(it)) }
    }

    /** True when [bytes] (a part of the package) is a formula. */
    fun isFormula(bytes: ByteArray?): Boolean = bytes != null && String(bytes, 0, minOf(bytes.size, 400), Charsets.UTF_8).contains("<math")

    private fun children(n: OdfNode): List<OdfNode> = n.children.filter { it.name != "annotation" && it.name != "annotation-xml" }

    /** The children of a row-like element as nodes: big operators with scripts take the next item as their operand. */
    private fun items(n: OdfNode): List<MathNode> {
        val kids = children(n)
        val out = ArrayList<MathNode>()
        var i = 0
        while (i < kids.size) {
            val k = kids[i]
            val nary = naryOf(k)
            if (nary != null) {
                val body = if (i + 1 < kids.size) element(kids[i + 1]) else MathNode.Row(emptyList())
                out.add(nary(body)); i += 2; continue
            }
            // An opening fence … closing fence run of an mrow is one delimited group.
            if (k.name == "mo" && fence(k) && (k.text.trim() in OPENS || k.text.isBlank())) {
                var depth = 0; var j = i
                while (j < kids.size) {
                    val kj = kids[j]
                    if (kj.name == "mo" && fence(kj) && kj.attr("separator") != "true") {
                        val c = kj.text.trim()
                        // An empty fence is a side left open: it opens the group or closes it.
                        if (j == i || (c in OPENS && c !in CLOSES)) depth++ else if (c in CLOSES || c.isEmpty()) depth--
                    }
                    if (depth == 0) break
                    j++
                }
                if (j < kids.size) {
                    out.add(delimited(kids.subList(i, j + 1))); i = j + 1; continue
                }
            }
            val e = element(k)
            // Invisible operators (function application, invisible times) leave nothing to set.
            if (!(e is MathNode.Run && e.text.isEmpty())) out.add(e)
            i++
        }
        return out
    }

    /** Function application, invisible times, separator and plus. */
    private const val INVISIBLE = "\u2061\u2062\u2063\u2064"

    private fun fence(mo: OdfNode) = mo.attr("fence") == "true" || mo.attr("stretchy") == "true"

    private fun delimited(run: List<OdfNode>): MathNode {
        val open = run.first().text.trim(); val close = run.last().text.trim()
        val inner = run.subList(1, run.size - 1)
        // Separators (fences marked as separators) split the content.
        val parts = ArrayList<MutableList<OdfNode>>().apply { add(ArrayList()) }
        var sep = "|"
        for (k in inner) if (k.name == "mo" && k.attr("separator") == "true") { sep = k.text.trim(); parts.add(ArrayList()) } else parts.last().add(k)
        val holder = OdfNode("mrow", emptyMap())
        return MathNode.Delimited(open, close, parts.map { p -> holder.parts.clear(); holder.parts.addAll(p); MathNode.row(items(holder)) }, sep)
    }

    /** A scripted big operator (∑ with limits …) as a function of its operand; null for anything else. */
    private fun naryOf(n: OdfNode): ((MathNode) -> MathNode)? {
        val kids = children(n)
        val under = when (n.name) { "munderover", "munder", "mover" -> true; "msubsup", "msub", "msup" -> false; "mo" -> null; else -> return null }
        val opNode = if (n.name == "mo") n else kids.firstOrNull() ?: return null
        val op = opNode.text.trim()
        if (opNode.name != "mo" || op !in BIG_OPS) return null
        if (under == null) return { body -> MathNode.Nary(op, null, null, body, op !in setOf("∫", "∬", "∭", "∮")) }
        val a = kids.getOrNull(1)?.let { element(it) }; val b = kids.getOrNull(2)?.let { element(it) }
        val (sub, sup) = when (n.name) { "munder", "msub" -> a to null; "mover", "msup" -> null to a; else -> a to b }
        return { body -> MathNode.Nary(op, sub, sup, body, under) }
    }

    private fun element(n: OdfNode): MathNode {
        val kids = children(n)
        fun arg(i: Int) = kids.getOrNull(i)?.let { element(it) } ?: MathNode.Row(emptyList())
        return when (n.name) {
            "mi" -> {
                val t = n.text.trim()
                val variant = n.attr("mathvariant")
                MathNode.Run(t, plain = variant == "normal" || variant == "bold" || (variant == null && t.length > 1), bold = variant?.startsWith("bold") == true)
            }
            "mn" -> MathNode.Run(n.text.trim(), plain = true)
            "mo" -> MathNode.Run(n.text.trim().filterNot { it in INVISIBLE }, plain = true)
            "mtext", "ms" -> MathNode.Run(n.text, plain = true)
            "mspace" -> MathNode.Run(" ", plain = true)
            "mfrac" -> MathNode.Frac(arg(0), arg(1), when {
                n.attr("linethickness")?.trim()?.trimEnd('p', 't', 'x', 'e', 'm', '%')?.toFloatOrNull() == 0f -> FracKind.NO_BAR
                n.attr("bevelled") == "true" -> FracKind.SKEWED
                else -> FracKind.BAR
            })
            "msqrt" -> MathNode.Radical(MathNode.row(items(n)))
            "mroot" -> MathNode.Radical(arg(0), arg(1))
            "msub" -> MathNode.Scripts(arg(0), arg(1), null)
            "msup" -> MathNode.Scripts(arg(0), null, arg(1))
            "msubsup" -> MathNode.Scripts(arg(0), arg(1), arg(2))
            "mmultiscripts" -> {
                val pre = kids.indexOfFirst { it.name == "mprescripts" }
                fun opt(i: Int) = kids.getOrNull(i)?.takeIf { it.name != "none" }?.let { element(it) }
                if (pre >= 0) MathNode.Scripts(arg(0), opt(pre + 1), opt(pre + 2), pre = true)
                else MathNode.Scripts(arg(0), opt(1), opt(2))
            }
            "munder", "mover", "munderover" -> overUnder(n, kids)
            "mtable" -> {
                val rows = kids.filter { it.name == "mtr" || it.name == "mlabeledtr" }.map { r ->
                    children(r).filter { it.name == "mtd" }.map { MathNode.row(items(it)) }
                }
                if (rows.isNotEmpty() && rows.all { it.size == 1 }) MathNode.Stack(rows.map { it[0] }) else MathNode.Matrix(rows)
            }
            "menclose" -> MathNode.Boxed(MathNode.row(items(n)), border = true)
            "mphantom" -> MathNode.Row(emptyList())
            "mfenced" -> MathNode.Delimited(n.attr("open") ?: "(", n.attr("close") ?: ")", kids.map { element(it) }, n.attr("separators")?.trim()?.take(1) ?: ",")
            else -> MathNode.row(items(n))   // mrow, mstyle, mpadded, semantics …
        }
    }

    /** `munder` / `mover` / `munderover`: an accent, a bar, a brace, or limits under / over their base. */
    private fun overUnder(n: OdfNode, kids: List<OdfNode>): MathNode {
        val base = kids.getOrNull(0)?.let { element(it) } ?: MathNode.Row(emptyList())
        val first = kids.getOrNull(1)
        val second = kids.getOrNull(2)
        val mark = first?.takeIf { it.name == "mo" }?.text?.trim()
        if (n.name != "munderover" && mark != null) {
            if (n.name == "mover" && (mark == "¯" || mark == "‾" || mark == "̅")) return MathNode.Bar(base, over = true)
            if (n.name == "munder" && (mark == "_" || mark == "‾" || mark == "¯" || mark == "̲")) return MathNode.Bar(base, over = false)
            if (mark in setOf("⏟", "⏞", "︸", "︷")) return MathNode.GroupChar(base, mark, under = n.name == "munder")
            if (n.name == "mover" && (n.attr("accent") == "true" || mark in COMBINING)) return MathNode.Accent(base, COMBINING[mark] ?: mark)
        }
        return when (n.name) {
            "munder" -> MathNode.Limit(base, first?.let { element(it) } ?: MathNode.Row(emptyList()), under = true)
            "mover" -> MathNode.Limit(base, first?.let { element(it) } ?: MathNode.Row(emptyList()), under = false)
            else -> MathNode.Limit(MathNode.Limit(base, first?.let { element(it) } ?: MathNode.Row(emptyList()), under = true),
                second?.let { element(it) } ?: MathNode.Row(emptyList()), under = false)
        }
    }
}
