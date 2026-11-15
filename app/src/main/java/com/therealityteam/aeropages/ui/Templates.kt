package com.therealityteam.aeropages.ui

import androidx.annotation.StringRes
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.format.DeskKind
import com.therealityteam.aeropages.model.BorderLine
import com.therealityteam.aeropages.model.BoxFrame
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.CondFormat
import com.therealityteam.aeropages.model.CondStyle
import com.therealityteam.aeropages.model.CondType
import com.therealityteam.aeropages.model.DataValidation
import com.therealityteam.aeropages.model.DesignShape
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.FieldDates
import com.therealityteam.aeropages.model.FormatCode
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.MasterText
import com.therealityteam.aeropages.model.MergeRegion
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.PagePresets
import com.therealityteam.aeropages.model.Paper
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ScaleKind
import com.therealityteam.aeropages.model.ScalePoint
import com.therealityteam.aeropages.model.ShapeAnchor
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.SheetTable
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideDesign
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.SlideListStyle
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.TabAlign
import com.therealityteam.aeropages.model.TabStop
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TableCell
import com.therealityteam.aeropages.model.TableRow
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun
import com.therealityteam.aeropages.model.ValidationType
import com.therealityteam.aeropages.model.Workbook
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** What the home screen and the File menus offer to start from: each module's blank document and its templates. */
enum class DeskTemplate(val kind: DeskKind, @StringRes val label: Int) {
    BLANK_DOC(DeskKind.PAGES, R.string.tpl_name_blank_doc),
    REPORT(DeskKind.PAGES, R.string.tpl_name_report),
    LETTER(DeskKind.PAGES, R.string.tpl_name_letter),
    MINUTES(DeskKind.PAGES, R.string.tpl_name_minutes),
    RESUME(DeskKind.PAGES, R.string.tpl_name_resume),
    BLANK_DECK(DeskKind.SLIDES, R.string.tpl_name_blank_deck),
    DECK_BUSINESS(DeskKind.SLIDES, R.string.tpl_name_deck_business),
    DECK_FRESH(DeskKind.SLIDES, R.string.tpl_name_deck_fresh),
    DECK_DARK(DeskKind.SLIDES, R.string.tpl_name_deck_dark),
    BLANK_BOOK(DeskKind.SHEETS, R.string.tpl_name_blank_book),
    BUDGET(DeskKind.SHEETS, R.string.tpl_name_budget),
    TASKS(DeskKind.SHEETS, R.string.tpl_name_tasks),
    INVOICE(DeskKind.SHEETS, R.string.tpl_name_invoice);

    val blank: Boolean get() = this == BLANK_DOC || this == BLANK_DECK || this == BLANK_BOOK

    companion object {
        /** The module's choices, its blank document first. */
        fun of(kind: DeskKind): List<DeskTemplate> = entries.filter { it.kind == kind }
    }
}

/**
 * The templates' content, in the language the app shows: [text] looks the words up (the app's
 * strings; tests give each language's), [today] dates letters, minutes, task lists and invoices.
 */
class Templates(private val text: (Int) -> String = { Strings.get(it) }, private val today: LocalDate = LocalDate.now()) {

    private fun s(@StringRes id: Int) = text(id)
    private fun lines(@StringRes id: Int) = text(id).split('\n')
    private fun dateText(d: LocalDate) = FieldDates.format(s(R.string.tpl_date_pattern), LocalDateTime.of(d, java.time.LocalTime.NOON))

    /** A new document from [t], on the reader's paper and margins (Letter or A4, `PagePresets.regionalPage`). */
    fun document(t: DeskTemplate): Document = when (t) {
        DeskTemplate.REPORT -> report()
        DeskTemplate.LETTER -> letter()
        DeskTemplate.MINUTES -> minutes()
        DeskTemplate.RESUME -> resume()
        else -> Document.newBlank()
    }

    fun workbook(t: DeskTemplate): Workbook = when (t) {
        DeskTemplate.BUDGET -> Workbook(mutableListOf(budget()))
        DeskTemplate.TASKS -> Workbook(mutableListOf(tasks()))
        DeskTemplate.INVOICE -> Workbook(mutableListOf(invoice()))
        else -> Workbook.newBlank()
    }.also { wb -> val letter = PagePresets.usesLetter(); wb.sheets.forEach { it.paper = if (letter) Paper.LETTER else Paper.A4 } }

