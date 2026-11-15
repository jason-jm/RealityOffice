package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.effect3d.rotate3D
import com.pico.spatial.ui.foundation.geometry.Rotation3D
import com.pico.spatial.ui.foundation.geometry.RotationAxis3D
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.foundation.layout.offset
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import com.therealityteam.aeropages.model.SlideChart
import androidx.compose.ui.res.stringResource
import com.therealityteam.aeropages.R
import java.util.Locale

/**
 * XR 空间3D图表: the chart's series rendered as columns floating at different depths.
 * Each series row steps toward the viewer with a real z offset, and dragging orbits
 * the whole plot via rotate3D — a PICO-only spatial view of the same chart model
 * that ChartPlot draws flat.
 */
@androidx.compose.runtime.NonSkippableComposable
@Composable
fun Spatial3DChart(chart: SlideChart, modifier: Modifier = Modifier, plotHeight: Int = 200, ink: Color = Aero.ink) {
    var rotY by remember { mutableFloatStateOf(-22f) }
    var rotX by remember { mutableFloatStateOf(10f) }
    val pal = CHART_PALETTES.getOrElse(chart.colorSet) { CHART_PALETTES[0] }
    val maxV = chart.series.flatMap { it.values }.maxOrNull()?.coerceAtLeast(0.001f) ?: 1f
    val catCount = chart.categories.size.coerceAtLeast(chart.series.firstOrNull()?.values?.size ?: 1)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().height(plotHeight.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        rotY += drag.x * 0.4f
                        rotX = (rotX - drag.y * 0.25f).coerceIn(-20f, 55f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.fillMaxSize()
                    .rotate3D { Rotation3D(rotX, RotationAxis3D.X) }
                    .rotate3D { Rotation3D(rotY, RotationAxis3D.Y) },
                contentAlignment = Alignment.BottomCenter,
            ) {
                // Farthest series first; each successive series floats 22dp closer (+z).
                chart.series.forEachIndexed { si, sr ->
                    val z = ((si - (chart.series.size - 1)) * 22 + 10).dp
                    val color = Color(pal[si % pal.size])
                    Row(
                        Modifier.fillMaxWidth().offset(z = z).padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        repeat(catCount) { ci ->
                            val v = sr.values.getOrElse(ci) { 0f }
                            val h = ((plotHeight - 28) * (v / maxV).coerceIn(0f, 1f)).dp
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (chart.showValues && v != 0f) {
                                    Text(trimChartValue(v), fontSize = 8.sp, color = ink.copy(alpha = 0.8f))
                                }
                                Box(
                                    Modifier.width(18.dp).height(h)
                                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                        .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.55f)))),
                                )
                            }
                        }
                    }
                }
                // Ground line anchoring the bars in space (slightly behind the front row).
                Box(
                    Modifier.fillMaxWidth().height(2.dp).offset(z = (-2).dp)
                        .background(ink.copy(alpha = 0.4f)),
                )
            }
        }
        // Category labels + legend.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            repeat(catCount) { ci ->
                Text(chart.categories.getOrElse(ci) { "${ci + 1}" }, fontSize = 9.sp, color = ink.copy(alpha = 0.67f), maxLines = 1)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            chart.series.take(4).forEachIndexed { si, sr ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Box(Modifier.size(8.dp).background(Color(pal[si % pal.size])))
                    Text(" ${sr.name}", fontSize = 9.sp, color = ink.copy(alpha = 0.67f), maxLines = 1)
                }
            }
            Text(stringResource(R.string.msg_drag_to_rotate), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
        }
    }
}

private fun trimChartValue(v: Float): String =
    if (v == v.toLong().toFloat()) v.toLong().toString() else String.format(Locale.getDefault(), "%.1f", v)

/**
 * XR 空间批注卡片: the document's review comments as glass cards floating in front of
 * the page (staggered z depths + spatial hover lift). Tapping a card jumps to its
 * anchored paragraph; ✕ deletes the comment.
 */
@Composable
fun SpatialCommentCards(
    editor: com.therealityteam.aeropages.editor.DocumentEditor,
    modifier: Modifier = Modifier,
    maxCards: Int = 6,
) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val comments = editor.document.comments
    // The comments as the pane draws them — avatar, author, text on the card fill — each floating a little nearer than the page
    // (its depth is the spatial part); on the window's own glass, so no second material and no fixed colours.
    Column(modifier.width(236.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (comments.isEmpty()) {
            Box(Modifier.fillMaxWidth().offset(z = 14.dp).clip(RoundedCornerShape(12.dp)).background(Aero.card).padding(12.dp)) {
                Text(stringResource(R.string.msg_no_comments_yet), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            }
            return@Column
        }
        comments.take(maxCards).forEachIndexed { i, c ->
            Column(
                Modifier.fillMaxWidth()
                    .offset(z = (16 + (i % 3) * 7).dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Aero.card)
                    .spatialHoverEffect()
                    .press {
                        val idx = editor.indexOfParagraphId(c.targetId)
                        if (idx >= 0) editor.focusParagraph(idx)
                    }
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Avatar(c.author, 24.dp)
                    Text(c.author, style = AeroType.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                        color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.weight(1f))
                    TitleIconButton("ic_close", stringResource(R.string.delete), { editor.deleteComment(c) }, size = 28.dp)
                }
                Text(c.text.ifBlank { stringResource(R.string.msg_empty_comment_hint) }, style = AeroType.body,
                    color = if (c.text.isBlank()) PicoTheme.colorScheme.labelSecondary else PicoTheme.colorScheme.labelPrimary,
                    maxLines = 3, modifier = Modifier.padding(end = 6.dp))
            }
        }
        if (comments.size > maxCards) {
            Text(stringResource(R.string.msg_more_comments_hint, comments.size - maxCards), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
        }
    }
}
