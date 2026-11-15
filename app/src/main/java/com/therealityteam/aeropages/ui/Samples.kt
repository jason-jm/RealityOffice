package com.therealityteam.aeropages.ui

import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun
import androidx.compose.ui.res.stringResource
import com.therealityteam.aeropages.R

/** Builds a demo document that exercises the editor's formatting features. */
object Samples {

    fun welcome(): Document {
        val doc = Document(mutableListOf())

        fun add(p: Paragraph) = doc.addParagraph(p)

        add(
            Paragraph(
                mutableListOf(TextRun("Reality Office · Pages")),
                ParagraphFormat(style = ParagraphStyle.TITLE, rawStyleId = "Title", align = TextAlign.CENTER),
            )
        )
        add(
            Paragraph(
                mutableListOf(
                    TextRun(
                        Strings.get(R.string.sample_subtitle_app),
                        CharacterFormat(italic = true, color = 0xFF6AA84F),
                    )
                ),
                ParagraphFormat(align = TextAlign.CENTER, spaceAfterPt = 16f),
            )
        )

        add(heading(Strings.get(R.string.sample_heading_welcome), ParagraphStyle.HEADING1))
        add(
            Paragraph(
                mutableListOf(
                    TextRun(Strings.get(R.string.sample_intro_part1)),
                    TextRun("SpatialUI", CharacterFormat(bold = true, color = 0xFF3D85C6)),
                    TextRun(Strings.get(R.string.sample_intro_part2)),
                    TextRun(".docx", CharacterFormat(fontFamily = "Consolas", highlight = 0xFFFFFF00)),
                    TextRun(Strings.get(R.string.sample_intro_part3)),
                    TextRun(".doc", CharacterFormat(fontFamily = "Consolas", highlight = 0xFFFFFF00)),
                    TextRun(Strings.get(R.string.sample_intro_part4)),
                ),
                ParagraphFormat(),
            )
        )

        add(heading(Strings.get(R.string.sample_heading_text_format), ParagraphStyle.HEADING2))
        add(plainListItem(Strings.get(R.string.sample_list_text_styles), ListKind.BULLET))
        add(plainListItem(Strings.get(R.string.sample_list_font_props), ListKind.BULLET))
        add(plainListItem(Strings.get(R.string.sample_list_highlight), ListKind.BULLET))

        add(heading(Strings.get(R.string.sample_heading_paragraph_styles), ParagraphStyle.HEADING2))
        add(numbered(Strings.get(R.string.sample_list_alignment), 1))
        add(numbered(Strings.get(R.string.sample_list_bullets_numbering), 2))
        add(numbered(Strings.get(R.string.sample_list_heading_spacing), 3))

        add(
            Paragraph(
                mutableListOf(TextRun(Strings.get(R.string.sample_tagline))),
                ParagraphFormat(style = ParagraphStyle.QUOTE, rawStyleId = "Quote", align = TextAlign.CENTER, indentLeftPt = 36f),
            )
        )

        add(heading(Strings.get(R.string.table), ParagraphStyle.HEADING2))
        doc.blocks.add(com.therealityteam.aeropages.model.TableBlock(sampleTable()))

        add(Paragraph.of(Strings.get(R.string.sample_table_hint)))
        add(heading(Strings.get(R.string.sample_heading_images_paging), ParagraphStyle.HEADING2))
        add(Paragraph.of(Strings.get(R.string.sample_image_paging_hint)))

        doc.header = Paragraph(
            mutableListOf(TextRun(Strings.get(R.string.sample_header_app))),
            ParagraphFormat(align = TextAlign.CENTER, spaceAfterPt = 0f),
        )
        doc.footer = Paragraph(
            mutableListOf(TextRun(Strings.get(R.string.sample_footer_confidential))),
            ParagraphFormat(align = TextAlign.CENTER, spaceAfterPt = 0f),
        )
        return doc
    }

    /** A small 3x3 demo table with a bold header row. */
    private fun sampleTable(): com.therealityteam.aeropages.model.Table {
        fun cell(text: String, bold: Boolean = false) = com.therealityteam.aeropages.model.TableCell(
            mutableListOf(Paragraph(mutableListOf(TextRun(text, CharacterFormat(bold = bold))), ParagraphFormat(spaceAfterPt = 0f))),
        )
        fun row(vararg cells: com.therealityteam.aeropages.model.TableCell) =
            com.therealityteam.aeropages.model.TableRow(cells.toMutableList())
        return com.therealityteam.aeropages.model.Table(
            mutableListOf(
                row(cell(Strings.get(R.string.sample_col_feature), true), cell(Strings.get(R.string.sample_col_status), true), cell(Strings.get(R.string.sample_col_description), true)),
                row(cell(Strings.get(R.string.sample_row_open_save)), cell("✓"), cell(Strings.get(R.string.sample_row_docx_support))),
                row(cell(Strings.get(R.string.table)), cell("✓"), cell(Strings.get(R.string.sample_row_table_support))),
            ),
        )
    }

    private fun heading(text: String, style: ParagraphStyle) = Paragraph(
        mutableListOf(TextRun(text, CharacterFormat(bold = style.bold, fontSizePt = style.sizePt))),
        ParagraphFormat(style = style, rawStyleId = style.styleId, spaceBeforePt = 12f, spaceAfterPt = 6f),
    )

    private fun plainListItem(text: String, kind: ListKind) = Paragraph(
        mutableListOf(TextRun(text)),
        ParagraphFormat(listKind = kind, spaceAfterPt = 2f),
    )

    private fun numbered(text: String, ordinal: Int) = Paragraph(
        mutableListOf(TextRun(text)),
        ParagraphFormat(listKind = ListKind.NUMBER, spaceAfterPt = 2f),
    )
}
