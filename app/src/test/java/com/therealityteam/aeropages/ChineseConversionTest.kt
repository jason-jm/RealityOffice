package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.ChineseConversion
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.TextRun
import com.therealityteam.aeropages.model.Workbook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Review ▸ Chinese Conversion: the characters (the device's engine — a few here), the words, and what each app converts. */
class ChineseConversionTest {

    /** A stand-in for ICU's transliterators: a handful of characters each way. */
    private object Fake : ChineseConversion.Engine {
        private val toT = mapOf('简' to '簡', '体' to '體', '国' to '國', '书' to '書', '发' to '發', '这' to '這', '说' to '說')
        private val toS = toT.entries.associate { (k, v) -> v to k }
        override fun toTraditional(text: String) = text.map { toT[it] ?: it }.joinToString("")
        override fun toSimplified(text: String) = text.map { toS[it] ?: it }.joinToString("")
    }

    @Before fun engine() { ChineseConversion.engine = Fake }
    @After fun noEngine() { ChineseConversion.engine = null }

    @Test
    fun charactersAndWords() {
        assertEquals("簡體中文書", ChineseConversion.convert("简体中文书", toTraditional = true))
        assertEquals("简体中文书", ChineseConversion.convert("簡體中文書", toTraditional = false))
        // The words first, longest first: 打印机 is 印表機, not 列印机.
        assertEquals("這台印表機可以列印", ChineseConversion.convert("这台打印机可以打印", toTraditional = true))
        assertEquals("这台打印机可以打印", ChineseConversion.convert("這台印表機可以列印", toTraditional = false))
        assertEquals("軟體和網路", ChineseConversion.convert("软件和网络", toTraditional = true))
        // Without the words, only characters.
        assertEquals("这台打印机", ChineseConversion.convert("这台打印机", toTraditional = true, vocabulary = false).let { Fake.toSimplified(it) })
        // A one-way word: 资料 is a mainland word too, so 資料 stays 資料 → 资料 by its characters only.
        assertEquals("資料", ChineseConversion.convert("数据", toTraditional = true))
        assertEquals("資料", ChineseConversion.convert("資料", toTraditional = false))
        // Text with no Chinese is left alone; no engine converts nothing.
        assertEquals("Hello", ChineseConversion.convert("Hello", toTraditional = true))
        ChineseConversion.engine = null
        assertEquals("简体", ChineseConversion.convert("简体", toTraditional = true))
    }

    @Test
    fun pagesConvertsTheSelectionOrTheDocumentKeepingEachRunsLook() {
        val doc = Document(mutableListOf())
        doc.addParagraph(Paragraph(mutableListOf(TextRun("内存", CharacterFormat()), TextRun("这本书", CharacterFormat(bold = true)))))
        doc.addParagraph(Paragraph.of("简体说明"))
        doc.header = Paragraph.of("国")
        val e = DocumentEditor().apply { load(doc) }
        // The selection only: "这本" in the bold run.
        e.setSelection(0, 2, 4)
        assertTrue(e.convertChinese(toTraditional = true) > 0)
        assertEquals("内存這本书", e.document.locations()[0].paragraph.plainText())
        // Nothing selected: the whole document and its header; 内存 becomes the longer 記憶體 and the bold stays on 這本書.
        e.setSelection(1, 0, 0)
        e.convertChinese(toTraditional = true)
        val runs = e.document.locations()[0].paragraph.runs
        assertEquals("記憶體這本書", runs.joinToString("") { it.text })
        assertEquals("記憶體", runs.filterNot { it.format.bold }.joinToString("") { it.text })
        assertEquals("這本書", runs.filter { it.format.bold }.joinToString("") { it.text })
        assertEquals("簡體說明", e.document.locations()[1].paragraph.plainText())
        assertEquals("國", e.document.header!!.plainText())
        // Nothing left to convert: no change, no undo step.
        assertEquals(0, e.convertChinese(toTraditional = true))
        // One step back to before the whole-document conversion.
        e.undo()
        assertEquals("内存這本书", e.document.locations()[0].paragraph.plainText())
        // A device that cannot convert says so.
        ChineseConversion.engine = null
        assertEquals(-1, e.convertChinese(toTraditional = true))
    }

    @Test
    fun sheetsConvertsTextCellsNotFormulas() {
        val e = SheetEditor().apply { load(Workbook.blank()) }
        e.setCell(0, 0, "简体"); e.setCell(0, 1, "=\"简体\""); e.setCell(5, 5, "国书")
        // One cell selected: the whole sheet, as Excel does — the formula left as it is.
        e.select(0, 0)
        assertEquals(2, e.convertChinese(toTraditional = true))
        assertEquals("簡體", e.sheet.cell(0, 0)?.text)
        assertEquals("=\"简体\"", e.sheet.cell(0, 1)?.text)
        assertEquals("國書", e.sheet.cell(5, 5)?.text)
        e.undo()
        assertEquals("简体", e.sheet.cell(0, 0)?.text)
        // A range: only its cells.
        e.select(5, 5); e.extendSelection(1, 1)
        assertEquals(1, e.convertChinese(toTraditional = true))
        assertEquals("简体", e.sheet.cell(0, 0)?.text)
    }

    @Test
    fun slidesConvertsEveryBoxAndTheNotes() {
        val e = SlideEditor().apply { load(Presentation.blank()) }
        e.replaceText(e.slide.title, e.slide.title.text, "简体")
        e.slide.notes = "这本书"
        assertTrue(e.convertChinese(toTraditional = true) > 0)
        assertEquals("簡體", e.slide.title.text)
        assertEquals("這本書", e.slide.notes)
        assertEquals(0, e.convertChinese(toTraditional = true))
        e.undo()
        assertEquals("简体", e.presentation.slides[0].title.text)
        assertFalse(e.presentation.slides[0].notes == "這本書")
    }
}