    fun presentation(t: DeskTemplate): Presentation = when (t) {
        DeskTemplate.DECK_BUSINESS -> deck(DeckDesigns.BUSINESS)
        DeskTemplate.DECK_FRESH -> deck(DeckDesigns.FRESH)
        DeskTemplate.DECK_DARK -> deck(DeckDesigns.DARK)
        else -> Presentation.blank()
    }

    // ---- Documents ----

    private fun run(t: String, size: Float? = null, bold: Boolean = false, color: Long? = null, italic: Boolean = false) =
        TextRun(t, CharacterFormat(bold = bold, italic = italic, fontSizePt = size, color = color))

    private fun para(t: String, f: ParagraphFormat = ParagraphFormat(), size: Float? = null, bold: Boolean = false, color: Long? = null) =
        Paragraph(mutableListOf(run(t, size, bold, color)), f)

    private fun Document.heading(t: String, color: Long, size: Float = 15f, style: ParagraphStyle = ParagraphStyle.HEADING1) =
        addParagraph(Paragraph(mutableListOf(run(t, size, bold = true, color = color)),
            ParagraphFormat(style = style, rawStyleId = style.styleId, spaceBeforePt = 16f, spaceAfterPt = 6f)))

    private fun Document.body(t: String, spaceAfter: Float = 8f) = addParagraph(para(t, ParagraphFormat(spaceAfterPt = spaceAfter, lineSpacing = 1.25f)))

    private fun Document.items(lines: List<String>, kind: ListKind) = lines.forEach {
        addParagraph(para(it, ParagraphFormat(listKind = kind, spaceAfterPt = 4f, lineSpacing = 1.2f)))
    }

    /** A table whose first row is a header in [head] with white bold text; [widths] are the columns' weights. */
    private fun table(rows: List<List<String>>, head: Long, widths: List<Float> = emptyList(), labelColumn: Long? = null): Table {
        val t = Table(gridLine = com.therealityteam.aeropages.model.CellBorder(color = 0xFFBFBFBF))
        t.columnWidths.addAll(widths)
        rows.forEachIndexed { r, cells ->
            val row = TableRow(header = r == 0 && labelColumn == null)
            cells.forEachIndexed { c, v ->
                val header = r == 0 && labelColumn == null
                val label = labelColumn != null && c == 0
                val p = Paragraph(mutableListOf(run(v, bold = header || label, color = if (header) 0xFFFFFFFF else null)), ParagraphFormat(spaceAfterPt = 0f))
                row.cells.add(TableCell(mutableListOf(p), shade = if (header) head else if (label) labelColumn else null))
            }
            t.rows.add(row)
        }
        return t
    }

    private fun report(): Document {
        val d = Document(mutableListOf()).also { it.page.assignFrom(PagePresets.regionalPage()) }
        d.addParagraph(para(s(R.string.tpl_report_title), ParagraphFormat(style = ParagraphStyle.TITLE, rawStyleId = "Title", spaceAfterPt = 4f), size = 28f, bold = true, color = INK))
        d.addParagraph(para(s(R.string.tpl_report_subtitle) + " · " + dateText(today), ParagraphFormat(spaceAfterPt = 22f), color = GRAY))
        d.heading(s(R.string.tpl_report_h_summary), INK)
        d.body(s(R.string.tpl_report_summary))
        d.heading(s(R.string.tpl_report_h_background), INK)
        d.body(s(R.string.tpl_report_background))
        d.heading(s(R.string.tpl_report_h_findings), INK)
        d.body(s(R.string.tpl_report_findings))
        val goals = lines(R.string.tpl_report_goals)
        val done = s(R.string.tpl_status_done); val going = s(R.string.tpl_status_progress)
        d.blocks.add(TableBlock(table(listOf(
            listOf(s(R.string.tpl_col_goal), s(R.string.tpl_col_target), s(R.string.tpl_col_actual), s(R.string.tpl_col_status)),
            listOf(goals[0], "90%", "92%", done),
            listOf(goals[1], "10%", "7%", going),
            listOf(goals[2], "200", "236", done),
        ), INK, listOf(2.2f, 1f, 1f, 1.2f))))
        d.addParagraph(para("", ParagraphFormat(spaceAfterPt = 0f)))
        d.heading(s(R.string.tpl_report_h_recommendations), INK)
        d.items(lines(R.string.tpl_report_recommendations), ListKind.BULLET)
        d.header = Paragraph(mutableListOf(run(s(R.string.tpl_report_title), 9f, color = GRAY)), ParagraphFormat(align = TextAlign.RIGHT, spaceAfterPt = 0f))
        d.footer = Paragraph(mutableListOf(run(s(R.string.sample_page_of_pages), 9f, color = GRAY)),
            ParagraphFormat(align = TextAlign.CENTER, spaceAfterPt = 0f))
        return d
    }

