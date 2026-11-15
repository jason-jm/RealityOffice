package com.therealityteam.aeropages.format.doc

import com.therealityteam.aeropages.model.DocProps
import java.nio.charset.Charset

/**
 * The properties a Word 97, Excel 97 or PowerPoint 97 file (and WPS's) keeps in its `\u0005SummaryInformation` stream
 * ([MS-OLEPS] property set): title (2), subject (3), author (4), keywords (5), comments (6), last author (8), revision
 * number (9, as text), made (12) and last saved (13) — strings in the set's code page (1) or UTF-16, times as FILETIMEs.
 */
object OleSummary {
    const val STREAM = "\u0005SummaryInformation"

    fun read(cfb: CompoundFileReader): DocProps = runCatching { cfb.readStream(STREAM)?.let { parse(it) } }.getOrNull() ?: DocProps()

    /** The properties of a SummaryInformation stream's first property set; empty ones when it cannot be read. */
    fun parse(b: ByteArray): DocProps {
        if (b.size < 48 || u16(b, 0) != 0xFFFE) return DocProps()
        val set = u32(b, 44).toInt()
        if (set < 0 || set + 8 > b.size) return DocProps()
        val count = u32(b, set + 4).toInt().coerceIn(0, 256)
        val offsets = HashMap<Int, Int>()
        for (i in 0 until count) {
            val at = set + 8 + i * 8
            if (at + 8 > b.size) break
            offsets[u32(b, at).toInt()] = set + u32(b, at + 4).toInt()
        }
        val codePage = offsets[1]?.takeIf { it + 6 <= b.size && u16(b, it) == 2 }?.let { u16(b, it + 4) } ?: 1252
        val charset = charsetOf(codePage)
        fun text(pid: Int): String {
            val at = offsets[pid] ?: return ""
            if (at + 8 > b.size) return ""
            val len = u32(b, at + 4).toInt()
            return when (u16(b, at)) {
                0x1E -> if (len <= 0 || at + 8 + len > b.size) "" else String(b, at + 8, len, if (codePage == 1200) Charsets.UTF_16LE else charset)
                0x1F -> if (len <= 0 || at + 8 + len * 2 > b.size) "" else String(b, at + 8, len * 2, Charsets.UTF_16LE)
                else -> ""
            }.trimEnd('\u0000').trim()
        }
        fun time(pid: Int): Long? {
            val at = offsets[pid] ?: return null
            if (at + 12 > b.size || u16(b, at) != 0x40) return null
            val ticks = (u32(b, at + 4) and 0xFFFFFFFFL) or (u32(b, at + 8) shl 32)
            // 100 ns since 1601; none (0) or an edit time (a small span, not a date) is no date.
            if (ticks < 116_444_736_000_000_000L) return null
            return (ticks - 116_444_736_000_000_000L) / 10_000L
        }
        return DocProps(text(2), text(3), text(4), text(5), text(6), text(8), time(12), time(13), text(9).toIntOrNull())
    }

    private fun charsetOf(cp: Int): Charset = runCatching {
        when (cp) {
            65001 -> Charsets.UTF_8
            936 -> Charset.forName("GBK")
            950 -> Charset.forName("Big5")
            932 -> Charset.forName("Shift_JIS")
            949 -> Charset.forName("EUC-KR")
            else -> Charset.forName("windows-$cp")
        }
    }.getOrDefault(Charsets.ISO_8859_1)

    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
}
