package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.Paragraph
import java.util.Locale

/**
 * Word's AutoCorrect and AutoFormat As You Type: what a paragraph's text calls for just after a character was typed —
 * a word completed by a space or a punctuation mark capitalised at a sentence's start, a common slip put right, "(c)"
 * turned into ©, straight quotes into the language's curly ones, "--" into a dash, "1st" with its "st" raised, "1/2"
 * into ½, "* " or "1. " at a paragraph's start into a list, a typed web address into a link — and the replacements
 * of one's own (Word's "Replace … With …" entries). Excel's AutoCorrect in a cell is the same minus the formatting
 * ([Options.forCells]), and a cell's first word starts no sentence there.
 *
 * Pure: [after] reads the text and says what to change; the editor makes the change (its own undo step, so Ctrl+Z
 * right after puts back what was typed, as in Word).
 */
object AutoCorrect {

    /** What a correction was — for the AutoCorrect Options button's "Undo …" and "Stop …". */
    enum class Kind { CAPITALIZE, DAYS, TWO_INITIALS, REPLACE, QUOTES, DASH, ORDINAL, FRACTION, BULLETS, NUMBERING, HYPERLINK }

    /** Which corrections are made (Word's AutoCorrect dialog, all on by default). */
    data class Options(
        val capitalizeSentences: Boolean = true,
        val twoInitialCaps: Boolean = true,
        val replaceText: Boolean = true,
        val smartQuotes: Boolean = true,
        val dashes: Boolean = true,
        val ordinals: Boolean = true,
        val fractions: Boolean = true,
        val autoLists: Boolean = true,
        val hyperlinks: Boolean = true,
        val capitalizeDays: Boolean = true,
        /** Entries no longer corrected (the button's "Stop Automatically Correcting "teh""), in small letters. */
        val skip: Set<String> = emptySet(),
        /** One's own replacements, typed text → what it becomes (Word's "Replace … With …"), as entered. */
        val entries: Map<String, String> = emptyMap(),
    ) {
        /** Excel's AutoCorrect in a cell: its replacements and capitals, none of Word's formatting as you type. */
        fun forCells(): Options = copy(smartQuotes = false, dashes = false, ordinals = false, fractions = false, autoLists = false, hyperlinks = false)

        fun allows(kind: Kind): Boolean = when (kind) {
            Kind.CAPITALIZE -> capitalizeSentences
            Kind.DAYS -> capitalizeDays
            Kind.TWO_INITIALS -> twoInitialCaps
            Kind.REPLACE -> replaceText
            Kind.QUOTES -> smartQuotes
            Kind.DASH -> dashes
            Kind.ORDINAL -> ordinals
            Kind.FRACTION -> fractions
            Kind.BULLETS, Kind.NUMBERING -> autoLists
            Kind.HYPERLINK -> hyperlinks
        }

        /** These options with [kind]'s switched off (the button's "Stop …"). */
        fun without(kind: Kind): Options = when (kind) {
            Kind.CAPITALIZE -> copy(capitalizeSentences = false)
            Kind.DAYS -> copy(capitalizeDays = false)
            Kind.TWO_INITIALS -> copy(twoInitialCaps = false)
            Kind.REPLACE -> copy(replaceText = false)
            Kind.QUOTES -> copy(smartQuotes = false)
            Kind.DASH -> copy(dashes = false)
            Kind.ORDINAL -> copy(ordinals = false)
            Kind.FRACTION -> copy(fractions = false)
            Kind.BULLETS, Kind.NUMBERING -> copy(autoLists = false)
            Kind.HYPERLINK -> copy(hyperlinks = false)
        }
    }

    /** The options in use (the app loads the saved ones at start and saves them when changed). */
    @Volatile var options: Options = Options()

