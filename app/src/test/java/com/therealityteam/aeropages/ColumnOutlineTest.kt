package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Outline
import com.therealityteam.aeropages.model.RowGroup
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Hidden columns (Home → Format → Hide & Unhide) and the column outline (Data → Group Columns). */
class ColumnOutlineTest {

    private fun editor(cols: Int = 12): SheetEditor = SheetEditor().apply {
        load(Workbook.blank())
        for (c in 0 until cols) setCell(0, c, "h${c + 1}")
        for (c in 0 until cols) setCell(1, c, "${c + 1}")
    }
    private fun SheetEditor.selectCols(c1: Int, c2: Int) { select(0, c1); extendSelection(0, c2 - c1) }
    private fun groups(e: SheetEditor) = e.sheet.colGroups.map { Triple(it.r1, it.r2, it.collapsed) }

    private fun part(bytes: ByteArray, name: String): String {
        val z = ZipInputStream(ByteArrayInputStream(bytes))
        while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().decodeToString() }
        error("no $name")
    }

    // --- the outline -------------------------------------------------------------------------

    @Test
    fun columnGroupsNestFoldAndFollowLevelButtons() {
        val e = editor()
        e.selectCols(1, 6); assertTrue(e.groupColumns())               // B–G
        e.selectCols(2, 4); assertTrue(e.groupColumns())               // C–E inside
        e.selectCols(1, 6); assertFalse("duplicate", e.groupColumns())
        e.selectCols(5, 8); assertFalse("partial overlap", e.groupColumns())
        assertEquals(2, e.maxColOutlineLevel)
        assertEquals(listOf(Triple(1, 6, false), Triple(2, 4, false)), groups(e))
        assertEquals(2, e.sheet.colLevel(3)); assertEquals(0, e.sheet.colLevel(7))

        // Collapse the inner group with the active cell inside: it moves to the summary column on the right.
        e.select(1, 3)
        e.toggleColGroup(e.sheet.colGroups.first { it.r1 == 2 })
        assertEquals(setOf(2, 3, 4), e.invisibleCols)
        assertEquals("selection left the folded columns", 5, e.selCol)
        e.showColOutlineLevel(1)
        assertEquals((1..6).toSet(), e.invisibleCols)
        e.showColOutlineLevel(3)
        assertTrue(e.invisibleCols.isEmpty())
        // Rows are untouched by the column outline.
        assertTrue(e.invisibleRows.isEmpty())
        e.selectCols(2, 4); assertTrue(e.ungroupColumns())
        assertEquals(listOf(Triple(1, 6, false)), groups(e))
        e.clearOutline()
        assertTrue(e.sheet.colGroups.isEmpty())
        e.undo()
        assertEquals(listOf(Triple(1, 6, false)), groups(e))
    }

    @Test
    fun touchingColumnGroupsMergeAsInExcel() {
        val e = editor()
        e.selectCols(1, 2); assertTrue(e.groupColumns())
        e.selectCols(3, 5); assertTrue(e.groupColumns())
        assertEquals(listOf(Triple(1, 5, false)), groups(e))
    }

    @Test
    fun theSharedOutlineRebuildsGroupsFromLevels() {
        // Levels per index as xlsx stores them: 1–6 at level 1, 2–4 at level 2; the summary after 2–4 is collapsed.
        val levels = mapOf(1 to 1, 2 to 2, 3 to 2, 4 to 2, 5 to 1, 6 to 1)
        val gs = Outline.fromLevels(levels, collapsedSummary = setOf(5), hidden = setOf(2, 3, 4))
        assertEquals(listOf(Triple(1, 6, false), Triple(2, 4, true)), gs.map { Triple(it.r1, it.r2, it.collapsed) })
        assertEquals(setOf(2, 3, 4), Outline.folded(gs))
        assertEquals(2, Outline.maxLevel(gs))
        assertFalse("partial overlap refused", Outline.canAdd(gs, RowGroup(5, 8)))
    }

    // --- hide / unhide -----------------------------------------------------------------------

    @Test
    fun hideAndUnhideColumnsLikeExcel() {
        val e = editor()
        e.selectCols(2, 3); assertTrue(e.hideColumns())               // C–D
        assertEquals(setOf(2, 3), e.invisibleCols)
        assertEquals("the active cell moved off the hidden columns", 4, e.selCol)
        // Arrow keys step over hidden columns.
        e.select(1, 1); e.moveSelection(0, 1)
        assertEquals(4, e.selCol)
        e.moveSelection(0, -1)
        assertEquals(1, e.selCol)
        // Select across them (B–E) to show them again.
        e.selectCols(1, 4); assertTrue(e.unhideColumns())
        assertTrue(e.sheet.hiddenCols.isEmpty())
        // With nothing hidden inside, the run next to the selection comes back.
        e.selectCols(5, 6); e.hideColumns()
        e.select(1, 4); assertTrue(e.unhideColumns())
        assertTrue(e.sheet.hiddenCols.isEmpty())
        assertFalse("nothing hidden there", e.unhideColumns())
        // Hiding every column is refused.
        e.selectCols(0, e.sheet.colCount - 1)
        assertFalse(e.hideColumns())
        // One undo step each.
        e.selectCols(7, 7); e.hideColumns()
        e.undo()
        assertTrue(e.sheet.hiddenCols.isEmpty())
    }

    @Test
    fun insertingAndDeletingColumnsMovesHiddenColumnsAndGroups() {
        val e = editor()
        e.selectCols(4, 5); e.hideColumns()                            // E–F hidden
        e.selectCols(6, 8); e.groupColumns()                           // G–I grouped
        e.insertColAt(1)                                               // a column before B
        assertEquals(setOf(5, 6), e.sheet.hiddenCols)
        assertEquals(listOf(Triple(7, 9, false)), groups(e))
        e.deleteColAt(0)
        assertEquals(setOf(4, 5), e.sheet.hiddenCols)
        assertEquals(listOf(Triple(6, 8, false)), groups(e))
    }

    // --- files -------------------------------------------------------------------------------

    private fun outlined(): Workbook {
        val e = editor()
        e.selectCols(1, 6); e.groupColumns()
        e.selectCols(2, 4); e.groupColumns()
        e.toggleColGroup(e.sheet.colGroups.first { it.r1 == 2 })     // C–E folded
        e.selectCols(9, 10); e.hideColumns()                           // J–K hidden by hand
        e.sheet.colWidths[8] = 2f
        return e.workbook
    }

    private fun assertOutline(back: com.therealityteam.aeropages.model.Sheet) {
        assertEquals(listOf(Triple(1, 6, false), Triple(2, 4, true)), back.colGroups.map { Triple(it.r1, it.r2, it.collapsed) })
        assertEquals("folded columns are not hidden by hand", setOf(9, 10), back.hiddenCols)
        assertEquals(setOf(2, 3, 4, 9, 10), back.invisibleCols())
        assertEquals(2f, back.colWidthFactor(8), 0.01f)
    }

    @Test
    fun excelKeepsHiddenColumnsAndTheColumnOutline() {
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(outlined(), bos)
        val xml = part(bos.toByteArray(), "xl/worksheets/sheet1.xml")
        assertTrue(xml.contains("""outlineLevelCol="2""""))
        // Runs of alike columns share one <col>: C–E folded at level 2, then the collapsed summary F.
        assertTrue(xml, xml.contains("""<col min="3" max="5" width="9.1406" hidden="1" outlineLevel="2"/>"""))
        assertTrue(xml, xml.contains("""<col min="6" max="6" width="9.1406" outlineLevel="1" collapsed="1"/>"""))
        assertTrue(xml, xml.contains("""<col min="10" max="11" width="9.1406" hidden="1"/>"""))
        assertOutline(XlsxReader.read(bos.toByteArray()).sheets[0])
    }

    @Test
    fun openDocumentKeepsHiddenColumnsAndTheColumnOutline() {
        val bos = ByteArrayOutputStream()
        OdsWriter.write(outlined(), bos)
        val xml = part(bos.toByteArray(), "content.xml")
        assertTrue(xml.contains("<table:table-column-group><table:table-column"))
        assertTrue(xml.contains("""<table:table-column-group table:display="false">"""))
        assertTrue(xml.contains("""table:visibility="collapse""""))
        assertOutline(OdsReader.read(bos.toByteArray()).sheets[0])
    }
}
