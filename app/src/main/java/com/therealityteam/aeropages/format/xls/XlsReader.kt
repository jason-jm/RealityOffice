package com.therealityteam.aeropages.format.xls

import com.therealityteam.aeropages.format.doc.CompoundFileReader
import com.therealityteam.aeropages.model.FormatCode
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.SheetState
import com.therealityteam.aeropages.model.Workbook

/**
 * Best-effort reader for the legacy binary .xls (Excel 97-2003, BIFF8 in an OLE2/CFB
 * container). Parses the "Workbook" stream's records — SST shared strings + the common
 * cell records (LABELSST/LABEL/NUMBER/RK/MULRK and FORMULA cached numbers) — splitting
 * sheets on worksheet BOF records, each named (and hidden, and its tab coloured) as the
 * workbook's BOUNDSHEET and the sheet's SHEETEXT records say. The SST may spill into CONTINUE
 * records (any real workbook with more than ~8 KB of text does), which are stitched back together.
 * Styles/formulas-as-text aren't decoded (text+numbers).
 */
object XlsReader {

    private const val BOF = 0x0809
    private const val BOUNDSHEET = 0x0085
    private const val SHEETEXT = 0x0862
    private const val SST = 0x00FC
    private const val LABELSST = 0x00FD
    private const val LABEL = 0x0204
    private const val NUMBER = 0x0203
    private const val RK = 0x027E
    private const val MULRK = 0x00BD
    private const val FORMULA = 0x0006
    private const val CONTINUE = 0x003C

    fun read(bytes: ByteArray): Workbook {
        val cfb = CompoundFileReader(bytes)
        // No workbook stream: not a workbook (a blank one could be saved over the file).
        val stream = cfb.readStream("Workbook") ?: cfb.readStream("Book") ?: throw IllegalArgumentException("Not an Excel workbook: no Workbook stream")
        val sst = ArrayList<String>()
        val sheets = ArrayList<Sheet>()
        var cur: Sheet? = null
        var maxRow = 0; var maxCol = 0

        fun put(row: Int, col: Int, text: String) {
            val s = cur ?: Sheet().also { sheets.add(it); cur = it }
            if (text.isEmpty()) return
            s.ensure(row, col).text = text
            if (row + 4 > s.rowCount) s.rowCount = row + 4
            if (col + 2 > s.colCount) s.colCount = col + 2
        }

        // The workbook's sheets (BOUNDSHEET): name and whether it shows, by the stream position of its
        // BOF; the worksheets' ones in order too, for a file whose positions do not match.
        val bound = HashMap<Int, Pair<String, SheetState>>()
        val worksheetsInOrder = ArrayList<Pair<String, SheetState>>()
        var off = 0
        var seenGlobals = false
        while (off + 4 <= stream.size) {
            val type = u16(stream, off)
            val len = u16(stream, off + 2)
            val data = off + 4
            if (data + len > stream.size) break
            var next = data + len
            when (type) {
                BOF -> {
                    val dt = if (len >= 4) u16(stream, data + 2) else 0
                    if (dt == 0x0010) { // worksheet
                        val (name, state) = bound[off] ?: worksheetsInOrder.getOrNull(sheets.size) ?: ("Sheet${sheets.size + 1}" to SheetState.VISIBLE)
                        cur = Sheet(name = name).also { it.state = state; sheets.add(it) }
                    }
                    else seenGlobals = true
                }
                // lbPlyPos, hsState (0 shown, 1 hidden, 2 very hidden), dt (0 = worksheet), then a ShortXLUnicodeString name.
                BOUNDSHEET -> if (len >= 8) {
                    val state = when (stream[data + 4].toInt() and 0x03) { 1 -> SheetState.HIDDEN; 2 -> SheetState.VERY_HIDDEN; else -> SheetState.VISIBLE }
                    val cch = stream[data + 6].toInt() and 0xFF
                    val wide = (stream[data + 7].toInt() and 0x01) != 0
                    val n = cch * (if (wide) 2 else 1)
                    val name = if (data + 8 + n <= data + len) String(stream, data + 8, n, if (wide) Charsets.UTF_16LE else Charsets.ISO_8859_1) else ""
                    if (name.isNotEmpty()) {
                        bound[u32(stream, data)] = name to state
                        if (stream[data + 5].toInt() == 0) worksheetsInOrder.add(name to state)
                    }
                }
                // The tab's colour (icvPlain, after the 12-byte future-record header and cb): a palette index, 0x7F for none.
                SHEETEXT -> if (len >= 20) {
                    val icv = stream[data + 16].toInt() and 0x7F
                    if (icv in 8..63) cur?.tabColor = FormatCode.paletteColor(icv - 7)
                }
                SST -> {
                    // Gather the SST record plus every CONTINUE that follows it, then parse them as one.
                    val segs = ArrayList<IntRange>()
                    segs.add(data until data + len)
                    while (next + 4 <= stream.size && u16(stream, next) == CONTINUE) {
                        val l = u16(stream, next + 2)
                        if (next + 4 + l > stream.size) break
                        segs.add(next + 4 until next + 4 + l)
                        next += 4 + l
                    }
                    sst.addAll(parseSstSegments(stream, segs))
                }
                LABELSST -> { val r = u16(stream, data); val c = u16(stream, data + 2); val isst = u32(stream, data + 6); put(r, c, sst.getOrElse(isst) { "" }) }
                LABEL -> { val r = u16(stream, data); val c = u16(stream, data + 2); put(r, c, readShortXLUnicode(stream, data + 6, data + len)) }
                NUMBER -> { val r = u16(stream, data); val c = u16(stream, data + 2); put(r, c, numStr(Double.fromBits(u64(stream, data + 6)))) }
                RK -> { val r = u16(stream, data); val c = u16(stream, data + 2); put(r, c, numStr(decodeRk(u32(stream, data + 6)))) }
                MULRK -> {
                    val r = u16(stream, data); val cFirst = u16(stream, data + 2)
                    val cLast = u16(stream, data + len - 2)
                    var p = data + 4; var c = cFirst
                    while (c <= cLast && p + 6 <= data + len) { put(r, c, numStr(decodeRk(u32(stream, p + 2)))); p += 6; c++ }
                }
                FORMULA -> {
                    val r = u16(stream, data); val c = u16(stream, data + 2)
                    // result bytes 6..13; if bytes 12-13 == 0xFFFF it's non-numeric (string/bool/err) → skip.
                    if (u16(stream, data + 12) != 0xFFFF) put(r, c, numStr(Double.fromBits(u64(stream, data + 6))))
                }
            }
            off = next
        }
        if (sheets.isEmpty()) sheets.add(Sheet())
        sheets.forEach { it.rowCount = maxOf(it.rowCount, 20); it.colCount = maxOf(it.colCount, 8) }
        return Workbook(sheets).also { it.props = com.therealityteam.aeropages.format.doc.OleSummary.read(cfb) }
    }

