package com.therealityteam.aeropages.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.format.pptx.PresetGeometries
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.SlideShape

/**
 * PowerPoint's Insert → Shapes gallery: its shapes by category, as PowerPoint lists them — the
 * rectangles, the basic shapes, the block arrows, the equation shapes, the flowchart, the stars and
 * banners, the callouts and the action buttons — those the app draws, each drawn as it will look.
 */
internal object ShapeCatalog {

    /** The categories, each a heading and its presets in PowerPoint's order. */
    val CATEGORIES: List<Pair<Int, List<String>>> = listOf(
        R.string.shapes_rectangles to listOf("rect", "roundRect", "snip1Rect", "snip2SameRect", "snip2DiagRect", "snipRoundRect", "round1Rect", "round2SameRect", "round2DiagRect"),
        R.string.shapes_basic to listOf(
            "ellipse", "triangle", "rtTriangle", "parallelogram", "trapezoid", "nonIsoscelesTrapezoid", "diamond", "pentagon", "hexagon", "heptagon",
            "octagon", "decagon", "dodecagon", "pie", "chord", "teardrop", "frame", "halfFrame", "corner", "diagStripe", "plus", "plaque", "can", "cube",
            "bevel", "donut", "noSmoking", "blockArc", "foldedCorner", "smileyFace", "heart", "lightningBolt", "sun", "moon", "cloud", "arc",
            "bracketPair", "bracePair", "leftBracket", "rightBracket", "leftBrace", "rightBrace", "pieWedge", "funnel",
        ),
        R.string.shapes_block_arrows to listOf(
            "rightArrow", "leftArrow", "upArrow", "downArrow", "leftRightArrow", "upDownArrow", "quadArrow", "leftRightUpArrow", "bentArrow", "uturnArrow",
            "leftUpArrow", "bentUpArrow", "curvedRightArrow", "curvedLeftArrow", "curvedUpArrow", "curvedDownArrow", "stripedRightArrow", "notchedRightArrow",
            "homePlate", "chevron", "rightArrowCallout", "downArrowCallout", "leftArrowCallout", "upArrowCallout", "leftRightArrowCallout",
            "upDownArrowCallout", "quadArrowCallout", "circularArrow", "swooshArrow",
        ),
        R.string.shapes_equation to listOf("mathPlus", "mathMinus", "mathMultiply", "mathDivide", "mathEqual", "mathNotEqual"),
        R.string.shapes_flowchart to listOf(
            "flowChartProcess", "flowChartAlternateProcess", "flowChartDecision", "flowChartInputOutput", "flowChartPredefinedProcess",
            "flowChartInternalStorage", "flowChartDocument", "flowChartMultidocument", "flowChartTerminator", "flowChartPreparation",
            "flowChartManualInput", "flowChartManualOperation", "flowChartConnector", "flowChartOffpageConnector", "flowChartPunchedCard",
            "flowChartPunchedTape", "flowChartSummingJunction", "flowChartOr", "flowChartCollate", "flowChartSort", "flowChartExtract",
            "flowChartMerge", "flowChartOfflineStorage", "flowChartOnlineStorage", "flowChartDelay", "flowChartMagneticTape",
            "flowChartMagneticDisk", "flowChartMagneticDrum", "flowChartDisplay",
        ),
        R.string.shapes_stars_banners to listOf(
            "irregularSeal1", "irregularSeal2", "star4", "star5", "star6", "star7", "star8", "star10", "star12", "star16", "star24", "star32",
            "ribbon2", "ribbon", "ellipseRibbon2", "ellipseRibbon", "verticalScroll", "horizontalScroll", "wave", "doubleWave",
        ),
        R.string.shapes_callouts to listOf(
            "wedgeRectCallout", "wedgeRoundRectCallout", "wedgeEllipseCallout", "cloudCallout", "borderCallout1", "borderCallout2", "borderCallout3",
            "accentCallout1", "accentCallout2", "accentCallout3", "callout1", "callout2", "callout3", "accentBorderCallout1", "accentBorderCallout2", "accentBorderCallout3",
        ),
        R.string.shapes_action_buttons to listOf(
            "actionButtonBackPrevious", "actionButtonForwardNext", "actionButtonBeginning", "actionButtonEnd", "actionButtonHome", "actionButtonInformation",
            "actionButtonReturn", "actionButtonMovie", "actionButtonDocument", "actionButtonSound", "actionButtonHelp", "actionButtonBlank",
        ),
    )

