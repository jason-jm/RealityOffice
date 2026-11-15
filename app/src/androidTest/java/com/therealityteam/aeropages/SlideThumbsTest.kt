package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pico.spatial.ui.design.PicoTheme
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.ui.LocalSlideThumbs
import com.therealityteam.aeropages.ui.ProvideSlideThumbs
import com.therealityteam.aeropages.ui.SlideThumbnail
import com.therealityteam.aeropages.ui.SlideThumbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A slide shown at two sizes at once — the navigator's thumbnail and the presenter console's — keeps a
 * picture for each: once both are drawn nothing more is rendered (each size used to replace the other's
 * picture without end, the UI never idle, the CPU never resting through a presentation).
 */
@RunWith(AndroidJUnit4::class)
class SlideThumbsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aSlideShownAtTwoSizesIsRenderedOnceForEach() {
        val editor = SlideEditor()
        editor.load(Presentation(mutableListOf(Slide(title = SlideBox("Quarterly review", CharacterFormat(fontSizePt = 40f, bold = true))))))
        val slide = editor.presentation.slides[0]
        var thumbs: SlideThumbs? = null
        rule.setContent {
            PicoTheme {
                ProvideSlideThumbs {
                    thumbs = LocalSlideThumbs.current
                    Row {
                        SlideThumbnail(editor, slide, 1, editor.revision, Modifier.width(120.dp).aspectRatio(16f / 9f))
                        SlideThumbnail(editor, slide, 1, editor.revision, Modifier.width(360.dp).aspectRatio(16f / 9f))
                    }
                }
            }
        }
        // Both pictures land (two renders), then the UI is idle: it used to time out here.
        rule.waitUntil(20_000) { (thumbs?.version ?: 0) >= 2 }
        rule.waitForIdle()
        val landed = thumbs!!.version
        Thread.sleep(3_000)
        rule.waitForIdle()
        assertEquals("nothing rendered once both sizes are drawn", landed, thumbs!!.version)
        assertTrue(landed <= 3)
    }
}
