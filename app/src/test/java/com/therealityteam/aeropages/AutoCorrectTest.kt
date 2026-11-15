package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.AutoCorrect
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Word's AutoCorrect as you type: the rules, and the editor making them as their own undo step. */
class AutoCorrectTest {

    @After fun reset() { AutoCorrect.options = AutoCorrect.Options() }

    /** [typed] one character at a time onto [start], in [lang], each correction made as the editor would: the text. */
    private fun typeText(start: String, typed: String, lang: String = "en", opts: AutoCorrect.Options = AutoCorrect.Options()): String {
        var text = start
        for (ch in typed) {
            text += ch
            for (fx in AutoCorrect.after(text, text.length, lang, opts)) {
                text = when (fx) {
                    is AutoCorrect.Replace -> text.substring(0, fx.start) + fx.text + text.substring(fx.end)
                    is AutoCorrect.MakeList -> text.substring(fx.markerEnd)
                    else -> text
                }
            }
        }
        return text
    }

    @Test
    fun sentencesStartWithACapital() {
        assertEquals("Hello world. This is it! Is it? Yes", typeText("", "hello world. this is it! is it? yes "). trimEnd())
        assertEquals("not after an abbreviation", "We met e.g. friends, etc. and so on", typeText("", "we met e.g. friends, etc. and so on ").trimEnd())
        assertEquals("not after an initial", "Mr. J. smith", typeText("", "Mr. J. smith ").trimEnd())
        assertEquals("a quote opens it", "“Go now.” She said", typeText("", "\"go now.\" she said ").trimEnd())
        assertEquals("a word with a capital inside is left", "iPhone", typeText("", "iPhone ").trimEnd())
        assertEquals("in German too", "Hallo. Wie geht's", typeText("", "hallo. wie geht's ", "de").trimEnd().replace('’', '\''))
        assertEquals("not in Chinese", "中文 abc", typeText("中文 ", "abc ", "zh").trimEnd())
        assertEquals("switched off", "hello", typeText("", "hello ", opts = AutoCorrect.Options(capitalizeSentences = false)).trimEnd())
    }

    @Test
    fun slipsAndTwoCapitalsAndSymbols() {
        assertEquals("I think the cat and I’m sure", typeText("", "i think teh cat adn i'm sure ").trimEnd())
        assertEquals("The", typeText("", "THe ").trimEnd())
        assertEquals("an acronym's plural stays", "Two IDs", typeText("", "two IDs ").trimEnd())
        assertEquals("English slips only in English", "Il libro i", typeText("", "il libro i ", "it").trimEnd())
        assertEquals("© 2026 Acme® Widget™ costs €5 …", typeText("", "(c) 2026 Acme(r) Widget(tm) costs (e)5 ... ").trimEnd())
        assertEquals("f(c) is a formula", "f(c)", typeText("", "f(c)"))
        assertEquals("A → b ← c ⇒ d", typeText("", "a --> b <-- c ==> d ").trimEnd())
        assertEquals("one no longer corrected", "The teh", typeText("", "the teh ", opts = AutoCorrect.Options(skip = setOf("teh"))).trimEnd())
    }

    @Test
    fun quotesDashesOrdinalsFractions() {
        assertEquals("“Quoted” and ‘single’ and it’s", typeText("", "\"quoted\" and 'single' and it's"))
        assertEquals("„Zitat“ hier", typeText("", "\"zitat\" hier", "de"))
        assertEquals("«\u00A0Mot\u00A0» ici", typeText("", "\"mot\" ici", "fr"))
        assertEquals("«Слово»", typeText("", "\"слово\"", "ru"))
        assertEquals("well—known and this – that", typeText("", "well--known and this - that ").trimEnd())
        assertEquals("a list's letter is not capitalised", "item", typeText("", "a) item"))
        assertEquals("½ cup", typeText("", "1/2 cup ").trimEnd())
        val raised = AutoCorrect.after("the 21st ", 9, "en")
        assertEquals(listOf(AutoCorrect.Raise(6, 8)), raised)
        assertTrue("11st is no ordinal", AutoCorrect.after("the 11st ", 9, "en").none { it is AutoCorrect.Raise })
    }

