package com.therealityteam.aeropages.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.therealityteam.aeropages.model.SlideChart
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.SlideTable

/**
 * The object last copied in the app — a picture, a drawn shape or text box, a chart, a slide's
 * table — shared by every module and window of the process: a chart copied on a sheet's page pastes
 * on a slide, a slide's picture on a sheet or a page. Each item is a copy, with the size it was
 * shown at in points (and, from a slide, where it sat, so a paste on a slide lands beside it).
 *
 * Whether an object or some text was copied last is the system clipboard's to say — see
 * [com.therealityteam.aeropages.ui.TextClipboard.copyObject].
 */
object ObjectClipboard {

    sealed class Item(val widthPt: Float, val heightPt: Float) {
        /** Where it sat on a slide (left, top as fractions of the slide), when it came from one. */
        var slidePlace: Pair<Float, Float>? = null
    }

    /** A picture: its bytes, its crop (fractions off each side), its shown size. */
    class Picture(val data: ByteArray, val mime: String, widthPt: Float, heightPt: Float,
                  val cropL: Float = 0f, val cropT: Float = 0f, val cropR: Float = 0f, val cropB: Float = 0f) : Item(widthPt, heightPt)

    /** A drawn shape, a line or a text box, with its text. */
    class Shape(val shape: SlideShape, widthPt: Float, heightPt: Float) : Item(widthPt, heightPt)

    /** A chart with its data. */
    class Chart(val chart: SlideChart, widthPt: Float, heightPt: Float) : Item(widthPt, heightPt)

    /** A slide's table (on a sheet it pastes as its cells' text). */
    class Table(val table: SlideTable, widthPt: Float, heightPt: Float) : Item(widthPt, heightPt)

    /** The object copied last, or null — state, so every window's Paste button follows it. */
    var item by mutableStateOf<Item?>(null)
        private set

    /** Put a copy of an object on the clipboard. */
    fun put(item: Item) { this.item = item }

    /** Forget it (tests). */
    fun clear() { item = null }

    /**
     * The words [item] carries, for the text copied beside it (what another app pastes): a shape's
     * text, a chart's title, a table's cells as tab-separated lines; "" for a picture.
     */
    fun textOf(item: Item): String = when (item) {
        is Picture -> ""
        is Shape -> item.shape.box.text
        is Chart -> item.chart.title
        is Table -> item.table.cells.joinToString("\n") { row -> row.joinToString("\t") { it.text } }
    }
}
