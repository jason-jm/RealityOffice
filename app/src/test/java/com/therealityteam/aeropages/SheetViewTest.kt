package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** A sheet's view as Excel and Calc keep it: zoom, gridlines, headings and frozen panes, and the View tab's commands. */
class SheetViewTest {

    private fun workbook(): Workbook {
        val a = Sheet("Data").apply { zoom = 150; showGridlines = false; showHeadings = false; freezeRows = 2; freezeCols = 1; ensure(0, 0).text = "x" }
        val b = Sheet("Q3 Sales").apply { zoom = 75; ensure(0, 0).text = "y" }
        return Workbook(mutableListOf(a, b))
    }

    private fun assertView(wb: Workbook, headingsPerSheet: Boolean) {
        val (a, b) = wb.sheets
        assertEquals(150, a.zoom); assertFalse(a.showGridlines); assertFalse(a.showHeadings)
        assertEquals(2, a.freezeRows); assertEquals(1, a.freezeCols)
        assertEquals(75, b.zoom); assertTrue(b.showGridlines)
        // Calc keeps the headings for the whole view.
        if (headingsPerSheet) assertTrue(b.showHeadings)
        assertEquals(0, b.freezeRows); assertEquals(0, b.freezeCols)
    }

    @Test
    fun excelKeepsTheViewPerSheet() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(workbook(), it) }.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["xl/worksheets/sheet1.xml"]!!)
        assertTrue(xml, xml.contains("""<sheetView workbookViewId="0" showGridLines="0" showRowColHeaders="0" zoomScale="150" zoomScaleNormal="150">"""))
        assertView(XlsxReader.read(bytes), headingsPerSheet = true)
        // A sheet as it comes needs no sheet view at all.
        val plain = ByteArrayOutputStream().also { XlsxWriter.write(Workbook.blank(), it) }.toByteArray()
        assertFalse(String(OfficeXml.unzip(plain)["xl/worksheets/sheet1.xml"]!!).contains("sheetView"))
    }

    @Test
    fun calcKeepsItInSettings() {
        val bytes = ByteArrayOutputStream().also { OdsWriter.write(workbook(), it) }.toByteArray()
        val parts = OfficeXml.unzip(bytes)
        val settings = String(parts["settings.xml"]!!)
        assertTrue(settings.contains("""<config:config-item-map-entry config:name="Data"><config:config-item config:name="CursorPositionX" config:type="int">0</config:config-item>"""))
        assertTrue(settings.contains("""<config:config-item config:name="HorizontalSplitMode" config:type="short">2</config:config-item>"""))
        assertTrue(settings.contains("""<config:config-item config:name="ZoomValue" config:type="int">150</config:config-item>"""))
        assertTrue(String(parts["META-INF/manifest.xml"]!!).contains("settings.xml"))
        assertView(OdsReader.read(bytes), headingsPerSheet = false)
    }

    @Test
    fun calcsOwnSettingsAreRead() {
        // As LibreOffice Calc writes them: a split (mode 1) is no freeze; the view's own gridlines and headings.
        val settings = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-settings xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:config="urn:oasis:names:tc:opendocument:xmlns:config:1.0" office:version="1.4"><office:settings>
<config:config-item-set config:name="ooo:view-settings"><config:config-item-map-indexed config:name="Views"><config:config-item-map-entry>
<config:config-item config:name="ViewId" config:type="string">view1</config:config-item>
<config:config-item-map-named config:name="Tables">
<config:config-item-map-entry config:name="Sheet1">
<config:config-item config:name="HorizontalSplitMode" config:type="short">0</config:config-item>
<config:config-item config:name="VerticalSplitMode" config:type="short">2</config:config-item>
<config:config-item config:name="HorizontalSplitPosition" config:type="int">0</config:config-item>
<config:config-item config:name="VerticalSplitPosition" config:type="int">1</config:config-item>
<config:config-item config:name="ZoomType" config:type="short">0</config:config-item>
<config:config-item config:name="ZoomValue" config:type="int">120</config:config-item>
<config:config-item config:name="ShowGrid" config:type="boolean">true</config:config-item>
</config:config-item-map-entry>
<config:config-item-map-entry config:name="Sheet2">
<config:config-item config:name="HorizontalSplitMode" config:type="short">1</config:config-item>
<config:config-item config:name="HorizontalSplitPosition" config:type="int">3000</config:config-item>
</config:config-item-map-entry>
</config:config-item-map-named>
<config:config-item config:name="ActiveTable" config:type="string">Sheet1</config:config-item>
<config:config-item config:name="ZoomValue" config:type="int">90</config:config-item>
<config:config-item config:name="ShowGrid" config:type="boolean">false</config:config-item>
<config:config-item config:name="HasColumnRowHeaders" config:type="boolean">false</config:config-item>
</config:config-item-map-entry></config:config-item-map-indexed></config:config-item-set>
</office:settings></office:document-settings>""".toByteArray()
        val sheets = listOf(Sheet("Sheet1"), Sheet("Sheet2"))
        com.therealityteam.aeropages.format.odf.OdsView.read(settings, sheets)
        val (a, b) = sheets
        assertEquals(1, a.freezeRows); assertEquals(0, a.freezeCols); assertEquals(120, a.zoom); assertTrue(a.showGridlines)
        assertEquals("a split is not frozen", 0, b.freezeCols)
        assertEquals("the view's zoom where the sheet has none", 90, b.zoom); assertFalse(b.showGridlines)
        assertTrue(sheets.none { it.showHeadings })
    }

    @Test
    fun theViewTabsCommands() {
        val e = SheetEditor().apply { load(Workbook.blank()) }
        e.markSaved()
        e.setZoom(1000); assertEquals(Sheet.MAX_ZOOM, e.sheet.zoom)
        e.setZoom(1); assertEquals(Sheet.MIN_ZOOM, e.sheet.zoom)
        e.setZoom(130)
        assertFalse("zoom is a view, not an edit", e.dirty)
        assertFalse(e.canUndo)
        e.toggleGridlines()
        assertFalse(e.sheet.showGridlines)
        e.undo()
        assertTrue(e.sheet.showGridlines)
        assertEquals("undo leaves the zoom", 130, e.sheet.zoom)
        // Freeze Top Row and First Column freeze just that, wherever the selection is (it stays).
        e.select(5, 3)
        e.freezeTopRow()
        assertEquals(1, e.sheet.freezeRows); assertEquals(0, e.sheet.freezeCols)
        assertEquals(5, e.selRow); assertEquals(3, e.selCol)
        e.freezeFirstColumn()
        assertEquals(0, e.sheet.freezeRows); assertEquals(1, e.sheet.freezeCols)
        e.unfreeze(); assertFalse(e.isFrozen)
    }

    /** With `-Daero.sampleDir`: a workbook zoomed, without gridlines, frozen, for LibreOffice. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        File(dir, "view.xlsx").outputStream().use { XlsxWriter.write(workbook(), it) }
        File(dir, "view.ods").outputStream().use { OdsWriter.write(workbook(), it) }
    }
}
