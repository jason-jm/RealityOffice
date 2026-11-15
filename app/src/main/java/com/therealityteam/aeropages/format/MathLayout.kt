package com.therealityteam.aeropages.format

import com.therealityteam.aeropages.model.FracKind
import com.therealityteam.aeropages.model.MathNode
import com.therealityteam.aeropages.model.MathText

/** How the typesetter measures text: the device's fonts on screen and in the PDF, an estimate in tests. */
interface MathFont {
    /** Advance width of [text] at [size]. */
    fun width(text: String, size: Float, italic: Boolean, bold: Boolean): Float
    /** How far [text]'s ink reaches above the baseline and below it, at [size]. */
    fun ink(text: String, size: Float, italic: Boolean, bold: Boolean): Pair<Float, Float>
}

/** Something to draw, placed from its box's origin (the left end of its baseline); y grows downwards. */
sealed class MathItem {
    /** Text whose baseline starts at ([x], [y]). */
    data class Glyphs(val x: Float, val y: Float, val text: String, val size: Float, val italic: Boolean, val bold: Boolean) : MathItem()
    /** A filled rectangle: fraction bars, overlines, the radical's roof. */
    data class Rule(val x: Float, val y: Float, val w: Float, val h: Float) : MathItem()
    /** A stroked outline: the radical sign, stretched brackets and braces, arrows. */
    data class Stroke(val path: List<PathOp>, val width: Float) : MathItem()
}

sealed class PathOp {
    data class Move(val x: Float, val y: Float) : PathOp()
    data class Line(val x: Float, val y: Float) : PathOp()
    data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : PathOp()
}

/** A typeset formula: [width], how far it reaches above its baseline and below it, and what to draw. */
class MathBox(val width: Float, val ascent: Float, val descent: Float, val items: List<MathItem>) {
    val height: Float get() = ascent + descent

    /** The same box shifted by ([dx], [dy]). */
    fun items(dx: Float, dy: Float): List<MathItem> = if (dx == 0f && dy == 0f) items else items.map { it.moved(dx, dy) }

    companion object {
        val EMPTY = MathBox(0f, 0f, 0f, emptyList())
    }
}

private fun MathItem.moved(dx: Float, dy: Float): MathItem = when (this) {
    is MathItem.Glyphs -> copy(x = x + dx, y = y + dy)
    is MathItem.Rule -> copy(x = x + dx, y = y + dy)
    is MathItem.Stroke -> copy(path = path.map { op ->
        when (op) {
            is PathOp.Move -> PathOp.Move(op.x + dx, op.y + dy)
            is PathOp.Line -> PathOp.Line(op.x + dx, op.y + dy)
            is PathOp.Cubic -> PathOp.Cubic(op.x1 + dx, op.y1 + dy, op.x2 + dx, op.y2 + dy, op.x + dx, op.y + dy)
        }
    })
}

/**
 * A small TeX-style typesetter for [MathNode]s: display, text, script and script-script styles;
 * spacing round binary operators and relations; fractions on the math axis; scripts raised and
 * lowered; radicals, big operators with limits, stretched brackets, matrices, accents and bars.
 * Units are whatever [size] is in (points for the PDF, pixels on screen).
 */
object MathLayout {
    enum class Style { DISPLAY, TEXT, SCRIPT, SCRIPT2 }

    private fun Style.smaller(): Style = when (this) { Style.DISPLAY, Style.TEXT -> Style.SCRIPT; else -> Style.SCRIPT2 }
    private fun Style.fracChild(): Style = when (this) { Style.DISPLAY -> Style.TEXT; Style.TEXT -> Style.SCRIPT; else -> Style.SCRIPT2 }
    private fun Style.scale(base: Float): Float = when (this) { Style.DISPLAY, Style.TEXT -> base; Style.SCRIPT -> base * 0.71f; Style.SCRIPT2 -> base * 0.55f }

    /** Typeset [node] at [size] (the text size) in display or text style. */
    fun layout(node: MathNode, font: MathFont, size: Float, display: Boolean = true): MathBox =
        Setter(font, size).box(node, if (display) Style.DISPLAY else Style.TEXT)

    /** A displayed formula at [size], set smaller (to half size at most) when it is wider than [maxWidth]. */
    fun layoutFitting(node: MathNode, font: MathFont, size: Float, maxWidth: Float): MathBox {
        val box = layout(node, font, size)
        if (box.width <= maxWidth || maxWidth <= 0f) return box
        return layout(node, font, size * (maxWidth / box.width).coerceIn(0.5f, 1f))
    }

