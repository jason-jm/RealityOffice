package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Excel's notes keep who wrote them: a new note is the reviewer's, an edited one keeps its author; xlsx lists the authors
 * and gives each note the VML shape Excel shows notes through (`legacyDrawing`); ODS keeps the author and date
 * (`dc:creator`, `dc:date`) and a paragraph per line.
 */
class SheetNotesTest {
    private val at = 1_760_000_000_000L
    private fun book() = Workbook.blank().also { wb ->
        val s = wb.sheets[0]
        s.ensure(0, 0).text = "Region"
        s.ensure(1, 0).also { it.text = "North"; it.comment = "Check the March figure"; it.commentAuthor = "Ann"; it.commentDate = at }
        s.ensure(2, 1).also { it.text = "12"; it.comment = "Two\nlines"; it.commentAuthor = "Bo" }
        s.ensure(4, 2).also { it.comment = "Nobody's" }
    }
    private fun parts(bytes: ByteArray): Map<String, String> = ZipInputStream(bytes.inputStream()).use { z ->
        generateSequence { z.nextEntry }.associate { it.name to z.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun aNewNoteIsTheReviewersAndAnEditedOneKeepsItsAuthor() {
        val e = SheetEditor().apply { load(Workbook.blank()); reviewer = "Ann" }
        e.select(1, 1)
        e.setComment("First")
        val c = e.sheet.cell(1, 1)!!
        assertEquals("Ann", c.commentAuthor)
        assertTrue(c.commentDate > 0)
        val when1 = c.commentDate
        e.reviewer = "Bo"
        e.setComment("Edited")
        assertEquals("Edited" to "Ann", c.comment to c.commentAuthor)
        assertEquals(when1, c.commentDate)
        assertEquals(listOf("Ann"), e.commentCells().map { it.author })
        e.undo()
        assertEquals("First", e.sheet.cell(1, 1)!!.comment)
        e.clearComment()
        e.sheet.cell(1, 1)!!.let { assertNull(it.comment); assertNull(it.commentAuthor); assertEquals(0L, it.commentDate) }
        // No name set: the localised "Me".
        assertEquals("Me", SheetEditor().reviewerName)
        // A copy keeps the author.
        assertEquals("Ann", Cell(comment = "x").also { it.commentAuthor = "Ann"; it.commentDate = at }.copyOf().let { "${it.commentAuthor}@${it.commentDate}" }.substringBefore('@'))
    }

    @Test
    fun xlsxListsTheAuthorsAndGivesEachNoteItsShape() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(book(), it) }.toByteArray()
        val p = parts(bytes)
        val comments = p.getValue("xl/comments1.xml")
        assertTrue(comments, comments.contains("<authors><author>Ann</author><author>Bo</author><author></author></authors>"))
        assertTrue(comments, comments.contains("""<comment ref="A2" authorId="0">"""))
        assertTrue(comments, comments.contains("""<comment ref="B3" authorId="1">"""))
        assertTrue(comments, comments.contains("""<comment ref="C5" authorId="2">"""))
        val vml = p.getValue("xl/drawings/vmlDrawing1.vml")
        assertEquals(3, Regex("""<x:ClientData ObjectType="Note">""").findAll(vml).count())
        assertTrue(vml, vml.contains("<x:Row>1</x:Row><x:Column>0</x:Column>") && vml.contains("<x:Row>4</x:Row><x:Column>2</x:Column>"))
        val sheet = p.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet, sheet.contains("""<legacyDrawing r:id="rIdVml"/>"""))
        // CT_Worksheet: legacyDrawing after the page setup parts and before tableParts / extLst.
        assertTrue(sheet, sheet.indexOf("<legacyDrawing") > sheet.indexOf("<pageMargins"))
        val rels = p.getValue("xl/worksheets/_rels/sheet1.xml.rels")
        assertTrue(rels, rels.contains("""Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/vmlDrawing" Target="../drawings/vmlDrawing1.vml""""))
        assertTrue(p.getValue("[Content_Types].xml").contains("""<Default Extension="vml" ContentType="application/vnd.openxmlformats-officedocument.vmlDrawing"/>"""))
        val back = XlsxReader.read(bytes).sheets[0]
        assertEquals("Ann", back.cell(1, 0)!!.commentAuthor)
        assertEquals("Two\nlines" to "Bo", back.cell(2, 1)!!.let { it.comment to it.commentAuthor })
        assertNull(back.cell(4, 2)!!.commentAuthor)
        // No notes: no VML.
        val plain = parts(ByteArrayOutputStream().also { XlsxWriter.write(Workbook.blank().also { it.sheets[0].ensure(0, 0).text = "x" }, it) }.toByteArray())
        assertTrue(plain.keys.none { it.endsWith(".vml") })
        assertTrue(!plain.getValue("[Content_Types].xml").contains("vml"))
    }

    @Test
    fun odsKeepsTheAuthorAndTheDate() {
        val bytes = ByteArrayOutputStream().also { OdsWriter.write(book(), it) }.toByteArray()
        val content = parts(bytes).getValue("content.xml")
        assertTrue(content, content.contains("<office:annotation><dc:creator>Ann</dc:creator><dc:date>2025-10-09T08:53:20</dc:date><text:p>Check the March figure</text:p></office:annotation>"))
        assertTrue(content, content.contains("<dc:creator>Bo</dc:creator><text:p>Two</text:p><text:p>lines</text:p>"))
        val back = OdsReader.read(bytes).sheets[0]
        back.cell(1, 0)!!.let { assertEquals("Ann", it.commentAuthor); assertEquals(at, it.commentDate) }
        assertEquals("Two\nlines" to "Bo", back.cell(2, 1)!!.let { it.comment to it.commentAuthor })
        back.cell(4, 2)!!.let { assertNull(it.commentAuthor); assertEquals(0L, it.commentDate) }
    }

    @Test
    fun aNoteKeptShownComesBackShownInBothFormats() {
        val e = SheetEditor().apply { load(book()) }
        e.select(1, 0)
        assertTrue(e.toggleNoteShown())
        assertTrue(e.activeNoteShown && e.noteVisible(e.sheet.cell(1, 0)))
        assertTrue("another note stays hidden", !e.noteVisible(e.sheet.cell(2, 1)))
        e.undo()
        assertTrue(!e.activeNoteShown)
        e.redo()
        // Show All Notes is a view setting: every note visible, nothing in the file changes.
        e.toggleShowAllNotes()
        assertTrue(e.noteVisible(e.sheet.cell(2, 1)) && !e.noteVisible(e.sheet.cell(0, 0)))
        e.toggleShowAllNotes()
        // A cell without a note has none to show.
        e.select(0, 0)
        assertTrue(!e.toggleNoteShown())
        // xlsx: the shown note's VML shape is visible with <x:Visible/>; the others hidden.
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(e.workbook, it) }.toByteArray()
        val vml = parts(bytes).getValue("xl/drawings/vmlDrawing1.vml")
        assertEquals(1, Regex("visibility:visible").findAll(vml).count())
        assertEquals(2, Regex("visibility:hidden").findAll(vml).count())
        assertTrue(vml, vml.contains("<x:Row>1</x:Row><x:Column>0</x:Column><x:Visible/></x:ClientData>"))
        val back = XlsxReader.read(bytes).sheets[0]
        assertTrue(back.cell(1, 0)!!.commentShown)
        assertTrue(!back.cell(2, 1)!!.commentShown)
        // ODS: office:display="true" on the shown note only.
        val ods = ByteArrayOutputStream().also { OdsWriter.write(e.workbook, it) }.toByteArray()
        val content = parts(ods).getValue("content.xml")
        assertEquals(1, Regex("""office:display="true"""").findAll(content).count())
        val fromOds = OdsReader.read(ods).sheets[0]
        assertTrue(fromOds.cell(1, 0)!!.commentShown && !fromOds.cell(2, 1)!!.commentShown)
        // Excel's own VML (shapes across lines, a <x:Visible/> with a space) is read too.
        val sheet = Workbook.blank().sheets[0].also { it.ensure(4, 2).comment = "x" }
        XlsxReader.readShownNotes(sheet, """<xml><v:shape><x:ClientData ObjectType="Note">
            <x:MoveWithCells/><x:Row>4</x:Row>
            <x:Column>2</x:Column> <x:Visible />
          </x:ClientData></v:shape></xml>""".toByteArray())
        assertTrue(sheet.cell(4, 2)!!.commentShown)
    }

    @Test
    fun howNotesPrintIsKeptInBothFormats() {
        val wb = book().also { it.sheets[0].print = it.sheets[0].print.copy(comments = com.therealityteam.aeropages.model.NotePrint.AT_END) }
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()
        val sheetXml = parts(bytes).getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheetXml, Regex("""<pageSetup [^>]*cellComments="atEnd"""").containsMatchIn(sheetXml))
        assertEquals(com.therealityteam.aeropages.model.NotePrint.AT_END, XlsxReader.read(bytes).sheets[0].print.comments)
        wb.sheets[0].print = wb.sheets[0].print.copy(comments = com.therealityteam.aeropages.model.NotePrint.AS_DISPLAYED)
        val shown = ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()
        assertEquals(com.therealityteam.aeropages.model.NotePrint.AS_DISPLAYED, XlsxReader.read(shown).sheets[0].print.comments)
        // None (Excel's default) writes no attribute.
        wb.sheets[0].print = wb.sheets[0].print.copy(comments = com.therealityteam.aeropages.model.NotePrint.NONE)
        assertTrue(!parts(ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()).getValue("xl/worksheets/sheet1.xml").contains("cellComments"))
        // ODS: at end of sheet is LibreOffice's "annotations" in the page layout's print list; as displayed has no ODS form.
        wb.sheets[0].print = wb.sheets[0].print.copy(comments = com.therealityteam.aeropages.model.NotePrint.AT_END)
        val ods = ByteArrayOutputStream().also { OdsWriter.write(wb, it) }.toByteArray()
        assertTrue(Regex("""style:print="[^"]*annotations""").containsMatchIn(parts(ods).getValue("styles.xml")))
        assertEquals(com.therealityteam.aeropages.model.NotePrint.AT_END, OdsReader.read(ods).sheets[0].print.comments)
        assertEquals(com.therealityteam.aeropages.model.NotePrint.NONE, com.therealityteam.aeropages.model.NotePrint.of("bogus"))
    }

    /** Samples (with `-Daero.sampleDir`): notes by two people and one by nobody known, as xlsx and ODS. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        File(dir, "sheet-notes.xlsx").outputStream().use { XlsxWriter.write(book(), it) }
        File(dir, "sheet-notes.ods").outputStream().use { OdsWriter.write(book(), it) }
        val shown = book().also { it.sheets[0].cell(1, 0)!!.commentShown = true }
        File(dir, "sheet-notes-shown.xlsx").outputStream().use { XlsxWriter.write(shown, it) }
        File(dir, "sheet-notes-shown.ods").outputStream().use { OdsWriter.write(shown, it) }
        val atEnd = book().also { it.sheets[0].print = it.sheets[0].print.copy(comments = com.therealityteam.aeropages.model.NotePrint.AT_END) }
        File(dir, "sheet-notes-print.xlsx").outputStream().use { XlsxWriter.write(atEnd, it) }
        File(dir, "sheet-notes-print.ods").outputStream().use { OdsWriter.write(atEnd, it) }
        val asShown = book().also { b -> b.sheets[0].cell(1, 0)!!.commentShown = true
            b.sheets[0].print = b.sheets[0].print.copy(comments = com.therealityteam.aeropages.model.NotePrint.AS_DISPLAYED) }
        File(dir, "sheet-notes-displayed.xlsx").outputStream().use { XlsxWriter.write(asShown, it) }
    }
}
