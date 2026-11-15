package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.ParagraphOps
import com.therealityteam.aeropages.format.doc.DocReader
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.BookmarkMark
import com.therealityteam.aeropages.model.Bookmarks
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.TextRun
import com.therealityteam.aeropages.model.TocBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Bookmarks — named places in the text — and links to them, through editing and every format. */
class BookmarksTest {
    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun para(text: String, vararg marks: BookmarkMark) = Paragraph.of(text).also { it.bookmarks.addAll(marks) }
    private fun start(name: String, at: Int) = BookmarkMark(name, at, false)
    private fun end(name: String, at: Int) = BookmarkMark(name, at, true)
    private fun doc(vararg ps: Paragraph) = Document(ps.mapTo(ArrayList()) { ParagraphBlock(it) as com.therealityteam.aeropages.model.Block })

    /** The text a found bookmark covers (within one paragraph). */
    private fun textOf(d: Document, b: Bookmarks.Found): String =
        d.locations()[b.startPara].paragraph.plainText().substring(b.start, b.end)

    @Test
    fun marksMoveWithTheTextAroundThem() {
        val p = para("Hello world", start("b", 6), end("b", 11))
        ParagraphOps.replace(p, 0, 0, "Oh ", CharacterFormat())
        assertEquals(listOf(start("b", 9), end("b", 14)), p.bookmarks)
        // Typed at its end the text goes after the bookmark; at its start, into it.
        ParagraphOps.replace(p, 14, 14, "!", CharacterFormat())
        ParagraphOps.replace(p, 9, 9, "big ", CharacterFormat())
        assertEquals("Oh Hello big world!", p.plainText())
        assertEquals(listOf(start("b", 9), end("b", 18)), p.bookmarks)
        // Text round a mark going away: the mark stands where the text was.
        ParagraphOps.replace(p, 7, 13, "", CharacterFormat())
        assertEquals(listOf(start("b", 7), end("b", 12)), p.bookmarks)

        // Split where a bookmark starts: it goes with the text after the cut; one ending there stays.
        val q = para("TitleBody", start("t", 0), end("t", 5), start("b", 5), end("b", 9))
        val tail = ParagraphOps.splitAt(q, 5)
        assertEquals(listOf(start("t", 0), end("t", 5)), q.bookmarks)
        assertEquals(listOf(start("b", 0), end("b", 4)), tail.bookmarks)
        // Joined again: the second paragraph's marks follow its text.
        ParagraphOps.append(q, tail)
        assertEquals(listOf(start("t", 0), end("t", 5), start("b", 5), end("b", 9)), q.bookmarks)

        // Track changes: struck text keeps the marks in it; accepting the deletion takes the text, not the marks.
        val t = para("one two three", start("m", 4), end("m", 7))
        ParagraphOps.trackedReplace(t, 4, 8, "", CharacterFormat(), "me", 0L)
        assertEquals(listOf(start("m", 4), end("m", 7)), t.bookmarks)
        ParagraphOps.removeDeleted(t)
        assertEquals("one three", t.plainText())
        assertEquals(listOf(start("m", 4), end("m", 4)), t.bookmarks)
    }

    @Test
    fun bookmarksAreAddedFoundAndUndone() {
        val editor = DocumentEditor()
        editor.load(doc(Paragraph.of("Introduction"), Paragraph.of("The method we use"), Paragraph.of("Results follow")))
        editor.setSelection(1, 4, 10)
        assertTrue(editor.addBookmark("Method"))
        assertFalse("a name starts with a letter", editor.addBookmark("1st"))
        assertFalse("hidden names are Word's", editor.addBookmark("_x"))
        assertFalse("no spaces", editor.addBookmark("a b"))
        assertTrue("letters of any script", Bookmarks.isValidName("方法_2"))
        val found = editor.bookmarks().single()
        assertEquals("Method", found.name)
        assertEquals("method", textOf(editor.document, found))

        // Across paragraphs; and a name used again moves the bookmark.
        editor.selectRange(DocumentEditor.DocPos(0, 5), DocumentEditor.DocPos(2, 7))
        assertTrue(editor.addBookmark("Span"))
        editor.setSelection(2, 0, 7)
        assertTrue(editor.addBookmark("Method"))
        val both = editor.bookmarks()
        assertEquals(listOf("Span", "Method"), both.map { it.name })
        assertEquals(Triple(0, 5, 2), both[0].let { Triple(it.startPara, it.start, it.endPara) })
        assertEquals("Results", textOf(editor.document, both[1]))

        editor.undo()
        assertEquals("method", textOf(editor.document, editor.bookmarks().first { it.name == "Method" }))
        editor.redo()
        assertEquals("Results", textOf(editor.document, editor.bookmarks().first { it.name == "Method" }))

        // Going to one selects its text.
        editor.setSelection(0, 0, 0)
        assertTrue(editor.goToBookmark("Method"))
        assertEquals(Triple(2, 0, 7), Triple(editor.active, editor.selStart, editor.selEnd))
        editor.deleteBookmark("Method")
        assertEquals(listOf("Span"), editor.bookmarks().map { it.name })
        assertFalse(editor.goToBookmark("Method"))
    }