    // Atom classes for spacing, after TeX.
    private enum class Cls { ORD, OP, BIN, REL, OPEN, CLOSE, PUNCT, INNER }

    private const val RELATIONS = "=<>≤≥≠≈≡∼≃≅∝→←↔⇒⇐⇔∈∉∋⊂⊃⊆⊇≪≫≺≻∣∥⊥:≔≜↦⟶⟹"
    private const val BINARIES = "+-−±∓×÷·∗∘∙∩∪∧∨⊕⊗⊖⊙∖⋅"
    private const val OPENS = "([{⟨⌊⌈|‖"
    private const val CLOSES = ")]}⟩⌋⌉"
    private const val PUNCT = ",;"

    private class Atom(val box: MathBox, var cls: Cls)

    private class Setter(val font: MathFont, val base: Float) {
        fun axis(s: Float) = 0.25f * s
        fun rule(s: Float) = maxOf(0.055f * s, 0.6f)

        fun box(n: MathNode, style: Style): MathBox = row(atoms(n, style), style)

        /** [n] as atoms to set in a row (a run gives one per token, so operators get their spacing). */
        fun atoms(n: MathNode, style: Style): List<Atom> {
            val s = style.scale(base)
            return when (n) {
                is MathNode.Run -> tokens(n, s)
                is MathNode.Row -> n.items.flatMap { atoms(it, style) }
                is MathNode.Frac -> listOf(Atom(frac(n, style), Cls.INNER))
                is MathNode.Scripts -> listOf(Atom(scripts(n, style), scriptsClass(n.base)))
                is MathNode.Radical -> listOf(Atom(radical(n, style), Cls.ORD))
                is MathNode.Nary -> nary(n, style)
                is MathNode.Delimited -> listOf(Atom(delimited(n, style), Cls.INNER))
                is MathNode.Matrix -> listOf(Atom(matrix(n, style), Cls.INNER))
                is MathNode.Func -> listOf(Atom(func(n, style), Cls.OP))
                is MathNode.Accent -> listOf(Atom(accent(n, style), Cls.ORD))
                is MathNode.Bar -> listOf(Atom(bar(n, style), Cls.ORD))
                is MathNode.Limit -> listOf(Atom(limit(n, style), Cls.OP))
                is MathNode.GroupChar -> listOf(Atom(groupChar(n, style), Cls.ORD))
                is MathNode.Stack -> listOf(Atom(stack(n, style), Cls.INNER))
                is MathNode.Boxed -> listOf(Atom(boxed(n, style), Cls.ORD))
            }
        }

        private fun scriptsClass(base: MathNode): Cls = when {
            base is MathNode.Run && base.text.length == 1 && base.text[0] in RELATIONS -> Cls.REL
            else -> Cls.ORD
        }

        /** A text box: [text] set at [s]. */
        fun glyphs(text: String, s: Float, italic: Boolean, bold: Boolean): MathBox {
            if (text.isEmpty()) return MathBox.EMPTY
            val w = font.width(text, s, italic, bold)
            val (a, d) = font.ink(text, s, italic, bold)
            // Italic letters lean past their advance: a little room keeps a following glyph off them.
            val lean = if (italic) 0.04f * s else 0f
            return MathBox(w + lean, maxOf(a, 0f), maxOf(d, 0f), listOf(MathItem.Glyphs(0f, 0f, text, s, italic, bold)))
        }

