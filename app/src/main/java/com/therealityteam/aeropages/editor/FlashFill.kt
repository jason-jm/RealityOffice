package com.therealityteam.aeropages.editor

import java.util.PriorityQueue

/**
 * Excel's Flash Fill: from a few examples — each the texts of a row's other columns and what was typed beside them —
 * a way of building the typed text out of those texts, to fill the rest of the column the same way.
 *
 * A way is a run of pieces: a whole cell, one of a cell's words (counted from its start or its end), a word's first
 * letter, one of its characters (for text written without spaces, a name in Chinese), or a fixed piece of text — each
 * as it is, in capitals, in small letters or capitalised. The way that fits every example and is the simplest (fewest
 * pieces, words before characters before fixed text) wins: "Wang, Li" from "Li Wang" is the second word, ", " and the
 * first; "LW" is two initials in capitals.
 */
object FlashFill {

    enum class Case { SAME, UPPER, LOWER, PROPER }

    sealed interface Piece
    data class Text(val text: String) : Piece
    data class Whole(val col: Int, val case: Case) : Piece
    /** Word [index] of column [col] (from its start; negative: from its end, -1 the last), or only its first letter. */
    data class Word(val col: Int, val index: Int, val case: Case, val initial: Boolean = false) : Piece
    /** Character [index] of column [col] (negative: from its end). */
    data class Char(val col: Int, val index: Int, val case: Case) : Piece

    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /** What a fixed piece of text costs (more than any part of a cell), and each further character of it. */
    private const val FIXED = 3.0
    private const val FIXED_MORE = 0.6
    private const val FIXED_LETTER = 1.5

    fun words(s: String): List<String> = WORD.findAll(s).map { it.value }.toList()

    private fun cased(s: String, case: Case): String = when (case) {
        Case.SAME -> s
        Case.UPPER -> s.uppercase()
        Case.LOWER -> s.lowercase()
        Case.PROPER -> s.lowercase().replaceFirstChar { it.titlecase() }
    }

    /** What [piece] makes of a row's [cells] (its columns' texts by column), or null when the row has no such part. */
    fun make(piece: Piece, cells: Map<Int, String>): String? = when (piece) {
        is Text -> piece.text
        is Whole -> cells[piece.col]?.takeIf { it.isNotEmpty() }?.let { cased(it, piece.case) }
        is Word -> cells[piece.col]?.let { words(it) }?.let { ws ->
            val i = if (piece.index >= 0) piece.index else ws.size + piece.index
            ws.getOrNull(i)?.let { w -> if (piece.initial) cased(w.take(1), piece.case) else cased(w, piece.case) }
        }
        is Char -> cells[piece.col]?.let { s ->
            val i = if (piece.index >= 0) piece.index else s.length + piece.index
            s.getOrNull(i)?.let { cased(it.toString(), piece.case) }
        }
    }

    /** A row by [way]: its pieces one after another, or null when a piece has nothing in the row. */
    fun apply(way: List<Piece>, cells: Map<Int, String>): String? {
        val out = StringBuilder()
        for (p in way) out.append(make(p, cells) ?: return null)
        return out.toString().takeIf { it.isNotEmpty() }
    }