    @Test
    fun linksWithinTheDocumentGoToTheirPlace() {
        val heading = Paragraph.of("Results").also { it.format.style = ParagraphStyle.HEADING1 }
        val editor = DocumentEditor()
        editor.load(doc(heading, Paragraph.of("We see results here.")))
        // A link to the heading: through a hidden bookmark round it.
        editor.setSelection(1, 7, 14)
        editor.linkToHeading(heading)
        assertEquals(listOf(start("_Results", 0), end("_Results", 7)), heading.bookmarks)
        editor.setSelection(1, 9, 9)
        val link = editor.linkAtCaret()!!
        assertEquals("#_Results", link.first)
        assertEquals(7 until 14, link.second)
        assertTrue(editor.followLink(link.first))
        assertEquals(Triple(0, 0, 7), Triple(editor.active, editor.selStart, editor.selEnd))
        // LibreOffice's link to a heading by its text, and a place that is gone.
        editor.setSelection(1, 0, 0)
        assertTrue(editor.followLink("#1.Results|outline"))
        assertEquals(0, editor.active)
        assertFalse(editor.followLink("#Nowhere"))
        assertFalse("an address is not a place here", editor.followLink("https://example.com"))

        // Typing at a link's end goes after the link.
        editor.setSelection(1, 14, 14)
        editor.replaceText(1, "We see results here.", "We see results! here.")
        assertNull(ParagraphOps.formatAt(editor.document.locations()[1].paragraph, 14).link)
        // The whole link changes, or goes, from the caret anywhere in it.
        editor.setSelection(1, 8, 8)
        editor.setLinkAtCaret("https://example.com")
        assertEquals("https://example.com" to (7 until 14), editor.linkAtCaret())
        editor.removeLink()
        assertNull(editor.linkAtCaret())
        assertEquals("We see results! here.", editor.document.locations()[1].paragraph.plainText())

        // A bookmark linked with nothing selected: its name at the caret, linked.
        editor.setSelection(1, 0, 0)
        assertTrue(editor.addBookmark("Start"))
        editor.setSelection(1, 21, 21)
        editor.applyLink("#Start", display = "Start")
        assertEquals("#Start", editor.linkAtCaret()?.first)
    }