        /** A run split into tokens: words (italic letters unless plain, function names upright), numbers, operators. */
        fun tokens(r: MathNode.Run, s: Float): List<Atom> {
            val out = ArrayList<Atom>()
            val text = r.text.replace('-', '−')
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == ' ' -> { out.add(Atom(MathBox(0.25f * s, 0f, 0f, emptyList()), Cls.ORD)); i++ }
                    MathText.isMathLetter(c) && !r.plain -> {
                        var j = i + 1
                        while (j < text.length && MathText.isMathLetter(text[j])) j++
                        val word = text.substring(i, j)
                        if (word in MathText.FUNCTIONS) out.add(Atom(glyphs(word, s, false, r.bold), Cls.OP))
                        else word.forEach { ch -> out.add(Atom(glyphs(ch.toString(), s, true, r.bold), Cls.ORD)) }
                        i = j
                    }
                    c.isDigit() || (c == '.' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                        var j = i + 1
                        while (j < text.length && (text[j].isDigit() || (text[j] == '.' && j + 1 < text.length && text[j + 1].isDigit()))) j++
                        out.add(Atom(glyphs(text.substring(i, j), s, false, r.bold), Cls.ORD)); i = j
                    }
                    r.plain && (c.isLetterOrDigit() || c == ' ') -> {
                        // Plain text (a word, normal text in a formula) stays one piece; a function's name spaces like one.
                        var j = i + 1
                        while (j < text.length && (text[j].isLetterOrDigit() || text[j] == ' ')) j++
                        val word = text.substring(i, j)
                        out.add(Atom(glyphs(word, s, false, r.bold), if (word in MathText.FUNCTIONS) Cls.OP else Cls.ORD)); i = j
                    }
                    else -> {
                        val cls = when (c) {
                            in RELATIONS -> Cls.REL; in BINARIES -> Cls.BIN; in OPENS -> Cls.OPEN; in CLOSES -> Cls.CLOSE
                            in PUNCT -> Cls.PUNCT; else -> Cls.ORD
                        }
                        // A big operator typed as text (∑ in a run) is set larger in display.
                        out.add(Atom(glyphs(c.toString(), s, false, r.bold), cls)); i++
                    }
                }
            }
            return out
        }

        /** Atoms side by side with TeX's spacing: thick round relations, medium round binary operators (not in scripts), thin after punctuation. */
        fun row(atoms: List<Atom>, style: Style): MathBox {
            if (atoms.isEmpty()) return MathBox(0f, 0f, 0f, emptyList())
            val s = style.scale(base)
            val tight = style == Style.SCRIPT || style == Style.SCRIPT2
            // A binary operator with nothing to its left (or after another operator, a relation, an opening) is unary.
            var prev: Cls? = null
            for (a in atoms) {
                if (a.cls == Cls.BIN && (prev == null || prev == Cls.BIN || prev == Cls.REL || prev == Cls.OPEN || prev == Cls.PUNCT || prev == Cls.OP)) a.cls = Cls.ORD
                prev = a.cls
            }
            atoms.lastOrNull()?.let { if (it.cls == Cls.BIN) it.cls = Cls.ORD }
            val items = ArrayList<MathItem>()
            var x = 0f; var asc = 0f; var desc = 0f
            var last: Cls? = null
            for (a in atoms) {
                last?.let { l -> x += space(l, a.cls, tight) * s }
                items.addAll(a.box.items(x, 0f))
                x += a.box.width
                asc = maxOf(asc, a.box.ascent); desc = maxOf(desc, a.box.descent)
                last = a.cls
            }
            return MathBox(x, asc, desc, items)
        }

        /** Space between two atoms, in ems (thin 3/18, medium 4/18, thick 5/18). */
        private fun space(a: Cls, b: Cls, tight: Boolean): Float {
            val thin = 3f / 18f; val med = 4f / 18f; val thick = 5f / 18f
            return when {
                a == Cls.PUNCT -> if (tight) 0f else thin
                a == Cls.REL || b == Cls.REL -> if (a == Cls.REL && b == Cls.REL || tight || a == Cls.OPEN || b == Cls.CLOSE) 0f else thick
                a == Cls.BIN || b == Cls.BIN -> if (tight) 0f else med
                a == Cls.OP && (b == Cls.ORD || b == Cls.OP || b == Cls.INNER) -> thin
                b == Cls.OP && (a == Cls.ORD || a == Cls.CLOSE || a == Cls.INNER) -> thin
                a == Cls.INNER && b != Cls.CLOSE && b != Cls.PUNCT -> if (tight) 0f else thin
                b == Cls.INNER && a != Cls.OPEN -> if (tight) 0f else thin
                else -> 0f
            }
        }

        fun frac(n: MathNode.Frac, style: Style): MathBox {
            val s = style.scale(base)
            if (n.kind == FracKind.LINEAR) return box(MathNode.Row(listOf(n.num, MathNode.Run("/", plain = true), n.den)), style)
            if (n.kind == FracKind.SKEWED) {
                val small = style.smaller()
                val num = box(n.num, small); val den = box(n.den, small)
                val slash = glyphs("⁄", s * 1.1f, false, false)
                val up = 0.45f * s
                val items = ArrayList<MathItem>()
                items.addAll(num.items(0f, -up))
                items.addAll(slash.items(num.width, 0f))
                items.addAll(den.items(num.width + slash.width, 0.1f * s))
                return MathBox(num.width + slash.width + den.width, maxOf(up + num.ascent, slash.ascent), maxOf(den.descent - 0.1f * s, slash.descent, 0f), items)
            }
            val child = style.fracChild()
            val num = box(n.num, child); val den = box(n.den, child)
            val t = rule(s)
            val gap = if (style == Style.DISPLAY) 2.4f * t else 1.4f * t
            val pad = 0.12f * s
            val w = maxOf(num.width, den.width) + 2 * pad
            val barTop = -axis(s) - t / 2
            val numBase = barTop - gap - maxOf(num.descent, 0.05f * s)
            val denBase = barTop + t + gap + maxOf(den.ascent, 0.45f * s)
            val items = ArrayList<MathItem>()
            items.addAll(num.items((w - num.width) / 2, numBase))
            items.addAll(den.items((w - den.width) / 2, denBase))
            if (n.kind == FracKind.BAR) items.add(MathItem.Rule(0.04f * s, barTop, w - 0.08f * s, t))
            return MathBox(w, -numBase + num.ascent, denBase + den.descent, items)
        }

        fun scripts(n: MathNode.Scripts, style: Style): MathBox {
            val s = style.scale(base)
            val baseBox = box(n.base, style)
            val small = style.smaller()
            val sup = n.sup?.let { box(it, small) }
            val sub = n.sub?.let { box(it, small) }
            val ss = small.scale(base)
            var up = maxOf(baseBox.ascent - 0.28f * s, if (style == Style.DISPLAY) 0.42f * s else 0.38f * s, (sup?.descent ?: 0f) + 0.12f * s)
            var down = maxOf(baseBox.descent + 0.05f * s, 0.18f * s, (sub?.ascent ?: 0f) - 0.4f * s)
            if (sup != null && sub != null) {
                // Keep the two scripts apart.
                val gap = (up - sup.descent) - (sub.ascent - down)
                val want = 4 * rule(s)
                if (gap < want) { val more = want - gap; down += more * 0.6f; up += more * 0.4f }
            }
            val italicLean = if (n.base is MathNode.Run && n.base.text.lastOrNull()?.let { MathText.isMathLetter(it) } == true && !n.base.plain) 0.06f * s else 0f
            val scriptW = maxOf((sup?.width ?: 0f) + italicLean, sub?.width ?: 0f)
            val items = ArrayList<MathItem>()
            val baseX = if (n.pre) scriptW + 0.05f * ss else 0f
            items.addAll(baseBox.items(baseX, 0f))
            val scriptX = if (n.pre) 0f else baseBox.width
            sup?.let { items.addAll(it.items(if (n.pre) scriptW - it.width else scriptX + italicLean, -up)) }
            sub?.let { items.addAll(it.items(if (n.pre) scriptW - it.width else scriptX, down)) }
            val w = baseBox.width + scriptW + 0.05f * ss
            val asc = maxOf(baseBox.ascent, sup?.let { up + it.ascent } ?: 0f)
            val desc = maxOf(baseBox.descent, sub?.let { down + it.descent } ?: 0f)
            return MathBox(w, asc, desc, items)
        }

        fun radical(n: MathNode.Radical, style: Style): MathBox {
            val s = style.scale(base)
            val body = box(n.body, style)
            val t = rule(s)
            val gap = if (style == Style.DISPLAY) 0.16f * s else 0.1f * s
            val bodyAsc = maxOf(body.ascent, 0.5f * s)
            val bodyDesc = maxOf(body.descent, 0.05f * s)
            val top = -(bodyAsc + gap + t / 2)
            val bottom = bodyDesc + 0.04f * s
            val h = bottom - top
            val sw = minOf(0.5f * s + 0.08f * h, 1.1f * s)
            val deg = n.degree?.let { box(it, Style.SCRIPT2) }
            val shift = deg?.let { maxOf(0f, it.width - 0.55f * sw) } ?: 0f
            val yA = top + h * 0.58f
            val items = ArrayList<MathItem>()
            val x0 = shift
            items.add(MathItem.Stroke(listOf(
                PathOp.Move(x0, yA + 0.06f * s),
                PathOp.Line(x0 + 0.2f * sw, yA - 0.02f * s),
                PathOp.Line(x0 + 0.5f * sw, bottom),
                PathOp.Line(x0 + sw, top),
                PathOp.Line(x0 + sw + body.width + 0.14f * s, top),
            ), t * 1.05f))
            items.addAll(body.items(x0 + sw + 0.07f * s, 0f))
            var asc = -top + t
            deg?.let {
                val degBase = yA - 0.08f * s - it.descent
                items.addAll(it.items(x0 + 0.55f * sw - it.width, degBase))
                asc = maxOf(asc, -degBase + it.ascent)
            }
            return MathBox(x0 + sw + body.width + 0.2f * s, asc, bottom + t / 2, items)
        }

        fun nary(n: MathNode.Nary, style: Style): List<Atom> {
            val s = style.scale(base)
            val integral = n.op in "∫∬∭∮∯∰"
            val display = style == Style.DISPLAY
            val opSize = s * when { display && integral -> 1.9f; display -> 1.45f; integral -> 1.3f; else -> 1.1f }
            val op = glyphs(n.op, opSize, false, false)
            // The operator sits centred on the math axis.
            val opBase = -axis(s) + (op.ascent - op.descent) / 2
            val opTop = opBase - op.ascent
            val opBottom = opBase + op.descent
            val small = style.smaller()
            val sup = n.sup?.let { box(it, small) }
            val sub = n.sub?.let { box(it, small) }
            val items = ArrayList<MathItem>()
            val w: Float
            var asc = -opTop
            var desc = opBottom
            if (n.under && display) {
                w = maxOf(op.width, sup?.width ?: 0f, sub?.width ?: 0f)
                items.addAll(op.items((w - op.width) / 2, opBase))
                val gap = 0.12f * s
                sup?.let { val b = opTop - gap - it.descent; items.addAll(it.items((w - it.width) / 2, b)); asc = -b + it.ascent }
                sub?.let { val b = opBottom + gap + it.ascent; items.addAll(it.items((w - it.width) / 2, b)); desc = b + it.descent }
            } else {
                items.addAll(op.items(0f, opBase))
                val lean = if (integral) 0.18f * opSize else 0.04f * s
                sup?.let { val b = opTop + 0.8f * it.ascent; items.addAll(it.items(op.width + lean * 0.4f, b)); asc = maxOf(asc, -b + it.ascent) }
                sub?.let { val b = opBottom - 0.1f * s; items.addAll(it.items(op.width - lean * 0.5f, b)); desc = maxOf(desc, b + it.descent) }
                w = op.width + maxOf(sup?.width?.plus(lean * 0.4f) ?: 0f, sub?.width?.minus(lean * 0.5f) ?: 0f, 0f) + 0.05f * s
            }
            val opAtom = Atom(MathBox(w, asc, desc, items), Cls.OP)
            return listOf(opAtom) + atoms(n.body, style)
        }

        fun delimited(n: MathNode.Delimited, style: Style): MathBox {
            val s = style.scale(base)
            val inner = n.items.map { box(it, style) }
            val innerAsc = inner.maxOfOrNull { it.ascent } ?: 0.5f * s
            val innerDesc = inner.maxOfOrNull { it.descent } ?: 0f
            val a = axis(s)
            // Stretched symmetrically round the axis to cover the content.
            val half = maxOf(innerAsc - a, innerDesc + a, 0.5f * s) + 0.08f * s
            val top = -a - half
            val bottom = -a + half
            val plain = innerAsc <= 0.8f * s && innerDesc <= 0.32f * s
            val items = ArrayList<MathItem>()
            var x = 0f
            var asc = innerAsc; var desc = innerDesc
            fun fence(c: String) {
                if (c.isEmpty()) return
                val b = if (plain) glyphs(c, s, false, false) else stretched(c, top, bottom, s)
                items.addAll(b.items(x, 0f)); x += b.width
                asc = maxOf(asc, b.ascent); desc = maxOf(desc, b.descent)
            }
            fence(n.open)
            val pad = 0.06f * s
            inner.forEachIndexed { k, box ->
                if (k > 0) { x += pad; fence(n.separator); x += pad }
                items.addAll(box.items(x + pad, 0f)); x += box.width + 2 * pad
            }
            fence(n.close)
            return MathBox(x, asc, desc, items)
        }

        /** A bracket drawn to reach from [top] to [bottom]. */
        fun stretched(c: String, top: Float, bottom: Float, s: Float): MathBox {
            val h = bottom - top
            val mid = (top + bottom) / 2
            val w = minOf(0.32f * s + 0.03f * h, 0.7f * s)
            val t = rule(s) * 1.2f
            val l = 0.18f * w; val r = 0.82f * w
            val path: List<PathOp> = when (c) {
                "(" -> listOf(PathOp.Move(r, top), PathOp.Cubic(l - 0.1f * w, top + h * 0.22f, l - 0.1f * w, bottom - h * 0.22f, r, bottom))
                ")" -> listOf(PathOp.Move(l, top), PathOp.Cubic(r + 0.1f * w, top + h * 0.22f, r + 0.1f * w, bottom - h * 0.22f, l, bottom))
                "[" -> listOf(PathOp.Move(r, top), PathOp.Line(l, top), PathOp.Line(l, bottom), PathOp.Line(r, bottom))
                "]" -> listOf(PathOp.Move(l, top), PathOp.Line(r, top), PathOp.Line(r, bottom), PathOp.Line(l, bottom))
                "⌊" -> listOf(PathOp.Move(l, top), PathOp.Line(l, bottom), PathOp.Line(r, bottom))
                "⌋" -> listOf(PathOp.Move(r, top), PathOp.Line(r, bottom), PathOp.Line(l, bottom))
                "⌈" -> listOf(PathOp.Move(r, top), PathOp.Line(l, top), PathOp.Line(l, bottom))
                "⌉" -> listOf(PathOp.Move(l, top), PathOp.Line(r, top), PathOp.Line(r, bottom))
                "⟨", "〈" -> listOf(PathOp.Move(r, top), PathOp.Line(l, mid), PathOp.Line(r, bottom))
                "⟩", "〉" -> listOf(PathOp.Move(l, top), PathOp.Line(r, mid), PathOp.Line(l, bottom))
                "|" -> listOf(PathOp.Move(w / 2, top), PathOp.Line(w / 2, bottom))
                "‖" -> listOf(PathOp.Move(w * 0.35f, top), PathOp.Line(w * 0.35f, bottom), PathOp.Move(w * 0.65f, top), PathOp.Line(w * 0.65f, bottom))
                "{" -> listOf(
                    PathOp.Move(r, top), PathOp.Cubic(w * 0.35f, top, w * 0.55f, mid, l, mid),
                    PathOp.Cubic(w * 0.55f, mid, w * 0.35f, bottom, r, bottom))
                "}" -> listOf(
                    PathOp.Move(l, top), PathOp.Cubic(w * 0.65f, top, w * 0.45f, mid, r, mid),
                    PathOp.Cubic(w * 0.45f, mid, w * 0.65f, bottom, l, bottom))
                else -> return glyphs(c, s, false, false)
            }
            return MathBox(w, -top, bottom, listOf(MathItem.Stroke(path, t)))
        }

        fun matrix(n: MathNode.Matrix, style: Style): MathBox {
            val s = style.scale(base)
            val cellStyle = if (style == Style.DISPLAY) Style.TEXT else style
            val cells = n.rows.map { r -> r.map { box(it, cellStyle) } }
            val cols = cells.maxOfOrNull { it.size } ?: 0
            if (cols == 0) return MathBox.EMPTY
            val colW = FloatArray(cols) { c -> cells.maxOf { r -> r.getOrNull(c)?.width ?: 0f } }
            val colGap = 0.9f * s; val rowGap = 0.35f * s
            val rowAsc = cells.map { r -> maxOf(r.maxOfOrNull { it.ascent } ?: 0f, 0.7f * s) }
            val rowDesc = cells.map { r -> maxOf(r.maxOfOrNull { it.descent } ?: 0f, 0.25f * s) }
            val total = rowAsc.sum() + rowDesc.sum() + rowGap * (cells.size - 1)
            // Centred on the math axis.
            val top = -axis(s) - total / 2
            val items = ArrayList<MathItem>()
            var y = top
            cells.forEachIndexed { ri, r ->
                val baseline = y + rowAsc[ri]
                var x = 0f
                for (c in 0 until cols) {
                    r.getOrNull(c)?.let { items.addAll(it.items(x + (colW[c] - it.width) / 2, baseline)) }
                    x += colW[c] + colGap
                }
                y = baseline + rowDesc[ri] + rowGap
            }
            val w = colW.sum() + colGap * (cols - 1)
            return MathBox(w, -top, top + total, items)
        }

        fun func(n: MathNode.Func, style: Style): MathBox {
            val s = style.scale(base)
            val name = box(n.name, style)
            val arg = box(n.arg, style)
            val gap = if (n.arg is MathNode.Delimited) 0.05f * s else 0.17f * s
            val items = ArrayList<MathItem>()
            items.addAll(name.items)
            items.addAll(arg.items(name.width + gap, 0f))
            return MathBox(name.width + gap + arg.width, maxOf(name.ascent, arg.ascent), maxOf(name.descent, arg.descent), items)
        }

        fun accent(n: MathNode.Accent, style: Style): MathBox {
            val s = style.scale(base)
            val body = box(n.body, style)
            val bodyTop = maxOf(body.ascent, 0.46f * s)
            val gap = 0.08f * s
            val items = ArrayList<MathItem>(body.items)
            val t = rule(s)
            var asc = body.ascent
            val lean = if (n.body is MathNode.Run && !n.body.plain) 0.08f * s else 0f
            when (n.mark) {
                "̅", "̄", "¯" -> { val y = -(bodyTop + gap + t); items.add(MathItem.Rule(0.05f * s + lean, y, body.width - 0.1f * s, t)); asc = -y }
                "⃗", "→" -> {
                    val y = -(bodyTop + gap + 0.12f * s)
                    val x1 = 0.05f * s + lean; val x2 = maxOf(body.width, 0.45f * s) + lean
                    items.add(MathItem.Stroke(listOf(PathOp.Move(x1, y), PathOp.Line(x2, y),
                        PathOp.Move(x2 - 0.14f * s, y - 0.09f * s), PathOp.Line(x2, y), PathOp.Line(x2 - 0.14f * s, y + 0.09f * s)), t))
                    asc = -y + 0.12f * s
                }
                else -> {
                    // The mark's ink (a circumflex sits high in its glyph box) rests just above the body.
                    val mark = spacingMark(n.mark)
                    val g = glyphs(mark, s, false, false)
                    val (inkTop, inkBottom) = font.ink(mark, s, false, false)
                    val y = -(bodyTop + gap) - inkBottom
                    items.addAll(g.items((body.width - g.width) / 2 + lean, y))
                    asc = -y + inkTop
                }
            }
            return MathBox(body.width, maxOf(asc, body.ascent), body.descent, items)
        }

        /** The spacing form of a combining accent, to draw on its own. */
        private fun spacingMark(m: String): String = when (m) {
            "̂" -> "ˆ"; "̃" -> "˜"; "̇" -> "˙"; "̈" -> "¨"; "́" -> "´"; "̀" -> "`"
            "̆" -> "˘"; "̌" -> "ˇ"; "̊" -> "˚"; "⃛" -> "⋯"
            else -> m
        }

        fun bar(n: MathNode.Bar, style: Style): MathBox {
            val s = style.scale(base)
            val body = box(n.body, style)
            val t = rule(s)
            val items = ArrayList<MathItem>(body.items)
            return if (n.over) {
                val y = -(maxOf(body.ascent, 0.46f * s) + 0.12f * s + t)
                items.add(MathItem.Rule(0f, y, body.width, t))
                MathBox(body.width, -y, body.descent, items)
            } else {
                val y = maxOf(body.descent, 0.05f * s) + 0.1f * s
                items.add(MathItem.Rule(0f, y, body.width, t))
                MathBox(body.width, body.ascent, y + t, items)
            }
        }

        fun limit(n: MathNode.Limit, style: Style): MathBox {
            val s = style.scale(base)
            val b = box(n.base, style)
            val l = box(n.limit, style.smaller())
            val w = maxOf(b.width, l.width)
            val items = ArrayList<MathItem>()
            items.addAll(b.items((w - b.width) / 2, 0f))
            val gap = 0.1f * s
            return if (n.under) {
                val y = b.descent + gap + l.ascent
                items.addAll(l.items((w - l.width) / 2, y))
                MathBox(w, b.ascent, y + l.descent, items)
            } else {
                val y = -(b.ascent + gap + l.descent)
                items.addAll(l.items((w - l.width) / 2, y))
                MathBox(w, -y + l.ascent, b.descent, items)
            }
        }

        fun groupChar(n: MathNode.GroupChar, style: Style): MathBox {
            val s = style.scale(base)
            val body = box(n.body, style)
            val t = rule(s)
            val w = body.width
            val depth = 0.28f * s
            val items = ArrayList<MathItem>(body.items)
            val brace = n.char == "⏟" || n.char == "⏞" || n.char == "︸" || n.char == "︷"
            if (!brace) {
                val g = glyphs(n.char, s, false, false)
                val y = if (n.under) body.descent + 0.1f * s + g.ascent else -(body.ascent + 0.1f * s + g.descent)
                items.addAll(g.items((w - g.width) / 2, y))
                return MathBox(maxOf(w, g.width), if (n.under) body.ascent else -y + g.ascent, if (n.under) y + g.descent else body.descent, items)
            }
            // A horizontal brace: ends curling towards the body, a point in the middle away from it.
            val y0 = if (n.under) body.descent + 0.1f * s else -(body.ascent + 0.1f * s)
            val dir = if (n.under) 1f else -1f
            val y1 = y0 + dir * depth
            val m = w / 2
            items.add(MathItem.Stroke(listOf(
                PathOp.Move(0f, y0), PathOp.Cubic(0f, y0 + dir * depth * 0.6f, m, y0 + dir * depth * 0.4f, m, y1),
                PathOp.Move(w, y0), PathOp.Cubic(w, y0 + dir * depth * 0.6f, m, y0 + dir * depth * 0.4f, m, y1),
            ), t))
            return MathBox(w, if (n.under) body.ascent else -y1 + t, if (n.under) y1 + t else body.descent, items)
        }

        fun stack(n: MathNode.Stack, style: Style): MathBox {
            val s = style.scale(base)
            val rows = n.rows.map { box(it, style) }
            if (rows.isEmpty()) return MathBox.EMPTY
            val gap = 0.35f * s
            val w = rows.maxOf { it.width }
            val total = rows.sumOf { (it.height).toDouble() }.toFloat() + gap * (rows.size - 1)
            val top = -axis(s) - total / 2
            val items = ArrayList<MathItem>()
            var y = top
            for (r in rows) {
                val baseline = y + r.ascent
                items.addAll(r.items((w - r.width) / 2, baseline))
                y = baseline + r.descent + gap
            }
            return MathBox(w, -top, top + total, items)
        }

        fun boxed(n: MathNode.Boxed, style: Style): MathBox {
            val body = box(n.body, style)
            if (!n.border) return body
            val s = style.scale(base)
            val pad = 0.18f * s
            val t = rule(s)
            val top = -(body.ascent + pad); val bottom = body.descent + pad
            val w = body.width + 2 * pad
            val items = ArrayList<MathItem>(body.items(pad, 0f))
            items.add(MathItem.Stroke(listOf(PathOp.Move(0f, top), PathOp.Line(w, top), PathOp.Line(w, bottom), PathOp.Line(0f, bottom), PathOp.Line(0f, top)), t))
            return MathBox(w, -top + t / 2, bottom + t / 2, items)
        }
    }
}

