package com.therealityteam.aeropages.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.core.graphics.createBitmap
import com.therealityteam.aeropages.format.pdf.SlidesPdfExporter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideDesign
import com.therealityteam.aeropages.model.SlideSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pictures of slide designs (a template's master pages), each rendered once per size by the PDF's
 * painter and kept — a few, the most recently used — so every slide on a design, the editor's and
 * the show's, lays the same picture under its content.
 */
private object DesignPictures {
    private data class Key(val design: SlideDesign, val size: SlideSize, val widthPx: Int, val picture: Boolean, val objects: Boolean)

    private const val KEEP = 8
    private val cache = object : LinkedHashMap<Key, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ImageBitmap>?) = size > KEEP
    }

    fun cached(d: SlideDesign, size: SlideSize, widthPx: Int, picture: Boolean, objects: Boolean): ImageBitmap? =
        synchronized(cache) { cache[Key(d, size, widthPx, picture, objects)] }

    fun render(d: SlideDesign, size: SlideSize, widthPx: Int, picture: Boolean, objects: Boolean): ImageBitmap? {
        cached(d, size, widthPx, picture, objects)?.let { return it }
        val heightPx = (widthPx / size.aspect).toInt().coerceAtLeast(1)
        val bitmap = runCatching {
            createBitmap(widthPx, heightPx).also { SlidesPdfExporter.renderDesign(it, d, size, picture, objects) }.asImageBitmap()
        }.getOrNull() ?: return null
        synchronized(cache) { cache[Key(d, size, widthPx, picture, objects)] = bitmap }
        return bitmap
    }
}

/**
 * What [slide]'s design draws — its picture fill (unless the slide has a background of its own) and
 * its pictures and shapes (unless the slide hides them) — filling [modifier]'s box, under the
 * slide's content: the editor and the show draw a template's slides as the PDF and PowerPoint do.
 */
@Composable
@androidx.compose.runtime.NonSkippableComposable
internal fun SlideDesignLayer(p: Presentation, slide: Slide, modifier: Modifier = Modifier) =
    // What it draws is passed as values: a slide given another design is the same Slide object, and Compose
    // (strong skipping) would not redraw a layer handed only the same deck and slide.
    DesignLayer(p.designOf(slide)?.takeIf { it.drawsSomething }, p.slideSize, slide.background == null, slide.designObjects, modifier)

@Composable
private fun DesignLayer(design: SlideDesign?, size: SlideSize, picture: Boolean, objects: Boolean, modifier: Modifier) {
    design ?: return
    if (!(picture && design.picture != null) && !(objects && design.items.isNotEmpty())) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(16, 2560)
        val bitmap by produceState(DesignPictures.cached(design, size, widthPx, picture, objects), design, size, widthPx, picture, objects) {
            value = DesignPictures.cached(design, size, widthPx, picture, objects)
                ?: withContext(Dispatchers.Default) { DesignPictures.render(design, size, widthPx, picture, objects) }
        }
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
    }
}
