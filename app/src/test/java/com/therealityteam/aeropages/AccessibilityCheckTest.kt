package com.therealityteam.aeropages

import com.therealityteam.aeropages.model.AccessibilityCheck
import com.therealityteam.aeropages.model.AccessibilityCheck.Kind
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.MergeRegion
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.SheetImage
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TextRun
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.therealityteam.aeropages.ui.statusFindings

/** Office's Check Accessibility findings for a document, a deck and a workbook. */
class AccessibilityCheckTest {
    private fun heading(text: String, style: ParagraphStyle) = ParagraphBlock(Paragraph.of(text).also { it.format.style = style })

    @Test
    fun aDocumentsPicturesTablesLinksAndHeadings() {
        val linked = Paragraph(mutableListOf(TextRun("See "), TextRun("https://example.com", CharacterFormat(link = "https://example.com")),
            TextRun(" or "), TextRun("our guide", CharacterFormat(link = "https://example.com/guide"))))
        val doc = Document(mutableListOf(
            heading("Overview", ParagraphStyle.HEADING1),
            ImageBlock(TestPng.header(10, 10), "image/png", 10f, 10f),
            ImageBlock(TestPng.header(10, 10), "image/png", 10f, 10f, altText = "A map"),
            ImageBlock(TestPng.header(10, 10), "image/png", 10f, 10f, decorative = true),
            heading("Details", ParagraphStyle.HEADING3),
            TableBlock(Table.of(2, 2)),
            ParagraphBlock(linked),
        ))
        val found = AccessibilityCheck.of(doc)
        assertEquals(listOf(Kind.MISSING_ALT_TEXT, Kind.SKIPPED_HEADING, Kind.MISSING_TABLE_HEADER, Kind.UNCLEAR_LINK), found.map { it.kind })
        assertEquals("only the first picture: the second has alt text, the third is decorative", 1, found[0].ordinal)
        assertEquals("Details", found[1].label)
        assertEquals("the address shown as its own text, not the named link", "https://example.com", found[3].label)
        // A header row clears the table.
        (doc.blocks[5] as TableBlock).table.rows[0].header = true
        assertTrue(AccessibilityCheck.of(doc).none { it.kind == Kind.MISSING_TABLE_HEADER })
    }

    @Test
    fun aDecksTitlesPicturesAndRepeats() {
        val deck = Presentation(mutableListOf(
            Slide(title = SlideBox("Plan")),
            Slide(title = SlideBox("")).also { it.images.add(SlideImage(TestPng.header(10, 10), "image/png", 10f, 10f)) },
            Slide(title = SlideBox("plan")),
        ))
        val found = AccessibilityCheck.of(deck)
        assertEquals(listOf(Kind.MISSING_SLIDE_TITLE to 1, Kind.MISSING_ALT_TEXT to 1, Kind.DUPLICATE_SLIDE_TITLE to 2), found.map { it.kind to it.slide })
        assertEquals(AccessibilityCheck.Severity.WARNING, found[2].severity)
    }

    @Test
    fun aWorkbooksPicturesMergesAndSheetNames() {
        val wb = Workbook(mutableListOf(
            Sheet(name = "Sheet1").also { s ->
                s.images.add(SheetImage(TestPng.header(10, 10), "image/png", 1, 1, widthPt = 10f, heightPt = 10f, name = "Logo"))
                s.merges.add(MergeRegion(0, 0, 0, 2))
            },
            Sheet(name = "Budget 2026"),
        ))
        val found = AccessibilityCheck.of(wb)
        assertEquals(listOf(Kind.MISSING_ALT_TEXT, Kind.MERGED_CELLS, Kind.DEFAULT_SHEET_NAME), found.map { it.kind })
        assertEquals("Logo", found[0].label)
        assertEquals("A1:C1", found[1].label)
        assertTrue(found.all { it.sheet == 0 })
    }

    @Test
    fun theStatusBarCountsErrorsAndWarningsNotTips() {
        // A workbook whose only finding is a default sheet name (a tip) is good to go.
        assertEquals(0, statusFindings(AccessibilityCheck.of(Workbook(mutableListOf(Sheet(name = "Sheet1"))))))
        val wb = Workbook(mutableListOf(Sheet(name = "Sheet1").also { it.merges.add(MergeRegion(0, 0, 0, 1)) }))
        assertEquals("the merge is a warning", 1, statusFindings(AccessibilityCheck.of(wb)))
    }
}
