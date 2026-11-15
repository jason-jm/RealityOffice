package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.AnimTarget
import com.therealityteam.aeropages.model.BibliographyBlock
import com.therealityteam.aeropages.model.Block
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.Croppable
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideChart
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.SlideTable
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TableCell
import com.therealityteam.aeropages.model.TableRow
import com.therealityteam.aeropages.model.TocBlock
import com.therealityteam.aeropages.model.Workbook

/**
 * Structural ("same content") comparisons of model objects.
 *
 * The model classes deliberately keep identity equality — the editors and the UI remove
 * elements by reference — so the undo machinery compares content here: the DocumentEditor's
 * block diff, and the test-only [verifyDelta] check of the scoped Sheets/Slides deltas.
 * Paragraph ids take part in the comparison (comments anchor to them).
 */
internal object ModelDiff {

    // --- Pages -----------------------------------------------------------------

    fun sameParagraph(a: Paragraph?, b: Paragraph?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a.id == b.id && a.format == b.format && a.runs == b.runs && a.bookmarks == b.bookmarks
    }

    fun sameParagraphs(a: List<Paragraph>, b: List<Paragraph>): Boolean = sameList(a, b) { x, y -> sameParagraph(x, y) }

    fun sameBlock(a: Block, b: Block): Boolean = when (a) {
        is ParagraphBlock -> b is ParagraphBlock && sameParagraph(a.paragraph, b.paragraph)
        is TableBlock -> b is TableBlock && sameTable(a.table, b.table)
        is ImageBlock -> b is ImageBlock && a.mime == b.mime && a.widthPt == b.widthPt && a.heightPt == b.heightPt && sameCrop(a, b) &&
            a.shadow == b.shadow && a.floating == b.floating && a.flipH == b.flipH && a.flipV == b.flipV &&
            a.altText == b.altText && a.decorative == b.decorative &&
            (a.data === b.data || a.data.contentEquals(b.data))
        is TocBlock -> b is TocBlock && a.caption == b.caption
        is BibliographyBlock -> b is BibliographyBlock
        is com.therealityteam.aeropages.model.IndexBlock -> b is com.therealityteam.aeropages.model.IndexBlock && a.rightAlign == b.rightAlign && a.headings == b.headings
        is com.therealityteam.aeropages.model.EquationBlock -> b is com.therealityteam.aeropages.model.EquationBlock &&
            a.math == b.math && a.align == b.align && a.sizePt == b.sizePt
        is com.therealityteam.aeropages.model.ChartBlock -> b is com.therealityteam.aeropages.model.ChartBlock &&
            a.widthPt == b.widthPt && a.heightPt == b.heightPt && sameChart(a.chart, b.chart)
        is com.therealityteam.aeropages.model.TextBoxBlock -> b is com.therealityteam.aeropages.model.TextBoxBlock &&
            a.widthPt == b.widthPt && a.heightPt == b.heightPt && a.floating == b.floating && sameParagraphs(a.paragraphs, b.paragraphs) &&
            (a.shape == null && b.shape == null || a.shape != null && b.shape != null && sameShape(a.shape!!, b.shape!!))
    }

    fun sameTable(a: Table, b: Table): Boolean =
        a.borders == b.borders && a.gridLine == b.gridLine && a.columnWidths == b.columnWidths && a.floating == b.floating && a.widthPt == b.widthPt &&
            a.align == b.align && a.indentPt == b.indentPt &&
            sameList(a.rows, b.rows) { x, y -> sameRow(x, y) }

    private fun sameRow(a: TableRow, b: TableRow): Boolean = a.header == b.header && a.heightPt == b.heightPt && a.exactHeight == b.exactHeight &&
        sameList(a.cells, b.cells) { x, y -> sameTableCell(x, y) }

    private fun sameTableCell(a: TableCell, b: TableCell): Boolean =
        a.shade == b.shade && a.gridSpan == b.gridSpan && a.rowSpan == b.rowSpan && a.vMerged == b.vMerged && a.borders == b.borders &&
            sameParagraphs(a.content, b.content) && sameList(a.images, b.images) { x, y -> sameBlock(x, y) }

