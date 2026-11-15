package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Bookmarks
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.IndexBlock
import com.therealityteam.aeropages.model.IndexEntries
import com.therealityteam.aeropages.model.IndexEntries.Entry
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/** Word's index: entries marked in the text (XE), the live index that lists them, kept through docx and ODT. */
class IndexEntriesTest {

    private fun doc(vararg texts: String) = Document(mutableListOf()).also { d -> texts.forEach { d.addParagraph(Paragraph.of(it)) } }

    private fun mark(e: DocumentEditor, para: Int, word: String, main: String = word, sub: String? = null) {
        val at = e.document.locations()[para].paragraph.plainText().indexOf(word)
        e.setSelection(para, at, at + word.length)
        assertTrue(e.markIndexEntry(main, sub))
    }

    /** A short report on planets, its words marked as Word's index entries would be. */
    private fun planets(): DocumentEditor {
        val d = doc("Mars is the fourth planet.", "Jupiter is the largest planet; Mars is smaller.", "Égypte watched Venus.", "In 1610 Galileo saw Jupiter's moons.")
        d.blocks.add(0, com.therealityteam.aeropages.model.ParagraphBlock(Paragraph.of("Planets").also { it.format.style = ParagraphStyle.HEADING1 }))
        val e = DocumentEditor().apply { load(d) }
        mark(e, 1, "Mars"); mark(e, 2, "Jupiter"); mark(e, 2, "Mars")
        mark(e, 2, "largest", "Jupiter", "size"); mark(e, 3, "Égypte"); mark(e, 3, "Venus")
        mark(e, 4, "1610", "1610"); mark(e, 4, "moons", "Jupiter", "moons")
        return e
    }

    @Test
    fun theIndexSortsItsEntriesAndGathersTheirParagraphs() {
        val e = planets()
        val lines = IndexEntries.lines(e.document, headings = false, locale = Locale.ENGLISH)
        assertEquals(listOf("1610", "Égypte", "Jupiter", "moons", "size", "Mars", "Venus"), lines.map { it.text })
        assertEquals(listOf(0, 0, 0, 1, 1, 0, 0), lines.map { it.level })
        // Each entry's paragraphs once, in reading order: the pages come from them.
        val paras = e.document.locations().map { it.paragraph }
        val mars = lines.first { it.text == "Mars" }
        assertEquals(listOf(paras[1], paras[2]), mars.refs)
        assertEquals("1, 2", mars.pages { p -> (paras.indexOf(p)).toString() })
        // Letter headings: accents off, digits under "#".
        val headed = IndexEntries.lines(e.document, headings = true, locale = Locale.ENGLISH)
        assertEquals(listOf("#", "E", "J", "M", "V"), headed.filter { it.heading }.map { it.text })
        assertEquals("E", IndexEntries.headingOf("élan"))
        assertNull(IndexEntries.headingOf("木星"))
        // Not bookmarks: the bookmark list leaves them out.
        assertTrue(Bookmarks.of(e.document).isEmpty())
    }

