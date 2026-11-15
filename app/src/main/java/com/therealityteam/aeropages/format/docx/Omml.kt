package com.therealityteam.aeropages.format.docx

import com.therealityteam.aeropages.format.XmlNode
import com.therealityteam.aeropages.model.FracKind
import com.therealityteam.aeropages.model.MathNode

/**
 * Office Math (OMML, the `m:` markup Word keeps equations in) to and from [MathNode]s: fractions,
 * scripts, radicals, n-ary operators, delimiters, matrices, functions, accents, bars, limits,
 * grouping characters, equation arrays and boxes. Properties Word leaves out take its defaults
 * (an `m:nary` without `m:chr` is an integral, an `m:d` without `m:begChr` a parenthesis).
 */
internal object Omml {
    const val M = "http://schemas.openxmlformats.org/officeDocument/2006/math"

    /** Integral signs take their limits as scripts at their right; other n-ary operators under and over them. */
    private val INTEGRALS = setOf("∫", "∬", "∭", "∮", "∯", "∰", "∱", "∲", "∳")

    /** An `m:oMath` — or any element holding math, an argument like `m:e` or `m:num` — as one node. */
    fun read(node: XmlNode): MathNode = MathNode.row(node.children.mapNotNull { element(it) })

    private fun empty(n: MathNode) = n is MathNode.Row && n.items.isEmpty()
    private fun arg(parent: XmlNode, name: String): MathNode? = parent.child(name)?.let { read(it) }?.takeUnless { empty(it) }
    private fun argOrEmpty(parent: XmlNode, name: String): MathNode = parent.child(name)?.let { read(it) } ?: MathNode.Row(emptyList())
    private fun prop(parent: XmlNode, pr: String, name: String): String? = parent.child(pr)?.child(name)?.attr("val")

    /** An on/off property: on when present without a value, or with 1 / on / true. */
    private fun flag(parent: XmlNode, pr: String, name: String): Boolean {
        val el = parent.child(pr)?.child(name) ?: return false
        val v = el.attr("val") ?: return true
        return v == "1" || v == "on" || v == "true"
    }

    private fun element(n: XmlNode): MathNode? = when (n.name) {
        "r" -> run(n)
        "f" -> MathNode.Frac(argOrEmpty(n, "num"), argOrEmpty(n, "den"), when (prop(n, "fPr", "type")) {
            "skw" -> FracKind.SKEWED; "lin" -> FracKind.LINEAR; "noBar" -> FracKind.NO_BAR; else -> FracKind.BAR
        })
        "sSup" -> MathNode.Scripts(argOrEmpty(n, "e"), null, arg(n, "sup"))
        "sSub" -> MathNode.Scripts(argOrEmpty(n, "e"), arg(n, "sub"), null)
        "sSubSup" -> MathNode.Scripts(argOrEmpty(n, "e"), arg(n, "sub"), arg(n, "sup"))
        "sPre" -> MathNode.Scripts(argOrEmpty(n, "e"), arg(n, "sub"), arg(n, "sup"), pre = true)
        "rad" -> MathNode.Radical(argOrEmpty(n, "e"), if (flag(n, "radPr", "degHide")) null else arg(n, "deg"))
        "nary" -> {
            val op = prop(n, "naryPr", "chr")?.takeIf { it.isNotEmpty() } ?: "∫"
            val under = when (prop(n, "naryPr", "limLoc")) { "undOvr" -> true; "subSup" -> false; else -> op !in INTEGRALS }
            MathNode.Nary(op, if (flag(n, "naryPr", "subHide")) null else arg(n, "sub"),
                if (flag(n, "naryPr", "supHide")) null else arg(n, "sup"), argOrEmpty(n, "e"), under)
        }
        "d" -> {
            val pr = n.child("dPr")
            // A missing character is the default; one given as "" is none.
            fun chr(name: String, default: String) = pr?.child(name)?.let { it.attr("val") ?: "" } ?: default
            MathNode.Delimited(chr("begChr", "("), chr("endChr", ")"), n.children("e").map { read(it) }, chr("sepChr", "|"))
        }
        "m" -> MathNode.Matrix(n.children("mr").map { r -> r.children("e").map { read(it) } })
        "func" -> MathNode.Func(argOrEmpty(n, "fName"), argOrEmpty(n, "e"))
        "acc" -> MathNode.Accent(argOrEmpty(n, "e"), prop(n, "accPr", "chr")?.takeIf { it.isNotEmpty() } ?: "̂")
        "bar" -> MathNode.Bar(argOrEmpty(n, "e"), over = prop(n, "barPr", "pos") == "top")
        "limLow" -> MathNode.Limit(argOrEmpty(n, "e"), argOrEmpty(n, "lim"), under = true)
        "limUpp" -> MathNode.Limit(argOrEmpty(n, "e"), argOrEmpty(n, "lim"), under = false)
        "groupChr" -> MathNode.GroupChar(argOrEmpty(n, "e"), prop(n, "groupChrPr", "chr")?.takeIf { it.isNotEmpty() } ?: "⏟",
            under = prop(n, "groupChrPr", "pos") != "top")
        "eqArr" -> MathNode.Stack(n.children("e").map { read(it) })
        "box" -> MathNode.Boxed(argOrEmpty(n, "e"), border = false)
        "borderBox" -> MathNode.Boxed(argOrEmpty(n, "e"), border = true)
        "phant" -> if (flag(n, "phantPr", "show") || n.child("phantPr")?.child("show") == null) argOrEmpty(n, "e") else null
        // Tracked deletions are not there; properties are read by their owners.
        "del", "delText" -> null
        else -> if (n.name.endsWith("Pr") || n.children.isEmpty()) null else read(n)
    }