    /**
     * The simplest way that builds each example's typed text from its row ([examples]: the row's columns' texts and
     * the text typed beside them), or null when none fits them all. A search over how far each example's text is
     * built, cheapest pieces first.
     */
    fun learn(examples: List<Pair<Map<Int, String>, String>>): List<Piece>? {
        if (examples.isEmpty() || examples.any { it.second.isEmpty() }) return null
        val first = examples[0]
        val n = examples.size
        val goal = examples.map { it.second.length }
        data class State(val at: List<Int>, val cost: Double, val way: List<Piece>) {
            /** Where it stands, and whether in fixed text (which goes on cheaply: one piece, not letters spelled out). */
            val key get() = at to (way.lastOrNull() is Text)
        }
        val queue = PriorityQueue<State>(compareBy({ it.cost }, { it.way.size }))
        val best = HashMap<Pair<List<Int>, Boolean>, Double>()
        queue.add(State(List(n) { 0 }, 0.0, emptyList()))
        var steps = 0
        while (queue.isNotEmpty() && steps++ < 200_000) {
            val st = queue.poll() ?: break
            if (st.at == goal) return merged(st.way)
            if ((best[st.key] ?: Double.MAX_VALUE) < st.cost) continue
            // The pieces the first example could continue with here, each tried against every example.
            for ((piece, cost) in candidates(first.first, first.second, st.at[0])) {
                val next = ArrayList<Int>(n)
                var fits = true
                for (k in 0 until n) {
                    val made = make(piece, examples[k].first)
                    if (made == null || made.isEmpty() || !examples[k].second.startsWith(made, st.at[k])) { fits = false; break }
                    next.add(st.at[k] + made.length)
                }
                if (!fits) continue
                val c = st.cost + if (piece is Text) {
                    // Fixed text goes on cheaply once started; a letter or digit in it costs more (a cell's would do).
                    (if (st.way.lastOrNull() is Text) FIXED_MORE else FIXED) + (if (piece.text.first().isLetterOrDigit()) FIXED_LETTER else 0.0)
                } else cost
                val state = State(next, c, st.way + piece)
                if (c < (best[state.key] ?: Double.MAX_VALUE)) {
                    best[state.key] = c
                    queue.add(state)
                }
            }
        }
        return null
    }

    /** Pieces that make [out] from position [at] for a row's [cells], each with its cost (simpler pieces cost less). */
    private fun candidates(cells: Map<Int, String>, out: String, at: Int): List<Pair<Piece, Double>> {
        val list = ArrayList<Pair<Piece, Double>>()
        val rest = out.substring(at)
        for ((col, s) in cells) {
            if (s.isEmpty()) continue
            for (case in Case.entries) {
                val caseCost = if (case == Case.SAME) 0.0 else 0.05
                if (rest.startsWith(cased(s, case))) list.add(Whole(col, case) to 0.9 + caseCost)
                val ws = words(s)
                ws.forEachIndexed { i, w ->
                    // From the start for the first word, from the end for the last — what stays true of other rows.
                    val fromStart = when (i) { 0 -> 1.0; else -> 1.05 }
                    val fromEnd = when (i) { ws.lastIndex -> 1.0; else -> 1.1 }
                    if (rest.startsWith(cased(w, case))) {
                        list.add(Word(col, i, case) to fromStart + caseCost)
                        list.add(Word(col, i - ws.size, case) to fromEnd + caseCost)
                    }
                    if (w.length > 1 && rest.startsWith(cased(w.take(1), case))) {
                        list.add(Word(col, i, case, initial = true) to fromStart + 0.5 + caseCost)
                        list.add(Word(col, i - ws.size, case, initial = true) to fromEnd + 0.5 + caseCost)
                    }
                }
                // A single character is a piece only in writing without spaces (Chinese, Japanese, Korean) or as a digit
                // (the last four of a code); Latin words are not spelt out of another word's letters.
                if (case == Case.SAME || case == Case.UPPER) s.forEachIndexed { i, ch ->
                    if ((ch.isDigit() || unspaced(ch)) && rest.startsWith(cased(ch.toString(), case))) {
                        list.add(Char(col, i, case) to 1.8 + caseCost + i * 0.001)
                        list.add(Char(col, i - s.length, case) to 1.85 + caseCost)
                    }
                }
            }
        }
        list.add(Text(rest.take(1)) to FIXED)
        return list
    }

    /** A character of a script written without spaces between words. */
    private fun unspaced(ch: kotlin.Char): Boolean = when (Character.UnicodeScript.of(ch.code)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL, Character.UnicodeScript.THAI -> true
        else -> false
    }

    /** Neighbouring fixed pieces as one. */
    private fun merged(way: List<Piece>): List<Piece> {
        val out = ArrayList<Piece>()
        for (p in way) {
            val last = out.lastOrNull()
            if (p is Text && last is Text) out[out.lastIndex] = Text(last.text + p.text) else out.add(p)
        }
        return out
    }
}
