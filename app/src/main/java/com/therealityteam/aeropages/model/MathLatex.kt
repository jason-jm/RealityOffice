package com.therealityteam.aeropages.model

/**
 * LaTeX's math notation to and from [MathNode]s — how an equation is typed and edited:
 * `\frac{a}{b}`, `x^2`, `x_{i}`, `\sqrt[3]{x}`, `\sum_{i=1}^{n}`, `\int_0^1`, `\lim_{x \to 0}`,
 * `\left( … \right)`, `\begin{pmatrix} a & b \\ c & d \end{pmatrix}`, `\begin{cases} … \end{cases}`,
 * `\hat{x}`, `\vec{v}`, `\overline{z}`, `\text{if}`, Greek letters and the usual symbols. Reading
 * is forgiving: an unknown command reads as its name, a brace left open closes at the end.
 */
object MathLatex {

    // ---- symbols ----

    private val GREEK = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ϵ", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
        "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ", "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "ϕ",
        "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
        "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    )
    private val SYMBOLS = mapOf(
        "pm" to "±", "mp" to "∓", "times" to "×", "div" to "÷", "cdot" to "·", "ast" to "∗", "star" to "⋆", "circ" to "∘", "bullet" to "∙",
        "cap" to "∩", "cup" to "∪", "wedge" to "∧", "land" to "∧", "vee" to "∨", "lor" to "∨", "oplus" to "⊕", "otimes" to "⊗", "setminus" to "∖",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠", "approx" to "≈", "equiv" to "≡", "sim" to "∼",
        "simeq" to "≃", "cong" to "≅", "propto" to "∝", "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂", "supset" to "⊃",
        "subseteq" to "⊆", "supseteq" to "⊇", "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "gets" to "←", "leftrightarrow" to "↔",
        "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔", "implies" to "⇒", "iff" to "⇔", "mapsto" to "↦",
        "ll" to "≪", "gg" to "≫", "parallel" to "∥", "perp" to "⊥", "mid" to "∣", "neg" to "¬", "lnot" to "¬",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇", "forall" to "∀", "exists" to "∃", "emptyset" to "∅", "varnothing" to "∅",
        "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ", "aleph" to "ℵ", "angle" to "∠", "triangle" to "△", "degree" to "°",
        "prime" to "′", "ldots" to "…", "dots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
        "langle" to "⟨", "rangle" to "⟩", "lfloor" to "⌊", "rfloor" to "⌋", "lceil" to "⌈", "rceil" to "⌉",
        "lbrace" to "{", "rbrace" to "}", "vert" to "|", "Vert" to "‖", "|" to "‖", "{" to "{", "}" to "}", "%" to "%", "#" to "#", "&" to "&", "$" to "$", "_" to "_",
    )
    private val SPACES = mapOf("," to " ", ";" to " ", ":" to " ", "!" to "", " " to " ", "quad" to "  ", "qquad" to "    ")
    private val BIG_OPS = mapOf(
        "sum" to "∑", "prod" to "∏", "coprod" to "∐", "int" to "∫", "iint" to "∬", "iiint" to "∭", "oint" to "∮",
        "bigcup" to "⋃", "bigcap" to "⋂", "bigoplus" to "⨁", "bigotimes" to "⨂", "bigvee" to "⋁", "bigwedge" to "⋀",
    )
    private val BIG_OP_CHARS = BIG_OPS.values.toSet()
    private val INTEGRALS = setOf("∫", "∬", "∭", "∮")
    private val ACCENTS = mapOf(
        "hat" to "̂", "widehat" to "̂", "tilde" to "̃", "widetilde" to "̃", "bar" to "̄", "vec" to "⃗",
        "overrightarrow" to "⃗", "dot" to "̇", "ddot" to "̈", "dddot" to "⃛", "acute" to "́", "grave" to "̀",
        "breve" to "̆", "check" to "̌", "mathring" to "̊",
    )
    private val LIMIT_WORDS = setOf("lim", "liminf", "limsup", "max", "min", "sup", "inf", "det", "gcd", "Pr", "argmax", "argmin")
    private val RELATIONS = "=<>≤≥≠≈≡∼≃≅∝∈∉∋⊂⊃⊆⊇→←↔⇒⇐⇔↦≪≫∥⊥∣"

