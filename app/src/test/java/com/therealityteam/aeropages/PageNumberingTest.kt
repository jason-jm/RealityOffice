package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.HeaderFooters
import com.therealityteam.aeropages.model.NumFormat
import com.therealityteam.aeropages.model.PageSetup
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** A section's page numbers: its own start and format (a report's front matter in i, ii …, its body from 1). */
class PageNumberingTest {

    @Test
    fun sectionsNumberTheirPages() {
        val front = PageSetup(pageNumberFormat = NumFormat.LOWER_ROMAN, pageNumberStart = 1)
        val body = PageSetup(pageNumberStart = 1)
        val annex = PageSetup(pageNumberFormat = NumFormat.UPPER_LETTER)
        assertEquals(listOf("i", "ii", "iii", "1", "2", "C", "D"), HeaderFooters.pageNumbers(listOf(front, front, front, body, body, annex, annex)))
        // A document saying nothing: 1, 2, 3.
        val plain = PageSetup()
        assertEquals(listOf("1", "2", "3"), HeaderFooters.pageNumbers(listOf(plain, plain, plain)))
        // The footer shows the page's number as its section has it.
        assertEquals("Page iv", HeaderFooters.fill("Page ${HeaderFooters.PAGE}", "iv", 9))
    }

    private fun report(): Document {
        val d = Document(mutableListOf())
        val front = Paragraph.of("Contents").also { it.format.sectionBreak = PageSetup(pageNumberFormat = NumFormat.LOWER_ROMAN, pageNumberStart = 1) }
        d.blocks.add(ParagraphBlock(front))
        d.blocks.add(ParagraphBlock(Paragraph.of("Chapter one")))
        d.page.pageNumberStart = 1
        d.footer = Paragraph.of("— ${HeaderFooters.PAGE} —")
        return d
    }

    @Test
    fun wordAndOdfKeepThem() {
        val d = report()
        val docx = ByteArrayOutputStream().also { DocxWriter.write(d, it) }.toByteArray()
        val xml = String(OfficeXml.unzip(docx)["word/document.xml"]!!)
        assertTrue(xml, xml.contains("""<w:pgNumType w:fmt="lowerRoman" w:start="1"/>"""))
        fun check(back: Document) {
            val first = (back.blocks[0] as ParagraphBlock).paragraph.format.sectionBreak!!
            assertEquals(NumFormat.LOWER_ROMAN, first.pageNumberFormat); assertEquals(1, first.pageNumberStart)
            assertEquals(NumFormat.DECIMAL, back.page.pageNumberFormat); assertEquals(1, back.page.pageNumberStart)
        }
        check(DocxReader.read(ByteArrayInputStream(docx)))
        check(OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(d, it) }.toByteArray()))
    }
}