/** Width and ink from character classes — for tests, and wherever no real font is at hand. */
object EstimateMathFont : MathFont {
    override fun width(text: String, size: Float, italic: Boolean, bold: Boolean): Float = text.sumOf { c ->
        (when {
            c in "iljtfr|.,:;'!" -> 0.3f
            c in "mwMW" -> 0.8f
            c.isUpperCase() -> 0.65f
            c.isLowerCase() -> 0.5f
            c.isDigit() -> 0.5f
            c in "∑∏∫∮∐⋃⋂" -> 0.8f
            c in "()[]{}" -> 0.35f
            c.code >= 0x3000 -> 1f
            else -> 0.6f
        } * size).toDouble()
    }.toFloat()

    override fun ink(text: String, size: Float, italic: Boolean, bold: Boolean): Pair<Float, Float> {
        var a = 0f; var d = 0f
        for (c in text) {
            val up = when {
                c.isUpperCase() || c.isDigit() || c in "bdfhklt()[]{}|/∑∏∫√∮" -> 0.72f
                c.isLowerCase() || c in "αβγδεζηθικλμνξοπρστυφχψω" -> 0.46f
                c in "=+−-±×÷<>≤≥≠≈" -> 0.55f
                else -> 0.6f
            }
            val down = when {
                c in "gjpqyβγζημξρφχψ(),;[]{}|/" -> 0.22f
                c in "∑∏∫∮" -> 0.25f
                else -> 0f
            }
            a = maxOf(a, up); d = maxOf(d, down)
        }
        return a * size to d * size
    }
}