    // ---- reading ----

    /** [latex] as a formula (never fails: what cannot be read is kept as text). */
    fun parse(latex: String): MathNode = Parser(latex.trim().removePrefix("$$").removeSuffix("$$").removePrefix("$").removeSuffix("$")).parse()

    private class Parser(val s: String) {
        var i = 0

        fun parse(): MathNode = MathNode.row(expr(stopAt = ""))

        private fun peek(): Char? = s.getOrNull(i)
        private fun skipSpaces() { while (i < s.length && s[i].isWhitespace()) i++ }

        /** A command's name after its backslash: letters, or the one character that follows. */
        private fun command(): String {
            val start = i
            if (i < s.length && s[i].isLetter()) { while (i < s.length && s[i].isLetter()) i++ } else if (i < s.length) i++
            return s.substring(start, i)
        }

        /** Look at the next command without taking it. */
        private fun nextCommand(): String? {
            skipSpaces()
            if (peek() != '\\') return null
            val save = i
            i++
            val name = command()
            i = save
            return name
        }

        /**
         * Items up to the end of the group: a closing brace, `&` or `\\` (cells and rows), `\right`,
         * `\middle`, `\end`, or [stopAt] (a closing bracket for `\sqrt[…]`).
         */
        fun expr(stopAt: String): List<MathNode> {
            val out = ArrayList<MathNode>()
            while (true) {
                skipSpaces()
                val c = peek() ?: break
                if (c == '}' || c == '&' || stopAt.isNotEmpty() && s.startsWith(stopAt, i)) break
                if (c == '\\') {
                    val name = nextCommand()
                    if (name == "\\" || name == "right" || name == "middle" || name == "end" || name == "cr") break
                    if (name == "atop") {
                        i++; command()
                        val below = MathNode.row(expr(stopAt))
                        return mutableListOf(MathNode.Frac(MathNode.row(out), below, FracKind.NO_BAR))
                    }
                }
                val item = scripted() ?: continue
                // A big operator takes what follows it, up to a relation, as its operand.
                if (item is MathNode.Nary) {
                    val body = ArrayList<MathNode>()
                    while (true) {
                        skipSpaces()
                        val d = peek() ?: break
                        if (d == '}' || d == '&' || d in RELATIONS || stopAt.isNotEmpty() && s.startsWith(stopAt, i)) break
                        if (d == '\\') {
                            val n = nextCommand()
                            if (n == "\\" || n == "right" || n == "middle" || n == "end" || n == "atop" || n == "cr" || SYMBOLS[n]?.let { it in RELATIONS } == true) break
                        }
                        body.add(scripted() ?: continue)
                    }
                    out.add(item.copy(body = MathNode.row(body)))
                } else out.add(item)
            }
            return out
        }

        /** An atom with any scripts after it (`x_i^2`, `f'`, `\sum_{k}^{n}`). */
        fun scripted(): MathNode? {
            val base = atom() ?: return null
            var sub: MathNode? = null
            var sup: MathNode? = null
            var limits: Boolean? = null
            while (true) {
                skipSpaces()
                when (peek()) {
                    '_' -> { i++; sub = arg() }
                    '^' -> { i++; sup = arg() }
                    '\'' -> { i++; sup = MathNode.row(listOfNotNull(sup, MathNode.Run("′", plain = true))) }
                    '\\' -> when (nextCommand()) {
                        "limits" -> { i++; command(); limits = true }
                        "nolimits" -> { i++; command(); limits = false }
                        else -> break
                    }
                    else -> break
                }
            }
            if (base is MathNode.Nary) return base.copy(sub = sub ?: base.sub, sup = sup ?: base.sup, under = limits ?: base.under)
            if (sub == null && sup == null) return base
            // lim, max, … take what is under them as a limit.
            if (base is MathNode.Run && base.plain && base.text in LIMIT_WORDS && sub != null && sup == null) return MathNode.Limit(base, sub, under = true)
            if (base is MathNode.GroupChar) {
                val label = if (base.under) sub else sup
                if (label != null) return MathNode.Limit(base, label, under = base.under)
            }
            return MathNode.Scripts(base, sub, sup)
        }