    /** Everything of a document except its blocks (page setup, header/footer, notes, comments, sources…). */
    fun sameDocumentMeta(a: Document, b: Document): Boolean =
        a.page == b.page && sameParagraph(a.header, b.header) && sameParagraph(a.footer, b.footer) &&
            a.hfPictures.keys == b.hfPictures.keys && a.hfPictures.all { (k, pics) -> sameList(pics, b.hfPictures[k].orEmpty()) { x, y -> sameBlock(x, y) } } &&
            sameParagraph(a.evenHeader, b.evenHeader) && sameParagraph(a.evenFooter, b.evenFooter) &&
            sameParagraph(a.firstHeader, b.firstHeader) && sameParagraph(a.firstFooter, b.firstFooter) &&
            a.differentOddEven == b.differentOddEven && a.differentFirstPage == b.differentFirstPage && a.defaultTabPt == b.defaultTabPt && a.lists == b.lists &&
            a.pageColor == b.pageColor && a.watermark == b.watermark && a.pageBorders == b.pageBorders && a.coverPage == b.coverPage &&
            a.styles.sameAs(b.styles) &&
            sameList(a.comments, b.comments) { x, y -> x.author == y.author && x.text == y.text && x.targetId == y.targetId && x.id == y.id && x.parentId == y.parentId && x.resolved == y.resolved && x.dateMs == y.dateMs } &&
            sameList(a.footnotes, b.footnotes) { x, y -> x.text == y.text } &&
            sameList(a.endnotes, b.endnotes) { x, y -> x.text == y.text } &&
            sameList(a.sources, b.sources) { x, y -> x.author == y.author && x.title == y.title && x.year == y.year }

    fun sameDocument(a: Document, b: Document): Boolean =
        sameDocumentMeta(a, b) && sameList(a.blocks, b.blocks) { x, y -> sameBlock(x, y) }

    // --- Sheets ----------------------------------------------------------------

    fun sameCell(a: Cell, b: Cell): Boolean =
        a.text == b.text && a.format == b.format && a.align == b.align && a.numberFormat == b.numberFormat &&
            a.borderTop == b.borderTop && a.borderBottom == b.borderBottom && a.borderLeft == b.borderLeft && a.borderRight == b.borderRight &&
            a.wrap == b.wrap && a.vAlign == b.vAlign && a.rotation == b.rotation && a.stacked == b.stacked && a.indent == b.indent && a.spread == b.spread && a.shrink == b.shrink && a.comment == b.comment && a.commentAuthor == b.commentAuthor && a.commentDate == b.commentDate && a.commentShown == b.commentShown && a.decimals == b.decimals && a.link == b.link && a.unlocked == b.unlocked &&
            a.runs == b.runs && a.formatCode == b.formatCode

    fun sameCells(a: Map<Long, Cell>, b: Map<Long, Cell>): Boolean =
        a.size == b.size && a.all { (k, v) -> b[k]?.let { sameCell(v, it) } ?: false }

    /** Everything of a sheet except its cells. */
    fun sameSheetMeta(a: Sheet, b: Sheet): Boolean =
        a.name == b.name && a.rowCount == b.rowCount && a.colCount == b.colCount &&
            a.freezeRows == b.freezeRows && a.freezeCols == b.freezeCols &&
            a.landscape == b.landscape && a.paper == b.paper && a.print == b.print && a.showHeadings == b.showHeadings && a.showGridlines == b.showGridlines && a.hiddenRows == b.hiddenRows &&
            a.protected == b.protected && a.protection == b.protection && a.state == b.state && a.tabColor == b.tabColor &&
            sameList(a.sparklines, b.sparklines) { x, y ->
                x.row == y.row && x.col == y.col && x.r1 == y.r1 && x.c1 == y.c1 && x.r2 == y.r2 && x.c2 == y.c2 &&
                    x.type == y.type && x.color == y.color && x.markers == y.markers
            } &&
            (a.printArea?.let { x -> b.printArea?.let { y -> x.r1 == y.r1 && x.c1 == y.c1 && x.r2 == y.r2 && x.c2 == y.c2 } ?: false } ?: (b.printArea == null)) &&
            a.colWidths == b.colWidths && a.rowHeights == b.rowHeights &&
            sameList(a.merges, b.merges) { x, y -> x.r1 == y.r1 && x.c1 == y.c1 && x.r2 == y.r2 && x.c2 == y.c2 } &&
            a.condFormats == b.condFormats &&
            a.validations == b.validations &&
            sameList(a.charts, b.charts) { x, y -> sameChart(x, y) } &&
            sameList(a.scenarios, b.scenarios) { x, y -> x.name == y.name && x.values == y.values } &&
            sameList(a.rowGroups, b.rowGroups) { x, y -> x.r1 == y.r1 && x.r2 == y.r2 && x.collapsed == y.collapsed } &&
            a.hiddenCols == b.hiddenCols && a.manualHiddenRows == b.manualHiddenRows && a.autoFilter == b.autoFilter &&
            sameList(a.colGroups, b.colGroups) { x, y -> x.r1 == y.r1 && x.r2 == y.r2 && x.collapsed == y.collapsed } &&
            sameList(a.images, b.images) { x, y -> x.sameAs(y) } &&
            sameList(a.tables, b.tables) { x, y -> x.sameAs(y) } &&
            sameList(a.shapes, b.shapes) { x, y ->
                x.row == y.row && x.col == y.col && x.dxPt == y.dxPt && x.dyPt == y.dyPt && x.widthPt == y.widthPt &&
                    x.heightPt == y.heightPt && x.name == y.name && sameShape(x.shape, y.shape)
            }

