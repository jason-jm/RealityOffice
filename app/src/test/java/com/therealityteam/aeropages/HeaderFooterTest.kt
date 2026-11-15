package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideChrome
import com.therealityteam.aeropages.model.SlideDate
import com.therealityteam.aeropages.model.SlideLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipInputStream

/** Header & Footer: the date (updated or fixed), the footer, the slide number, not on title slides — shown alike and kept in pptx and ODP. */
class HeaderFooterTest {

    private val now = LocalDateTime.of(2026, 10, 1, 14, 5)

    @Test
    fun datesShowInTheirFormats() {
        val shown = SlideDate.FORMATS.map { SlideDate(it).text(now) }
        assertEquals(listOf("2026/10/1", "2026年10月1日", "2026年10月1日 星期四", "2026-10-01", "10/1/2026", "October 1, 2026", "1 October 2026", "Thursday, October 1, 2026"), shown)
        assertEquals("a fixed date is its text", "Q4 review", SlideDate(fixed = "Q4 review").text(now))
    }

    @Test
    fun powerPointDateFieldsComeBackAsTheirFormats() {
        SlideDate.FORMATS.forEach { f ->
            val (type, _) = com.therealityteam.aeropages.format.pptx.DateFields.fieldOf(f)
            assertEquals(f, com.therealityteam.aeropages.format.pptx.DateFields.formatOf(type, SlideDate(f).text(now)))
        }
        assertEquals("PowerPoint's own, by its type", "[$-409]mmm-yy", com.therealityteam.aeropages.format.pptx.DateFields.formatOf("datetime7", "Oct-26"))
    }

    private fun deck(date: SlideDate? = SlideDate("yyyy\"年\"m\"月\"d\"日\""), hideOnTitle: Boolean = true) = Presentation(mutableListOf(
        Slide(title = SlideBox("Cover"), layout = SlideLayout.TITLE),
        Slide(title = SlideBox("Agenda"), body = SlideBox("One")),
        Slide(title = SlideBox("Plan"), body = SlideBox("Two")),
    ), footer = "AeroDesk", showSlideNumber = true, date = date, hideFooterOnTitle = hideOnTitle)

    @Test
    fun partsAtTheFootNotOnTitleSlides() {
        val p = deck()
        assertTrue(SlideChrome.partsOf(p, p.slides[0], 1, now).isEmpty())
        assertEquals(listOf(SlideChrome.Part.DATE to "2026年10月1日", SlideChrome.Part.FOOTER to "AeroDesk", SlideChrome.Part.NUMBER to "2"),
            SlideChrome.partsOf(p, p.slides[1], 2, now))
        p.hideFooterOnTitle = false
        assertEquals(3, SlideChrome.partsOf(p, p.slides[0], 1, now).size)
        assertTrue("left, centre, right", SlideChrome.Part.DATE.xPct < SlideChrome.Part.FOOTER.xPct && SlideChrome.Part.FOOTER.xPct < SlideChrome.Part.NUMBER.xPct)
    }

