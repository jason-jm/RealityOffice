package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.TabAlign
import com.therealityteam.aeropages.model.TabLayout
import com.therealityteam.aeropages.model.TabLeader
import com.therealityteam.aeropages.model.TabStop
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Tab stops: where tabs take text (left, centre, right, decimal, default, hanging), and the stops in every format. */
class TabStopsTest {

    /** Every character 6 points wide. */
    private val mono: (String, CharacterFormat) -> Float = { s, _ -> s.length * 6f }

    private fun para(text: String, f: ParagraphFormat = ParagraphFormat()) = Paragraph(mutableListOf(TextRun(text)), f)
    private fun advances(p: Paragraph, width: Float = 468f, defaultTab: Float = 36f) = TabLayout.tabs(p, width, defaultTab, mono).map { it.advance }

    @Test
    fun tabsGoToTheirStops() {
        // "Name:" is 30 wide; a left stop at 90 takes the value there.
        assertEquals(listOf(60f), advances(para("Name:\tJohn", ParagraphFormat(tabStops = listOf(TabStop(90f))))))
        // No stop: the default ones, 36 apart — the next past 30 is 36, then past 48 ("ab" after it) 72.
        assertEquals(listOf(6f, 24f), advances(para("Name:\tab\tc")))
        // A right stop at the margin: "12" ends there; a dot leader fills the gap.
        val toc = TabLayout.tabs(para("Intro\t12", ParagraphFormat(tabStops = listOf(TabStop(468f, TabAlign.RIGHT, TabLeader.DOT)))), 468f, 36f, mono)
        assertEquals(468f - 12f - 30f, toc[0].advance); assertEquals(TabLeader.DOT, toc[0].leader)
        // Centred on 234: "Title" (30 wide) starts at 219.
        assertEquals(listOf(219f), advances(para("\tTitle", ParagraphFormat(tabStops = listOf(TabStop(234f, TabAlign.CENTER))))))
        // Decimal at 120: "123.45" puts its point there — "123" is 18 wide, so the text starts at 102 ("Sum:" ends at 24).
        assertEquals(listOf(102f - 24f), advances(para("Sum:\t123.45", ParagraphFormat(tabStops = listOf(TabStop(120f, TabAlign.DECIMAL))))))
        // Past the last custom stop, the default ones go on: the first past 88 is 108.
        assertEquals(listOf(70f, 20f), advances(para("ab\tc\td", ParagraphFormat(tabStops = listOf(TabStop(82f))))))
    }

    @Test
    fun hangingIndentsAndWrapping() {
        // A hanging indent stops the first line's tab at the left indent: "1." then the text at 36.
        assertEquals(listOf(24f), advances(para("1.\tFirst item", ParagraphFormat(indentLeftPt = 36f, firstLineIndentPt = -36f))))
        // A stop past the line's end: the text after the tab goes on to the next line.
        val p = para("Left\tRight", ParagraphFormat(tabStops = listOf(TabStop(600f))))
        assertEquals(listOf(468f - 24f), advances(p))
        // Text that wrapped before the tab measures from the new line's start.
        val long = para("aaaaaaaaaa bbbbbbbbbb cccccccccc\tz", ParagraphFormat(tabStops = listOf(TabStop(100f))))
        assertEquals("the third word wrapped: the tab stands at 60, its stop at 100", listOf(40f), advances(long, width = 130f))
    }