    /** A letter laid out as the language writes one: English block style, Chinese 称呼 … 此致 敬礼, Japanese 拝啓 … 敬具. */
    private fun letter(): Document {
        val d = Document(mutableListOf()).also { it.page.assignFrom(PagePresets.regionalPage()) }
        val tight = ParagraphFormat(spaceAfterPt = 0f)
        fun block(id: Int, align: TextAlign = TextAlign.LEFT, after: Float = 18f, boldFirst: Boolean = false) {
            val ls = lines(id)
            ls.forEachIndexed { i, l ->
                d.addParagraph(para(l, ParagraphFormat(align = align, spaceAfterPt = if (i == ls.lastIndex) after else 0f), bold = boldFirst && i == 0))
            }
        }
        val body = ParagraphFormat(spaceAfterPt = 10f, lineSpacing = 1.3f)
        when (s(R.string.tpl_letter_style)) {
            "chinese" -> {
                // 称呼顶格；正文、"此致"首行缩进两字，"敬礼"顶格；署名和日期在右下。
                d.addParagraph(para(s(R.string.tpl_letter_salutation), ParagraphFormat(spaceAfterPt = 8f)))
                val indent = body.copyOf().also { it.firstLineIndentPt = 22f }
                d.addParagraph(para(s(R.string.tpl_letter_body1), indent))
                d.addParagraph(para(s(R.string.tpl_letter_body2), indent.copyOf()))
                val closing = lines(R.string.tpl_letter_closing)
                d.addParagraph(para(closing[0], ParagraphFormat(spaceBeforePt = 6f, spaceAfterPt = 0f, firstLineIndentPt = 22f)))
                closing.drop(1).forEach { d.addParagraph(para(it, ParagraphFormat(spaceAfterPt = 30f))) }
                d.addParagraph(para(s(R.string.tpl_your_name), ParagraphFormat(align = TextAlign.RIGHT, spaceAfterPt = 0f)))
                d.addParagraph(para(dateText(today), ParagraphFormat(align = TextAlign.RIGHT, spaceAfterPt = 0f)))
            }
            "japanese" -> {
                d.addParagraph(para(dateText(today), ParagraphFormat(align = TextAlign.RIGHT, spaceAfterPt = 12f)))
                block(R.string.tpl_letter_recipient, after = 12f)
                block(R.string.tpl_letter_sender, TextAlign.RIGHT, after = 24f)
                val indent = body.copyOf().also { it.firstLineIndentPt = 11f }
                d.addParagraph(para(s(R.string.tpl_letter_salutation), indent))
                d.addParagraph(para(s(R.string.tpl_letter_body1), indent.copyOf()))
                d.addParagraph(para(s(R.string.tpl_letter_body2), indent.copyOf()))
                d.addParagraph(para(s(R.string.tpl_letter_closing), ParagraphFormat(align = TextAlign.RIGHT, spaceBeforePt = 6f, spaceAfterPt = 0f)))
            }
            else -> {
                block(R.string.tpl_letter_sender, boldFirst = true)
                d.addParagraph(para(dateText(today), ParagraphFormat(spaceAfterPt = 18f)))
                block(R.string.tpl_letter_recipient)
                d.addParagraph(para(s(R.string.tpl_letter_salutation), ParagraphFormat(spaceAfterPt = 10f)))
                d.addParagraph(para(s(R.string.tpl_letter_body1), body))
                d.addParagraph(para(s(R.string.tpl_letter_body2), body.copyOf()))
                d.addParagraph(para(s(R.string.tpl_letter_closing), ParagraphFormat(spaceBeforePt = 6f, spaceAfterPt = 36f)))
                d.addParagraph(para(s(R.string.tpl_your_name), tight))
            }
        }
        return d
    }