    sealed interface Fix { val kind: Kind }
    /** [start, end) of the paragraph's text becomes [text]. */
    data class Replace(val start: Int, val end: Int, val text: String, override val kind: Kind) : Fix
    /** [start, end) raised (an ordinal's "st"). */
    data class Raise(val start: Int, val end: Int) : Fix { override val kind get() = Kind.ORDINAL }
    /** [start, end) becomes a link to [url]. */
    data class Link(val start: Int, val end: Int, val url: String) : Fix { override val kind get() = Kind.HYPERLINK }
    /** The paragraph's first [markerEnd] characters (the "* " typed) go and the paragraph becomes a list item. */
    data class MakeList(val markerEnd: Int, val bullet: String?, val numbering: Numbering?) : Fix {
        override val kind get() = if (bullet != null) Kind.BULLETS else Kind.NUMBERING
    }
    /** A numbered list's look: its numbers' kind and what follows them ("." or ")"). */
    enum class Numbering(val closer: Char) { DECIMAL_DOT('.'), DECIMAL_PAREN(')'), LOWER_DOT('.'), LOWER_PAREN(')'), UPPER_DOT('.'), UPPER_PAREN(')') }

    /** Characters that end a word as typed (Word's delimiters): white space and punctuation. */
    private const val PUNCT = ".,;:!?)]}"
    private const val CLOSERS = ")]}\"'”’»›"
    private const val OPENERS = "([{\"'“‘«‹„‚"

    /**
     * What [text] (a paragraph's, a character just typed before [caret]) calls for, the fixes from right to left —
     * each before the caret — or an empty list. [language]: the text's language (quotes, the English corrections).
     */
    fun after(text: String, caret: Int, language: String, opts: Options = options, startsSentence: Boolean = true): List<Fix> {
        if (caret <= 0 || caret > text.length) return emptyList()
        val ctx = Ctx(opts, startsSentence)
        val typed = text[caret - 1]
        val lang = language.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        // A double quote after a word closes it: the word is corrected as by any punctuation mark, the quote curled.
        if (typed == '"' || typed == '\'') {
            val prev = text.getOrNull(caret - 2)
            val closing = prev != null && !opensQuote(prev)
            val word = if (typed == '"' && closing) words(text, caret - 1, typed, space = false, lang, ctx) else emptyList()
            val q = if (opts.smartQuotes) quote(text, caret, typed, lang) else null
            return listOfNotNull(q) + word
        }
        // "(c)", "...", "-->" — and one's own entries ending in a mark, ":)" — as soon as their last character is typed.
        if (opts.replaceText) (entryNow(text, caret, opts) ?: symbol(text, caret, opts.skip))?.let { return listOf(it) }
        val space = typed == ' ' || typed == '\t' || typed == '\u00A0'
        if (!space && typed !in PUNCT) return emptyList()
        // At a paragraph's start: "* " a bulleted list, "1. " a numbered one.
        if (space && opts.autoLists) autoList(text, caret)?.let { return listOf(it) }
        return words(text, caret - 1, typed, space, lang, ctx)
    }

    /** What a correction is read with: the options, and whether the text's start is a sentence's (not in a cell). */
    private class Ctx(val opts: Options, val startsSentence: Boolean)

    /** What the word ended at [end] by [typed] (a space when [space]) calls for: a link, a dash, ½, 1st, a capital. */
    private fun words(text: String, end: Int, typed: Char, space: Boolean, lang: String, ctx: Ctx): List<Fix> {
        val opts = ctx.opts
        val out = ArrayList<Fix>()
        var start = end
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        if (start == end) return emptyList()
        val token = text.substring(start, end)
        // One's own entries first: the whole token as typed, else the word without its brackets and marks.
        if (opts.replaceText) entryFor(text, start, end, opts)?.let { return listOf(it) }
        if (space && opts.hyperlinks) hyperlink(token, start)?.let { return listOf(it) }
        if (space && opts.dashes) dash(text, start, end, token)?.let { out.add(it) }
        // The word in the token: its brackets, quotes and trailing marks aside.
        var a = start; var b = end
        while (a < b && text[a] in OPENERS) a++
        while (b > a && (text[b - 1] in PUNCT || text[b - 1] in CLOSERS)) b--
        if (a >= b) return out
        val word = text.substring(a, b)
        if (opts.fractions) FRACTIONS[word]?.let { if (a == start) return out + Replace(a, b, it, Kind.FRACTION) }
        if (lang == "en" && opts.ordinals) ordinal(word, a)?.let { return out + it }
        if (out.isNotEmpty()) return out
        // A letter before "." or ")" is a list's marker or an initial (a) J.), not a word to capitalise.
        val marker = word.length == 1 && b == end && (typed == '.' || typed == ')')
        wordFix(text, a, word, lang, ctx, capitalize = !marker)?.let { out.add(it) }
        return out
    }