    @Test
    fun wordsFieldCodesAreReadAndWritten() {
        val e = Entry("Time: hours", "the \"best\"")
        assertEquals(e, IndexEntries.fromFieldCode(IndexEntries.fieldCode(e)))
        assertEquals(Entry("Selection", "Extending"), IndexEntries.fromFieldCode(""" XE "Selection:Extending" \b """))
        assertEquals(Entry("Mars"), IndexEntries.fromFieldCode("XE Mars"))
        assertEquals(Entry("A", "B: C"), IndexEntries.fromFieldCode("""XE "A:B:C""""))
        assertNull(IndexEntries.fromFieldCode("XEX \"a\""))
        assertNull(IndexEntries.fromFieldCode("PAGEREF _Ref1"))
        assertTrue(IndexEntries.isIndexField(""" INDEX \c "2" \z "1033" """))
        assertFalse(IndexEntries.isIndexField("INDEXES"))
        val b = IndexEntries.blockOf(IndexEntries.indexCode(IndexBlock(rightAlign = true, headings = true)))
        assertTrue(b.rightAlign && b.headings)
        val plain = IndexEntries.blockOf(""" INDEX \c "2" \z "1033" """)
        assertFalse(plain.rightAlign || plain.headings)
    }

    @Test
    fun markingIsOneUndoStepAndTheMarkGoesWithItsText() {
        val e = DocumentEditor().apply { load(doc("Saturn has rings. Saturn is far.", "No word here.", "Saturn again.")) }
        e.setSelection(0, 0, 6)
        assertTrue(e.markIndexEntry("Saturn"))
        assertFalse(e.markIndexEntry("   "))
        val ref = e.indexEntries().single()
        assertEquals(6, ref.offset)
        e.undo()
        assertTrue(e.indexEntries().isEmpty())
        e.redo()
        // Mark All: after the first "Saturn" of each paragraph that has none marked yet, in one step.
        assertEquals(1, e.markAllIndexEntries("Saturn", "Saturn"))
        assertEquals(listOf(0, 2), e.indexEntries().map { it.para })
        assertEquals(0, e.markAllIndexEntries("Saturn", "Saturn"))
        e.undo()
        assertEquals(1, e.indexEntries().size)
        // Typed before it, it moves along; the text round it deleted, it goes (Word deletes the XE field with it).
        e.setSelection(0, 0, 0); e.typeOver("The ")
        assertEquals(10, e.indexEntries().single().offset)
        e.setSelection(0, 4, 12); e.typeOver("")
        assertTrue(e.indexEntries().isEmpty())
        e.undo()
        assertEquals(1, e.indexEntries().size)
        e.removeIndexEntry(e.indexEntries().single())
        assertTrue(e.indexEntries().isEmpty())
    }

    @Test
    fun insertIndexAddsOneAndLaysTheOneThereOutAnew() {
        val e = planets()
        e.setSelection(e.document.locations().lastIndex, 0, 0)
        e.insertIndex(rightAlign = false, headings = false)
        val at = e.document.blocks.indexOfFirst { it is IndexBlock }
        assertEquals(e.document.blocks.size - 2, at)
        e.setSelection(0, 0, 0)
        e.insertIndex(rightAlign = true, headings = true)
        assertEquals(1, e.document.blocks.count { it is IndexBlock })
        assertEquals(at, e.document.blocks.indexOfFirst { it is IndexBlock })
        assertTrue(e.indexBlock!!.rightAlign && e.indexBlock!!.headings)
        e.undo()
        assertFalse(e.indexBlock!!.rightAlign)
        e.removeIndex()
        assertNull(e.indexBlock)
        // The entries stay marked.
        assertEquals(8, e.indexEntries().size)
    }

    @Test
    fun wordKeepsTheEntriesAndTheIndex() {
        val e = planets()
        e.setSelection(e.document.locations().lastIndex, 0, 0)
        e.insertIndex(rightAlign = false, headings = true)
        e.document.pageCache = e.document.paragraphs().associate { it.id to "2" }
        val bytes = ByteArrayOutputStream().also { DocxWriter.write(e.document, it) }.toByteArray()
        val parts = OfficeXml.unzip(bytes)
        val xml = String(parts["word/document.xml"]!!)
        assertTrue(xml, xml.contains("""<w:instrText xml:space="preserve"> XE &quot;Jupiter:size&quot; </w:instrText>"""))
        assertTrue(xml.contains("""<w:instrText xml:space="preserve"> INDEX \h &quot;A&quot; \c &quot;1&quot; </w:instrText>"""))
        assertTrue(xml.contains("""<w:pStyle w:val="Index2"/>"""))
        assertTrue(xml.contains("""Mars</w:t></w:r><w:r><w:t xml:space="preserve">, 2</w:t></w:r>"""))
        assertTrue(String(parts["word/styles.xml"]!!).contains("""w:styleId="IndexHeading""""))
        val back = DocxReader.read(ByteArrayInputStream(bytes))
        assertEquals(1, back.blocks.count { it is IndexBlock })
        assertTrue((back.blocks.first { it is IndexBlock } as IndexBlock).headings)
        assertEquals(e.indexEntries().map { it.entry to it.offset }, IndexEntries.refs(back).map { it.entry to it.offset })
        // The cached lines are not read in as text.
        assertEquals(e.document.paragraphs().map { it.plainText() }, back.paragraphs().map { it.plainText() })
    }

    @Test
    fun wordsOwnIndexFieldOverParagraphsBecomesOneIndex() {
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val body = """<w:p><w:r><w:t xml:space="preserve">Comets</w:t></w:r><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText> XE "Comet" </w:instrText></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r><w:r><w:t xml:space="preserve"> fly by.</w:t></w:r></w:p>""" +
            """<w:p><w:pPr><w:pStyle w:val="Index1"/></w:pPr><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> INDEX \e "	" \c "2" \z "1033" </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>Comet</w:t></w:r><w:r><w:tab/><w:t>1</w:t></w:r></w:p>""" +
            """<w:p><w:pPr><w:pStyle w:val="Index1"/><w:sectPr><w:cols w:num="2"/></w:sectPr></w:pPr><w:r><w:t>Star</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>""" +
            """<w:p><w:r><w:t>After.</w:t></w:r></w:p>"""
        val docx = zip(mapOf(
            "[Content_Types].xml" to """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""".toByteArray(),
            "_rels/.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""".toByteArray(),
            "word/document.xml" to """<?xml version="1.0"?><w:document xmlns:w="$w"><w:body>$body<w:sectPr/></w:body></w:document>""".toByteArray(),
        ))
        val back = DocxReader.read(ByteArrayInputStream(docx))
        val index = back.blocks.filterIsInstance<IndexBlock>().single()
        assertTrue(index.rightAlign)
        assertEquals(listOf(Entry("Comet")), IndexEntries.refs(back).map { it.entry })
        assertEquals(6, IndexEntries.refs(back).single().offset)
        // Its result is gone; its section (Word's two columns) still ends where it did.
        val texts = back.paragraphs().map { it.plainText() }
        assertEquals(listOf("Comets fly by.", "", "After."), texts)
        assertEquals(2, back.paragraphs()[1].format.sectionBreak?.columns)
    }

    @Test
    fun odfKeepsThemAsAnAlphabeticalIndex() {
        val e = planets()
        e.setSelection(e.document.locations().lastIndex, 0, 0)
        e.insertIndex(rightAlign = true, headings = false)
        val bytes = ByteArrayOutputStream().also { OdtWriter.write(e.document, it) }.toByteArray()
        val content = String(OfficeXml.unzip(bytes)["content.xml"]!!)
        assertTrue(content, content.contains("""<text:alphabetical-index-mark text:string-value="size" text:key1="Jupiter"/>"""))
        assertTrue(content.contains("""<text:alphabetical-index-source text:ignore-case="true" text:alphabetical-separators="false""""))
        assertTrue(String(OfficeXml.unzip(bytes)["styles.xml"]!!).contains("""style:name="Index_20_2""""))
        val back = OdtReader.read(bytes)
        val index = back.blocks.filterIsInstance<IndexBlock>().single()
        assertTrue(index.rightAlign)
        assertEquals(e.indexEntries().map { it.entry to it.offset }, IndexEntries.refs(back).map { it.entry to it.offset })
        assertEquals(e.document.paragraphs().map { it.plainText() }, back.paragraphs().map { it.plainText() })
    }

    @Test
    fun libreOfficesRangeMarksAreEntriesToo() {
        val content = """<?xml version="1.0"?><office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.3"><office:body><office:text>""" +
            """<text:p>The <text:alphabetical-index-mark-start text:id="I1" text:key1="Planets"/>red planet<text:alphabetical-index-mark-end text:id="I1"/> and <text:alphabetical-index-mark text:string-value="Moon"/>moons.</text:p>""" +
            """<text:alphabetical-index text:name="Alphabetical Index1"><text:alphabetical-index-source text:alphabetical-separators="true"/><text:index-body><text:p>Moon, 1</text:p></text:index-body></text:alphabetical-index>""" +
            "</office:text></office:body></office:document-content>"
        val odt = zip(mapOf("mimetype" to "application/vnd.oasis.opendocument.text".toByteArray(), "content.xml" to content.toByteArray()))
        val back = OdtReader.read(odt)
        assertEquals(listOf(Entry("Planets", "red planet") to 14, Entry("Moon") to 19), IndexEntries.refs(back).map { it.entry to it.offset })
        assertTrue(back.blocks.filterIsInstance<IndexBlock>().single().headings)
        assertEquals(listOf("The red planet and moons."), back.paragraphs().map { it.plainText() })
    }

    /**
     * With `-Daero.indexLo=<dir>`: LibreOffice's conversions of the sample (`odt/index.odt` from our docx, `docx/index.docx`
     * from our ODT) read back as one index with the entries — its docx's cached index not read in as text.
     */
    @Test
    fun libreOfficesIndexesComeBack() {
        val dir = System.getProperty("aero.indexLo")?.let(::File) ?: return
        val fromDocx = OdtReader.read(File(dir, "odt/index.odt").readBytes())
        assertTrue(fromDocx.blocks.filterIsInstance<IndexBlock>().single().headings)
        assertEquals(8, IndexEntries.refs(fromDocx).size)
        val fromOdt = DocxReader.read(File(dir, "docx/index.docx").inputStream())
        val index = fromOdt.blocks.filterIsInstance<IndexBlock>().single()
        assertTrue(index.headings && !index.rightAlign)
        assertTrue(IndexEntries.refs(fromOdt).map { it.entry }.containsAll(listOf(Entry("Jupiter", "size"), Entry("Jupiter", "moons"), Entry("1610"))))
        assertFalse(fromOdt.paragraphs().any { it.plainText() == "Venus" || it.plainText().startsWith("Venus,") })
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z -> entries.forEach { (name, body) -> z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(body); z.closeEntry() } }
        return out.toByteArray()
    }

    /** With `-Daero.sampleDir`: a report with marked entries and its index, for LibreOffice. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        val e = planets()
        e.document.addParagraph(Paragraph.of("Index").also { it.format.style = ParagraphStyle.HEADING1 })
        e.setSelection(e.document.locations().lastIndex, 0, 0)
        e.document.addParagraph(Paragraph.of(""))
        e.setSelection(e.document.locations().lastIndex, 0, 0)
        e.insertIndex(rightAlign = false, headings = true)
        File(dir, "index.docx").outputStream().use { DocxWriter.write(e.document, it) }
        File(dir, "index.odt").outputStream().use { OdtWriter.write(e.document, it) }
    }
}
