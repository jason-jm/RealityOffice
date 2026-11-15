package com.therealityteam.aeropages.format

/**
 * Pixel size of a PNG / JPEG / GIF read from its header bytes — or an SVG's declared size — no
 * bitmap decoder needed, so it works while parsing on a background thread and in plain JVM tests.
 */
object ImageSize {
    /** `width to height` in pixels, or null when the bytes are not a recognised image. */
    fun of(data: ByteArray): Pair<Float, Float>? {
        if (data.size < 10) return null
        fun u8(i: Int) = data[i].toInt() and 0xFF
        fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun be32(i: Int) = (be16(i) shl 16) or be16(i + 2)
        fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        val size: Pair<Int, Int>? = when {
            // PNG: 8-byte signature, then the IHDR chunk (width / height at 16..23).
            data.size >= 24 && u8(0) == 0x89 && u8(1) == 'P'.code && u8(2) == 'N'.code && u8(3) == 'G'.code -> be32(16) to be32(20)
            // GIF87a / GIF89a: little-endian logical screen size right after the signature.
            u8(0) == 'G'.code && u8(1) == 'I'.code && u8(2) == 'F'.code -> le16(6) to le16(8)
            // JPEG: walk the marker segments to the first start-of-frame (SOF0..SOF15 minus DHT/JPG/DAC).
            u8(0) == 0xFF && u8(1) == 0xD8 -> {
                var i = 2
                var found: Pair<Int, Int>? = null
                while (found == null && i + 9 < data.size) {
                    if (u8(i) != 0xFF) { i++; continue }
                    val marker = u8(i + 1)
                    when {
                        marker == 0xFF -> i++                                            // fill byte
                        marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC ->
                            found = be16(i + 7) to be16(i + 5)                            // width, height
                        marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> i += 2 // standalone markers
                        else -> i += 2 + be16(i + 2)
                    }
                }
                found
            }
            else -> null
        }
        return size?.takeIf { it.first > 0 && it.second > 0 }?.let { it.first.toFloat() to it.second.toFloat() }
            ?: com.therealityteam.aeropages.format.svg.SvgReader.size(data)   // an SVG: the size it declares, in CSS pixels
            ?: com.therealityteam.aeropages.format.metafile.Metafiles.sizePx(data)   // an EMF or a WMF: its frame, at 96 dpi
    }

    /**
     * The resolution the picture declares — a PNG's `pHYs` chunk (per metre), a JPEG's JFIF density
     * (per inch or cm) — as dots per inch, or null when it declares none (readers then assume 96).
     */
    fun dpi(data: ByteArray): Float? {
        fun u8(i: Int) = data[i].toInt() and 0xFF
        fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun be32(i: Int) = (be16(i).toLong() shl 16) or be16(i + 2).toLong()
        if (data.size >= 24 && u8(0) == 0x89 && u8(1) == 'P'.code) {
            var i = 8
            while (i + 12 <= data.size) {
                val len = be32(i).toInt()
                val type = String(data, i + 4, 4, Charsets.US_ASCII)
                if (type == "pHYs" && len >= 9 && i + 17 <= data.size) {
                    val perMetre = be32(i + 8)
                    return if (u8(i + 16) == 1 && perMetre > 0) (perMetre * 0.0254f) else null
                }
                if (type == "IDAT" || type == "IEND" || len < 0) return null
                i += 12 + len
            }
            return null
        }
        if (data.size >= 18 && u8(0) == 0xFF && u8(1) == 0xD8 && u8(2) == 0xFF && u8(3) == 0xE0 &&
            String(data, 6, 4, Charsets.US_ASCII) == "JFIF") {
            val units = u8(13)
            val x = be16(14)
            return when {
                x <= 0 -> null
                units == 1 -> x.toFloat()
                units == 2 -> x * 2.54f
                else -> null
            }
        }
        return null
    }

    /** The picture's natural size in points: its pixels at the resolution it declares (96 dpi when none). */
    fun naturalPt(data: ByteArray): Pair<Float, Float>? {
        val (w, h) = of(data) ?: return null
        val scale = 72f / (dpi(data)?.takeIf { it >= 1f } ?: 96f)
        return w * scale to h * scale
    }
}