    /** Whether a quote typed after [prev] opens (after white space, a bracket, a dash or another opening quote). */
    private fun opensQuote(prev: Char): Boolean = prev.isWhitespace() || prev in "([{<—–-/" || prev in "“‘«‹„‚"

    // --- making them in a paragraph (both editors) ---------------------------------------------------

    /** The language AutoCorrect reads [text] in: its own, else the app's (Latin letters in a CJK app's: English). */
    fun languageOf(text: String): String {
        com.therealityteam.aeropages.model.TextLanguage.guess(text)?.language?.let { return it }
        val app = Locale.getDefault().language
        return if (app in setOf("zh", "ja", "ko", "ru", "th", "hi", "uk", "el")) "en" else app
    }

    /** The fixes [para] allows: none inside a field's result, a formula or a link already there. */
    internal fun doable(para: Paragraph, fixes: List<Fix>): List<Fix> {
        val n = ParagraphOps.length(para)
        fun plain(a: Int, b: Int) = (a until maxOf(a + 1, b)).all { i ->
            val f = ParagraphOps.formatAt(para, i.coerceIn(0, maxOf(0, n - 1)))
            f.field == null && f.math == null && f.link == null
        }
        return fixes.filter { fx ->
            when (fx) {
                is Replace -> plain(fx.start, fx.end)
                is Raise -> plain(fx.start, fx.end)
                is Link -> plain(fx.start, fx.end)
                is MakeList -> para.format.listKind == com.therealityteam.aeropages.model.ListKind.NONE && plain(0, fx.markerEnd)
            }
        }
    }

    /** Where [fx] starts in the text. */
    internal fun startOf(fx: Fix): Int = when (fx) { is Replace -> fx.start; is Raise -> fx.start; is Link -> fx.start; is MakeList -> 0 }

    /** The fix the AutoCorrect Options button names (a word's, over the quote curled with it), what it was and is now. */
    internal fun named(text: String, fixes: List<Fix>): Triple<Fix, String, String> {
        val main = fixes.firstOrNull { it.kind != Kind.QUOTES } ?: fixes.first()
        val (was, now) = when (main) {
            is Replace -> text.substring(main.start, main.end) to main.text
            is Raise -> text.substring(main.start, main.end) to text.substring(main.start, main.end)
            is Link -> text.substring(main.start, main.end) to main.url
            is MakeList -> text.substring(0, main.markerEnd) to ""
        }
        return Triple(main, was, now)
    }

    /** [fx] made in [para] (a list's marker only taken away — the list is the editor's): how much the text before it grew. */
    internal fun make(para: Paragraph, fx: Fix): Int = when (fx) {
        is Replace -> { ParagraphOps.replace(para, fx.start, fx.end, fx.text, ParagraphOps.formatAt(para, fx.start)); fx.text.length - (fx.end - fx.start) }
        is Raise -> { ParagraphOps.applyFormat(para, fx.start, fx.end) { it.superscript = true; it.subscript = false }; 0 }
        is Link -> { ParagraphOps.applyFormat(para, fx.start, fx.end) { it.link = fx.url }; 0 }
        is MakeList -> { ParagraphOps.replace(para, 0, fx.markerEnd, "", ParagraphOps.formatAt(para, 0)); -fx.markerEnd }
    }

