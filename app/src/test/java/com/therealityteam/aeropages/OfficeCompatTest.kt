package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.model.ParagraphBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Compatibility against REAL Office output patterns our own writer never produces:
 * Excel's style-only self-closing cells, rich-text shared strings, shared formulas,
 * extension namespaces; Word's rsid/proofErr/bookmark noise. Fixtures are hand-built
 * to mirror genuine Excel/Word part structure.
 */
class OfficeCompatTest {

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            entries.forEach { (name, content) ->
                z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray()); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun realExcelPatternsParse() {
        // Mirrors Excel-generated parts: dimension, x14ac attrs, cellStyleXfs, style-only
        // empty cells (<c .. s="1"/>), rich-text <si>, shared formulas (master + followers).
        val workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006">
<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
        val shared = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="3" uniqueCount="3">
<si><t>plain</t></si>
<si><r><rPr><b/><sz val="11"/></rPr><t>rich </t></r><r><t xml:space="preserve">text</t></r></si>
<si><t/></si></sst>"""
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>
</styleSheet>"""
        // Row 1: A1 styled-but-EMPTY self-closing, B1 shared string 0, C1 rich string 1.
        // Rows 2-4 col A: numbers; col B: shared formula =A2*2 master + si followers.
        val sheet1 = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" xmlns:x14ac="http://schemas.microsoft.com/office/spreadsheetml/2009/9/ac" mc:Ignorable="x14ac">
<dimension ref="A1:C4"/><sheetViews><sheetView tabSelected="1" workbookViewId="0"/></sheetViews>
<sheetFormatPr defaultRowHeight="14.4" x14ac:dyDescent="0.3"/>
<sheetData>
<row r="1" spans="1:3" x14ac:dyDescent="0.3"><c r="A1" s="1"/><c r="B1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c></row>
<row r="2" spans="1:3"><c r="A2"><v>10</v></c><c r="B2"><f t="shared" ref="B2:B4" si="0">A2*2</f><v>20</v></c></row>
<row r="3" spans="1:3"><c r="A3"><v>20</v></c><c r="B3"><f t="shared" si="0"/><v>40</v></c></row>
<row r="4" spans="1:3"><c r="A4"><v>30</v></c><c r="B4"><f t="shared" si="0"/><v>60</v></c></row>
</sheetData><pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/></worksheet>"""
        val bytes = zipOf(
            "xl/workbook.xml" to workbook,
            "xl/_rels/workbook.xml.rels" to rels,
            "xl/sharedStrings.xml" to shared,
            "xl/styles.xml" to styles,
            "xl/worksheets/sheet1.xml" to sheet1,
        )

        val wb = XlsxReader.read(bytes)
        val s = wb.sheets[0]
        // The style-only empty A1 must NOT swallow B1/C1 (self-closing <c/> handling).
        assertEquals("plain", s.cell(0, 1)?.text)
        assertEquals("rich text", s.cell(0, 2)?.text)
        assertTrue("A1 stays empty", s.cell(0, 0)?.text.isNullOrEmpty())
        // Shared formulas expand with shifted refs instead of degrading to static values.
        assertEquals("=A2*2", s.cell(1, 1)?.text)
        assertEquals("=A3*2", s.cell(2, 1)?.text)
        assertEquals("=A4*2", s.cell(3, 1)?.text)
        // And they evaluate.
        val e = com.therealityteam.aeropages.editor.SheetEditor()
        e.load(wb)
        assertEquals("60", e.display(3, 1))
    }

    @Test
    fun realWordNoiseParses() {
        // Word-generated document.xml noise: rsid attributes, proofErr, bookmarkStart/End,
        // szCs, lang on rPr, lastRenderedPageBreak — none of which our writer emits.
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml">
<w:body>
<w:p w:rsidR="00AB12CD" w:rsidRDefault="00AB12CD"><w:pPr><w:pStyle w:val="Heading1"/><w:rPr><w:lang w:val="zh-CN"/></w:rPr></w:pPr>
<w:bookmarkStart w:id="0" w:name="_Toc12345"/><w:r w:rsidRPr="00FF00AA"><w:rPr><w:b/><w:bCs/><w:sz w:val="32"/><w:szCs w:val="32"/><w:lang w:val="zh-CN"/></w:rPr><w:t>标题一</w:t></w:r><w:bookmarkEnd w:id="0"/></w:p>
<w:p><w:proofErr w:type="spellStart"/><w:r><w:rPr><w:i/></w:rPr><w:t>italicz</w:t></w:r><w:proofErr w:type="spellEnd"/><w:r><w:lastRenderedPageBreak/><w:t xml:space="preserve"> 正文继续</w:t></w:r></w:p>
</w:body></w:document>"""
        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
        val bytes = zipOf("[Content_Types].xml" to contentTypes, "word/document.xml" to document)

        val doc = DocxReader.read(ByteArrayInputStream(bytes))
        val paras = doc.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph }
        assertEquals("标题一", paras[0].plainText())
        assertEquals(com.therealityteam.aeropages.model.ParagraphStyle.HEADING1, paras[0].format.style)
        assertTrue("bold heading run", paras[0].runs.first().format.bold)
        assertEquals("italicz 正文继续", paras[1].plainText())
        assertTrue("italic survives proofErr wrapper", paras[1].runs.first().format.italic)
    }
}