    private fun minutes(): Document {
        val d = Document(mutableListOf()).also { it.page.assignFrom(PagePresets.regionalPage()) }
        d.addParagraph(para(s(R.string.tpl_minutes_title), ParagraphFormat(style = ParagraphStyle.TITLE, rawStyleId = "Title", align = TextAlign.CENTER, spaceAfterPt = 14f),
            size = 24f, bold = true, color = INK))
        val labels = lines(R.string.tpl_minutes_labels)
        val name = s(R.string.tpl_name_placeholder)
        val values = listOf(s(R.string.tpl_minutes_subject), dateText(today) + "  14:00 – 15:00", s(R.string.tpl_minutes_room), name, name, name)
        d.blocks.add(TableBlock(table(labels.zip(values) { l, v -> listOf(l, v) }, INK, listOf(1f, 3.4f), labelColumn = 0xFFDEEAF6)))
        d.heading(s(R.string.tpl_minutes_h_agenda), INK, 14f)
        d.items(lines(R.string.tpl_minutes_agenda), ListKind.NUMBER)
        d.heading(s(R.string.tpl_minutes_h_discussion), INK, 14f)
        d.body(s(R.string.tpl_minutes_discussion))
        d.heading(s(R.string.tpl_minutes_h_actions), INK, 14f)
        val due = FieldDates.format("yyyy-MM-dd", LocalDateTime.of(today.plusDays(7), java.time.LocalTime.NOON))
        d.blocks.add(TableBlock(table(listOf(
            listOf(s(R.string.tpl_col_action), s(R.string.tpl_col_owner), s(R.string.tpl_col_due), s(R.string.tpl_col_status)),
            listOf(s(R.string.tpl_minutes_action), name, due, s(R.string.tpl_status_todo)),
            listOf("", "", "", ""),
            listOf("", "", "", ""),
        ), INK, listOf(2.6f, 1f, 1.1f, 1f))))
        d.heading(s(R.string.tpl_minutes_h_next), INK, 14f)
        d.body(s(R.string.tpl_minutes_next))
        return d
    }

    private fun resume(): Document {
        val d = Document(mutableListOf()).also { it.page.assignFrom(PagePresets.regionalPage()) }
        val right = TabStop(d.page.widthPt - d.page.marginLeftPt - d.page.marginRightPt, TabAlign.RIGHT)
        d.addParagraph(para(s(R.string.tpl_your_name), ParagraphFormat(style = ParagraphStyle.TITLE, rawStyleId = "Title", spaceAfterPt = 2f), size = 26f, bold = true, color = TEAL))
        d.addParagraph(para(s(R.string.tpl_resume_contact), ParagraphFormat(spaceAfterPt = 14f), color = GRAY))
        fun section(id: Int) = d.heading(s(id), TEAL, 13f, ParagraphStyle.HEADING2)
        fun entry(title: String, period: String) = d.addParagraph(Paragraph(
            mutableListOf(run(title, bold = true), run("\t$period", color = GRAY)),
            ParagraphFormat(spaceBeforePt = 4f, spaceAfterPt = 3f, tabStops = listOf(right))))
        section(R.string.tpl_resume_h_profile)
        d.body(s(R.string.tpl_resume_profile))
        section(R.string.tpl_resume_h_experience)
        val y = today.year
        entry(s(R.string.tpl_resume_job), "${y - 3}.03 – ${s(R.string.tpl_resume_now)}")
        d.items(lines(R.string.tpl_resume_achievements), ListKind.BULLET)
        entry(s(R.string.tpl_resume_job), "${y - 6}.07 – ${y - 3}.02")
        d.items(lines(R.string.tpl_resume_achievements).take(1), ListKind.BULLET)
        section(R.string.tpl_resume_h_education)
        entry(s(R.string.tpl_resume_school), "${y - 10} – ${y - 6}")
        section(R.string.tpl_resume_h_skills)
        d.items(lines(R.string.tpl_resume_skills), ListKind.BULLET)
        return d
    }

    // ---- Workbooks ----