    fun sameSheet(a: Sheet, b: Sheet): Boolean = sameSheetMeta(a, b) && sameCells(a.cells, b.cells)

    fun sameWorkbook(a: Workbook, b: Workbook): Boolean =
        a.names == b.names && a.structureLocked == b.structureLocked && a.structurePasswords == b.structurePasswords &&
            sameList(a.sheets, b.sheets) { x, y -> sameSheet(x, y) }

    // --- Slides ----------------------------------------------------------------

    fun sameBox(a: SlideBox, b: SlideBox): Boolean =
        a.listStyle == b.listStyle && a.lineSpacing == b.lineSpacing && a.fill == b.fill && a.borders == b.borders && a.cellAnchor == b.cellAnchor && a.colSpan == b.colSpan && a.rowSpan == b.rowSpan && sameAnims(a, b) &&
            (a.frame?.sameAs(b.frame) ?: (b.frame == null)) &&
            a.paragraph.format == b.paragraph.format && a.paragraph.runs == b.paragraph.runs

    fun sameShape(a: SlideShape, b: SlideShape): Boolean =
        a.type == b.type && sameBox(a.box, b.box) && a.xPct == b.xPct && a.yPct == b.yPct && a.wPct == b.wPct &&
            a.hPct == b.hPct && a.fill == b.fill && a.border == b.border && a.borderWidthPt == b.borderWidthPt && a.anchor == b.anchor &&
            a.rotationDeg == b.rotationDeg && a.flipH == b.flipH && a.flipV == b.flipV &&
            a.fillGradient == b.fillGradient && a.gradientAngle == b.gradientAngle && a.groupId == b.groupId &&
            a.dash == b.dash && a.beginArrow == b.beginArrow && a.endArrow == b.endArrow && a.path == b.path &&
            a.gradientKind == b.gradientKind && a.gradientCx == b.gradientCx && a.gradientCy == b.gradientCy && a.shadow == b.shadow &&
            a.preset == b.preset && a.presetAdjust == b.presetAdjust && a.action == b.action && a.hoverAction == b.hoverAction && a.noWrap == b.noWrap && sameObject(a, b)

    fun sameSlideTable(a: SlideTable, b: SlideTable): Boolean =
        a.styleIndex == b.styleIndex && a.headerRow == b.headerRow && a.bandedRows == b.bandedRows &&
            a.firstColumn == b.firstColumn && a.lastColumn == b.lastColumn && a.totalRow == b.totalRow &&
            a.bandedColumns == b.bandedColumns && a.xPct == b.xPct && a.yPct == b.yPct && a.wPct == b.wPct &&
            a.hPct == b.hPct && a.colWeights == b.colWeights && a.rowWeights == b.rowWeights && sameAnims(a, b) && sameObject(a, b) &&
            sameList(a.cells, b.cells) { r1, r2 -> sameList(r1, r2) { x, y -> sameBox(x, y) } }

