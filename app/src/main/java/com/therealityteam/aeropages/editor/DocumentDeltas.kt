package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.Block
import com.therealityteam.aeropages.model.Comment
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Endnote
import com.therealityteam.aeropages.model.Footnote
import com.therealityteam.aeropages.model.PageSetup
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.Source

/** Undo deltas for [DocumentEditor]; see [Delta]. Produced by the editor's block diff. */

/** A run of top-level blocks: [stored] replaces the [count] blocks currently at [start]. */
internal class BlocksDelta(val start: Int, val count: Int, val stored: List<Block>) : Delta<Document> {
    override fun apply(model: Document): Delta<Document> {
        val range = model.blocks.subList(start, start + count)
        val current = range.map { it.copyOf() }
        range.clear()
        model.blocks.addAll(start, stored.map { it.copyOf() })
        return BlocksDelta(start, stored.size, current)
    }
}

/** Everything of a document except its blocks: page setup, header/footer, comments, footnotes, endnotes, sources, page décor. */
internal class DocumentMetaDelta(
    private val page: PageSetup,
    private val header: Paragraph?,
    private val footer: Paragraph?,
    private val comments: List<Comment>,
    private val footnotes: List<Footnote>,
    private val endnotes: List<Endnote>,
    private val sources: List<Source>,
    private val pageColor: Long?,
    private val watermark: String?,
    private val pageBorders: com.therealityteam.aeropages.model.PageBorders?,
    private val styles: com.therealityteam.aeropages.model.StyleSheet,
    private val evenHeader: Paragraph?, private val evenFooter: Paragraph?, private val firstHeader: Paragraph?, private val firstFooter: Paragraph?,
    private val differentOddEven: Boolean, private val differentFirstPage: Boolean,
    private val defaultTabPt: Float,
    private val hfPictures: Map<com.therealityteam.aeropages.model.HfSlot, List<com.therealityteam.aeropages.model.ImageBlock>> = emptyMap(),
    private val lists: Map<Int, com.therealityteam.aeropages.model.ListDef> = emptyMap(),
    private val coverPage: Boolean = false,
) : Delta<Document> {
    override fun apply(model: Document): Delta<Document> {
        val current = capture(model)
        install(model)
        return current
    }

    /** Copy the stored state into [model]. */
    fun install(model: Document) {
        model.page.assignFrom(page)
        model.header = header?.copyOf(); model.footer = footer?.copyOf()
        model.comments.clear(); model.comments.addAll(comments.map { it.copyOf() })
        model.footnotes.clear(); model.footnotes.addAll(footnotes.map { it.copyOf() })
        model.endnotes.clear(); model.endnotes.addAll(endnotes.map { it.copyOf() })
        model.sources.clear(); model.sources.addAll(sources.map { it.copyOf() })
        model.pageColor = pageColor; model.watermark = watermark; model.pageBorders = pageBorders
        model.styles.assignFrom(styles)
        model.evenHeader = evenHeader?.copyOf(); model.evenFooter = evenFooter?.copyOf()
        model.firstHeader = firstHeader?.copyOf(); model.firstFooter = firstFooter?.copyOf()
        model.differentOddEven = differentOddEven; model.differentFirstPage = differentFirstPage
        model.defaultTabPt = defaultTabPt
        model.hfPictures.clear()
        hfPictures.forEach { (k, pics) -> model.hfPictures[k] = pics.map { it.copyOf() as com.therealityteam.aeropages.model.ImageBlock }.toMutableList() }
        model.lists.clear(); model.lists.putAll(lists)
        model.coverPage = coverPage
    }

    companion object {
        fun capture(d: Document) = DocumentMetaDelta(
            d.page.copy(), d.header?.copyOf(), d.footer?.copyOf(),
            d.comments.map { it.copyOf() }, d.footnotes.map { it.copyOf() }, d.endnotes.map { it.copyOf() }, d.sources.map { it.copyOf() },
            d.pageColor, d.watermark, d.pageBorders, d.styles.copyOf(),
            d.evenHeader?.copyOf(), d.evenFooter?.copyOf(), d.firstHeader?.copyOf(), d.firstFooter?.copyOf(),
            d.differentOddEven, d.differentFirstPage, d.defaultTabPt,
            d.hfPictures.mapValues { (_, pics) -> pics.map { it.copyOf() as com.therealityteam.aeropages.model.ImageBlock } },
            d.lists.toMap(),
            d.coverPage,
        )
    }
}