    /** An `m:r`: its text (`m:t`, or `w:t` inside it), upright when `m:sty` says so or it is normal text (`m:nor`). */
    private fun run(n: XmlNode): MathNode? {
        val text = buildString { for (c in n.children) if (c.name == "t") append(c.text) }
        if (text.isEmpty()) return null
        val sty = prop(n, "rPr", "sty")
        val normal = flag(n, "rPr", "nor")
        return MathNode.Run(text, plain = normal || sty == "p" || sty == "b", bold = sty == "b" || sty == "bi")
    }

    /**
     * [n] as OMML (the content of an `m:oMath`), its runs at [sizePt] when given. Each run carries
     * [runProps] — by default Word's `w:rPr` (Cambria Math, the size); PowerPoint's runs carry an `a:rPr`.
     */
    fun write(n: MathNode, sizePt: Float? = null, runProps: String? = null): String {
        this.runProps = runProps ?: buildString {
            append("""<w:rPr><w:rFonts w:ascii="Cambria Math" w:hAnsi="Cambria Math"/>""")
            sizePt?.let { val hp = Ooxml.ptToHalfPt(it); append("""<w:sz w:val="$hp"/><w:szCs w:val="$hp"/>""") }
            append("</w:rPr>")
        }
        return StringBuilder().apply { node(n) }.toString()
    }

    /** Each math run's `w:rPr`: Cambria Math, and the formula's size. */
    private var runProps = ""

    private fun StringBuilder.arg(tag: String, n: MathNode?) {
        append("<m:$tag>"); n?.let { node(it) }; append("</m:$tag>")
    }

