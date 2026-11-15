package com.therealityteam.aeropages.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.AlertDialog
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.model.Croppable
import com.therealityteam.aeropages.model.NumberSymbols

/**
 * Crop, as in PowerPoint and Word: the whole picture ([data], [aspect] wide as its frame shows it)
 * with what the crop cuts away dimmed, the share cut from each side (percent), and Crop to Aspect
 * Ratio presets that take the largest centred part of that shape. OK hands the new sides to
 * [onCrop]; Reset shows the whole picture again. [crop] is the picture's crop now.
 */
@Composable
internal fun CropDialog(data: ByteArray, aspect: Float, crop: Croppable, onCrop: (Float, Float, Float, Float) -> Unit, onDismiss: () -> Unit) {
    fun pct(v: Float) = (Math.round(v * 1000f) / 10f).let { if (it == it.toInt().toFloat()) it.toInt().toString() else NumberSymbols.current().local(it.toString()) }
    var left by remember { mutableStateOf(pct(crop.cropL)) }
    var top by remember { mutableStateOf(pct(crop.cropT)) }
    var right by remember { mutableStateOf(pct(crop.cropR)) }
    var bottom by remember { mutableStateOf(pct(crop.cropB)) }
    fun frac(s: String) = ((s.trim().replace(',', '.').toFloatOrNull() ?: 0f) / 100f).coerceIn(0f, Croppable.MAX_CUT)
    /** The centred crop of the whole picture that leaves it [ratio] wide. */
    fun toAspect(ratio: Float) {
        if (aspect > ratio) { val cut = (1f - ratio / aspect) / 2f; left = pct(cut); right = pct(cut); top = "0"; bottom = "0" }
        else { val cut = (1f - aspect / ratio) / 2f; top = pct(cut); bottom = pct(cut); left = "0"; right = "0" }
    }
    AeroDialog(title = stringResource(R.string.title_crop_picture), onDismiss = onDismiss, width = 460.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.crop_reset), { left = "0"; top = "0"; right = "0"; bottom = "0" })
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.btn_ok), {
                    onCrop(frac(left), frac(top), frac(right), frac(bottom)); onDismiss()
                }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(460.dp)) {
                // The whole picture, the part the crop keeps framed and the rest dimmed.
                val bmp = rememberDecodedImage(data)
                val (l, t, r, b) = Croppable.clamp(frac(left), frac(top), frac(right), frac(bottom))
                Box(Modifier.fillMaxWidth().height(200.dp).background(Color(0xFF2B2B2B), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    if (bmp != null) {
                        val boxAspect = 460f / 200f
                        val (w, h) = if (aspect >= boxAspect) 440.dp to (440f / aspect).dp else (184f * aspect).dp to 184.dp
                        Box(Modifier.size(w, h)) {
                            Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                            Canvas(Modifier.fillMaxSize().semantics { contentDescription = "crop preview" }) {
                                val shade = Color.Black.copy(alpha = 0.55f)
                                val x0 = size.width * l; val x1 = size.width * (1f - r); val y0 = size.height * t; val y1 = size.height * (1f - b)
                                drawRect(shade, size = Size(size.width, y0))
                                drawRect(shade, topLeft = Offset(0f, y1), size = Size(size.width, size.height - y1))
                                drawRect(shade, topLeft = Offset(0f, y0), size = Size(x0, y1 - y0))
                                drawRect(shade, topLeft = Offset(x1, y0), size = Size(size.width - x1, y1 - y0))
                                drawRect(Color.White, topLeft = Offset(x0, y0), size = Size(x1 - x0, y1 - y0), style = Stroke(2.dp.toPx()))
                            }
                        }
                    }
                }
                // Crop to an aspect ratio (PowerPoint's Crop ▸ Aspect Ratio), the one the crop now keeps marked.
                DialogLabel(stringResource(R.string.crop_aspect_ratio))
                val ratios = listOf("1:1" to 1f, "4:3" to 4f / 3f, "3:2" to 1.5f, "16:9" to 16f / 9f, "3:4" to 0.75f, "9:16" to 9f / 16f)
                val kept = aspect * (1f - l - r) / (1f - t - b).coerceAtLeast(0.01f)
                ChoiceSegments(ratios, ratios.firstOrNull { kotlin.math.abs(it.second - kept) < 0.01f }, { it.first }, { toAspect(it.second) }, width = 460.dp)
                Text(stringResource(R.string.crop_hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                // The four edges cut off, in per cent: a spin box each (half a per cent a press), typed into as well.
                val edges = listOf(
                    Triple(R.string.crop_left, left) { v: String -> left = v }, Triple(R.string.crop_top, top) { v: String -> top = v },
                    Triple(R.string.crop_right, right) { v: String -> right = v }, Triple(R.string.crop_bottom, bottom) { v: String -> bottom = v },
                )
                edges.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        pair.forEach { (res, value, set) ->
                            val label = stringResource(res)
                            FieldRow(label, labelWidth = 48.dp) {
                                SpinFrame({ d -> set(pct((frac(value) + d * 0.005f).coerceIn(0f, Croppable.MAX_CUT))) }, description = label) {
                                    BasicTextField(
                                        value = value, onValueChange = set, singleLine = true,
                                        textStyle = AeroType.command.copy(color = PicoTheme.colorScheme.labelPrimary, textAlign = androidx.compose.ui.text.style.TextAlign.End),
                                        cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalAccent.current),
                                        modifier = Modifier.width(52.dp).semantics { contentDescription = label },
                                    )
                                    Text("%", style = AeroType.command, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.padding(start = 2.dp, end = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
}
