package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.SheetImage
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pictures over a sheet (Insert → Pictures): the editor's commands, and the picture in xlsx and ODS. */
class SheetPicturesTest {

    private val png = TestPng.header(320, 240)   // 240 × 180 pt at 96 dpi

    private fun workbookWithPicture(): Workbook {
        val wb = Workbook.blank()
        val s = wb.sheets[0]
        s.ensure(0, 0).text = "Logo below"
        s.ensure(2, 1).text = "under the picture"
        val img = SheetImage(png, "image/png", row = 2, col = 1, dxPt = 6f, dyPt = 4f, widthPt = 160f, heightPt = 120f, name = "Logo")
        img.setCrop(0.1f, 0f, 0.05f, 0.2f)
        s.images.add(img)
        s.images.add(SheetImage(png, "image/png", row = 7, col = 0, widthPt = 48f, heightPt = 36f, name = "Icon"))
        return wb
    }

    private fun assertSamePicture(expected: SheetImage, actual: SheetImage) {
        assertEquals(expected.row, actual.row); assertEquals(expected.col, actual.col)
        assertEquals(expected.dxPt, actual.dxPt, 0.01f); assertEquals(expected.dyPt, actual.dyPt, 0.01f)
        assertEquals(expected.widthPt, actual.widthPt, 0.01f); assertEquals(expected.heightPt, actual.heightPt, 0.01f)
        assertEquals(expected.name, actual.name)
        assertEquals(expected.cropL, actual.cropL, 0.001f); assertEquals(expected.cropT, actual.cropT, 0.001f)
        assertEquals(expected.cropR, actual.cropR, 0.001f); assertEquals(expected.cropB, actual.cropB, 0.001f)
        assertTrue(expected.data.contentEquals(actual.data))
        assertEquals("image/png", actual.mime)
    }

    @Test
    fun xlsxKeepsAPictureAnchoredToItsCell() {
        val wb = workbookWithPicture()
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(wb, bos)
        val parts = OfficeXml.unzip(bos.toByteArray())
        val drawing = String(parts["xl/drawings/drawing1.xml"]!!, Charsets.UTF_8)
        assertTrue("a one-cell anchor: moves with its cell, keeps its size",
            drawing.contains("<xdr:oneCellAnchor><xdr:from><xdr:col>1</xdr:col><xdr:colOff>76200</xdr:colOff><xdr:row>2</xdr:row><xdr:rowOff>50800</xdr:rowOff></xdr:from><xdr:ext cx=\"2032000\" cy=\"1524000\"/>"))
        assertTrue(drawing.contains("<a:srcRect l=\"10000\" r=\"5000\" b=\"20000\"/>"))
        assertTrue(parts.containsKey("xl/media/image1.png") && parts.containsKey("xl/media/image2.png"))
        assertTrue(String(parts["[Content_Types].xml"]!!, Charsets.UTF_8).contains("<Default Extension=\"png\" ContentType=\"image/png\"/>"))
        val back = XlsxReader.read(bos.toByteArray()).sheets[0]
        assertEquals(2, back.images.size)
        wb.sheets[0].images.zip(back.images).forEach { (a, b) -> assertSamePicture(a, b) }
        assertEquals("the cell under the picture keeps its text", "under the picture", back.cell(2, 1)?.text)
    }