        /** An argument: a braced group, or one token. */
        fun arg(): MathNode {
            skipSpaces()
            return when (peek()) {
                '{' -> group()
                null -> MathNode.Row(emptyList())
                else -> atom() ?: MathNode.Row(emptyList())
            }
        }

        fun group(): MathNode {
            i++  // {
            val items = expr(stopAt = "")
            if (peek() == '}') i++
            return MathNode.row(items).let { if (it is MathNode.Row && it.items.isEmpty()) it else it }
        }

        /** Text inside `\text{…}`: kept as written. */
        fun textArg(): String {
            skipSpaces()
            if (peek() != '{') return ""
            i++
            val sb = StringBuilder()
            var depth = 1
            while (i < s.length) {
                val c = s[i++]
                if (c == '{') depth++ else if (c == '}') { depth--; if (depth == 0) break }
                sb.append(c)
            }
            return sb.toString()
        }

        /** A delimiter after `\left` / `\middle` / `\right`: a character or a command; `.` is none. */
        fun delimiter(): String {
            skipSpaces()
            val c = peek() ?: return ""
            if (c == '\\') {
                i++
                val name = command()
                return when (name) { "{", "lbrace" -> "{"; "}", "rbrace" -> "}"; "|", "Vert" -> "‖"; "vert" -> "|"; else -> SYMBOLS[name] ?: "" }
            }
            i++
            return if (c == '.') "" else c.toString()
        }

        fun atom(): MathNode? {
            skipSpaces()
            val c = peek() ?: return null
            when {
                c == '{' -> return group()
                c == '\\' -> { i++; return commandAtom(command()) }
                c.isDigit() || c == '.' && s.getOrNull(i + 1)?.isDigit() == true -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.' && s.getOrNull(i + 1)?.isDigit() == true)) i++
                    return MathNode.Run(s.substring(start, i))
                }
                c.isLetter() -> { i++; return MathNode.Run(c.toString()) }
                c == '}' || c == '&' -> { i++; return null }
                c == '^' || c == '_' -> { i++; arg(); return null }   // a script with nothing to attach to
                c.toString() in BIG_OP_CHARS -> { i++; return MathNode.Nary(c.toString(), null, null, MathNode.Row(emptyList()), c.toString() !in INTEGRALS) }
                else -> { i++; return MathNode.Run(c.toString()) }
            }
        }

