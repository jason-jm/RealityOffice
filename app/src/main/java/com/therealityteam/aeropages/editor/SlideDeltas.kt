package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide

/** Undo deltas for [SlideEditor]; see [Delta]. All are positional (slide indices). */

/** One slide, by index. */
internal class SlideDelta(private val index: Int, private val stored: Slide) : Delta<Presentation> {
    override fun apply(model: Presentation): Delta<Presentation> {
        val current = model.slides[index].copyOf()
        model.slides[index] = stored.copyOf()
        return SlideDelta(index, current)
    }
}

/** A run of slides: [stored] replaces the [count] slides currently at [start] (insertion = count 1, empty stored). */
internal class SlidesRangeDelta(private val start: Int, private val count: Int, private val stored: List<Slide>) : Delta<Presentation> {
    override fun apply(model: Presentation): Delta<Presentation> {
        val range = model.slides.subList(start, start + count)
        val current = range.map { it.copyOf() }
        range.clear()
        model.slides.addAll(start, stored.map { it.copyOf() })
        return SlidesRangeDelta(start, stored.size, current)
    }
}

/** Reorder: moves the slide now at [to] back to [from]. */
internal class SlideMoveDelta(private val from: Int, private val to: Int) : Delta<Presentation> {
    override fun apply(model: Presentation): Delta<Presentation> {
        val s = model.slides.removeAt(to)
        model.slides.add(from, s)
        return SlideMoveDelta(to, from)
    }
}

/** Presentation-level settings: slide size, theme, show options, footer, slide master. */
internal class DeckMetaDelta(
    private val slideSize: com.therealityteam.aeropages.model.SlideSize, private val themeIndex: Int, private val loopShow: Boolean,
    private val useTimings: Boolean, private val showNarration: Boolean,
    private val footer: String, private val showSlideNumber: Boolean,
    private val master: com.therealityteam.aeropages.model.SlideMaster,
    private val date: com.therealityteam.aeropages.model.SlideDate?, private val hideFooterOnTitle: Boolean,
    /** The designs (master pages) the slides can be drawn on: a designed theme adds and removes them. */
    private val designs: List<com.therealityteam.aeropages.model.SlideDesign>,
    /** Set Up Slide Show and the custom shows. */
    private val show: com.therealityteam.aeropages.model.ShowSetup = com.therealityteam.aeropages.model.ShowSetup(),
    private val customShows: List<com.therealityteam.aeropages.model.CustomShow> = emptyList(),
    private val firstSlideNumber: Int = 1,
) : Delta<Presentation> {
    override fun apply(model: Presentation): Delta<Presentation> {
        val current = capture(model)
        model.slideSize = slideSize; model.themeIndex = themeIndex; model.loopShow = loopShow
        model.useTimings = useTimings; model.showNarration = showNarration
        model.footer = footer; model.showSlideNumber = showSlideNumber
        model.date = date; model.hideFooterOnTitle = hideFooterOnTitle
        model.master.assignFrom(master)
        model.designs.clear(); model.designs.addAll(designs)
        model.show = show; model.customShows = customShows; model.firstSlideNumber = firstSlideNumber
        return current
    }

    companion object {
        fun capture(p: Presentation) = DeckMetaDelta(p.slideSize, p.themeIndex, p.loopShow, p.useTimings, p.showNarration, p.footer, p.showSlideNumber, p.master.copyOf(),
            p.date, p.hideFooterOnTitle, p.designs.toList(), p.show, p.customShows, p.firstSlideNumber)
    }
}
