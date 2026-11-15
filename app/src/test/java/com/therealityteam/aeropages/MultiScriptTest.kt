package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.WordCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Text in the app's writing systems — Hangul, Thai, Devanagari, Vietnamese's stacked accents, Cyrillic, German's ß — keeps
 * every character through docx and ODT, the theme names a font for each script, and Word's count counts it.
 */
class MultiScriptTest {
    private val lines = listOf(
        "한국어: 분기별 보고서를 검토했습니다.",
        "ไทย: เราได้ตรวจสอบรายงานประจำไตรมาสแล้ว",
        "हिन्दी: हमने तिमाही रिपोर्ट की समीक्षा की।",
        "Tiếng Việt: Chúng tôi đã xem xét báo cáo hằng quý.",
        "Русский: Мы проверили квартальный отчёт.",
        "Deutsch: Größenänderung der Übersicht",
        "简体中文：我们审阅了季度报告。日本語：四半期報告書を確認しました。",
    )

    private fun document(): Document = Document(mutableListOf<com.therealityteam.aeropages.model.Block>(
        ParagraphBlock(Paragraph.of("Aero Desk — 18 languages").also { it.format.style = ParagraphStyle.HEADING1 }),
    ).also { b -> lines.forEach { b.add(ParagraphBlock(Paragraph.of(it))) } })

    private fun texts(d: Document) = d.paragraphs().map { it.plainText() }

    @Test
    fun everyScriptSurvivesDocxAndOdt() {
        val docx = ByteArrayOutputStream().also { DocxWriter.write(document(), it) }.toByteArray()
        assertEquals(texts(document()), texts(DocxReader.read(ByteArrayInputStream(docx))))
        val odt = ByteArrayOutputStream().also { OdtWriter.write(document(), it) }.toByteArray()
        assertEquals(texts(document()), texts(OdtReader.read(odt)))
        // The theme names Word's fonts for Korean, Thai and Devanagari besides Chinese and Japanese.
        val theme = ZipInputStream(ByteArrayInputStream(docx)).use { z ->
            generateSequence { z.nextEntry }.first { it.name == "word/theme/theme1.xml" }.let { z.readBytes().toString(Charsets.UTF_8) }
        }
        for (script in listOf("Hang", "Thai", "Deva", "Hans", "Jpan")) assertTrue("theme font for $script", theme.contains("script=\"$script\""))
    }

    @Test
    fun wordsAreCountedInEveryScript() {
        val c = WordCounts.of(lines.joinToString("\n"))
        assertTrue("East Asian characters each count: ${c.asian}", c.asian > 20)
        assertTrue("other words by spaces: ${c.other}", c.other > 25)
    }

    /** With `-Daero.sampleDir`: the document as docx and ODT, for the canvas, PDF export and LibreOffice. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        File(dir, "scripts.docx").outputStream().use { DocxWriter.write(document(), it) }
        File(dir, "scripts.odt").outputStream().use { OdtWriter.write(document(), it) }
        // Characters whose Chinese and Japanese forms differ, in a Chinese and a Japanese paragraph (HanLocale).
        val forms = Document(mutableListOf<com.therealityteam.aeropages.model.Block>(
            ParagraphBlock(Paragraph.of("中文：直 骨 写 角 海 次 令", com.therealityteam.aeropages.model.CharacterFormat(fontSizePt = 28f))),
            ParagraphBlock(Paragraph.of("日本語：直す 骨 写す 角 海 次 令", com.therealityteam.aeropages.model.CharacterFormat(fontSizePt = 28f))),
        ))
        File(dir, "han-forms.docx").outputStream().use { DocxWriter.write(forms, it) }
        val book = com.therealityteam.aeropages.model.Workbook.blank()
        val sheet = book.sheets[0]
        listOf("直 骨 写 角 次 令", "直す 骨 写す 角 次 令").forEachIndexed { r, t ->
            sheet.cells[sheet.key(r, 0)] = com.therealityteam.aeropages.model.Cell(t, com.therealityteam.aeropages.model.CharacterFormat(fontSizePt = 24f))
        }
        sheet.colWidths[0] = 4f; sheet.rowHeights[0] = 2f; sheet.rowHeights[1] = 2f
        File(dir, "han-forms.xlsx").outputStream().use { com.therealityteam.aeropages.format.xlsx.XlsxWriter.write(book, it) }
    }
}
