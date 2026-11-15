package com.therealityteam.aeropages

/**
 * A minimal compound file (OLE2 / CFB) for tests: up to three streams, each padded to 4096 bytes or
 * more so none lives in the mini stream; FAT in sector 0, the directory in sector 1, the streams after.
 */
internal object TestCfb {

    fun build(streams: Map<String, ByteArray>): ByteArray {
        require(streams.size in 1..3) { "one directory sector holds the root and three streams" }
        val sec = 512
        val padded = streams.mapValues { (_, b) -> if (b.size >= 4096) b else b + ByteArray(4096 - b.size) }
        val counts = padded.values.map { (it.size + sec - 1) / sec }
        val out = ByteArray(sec * (3 + counts.sum()))
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()).copyInto(out)
        out.put16(0x18, 0x3E); out.put16(0x1A, 3); out.put16(0x1C, 0xFFFE); out.put16(0x1E, 9); out.put16(0x20, 6)
        out.put32(0x2C, 1); out.put32(0x30, 1); out.put32(0x38, 4096); out.put32(0x3C, -2); out.put32(0x40, 0); out.put32(0x44, -2); out.put32(0x48, 0)
        out.put32(0x4C, 0); for (i in 1 until 109) out.put32(0x4C + i * 4, -1)
        val fat = sec
        for (i in 0 until 128) out.put32(fat + i * 4, -1)
        out.put32(fat, -3); out.put32(fat + 4, -2)
        val dir = sec * 2
        fun entry(idx: Int, name: String, type: Int, start: Int, size: Int, child: Int = -1, right: Int = -1) {
            val b = dir + idx * 128
            name.forEachIndexed { k, ch -> out.put16(b + k * 2, ch.code) }
            out.put16(b + 0x40, (name.length + 1) * 2); out[b + 0x42] = type.toByte(); out[b + 0x43] = 1
            out.put32(b + 0x44, -1); out.put32(b + 0x48, right); out.put32(b + 0x4C, child); out.put32(b + 0x74, start); out.put32(b + 0x78, size)
        }
        entry(0, "Root Entry", 5, -2, 0, child = 1)
        var sector = 2
        padded.entries.forEachIndexed { i, (name, bytes) ->
            val n = counts[i]
            for (k in 0 until n) out.put32(fat + (sector + k) * 4, if (k == n - 1) -2 else sector + k + 1)
            entry(i + 1, name, 2, sector, bytes.size, right = if (i + 1 < padded.size) i + 2 else -1)
            bytes.copyInto(out, sec * (sector + 1))
            sector += n
        }
        return out
    }

    fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    fun le32(v: Int) = le16(v and 0xFFFF) + le16((v shr 16) and 0xFFFF)

    private fun ByteArray.put16(at: Int, v: Int) { this[at] = (v and 0xFF).toByte(); this[at + 1] = ((v shr 8) and 0xFF).toByte() }
    private fun ByteArray.put32(at: Int, v: Int) { put16(at, v and 0xFFFF); put16(at + 2, (v shr 16) and 0xFFFF) }
}
