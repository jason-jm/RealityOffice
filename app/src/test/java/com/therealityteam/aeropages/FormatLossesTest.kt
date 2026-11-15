package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.Csv
import com.therealityteam.aeropages.format.FormatLosses
import com.therealityteam.aeropages.format.FormatLosses.Loss
import com.therealityteam.aeropages.format.TextImporters
import com.therealityteam.aeropages.format.XmlPull
import com.therealityteam.aeropages.format.odf.OdfNode
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Comment
import com.therealityteam.aeropages.model.MergeRegion
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.SheetState
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * What saving in a plain type (CSV, text) would drop — nothing for a file just opened from one —
 * and the count of damaged XML parts a read had to salvage.
 */
class FormatLossesTest {

    private val csv = "Region,Sales,Date\nNorth,120,2026-10-01\nSouth,90,2026-10-02\n".toByteArray()

    @Test fun aCsvJustOpenedLosesNothingAsCsv() {
        assertEquals(emptySet<Loss>(), FormatLosses.csv(Csv.toWorkbook(csv)))
    }

    @Test fun whatACsvCannotKeep() {
        fun book(edit: (Workbook) -> Unit) = Csv.toWorkbook(csv).also(edit)
        assertEquals(setOf(Loss.OTHER_SHEETS), FormatLosses.csv(book { it.sheets.add(Sheet(name = "More")) }))
        assertEquals(setOf(Loss.FORMULAS), FormatLosses.csv(book { it.sheets[0].ensure(3, 1).text = "=SUM(B2:B3)" }))
        assertEquals(setOf(Loss.FORMATTING), FormatLosses.csv(book { it.sheets[0].ensure(0, 0).format.bold = true }))
        assertEquals(setOf(Loss.CELL_EXTRAS), FormatLosses.csv(book { it.sheets[0].ensure(1, 0).comment = "check" }))
        assertEquals(setOf(Loss.CELL_EXTRAS), FormatLosses.csv(book { it.sheets[0].merges.add(MergeRegion(0, 0, 0, 1)) }))
        assertEquals("a number format shows in the CSV's text: nothing lost", emptySet<Loss>(),
            FormatLosses.csv(book { it.sheets[0].ensure(1, 1).apply { numberFormat = NumberFormat.CURRENCY_USD; decimals = 2 } }))
    }

    @Test fun aCsvHoldsTheFirstSheetShown() {
        val wb = Workbook(mutableListOf(Sheet(name = "Setup", state = SheetState.HIDDEN).also { it.ensure(0, 0).text = "=1+1" }, Sheet(name = "Data")))
        assertEquals("Data", FormatLosses.csvSheet(wb).name)
        assertEquals("the hidden sheet's formula is not the CSV's", setOf(Loss.OTHER_SHEETS), FormatLosses.csv(wb))
    }

    @Test fun aTextFileJustOpenedLosesNothingAsText() {
        val doc = TextImporters.txt(ByteArrayInputStream("First line\nSecond line\n\nAfter a gap".toByteArray()))
        assertEquals(emptySet<Loss>(), FormatLosses.text(doc))
    }

    @Test fun whatPlainTextCannotKeep() {
        fun doc(edit: (com.therealityteam.aeropages.model.Document) -> Unit) = TextImporters.txt(ByteArrayInputStream("Words".toByteArray())).also(edit)
        assertEquals(setOf(Loss.FORMATTING), FormatLosses.text(doc { (it.blocks[0] as ParagraphBlock).paragraph.runs[0].format.bold = true }))
        assertEquals(setOf(Loss.FORMATTING), FormatLosses.text(doc { it.blocks.add(ParagraphBlock(Paragraph.of("Title", pFormat = ParagraphFormat(style = ParagraphStyle.HEADING1)))) }))
        assertEquals(setOf(Loss.OBJECTS), FormatLosses.text(doc { it.blocks.add(TableBlock(Table())) }))
        assertEquals(setOf(Loss.NOTES), FormatLosses.text(doc { it.header = Paragraph.of("Report") }))
        assertEquals(setOf(Loss.NOTES), FormatLosses.text(doc { d -> d.comments.add(Comment("Ann", "check", (d.blocks[0] as ParagraphBlock).paragraph.id)) }))
        assertEquals("an empty header is nothing", emptySet<Loss>(), FormatLosses.text(doc { it.header = Paragraph.of("") }))
    }

    // --- damaged XML -------------------------------------------------------------------

