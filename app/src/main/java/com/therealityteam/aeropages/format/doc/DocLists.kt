package com.therealityteam.aeropages.format.doc

import com.therealityteam.aeropages.model.ListDef
import com.therealityteam.aeropages.model.ListLevel
import com.therealityteam.aeropages.model.NumFormat

/**
 * A Word 97–2003 document's lists ([MS-DOC] 2.9.147 PlfLst, 2.9.150 LVL, 2.9.143 PlfLfo): each
 * list definition's levels, and the list instances (LFOs) paragraphs name by `sprmPIlfo` — the
 * instances of one definition counting on together as one list of the app's, one that overrides a
 * level (a restart) a list of its own, as Word's newer files have it (see WordLists). Also the
 * paragraph styles' lists (a heading style numbering its headings), from the style sheet.
 */
internal object DocLists {

    /** A list instance's list (its app id) and levels, and each level's indents (left, first line; twips). */
    class Instance(val listId: Int, val def: ListDef, val indents: List<Pair<Int?, Int?>>)

    private class Lst(val lsid: Int, val levels: List<ListLevel>, val indents: List<Pair<Int?, Int?>>)

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, at: Int) = le16(b, at) or (le16(b, at + 2) shl 16)

    /** The list instances by their 1-based index (what `sprmPIlfo` says). */
    fun read(table: ByteArray, fcLst: Int, lcbLst: Int, fcLfo: Int, lcbLfo: Int, fonts: List<String>): Map<Int, Instance> {
        if (lcbLst <= 2 || lcbLfo <= 4 || fcLst < 0 || fcLfo < 0 || fcLst + 2 > table.size || fcLfo + 4 > table.size) return emptyMap()
        val cLst = le16(table, fcLst).toShort().toInt()
        if (cLst <= 0 || fcLst + 2 + cLst * 28 > table.size) return emptyMap()
        val lstfs = (0 until cLst).map { i -> val at = fcLst + 2 + i * 28; Triple(le32(table, at), (table[at + 26].toInt() and 1) != 0, at) }
        // The levels follow the definitions: one for a simple list, nine for the others.
        var at = fcLst + 2 + cLst * 28
        val lsts = HashMap<Int, Lst>()
        for ((lsid, simple, _) in lstfs) {
            val levels = ArrayList<ListLevel>(); val indents = ArrayList<Pair<Int?, Int?>>()
            repeat(if (simple) 1 else 9) {
                val (lvl, ind, next) = readLvl(table, at, fonts) ?: return lsts.toInstances(table, fcLfo, lcbLfo, fonts)
                levels.add(lvl); indents.add(ind); at = next
            }
            lsts[lsid] = Lst(lsid, levels, indents)
        }
        return lsts.toInstances(table, fcLfo, lcbLfo, fonts)
    }

    private fun Map<Int, Lst>.toInstances(table: ByteArray, fcLfo: Int, lcbLfo: Int, fonts: List<String>): Map<Int, Instance> {
        val lfoMac = le32(table, fcLfo)
        if (lfoMac <= 0 || lfoMac > 0x7FF || fcLfo + 4 + lfoMac * 16 > table.size) return emptyMap()
        val lfos = (0 until lfoMac).map { i -> val a = fcLfo + 4 + i * 16; le32(table, a) to (table[a + 12].toInt() and 0xFF) }
        // Each LFO's overrides (LFOData: a CP, then its LFOLVLs, each perhaps with a whole level).
        var at = fcLfo + 4 + lfoMac * 16
        val out = HashMap<Int, Instance>()
        val byLst = HashMap<Int, Int>()
        var next = 1
        for ((i, lfo) in lfos.withIndex()) {
            val (lsid, clfolvl) = lfo
            val starts = HashMap<Int, Int>(); val redefined = HashMap<Int, ListLevel>()
            if (clfolvl > 0) {
                at += 4
                repeat(clfolvl) {
                    if (at + 8 > table.size) return@repeat
                    val start = le32(table, at); val flags = le32(table, at + 4)
                    val lv = flags and 0xF
                    at += 8
                    if ((flags and 0x10) != 0) starts[lv] = start
                    if ((flags and 0x20) != 0) readLvl(table, at, fonts)?.let { (l, _, n) -> redefined[lv] = l; at = n } ?: return@repeat
                }
            } else at += 4
            val lst = this[lsid] ?: continue
            val base = if (lst.levels.firstOrNull()?.format == NumFormat.BULLET) ListDef.BULLETS else ListDef.NUMBERS
            val def = ListDef(List(9) { lv ->
                var l = redefined[lv] ?: lst.levels.getOrNull(lv) ?: base.level(lv)
                starts[lv]?.let { l = l.copy(start = it) }
                l
            })
            val id = if (starts.isNotEmpty() || redefined.isNotEmpty()) next++ else byLst.getOrPut(lsid) { next++ }
            out[i + 1] = Instance(id, def, lst.indents)
        }
        return out
    }

    /** One LVL at [at]: the level, its indents, where the next begins; null when it runs past the stream. */
    private fun readLvl(b: ByteArray, at: Int, fonts: List<String>): Triple<ListLevel, Pair<Int?, Int?>, Int>? {
        if (at + 28 > b.size) return null
        val start = le32(b, at)
        val nfc = b[at + 4].toInt() and 0xFF
        val flags = b[at + 5].toInt() and 0xFF
        val cbChpx = b[at + 24].toInt() and 0xFF
        val cbPapx = b[at + 25].toInt() and 0xFF
        val restartLim = b[at + 26].toInt() and 0xFF
        var p = at + 28
        var left: Int? = null; var first: Int? = null
        if (p + cbPapx > b.size) return null
        sprms(b, p, p + cbPapx) { sprm, op ->
            when (sprm) {
                0x840F, 0x845E -> left = op.toShort().toInt()
                0x8411, 0x8460 -> first = op.toShort().toInt()
            }
        }
        p += cbPapx
        var font: String? = null
        if (p + cbChpx > b.size) return null
        sprms(b, p, p + cbChpx) { sprm, op -> if (sprm == 0x4A4F || sprm == 0x4A51) fonts.getOrNull(op)?.let { font = it } }
        p += cbChpx
        if (p + 2 > b.size) return null
        val cch = le16(b, p)
        if (p + 2 + cch * 2 > b.size) return null
        val text = StringBuilder()
        for (k in 0 until cch) {
            val ch = le16(b, p + 2 + k * 2)
            if (ch < 9) text.append('%').append(ch + 1) else text.append(ch.toChar())
        }
        p += 2 + cch * 2
        val format = formatOf(nfc)
        val restart = if ((flags and 0x08) != 0) (if (restartLim == 0) ListLevel.NEVER else restartLim - 1) else -1
        val level = ListLevel(format, text.toString(), start, if (format == NumFormat.BULLET) font else null, restart, (flags and 0x04) != 0)
        return Triple(level, left to first, p)
    }

    /** The sprms of a grpprl: each one's id and its operand (up to four bytes; longer ones are skipped). */
    private fun sprms(b: ByteArray, from: Int, to: Int, each: (Int, Int) -> Unit) {
        var i = from
        while (i + 2 <= to) {
            val sprm = le16(b, i); i += 2
            val size = when (sprm ushr 13) { 0, 1 -> 1; 2, 4, 5 -> 2; 3 -> 4; 7 -> 3; else -> if (i < to) (b[i].toInt() and 0xFF) + 1 else return }
            if (i + size > to) return
            val op = when (size) { 1 -> b[i].toInt() and 0xFF; 2 -> le16(b, i); 3 -> le16(b, i) or ((b[i + 2].toInt() and 0xFF) shl 16); 4 -> le32(b, i); else -> 0 }
            if (size <= 4) each(sprm, op)
            i += size
        }
    }

    /** Word's number formats (MSONFC) as the app's. */
    fun formatOf(nfc: Int): NumFormat = when (nfc) {
        1 -> NumFormat.UPPER_ROMAN
        2 -> NumFormat.LOWER_ROMAN
        3 -> NumFormat.UPPER_LETTER
        4 -> NumFormat.LOWER_LETTER
        10, 11, 16, 17, 33, 34, 35, 36, 37, 39, 40, 41, 42, 43, 44 -> NumFormat.CHINESE
        38 -> NumFormat.CHINESE_LEGAL
        14, 19 -> NumFormat.FULL_WIDTH
        18, 28, 29 -> NumFormat.CIRCLED
        22 -> NumFormat.DECIMAL_ZERO
        23 -> NumFormat.BULLET
        26 -> NumFormat.FULL_STOP
        27 -> NumFormat.PARENTHESIZED
        30 -> NumFormat.HEAVENLY_STEMS
        31, 32 -> NumFormat.EARTHLY_BRANCHES
        255 -> NumFormat.NONE
        else -> NumFormat.DECIMAL
    }

    /** A paragraph style's list: the instance (`sprmPIlfo`) and level (`sprmPIlvl`) it sets, and the style it is based on. */
    class StyleList(val ilfo: Int?, val ilvl: Int?, val base: Int)

    /** The style sheet's paragraph styles' lists ([MS-DOC] 2.9.271 STSH), by style index. */
    fun styles(table: ByteArray, fcStshf: Int, lcbStshf: Int): Map<Int, StyleList> {
        if (fcStshf < 0 || lcbStshf < 4 || fcStshf + lcbStshf > table.size) return emptyMap()
        val cbStshi = le16(table, fcStshf)
        val shi = fcStshf + 2
        if (cbStshi < 4) return emptyMap()
        val cstd = le16(table, shi)
        val cbBase = le16(table, shi + 2)
        val end = fcStshf + lcbStshf
        var at = shi + cbStshi
        val out = HashMap<Int, StyleList>()
        for (istd in 0 until cstd) {
            if (at + 2 > end) break
            val cb = le16(table, at)
            val std = at + 2
            at = std + cb
            if (cb == 0 || at > end || cb < cbBase + 2) continue
            val stk = le16(table, std + 2) and 0xF
            val base = le16(table, std + 2) ushr 4
            if (stk != 1) continue
            // The name (Xstz), then the paragraph style's UpxPapx.
            var p = std + cbBase
            val cch = le16(table, p)
            p += 2 + cch * 2 + 2
            if ((p - std) % 2 != 0) p++
            if (p + 2 > at) continue
            val cbUpx = le16(table, p)
            if (cbUpx < 2 || p + 2 + cbUpx > at) continue
            var ilfo: Int? = null; var ilvl: Int? = null
            sprms(table, p + 4, p + 2 + cbUpx) { sprm, op ->
                when (sprm) {
                    0x460B -> ilfo = op
                    0x260A -> ilvl = op
                }
            }
            out[istd] = StyleList(ilfo, ilvl, base)
        }
        return out
    }

    /** The list a paragraph of style [istd] takes: its instance and level, from its style or the ones that style is based on. */
    fun ofStyle(styles: Map<Int, StyleList>, istd: Int): Pair<Int, Int?>? {
        var s = istd
        var ilvl: Int? = null
        repeat(10) {
            val st = styles[s] ?: return null
            if (ilvl == null) ilvl = st.ilvl
            st.ilfo?.let { return it to ilvl }
            if (st.base == 0xFFF || st.base == s) return null
            s = st.base
        }
        return null
    }
}
