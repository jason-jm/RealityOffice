package com.therealityteam.aeropages.ui

import androidx.annotation.StringRes
import com.therealityteam.aeropages.model.BoxFrame
import com.therealityteam.aeropages.model.DesignShape
import com.therealityteam.aeropages.model.MasterText
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideDesign
import com.therealityteam.aeropages.model.SlideShape

/**
 * The app's designed looks for decks: a background, text colours and an accent, with a cover design
 * (title and section slides) and a content design drawn with shapes — what the Business, Fresh and
 * Midnight templates are made with, and what Design offers beside its plain colour themes.
 */
internal object DeckDesigns {
    /** A deck's look: its background, text colours, accent, and what its cover and content pages draw. */
    class Look(
        val name: String, val background: Long, val title: Long, val body: Long, val accent: Long, val soft: Long,
        /** The cover (title and section) page's background, and its text colours. */
        val cover: Long, val coverTitle: Long, val coverBody: Long,
        val coverItems: (Look) -> List<SlideShape>,
        val contentItems: (Look) -> List<SlideShape>,
    )

    /** Where a cover's title and subtitle sit: left, a little above the middle, clear of the cover's drawing. */
    val COVER_TITLE = BoxFrame(0.08f, 0.28f, 0.80f, 0.24f)
    val COVER_BODY = BoxFrame(0.08f, 0.56f, 0.80f, 0.12f)

    private fun rect(x: Float, y: Float, w: Float, h: Float, fill: Long, type: ShapeType = ShapeType.RECT) =
        SlideShape(type, SlideBox(""), xPct = x, yPct = y, wPct = w, hPct = h, fill = fill, border = null)

    /** Deep blue: the cover's lower band, a short accent bar over each content page's title. */
    val BUSINESS = Look("Business", background = 0xFFFFFFFF, title = 0xFF1F3864, body = 0xFF404040, accent = 0xFF2E75B6, soft = 0xFFEAF1FB,
        cover = 0xFF1F3864, coverTitle = 0xFFFFFFFF, coverBody = 0xFFBDD7EE,
        coverItems = { listOf(rect(0f, 0.84f, 1f, 0.16f, 0xFF162B4D), rect(0.08f, 0.53f, 0.10f, 0.012f, 0xFF5B9BD5)) },
        contentItems = { listOf(rect(0.05625f, 0.055f, 0.06f, 0.012f, it.accent), rect(0f, 0.955f, 1f, 0.045f, 0xFF1F3864)) })

    /** Warm paper and green: soft circles on the cover, a green rule down the content pages' left edge. */
    val FRESH = Look("Fresh", background = 0xFFF7F5EF, title = 0xFF2E5E4E, body = 0xFF4A4A4A, accent = 0xFF3F8F6B, soft = 0xFFE4EEDF,
        cover = 0xFFF7F5EF, coverTitle = 0xFF2E5E4E, coverBody = 0xFF5F6F66,
        coverItems = { listOf(rect(0.64f, -0.22f, 0.50f, 0.889f, 0xFFDCE9D5, ShapeType.ELLIPSE), rect(0.80f, 0.52f, 0.26f, 0.462f, 0xFFB9D7A8, ShapeType.ELLIPSE),
            rect(0.08f, 0.53f, 0.08f, 0.012f, 0xFF3F8F6B)) },
        contentItems = { listOf(rect(0f, 0f, 0.012f, 1f, it.accent), rect(0.90f, 0.80f, 0.14f, 0.249f, 0xFFE4EEDF, ShapeType.ELLIPSE)) })

    /** Night blue with a cyan accent: a thin cyan line under the cover's title, a cyan bar by the content pages' titles. */
    val DARK = Look("Midnight", background = 0xFF161A22, title = 0xFFFFFFFF, body = 0xFFC9CED8, accent = 0xFF3FC1C9, soft = 0xFF222A36,
        cover = 0xFF10131A, coverTitle = 0xFFFFFFFF, coverBody = 0xFF9AA4B5,
        coverItems = { listOf(rect(0.08f, 0.53f, 0.12f, 0.008f, 0xFF3FC1C9), rect(0.70f, 0f, 0.30f, 1f, 0xFF161C27), rect(0.70f, 0f, 0.004f, 1f, 0xFF3FC1C9)) },
        contentItems = { listOf(rect(0.035f, 0.075f, 0.006f, 0.12f, it.accent)) })

    val ALL get() = listOf(BUSINESS, FRESH, DARK)

    /** The template each look is shown with in the gallery, and its name. */
    fun templateOf(look: Look): DeskTemplate = when (look.name) { "Fresh" -> DeskTemplate.DECK_FRESH; "Midnight" -> DeskTemplate.DECK_DARK; else -> DeskTemplate.DECK_BUSINESS }
    @StringRes fun labelOf(look: Look): Int = templateOf(look).label

    /** Design's theme number for [look] (the plain colour themes are 0 …). */
    fun indexOf(look: Look): Int = 100 + ALL.indexOfFirst { it.name == look.name }

    /** The names of the designs these looks put on slides: a plain theme takes them off again. */
    val NAMES: Set<String> get() = ALL.flatMap { listOf(it.name, it.name + " Title") }.toSet()

    fun cover(look: Look) = SlideDesign(look.name + " Title", background = look.cover, items = look.coverItems(look).mapTo(mutableListOf()) { DesignShape(it) },
        titleText = MasterText(null, null, look.coverTitle, true), bodyText = MasterText(null, null, look.coverBody, null))

    fun content(look: Look) = SlideDesign(look.name, background = look.background, items = look.contentItems(look).mapTo(mutableListOf()) { DesignShape(it) })
}