    private fun Sheet.put(r: Int, c: Int, value: String, bold: Boolean = false, size: Float? = null, color: Long? = null, fill: Long? = null,
                          align: TextAlign? = null, code: String? = null, italic: Boolean = false): Cell {
        val cell = ensure(r, c)
        cell.text = value
        cell.format.bold = bold; cell.format.italic = italic; cell.format.fontSizePt = size; cell.format.color = color; cell.format.highlight = fill
        cell.align = align
        if (code != null) { cell.formatCode = code; cell.numberFormat = FormatCode.category(code) }
        return cell
    }

    private fun serial(d: LocalDate) = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), d).toString()

    private fun Sheet.date(r: Int, c: Int, d: LocalDate, align: TextAlign? = null) =
        put(r, c, serial(d), align = align).also { it.numberFormat = NumberFormat.DATE }

    private fun budget(): Sheet {
        val sh = Sheet(s(R.string.tpl_budget_sheet), rowCount = 40, colCount = 8)
        val money = "#,##0"
        sh.put(0, 0, s(R.string.tpl_name_budget), bold = true, size = 20f, color = GREEN_DARK)
        sh.merges.add(MergeRegion(0, 0, 0, 3))
        sh.put(1, 0, s(R.string.tpl_budget_note), italic = true, color = GRAY).wrap = true
        sh.merges.add(MergeRegion(1, 0, 1, 3)); sh.rowHeights[0] = 1.6f; sh.rowHeights[1] = 1.5f
        val head = listOf(s(R.string.tpl_col_item), s(R.string.tpl_col_budget), s(R.string.tpl_col_actual), s(R.string.tpl_col_diff))
        fun band(r: Int, label: String) {
            for (c in 0..3) sh.put(r, c, if (c == 0) label else "", bold = true, color = 0xFFFFFFFF, fill = GREEN)
            for (c in 0..3) sh.put(r + 1, c, head[c], bold = true, fill = GREEN_TINT, align = if (c == 0) null else TextAlign.RIGHT).borderBottom = BorderLine(color = GREEN)
        }
        fun total(r: Int, from: Int, to: Int, label: String, diff: String) {
            sh.put(r, 0, label, bold = true).borderTop = BorderLine(color = GREEN)
            for (c in 1..2) { val col = Workbook.colLabel(c); sh.put(r, c, "=SUM($col${from + 1}:$col${to + 1})", bold = true, code = money).borderTop = BorderLine(color = GREEN) }
            sh.put(r, 3, diff, bold = true, code = money).borderTop = BorderLine(color = GREEN)
        }
        // Income: rows 6–7, its total in row 8; the difference is what came in over the budget.
        band(3, s(R.string.tpl_budget_income))
        val income = lines(R.string.tpl_budget_income_items)
        val incomeValues = listOf(8000 to 8000, 500 to 650)
        income.forEachIndexed { i, name ->
            val r = 5 + i
            sh.put(r, 0, name); sh.put(r, 1, "${incomeValues[i].first}", code = money); sh.put(r, 2, "${incomeValues[i].second}", code = money)
            sh.put(r, 3, "=C${r + 1}-B${r + 1}", code = money)
        }
        total(7, 5, 6, s(R.string.tpl_total), "=C8-B8")
        // Expenses: rows 12–18, total in row 19; the difference is what was left of the budget.
        band(9, s(R.string.tpl_budget_expenses))
        val expenses = lines(R.string.tpl_budget_expense_items)
        val expenseValues = listOf(2500 to 2500, 1500 to 1720, 400 to 360, 300 to 280, 500 to 640, 2000 to 2000, 300 to 150)
        expenses.forEachIndexed { i, name ->
            val r = 11 + i
            sh.put(r, 0, name); sh.put(r, 1, "${expenseValues.getOrElse(i) { 0 to 0 }.first}", code = money); sh.put(r, 2, "${expenseValues.getOrElse(i) { 0 to 0 }.second}", code = money)
            sh.put(r, 3, "=B${r + 1}-C${r + 1}", code = money)
        }
        val last = 11 + expenses.size - 1
        total(last + 1, 11, last, s(R.string.tpl_total), "=B${last + 2}-C${last + 2}")
        val bal = last + 3
        sh.put(bal, 0, s(R.string.tpl_budget_balance), bold = true, size = 13f, color = GREEN_DARK)
        sh.put(bal, 1, "=B8-B${last + 2}", bold = true, size = 13f, code = money)
        sh.put(bal, 2, "=C8-C${last + 2}", bold = true, size = 13f, code = money)
        sh.put(bal, 3, "=C${bal + 1}-B${bal + 1}", bold = true, size = 13f, code = money)
        sh.rowHeights[bal] = 1.4f
        for (r in listOf(5..7, 11..last + 1, bal..bal)) {
            sh.condFormats.add(CondFormat(r.first, 3, r.last, 3, CondType.LESS, style = CondStyle(fill = 0xFFFFC7CE, font = 0xFF9C0006)))
            sh.condFormats.add(CondFormat(r.first, 3, r.last, 3, CondType.GREATER, style = CondStyle(fill = 0xFFC6EFCE, font = 0xFF006100)))
        }
        sh.condFormats.add(CondFormat(11, 2, last, 2, CondType.DATA_BAR, color = 0xFF63BE7B, barMin = 0, barMax = 100))
        sh.colWidths[0] = 2.0f; for (c in 1..3) sh.colWidths[c] = 1.2f
        return sh
    }

    private fun tasks(): Sheet {
        val sh = Sheet(s(R.string.tpl_tasks_sheet), rowCount = 60, colCount = 10)
        sh.put(0, 0, s(R.string.tpl_name_tasks), bold = true, size = 20f, color = INK)
        sh.rowHeights[0] = 1.6f
        val head = listOf(R.string.tpl_col_task, R.string.tpl_col_owner, R.string.tpl_col_start, R.string.tpl_col_due, R.string.tpl_col_status, R.string.tpl_col_priority, R.string.tpl_col_progress).map { s(it) }
        head.forEachIndexed { c, h -> sh.put(2, c, h, bold = true) }
        val todo = s(R.string.tpl_status_todo); val going = s(R.string.tpl_status_progress); val done = s(R.string.tpl_status_done); val hold = s(R.string.tpl_status_hold)
        val high = s(R.string.tpl_priority_high); val medium = s(R.string.tpl_priority_medium); val low = s(R.string.tpl_priority_low)
        val names = lines(R.string.tpl_tasks_items); val owners = lines(R.string.tpl_tasks_owners)
        data class Row(val start: Long, val due: Long, val status: String, val priority: String, val progress: String)
        val rows = listOf(Row(-21, -14, done, high, "1"), Row(-14, -2, going, medium, "0.8"), Row(-7, 7, going, high, "0.4"), Row(3, 14, todo, medium, "0"), Row(14, 21, todo, low, "0"))
        rows.forEachIndexed { i, row ->
            val r = 3 + i
            sh.put(r, 0, names.getOrElse(i) { "" }); sh.put(r, 1, owners[i % owners.size])
            sh.date(r, 2, today.plusDays(row.start)); sh.date(r, 3, today.plusDays(row.due))
            sh.put(r, 4, row.status); sh.put(r, 5, row.priority, align = TextAlign.CENTER); sh.put(r, 6, row.progress, code = "0%")
        }
        val lastRow = 3 + rows.size - 1
        sh.tables.add(SheetTable("Tasks", 2, 0, lastRow, 6))
        // Rules and lists reach below the table, for the rows added to it.
        val end = 49
        sh.validations.add(DataValidation(3, 4, end, 4, ValidationType.LIST, items = listOf(todo, going, done, hold)))
        sh.validations.add(DataValidation(3, 5, end, 5, ValidationType.LIST, items = listOf(high, medium, low)))
        sh.validations.add(DataValidation(3, 6, end, 6, ValidationType.DECIMAL, min = 0.0, max = 1.0))
        fun quoted(t: String) = "\"" + t.replace("\"", "\"\"") + "\""
        sh.condFormats.add(CondFormat(3, 4, end, 4, CondType.EQUAL, formula1 = quoted(done), style = CondStyle(fill = 0xFFC6EFCE, font = 0xFF006100)))
        sh.condFormats.add(CondFormat(3, 4, end, 4, CondType.EQUAL, formula1 = quoted(going), style = CondStyle(fill = 0xFFFFEB9C, font = 0xFF9C5700)))
        sh.condFormats.add(CondFormat(3, 5, end, 5, CondType.EQUAL, formula1 = quoted(high), style = CondStyle(font = 0xFFC00000, bold = true)))
        // Overdue: a due date gone by on a task not done.
        sh.condFormats.add(CondFormat(3, 3, end, 3, CondType.FORMULA, formula1 = "AND(\$D4<>\"\",\$D4<TODAY(),\$E4<>${quoted(done)})", style = CondStyle(fill = 0xFFFFC7CE, font = 0xFF9C0006, bold = true)))
        sh.condFormats.add(CondFormat(3, 6, end, 6, CondType.DATA_BAR, color = 0xFF5B9BD5,
            barEnds = listOf(ScalePoint(ScaleKind.NUMBER, 0.0), ScalePoint(ScaleKind.NUMBER, 1.0)), barMin = 0, barMax = 100))
        sh.freezeRows = 3
        sh.landscape = true   // seven columns: a portrait page cuts off the priority and progress
        listOf(2.4f, 1.1f, 1.2f, 1.2f, 1.1f, 0.9f, 1.0f).forEachIndexed { c, w -> sh.colWidths[c] = w }
        return sh
    }

    private fun invoice(): Sheet {
        val sh = Sheet(s(R.string.tpl_invoice_sheet), rowCount = 40, colCount = 8)
        val money = "#,##0.00"
        val from = lines(R.string.tpl_invoice_from)
        from.forEachIndexed { i, l -> sh.put(i, 0, l, bold = i == 0, size = if (i == 0) 14f else null, color = if (i == 0) INK else GRAY) }
        sh.put(0, 3, s(R.string.tpl_invoice_title), bold = true, size = 24f, color = INK, align = TextAlign.RIGHT)
        sh.rowHeights[0] = 1.8f
        // Who it is for, and the invoice's number and dates.
        sh.put(4, 0, s(R.string.tpl_invoice_bill_to), bold = true, color = GRAY)
        lines(R.string.tpl_invoice_client).forEachIndexed { i, l -> sh.put(5 + i, 0, l, bold = i == 0) }
        val labels = lines(R.string.tpl_invoice_labels)
        sh.put(4, 2, labels[0], color = GRAY, align = TextAlign.RIGHT); sh.put(4, 3, "INV-0001", align = TextAlign.RIGHT)
        sh.put(5, 2, labels[1], color = GRAY, align = TextAlign.RIGHT); sh.date(5, 3, today, TextAlign.RIGHT)
        sh.put(6, 2, labels[2], color = GRAY, align = TextAlign.RIGHT)
        sh.put(6, 3, "=D6+30", align = TextAlign.RIGHT).numberFormat = NumberFormat.DATE
        // The items: amount = quantity × unit price; two empty rows for more.
        val head = listOf(s(R.string.sample_col_description), s(R.string.tpl_col_qty), s(R.string.tpl_col_unit_price), s(R.string.tpl_col_amount))
        head.forEachIndexed { c, h -> sh.put(9, c, h, bold = true, color = 0xFFFFFFFF, fill = INK, align = if (c == 0) null else TextAlign.RIGHT) }
        val items = lines(R.string.tpl_invoice_items)
        val values = listOf(12 to "150", 1 to "2400", 5 to "199", 6 to "80")
        val rule = BorderLine(color = 0xFFD9D9D9)
        for (i in 0 until 6) {
            val r = 10 + i
            val v = values.getOrNull(i)
            sh.put(r, 0, items.getOrElse(i) { "" }).borderBottom = rule
            sh.put(r, 1, v?.first?.toString() ?: "").borderBottom = rule
            sh.put(r, 2, v?.second ?: "", code = money).borderBottom = rule
            sh.put(r, 3, "=IF(B${r + 1}=\"\",\"\",B${r + 1}*C${r + 1})", code = money).borderBottom = rule
        }
        sh.put(17, 2, s(R.string.tpl_invoice_subtotal), align = TextAlign.RIGHT); sh.put(17, 3, "=SUM(D11:D16)", code = money)
        sh.put(18, 2, s(R.string.tpl_invoice_tax_rate), align = TextAlign.RIGHT); sh.put(18, 3, "0.06", code = "0%")
        sh.put(19, 2, s(R.string.tpl_invoice_tax), align = TextAlign.RIGHT); sh.put(19, 3, "=ROUND(D18*D19,2)", code = money)
        sh.put(20, 2, s(R.string.tpl_total), bold = true, size = 13f, align = TextAlign.RIGHT, fill = INK_TINT).borderTop = BorderLine(color = INK)
        sh.put(20, 3, "=D18+D20", bold = true, size = 13f, code = money, fill = INK_TINT).borderTop = BorderLine(color = INK)
        sh.rowHeights[20] = 1.4f
        sh.put(22, 0, s(R.string.tpl_invoice_thanks), italic = true, color = GRAY)
        listOf(2.8f, 0.8f, 1.3f, 1.4f).forEachIndexed { c, w -> sh.colWidths[c] = w }
        return sh
    }

    // ---- Presentations ----

    private fun deck(look: DeckDesigns.Look): Presentation {
        val p = Presentation(mutableListOf())
        p.master.background = look.background; p.master.titleColor = look.title; p.master.bodyColor = look.body
        p.master.titleBold = true
        p.themeIndex = DeckDesigns.indexOf(look)
        val cover = DeckDesigns.cover(look)
        val content = DeckDesigns.content(look)
        p.designs += cover; p.designs += content
        fun box(t: String, size: Float, bold: Boolean = false, align: TextAlign = TextAlign.LEFT, frame: BoxFrame? = null, list: SlideListStyle = SlideListStyle.NONE) =
            SlideBox(t, CharacterFormat(fontSizePt = size, bold = bold), align).also { it.frame = frame; it.listStyle = list; if (list != SlideListStyle.NONE) it.lineSpacing = 1.2f }
        fun slide(layout: SlideLayout, design: SlideDesign, title: SlideBox, body: SlideBox) =
            Slide(title = title, body = body, layout = layout, design = design.name).also { p.slides += it }

        slide(SlideLayout.TITLE, cover,
            box(s(R.string.tpl_deck_title), 44f, true, frame = DeckDesigns.COVER_TITLE.copyOf()),
            box(s(R.string.tpl_deck_subtitle), 20f, frame = DeckDesigns.COVER_BODY.copyOf()))
        slide(SlideLayout.TITLE_CONTENT, content, box(s(R.string.tpl_deck_agenda), 34f, true),
            box(s(R.string.tpl_deck_agenda_items), 24f, list = SlideListStyle.NUMBER))
        slide(SlideLayout.SECTION, cover,
            box(s(R.string.tpl_deck_section), 38f, true, frame = BoxFrame(0.08f, 0.34f, 0.80f, 0.20f)),
            box(s(R.string.tpl_deck_section_sub), 20f, frame = BoxFrame(0.08f, 0.56f, 0.80f, 0.12f)))
        slide(SlideLayout.TITLE_CONTENT, content, box(s(R.string.tpl_deck_points), 34f, true),
            box(s(R.string.tpl_deck_points_items), 24f, list = SlideListStyle.BULLET))
        // Three figures on cards under a title.
        val numbers = slide(SlideLayout.BLANK, content, box(s(R.string.tpl_deck_numbers), 34f, true), box("", 22f))
        val labels = lines(R.string.tpl_deck_numbers_labels)
        listOf("1,280", "+36%", "98%").forEachIndexed { i, n ->
            val card = SlideShape(ShapeType.ROUNDED, SlideBox(Paragraph(mutableListOf(
                TextRun(n + "\n", CharacterFormat(fontSizePt = 44f, bold = true, color = look.accent)),
                TextRun(labels.getOrElse(i) { "" }, CharacterFormat(fontSizePt = 18f, color = look.body))), ParagraphFormat(align = TextAlign.CENTER))),
                xPct = 0.08f + i * 0.29f, yPct = 0.34f, wPct = 0.26f, hPct = 0.42f, fill = look.soft, border = null)
            card.anchor = ShapeAnchor.CENTER
            numbers.shapes += card
        }
        slide(SlideLayout.TITLE, cover,
            box(s(R.string.tpl_deck_thanks), 48f, true, frame = BoxFrame(0.08f, 0.30f, 0.80f, 0.22f)),
            box(s(R.string.tpl_deck_thanks_sub), 20f, frame = BoxFrame(0.08f, 0.56f, 0.80f, 0.12f)))
        return p
    }

    private companion object {
        const val INK = 0xFF1F3864
        const val INK_TINT = 0xFFDEEAF6
        const val TEAL = 0xFF1D6A7A
        const val GRAY = 0xFF7F7F7F
        const val GREEN = 0xFF2E7D4F
        const val GREEN_DARK = 0xFF1E5631
        const val GREEN_TINT = 0xFFE2EFDA

    }
}
