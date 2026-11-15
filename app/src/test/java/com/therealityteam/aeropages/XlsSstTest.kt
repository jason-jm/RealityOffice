package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.xls.XlsReader
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/** BIFF8 shared-string table parsing across CONTINUE record boundaries. */
class XlsSstTest {

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Int) = le16(v and 0xFFFF) + le16((v shr 16) and 0xFFFF)

    @Test fun stringSplitMidCharacterArrayAcrossContinue() {
        // SST header (cstTotal, cstUnique), "abc" compressed, then "wxyz" wide split after "wx".
        val seg1 = ByteArrayOutputStream().apply {
            write(le32(2)); write(le32(2))
            write(le16(3)); write(0x00); write("abc".toByteArray(Charsets.ISO_8859_1))
            write(le16(4)); write(0x01); write("wx".toByteArray(Charsets.UTF_16LE))
        }.toByteArray()
        val seg2 = ByteArrayOutputStream().apply { write(0x01); write("yz".toByteArray(Charsets.UTF_16LE)) }.toByteArray()
        val stream = seg1 + seg2
        val strings = XlsReader.parseSstSegments(stream, listOf(0 until seg1.size, seg1.size until stream.size))
        assertEquals(listOf("abc", "wxyz"), strings)
    }

    @Test fun continuationMaySwitchToCompressedAndStringsMayStartInContinue() {
        // "héllo" wide, split after "hé" with the rest continued as 8-bit; then "next" wholly inside the CONTINUE.
        val seg1 = ByteArrayOutputStream().apply {
            write(le32(2)); write(le32(2))
            write(le16(5)); write(0x01); write("hé".toByteArray(Charsets.UTF_16LE))
        }.toByteArray()
        val seg2 = ByteArrayOutputStream().apply {
            write(0x00); write("llo".toByteArray(Charsets.ISO_8859_1))
            write(le16(4)); write(0x00); write("next".toByteArray(Charsets.ISO_8859_1))
        }.toByteArray()
        val stream = seg1 + seg2
        assertEquals(listOf("héllo", "next"), XlsReader.parseSstSegments(stream, listOf(0 until seg1.size, seg1.size until stream.size)))
    }

    @Test fun richTextRunsAreSkippedEvenWhenTheySpanRecords() {
        // "ab" with 2 formatting runs (8 bytes) that straddle the boundary; then "c".
        val seg1 = ByteArrayOutputStream().apply {
            write(le32(2)); write(le32(2))
            write(le16(2)); write(0x08); write(le16(2)); write("ab".toByteArray(Charsets.ISO_8859_1)); write(byteArrayOf(0, 0, 0))
        }.toByteArray()
        val seg2 = ByteArrayOutputStream().apply { write(byteArrayOf(0, 0, 0, 0, 0)); write(le16(1)); write(0x00); write("c".toByteArray()) }.toByteArray()
        val stream = seg1 + seg2
        assertEquals(listOf("ab", "c"), XlsReader.parseSstSegments(stream, listOf(0 until seg1.size, seg1.size until stream.size)))
    }
}
