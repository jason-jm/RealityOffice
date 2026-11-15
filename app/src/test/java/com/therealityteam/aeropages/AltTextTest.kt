package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** A picture's Alt Text and decorative mark: set in one undo step, kept through docx and ODT. */
class AltTextTest {
    private fun doc() = Document(mutableListOf(
        ParagraphBlock(Paragraph.of("Before")),
        ImageBlock(TestPng.header(40, 20), "image/png", 80f, 40f, altText = "A bar chart: sales rose 12% in Q3 & \"held\"."),
        ImageBlock(TestPng.header(20, 20), "image/png", 20f, 20f, decorative = true),
        ParagraphBlock(Paragraph.of("After")),
    ))

    private fun pictures(d: Document) = d.blocks.filterIsInstance<ImageBlock>()

    private fun entry(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().decodeToString() }
        }
        return ""
    }

    @Test
    fun docxKeepsTheDescriptionAndTheDecorativeMark() {
        val out = ByteArrayOutputStream()
        DocxWriter.write(doc(), out)
        val xml = entry(out.toByteArray(), "word/document.xml")
        assertTrue(xml.contains("""descr="A bar chart: sales rose 12% in Q3 &amp; &quot;held&quot;."""))
        assertTrue(xml.contains("adec:decorative"))
        val back = pictures(DocxReader.read(ByteArrayInputStream(out.toByteArray())))
        assertEquals("A bar chart: sales rose 12% in Q3 & \"held\".", back[0].altText)
        assertFalse(back[0].decorative)
        assertTrue(back[1].decorative)
        assertEquals("", back[1].altText)
    }

    @Test
    fun odtKeepsTheDescriptionAndTheDecorativeMark() {
        val out = ByteArrayOutputStream()
        OdtWriter.write(doc(), out)
        val xml = entry(out.toByteArray(), "content.xml")
        assertTrue(xml.contains("<svg:desc>A bar chart"))
        val styles = xml + entry(out.toByteArray(), "styles.xml")
        assertTrue("in the picture's style, as LibreOffice writes it", styles.contains("""loext:decorative="true""""))
        assertTrue("pictures come from LibreOffice's Graphics style", xml.contains("""style:parent-style-name="Graphics""""))
        val back = pictures(OdtReader.read(out.toByteArray()))
        assertEquals("A bar chart: sales rose 12% in Q3 & \"held\".", back[0].altText)
        assertTrue(back[1].decorative)
    }

    @Test
    fun altTextIsOneUndoStepAndDecorativePutsTheDescriptionAside() {
        val d = DocumentEditor()
        d.load(doc())
        val first = pictures(d.document)[0]
        d.setImageAltText(first, "  A new description  ", decorative = false)
        assertEquals("A new description", pictures(d.document)[0].altText)
        d.undo()
        assertEquals("A bar chart: sales rose 12% in Q3 & \"held\".", pictures(d.document)[0].altText)
        d.setImageAltText(pictures(d.document)[0], "ignored", decorative = true)
        pictures(d.document)[0].let { assertTrue(it.decorative); assertEquals("", it.altText) }
        val rev = d.revision
        d.setImageAltText(pictures(d.document)[0], "", decorative = true)
        assertEquals("nothing changed: no step", rev, d.revision)
    }

    /** With `-Daero.sampleDir`: the document as docx and ODT, for LibreOffice and the canvas. */
    @Test
    fun writeSamples() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        File(dir, "alt-text.docx").outputStream().use { DocxWriter.write(doc(), it) }
        File(dir, "alt-text.odt").outputStream().use { OdtWriter.write(doc(), it) }
    }
}