    /**
     * Parse the SST body spread over [segs] (the SST record followed by its CONTINUE records).
     * Per [MS-XLS] a string's character array may be split across records, in which case the
     * continuation starts with a fresh option byte saying whether the rest is 16-bit; string
     * headers and rich-text / extended data never carry that byte.
     */
    internal fun parseSstSegments(b: ByteArray, segs: List<IntRange>): List<String> {
        val out = ArrayList<String>()
        if (segs.isEmpty()) return out
        val cur = SegCursor(b, segs)
        if (cur.remaining() < 8) return out
        cur.skip(4)                       // cstTotal
        val unique = cur.u32()
        var n = 0
        while (n < unique && cur.remaining() >= 3) {
            val cch = cur.u16()
            val flags = cur.u8()
            val runs = if (flags and 0x08 != 0) cur.u16() else 0
            val cbExt = if (flags and 0x04 != 0) cur.u32() else 0
            out.add(cur.readChars(cch, flags and 0x01 != 0))
            cur.skip(runs * 4 + cbExt)
            n++
        }
        return out
    }

    /** Sequential reader over record segments, aware of the option byte at a mid-string CONTINUE boundary. */
    private class SegCursor(private val b: ByteArray, private val segs: List<IntRange>) {
        private var seg = 0
        private var pos = segs[0].first

        private fun settle() { while (seg < segs.size && pos > segs[seg].last) { seg++; if (seg < segs.size) pos = segs[seg].first } }
        fun remaining(): Int {
            settle()
            if (seg >= segs.size) return 0
            var r = segs[seg].last + 1 - pos
            for (k in seg + 1 until segs.size) r += segs[k].last + 1 - segs[k].first
            return r
        }
        fun u8(): Int { settle(); if (seg >= segs.size) return 0; return b[pos++].toInt() and 0xFF }
        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Int = u16() or (u16() shl 16)
        fun skip(n: Int) {
            var left = n
            while (left > 0) { settle(); if (seg >= segs.size) return; val take = minOf(left, segs[seg].last + 1 - pos); pos += take; left -= take }
        }
        fun readChars(cch: Int, wide0: Boolean): String {
            val sb = StringBuilder(cch)
            var wide = wide0
            var left = cch
            while (left > 0) {
                settle()
                if (seg >= segs.size) break
                // Entering a CONTINUE record mid-string: its first byte re-declares the character width.
                if (seg > 0 && pos == segs[seg].first) wide = (b[pos++].toInt() and 0x01) != 0
                val perChar = if (wide) 2 else 1
                val n = minOf(left, (segs[seg].last + 1 - pos) / perChar)
                if (n <= 0) { pos = segs[seg].last + 1; continue }
                if (wide) for (i in 0 until n) { sb.append(((b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8)).toChar()); pos += 2 }
                else for (i in 0 until n) { sb.append((b[pos].toInt() and 0xFF).toChar()); pos++ }
                left -= n
            }
            return sb.toString()
        }
    }

    /** Short XLUnicodeString (used by LABEL): 2-byte cch, 1-byte grbit, then chars. */
    private fun readShortXLUnicode(b: ByteArray, p0: Int, end: Int): String {
        if (p0 + 3 > end) return ""
        val cch = u16(b, p0); val is16 = (b[p0 + 2].toInt() and 0x01) != 0
        val start = p0 + 3; val n = cch * (if (is16) 2 else 1)
        if (start + n > end) return ""
        return String(b, start, n, if (is16) Charsets.UTF_16LE else Charsets.ISO_8859_1)
    }

    private fun decodeRk(rk: Int): Double {
        val div100 = rk and 0x01 != 0
        val isInt = rk and 0x02 != 0
        var v = if (isInt) (rk shr 2).toDouble() else Double.fromBits((rk.toLong() and 0xFFFFFFFCL) shl 32)
        if (div100) v /= 100.0
        return v
    }

    private fun numStr(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    private fun u64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
}