        fun commandAtom(name: String): MathNode? {
            GREEK[name]?.let { return MathNode.Run(it) }
            SYMBOLS[name]?.let { return MathNode.Run(it) }
            SPACES[name]?.let { return if (it.isEmpty()) null else MathNode.Run(it, plain = true) }
            BIG_OPS[name]?.let { return MathNode.Nary(it, null, null, MathNode.Row(emptyList()), it !in INTEGRALS) }
            ACCENTS[name]?.let { return MathNode.Accent(arg(), it) }
            if (name in MathText.FUNCTIONS || name in LIMIT_WORDS) {
                val word = MathNode.Run(name, plain = true)
                // A function takes its argument (`\sin x`); a limit word waits for its subscript.
                skipSpaces()
                if (name in LIMIT_WORDS || peek() == '_' || peek() == '^' || peek() == null || peek() == '}') return word
                return MathNode.Func(word, scripted() ?: return word)
            }
            return when (name) {
                "frac", "dfrac", "tfrac", "cfrac" -> MathNode.Frac(arg(), arg())
                "sfrac" -> MathNode.Frac(arg(), arg(), FracKind.SKEWED)
                "binom", "dbinom", "tbinom" -> MathNode.Delimited("(", ")", listOf(MathNode.Frac(arg(), arg(), FracKind.NO_BAR)))
                "sqrt" -> {
                    skipSpaces()
                    val degree = if (peek() == '[') { i++; val d = MathNode.row(expr(stopAt = "]")); if (peek() == ']') i++; d } else null
                    MathNode.Radical(arg(), degree)
                }
                "left" -> {
                    val open = delimiter()
                    val items = ArrayList<MathNode>()
                    var sep = "|"
                    var close = ""
                    while (true) {
                        items.add(MathNode.row(expr(stopAt = "")))
                        skipSpaces()
                        if (i >= s.length) break
                        when (nextCommand()) {
                            "middle" -> { i++; command(); sep = delimiter().ifEmpty { "|" } }
                            "right" -> { i++; command(); close = delimiter(); break }
                            else -> { i++; if (i >= s.length) break }
                        }
                    }
                    MathNode.Delimited(open, close, items, sep)
                }
                "begin" -> environment(textArg())
                "overline" -> MathNode.Bar(arg(), over = true)
                "underline" -> MathNode.Bar(arg(), over = false)
                "overbrace" -> MathNode.GroupChar(arg(), "⏞", under = false)
                "underbrace" -> MathNode.GroupChar(arg(), "⏟", under = true)
                "boxed", "fbox" -> MathNode.Boxed(arg(), border = true)
                "overset", "stackrel" -> { val top = arg(); MathNode.Limit(arg(), top, under = false) }
                "underset" -> { val bottom = arg(); MathNode.Limit(arg(), bottom, under = true) }
                "prescript" -> { val sup = arg(); val sub = arg(); MathNode.Scripts(arg(), sub.nonEmpty(), sup.nonEmpty(), pre = true) }
                "text", "textrm", "mathrm", "mbox", "operatorname", "textnormal", "mathup" -> MathNode.Run(textArg(), plain = true)
                "mathbf", "textbf", "boldsymbol", "bm" -> bold(arg())
                "mathit", "textit", "mathnormal" -> arg()
                "mathbb", "mathcal", "mathfrak", "mathsf", "mathtt" -> arg()
                "left.", "right." -> null
                "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr" -> null
                else -> MathNode.Run(name, plain = true)
            }
        }

        private fun MathNode.nonEmpty(): MathNode? = takeUnless { it is MathNode.Row && it.items.isEmpty() }

        private fun bold(n: MathNode): MathNode = when (n) {
            is MathNode.Run -> n.copy(bold = true)
            is MathNode.Row -> MathNode.Row(n.items.map { bold(it) })
            else -> n
        }

