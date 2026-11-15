package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.SheetImage
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Alt Text on slides' and sheets' pictures: set in one undo step, kept through pptx, ODP, xlsx and ODS. */
class AltTextSlidesSheetsTest {
    private val described = "A team of five around a whiteboard & \"plans\"."

    private fun deck() = Presentation(mutableListOf(Slide(title = SlideBox("Team")).also { s ->
        s.images.add(SlideImage(TestPng.blend(40, 20, 0x2E75B6), "image/png", 40f, 20f, altText = described))
        s.images.add(SlideImage(TestPng.blend(20, 20, 0xC55A11), "image/png", 20f, 20f, xPct = 0.7f, decorative = true))
    }))

    private fun book() = Workbook(mutableListOf(Sheet(name = "Photos").also { s ->
        s.images.add(SheetImage(TestPng.blend(40, 20, 0x2E75B6), "image/png", 1, 1, widthPt = 80f, heightPt = 40f, name = "Picture 1").also { it.altText = described })
        s.images.add(SheetImage(TestPng.blend(20, 20, 0xC55A11), "image/png", 6, 1, widthPt = 20f, heightPt = 20f, name = "Picture 2").also { it.decorative = true })
    }))

    private fun slideAlts(p: Presentation) = p.slides[0].images.map { it.altText to it.decorative }
    private fun sheetAlts(w: Workbook) = w.sheets[0].images.map { it.altText to it.decorative }

    @Test
    fun pptxAndOdpKeepTheDescriptionAndTheDecorativeMark() {
        val pptx = ByteArrayOutputStream().also { PptxWriter.write(deck(), it) }.toByteArray()
        assertEquals(listOf(described to false, "" to true), slideAlts(PptxReader.read(pptx)))
        val odp = ByteArrayOutputStream().also { OdpWriter.write(deck(), it) }.toByteArray()
        assertEquals(listOf(described to false, "" to true), slideAlts(OdpReader.read(odp)))
    }

    @Test
    fun xlsxAndOdsKeepTheDescriptionAndTheDecorativeMark() {
        val xlsx = ByteArrayOutputStream().also { XlsxWriter.write(book(), it) }.toByteArray()
        assertEquals(listOf(described to false, "" to true), sheetAlts(XlsxReader.read(xlsx)))
        val ods = ByteArrayOutputStream().also { OdsWriter.write(book(), it) }.toByteArray()
        assertEquals(listOf(described to false, "" to true), sheetAlts(OdsReader.read(ods)))
    }

    @Test
    fun theEditorsSetItInOneStep() {
        val slides = SlideEditor().also { it.load(deck()) }
        slides.selectImage(slides.slide.images[0])
        slides.setImageAltText("  On stage  ", decorative = false)
        assertEquals("On stage", slides.slide.images[0].altText)
        slides.undo()
        assertEquals(described, slides.slide.images[0].altText)

        val sheets = SheetEditor().also { it.load(book()) }
        val img = sheets.sheet.images[0]
        sheets.setImageAltText(img, "ignored", decorative = true)
        sheets.sheet.images[0].let { assertTrue(it.decorative); assertEquals("", it.altText) }
        val rev = sheets.revision
        sheets.setImageAltText(sheets.sheet.images[0], "", decorative = true)
        assertEquals("nothing changed: no step", rev, sheets.revision)
    }

    /** With `-Daero.sampleDir`: the deck and the workbook in all four formats, for LibreOffice. */
    @Test
    fun writeSamples() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        File(dir, "alt-text.pptx").outputStream().use { PptxWriter.write(deck(), it) }
        File(dir, "alt-text.odp").outputStream().use { OdpWriter.write(deck(), it) }
        File(dir, "alt-text.xlsx").outputStream().use { XlsxWriter.write(book(), it) }
        File(dir, "alt-text.ods").outputStream().use { OdsWriter.write(book(), it) }
    }
}