    @Test
    fun listsAndLinks() {
        assertEquals(AutoCorrect.MakeList(2, "•", null), AutoCorrect.after("* ", 2, "en").single())
        assertEquals(AutoCorrect.MakeList(3, null, AutoCorrect.Numbering.DECIMAL_DOT), AutoCorrect.after("1. ", 3, "en").single())
        assertEquals(AutoCorrect.MakeList(3, null, AutoCorrect.Numbering.LOWER_PAREN), AutoCorrect.after("a) ", 3, "en").single())
        assertTrue("not mid-paragraph", AutoCorrect.after("see 1. ", 7, "en").none { it is AutoCorrect.MakeList })
        assertEquals(AutoCorrect.Link(4, 19, "http://www.example.com"), AutoCorrect.after("see www.example.com. ", 21, "en").single())
        assertEquals(AutoCorrect.Link(1, 18, "https://a.org/x?y"), AutoCorrect.after("(https://a.org/x?y) ", 20, "en").single())
        assertEquals(AutoCorrect.Link(0, 12, "mailto:ann@acme.com"), AutoCorrect.after("ann@acme.com ", 13, "en").single())
        assertNull(AutoCorrect.after("www ", 4, "en").singleOrNull { it is AutoCorrect.Link })
    }

    // --- in the editor ---------------------------------------------------------------------------

    private fun editor(text: String = "") = DocumentEditor().apply {
        load(Document(mutableListOf(ParagraphBlock(Paragraph.of(text)))))
        setSelection(0, text.length, text.length)
    }

    /** Types [s] at the caret one character at a time, as the paragraph's field does. */
    private fun DocumentEditor.type(s: String) {
        for (ch in s) {
            val p = activeParagraph
            val old = p.plainText()
            val at = selStart
            replaceText(active, old, old.substring(0, at) + ch + old.substring(at))
            setSelection(active, at + 1, at + 1)
            autoCorrectTyped(active)
        }
    }

    @Test
    fun aCorrectionIsItsOwnUndoStep() {
        val e = editor()
        e.type("teh ")
        assertEquals("The ", e.activeParagraph.plainText())
        val mark = e.autoFixMark!!
        assertEquals(AutoCorrect.Kind.REPLACE, mark.kind)
        assertEquals("teh", mark.was)
        assertEquals(4, e.selStart)
        assertTrue(e.undoAutoFix())
        assertEquals("what was typed comes back", "teh ", e.activeParagraph.plainText())
        assertNull(e.autoFixMark)
        e.type("cat")
        assertEquals("teh cat", e.activeParagraph.plainText())
        assertNull("the next key ends the button", e.autoFixMark)
    }

    @Test
    fun typedListsAndLinksAndEnter() {
        val e = editor()
        e.type("* milk")
        assertEquals("milk", e.activeParagraph.plainText())
        assertEquals(ListKind.BULLET, e.activeParagraph.format.listKind)
        e.undo(); e.undo()
        assertEquals("the typing, then the list undone", "* ", e.activeParagraph.plainText())
        assertEquals(ListKind.NONE, e.activeParagraph.format.listKind)
        val n = editor()
        n.type("1) first")
        assertEquals("first", n.activeParagraph.plainText())
        assertEquals(ListKind.NUMBER, n.activeParagraph.format.listKind)
        val def = n.document.lists[n.activeParagraph.format.listId]!!
        assertEquals("%1)", def.level(0).text)
        val l = editor("Visit")
        l.type(" www.acme.com ")
        val link = l.activeParagraph.runs.first { it.text.contains("acme") }
        assertEquals("http://www.acme.com", link.format.link)
        assertEquals("www.acme.com", link.text)
        // Enter: the word it ends, corrected first.
        val x = editor("see you. thanks")
        val at = x.autoCorrectAtEnter(0, 15)
        assertEquals("see you. Thanks", x.activeParagraph.plainText())
        assertEquals(15, at)
    }

    @Test
    fun notWithChangesTrackedNorInsideALink() {
        val e = editor()
        e.toggleTrackChanges()
        e.type("teh ")
        assertFalse(e.activeParagraph.plainText().startsWith("The"))
        val l = editor("go")
        l.setSelection(0, 0, 2); l.applyLink("https://x.org"); l.setSelection(0, 2, 2)
        l.type(" ")
        assertEquals("a link's text is not changed", "go ", l.activeParagraph.plainText())
    }