    private fun zip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun entry(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().toString(Charsets.UTF_8) }
        }
        error("no $name")
    }

    @Test
    fun wordTabStopsThroughStylesAndSettings() {
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val styles = """<?xml version="1.0" encoding="UTF-8"?><w:styles xmlns:w="$w"><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Form"><w:name w:val="Form"/><w:basedOn w:val="Normal"/><w:pPr><w:tabs><w:tab w:val="left" w:pos="1440"/><w:tab w:val="right" w:leader="dot" w:pos="9360"/></w:tabs></w:pPr></w:style></w:styles>"""
        val body = """<w:p><w:pPr><w:pStyle w:val="Form"/></w:pPr><w:r><w:t>Name:</w:t></w:r><w:r><w:tab/><w:t>John</w:t></w:r></w:p>""" +
            """<w:p><w:pPr><w:pStyle w:val="Form"/><w:tabs><w:tab w:val="clear" w:pos="1440"/><w:tab w:val="decimal" w:pos="4320"/><w:tab w:val="bar" w:pos="5000"/></w:tabs></w:pPr><w:r><w:t>Sum</w:t></w:r></w:p>"""
        val bytes = zip(mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="$w"><w:body>$body</w:body></w:document>""",
            "word/styles.xml" to styles,
            "word/settings.xml" to """<?xml version="1.0" encoding="UTF-8"?><w:settings xmlns:w="$w"><w:defaultTabStop w:val="420"/></w:settings>""",
        ))
        val doc = DocxReader.read(ByteArrayInputStream(bytes))
        assertEquals(21f, doc.defaultTabPt)
        val paras = doc.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph }
        assertEquals("the style's stops", listOf(TabStop(72f), TabStop(468f, TabAlign.RIGHT, TabLeader.DOT)), paras[0].format.tabStops)
        assertEquals("one cleared, one added, a bar left out", listOf(TabStop(216f, TabAlign.DECIMAL), TabStop(468f, TabAlign.RIGHT, TabLeader.DOT)), paras[1].format.tabStops)
        val out = ByteArrayOutputStream().also { DocxWriter.write(doc, it) }.toByteArray()
        val back = DocxReader.read(ByteArrayInputStream(out))
        assertEquals(paras.map { it.format.tabStops }, back.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph.format.tabStops })
        assertEquals(21f, back.defaultTabPt)
        assertTrue(entry(out, "word/document.xml").contains("""<w:tab w:val="right" w:pos="9360" w:leader="dot"/>"""))
    }

    @Test
    fun odtTabStopsCountFromTheIndent() {
        val doc = Document(mutableListOf())
        doc.defaultTabPt = 21f
        doc.addParagraph(para("Name:\tJohn", ParagraphFormat(indentLeftPt = 36f, tabStops = listOf(TabStop(108f), TabStop(468f, TabAlign.RIGHT, TabLeader.DOT)))))
        doc.addParagraph(para("1.\t2.50", ParagraphFormat(tabStops = listOf(TabStop(144f, TabAlign.DECIMAL, TabLeader.HYPHEN), TabStop(200f, TabAlign.CENTER, TabLeader.UNDERSCORE)))))
        val bytes = ByteArrayOutputStream().also { OdtWriter.write(doc, it) }.toByteArray()
        assertTrue("from the paragraph's indent, as ODF counts", entry(bytes, "content.xml").contains("""<style:tab-stop style:position="72.00pt"/>"""))
        assertTrue(entry(bytes, "styles.xml").contains("""style:tab-stop-distance="21.00pt""""))
        val back = OdtReader.read(bytes)
        assertEquals(doc.blocks.map { (it as ParagraphBlock).paragraph.format.tabStops }, back.blocks.map { (it as ParagraphBlock).paragraph.format.tabStops })
        assertEquals(21f, back.defaultTabPt)
        // A document converted from Word counts its stops from the margin (LibreOffice's TabsRelativeToIndent = false).
        val settings = """<?xml version="1.0" encoding="UTF-8"?><office:document-settings xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:config="urn:oasis:names:tc:opendocument:xmlns:config:1.0">""" +
            """<office:settings><config:config-item-set config:name="ooo:configuration-settings"><config:config-item config:name="TabsRelativeToIndent" config:type="boolean">false</config:config-item></config:config-item-set></office:settings></office:document-settings>"""
        val parts = com.therealityteam.aeropages.format.OfficeXml.unzip(ByteArrayInputStream(bytes)).toMutableMap()
        parts["settings.xml"] = settings.toByteArray()
        val fromMargin = OdtReader.read(zip(parts.mapValues { String(it.value, Charsets.UTF_8) }))
        assertEquals(72f, (fromMargin.blocks[0] as ParagraphBlock).paragraph.format.tabStops[0].posPt)
    }

    @Test
    fun wordTemplatesPlaceholdersShow() {
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val body = """<w:p><w:r><w:t xml:space="preserve">To: </w:t></w:r><w:r><w:fldChar w:fldCharType="begin"/></w:r>""" +
            """<w:r><w:instrText xml:space="preserve"> MACROBUTTON NoMacro [Click to enter the subject] </w:instrText></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>""" +
            """<w:r><w:t xml:space="preserve"> / </w:t></w:r><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText> SAVEDATE </w:instrText></w:r>""" +
            """<w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>2026-10-07</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>"""
        val doc = DocxReader.read(ByteArrayInputStream(zip(mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="$w"><w:body>$body</w:body></w:document>""",
        ))))
        assertEquals("To: [Click to enter the subject] / 2026-10-07", (doc.blocks[0] as ParagraphBlock).paragraph.plainText())
    }
}
