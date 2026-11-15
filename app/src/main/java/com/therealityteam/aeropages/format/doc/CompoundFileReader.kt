package com.therealityteam.aeropages.format.doc

/**
 * A minimal reader for the Compound File Binary (CFB / OLE2) container format
 * that legacy `.doc`, `.xls`, `.ppt` files are stored in.
 *
 * It implements just enough of [MS-CFB] to enumerate top-level streams and read
 * their full bytes: header parsing, the FAT and mini-FAT sector chains, the
 * directory, and the mini stream. The whole file is held in memory, which is
 * fine for documents that fit comfortably on a headset.
 */
class CompoundFileReader(private val data: ByteArray) {

    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Long
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries: List<DirEntry>
    private val miniStream: ByteArray

    data class DirEntry(val name: String, val type: Int, val startSector: Int, val size: Long)

    init {
        require(data.size >= 512) { "File too small to be a CFB container" }
        require(isCfb(data)) { "Not a CFB container (bad signature)" }

        sectorSize = 1 shl u16(0x1E)
        miniSectorSize = 1 shl u16(0x20)
        miniCutoff = u32(0x38)

        val numFatSectors = u32(0x2C).toInt()
        val firstDirSector = u32(0x30).toInt()
        val firstMiniFatSector = u32(0x3C).toInt()
        val numMiniFatSectors = u32(0x40).toInt()
        val firstDifatSector = u32(0x44).toInt()
        val numDifatSectors = u32(0x48).toInt()

        // Gather all FAT sector numbers: the first 109 live in the header DIFAT,
        // the rest follow a DIFAT chain.
        val fatSectors = ArrayList<Int>(numFatSectors)
        for (i in 0 until 109) {
            val s = s32(0x4C + i * 4)
            if (s >= 0) fatSectors.add(s)
        }
        var difat = firstDifatSector
        var guard = 0
        while (difat >= 0 && difat != ENDOFCHAIN && guard++ < numDifatSectors + 1) {
            val base = sectorOffset(difat)
            val perSector = sectorSize / 4
            for (i in 0 until perSector - 1) {
                val s = s32(base + i * 4)
                if (s >= 0) fatSectors.add(s)
            }
            difat = s32(base + (perSector - 1) * 4)
        }

        // Build the FAT: concatenate the entries of every FAT sector.
        val entriesPerSector = sectorSize / 4
        fat = IntArray(fatSectors.size * entriesPerSector)
        var fi = 0
        for (sec in fatSectors) {
            val base = sectorOffset(sec)
            for (i in 0 until entriesPerSector) fat[fi++] = s32(base + i * 4)
        }

        // Read the directory chain, then parse 128-byte entries.
        val dirBytes = readFatChain(firstDirSector)
        entries = parseDirectory(dirBytes)

        // Mini-FAT chain.
        miniFat = if (firstMiniFatSector >= 0 && numMiniFatSectors > 0) {
            val mfBytes = readFatChain(firstMiniFatSector)
            IntArray(mfBytes.size / 4) { le32(mfBytes, it * 4) }
        } else IntArray(0)

        // The mini stream is the content of the root entry (type 5), stored in
        // the regular FAT, and sliced into mini sectors.
        val root = entries.firstOrNull { it.type == 5 }
        miniStream = if (root != null) readFatChain(root.startSector) else ByteArray(0)
    }

    /** All readable stream names at any level (CFB here is effectively flat for .doc). */
    fun streamNames(): List<String> = entries.filter { it.type == 2 }.map { it.name }

    /** Read the full bytes of the named stream, or null if absent. */
    fun readStream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name == name } ?: return null
        val raw = if (e.size < miniCutoff) readMiniChain(e.startSector) else readFatChain(e.startSector)
        return if (raw.size.toLong() > e.size) raw.copyOf(e.size.toInt()) else raw
    }

    // --- chains ----------------------------------------------------------------

    private fun readFatChain(start: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var sector = start
        var guard = 0
        while (sector in 0 until fat.size && sector != ENDOFCHAIN && guard++ < fat.size + 1) {
            val off = sectorOffset(sector)
            out.write(data, off, minOf(sectorSize, data.size - off))
            sector = fat[sector]
        }
        return out.toByteArray()
    }

    private fun readMiniChain(start: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var sector = start
        var guard = 0
        while (sector >= 0 && sector != ENDOFCHAIN && sector < miniFat.size && guard++ < miniFat.size + 1) {
            val off = sector * miniSectorSize
            if (off + miniSectorSize <= miniStream.size) {
                out.write(miniStream, off, miniSectorSize)
            }
            sector = miniFat[sector]
        }
        return out.toByteArray()
    }

    private fun parseDirectory(bytes: ByteArray): List<DirEntry> {
        val result = ArrayList<DirEntry>()
        var off = 0
        while (off + 128 <= bytes.size) {
            val nameLen = le16(bytes, off + 0x40)
            val type = bytes[off + 0x42].toInt() and 0xFF
            if (type != 0 && nameLen >= 2) {
                val chars = (nameLen / 2) - 1   // exclude null terminator
                val sb = StringBuilder()
                for (i in 0 until chars) {
                    val c = le16(bytes, off + i * 2)
                    if (c != 0) sb.append(c.toChar())
                }
                val start = le32(bytes, off + 0x74)
                val size = le32u(bytes, off + 0x78)
                result.add(DirEntry(sb.toString(), type, start, size))
            }
            off += 128
        }
        return result
    }

    private fun sectorOffset(sector: Int): Int = (sector + 1) * sectorSize

    // --- little-endian primitives over the backing array -----------------------

    private fun u16(off: Int): Int = (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
    private fun u32(off: Int): Long = le32u(data, off)
    private fun s32(off: Int): Int = le32(data, off)

    companion object {
        private const val ENDOFCHAIN = -2 // 0xFFFFFFFE as a signed Int

        fun isCfb(data: ByteArray): Boolean =
            data.size >= 8 &&
                data[0] == 0xD0.toByte() && data[1] == 0xCF.toByte() &&
                data[2] == 0x11.toByte() && data[3] == 0xE0.toByte() &&
                data[4] == 0xA1.toByte() && data[5] == 0xB1.toByte() &&
                data[6] == 0x1A.toByte() && data[7] == 0xE1.toByte()

        private fun le16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        private fun le32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

        private fun le32u(b: ByteArray, off: Int): Long = le32(b, off).toLong() and 0xFFFFFFFFL
    }
}
