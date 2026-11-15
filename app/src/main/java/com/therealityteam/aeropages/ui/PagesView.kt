package com.therealityteam.aeropages.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * How Pages shows the document (Word's View tab): the zoom, pages side by side ([multiPage]) and the ruler — kept per module
 * ([rememberPagesView]) — and the canvas's size, which the zoom presets fit the page to: One Page (a whole page in the window),
 * Multiple Pages (two side by side, more as one zooms out) and Page Width (the page across the window).
 */
@Stable
class PagesView internal constructor(zoomState: MutableFloatState, multiState: MutableState<Boolean>, rulerState: MutableState<Boolean>) {
    var zoom by zoomState
        private set
    var multiPage by multiState
        private set
    var ruler by rulerState

    /** The canvas the pages are shown in, in dp (the ruler's strip not counted): set by the canvas as it is laid out. */
    var canvasWidthDp by mutableFloatStateOf(0f)
    var canvasHeightDp by mutableFloatStateOf(0f)

    /** A zoom as picked (100%, the menu's levels): one page to a row again. */
    fun zoomTo(z: Float) { zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM); multiPage = false }

    /** The status bar's zoom slider: any level, the pages side by side staying so. */
    fun slideTo(z: Float) { zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM) }

    /** Zoom out or in a tenth — the pages side by side stay so (more fit across as they shrink). */
    fun step(by: Float) { zoom = ((Math.round(zoom * 10f) + Math.round(by * 10f)) / 10f).coerceIn(MIN_ZOOM, MAX_ZOOM) }

    /** One Page: the zoom that shows a whole [widthPt] × [heightPt] page in the window. */
    fun onePage(widthPt: Float, heightPt: Float) { fit(widthPt, heightPt, 1)?.let { zoomTo(it) } }

    /** Multiple Pages: two pages side by side, whole. */
    fun multiplePages(widthPt: Float, heightPt: Float) { fit(widthPt, heightPt, 2)?.let { zoom = it; multiPage = true } }

    /** Page Width: the page as wide as the window (less a little room at the sides). */
    fun pageWidth(widthPt: Float) {
        if (canvasWidthDp <= 0f || widthPt <= 0f) return
        zoomTo((canvasWidthDp - 2 * SIDE_DP) / widthPt)
    }

    /** The zoom at which [across] pages of [widthPt] × [heightPt], with the space round them, fill the canvas; null before it is laid out. */
    private fun fit(widthPt: Float, heightPt: Float, across: Int): Float? {
        if (canvasWidthDp <= 0f || canvasHeightDp <= 0f || widthPt <= 0f || heightPt <= 0f) return null
        // Each page card has PAGE_GAP_DP (zoomed) above and below it, and the same between pages in a row.
        val byHeight = canvasHeightDp / (heightPt + 2 * PAGE_GAP_DP)
        val byWidth = (canvasWidthDp - 2 * SIDE_DP) / (across * widthPt + (across - 1) * PAGE_GAP_DP)
        return minOf(byHeight, byWidth)
    }

    /** How the zoom reads: "100%". */
    val percent: Int get() = Math.round(zoom * 100f)

    companion object {
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 4f
        /** The space at the window's sides that the zoom presets leave free. */
        const val SIDE_DP = 24f
        /** The space round each page (zoomed with it): above and below, and between pages side by side. */
        const val PAGE_GAP_DP = 16f
        /** The zoom menu's levels, in percent. */
        val LEVELS = listOf(50, 75, 100, 125, 150, 200, 300)
    }
}

/** Pages' view, remembered per module across launches. */
@Composable
internal fun rememberPagesView(): PagesView {
    val zoom = rememberLayoutFloat("pages.zoom", 1f)
    val multi = rememberLayoutFlag("pages.multiPage")
    val ruler = rememberLayoutFlag("pages.ruler", true)
    return remember(zoom, multi, ruler) { PagesView(zoom, multi, ruler) }
}