    /** The presets the app draws as shapes of its own. */
    val OWN: Map<String, ShapeType> get() = com.therealityteam.aeropages.editor.OWN_PRESETS

    /** Whether the app can draw [name]. */
    fun draws(name: String): Boolean = name in OWN || PresetGeometries.has(name)

    /** The categories with the presets the app draws (one drawing none is left out). */
    val shown: List<Pair<Int, List<String>>> get() = CATEGORIES.map { (title, names) -> title to names.filter(::draws) }.filter { it.second.isNotEmpty() }

    /** "flowChartPredefinedProcess" → "Flow chart predefined process", "star10" → "Star 10": a name to say a preset by. */
    fun spoken(name: String): String {
        val words = Regex("[A-Z]?[a-z]+|[0-9]+|[A-Z]+(?![a-z])").findAll(name).map { it.value.lowercase() }.toList()
        return words.joinToString(" ").replaceFirstChar { it.uppercase() }
    }
}

/** The gallery: each category's heading and its shapes; picking one calls [onPick] with its preset. */
@Composable
internal fun ShapeGallery(onLine: ((arrowAtStart: Boolean, arrowAtEnd: Boolean) -> Unit)? = null, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // PowerPoint's gallery opens with its lines: plain, arrow, double arrow.
        if (onLine != null) {
            Text(stringResource(R.string.shapes_category_lines), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(Triple(false, false, R.string.shape_line), Triple(false, true, R.string.shape_line_arrow), Triple(true, true, R.string.shape_double_arrow))
                    .forEach { (start, end, label) -> LineTile(start, end, stringResource(label)) { onLine(start, end) } }
            }
        }
        ShapeCatalog.shown.forEach { (title, names) ->
            Text(stringResource(title), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            WrapRow(spacing = 4.dp, lineSpacing = 4.dp) {
                names.forEach { name -> ShapeTile(name) { onPick(name) } }
            }
        }
    }
}

/** A line of the gallery: a diagonal with the arrowheads it has. */
@Composable
private fun LineTile(arrowAtStart: Boolean, arrowAtEnd: Boolean, label: String, onClick: () -> Unit) {
    val color = PicoTheme.colorScheme.labelSecondary
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)).press(onClick = onClick)
            .semantics { contentDescription = label }.padding(8.dp),
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val a = androidx.compose.ui.geometry.Offset(0f, size.height); val b = androidx.compose.ui.geometry.Offset(size.width, 0f)
            drawLine(color, a, b, 1.6.dp.toPx())
            fun head(tip: androidx.compose.ui.geometry.Offset, from: androidx.compose.ui.geometry.Offset) {
                val dx = tip.x - from.x; val dy = tip.y - from.y; val len = kotlin.math.hypot(dx, dy); val ux = dx / len; val uy = dy / len
                val l = size.minDimension * 0.32f; val w = l * 0.55f
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(tip.x, tip.y); lineTo(tip.x - ux * l - uy * w, tip.y - uy * l + ux * w); lineTo(tip.x - ux * l + uy * w, tip.y - uy * l - ux * w); close()
                }
                drawPath(p, color)
            }
            if (arrowAtEnd) head(b, a)
            if (arrowAtStart) head(a, b)
        }
    }
}

/** One shape of the gallery, drawn by the painter the slide uses — in outline, as PowerPoint's gallery draws its shapes. */
@Composable
private fun ShapeTile(name: String, onClick: () -> Unit) {
    val line = PicoTheme.colorScheme.labelSecondary.toArgb().toLong() and 0xFFFFFFFFL
    val sample = remember(name, line) {
        SlideShape(ShapeCatalog.OWN[name] ?: ShapeType.FREEFORM, fill = null, border = line, borderWidthPt = 1.1f).also { if (name !in ShapeCatalog.OWN) it.preset = name }
    }
    val label = ShapeCatalog.spoken(name)
    val pxPerPt = LocalDensity.current.density
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)).press(onClick = onClick)
            .semantics { contentDescription = label }.padding(6.dp),
    ) {
        ShapeGeometry(sample, pxPerPt, 0, Modifier.fillMaxSize())
    }
}