    @Test fun salvagedPartsAreCountedPerRead() {
        XmlPull.takeSalvaged()
        XmlPull.parse("<a><b>fine</b></a>".toByteArray())
        assertEquals(0, XmlPull.takeSalvaged())
        XmlPull.parse("<a><b>broken</a>".toByteArray())
        OdfNode.parse("<office:document-content><table:table></office:document-content>".toByteArray())
        assertEquals(2, XmlPull.takeSalvaged())
        assertEquals("taking resets the count", 0, XmlPull.takeSalvaged())
    }

    @Test fun aDamagedWorkbookIsSeenAsRepaired() {
        val good = ByteArrayOutputStream().also { XlsxWriter.write(Workbook(mutableListOf(Sheet(name = "S").also { it.ensure(0, 0).text = "ok"; it.ensure(1, 0).text = "2" })), it) }.toByteArray()
        val dir = java.nio.file.Files.createTempDirectory("aero-repair").toFile().apply { deleteOnExit() }
        val goodFile = File(dir, "good.xlsx").apply { writeBytes(good) }
        XmlPull.takeSalvaged()
        com.therealityteam.aeropages.ui.readWorkbook(goodFile.path)
        assertEquals("a sound file is not repaired", 0, XmlPull.takeSalvaged())
        // The same workbook with its sheet's last end tags cut off, as a truncated download would leave it.
        val damaged = ByteArrayOutputStream()
        ZipOutputStream(damaged).use { z ->
            ZipInputStream(ByteArrayInputStream(good)).use { zin ->
                generateSequence { zin.nextEntry }.forEach { e ->
                    var bytes = zin.readBytes()
                    if (e.name == "xl/worksheets/sheet1.xml") bytes = bytes.toString(Charsets.UTF_8).substringBefore("</sheetData>").toByteArray()
                    z.putNextEntry(ZipEntry(e.name)); z.write(bytes); z.closeEntry()
                }
            }
        }
        val damagedFile = File(dir, "damaged.xlsx").apply { writeBytes(damaged.toByteArray()) }
        val wb = com.therealityteam.aeropages.ui.readWorkbook(damagedFile.path)
        assertTrue("salvaged", XmlPull.takeSalvaged() > 0)
        assertEquals("what could be read is there", "ok", wb.sheets[0].cell(0, 0)?.text)
    }

    /** Read [f] as the module that opens it does, and how many of its parts had to be salvaged. */
    private fun salvagedReading(f: File): Int {
        XmlPull.takeSalvaged()
        when (f.extension.lowercase()) {
            "xlsx", "xlsm", "ods", "xls", "csv" -> com.therealityteam.aeropages.ui.readWorkbook(f.path)
            "pptx", "pptm", "odp", "ppt" -> com.therealityteam.aeropages.ui.readDeck(f.path)
            else -> com.therealityteam.aeropages.format.DocumentRepository.open(f.path)
        }
        return XmlPull.takeSalvaged()
    }

    @Test fun noFileTheAppWritesReadsAsDamaged() {
        val dir = File(System.getProperty("java.io.tmpdir"), "aero-salvage-samples").apply { deleteRecursively(); mkdirs() }
        SampleFileMakerTest().writeTo(dir)
        val damaged = dir.listFiles()!!.filter { it.isFile }.sortedBy { it.name }.mapNotNull { f -> salvagedReading(f).takeIf { it > 0 }?.let { "${f.name}: $it" } }
        assertTrue("files the app writes must read without salvaging anything:\n" + damaged.joinToString("\n"), damaged.isEmpty())
    }

    /** Every document under `-Daero.scanDir=<folder>` read as its module reads it; the salvage counts go to `aero-salvage-scan.txt` in the temp folder. */
    @Test fun salvageInAFolder() {
        val dir = System.getProperty("aero.scanDir")?.let { File(it) }
        org.junit.Assume.assumeTrue(dir != null && dir.isDirectory)
        val exts = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp")
        val lines = dir!!.walkTopDown().filter { it.isFile && it.extension.lowercase() in exts }.sortedBy { it.path }
            .map { f -> "${f.relativeTo(dir).path}: ${runCatching { salvagedReading(f) }.getOrElse { "failed: $it" }}" }.toList()
        File(System.getProperty("java.io.tmpdir"), "aero-salvage-scan.txt").writeText(lines.joinToString("\n"))
    }

    @Test fun boldInACsvSheetIsFormatting() {
        // CharacterFormat is compared as a value: a default one is no formatting.
        val wb = Csv.toWorkbook(csv)
        wb.sheets[0].ensure(0, 0).format.apply { bold = false; italic = false }
        assertEquals(CharacterFormat(), wb.sheets[0].cell(0, 0)!!.format)
        assertEquals(emptySet<Loss>(), FormatLosses.csv(wb))
    }
}
