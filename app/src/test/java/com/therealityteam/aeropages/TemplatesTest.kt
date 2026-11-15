package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.FormulaEngine
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.CondType
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.ValidationType
import com.therealityteam.aeropages.ui.DeskTemplate
import com.therealityteam.aeropages.ui.Templates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

/**
 * The home screen's templates, built in each of the app's languages from its string files: every
 * text found, the sums and totals right, and each one kept through its own format and ODF.
 */
class TemplatesTest {

    // Every language the app ships: the default and each values-xx with a strings.xml.
    private val languages = listOf("values") + (File("src/main/res").listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!
        .map { it.name }.sorted())
    private val today = LocalDate.of(2026, 10, 14)

    /** A language's strings by resource id, read from its strings.xml as Android reads them. */
    private fun lookup(dir: String): (Int) -> String {
        val xml = File("src/main/res/$dir/strings.xml").readText()
        val values = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).associate { m ->
            var v = m.groupValues[2]
            if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length - 1)
            m.groupValues[1] to v.replace("\\n", "\n").replace("\\'", "'").replace("\\\"", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        }
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        return { id -> values[names[id]] ?: error("no string ${names[id]} in $dir") }
    }

    private fun texts(d: Document): String = buildString {
        for (b in d.blocks) when (b) {
            is ParagraphBlock -> appendLine(b.paragraph.plainText())
            is TableBlock -> b.table.rows.forEach { r -> r.cells.forEach { c -> c.content.forEach { appendLine(it.plainText()) } } }
            else -> {}
        }
    }

    @Test
    fun documentsInEveryLanguage() {
        for (lang in languages) {
            val t = Templates(lookup(lang), today)
            val s = lookup(lang)
            for (tpl in DeskTemplate.of(com.therealityteam.aeropages.format.DeskKind.PAGES).filter { !it.blank }) {
                val d = t.document(tpl)
                val all = texts(d)
                assertTrue("$lang $tpl has content", d.blocks.size >= 7)
                assertFalse("$lang $tpl: no string escapes left", all.contains("\\n") || all.contains("\\'"))
                when (tpl) {
                    DeskTemplate.REPORT -> {
                        assertTrue(all.contains(s(R.string.tpl_report_title)))
                        assertEquals(4, d.blocks.filterIsInstance<TableBlock>().single().table.columnCount)
                    }
                    DeskTemplate.LETTER -> assertTrue("$lang: the letter is dated", all.contains("2026") && all.contains(s(R.string.tpl_your_name)))
                    DeskTemplate.MINUTES -> assertEquals(2, d.blocks.count { it is TableBlock })
                    else -> assertTrue(all.contains(s(R.string.tpl_resume_h_experience)))
                }
                // Kept through Word and ODF.
                val docx = ByteArrayOutputStream().also { DocxWriter.write(d, it) }.toByteArray()
                assertEquals("$lang $tpl docx", all, texts(DocxReader.read(ByteArrayInputStream(docx))))
                val odt = ByteArrayOutputStream().also { OdtWriter.write(d, it) }.toByteArray()
                assertEquals("$lang $tpl odt", all, texts(OdtReader.read(odt)))
            }
        }
    }

    private fun value(sh: Sheet, a1: String): String {
        val (r, c) = com.therealityteam.aeropages.model.A1.parse(a1)!!
        return FormulaEngine.display(sh, r, c, listOf(sh))
    }

    @Test
    fun workbooksWorkOutTheirSums() {
        for (lang in languages) {
            val t = Templates(lookup(lang), today)
            val budget = t.workbook(DeskTemplate.BUDGET).sheets.single()
            assertEquals("8650", value(budget, "C8"))
            assertEquals("7650", value(budget, "C19"))
            assertEquals("1000", value(budget, "C21"))
            assertEquals("-220", value(budget, "D13"))   // Food over its budget …
            val invoice = t.workbook(DeskTemplate.INVOICE).sheets.single()
            assertEquals("5675", value(invoice, "D18"))
            assertEquals("6015.5", value(invoice, "D21"))
            assertEquals("", value(invoice, "D15"))      // … an empty item row shows nothing.
            val tasks = t.workbook(DeskTemplate.TASKS).sheets.single()
            assertEquals(listOf(ValidationType.LIST, ValidationType.LIST, ValidationType.DECIMAL), tasks.validations.map { it.type })
            assertEquals(1, tasks.tables.size)
            for (wb in listOf(budget, invoice, tasks)) for ((key, cell) in wb.cells) if (cell.text.startsWith("=")) {
                val v = FormulaEngine.display(wb, (key / 1000).toInt(), (key % 1000).toInt(), listOf(wb))
                assertFalse("$lang ${wb.name} ${cell.text} = $v", v.startsWith("#"))
            }
        }
    }

    @Test
    fun workbooksKeepTheirRulesInFiles() {
        val t = Templates(lookup("values-zh"), today)
        for (tpl in listOf(DeskTemplate.BUDGET, DeskTemplate.TASKS, DeskTemplate.INVOICE)) {
            val wb = t.workbook(tpl)
            val sh = wb.sheets.single()
            val xlsx = ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()
            val ods = ByteArrayOutputStream().also { OdsWriter.write(wb, it) }.toByteArray()
            for ((fmt, back) in listOf("xlsx" to XlsxReader.read(xlsx).sheets.single(), "ods" to OdsReader.read(ods).sheets.single())) {
                assertEquals("$tpl $fmt formulas", sh.cells.values.count { it.text.startsWith("=") }, back.cells.values.count { it.text.startsWith("=") })
                assertEquals("$tpl $fmt rules", sh.condFormats.map { it.type }, back.condFormats.map { it.type })
                assertEquals("$tpl $fmt lists", sh.validations.size, back.validations.size)
                assertEquals("$tpl $fmt merges", sh.merges.size, back.merges.size)
            }
        }
        // The task list's overdue rule: a due date gone by on a task not done.
        val tasks = t.workbook(DeskTemplate.TASKS).sheets.single()
        assertTrue(tasks.condFormats.any { it.type == CondType.FORMULA && it.formula1!!.contains("TODAY()") })
    }

    /** With `-Daero.sampleDir`, every template in Chinese and English as files, for LibreOffice to look at. */
    @Test
    fun writeSamples() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it, "templates").also { d -> d.mkdirs() } } ?: return
        for (lang in listOf("values", "values-zh")) {
            val t = Templates(lookup(lang), today)
            val tag = if (lang == "values") "en" else "zh"
            for (tpl in DeskTemplate.entries.filter { !it.blank }) {
                val name = "${tpl.name.lowercase()}-$tag"
                when (tpl.kind) {
                    com.therealityteam.aeropages.format.DeskKind.PAGES -> t.document(tpl).let { d ->
                        File(dir, "$name.docx").outputStream().use { DocxWriter.write(d, it) }; File(dir, "$name.odt").outputStream().use { OdtWriter.write(d, it) } }
                    com.therealityteam.aeropages.format.DeskKind.SHEETS -> t.workbook(tpl).let { wb ->
                        File(dir, "$name.xlsx").outputStream().use { XlsxWriter.write(wb, it) }; File(dir, "$name.ods").outputStream().use { OdsWriter.write(wb, it) } }
                    com.therealityteam.aeropages.format.DeskKind.SLIDES -> t.presentation(tpl).let { p ->
                        File(dir, "$name.pptx").outputStream().use { PptxWriter.write(p, it) }; File(dir, "$name.odp").outputStream().use { OdpWriter.write(p, it) } }
                }
            }
        }
    }

    @Test
    fun decksInEveryLanguage() {
        for (lang in languages) {
            val t = Templates(lookup(lang), today)
            for (tpl in listOf(DeskTemplate.DECK_BUSINESS, DeskTemplate.DECK_FRESH, DeskTemplate.DECK_DARK)) {
                val p = t.presentation(tpl)
                assertEquals(6, p.slides.size)
                assertEquals(2, p.designs.size)
                assertTrue(p.slides.all { p.designOf(it) != null })
                assertEquals(3, p.slides[4].shapes.size)
                val titles = p.slides.map { it.title.text }
                val pptx = PptxReader.read(ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray())
                assertEquals("$lang $tpl pptx", titles, pptx.slides.map { it.title.text })
                assertEquals(p.slides[1].body.text, pptx.slides[1].body.text)
                assertTrue("$lang $tpl: the designs' drawing kept", pptx.slides.all { pptx.designOf(it)?.items?.isNotEmpty() == true })
                val odp = OdpReader.read(ByteArrayOutputStream().also { OdpWriter.write(p, it) }.toByteArray())
                assertEquals("$lang $tpl odp", titles, odp.slides.map { it.title.text })
            }
        }
    }
}