    private fun docxOf(body: String, rels: String = ""): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(n: String, c: String) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""")
            put("word/document.xml", """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="$w" xmlns:r="$r"><w:body>$body</w:body></w:document>""")
            put("word/_rels/document.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$rels</Relationships>""")
        }
        return out.toByteArray()
    }

    private fun partOf(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == name) return z.readBytes().toString(Charsets.UTF_8)
            }
        }
        error("no $name")
    }

    private fun runsOf(text: String) = """<w:r><w:t xml:space="preserve">$text</w:t></w:r>"""

    /** Each run's text and link, for a paragraph. */
    private fun links(p: Paragraph) = p.runs.filter { it.format.link != null && it.text.isNotEmpty() }.map { it.text to it.format.link }

    @Test
    fun docxKeepsBookmarksAndLinksToThem() {
        val body = """<w:bookmarkStart w:id="9" w:name="Top"/>""" +
            """<w:p>${runsOf("Chapter ")}<w:bookmarkStart w:id="1" w:name="Name"/>${runsOf("one")}<w:bookmarkEnd w:id="1"/>${runsOf(" ends.")}</w:p>""" +
            """<w:p><w:bookmarkStart w:id="2" w:name="Across"/>${runsOf("first")}</w:p>""" +
            """<w:p>${runsOf("second")}<w:bookmarkEnd w:id="2"/><w:bookmarkStart w:id="3" w:name="Point"/><w:bookmarkEnd w:id="3"/>${runsOf("!")}</w:p>""" +
            """<w:p><w:hyperlink w:anchor="Name" w:history="1">${runsOf("to the name")}</w:hyperlink>${runsOf(" and ")}""" +
            """<w:hyperlink r:id="rId7" w:anchor="part">${runsOf("away")}</w:hyperlink></w:p>""" +
            """<w:p><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> HYPERLINK \l "Across" \o "tip" </w:instrText></w:r>""" +
            """<w:r><w:fldChar w:fldCharType="separate"/></w:r>${runsOf("field link")}<w:r><w:fldChar w:fldCharType="end"/></w:r>""" +
            """<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> HYPERLINK "https://example.org/" </w:instrText></w:r>""" +
            """<w:r><w:fldChar w:fldCharType="separate"/></w:r>${runsOf("web")}<w:r><w:fldChar w:fldCharType="end"/></w:r><w:bookmarkEnd w:id="9"/></w:p>"""
        val rels = """<Relationship Id="rId7" Type="$r/hyperlink" Target="https://example.com/page" TargetMode="External"/>"""
        val read = DocxReader.read(ByteArrayInputStream(docxOf(body, rels)))
        fun check(d: Document, label: String) {
            val ps = d.paragraphs()
            val found = Bookmarks.of(d).associateBy { it.name }
            assertEquals(label, setOf("Top", "Name", "Across", "Point"), found.keys)
            assertEquals(label, "one", textOf(d, found.getValue("Name")))
            assertEquals(label, Bookmarks.Found("Across", 1, 0, 2, 6), found.getValue("Across"))
            assertEquals(label, Bookmarks.Found("Point", 2, 6, 2, 6), found.getValue("Point"))
            assertEquals(label, Bookmarks.Found("Top", 0, 0, 4, 13), found.getValue("Top"))
            assertEquals(label, listOf("to the name" to "#Name", "away" to "https://example.com/page#part"), links(ps[3]))
            assertEquals(label, listOf("field link" to "#Across", "web" to "https://example.org/"), links(ps[4]))
        }
        check(read, "read")
        val out = ByteArrayOutputStream().also { DocxWriter.write(read, it) }.toByteArray()
        val xml = partOf(out, "word/document.xml")
        assertTrue(xml.contains("""<w:hyperlink w:anchor="Name" w:history="1">"""))
        // Every start is ended by its id.
        val starts = Regex("""<w:bookmarkStart w:id="(\d+)" w:name="([^"]+)"/>""").findAll(xml).map { it.groupValues[1] }.toList()
        val ends = Regex("""<w:bookmarkEnd w:id="(\d+)"/>""").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(starts.sorted(), ends.sorted())
        check(DocxReader.read(ByteArrayInputStream(out)), "written")
    }

    @Test
    fun odtKeepsBookmarksAndLinksToThem() {
        val d = doc(
            para("Chapter one ends.", start("Name", 8), end("Name", 11)),
            para("first", start("Across", 0)),
            para("second!", end("Across", 6), start("Point", 6), end("Point", 6)),
            Paragraph(mutableListOf(TextRun("to the name", CharacterFormat(link = "#Name")), TextRun(" and "), TextRun("away", CharacterFormat(link = "https://example.com")))),
        )
        val out = ByteArrayOutputStream().also { OdtWriter.write(d, it) }.toByteArray()
        val xml = partOf(out, "content.xml")
        assertTrue(xml.contains("""<text:bookmark text:name="Point"/>"""))
        assertTrue(xml.contains("""<text:bookmark-start text:name="Across"/>"""))
        assertTrue(xml.contains("""xlink:href="#Name""""))
        val back = OdtReader.read(out)
        assertEquals(Bookmarks.of(d).map { it.copy() }, Bookmarks.of(back))
        assertEquals(links(d.paragraphs()[3]), links(back.paragraphs()[3]))
    }

    @Test
    fun aTableOfContentsLinksToItsHeadingsInWord() {
        val h1 = Paragraph.of("Aims").also { it.format.style = ParagraphStyle.HEADING1 }
        val h2 = Paragraph.of("Means").also { it.format.style = ParagraphStyle.HEADING2 }
        val d = Document(mutableListOf(TocBlock(), ParagraphBlock(h1), ParagraphBlock(Paragraph.of("text")), ParagraphBlock(h2)))
        val out = ByteArrayOutputStream().also { DocxWriter.write(d, it) }.toByteArray()
        val xml = partOf(out, "word/document.xml")
        assertTrue(xml.contains("""<w:hyperlink w:anchor="_Toc100000001" w:history="1">"""))
        assertTrue(xml.contains("""<w:hyperlink w:anchor="_Toc100000002" w:history="1">"""))
        val back = DocxReader.read(ByteArrayInputStream(out))
        val found = Bookmarks.of(back)
        assertEquals(listOf("_Toc100000001", "_Toc100000002"), found.map { it.name })
        assertEquals(listOf("Aims", "Means"), found.map { textOf(back, it) })
        // Written again: the headings keep their bookmarks, and no others come.
        val again = ByteArrayOutputStream().also { DocxWriter.write(back, it) }.toByteArray()
        assertEquals(found, Bookmarks.of(DocxReader.read(ByteArrayInputStream(again))))
        // OpenDocument the same way: each entry links to its heading's bookmark.
        val odt = ByteArrayOutputStream().also { OdtWriter.write(d, it) }.toByteArray()
        val content = partOf(odt, "content.xml")
        assertTrue(content.contains("""<text:a xlink:type="simple" xlink:href="#_Toc100000002">Means<text:tab/></text:a>"""))
        val fromOdt = OdtReader.read(odt)
        assertEquals(listOf("Aims", "Means"), Bookmarks.of(fromOdt).map { textOf(fromOdt, it) })
    }

    /**
     * A Word 97 file (`resources/doc/bookmarks.doc`: LibreOffice's from the links sample — SttbfBkmk,
     * PlcfBkf, PlcfBkl, `HYPERLINK \l` fields): its bookmarks on the text they mark, its links to them.
     */
    @Test
    fun wordNinetySevenFilesKeepTheirBookmarksAndLinks() {
        val d = DocReader.read(javaClass.getResourceAsStream("/doc/bookmarks.doc")!!)
        val found = Bookmarks.of(d).associateBy { it.name }
        val names = found.keys.filterNot { it.startsWith("_Toc") }.toSet()
        assertEquals(found.keys.toString(), setOf("Method", "_Results", "Here"), names)
        assertEquals("measured the light at three heights", textOf(d, found.getValue("Method")))
        assertEquals("3. Results", textOf(d, found.getValue("_Results")))
        val here = found.getValue("Here")
        assertEquals(here.start, here.end)
        assertEquals("The ", d.locations()[here.startPara].paragraph.plainText().substring(0, here.start))
        val linked = d.paragraphs().flatMap { links(it) }
        assertTrue(linked.toString(), ("the method section" to "#Method") in linked)
        assertTrue(linked.toString(), ("Results" to "#_Results") in linked)
        assertTrue(linked.toString(), ("example.com" to "https://example.com/") in linked)
    }

    @Test
    fun hyperlinkFieldCodes() {
        assertEquals("https://a.com/x", DocReader.hyperlinkOf(""" HYPERLINK "https://a.com/x" """))
        assertEquals("#_Toc123", DocReader.hyperlinkOf(""" HYPERLINK \l "_Toc123" \h """))
        assertEquals("https://a.com#part", DocReader.hyperlinkOf("""HYPERLINK "https://a.com" \l "part" \o "a tip" """))
        assertEquals("""C:\docs\a.doc""", DocReader.hyperlinkOf("""HYPERLINK "C:\\docs\\a.doc""""))
        assertEquals("https://b.com", DocReader.hyperlinkOf("""hyperlink https://b.com"""))
        assertEquals("#Method", DocReader.hyperlinkOf(" HYPERLINK  \\l \"Method\"\u0001"))
        assertNull(DocReader.hyperlinkOf(" PAGE "))
        assertNull(DocReader.hyperlinkOf(" HYPERLINK "))
    }

    @Test
    fun headingNamesAreWordsJoinedAndUnique() {
        assertEquals("_Results_and_discussion", Bookmarks.headingName("Results and discussion", emptySet()))
        assertEquals("_结果", Bookmarks.headingName("结果", emptySet()))
        assertEquals("_Heading", Bookmarks.headingName("  ", emptySet()))
        assertEquals("_Aims_1", Bookmarks.headingName("Aims", setOf("_Aims")))
    }
}