    // --- quotes ---------------------------------------------------------------------------------

    /** Opening and closing double quotes, then single ones, by language (Word's per-language smart quotes). */
    private fun quotesOf(lang: String): String = when (lang) {
        "de" -> "„“‚‘"
        "ru" -> "«»„“"
        "pl" -> "„”‚’"
        "fr" -> "«»‘’"
        else -> "“”‘’"
    }

    private fun quote(text: String, caret: Int, typed: Char, lang: String): Replace? {
        val prev = text.getOrNull(caret - 2)
        val opening = prev == null || opensQuote(prev)
        val q = quotesOf(lang)
        if (typed == '\'') {
            // A letter's or a digit's: the apostrophe (it’s, ’90s after a space is rare enough to curl as an opening quote).
            val ch = when {
                !opening && (prev!!.isLetterOrDigit()) -> '’'
                opening -> q[2]
                else -> q[3]
            }
            return Replace(caret - 1, caret, ch.toString(), Kind.QUOTES)
        }
        if (lang == "fr") {
            // French quotes stand apart from what they hold by a no-break space: « mot ».
            return if (opening) Replace(caret - 1, caret, "« ", Kind.QUOTES)
            else if (prev == ' ') Replace(caret - 2, caret, " »", Kind.QUOTES)
            else Replace(caret - 1, caret, " »", Kind.QUOTES)
        }
        return Replace(caret - 1, caret, (if (opening) q[0] else q[1]).toString(), Kind.QUOTES)
    }

    // --- symbols --------------------------------------------------------------------------------

    /** Word's default AutoCorrect entries that are symbols, applied when the last character is typed. */
    private val SYMBOLS = listOf(
        "(c)" to "©", "(r)" to "®", "(tm)" to "™", "(e)" to "€",
        "..." to "…", "-->" to "→", "<--" to "←", "==>" to "⇒", "<==" to "⇐", "<=>" to "⇔",
    )

    private fun symbol(text: String, caret: Int, skip: Set<String>): Replace? {
        for ((key, sym) in SYMBOLS) {
            if (key in skip) continue
            val s = caret - key.length
            if (s < 0 || !text.regionMatches(s, key, 0, key.length, ignoreCase = true)) continue
            val before = text.getOrNull(s - 1)
            // Not glued to a word before it — f(c) stays f(c) — save ® and ™, which follow a name: Acme®.
            if ((key == "(c)" || key == "(e)") && before != null && before.isLetterOrDigit()) continue
            if (key == "..." && before == '.') continue
            return Replace(s, caret, sym, Kind.REPLACE)
        }
        return null
    }

    // --- lists ----------------------------------------------------------------------------------

    private val NUMBER_MARK = Regex("^(1|a|A)([.)])$")

    private fun autoList(text: String, caret: Int): MakeList? {
        val marker = text.substring(0, caret - 1)
        when (marker) {
            "*" -> return MakeList(caret, "•", null)
            "-" -> return MakeList(caret, "–", null)
            ">" -> return MakeList(caret, "➢", null)
        }
        val m = NUMBER_MARK.matchEntire(marker) ?: return null
        val paren = m.groupValues[2] == ")"
        val kind = when (m.groupValues[1]) {
            "1" -> if (paren) Numbering.DECIMAL_PAREN else Numbering.DECIMAL_DOT
            "a" -> if (paren) Numbering.LOWER_PAREN else Numbering.LOWER_DOT
            else -> if (paren) Numbering.UPPER_PAREN else Numbering.UPPER_DOT
        }
        return MakeList(caret, null, kind)
    }

    // --- links ----------------------------------------------------------------------------------

    private val WEB = Regex("^(https?://|ftp://|www\\.)[^\\s]+$", RegexOption.IGNORE_CASE)
    private val MAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$")

