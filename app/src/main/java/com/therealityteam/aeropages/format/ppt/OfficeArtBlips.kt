package com.therealityteam.aeropages.format.ppt

import com.therealityteam.aeropages.format.ppt.PptReader.Rec
import com.therealityteam.aeropages.format.ppt.PptReader.u16
import com.therealityteam.aeropages.format.ppt.PptReader.u32

/**
 * OfficeArt pictures (BLIP records, [MS-ODRAW] 2.2.23): their bytes as the files they are — a JPEG,
 * PNG or TIFF as stored, a DIB given its file header, a metafile inflated (a WMF given its placeable
 * header) — for PowerPoint's .ppt and Word's .doc alike.
 */
internal object OfficeArtBlips {
    /** A blip record's picture: its bytes as a file of its kind (a DIB given its file header; a metafile inflated) and its type. */
    fun blipData(p: ByteArray, r: Rec): Pair<ByteArray, String>? {
        val twoUids = r.inst and 0x1 != 0
        return when (r.type) {
            0xF01E, 0xF01D, 0xF02A, 0xF01F, 0xF029 -> {
                val off = r.start + 16 + (if (twoUids) 16 else 0) + 1
                if (off >= r.end) return null
                val bytes = p.copyOfRange(off, r.end)
                when (r.type) {
                    0xF01E -> bytes to "image/png"
                    0xF01F -> dibToBmp(bytes)?.let { it to "image/bmp" }
                    0xF029 -> bytes to "image/tiff"
                    else -> bytes to "image/jpeg"
                }
            }
            0xF01A, 0xF01B -> {
                val off = r.start + 16 + (if (twoUids) 16 else 0)
                if (off + 34 > r.end) return null
                val compressed = p[off + 32].toInt() and 0xFF == 0
                val raw = p.copyOfRange(off + 34, r.end)
                val bytes = if (compressed) runCatching { java.util.zip.InflaterInputStream(raw.inputStream()).readBytes() }.getOrNull() ?: return null else raw
                // A WMF without its placeable header: given one, as files carry it.
                if (r.type == 0xF01B) placeableWmf(bytes, p, off) to "image/x-wmf" else bytes to "image/x-emf"
            }
            else -> null
        }
    }

    /** A DIB (BITMAPINFOHEADER and bits) as a .bmp file. */
    fun dibToBmp(dib: ByteArray): ByteArray? {
        if (dib.size < 40) return null
        val headerSize = u32(dib, 0); val bitCount = u16(dib, 14); val colorsUsed = u32(dib, 32)
        val palette = (if (colorsUsed != 0) colorsUsed else if (bitCount <= 8) 1 shl bitCount else 0) * 4
        val out = ByteArray(14 + dib.size)
        out[0] = 'B'.code.toByte(); out[1] = 'M'.code.toByte()
        fun put32(o: Int, v: Int) { for (k in 0 until 4) out[o + k] = (v ushr (8 * k)).toByte() }
        put32(2, out.size); put32(10, 14 + headerSize + palette)
        System.arraycopy(dib, 0, out, 14, dib.size)
        return out
    }

    /** A WMF given the placeable header (key, bounds from the blip's metafile header, inch 1440) when it has none. */
    fun placeableWmf(wmf: ByteArray, p: ByteArray, headerAt: Int): ByteArray {
        if (wmf.size >= 4 && u32(wmf, 0) == 0x9AC6CDD7.toInt()) return wmf
        val l = u32(p, headerAt + 4); val t = u32(p, headerAt + 8); val rr = u32(p, headerAt + 12); val bb = u32(p, headerAt + 16)
        val head = ByteArray(22)
        fun put16(o: Int, v: Int) { head[o] = v.toByte(); head[o + 1] = (v ushr 8).toByte() }
        put16(0, 0xCDD7); put16(2, 0x9AC6); put16(6, l); put16(8, t); put16(10, rr); put16(12, bb); put16(14, 1440)
        var sum = 0; for (k in 0 until 10) sum = sum xor u16(head, k * 2)
        put16(20, sum)
        return head + wmf
    }
}