    private fun parts(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; out[e.name] = String(z.readBytes()) } }
        return out
    }

    private fun assertSame(want: Presentation, got: Presentation) {
        assertEquals(want.footer, got.footer)
        assertEquals(want.showSlideNumber, got.showSlideNumber)
        assertEquals(want.date, got.date)
        assertEquals(want.hideFooterOnTitle, got.hideFooterOnTitle)
    }

    @Test
    fun pptxKeepsThem() {
        val bytes = ByteArrayOutputStream().also { PptxWriter.write(deck(), it) }.toByteArray()
        val files = parts(bytes)
        assertFalse("the title slide goes without", files["ppt/slides/slide1.xml"]!!.contains("type=\"ftr\""))
        val s2 = files["ppt/slides/slide2.xml"]!!
        assertTrue(s2.contains("type=\"dt\"") && s2.contains("type=\"datetime4\"") && s2.contains("type=\"ftr\"") && s2.contains("type=\"slidenum\""))
        assertTrue("PowerPoint's own Header & Footer finds where they go", files["ppt/slideMasters/slideMaster1.xml"]!!.contains("type=\"sldNum\""))
        assertTrue(files["ppt/slideLayouts/slideLayout2.xml"]!!.contains("type=\"dt\""))
        assertSame(deck(), PptxReader.read(bytes))
        val fixed = deck(SlideDate(fixed = "Q4 review"), hideOnTitle = false)
        assertSame(fixed, PptxReader.read(ByteArrayOutputStream().also { PptxWriter.write(fixed, it) }.toByteArray()))
    }

    @Test
    fun odpKeepsThem() {
        val bytes = ByteArrayOutputStream().also { OdpWriter.write(deck(), it) }.toByteArray()
        val files = parts(bytes)
        val content = files["content.xml"]!!
        assertTrue(content.contains("<presentation:footer-decl presentation:name=\"ftr1\">AeroDesk</presentation:footer-decl>"))
        assertTrue(content.contains("presentation:source=\"current-date\" style:data-style-name=\"DateField1\""))
        assertTrue("its format a data style LibreOffice draws, the format its title",
            content.contains("<number:date-style style:name=\"DateField1\" number:title=\"yyyy&quot;年&quot;m&quot;月&quot;d&quot;日&quot;\"><number:day/>"))
        assertTrue(content.contains("presentation:use-footer-name=\"ftr1\""))
        assertTrue("the title page's style switches them off", content.contains("presentation:display-footer=\"false\" presentation:display-page-number=\"false\" presentation:display-date-time=\"false\""))
        val styles = files["styles.xml"]!!
        assertTrue("the master's frames", styles.contains("presentation:class=\"page-number\"") && styles.contains("<presentation:footer/>") && styles.contains("<presentation:date-time/>"))
        assertSame(deck(), OdpReader.read(bytes))
        val fixed = deck(SlideDate(fixed = "Q4 review"), hideOnTitle = false)
        assertSame(fixed, OdpReader.read(ByteArrayOutputStream().also { OdpWriter.write(fixed, it) }.toByteArray()))
        val none = Presentation(mutableListOf(Slide(title = SlideBox("Plain"))))
        val back = OdpReader.read(ByteArrayOutputStream().also { OdpWriter.write(none, it) }.toByteArray())
        assertEquals("", back.footer); assertFalse(back.showSlideNumber); assertNull(back.date)
    }

    /** A date style LibreOffice wrote (no title of the app's): the app's format of its kind. */
    @Test
    fun libreOfficesDateStylesByKind() {
        listOf("yyyy-mm-dd", "[$-409]d mmmm yyyy", "[$-409]dddd, mmmm d, yyyy").forEach { f ->
            val bytes = ByteArrayOutputStream().also { OdpWriter.write(deck(SlideDate(f)), it) }.toByteArray()
            val out = ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(out).use { z ->
                ZipInputStream(bytes.inputStream()).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        var data = zin.readBytes()
                        if (e.name == "content.xml") data = String(data).replace(Regex(""" number:title="[^"]*""""), "").toByteArray()
                        z.putNextEntry(java.util.zip.ZipEntry(e.name)); z.write(data); z.closeEntry()
                    }
                }
            }
            assertEquals(SlideDate.formatOf(SlideDate.kindOf(f)), OdpReader.read(out.toByteArray()).date?.format)
        }
    }

    @Test
    fun headerAndFooterUndo() {
        val e = SlideEditor(); e.load(Presentation.blank())
        e.setFooter("AeroDesk", true, SlideDate("yyyy-mm-dd"), hideOnTitle = true)
        assertEquals(SlideDate("yyyy-mm-dd"), e.presentation.date); assertTrue(e.presentation.hideFooterOnTitle)
        e.undo()
        assertNull(e.presentation.date); assertFalse(e.presentation.hideFooterOnTitle); assertEquals("", e.presentation.footer)
        e.redo()
        assertEquals("AeroDesk", e.presentation.footer); assertEquals(SlideDate("yyyy-mm-dd"), e.presentation.date)
    }
}