    /** The link [text] is when it is a web or e-mail address and nothing else (a cell's whole entry), else null. */
    fun linkFor(text: String): String? = hyperlink(text, 0)?.takeIf { it.start == 0 && it.end == text.length }?.url

    private fun hyperlink(token: String, start: Int): Link? {
        var a = 0; var b = token.length
        while (a < b && token[a] in "(<\"'“‘") a++
        while (b > a && token[b - 1] in ".,;:!?)>\"'”’") b--
        val core = token.substring(a, b)
        val url = when {
            WEB.matches(core) && core.length > 4 && core.substringAfter("://").contains('.') ->
                if (core.startsWith("www.", ignoreCase = true)) "http://$core" else core
            MAIL.matches(core) -> "mailto:$core"
            else -> return null
        }
        return Link(start + a, start + b, url)
    }

    // --- dashes ---------------------------------------------------------------------------------

    private val EM = Regex("^([\\p{L}\\p{N}]+)--([\\p{L}\\p{N}]+)[.,;:!?)]*$")

    /** "word--word " an em dash; "word - word " (or " -- ") an en dash. */
    private fun dash(text: String, start: Int, end: Int, token: String): Replace? {
        EM.find(token)?.let { m -> val at = start + m.groupValues[1].length; return Replace(at, at + 2, "—", Kind.DASH) }
        if (!token.first().isLetterOrDigit()) return null
        // The token is the word after " - ": the dash, a space each side, a word before.
        if (text.getOrNull(start - 1) != ' ') return null
        var d = start - 1
        var dashes = 0
        while (d > 0 && text[d - 1] == '-' && dashes < 2) { d--; dashes++ }
        if (dashes == 0 || text.getOrNull(d - 1) != ' ') return null
        val before = text.getOrNull(d - 2) ?: return null
        if (!before.isLetterOrDigit() && before !in CLOSERS) return null
        if (end <= start) return null
        return Replace(d, d + dashes, "–", Kind.DASH)
    }

    // --- words ----------------------------------------------------------------------------------

    private val FRACTIONS = mapOf("1/2" to "½", "1/4" to "¼", "3/4" to "¾")

    private val ORDINAL = Regex("^(\\d+)(st|nd|rd|th)$")

    private fun ordinal(word: String, at: Int): Raise? {
        val m = ORDINAL.matchEntire(word) ?: return null
        val n = m.groupValues[1].takeLast(2).toInt()
        val right = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        if (m.groupValues[2] != right) return null
        val s = at + m.groupValues[1].length
        return Raise(s, s + 2)
    }

    /** Common English slips Word puts right as they are typed (its AutoCorrect list's best known), in small letters. */
    private val ENGLISH = mapOf(
        "i" to "I", "i'm" to "I'm", "i’m" to "I’m", "i've" to "I've", "i’ve" to "I’ve", "i'll" to "I'll", "i’ll" to "I’ll",
        "i'd" to "I'd", "i’d" to "I’d",
        "teh" to "the", "adn" to "and", "taht" to "that", "thier" to "their", "wich" to "which", "becuase" to "because",
        "beleive" to "believe", "recieve" to "receive", "seperate" to "separate", "definately" to "definitely",
        "occured" to "occurred", "occurence" to "occurrence", "untill" to "until", "accomodate" to "accommodate",
        "acheive" to "achieve", "alot" to "a lot", "arguement" to "argument", "begining" to "beginning",
        "calender" to "calendar", "comming" to "coming", "commitee" to "committee", "enviroment" to "environment",
        "existance" to "existence", "foward" to "forward", "freind" to "friend", "goverment" to "government",
        "happend" to "happened", "independant" to "independent", "neccessary" to "necessary", "peice" to "piece",
        "posession" to "possession", "prefered" to "preferred", "recomend" to "recommend", "refered" to "referred",
        "succesful" to "successful", "suprise" to "surprise", "tommorow" to "tomorrow", "truely" to "truly",
        "wierd" to "weird", "youre" to "you're", "dont" to "don't", "didnt" to "didn't", "doesnt" to "doesn't",
        "isnt" to "isn't", "wasnt" to "wasn't", "couldnt" to "couldn't", "shouldnt" to "shouldn't", "wouldnt" to "wouldn't",
        "hte" to "the", "thsi" to "this", "waht" to "what", "whihc" to "which", "knwo" to "know", "jsut" to "just",
    )