        /** `\begin{…} … \end{…}`: matrices (bracketed as their name says), cases, stacked lines. */
        fun environment(name: String): MathNode {
            val rows = ArrayList<List<MathNode>>()
            var cells = ArrayList<MathNode>()
            if (name == "array") textArg()   // the column spec
            while (i < s.length) {
                cells.add(MathNode.row(expr(stopAt = "")))
                skipSpaces()
                val c = peek() ?: break
                when {
                    c == '&' -> i++
                    c == '}' -> i++
                    c == '\\' -> when (nextCommand()) {
                        "\\", "cr" -> { i++; command(); rows.add(cells); cells = ArrayList() }
                        "end" -> { i++; command(); textArg(); break }
                        else -> { i++; command() }
                    }
                    else -> i++
                }
            }
            if (cells.any { !(it is MathNode.Row && it.items.isEmpty()) } || rows.isEmpty()) rows.add(cells)
            val oneColumn = rows.all { it.size == 1 }
            val grid: MathNode = if (oneColumn && name in setOf("gathered", "gather", "aligned", "align", "split", "cases")) MathNode.Stack(rows.map { it[0] })
                else MathNode.Matrix(rows)
            return when (name) {
                "pmatrix" -> MathNode.Delimited("(", ")", listOf(grid))
                "bmatrix" -> MathNode.Delimited("[", "]", listOf(grid))
                "Bmatrix" -> MathNode.Delimited("{", "}", listOf(grid))
                "vmatrix" -> MathNode.Delimited("|", "|", listOf(grid))
                "Vmatrix" -> MathNode.Delimited("‖", "‖", listOf(grid))
                "cases" -> MathNode.Delimited("{", "", listOf(grid))
                else -> grid
            }
        }
    }

    // ---- writing ----

    private val FROM_SYMBOL: Map<String, String> = (SYMBOLS.filterKeys { it.all { c -> c.isLetter() } && it !in setOf("le", "ge", "ne", "gets", "land", "lor", "lnot", "implies", "iff", "vert", "Vert", "lbrace", "rbrace", "dots", "varnothing") }
        .entries.associate { (k, v) -> v to k }) + GREEK.entries.associate { (k, v) -> v to k }
    private val FROM_ACCENT = mapOf("̂" to "hat", "̃" to "tilde", "̄" to "bar", "̅" to "overline", "⃗" to "vec", "̇" to "dot",
        "̈" to "ddot", "⃛" to "dddot", "́" to "acute", "̀" to "grave", "̆" to "breve", "̌" to "check", "̊" to "mathring")
    private val FROM_BIG_OP = BIG_OPS.entries.associate { (k, v) -> v to k }
    private val FENCES = mapOf("{" to "\\{", "}" to "\\}", "‖" to "\\|", "⟨" to "\\langle", "⟩" to "\\rangle", "⌊" to "\\lfloor", "⌋" to "\\rfloor",
        "⌈" to "\\lceil", "⌉" to "\\rceil", "" to ".")

    /** [n] in LaTeX. */
    fun write(n: MathNode): String = StringBuilder().apply { node(n) }.toString().trim().replace(Regex(" {2,}"), " ")

    private fun StringBuilder.braced(n: MathNode?) { append("{"); n?.let { node(it) }; append("}") }

    /** A command name, with a space after it so a letter that follows does not run into it. */
    private fun StringBuilder.cmd(name: String) { append("\\").append(name).append(" ") }

    private fun StringBuilder.node(n: MathNode) {
        when (n) {
            is MathNode.Run -> run(n)
            is MathNode.Row -> n.items.forEach { node(it) }
            is MathNode.Frac -> when (n.kind) {
                FracKind.SKEWED -> { append("\\sfrac"); braced(n.num); braced(n.den) }
                FracKind.NO_BAR -> { append("{"); node(n.num); append(" \\atop "); node(n.den); append("}") }
                FracKind.LINEAR -> { braced(n.num); append("/"); braced(n.den) }
                FracKind.BAR -> { append("\\frac"); braced(n.num); braced(n.den) }
            }
            is MathNode.Scripts -> {
                if (n.pre) { append("\\prescript"); braced(n.sup); braced(n.sub); braced(n.base) }
                else {
                    val simple = n.base is MathNode.Run && n.base.text.length == 1
                    if (simple) node(n.base) else braced(n.base)
                    n.sub?.let { append("_"); braced(it) }
                    n.sup?.let { append("^"); braced(it) }
                }
            }
            is MathNode.Radical -> {
                append("\\sqrt")
                n.degree?.let { append("["); node(it); append("]") }
                braced(n.body)
            }
            is MathNode.Nary -> {
                val name = FROM_BIG_OP[n.op]
                if (name != null) append("\\").append(name) else append(n.op)
                if (n.under != (n.op !in INTEGRALS)) append(if (n.under) "\\limits" else "\\nolimits")
                n.sub?.let { append("_"); braced(it) }
                n.sup?.let { append("^"); braced(it) }
                append(" ")
                node(n.body)
            }
            is MathNode.Delimited -> {
                val inner = n.items.singleOrNull()
                val env = when {
                    inner is MathNode.Matrix && n.open == "(" && n.close == ")" -> "pmatrix"
                    inner is MathNode.Matrix && n.open == "[" && n.close == "]" -> "bmatrix"
                    inner is MathNode.Matrix && n.open == "|" && n.close == "|" -> "vmatrix"
                    (inner is MathNode.Matrix || inner is MathNode.Stack) && n.open == "{" && n.close.isEmpty() -> "cases"
                    else -> null
                }
                if (env != null) { environment(env, inner!!); return }
                append("\\left").append(FENCES[n.open] ?: n.open).append(" ")
                n.items.forEachIndexed { k, it -> if (k > 0) append(" \\middle").append(FENCES[n.separator] ?: n.separator).append(" "); node(it) }
                append(" \\right").append(FENCES[n.close] ?: n.close).append(" ")
            }
            is MathNode.Matrix -> environment("matrix", n)
            is MathNode.Stack -> environment("gathered", n)
            is MathNode.Func -> {
                val name = n.name
                when {
                    name is MathNode.Run && name.plain && name.text in MathText.FUNCTIONS -> cmd(name.text)
                    name is MathNode.Run && name.plain && name.text.length > 1 -> { append("\\operatorname"); braced(name) }
                    else -> node(name)
                }
                node(n.arg)
            }
            is MathNode.Accent -> {
                val name = FROM_ACCENT[n.mark]
                if (name != null) { append("\\").append(name); braced(n.body) }
                else { append("\\overset"); braced(MathNode.Run(n.mark)); braced(n.body) }
            }
            is MathNode.Bar -> { append(if (n.over) "\\overline" else "\\underline"); braced(n.body) }
            is MathNode.Limit -> {
                val base = n.base
                when {
                    base is MathNode.Run && base.plain && base.text in LIMIT_WORDS && n.under -> { cmd(base.text); append("_"); braced(n.limit) }
                    base is MathNode.GroupChar && base.under == n.under -> { node(base); append(if (n.under) "_" else "^"); braced(n.limit) }
                    else -> { append(if (n.under) "\\underset" else "\\overset"); braced(n.limit); braced(base) }
                }
            }
            is MathNode.GroupChar -> when (n.char) {
                "⏟" -> { append("\\underbrace"); braced(n.body) }
                "⏞" -> { append("\\overbrace"); braced(n.body) }
                else -> { append(if (n.under) "\\underset" else "\\overset"); braced(MathNode.Run(n.char)); braced(n.body) }
            }
            is MathNode.Boxed -> if (n.border) { append("\\boxed"); braced(n.body) } else braced(n.body)
        }
    }

    private fun StringBuilder.environment(name: String, grid: MathNode) {
        append("\\begin{").append(name).append("} ")
        when (grid) {
            is MathNode.Matrix -> grid.rows.forEachIndexed { r, row ->
                if (r > 0) append(" \\\\ ")
                row.forEachIndexed { c, cell -> if (c > 0) append(" & "); node(cell) }
            }
            is MathNode.Stack -> grid.rows.forEachIndexed { r, row -> if (r > 0) append(" \\\\ "); node(row) }
            else -> node(grid)
        }
        append(" \\end{").append(name).append("} ")
    }

    private fun StringBuilder.run(r: MathNode.Run) {
        val t = r.text
        if (r.plain && t.any { it.isLetter() } && t !in MathText.FUNCTIONS && t !in LIMIT_WORDS) {
            append(if (r.bold) "\\mathbf{" else "\\text{").append(t.replace("{", "\\{").replace("}", "\\}")).append("}")
            return
        }
        if (r.plain && (t in MathText.FUNCTIONS || t in LIMIT_WORDS)) { cmd(t); return }
        if (r.bold) append("\\mathbf{")
        for (c in t) {
            val name = FROM_SYMBOL[c.toString()]
            when {
                name != null -> cmd(name)
                c == '{' || c == '}' || c == '%' || c == '#' || c == '&' || c == '_' || c == '$' -> append("\\").append(c)
                c == '−' -> append("-")
                c == ' ' -> append("\\ ")
                else -> append(c)
            }
        }
        if (r.bold) append("}")
    }
}
