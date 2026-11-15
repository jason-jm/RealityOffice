package com.therealityteam.aeropages.format.doc

/**
 * A Word 97 document's style sheet ([MS-DOC] 2.9.271 STSH): each style's name, kind, the style it is
 * based on, and the paragraph and character properties it sets (its UPXs' grpprls). A paragraph's or a
 * run's formatting is its style's — through the chain of styles it is based on — under its own.
 */
internal object DocStyles {
    /** A style ([MS-DOC] 2.9.260 STD): [sti] its built-in identity (0 Normal, 1–9 heading 1–9 …), [stk] 1 paragraph / 2 character. */
    class Std(val istd: Int, val name: String, val sti: Int, val stk: Int, val base: Int, val papx: ByteArray?, val chpx: ByteArray?)

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    fun read(table: ByteArray, fcStshf: Int, lcbStshf: Int): Map<Int, Std> {
        if (fcStshf < 0 || lcbStshf < 4 || fcStshf + lcbStshf > table.size) return emptyMap()
        val cbStshi = le16(table, fcStshf)
        val shi = fcStshf + 2
        if (cbStshi < 4) return emptyMap()
        val cstd = le16(table, shi)
        val cbBase = le16(table, shi + 2)
        val end = fcStshf + lcbStshf
        var at = shi + cbStshi
        val out = HashMap<Int, Std>()
        for (istd in 0 until cstd) {
            if (at + 2 > end) break
            val cb = le16(table, at)
            val std = at + 2
            at = std + cb
            if (cb == 0 || at > end || cb < cbBase + 2) continue
            val sti = le16(table, std) and 0xFFF
            val stk = le16(table, std + 2) and 0xF
            val base = le16(table, std + 2) ushr 4
            // The name: a length-prefixed UTF-16 string with a terminating zero.
            var p = std + cbBase
            if (p + 2 > at) continue
            val cch = le16(table, p)
            val name = buildString { for (k in 0 until cch) { val c = p + 2 + k * 2; if (c + 1 >= at) break; append(le16(table, c).toChar()) } }
            p += 2 + cch * 2 + 2
            // The UPXs, each length-prefixed and padded to an even offset from the STD's start.
            fun upx(): ByteArray? {
                if ((p - std) % 2 != 0) p++
                if (p + 2 > at) return null
                val n = le16(table, p)
                if (n < 0 || p + 2 + n > at) return null
                val bytes = table.copyOfRange(p + 2, p + 2 + n)
                p += 2 + n
                return bytes
            }
            when (stk) {
                1 -> {
                    // A paragraph style: its PAPX (a style index, then its sprms), then its CHPX.
                    val papx = upx()?.let { if (it.size >= 2) it.copyOfRange(2, it.size) else null }
                    val chpx = upx()
                    out[istd] = Std(istd, name, sti, stk, base, papx, chpx)
                }
                2 -> out[istd] = Std(istd, name, sti, stk, base, null, upx())
            }
        }
        return out
    }

    /** [istd]'s chain from the root style down to it (cycle-safe): what applies first comes first. */
    fun chain(styles: Map<Int, Std>, istd: Int): List<Std> {
        val out = ArrayList<Std>()
        var s = styles[istd]
        val seen = HashSet<Int>()
        while (s != null && seen.add(s.istd) && out.size < 12) {
            out.add(s)
            s = if (s.base == 0xFFF) null else styles[s.base]
        }
        return out.asReversed()
    }
}