    /** English day names (in small letters), capitalised as typed. */
    private val DAYS = setOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

    /** [value] as [typed] has it: a key in small letters typed with a capital first gives the value a capital first. */
    private fun asTyped(key: String, typed: String, value: String): String =
        if (key == key.lowercase(Locale.ROOT) && typed.firstOrNull()?.isUpperCase() == true && typed.drop(1) == typed.drop(1).lowercase(Locale.ROOT))
            value.replaceFirstChar { it.titlecase(Locale.ROOT) } else value

    /** One's own entry [typed] is, if any: exact, or (a key in small letters) whatever its case. */
    private fun entryOf(typed: String, opts: Options): Pair<String, String>? {
        if (typed.isEmpty() || opts.entries.isEmpty()) return null
        val lower = typed.lowercase(Locale.ROOT)
        if (lower in opts.skip) return null
        opts.entries[typed]?.let { return typed to it }
        val hit = opts.entries.entries.firstOrNull { it.key == lower } ?: return null
        return hit.key to hit.value
    }

    /** One's own entry the word ended at [end] (from [start]) is: the token as typed, else the word in it. */
    private fun entryFor(text: String, start: Int, end: Int, opts: Options): Replace? {
        val token = text.substring(start, end)
        entryOf(token, opts)?.let { (k, v) -> if (k.last().isLetterOrDigit()) return Replace(start, end, asTyped(k, token, v), Kind.REPLACE) }
        var a = start; var b = end
        while (a < b && text[a] in OPENERS) a++
        while (b > a && (text[b - 1] in PUNCT || text[b - 1] in CLOSERS)) b--
        if (a >= b || (a == start && b == end)) return null
        val word = text.substring(a, b)
        val (k, v) = entryOf(word, opts) ?: return null
        return Replace(a, b, asTyped(k, word, v), Kind.REPLACE)
    }

    /** One's own entry ending in a mark (":)", "e.g.") just completed at [caret], standing on its own. */
    private fun entryNow(text: String, caret: Int, opts: Options): Replace? {
        if (opts.entries.isEmpty() || text[caret - 1].isLetterOrDigit()) return null
        for ((key, value) in opts.entries) {
            if (key.last().isLetterOrDigit() || key.lowercase(Locale.ROOT) in opts.skip) continue
            val s = caret - key.length
            if (s < 0 || !text.regionMatches(s, key, 0, key.length, ignoreCase = key == key.lowercase(Locale.ROOT))) continue
            val before = text.getOrNull(s - 1)
            if (before != null && !before.isWhitespace() && before !in OPENERS) continue
            return Replace(s, caret, asTyped(key, text.substring(s, caret), value), Kind.REPLACE)
        }
        return null
    }

    /** Whether [key] can be an entry of one's own: no white space, not empty. */
    fun validKey(key: String): Boolean = key.isNotEmpty() && key.none { it.isWhitespace() }

    /** Abbreviations a full stop does not end a sentence after. */
    private val ABBREVIATIONS = setOf(
        "e.g", "i.e", "etc", "vs", "approx", "fig", "figs", "no", "nos", "p", "pp", "vol", "cf", "al", "mr", "mrs", "ms",
        "dr", "prof", "st", "jr", "sr", "inc", "ltd", "co", "dept", "est", "jan", "feb", "mar", "apr", "jun", "jul", "aug",
        "sep", "sept", "oct", "nov", "dec", "z.b", "bzw", "usw", "ca", "vgl", "env", "cf", "ex", "ref", "tel",
    )

