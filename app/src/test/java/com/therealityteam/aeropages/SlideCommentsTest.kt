package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.SlideComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Slide comments as PowerPoint keeps them (classic `p:cmLst` with commentAuthors.xml, replies by `p15:threadingInfo`; modern
 * `p188:cmLst` read) and as LibreOffice does in ODP (`officeooo:annotation`).
 */
class SlideCommentsTest {
    private val t0 = 1_760_000_000_000L
    fun deck(): Presentation = Presentation.blank().also { p ->
        p.slides[0].title.paragraph.runs[0].text = "Quarterly review"
        p.slides[0].comments.add(SlideComment("Ann Lee", "Can we show last year's figure beside this one?", t0, 0.1f, 0.2f,
            replies = listOf(SlideComment("Bo", "Added it to the notes.", t0 + 60_000))))
        p.slides[0].comments.add(SlideComment("Bo", "Title reads well.", t0 + 120_000, 0.5f, 0.05f, resolved = false))
    }
    private fun pptx(p: Presentation) = ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray()
    private fun part(zip: ByteArray, name: String): String? {
        ZipInputStream(zip.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().toString(Charsets.UTF_8) } }
        return null
    }

    @Test
    fun pptxWritesPowerPointsCommentsAndReadsThemBack() {
        val bytes = pptx(deck())
        val authors = part(bytes, "ppt/commentAuthors.xml")!!
        assertTrue(authors, authors.contains("""<p:cmAuthor id="0" name="Ann Lee" initials="AL" lastIdx="1" clrIdx="0"/>"""))
        assertTrue(authors, authors.contains("""<p:cmAuthor id="1" name="Bo" initials="B" lastIdx="2" clrIdx="1"/>"""))
        val cm = part(bytes, "ppt/comments/comment1.xml")!!
        // 0.1 of 960 pt = 96 pt = 768 eighths; 0.2 of 540 = 108 pt = 864.
        assertTrue(cm, cm.contains("""<p:cm authorId="0" dt="2025-10-09T08:53:20.000" idx="1"><p:pos x="768" y="864"/><p:text>Can we show last year&apos;s figure beside this one?</p:text></p:cm>""") ||
            cm.contains("""<p:pos x="768" y="864"/><p:text>Can we show last year's figure beside this one?</p:text>"""))
        assertTrue(cm, cm.contains("""<p15:parentCm authorId="0" idx="1"/>"""))
        assertTrue(part(bytes, "ppt/slides/_rels/slide1.xml.rels")!!.contains("relationships/comments"))
        assertTrue(part(bytes, "[Content_Types].xml")!!.contains("/ppt/comments/comment1.xml"))
        assertTrue(part(bytes, "ppt/_rels/presentation.xml.rels")!!.contains("commentAuthors.xml"))
        assertEquals("no custom property any more", null, part(bytes, "docProps/custom.xml"))
        val back = PptxReader.read(bytes).slides[0].comments
        assertEquals(2, back.size)
        val first = back[0]
        assertEquals("Ann Lee", first.author); assertEquals(t0, first.dateMs)
        assertEquals(0.1f, first.x, 0.002f); assertEquals(0.2f, first.y, 0.002f)
        assertEquals(listOf("Bo" to "Added it to the notes."), first.replies.map { it.author to it.text })
        assertEquals("Title reads well.", back[1].text)
    }

    @Test
    fun modernCommentsAreRead() {
        val bytes = pptx(deck())
        // Replace the classic part with a modern one (PowerPoint 365's), its authors in authors.xml.
        val modern = """<?xml version="1.0" encoding="UTF-8"?><p188:cmLst xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p188="http://schemas.microsoft.com/office/powerpoint/2018/8/main">""" +
            """<p188:cm id="{A}" authorId="{X}" created="2026-10-20T08:00:00.000" status="resolved"><p188:replyLst><p188:reply id="{B}" authorId="{Y}" created="2026-10-20T09:00:00.000"><p188:txBody><a:bodyPr/><a:p><a:r><a:t>Done.</a:t></a:r></a:p></p188:txBody></p188:reply></p188:replyLst>""" +
            """<p188:txBody><a:bodyPr/><a:p><a:r><a:t>Check the </a:t></a:r><a:r><a:t>total</a:t></a:r></a:p><a:p><a:r><a:t>again.</a:t></a:r></a:p></p188:txBody></p188:cm></p188:cmLst>"""
        val authors = """<?xml version="1.0" encoding="UTF-8"?><p188:authorLst xmlns:p188="http://schemas.microsoft.com/office/powerpoint/2018/8/main"><p188:author id="{X}" name="Chen" initials="C" userId="Chen" providerId="None"/><p188:author id="{Y}" name="Dana" initials="D" userId="Dana" providerId="None"/></p188:authorLst>"""
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { o ->
            ZipInputStream(bytes.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    o.putNextEntry(java.util.zip.ZipEntry(e.name)); o.write(if (e.name == "ppt/comments/comment1.xml") modern.toByteArray() else z.readBytes()); o.closeEntry()
                }
            }
            o.putNextEntry(java.util.zip.ZipEntry("ppt/authors.xml")); o.write(authors.toByteArray()); o.closeEntry()
        }
        val c = PptxReader.read(out.toByteArray()).slides[0].comments.single()
        assertEquals("Chen", c.author); assertEquals("Check the total\nagain.", c.text); assertTrue(c.resolved)
        assertEquals(listOf("Dana" to "Done."), c.replies.map { it.author to it.text })
    }

    @Test
    fun odpKeepsCommentsAsLibreOfficeDoes() {
        fun odp(p: Presentation) = ByteArrayOutputStream().also { com.therealityteam.aeropages.format.odf.OdpWriter.write(p, it) }.toByteArray()
        val d = deck().also { it.slides[0].comments[1] = it.slides[0].comments[1].copy(resolved = true) }
        val bytes = odp(d)
        val content = part(bytes, "content.xml")!!
        // After the notes, at the page's end; the reply an annotation of its own, marked for this app.
        assertTrue(content, Regex("""<officeooo:annotation svg:x="96.00pt" svg:y="108.00pt"[^>]*><dc:creator>Ann Lee</dc:creator><meta:creator-initials>AL</meta:creator-initials><dc:date>2025-10-09T08:53:20</dc:date><text:p>""").containsMatchIn(content))
        assertTrue(content.contains("""aero:reply="true""""))
        assertTrue(content.indexOf("<officeooo:annotation") < content.indexOf("</draw:page>"))
        val back = com.therealityteam.aeropages.format.odf.OdpReader.read(bytes).slides[0].comments
        assertEquals(listOf("Ann Lee", "Bo"), back.map { it.author })
        assertEquals(listOf("Bo" to "Added it to the notes."), back[0].replies.map { it.author to it.text })
        assertEquals(t0, back[0].dateMs); assertEquals(0.1f, back[0].x, 0.002f)
        assertTrue(back[1].resolved)
        // LibreOffice's own (no marks): each annotation a comment.
        val lo = content.replace(""" aero:reply="true"""", "").replace(""" aero:resolved="true"""", "")
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { o ->
            ZipInputStream(bytes.inputStream()).use { z ->
                while (true) { val e = z.nextEntry ?: break; o.putNextEntry(java.util.zip.ZipEntry(e.name)); o.write(if (e.name == "content.xml") lo.toByteArray() else z.readBytes()); o.closeEntry() }
            }
        }
        assertEquals(3, com.therealityteam.aeropages.format.odf.OdpReader.read(out.toByteArray()).slides[0].comments.size)
    }

    @Test
    fun libreOfficesCommentsAreRead() {
        // LibreOffice's pptx of our ODP: classic comments, its authors listed in another order, the reply one of its own.
        val c = PptxReader.read(java.io.File("src/test/resources/pptx/comments.pptx").readBytes()).slides[0].comments
        assertEquals(listOf("Ann Lee" to "Can we show last year's figure beside this one?", "Bo" to "Added it to the notes.", "Bo" to "Title reads well."),
            c.map { it.author to it.text })
        assertEquals(t0, c[0].dateMs)
    }

    /** Samples (with `-Daero.sampleDir`): a deck with comments and a reply, as pptx and ODP. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { java.io.File(it).also { d -> d.mkdirs() } } ?: return
        java.io.File(dir, "slide-comments.pptx").outputStream().use { PptxWriter.write(deck(), it) }
        java.io.File(dir, "slide-comments.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(deck(), it) }
    }
}
