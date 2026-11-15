package com.therealityteam.aeropages.ui

import androidx.core.graphics.createBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import com.therealityteam.aeropages.editor.ModelDiff
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.pdf.SlidesPdfExporter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideDesign
import com.therealityteam.aeropages.model.SlideMaster
import com.therealityteam.aeropages.model.SlideSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap

/**
 * Slide thumbnails as the PDF draws the slide — shapes, pictures, tables, charts, the lot — each
 * rendered into a bitmap off the main thread from a copy of its slide, and again only when that
 * slide, or what the deck lends it (its size, master, footer, number), has changed. Until a new
 * one is ready the old one stays, so editing never flashes the pane blank.
 */
internal class SlideThumbs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * What a slide's picture depends on besides the slide itself: the deck's size, master, header
     * and footer (the date as it shows today), its number and its design (the deck's, never edited, so by identity).
     */
    private class DeckLook(val size: SlideSize, val master: SlideMaster, val footer: String, val showNumber: Boolean, val date: String?, val hideOnTitle: Boolean,
                           val number: Int, val design: SlideDesign?) {
        fun same(o: DeckLook): Boolean = size == o.size && master.sameAs(o.master) && footer == o.footer && showNumber == o.showNumber && date == o.date &&
            hideOnTitle == o.hideOnTitle && number == o.number && design === o.design

        companion object {
            fun of(p: Presentation, number: Int, design: SlideDesign?) =
                DeckLook(p.slideSize, p.master, p.footer, p.showSlideNumber, p.date?.text(), p.hideFooterOnTitle, number + p.firstSlideNumber - 1, design)
        }
    }

    private class Entry(val slide: Slide, val look: DeckLook, val widthPx: Int, val bitmap: ImageBitmap)

    /**
     * Each slide's pictures, one per width it is shown at (the newest last, [WIDTHS] at most): a slide
     * shown at two sizes at once — the navigator's and the presenter console's — keeps both, rather than
     * each size replacing the other's picture without end.
     */
    private val entries = IdentityHashMap<Slide, ArrayList<Entry>>()
    private val pending = IdentityHashMap<Slide, MutableSet<Int>>()

    /** Bumped when a new picture lands, so the thumbnails showing it recompose. */
    var version by mutableIntStateOf(0)
        private set

    /**
     * The picture of [slide] (number [number] of [p]) [widthPx] wide: the latest one ready at that
     * width — else, until it is, the nearest other width's (null before the first) — a fresh one started
     * when it is out of date.
     */
    fun bitmapFor(p: Presentation, slide: Slide, number: Int, widthPx: Int): ImageBitmap? {
        val look = DeckLook.of(p, number, p.designOf(slide))
        val list = entries[slide]
        val e = list?.lastOrNull { it.widthPx == widthPx }
        val fresh = e != null && e.look.same(look) && ModelDiff.sameSlide(e.slide, slide)
        if (!fresh && pending[slide]?.contains(widthPx) != true) render(p, slide, number, widthPx)
        return (e ?: list?.minByOrNull { kotlin.math.abs(it.widthPx - widthPx) })?.bitmap
    }

    private fun render(p: Presentation, slide: Slide, number: Int, widthPx: Int) {
        // Copies, taken here on the main thread: the editor goes on changing the live ones.
        val copy = slide.copyOf()
        // Its design with it (designs are not edited: shared, not copied), so it shows over the template as it prints.
        val design = p.designOf(slide)
        val deck = Presentation(mutableListOf(copy), slideSize = p.slideSize, footer = p.footer, showSlideNumber = p.showSlideNumber, master = p.master.copyOf(),
            designs = listOfNotNull(design).toMutableList(), date = p.date, hideFooterOnTitle = p.hideFooterOnTitle, firstSlideNumber = p.firstSlideNumber)
        val look = DeckLook.of(deck, number, design)
        val heightPx = (widthPx / deck.slideSize.aspect).toInt().coerceAtLeast(1)
        pending.getOrPut(slide) { HashSet() } += widthPx
        val job: Job = scope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                runCatching {
                    createBitmap(widthPx, heightPx).also { SlidesPdfExporter.renderSlide(it, deck, copy, number) }.asImageBitmap()
                }.getOrNull()
            }
            if (bitmap != null) {
                val list = entries.getOrPut(slide) { ArrayList() }
                list.removeAll { it.widthPx == widthPx }
                list += Entry(copy, look, widthPx, bitmap)
                while (list.size > WIDTHS) list.removeAt(0)
                version++
            }
        }
        job.invokeOnCompletion { pending[slide]?.let { it -= widthPx; if (it.isEmpty()) pending.remove(slide) } }
    }

    /** Forget the pictures of slides no longer in [p] (deleted, or replaced by an undo). */
    fun prune(p: Presentation) {
        val live = java.util.Collections.newSetFromMap(IdentityHashMap<Slide, Boolean>()).apply { addAll(p.slides) }
        entries.keys.retainAll(live)
    }

    fun dispose() = scope.cancel()

    private companion object {
        /** The sizes one slide is kept at: the navigator's, the console's current and next, say. */
        const val WIDTHS = 3
    }
}

/** The slide thumbnails of the Slides window (its navigator, sorter and presenter console share them). */
internal val LocalSlideThumbs = staticCompositionLocalOf<SlideThumbs?> { null }

/** One cache of slide thumbnails for [content], dropped with it. */
@Composable
internal fun ProvideSlideThumbs(content: @Composable () -> Unit) {
    val thumbs = remember { SlideThumbs() }
    DisposableEffect(thumbs) { onDispose { thumbs.dispose() } }
    androidx.compose.runtime.CompositionLocalProvider(LocalSlideThumbs provides thumbs, content = content)
}

/**
 * [slide] (number [number]) as it prints, filling [modifier]'s box (the slide's own proportions);
 * its background colour stands in until its first picture is ready. [revision] is the editor's:
 * the slide is the same object after an edit, and its picture must still be looked at again.
 */
@Composable
internal fun SlideThumbnail(editor: SlideEditor, slide: Slide, number: Int, revision: Int, modifier: Modifier = Modifier) =
    DeckThumbnail(editor.presentation, slide, number, revision, modifier)

/** [slide] (number [number]) of [deck] — this window's or another file's (Reuse Slides) — as it prints, filling [modifier]'s box. */
@Composable
internal fun DeckThumbnail(deck: com.therealityteam.aeropages.model.Presentation, slide: Slide, number: Int, revision: Int, modifier: Modifier = Modifier) {
    val thumbs = LocalSlideThumbs.current
    BoxWithConstraints(modifier) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(16, 640)
        @Suppress("UNUSED_EXPRESSION") revision
        @Suppress("UNUSED_VARIABLE") val landed = thumbs?.version
        val bitmap = thumbs?.bitmapFor(deck, slide, number, widthPx)
        if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        else Box(Modifier.fillMaxSize().slideBackground(deck, slide))
    }
}