    private fun wordFix(text: String, a: Int, word: String, lang: String, ctx: Ctx, capitalize: Boolean = true): Replace? {
        val opts = ctx.opts
        if (!word.all { it.isLetter() || it == '\'' || it == '’' }) return null
        var fixed = word
        var kind: Kind? = null
        if (lang == "en" && opts.replaceText) {
            val lower = word.lowercase(Locale.ROOT)
            ENGLISH[lower]?.takeIf { lower !in opts.skip }?.let { r ->
                // As typed: in small letters, or with its first a capital ("Teh" → "The").
                val asTyped = when {
                    word == lower -> r
                    word.first().isUpperCase() && word.drop(1) == lower.drop(1) -> r.replaceFirstChar { it.titlecase(Locale.ROOT) }
                    else -> null
                }
                if (asTyped != null && asTyped != word) { fixed = asTyped; kind = Kind.REPLACE }
            }
        }
        // "THe" → "The"; not "IDs" or "PCs" (an acronym's plural).
        if (kind == null && opts.twoInitialCaps && fixed.length >= 3 && fixed[0].isUpperCase() && fixed[1].isUpperCase() &&
            fixed.drop(2).all { it.isLowerCase() } && fixed.drop(2) != "s"
        ) { fixed = fixed[0] + fixed.substring(1).lowercase(Locale.ROOT); kind = Kind.TWO_INITIALS }
        // English day names take their capital wherever they stand (Word's and Excel's "Capitalize names of days").
        if (kind == null && lang == "en" && opts.capitalizeDays && fixed in DAYS) {
            fixed = fixed.replaceFirstChar { it.titlecase(Locale.ROOT) }; kind = Kind.DAYS
        }
        if (capitalize && opts.capitalizeSentences && fixed.first().isLowerCase() && fixed.none { it.isUpperCase() } && sentenceStart(text, a, ctx.startsSentence)) {
            fixed = fixed.replaceFirstChar { it.titlecase(Locale.ROOT) }
            if (kind == null) kind = Kind.CAPITALIZE
        }
        val k = kind ?: return null
        return Replace(a, a + word.length, fixed, k)
    }

    /**
     * Whether the word at [a] starts a sentence: the paragraph's start (when [atStart] — not a cell's), a new line, or
     * after ". " "! " "? " (not "e.g. ").
     */
    private fun sentenceStart(text: String, a: Int, atStart: Boolean): Boolean {
        var i = a
        // Opening quotes and brackets before it, a French quote's no-break space with them: « Mot.
        while (i > 0 && (text[i - 1] in OPENERS || text[i - 1] == '\u00A0' && text.getOrNull(i - 2)?.let { it in OPENERS } == true)) i--
        if (i == 0) return atStart
        if (!text[i - 1].isWhitespace()) return false
        while (i > 0 && text[i - 1].isWhitespace()) { if (text[i - 1] == '\n') return true; i-- }
        if (i == 0) return atStart
        var j = i
        while (j > 0 && text[j - 1] in CLOSERS) j--
        if (j == 0) return false
        val mark = text[j - 1]
        if (mark == '!' || mark == '?') return true
        if (mark != '.') return false
        // The word the full stop ends: an abbreviation, an initial, a number or a web address does not end a sentence.
        var k = j - 1
        while (k > 0 && !text[k - 1].isWhitespace()) k--
        val before = text.substring(k, j - 1).trimStart { it in OPENERS }
        if (before.isEmpty()) return false
        if (before.endsWith('.')) return true   // "…" written as dots
        val low = before.lowercase(Locale.ROOT)
        if (low in ABBREVIATIONS) return false
        if (before.length == 1 && before[0].isLetter()) return false
        if (before.any { it.isDigit() }) return before.any { it.isLetter() } && !before.contains('.')
        if (before.contains('.') || before.contains('@') || before.contains('/')) return false
        return true
    }
}