    private fun StringBuilder.node(n: MathNode) {
        when (n) {
            is MathNode.Run -> {
                append("<m:r>")
                val sty = when { n.plain && n.bold -> "b"; n.plain -> "p"; n.bold -> "bi"; else -> null }
                if (sty != null) append("""<m:rPr><m:sty m:val="$sty"/></m:rPr>""")
                append(runProps)
                append("""<m:t xml:space="preserve">""").append(Ooxml.escape(n.text)).append("</m:t></m:r>")
            }
            is MathNode.Row -> n.items.forEach { node(it) }
            is MathNode.Frac -> {
                append("<m:f>")
                when (n.kind) {
                    FracKind.SKEWED -> append("""<m:fPr><m:type m:val="skw"/></m:fPr>""")
                    FracKind.LINEAR -> append("""<m:fPr><m:type m:val="lin"/></m:fPr>""")
                    FracKind.NO_BAR -> append("""<m:fPr><m:type m:val="noBar"/></m:fPr>""")
                    FracKind.BAR -> {}
                }
                arg("num", n.num); arg("den", n.den)
                append("</m:f>")
            }
            is MathNode.Scripts -> when {
                n.pre -> { append("<m:sPre>"); arg("sub", n.sub); arg("sup", n.sup); arg("e", n.base); append("</m:sPre>") }
                n.sub != null && n.sup != null -> { append("<m:sSubSup>"); arg("e", n.base); arg("sub", n.sub); arg("sup", n.sup); append("</m:sSubSup>") }
                n.sub != null -> { append("<m:sSub>"); arg("e", n.base); arg("sub", n.sub); append("</m:sSub>") }
                n.sup != null -> { append("<m:sSup>"); arg("e", n.base); arg("sup", n.sup); append("</m:sSup>") }
                else -> node(n.base)
            }
            is MathNode.Radical -> {
                append("<m:rad>")
                if (n.degree == null) append("""<m:radPr><m:degHide m:val="1"/></m:radPr>""")
                arg("deg", n.degree); arg("e", n.body)
                append("</m:rad>")
            }
            is MathNode.Nary -> {
                append("""<m:nary><m:naryPr><m:chr m:val="${Ooxml.escape(n.op)}"/><m:limLoc m:val="${if (n.under) "undOvr" else "subSup"}"/>""")
                if (n.sub == null) append("""<m:subHide m:val="1"/>""")
                if (n.sup == null) append("""<m:supHide m:val="1"/>""")
                append("</m:naryPr>")
                arg("sub", n.sub); arg("sup", n.sup); arg("e", n.body)
                append("</m:nary>")
            }
            is MathNode.Delimited -> {
                append("<m:d>")
                if (n.open != "(" || n.close != ")" || n.separator != "|") {
                    append("<m:dPr>")
                    if (n.open != "(") append("""<m:begChr m:val="${Ooxml.escape(n.open)}"/>""")
                    if (n.separator != "|") append("""<m:sepChr m:val="${Ooxml.escape(n.separator)}"/>""")
                    if (n.close != ")") append("""<m:endChr m:val="${Ooxml.escape(n.close)}"/>""")
                    append("</m:dPr>")
                }
                if (n.items.isEmpty()) arg("e", null) else n.items.forEach { arg("e", it) }
                append("</m:d>")
            }
            is MathNode.Matrix -> {
                val cols = n.rows.maxOfOrNull { it.size } ?: 1
                append("""<m:m><m:mPr><m:mcs><m:mc><m:mcPr><m:count m:val="$cols"/><m:mcJc m:val="center"/></m:mcPr></m:mc></m:mcs></m:mPr>""")
                n.rows.forEach { r -> append("<m:mr>"); for (c in 0 until cols) arg("e", r.getOrNull(c)); append("</m:mr>") }
                append("</m:m>")
            }
            is MathNode.Func -> { append("<m:func>"); arg("fName", n.name); arg("e", n.arg); append("</m:func>") }
            is MathNode.Accent -> { append("""<m:acc><m:accPr><m:chr m:val="${Ooxml.escape(n.mark)}"/></m:accPr>"""); arg("e", n.body); append("</m:acc>") }
            is MathNode.Bar -> { append("""<m:bar><m:barPr><m:pos m:val="${if (n.over) "top" else "bot"}"/></m:barPr>"""); arg("e", n.body); append("</m:bar>") }
            is MathNode.Limit -> {
                val tag = if (n.under) "limLow" else "limUpp"
                append("<m:$tag>"); arg("e", n.base); arg("lim", n.limit); append("</m:$tag>")
            }
            is MathNode.GroupChar -> {
                append("""<m:groupChr><m:groupChrPr><m:chr m:val="${Ooxml.escape(n.char)}"/><m:pos m:val="${if (n.under) "bot" else "top"}"/>""")
                append("""<m:vertJc m:val="${if (n.under) "top" else "bot"}"/></m:groupChrPr>""")
                arg("e", n.body)
                append("</m:groupChr>")
            }
            is MathNode.Stack -> { append("<m:eqArr>"); n.rows.forEach { arg("e", it) }; append("</m:eqArr>") }
            is MathNode.Boxed -> {
                val tag = if (n.border) "borderBox" else "box"
                append("<m:$tag>"); arg("e", n.body); append("</m:$tag>")
            }
        }
    }
}