    @Test
    fun odsPutsAPictureInItsAnchorCell() {
        val wb = workbookWithPicture()
        val bos = ByteArrayOutputStream()
        OdsWriter.write(wb, bos)
        val parts = OfficeXml.unzip(bos.toByteArray())
        val content = String(parts["content.xml"]!!, Charsets.UTF_8)
        // As LibreOffice keeps a cell-anchored picture: inside the cell, placed from its corner.
        val frame = Regex("""<table:table-cell[^>]*>(<text:p>under the picture</text:p>)<draw:frame [^>]*draw:name="Logo"[^>]*svg:x="6.00pt" svg:y="4.00pt">""")
        assertTrue(content, frame.containsMatchIn(content))
        assertTrue("an empty anchor cell holds its picture", content.contains("<table:table-cell><draw:frame draw:z-index=\"1\" draw:name=\"Icon\""))
        assertTrue("the crop is the frame style's clip", Regex("""fo:clip="rect\(0.00pt, 12.00pt, 36.00pt, 24.00pt\)"""").containsMatchIn(content))
        assertTrue(String(parts["META-INF/manifest.xml"]!!, Charsets.UTF_8).contains("manifest:full-path=\"Pictures/image1.png\" manifest:media-type=\"image/png\""))
        val back = OdsReader.read(bos.toByteArray()).sheets[0]
        assertEquals(2, back.images.size)
        wb.sheets[0].images.zip(back.images).forEach { (a, b) -> assertSamePicture(a, b) }
        assertEquals("under the picture", back.cell(2, 1)?.text)
    }

    @Test
    fun odsPageAnchoredPicturesFindTheirCell() {
        // LibreOffice's "To page" anchor: the frame in table:shapes, at a position on the sheet.
        val content = """<?xml version="1.0" encoding="UTF-8"?><office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">""" +
            """<office:body><office:spreadsheet><table:table table:name="Sheet1"><table:shapes><draw:frame draw:name="Page pic" svg:width="100pt" svg:height="50pt" svg:x="140pt" svg:y="40pt">""" +
            """<draw:image xlink:href="Pictures/p.png"/></draw:frame></table:shapes><table:table-column/><table:table-row><table:table-cell office:value-type="string"><text:p>x</text:p></table:table-cell></table:table-row></table:table></office:spreadsheet></office:body></office:document-content>"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("content.xml")); z.write(content.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("Pictures/p.png")); z.write(png); z.closeEntry()
        }
        val img = OdsReader.read(bos.toByteArray()).sheets[0].images.single()
        // Columns are 64 pt and rows 15 pt by default: 140 pt is 12 pt into C, 40 pt is 10 pt into row 3.
        assertEquals(2, img.col); assertEquals(12f, img.dxPt, 0.01f)
        assertEquals(2, img.row); assertEquals(10f, img.dyPt, 0.01f)
        assertEquals(100f, img.widthPt, 0.01f); assertEquals("Page pic", img.name)
    }

    @Test
    fun xlsxTwoCellAndAbsoluteAnchorsAreRead() {
        val xdr = "xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
        fun pic(id: Int) = "<xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"$id\" name=\"P$id\"/><xdr:cNvPicPr/></xdr:nvPicPr><xdr:blipFill><a:blip r:embed=\"rId1\"/></xdr:blipFill><xdr:spPr/></xdr:pic>"
        val drawing = "<xdr:wsDr $xdr>" +
            // Two-cell, sized by its cells: B3 + 12 700 EMU (1 pt) to D5 (two 48 pt columns less 1 pt, two 15 pt rows).
            "<xdr:twoCellAnchor><xdr:from><xdr:col>1</xdr:col><xdr:colOff>12700</xdr:colOff><xdr:row>2</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>" +
            "<xdr:to><xdr:col>3</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>4</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>${pic(2)}<xdr:clientData/></xdr:twoCellAnchor>" +
            // Absolute: 100 pt across (two 48 pt columns and 4 pt), 20 pt down (a 15 pt row and 5 pt).
            "<xdr:absoluteAnchor><xdr:pos x=\"1270000\" y=\"254000\"/><xdr:ext cx=\"635000\" cy=\"381000\"/>${pic(3)}<xdr:clientData/></xdr:absoluteAnchor></xdr:wsDr>"
        val parts = linkedMapOf(
            "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
            "xl/workbook.xml" to "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
            "xl/worksheets/sheet1.xml" to "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheetData/><drawing r:id=\"rId1\"/></worksheet>",
            "xl/worksheets/_rels/sheet1.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing\" Target=\"../drawings/drawing1.xml\"/></Relationships>",
            "xl/drawings/drawing1.xml" to drawing,
            "xl/drawings/_rels/drawing1.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/image1.jpeg\"/></Relationships>",
        )
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            parts.forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
            z.putNextEntry(ZipEntry("xl/media/image1.jpeg")); z.write(png); z.closeEntry()
        }
        val (two, abs) = XlsxReader.read(bos.toByteArray()).sheets[0].images
        assertEquals(2, two.row); assertEquals(1, two.col); assertEquals(1f, two.dxPt, 0.01f)
        assertEquals(95f, two.widthPt, 0.01f); assertEquals(30f, two.heightPt, 0.01f)
        assertEquals("its bytes say PNG, whatever its name", "image/png", two.mime); assertEquals("P2", two.name)
        assertEquals(1, abs.row); assertEquals(2, abs.col)
        assertEquals(4f, abs.dxPt, 0.01f); assertEquals(5f, abs.dyPt, 0.01f)
        assertEquals(50f, abs.widthPt, 0.01f); assertEquals(30f, abs.heightPt, 0.01f)
    }

    private fun editor(): SheetEditor = SheetEditor().also { it.load(Workbook.blank()) }

    @Test
    fun insertingPlacesThePictureAtTheActiveCellAndPicksIt() {
        val e = editor()
        e.select(3, 2)
        assertTrue(e.insertImage(png, "image/png"))
        val img = e.sheet.images.single()
        assertEquals(3, img.row); assertEquals(2, img.col)
        assertEquals("its natural size (320 × 240 px at 96 dpi)", 240f, img.widthPt, 0.01f)
        assertEquals(180f, img.heightPt, 0.01f)
        assertEquals("Picture 1", img.name)
        assertSame(img, e.selectedImage)
        // A big one shrinks to fit, keeping its proportions; the next gets the next name.
        e.select(0, 0)
        assertNull("picking a cell drops the picture", e.selectedImage)
        e.insertImage(TestPng.header(2400, 1200), "image/png")
        val big = e.sheet.images[1]
        assertEquals(SheetEditor.MAX_PICTURE_W, big.widthPt, 0.01f)
        assertEquals(SheetEditor.MAX_PICTURE_W / 2f, big.heightPt, 0.01f)
        assertEquals("Picture 2", big.name)
        assertFalse("not a picture", e.insertImage(byteArrayOf(1, 2, 3), "image/png"))
        e.undo()
        assertEquals(1, e.sheet.images.size)
        assertNull(e.selectedImage)
    }

    @Test
    fun movingReanchorsAtTheCellUnderTheCorner() {
        val e = editor()
        e.select(1, 1)
        e.insertImage(png, "image/png")
        val img = e.sheet.images.single()
        val col = SheetEditor.GRID_COL_PT
        val (x, y) = e.imageOrigin(img)
        assertEquals(col, x, 0.01f); assertEquals(15f, y, 0.01f)
        // Half a column right and two and a third rows down: C3, 5 pt into it.
        e.moveImage(img, x + col * 1.5f, y + 35f)
        assertEquals(2, img.col); assertEquals(col * 0.5f, img.dxPt, 0.01f)
        assertEquals(3, img.row); assertEquals(5f, img.dyPt, 0.01f)
        // Hidden columns take no room: hiding B puts the picture's corner a column further left on the grid.
        e.selectColumns(1); e.hideColumns()
        assertEquals(col * 1.5f, e.imageOrigin(img).first, 0.01f)
        e.undo(); e.undo()
        val back = e.sheet.images.single()
        assertEquals(1, back.col); assertEquals(1, back.row); assertEquals(0f, back.dxPt, 0f)
        // Past the top-left corner it stops at A1.
        e.moveImage(back, -50f, -50f)
        assertEquals(0, back.row); assertEquals(0, back.col); assertEquals(0f, back.dxPt, 0f)
    }

    @Test
    fun resizingCroppingAndResetting() {
        val e = editor()
        e.insertImage(png, "image/png")
        val img = e.sheet.images.single()
        e.resizeImage(img, 120f, 90f)
        assertEquals(120f, img.widthPt, 0f); assertEquals(90f, img.heightPt, 0f)
        e.resizeImage(img, 1f, 1f)
        assertEquals("never smaller than a few points", SheetEditor.MIN_PICTURE_PT, img.widthPt, 0f)
        e.undo()
        val pic = e.sheet.images.single()
        // Cropping a quarter off the left keeps the rest where it was: the frame narrows, its corner moves right.
        e.cropImage(pic, 0.25f, 0f, 0f, 0.5f)
        assertEquals(90f, pic.widthPt, 0.01f); assertEquals(45f, pic.heightPt, 0.01f)
        assertEquals(30f, e.imageOrigin(pic).first, 0.01f)
        // Reset Size: the part the crop leaves at its natural size (240 × 180 pt whole).
        e.resetImageSize(pic)
        assertEquals(180f, pic.widthPt, 0.01f); assertEquals(90f, pic.heightPt, 0.01f)
        // A new frame from a corner handle: moved and sized in one step.
        e.setImageFrame(pic, 10f, 20f, 60f, 30f)
        assertEquals(0, pic.col); assertEquals(10f, pic.dxPt, 0.01f)
        assertEquals(1, pic.row); assertEquals(5f, pic.dyPt, 0.01f)
        assertEquals(60f, pic.widthPt, 0f)
        e.undo()
        assertEquals(180f, e.sheet.images.single().widthPt, 0.01f)
    }

    @Test
    fun keysDuplicateAndDelete() {
        val e = editor()
        e.select(2, 2)
        e.insertImage(png, "image/png")
        val img = e.sheet.images.single()
        val depth = e.undoDepth
        repeat(3) { e.nudgeSelectedImage(1f, 0f) }
        e.nudgeSelectedImage(0f, -1f)
        assertEquals("a run of nudges is one step", depth + 1, e.undoDepth)
        assertEquals(3f, img.dxPt, 0.01f)
        assertEquals("up a point from the top of C3: 14 pt into C2", 1, img.row)
        assertEquals(14f, img.dyPt, 0.01f)
        e.duplicateSelectedImage()
        val copy = e.sheet.images[1]
        assertSame(copy, e.selectedImage)
        assertEquals("Picture 2", copy.name)
        assertEquals(e.imageOrigin(img).first + SheetEditor.DUPLICATE_OFFSET_PT, e.imageOrigin(copy).first, 0.01f)
        e.deleteSelectedImage()
        assertEquals(listOf(img), e.sheet.images)
        assertNull(e.selectedImage)
        e.selectImage(img)
        e.selectSheet(0)
        assertNull("switching sheets drops the pick", e.selectedImage)
    }

    @Test
    fun picturesMoveWithTheirRowsAndColumns() {
        val e = editor()
        e.select(4, 3)
        e.insertImage(png, "image/png")
        val img = e.sheet.images.single()
        e.insertRowAt(1)
        assertEquals(5, img.row)
        e.deleteColAt(0)
        assertEquals(2, img.col)
        e.deleteRowAt(5)
        assertEquals("its own row going leaves it on the row that slides up", 5, img.row)
        e.insertColAt(4)
        assertEquals("a column inserted right of it does not move it", 2, img.col)
    }

    @Test
    fun aProtectedSheetKeepsItsPictures() {
        val e = editor()
        e.insertImage(png, "image/png")
        val img = e.sheet.images.single()
        e.toggleProtection()
        assertFalse(e.insertImage(png, "image/png"))
        e.selectImage(img)
        e.deleteSelectedImage()
        e.moveImage(img, 200f, 200f)
        assertEquals(1, e.sheet.images.size)
        assertEquals(0, img.row); assertEquals(0, img.col)
    }
}