    fun sameChart(a: SlideChart, b: SlideChart): Boolean =
        a.type == b.type && a.title == b.title && a.colorSet == b.colorSet &&
            a.showLegend == b.showLegend && a.showGrid == b.showGrid && a.showValues == b.showValues &&
            a.legendPos == b.legendPos && a.catAxisTitle == b.catAxisTitle && a.valAxisTitle == b.valAxisTitle &&
            a.valMin == b.valMin && a.valMax == b.valMax && a.valMajor == b.valMajor && a.valFormat == b.valFormat &&
            a.xPct == b.xPct && a.yPct == b.yPct && a.wPct == b.wPct && a.hPct == b.hPct &&
            sameAnims(a, b) && sameObject(a, b) &&
            a.sourceRef == b.sourceRef && a.sourceHeader == b.sourceHeader && a.sourceCats == b.sourceCats &&
            // A range-bound chart's categories/series are a cache of the cells, not state of their own.
            (a.sourceRef != null || (a.categories == b.categories && sameList(a.series, b.series) { x, y -> x.name == y.name && x.values == y.values }))

    fun sameImage(a: SlideImage, b: SlideImage): Boolean =
        a.mime == b.mime && a.widthPt == b.widthPt && a.heightPt == b.heightPt &&
            a.xPct == b.xPct && a.yPct == b.yPct && a.wPct == b.wPct && a.hPct == b.hPct &&
            sameAnims(a, b) && sameCrop(a, b) && a.shadow == b.shadow && a.rotationDeg == b.rotationDeg && a.flipH == b.flipH && a.flipV == b.flipV &&
            a.action == b.action && a.hoverAction == b.hoverAction && a.altText == b.altText && a.decorative == b.decorative && sameObject(a, b) && (a.data === b.data || a.data.contentEquals(b.data))

    private fun sameObject(a: com.therealityteam.aeropages.model.SlideObject, b: com.therealityteam.aeropages.model.SlideObject): Boolean =
        a.objectName == b.objectName && a.hidden == b.hidden

    private fun sameAnims(a: AnimTarget, b: AnimTarget): Boolean = sameList(a.anims, b.anims) { x, y -> x.sameAs(y) }

    private fun sameCrop(a: Croppable, b: Croppable): Boolean =
        a.cropL == b.cropL && a.cropT == b.cropT && a.cropR == b.cropR && a.cropB == b.cropB

    fun sameSlide(a: Slide, b: Slide): Boolean =
        a.id == b.id && sameBox(a.title, b.title) && sameBox(a.body, b.body) && a.background == b.background && a.backgroundShade == b.backgroundShade && a.layout == b.layout &&
            a.notes == b.notes && a.transition == b.transition && a.transitionSpeed == b.transitionSpeed && a.transitionDir == b.transitionDir && a.sectionName == b.sectionName && a.hidden == b.hidden &&
            a.advanceMs == b.advanceMs && a.comments == b.comments && a.design == b.design && a.designObjects == b.designObjects &&
            sameList(a.images, b.images) { x, y -> sameImage(x, y) } &&
            sameList(a.shapes, b.shapes) { x, y -> sameShape(x, y) } &&
            sameList(a.tables, b.tables) { x, y -> sameSlideTable(x, y) } &&
            sameList(a.charts, b.charts) { x, y -> sameChart(x, y) }

    fun samePresentation(a: Presentation, b: Presentation): Boolean =
        a.slideSize == b.slideSize && a.themeIndex == b.themeIndex && a.loopShow == b.loopShow &&
            a.useTimings == b.useTimings && a.showNarration == b.showNarration && a.footer == b.footer &&
            a.showSlideNumber == b.showSlideNumber && a.date == b.date && a.hideFooterOnTitle == b.hideFooterOnTitle &&
            a.show == b.show && a.customShows == b.customShows && a.firstSlideNumber == b.firstSlideNumber &&
            a.master.sameAs(b.master) && sameList(a.designs, b.designs) { x, y -> x === y } && sameList(a.slides, b.slides) { x, y -> sameSlide(x, y) }

    private inline fun <T> sameList(a: List<T>, b: List<T>, same: (T, T) -> Boolean): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (!same(a[i], b[i])) return false
        return true
    }
}
