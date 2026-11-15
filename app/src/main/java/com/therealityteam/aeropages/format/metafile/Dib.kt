package com.therealityteam.aeropages.format.metafile

import kotlin.math.abs

/** A device-independent bitmap as metafiles carry it: its BITMAPINFO (header, masks, colour table) and its bits. */
internal object Dib {

    /** The BITMAPINFO's length at the start of a packed DIB (its header, bit-field masks and colour table). */
    fun infoSize(dib: ByteArray): Int {
        if (dib.size < 12) return dib.size
        val headerSize = u32(dib, 0)
        if (headerSize == 12) {
            // BITMAPCOREHEADER: its colours three bytes each.
            val bitCount = u16(dib, 10)
            return 12 + (if (bitCount in 1..8) (1 shl bitCount) * 3 else 0)
        }
        if (dib.size < 36) return dib.size
        val bitCount = u16(dib, 14); val compression = u32(dib, 16); val used = u32(dib, 32)
        val masks = if (headerSize == 40 && compression == 3) 12 else if (headerSize == 40 && compression == 6) 16 else 0
        val colours = if (used in 1..256) used else if (bitCount in 1..8) 1 shl bitCount else 0
        return headerSize + masks + colours * 4
    }

    /** A DIB as a file a decoder reads: the PNG or JPEG it holds, else a BMP (a file header before it). */
    fun file(info: ByteArray, bits: ByteArray): ByteArray? {
        if (info.size < 12) return null
        if (u32(info, 0) >= 40 && info.size >= 20) {
            val c = u32(info, 16)
            if (c == 4 || c == 5) return bits                 // BI_JPEG, BI_PNG
        }
        val out = ByteArray(14 + info.size + bits.size)
        out[0] = 'B'.code.toByte(); out[1] = 'M'.code.toByte()
        put32(out, 2, out.size); put32(out, 10, 14 + info.size)
        info.copyInto(out, 14); bits.copyInto(out, 14 + info.size)
        return out
    }

    /** A packed DIB (its info then its bits) as a file. */
    fun file(dib: ByteArray): ByteArray? {
        if (dib.size < 12) return null
        val n = infoSize(dib).coerceAtMost(dib.size)
        return file(dib.copyOfRange(0, n), dib.copyOfRange(n, dib.size))
    }

    /**
     * The part of the bitmap a blit takes — left, top, width, height as a decoder lays it out, top row
     * first — or null for the whole of it. [fromFoot]: the source's y counts from a bottom-up bitmap's
     * foot, as a DIB's own origin is (StretchDIBits); a blit from a device context counts from the top.
     */
    fun source(info: ByteArray, x: Int, y: Int, w: Int, h: Int, fromFoot: Boolean): IntArray? {
        if (info.size < 12) return null
        val core = u32(info, 0) == 12
        val bw = if (core) u16(info, 4) else u32(info, 4)
        val rawH = if (core) i16(info, 6) else u32(info, 8)
        val bh = abs(rawH)
        if (w <= 0 || h <= 0 || bw <= 0 || bh <= 0) return null
        if (x <= 0 && y <= 0 && w >= bw && h >= bh) return null
        val top = if (fromFoot && rawH > 0) bh - y - h else y
        return intArrayOf(x.coerceAtLeast(0), top.coerceAtLeast(0), w.coerceAtMost(bw), h.coerceAtMost(bh))
    }

    private fun put32(b: ByteArray, at: Int, v: Int) { for (k in 0 until 4) b[at + k] = (v ushr (8 * k)).toByte() }
}
