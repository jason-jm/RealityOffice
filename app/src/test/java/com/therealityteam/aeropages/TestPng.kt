package com.therealityteam.aeropages

/** Minimal PNG headers for tests that only need a picture's size and declared resolution, not its pixels. */
internal object TestPng {
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun chunk(type: String, data: ByteArray) = be32(data.size) + type.toByteArray() + data + be32(0)

    /** A [w] × [h] PNG header — IHDR, and a pHYs chunk when [pxPerMetre] is given. */
    fun header(w: Int, h: Int, pxPerMetre: Int? = null): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            chunk("IHDR", be32(w) + be32(h) + byteArrayOf(8, 6, 0, 0, 0)) +
            (pxPerMetre?.let { chunk("pHYs", be32(it) + be32(it) + byteArrayOf(1)) } ?: ByteArray(0)) +
            chunk("IEND", ByteArray(0))

    /** A real [w] × [h] PNG — a diagonal blend from [rgb] to white — that LibreOffice can draw (header() is only a size). */
    fun blend(w: Int, h: Int, rgb: Int): ByteArray {
        val raw = java.io.ByteArrayOutputStream()
        for (y in 0 until h) {
            raw.write(0)
            for (x in 0 until w) {
                val t = (x + y).toFloat() / (w + h)
                for (shift in intArrayOf(16, 8, 0)) { val c = (rgb shr shift) and 0xFF; raw.write((c + (255 - c) * t * 0.7f).toInt()) }
            }
        }
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun put(type: String, data: ByteArray) {
            val d = java.io.DataOutputStream(out)
            d.writeInt(data.size)
            val body = type.toByteArray() + data
            d.write(body)
            d.writeInt(java.util.zip.CRC32().apply { update(body) }.value.toInt())
        }
        put("IHDR", java.nio.ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(2).put(0).put(0).put(0).array())
        put("IDAT", java.io.ByteArrayOutputStream().also { b -> java.util.zip.DeflaterOutputStream(b).use { it.write(raw.toByteArray()) } }.toByteArray())
        put("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