    @Test
    fun slidesCorrectInABoxToo() {
        val e = com.therealityteam.aeropages.editor.SlideEditor().apply {
            load(com.therealityteam.aeropages.model.Presentation(mutableListOf(
                com.therealityteam.aeropages.model.Slide(layout = com.therealityteam.aeropages.model.SlideLayout.TITLE_CONTENT),
            )))
        }
        val body = e.slide.body
        body.paragraph.runs.clear(); body.paragraph.runs.add(com.therealityteam.aeropages.model.TextRun(""))
        e.setSelection(body, 0, 0)
        fun type(s: String) = s.forEach { ch ->
            val old = body.text; val at = e.selStart
            e.replaceText(body, old, old.substring(0, at) + ch + old.substring(at))
            e.setSelection(body, at + 1, at + 1)
            e.autoCorrectTyped(body)
        }
        type("first point.\nsecond (c)")
        assertEquals("each line starts a sentence", "First point.\nSecond ©", body.text)
        assertEquals(AutoCorrect.Kind.REPLACE, e.autoFixFor(body)!!.kind)
        assertTrue(e.undoAutoFix())
        assertEquals("First point.\nSecond (c)", e.slide.body.text)
        val again = e.slide.body
        again.paragraph.runs.clear(); again.paragraph.runs.add(com.therealityteam.aeropages.model.TextRun(""))
        e.setSelection(again, 0, 0)
        "* x ".forEach { ch ->
            val old = again.text; val at = e.selStart
            e.replaceText(again, old, old.substring(0, at) + ch + old.substring(at))
            e.setSelection(again, at + 1, at + 1)
            e.autoCorrectTyped(again)
        }
        assertTrue("a box keeps its own bullets: no list made", again.text.startsWith("* "))
    }

    @Test
    fun dayNamesAndOwnEntries() {
        assertEquals("We meet on Monday or Friday", typeText("", "we meet on monday or friday ").trimEnd())
        assertEquals("only in English", "Am montag", typeText("", "am montag ", "de").trimEnd())
        assertEquals("switched off", "On sunday", typeText("", "on sunday ", opts = AutoCorrect.Options(capitalizeDays = false)).trimEnd())
        val own = AutoCorrect.Options(entries = mapOf("addr" to "12 Main Street", ":)" to "\u263A", "ms" to "Microsoft", "AeD" to "Aero Desk"))
        assertEquals("My 12 Main Street, please", typeText("", "my addr, please ", opts = own).trimEnd())
        assertEquals("a key in small letters typed with a capital", "Microsoft and Microsoft", typeText("", "Ms and ms ", opts = own).trimEnd())
        assertEquals("a key with capitals only as typed", "Aero Desk aed", typeText("", "AeD aed ", opts = own).trimEnd())
        assertEquals("a key ending in a mark, at once", "Fine \u263A", typeText("", "fine :)", opts = own))
        assertEquals("not inside a word", "Ms:)", typeText("", "Ms:)", opts = own.copy(entries = mapOf(":)" to "\u263A"))))
        assertEquals("no longer corrected", "My addr", typeText("", "my addr ", opts = own.copy(skip = setOf("addr"))).trimEnd())
        assertTrue(AutoCorrect.validKey("btw")); assertFalse(AutoCorrect.validKey("by the way")); assertFalse(AutoCorrect.validKey(""))
    }

    @Test
    fun excelCorrectsACellWithoutFormatting() {
        val cells = AutoCorrect.Options().forCells()
        fun cell(typed: String): String {
            var text = ""
            for (ch in typed) {
                text += ch
                for (fx in AutoCorrect.after(text, text.length, "en", cells, startsSentence = false))
                    if (fx is AutoCorrect.Replace) text = text.substring(0, fx.start) + fx.text + text.substring(fx.end)
            }
            return text
        }
        assertEquals("a cell's first word starts no sentence", "apple pie", cell("apple pie"))
        assertEquals("hello. World", cell("hello. world "). trimEnd())
        assertEquals("the © Monday", cell("teh (c) monday ").trimEnd())
        assertEquals("quotes stay straight", "say \"hi\"", cell("say \"hi\""))
        assertEquals("no dashes, ordinals or links", "a - b 1st www.x.com", cell("a - b 1st www.x.com "). trimEnd())
        val e = com.therealityteam.aeropages.editor.SheetEditor()
        assertEquals("the " to 4, e.autoCorrected("teh ", 4))
        assertNull("nothing to correct", e.autoCorrected("abc ", 4))
    }

    @Test
    fun anAddressTypedIntoACellBecomesALinkOnEnter() {
        assertEquals("http://www.acme.com", AutoCorrect.linkFor("www.acme.com"))
        assertEquals("mailto:ann@acme.com", AutoCorrect.linkFor("ann@acme.com"))
        assertNull("not the whole entry", AutoCorrect.linkFor("see www.acme.com"))
        val e = com.therealityteam.aeropages.editor.SheetEditor().apply { load(com.therealityteam.aeropages.model.Workbook.blank()); select(0, 0) }
        e.typeCell(0, 0, "https://acme.com/a"); e.commitEntry()
        assertEquals("https://acme.com/a", e.sheet.cell(0, 0)!!.link)
        e.undo()
        assertTrue("entry and link go together", e.sheet.cell(0, 0)?.text.isNullOrEmpty())
        e.select(1, 0); e.typeCell(1, 0, "plain text"); e.commitEntry()
        assertNull(e.sheet.cell(1, 0)!!.link)
    }
}
