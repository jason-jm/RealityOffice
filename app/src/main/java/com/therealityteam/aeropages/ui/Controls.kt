package com.therealityteam.aeropages.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.LocalDisableAlpha
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.graphics.SpatialHoverStyle
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

/**
 * The gaze highlight of a flat command: PICO's own `lightenHover` (a Vibrant lighten, as the
 * system's buttons and menus brighten under the gaze) — not a fixed darken, which reads as a
 * grey smudge on the glass.
 */

/**
 * A gaze-reactive highlight layer. Fully transparent at rest (the button reads flat),
 * and the SYSTEM fades it in to [RibbonGazeFill] when the user looks at the parent —
 * driven by the [spatialHoverEffect] DSL, whose `isActive` is toggled out-of-process
 * by the gaze pipeline (the app never observes hover itself). Place as the FIRST child
 * of the button's Box so it sits behind the icon/label.
 */
@Composable
internal fun BoxScope.GazeFill(shape: androidx.compose.ui.graphics.Shape) {
    Box(
        Modifier
            .matchParentSize()
            .clip(shape)
            .spatialHoverEffect { alpha(if (it.isActive) 1f else 0f) }
            .background(PicoTheme.colorScheme.lightenHover),
    )
}

// --- Gaze tooltips rendered in a top overlay (escape the ribbon's scroll clip) ---
//
// A true "name beside the button on gaze" cannot live as a child of the button: the
// ribbon rows scroll-clip anything drawn outside a button, and sibling buttons paint
// over it. So each icon button REGISTERS its on-screen rect + name into a [TooltipHost];
// a single [TooltipOverlay] at the app root (painted last, never clipped) then places,
// over each registered button, a gaze sensor whose name label the system fades in below
// the button. The button keeps its own [GazeFill] highlight; the overlay only adds text.

/** Registry of currently-visible icon buttons and their names, keyed by a stable id. */
class TooltipHost {
    data class Tip(val left: Int, val top: Int, val width: Int, val height: Int, val name: String, val cornerDp: Float)
    val tips = androidx.compose.runtime.mutableStateMapOf<Long, Tip>()
}

val LocalTooltipHost = androidx.compose.runtime.staticCompositionLocalOf<TooltipHost?> { null }

/** TEMP: force overlay labels visible to verify coordinate alignment in a screenshot. */
private const val OVERLAY_DEBUG = false

private var tipIdSeq = 0L
private fun nextTipId(): Long = tipIdSeq++

/**
 * Registers this composable's window rect + [name] with the ambient [TooltipHost] so the
 * top-level [TooltipOverlay] can show a gaze-driven name label beside it. No-op when no
 * host is provided (e.g. inside dialogs), so the button simply has no text tooltip there.
 */
fun Modifier.gazeTooltip(name: String, cornerDp: Float = 8f): Modifier = composed {
    val host = LocalTooltipHost.current
    if (host == null || name.isBlank()) return@composed Modifier
    val id = remember { nextTipId() }
    androidx.compose.runtime.DisposableEffect(id) { onDispose { host.tips.remove(id) } }
    Modifier.onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        val tip = TooltipHost.Tip(b.left.toInt(), b.top.toInt(), c.size.width, c.size.height, name, cornerDp)
        // Only write on real change: onGloballyPositioned fires on every layout pass, and
        // unconditional writes recompose the overlay each frame — the system hover region
        // under each sensor then never settles, so gaze highlight/labels never fire.
        if (host.tips[id] != tip) host.tips[id] = tip
    }
}

/**
 * Renders the gaze-driven name labels on top of everything. Place as the LAST child of
 * the app's root Box. For each registered button it positions a transparent, click-through
 * sensor over the button; the [spatialHoverEffect] DSL fades the name label in below it
 * when the system reports gaze on that region.
 */
@Composable
fun TooltipOverlay(host: TooltipHost) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Purely decorative: the labels duplicate each button's contentDescription (see [Tooltip]),
    // so keep the invisible sensors and their text out of the accessibility tree.
    Box(Modifier.fillMaxSize().semantics { hideFromAccessibility() }) {
        host.tips.forEach { (id, tip) ->
            androidx.compose.runtime.key(id) {
                Box(
                    Modifier
                        .offset { IntOffset(tip.left, tip.top) }
                        .size(with(density) { tip.width.toDp() }, with(density) { tip.height.toDp() })
                        .then(if (OVERLAY_DEBUG) Modifier else Modifier.spatialHoverEffect { alpha(if (it.isActive) 1f else 0f) }),
                ) {
                    // Grey highlight over the button (the overlay sensor "owns" the gaze,
                    // so the button's own GazeFill no longer fires — this replaces it).
                    Box(Modifier.matchParentSize().clip(RoundedCornerShape(tip.cornerDp.dp)).background(PicoTheme.colorScheme.lightenHover))
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = 26.dp)
                            .wrapContentSize(align = Alignment.Center, unbounded = true)
                            .clip(RoundedCornerShape(8.dp))
                            // design-style: design-surface tooltip
                            .background(PicoTheme.colorScheme.fillPrimary)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(tip.name, color = PicoTheme.colorScheme.labelPrimaryLight, maxLines = 1, softWrap = false, style = AeroType.caption)
                    }
                }
            }
        }
    }
}

/**
 * Application-side toolbar controls. We build these as custom composables (rather
 * than reusing a single built-in) because a ribbon needs dense, square,
 * toggle-aware buttons. They still route through SpatialUI tokens, hover, and
 * indication so they feel native:
 *  - colours via [PicoTheme.colorScheme]
 *  - hover via [Modifier.spatialHoverEffect]
 *  - press/click audio via [LocalIndication]
 *  - disabled alpha via [LocalDisableAlpha]
 */

/**
 * Attaches the control's [text] name as a semantics/accessibility label.
 *
 * NOTE: On PICO the gaze-hover signal is handled entirely in the system process
 * and is NOT observable by app code (verified in the Spatial SDK sources:
 * `detectSpatialPointerEvent` is press-based; there is no hover/gaze callback and
 * no OS tooltip component). So a visual "name appears when you look" tooltip
 * cannot be driven from the app layer. The gaze affordance is the system
 * highlight (`spatialHoverEffect(style = Highlight)`); this label is what the OS
 * accessibility layer can surface on gaze focus.
 */
@Composable
fun Tooltip(text: String, content: @Composable (a11y: Modifier) -> Unit) {
    // The name goes onto the control's own (clickable) node — a merging parent would stay a
    // separate node from the merging clickable child, leaving the button itself unnamed.
    content(if (text.isBlank()) Modifier else Modifier.semantics { contentDescription = text })
}

/** [content] at the app's own size ([LocalUiDensity]) inside zoomed content: floating bars and tips keep their size at any zoom. */
@Composable
internal fun AtUiSize(content: @Composable () -> Unit) {
    val ui = LocalUiDensity.current
    if (ui == null) content() else androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides ui, content = content)
}

/**
 * A floating glass bar of actions — a selection's context bar. [place] puts it given its own
 * measured size (null hides it); it runs at layout time, so a scroll or a moved selection moves
 * the bar without recomposing it.
 */
@Composable
internal fun FloatingActionBar(
    description: String,
    place: (androidx.compose.ui.unit.IntSize) -> IntOffset?,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    var size by remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    AtUiSize { Row(
        Modifier
            .offset { place(size) ?: IntOffset(-100_000, -100_000) }
            .onSizeChanged { size = it }
            .barSurface()
            .semantics { contentDescription = description }
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    ) }
}

/**
 * Children left to right, starting a new line when the next one would not fit the width — a small
 * FlowRow for pills whose labels vary a lot in length across languages (パーセンテージ, Comma Style).
 */
@Composable
fun WrapRow(
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = 6.dp,
    lineSpacing: androidx.compose.ui.unit.Dp = 6.dp,
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val lineGap = lineSpacing.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val positions = ArrayList<IntOffset>(placeables.size)
        var x = 0
        var y = 0
        var lineHeight = 0
        var width = 0
        for (p in placeables) {
            if (x > 0 && x + p.width > constraints.maxWidth) { x = 0; y += lineHeight + lineGap; lineHeight = 0 }
            positions.add(IntOffset(x, y))
            width = maxOf(width, x + p.width)
            x += p.width + gap
            lineHeight = maxOf(lineHeight, p.height)
        }
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), (y + lineHeight).coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placeables.forEachIndexed { i, p -> p.place(positions[i]) }
        }
    }
}

/** A pill with a small drawn [icon] before its [text] (a menu item such as Excel's border presets); no text shows the icon alone. */
@Composable
fun IconPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(Aero.commandRadius))
            .background(if (selected) Aero.tint(LocalAccent.current) else PicoTheme.colorScheme.fillTertiary)
            .then(if (text.isEmpty()) Modifier else Modifier.semantics { contentDescription = text })
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .controllerHapticFeedback(interactionSource = interaction)
            .padding(horizontal = 10.dp),
    ) {
        GazeFill(RoundedCornerShape(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            icon()
            if (text.isNotEmpty()) Text(text, style = AeroType.body, maxLines = 1, color = PicoTheme.colorScheme.labelPrimary)
        }
    }
}

/** A round colour swatch used by the colour / highlight pickers. */
/**
 * A colour to pick, as Office's pickers draw one: a rounded square with a faint edge (so white and grey still read);
 * the current one ringed in the module accent.
 */
@Composable
fun ColorSwatch(
    color: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val label = androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.a11y_color, String.format(java.util.Locale.ROOT, "#%06X", color and 0xFFFFFF))
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .size(30.dp)
            .then(if (selected) Modifier.border(2.dp, LocalAccent.current, RoundedCornerShape(8.dp)).padding(3.dp) else Modifier.padding(1.dp))
            .clip(shape)
            .semantics { contentDescription = label; this.selected = selected }
            .spatialHoverEffect(style = SpatialHoverStyle.Highlight)
            .background(Color(color))
            .border(BorderStroke(1.dp, Color(0x1F000000)), shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick).controllerHapticFeedback(interactionSource = interaction),
    )
}

/** A gradient's swatch: [first] at the top shading to [second] at the bottom, as [ColorSwatch] is a colour's. */
@Composable
fun GradientSwatch(first: Long, second: Long, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val label = androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.a11y_gradient,
        String.format(java.util.Locale.ROOT, "#%06X", first and 0xFFFFFF), String.format(java.util.Locale.ROOT, "#%06X", second and 0xFFFFFF))
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(30.dp)
            .then(if (selected) Modifier.border(2.dp, LocalAccent.current, RoundedCornerShape(8.dp)).padding(3.dp) else Modifier.padding(1.dp))
            .clip(RoundedCornerShape(6.dp))
            .semantics { contentDescription = label; this.selected = selected }
            .spatialHoverEffect(style = SpatialHoverStyle.Highlight)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(first), Color(second))))
            .border(BorderStroke(1.dp, Color(0x1F000000)), RoundedCornerShape(6.dp))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick).controllerHapticFeedback(interactionSource = interaction),
    ) {}
}

/**
 * A drop-down's caret: a small chevron pointing down, drawn rather than typed ("▾" in the system font
 * is little more than a dot at ribbon sizes).
 */
@Composable
fun DropCaret(color: Color, modifier: Modifier = Modifier, width: androidx.compose.ui.unit.Dp = 8.dp) {
    androidx.compose.foundation.Canvas(modifier.size(width, width * 0.6f)) {
        val sw = (width * 0.19f).toPx()
        val p = androidx.compose.ui.graphics.Path().apply { moveTo(sw / 2f, sw / 2f); lineTo(size.width / 2f, size.height - sw / 2f); lineTo(size.width - sw / 2f, sw / 2f) }
        drawPath(p, color, style = Stroke(sw, cap = StrokeCap.Butt, join = StrokeJoin.Miter))
    }
}

/** A command's foreground: labelPrimary always — a checked command is shown by its tint, not by inverting it. */
@Composable
private fun commandInk(): Color = PicoTheme.colorScheme.labelPrimary

/** A checked or open command's fill: the module accent, faint. */
@Composable
private fun commandFill(selected: Boolean): Color = if (selected) Aero.tint(LocalAccent.current) else Color.Transparent

/**
 * [icon] at [size] with an optional colour bar across its foot (Font Color, Highlight, Fill Color):
 * the bar takes the bottom of the icon's box, which those icons leave clear.
 */
@Composable
private fun CommandIcon(icon: String, ink: Color, size: androidx.compose.ui.unit.Dp, accent: Color?) {
    Box(Modifier.size(size)) {
        RibbonGlyph(icon, ink, size)
        if (accent != null) Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = size * 0.02f).width(size * 0.72f).height(size * 0.16f)
                .clip(RoundedCornerShape(1.dp)).background(accent),
        )
    }
}

// --- Office ribbon commands ---------------------------------------------------------------------------

/**
 * A large ribbon command (Office's Paste, New Slide, Find): a 32 dp icon over a label of up to two
 * lines, the ribbon's full height; checked or open shows the accent tint, a menu adds a caret.
 */
@Composable
fun RibbonButton(
    icon: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    dropdown: Boolean = false,
    accent: Color? = null,
    tooltip: String = label,
) = Tooltip(tooltip) { a11y ->
    val interaction = remember { MutableInteractionSource() }
    val (track, click) = anchoredClick(onClick)
    val ink = commandInk()
    val shape = RoundedCornerShape(Aero.commandRadius)
    // A squeezed ribbon (a long language, a narrow window) narrows its big buttons: the label wraps or shortens, its
    // full name stays in the tooltip and the screen reader's label.
    val maxWidth = when (LocalRibbonSqueeze.current) { 0, 1 -> 96.dp; 2 -> 80.dp; else -> 68.dp }
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier.then(a11y).then(track)
            .height(Aero.ribbonContentHeight)
            .widthIn(min = 56.dp, max = maxWidth)
            .clip(shape)
            .alpha(if (enabled) 1f else LocalDisableAlpha.current)
            .background(commandFill(selected))
            .clickable(enabled = enabled, interactionSource = interaction, indication = LocalIndication.current, onClick = click)
            .controllerHapticFeedback(interactionSource = interaction),
    ) {
        if (enabled) GazeFill(shape)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
        ) {
            CommandIcon(icon, ink, 32.dp, accent)
            Text(
                // Two balanced lines, a long word hyphenated where the language allows (Dokument-/prüfung, Финан-/совые).
                label, style = AeroType.command.copy(hyphens = androidx.compose.ui.text.style.Hyphens.Auto,
                    lineBreak = androidx.compose.ui.text.style.LineBreak.Heading),
                color = ink, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
            )
            if (dropdown) DropCaret(PicoTheme.colorScheme.labelSecondary, Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * An icon ribbon command (Office's Bold, Align Left, Borders): 20 dp icon in a 32 x 30 target, its
 * name on gaze. Rows of these make the two- and three-row groups; an empty [icon] is the caret half
 * of a split button.
 */
@Composable
fun RibbonMiniButton(
    icon: String,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    dropdown: Boolean = false,
    accent: Color? = null,
) = Tooltip(tooltip) { a11y ->
    val interaction = remember { MutableInteractionSource() }
    val (track, click) = anchoredClick(onClick)
    val ink = commandInk()
    val shape = RoundedCornerShape(Aero.commandRadius)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.then(a11y).then(track)
            .height(Aero.commandHeight)
            .widthIn(min = if (icon.isEmpty()) 18.dp else 32.dp)
            .alpha(if (enabled) 1f else LocalDisableAlpha.current)
            .clip(shape)
            .background(commandFill(selected))
            .then(if (enabled) Modifier.gazeTooltip(tooltip, cornerDp = 8f) else Modifier)
            .clickable(enabled = enabled, interactionSource = interaction, indication = LocalIndication.current, onClick = click)
            .controllerHapticFeedback(interactionSource = interaction),
    ) {
        if (enabled) GazeFill(shape)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = if (icon.isEmpty()) 4.dp else 6.dp)) {
            // A caret-only button (the menu half of a split button) draws no icon — not even an empty text.
            if (icon.isNotEmpty() || accent != null) CommandIcon(icon, ink, 20.dp, accent)
            if (dropdown) DropCaret(PicoTheme.colorScheme.labelSecondary, Modifier.padding(start = if (icon.isNotEmpty()) 4.dp else 0.dp), 8.dp)
        }
    }
}

/** A small ribbon command: a 20 dp icon with its [label] beside it, for the stacked commands of a group (PowerPoint's Layout, Reset, Section). */
@Composable
fun RibbonSmallButton(
    icon: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    dropdown: Boolean = false,
    tooltip: String = label,
    /** The ribbon squeeze ([LocalRibbonSqueeze]) from which it shows its icon alone, Office's way with a narrow window; its name stays its tooltip. */
    compactAt: Int = Int.MAX_VALUE,
) = Tooltip(tooltip) { a11y ->
    val interaction = remember { MutableInteractionSource() }
    val (track, click) = anchoredClick(onClick)
    val ink = commandInk()
    val shape = RoundedCornerShape(Aero.commandRadius)
    val named = LocalRibbonSqueeze.current < compactAt
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier.then(a11y).then(track)
            .height(Aero.commandHeight)
            .alpha(if (enabled) 1f else LocalDisableAlpha.current)
            .clip(shape)
            .background(commandFill(selected))
            .clickable(enabled = enabled, interactionSource = interaction, indication = LocalIndication.current, onClick = click)
            .controllerHapticFeedback(interactionSource = interaction),
    ) {
        if (enabled) GazeFill(shape)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 6.dp)) {
            RibbonGlyph(icon, ink, 20.dp)
            if (named) Text(label, style = AeroType.command, color = ink, maxLines = 1)
            if (dropdown) DropCaret(PicoTheme.colorScheme.labelSecondary)
        }
    }
}

/** An Office combo box (font name, size): the value and a caret on a field, opening its list. */
@Composable
fun RibbonCombo(
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 120.dp,
    selected: Boolean = false,
    tooltip: String = "",
) = Tooltip(tooltip) { a11y ->
    val interaction = remember { MutableInteractionSource() }
    val (track, click) = anchoredClick(onClick)
    val shape = RoundedCornerShape(Aero.commandRadius)
    Box(
        modifier = modifier.then(a11y).then(track)
            .clip(shape)
            // The field of a control, not a content surface: lighter than the ribbon, the accent when open.
            .background(if (selected) Aero.tint(LocalAccent.current) else PicoTheme.colorScheme.fillSecondary)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = click)
            .controllerHapticFeedback(interactionSource = interaction),
    ) {
        GazeFill(shape)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.width(width + 8.dp).height(Aero.commandHeight).padding(horizontal = 10.dp),
        ) {
            Text(value, style = AeroType.command, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.weight(1f))
            DropCaret(PicoTheme.colorScheme.labelSecondary)
        }
    }
}

/** A ribbon tab: its name, the accent bar under it when it is the one shown. [contextual] tabs (Table Design) name themselves in the accent. */
@Composable
fun RibbonTab(title: String, selected: Boolean, contextual: Boolean = false, foldable: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    // With the ribbon showing tabs only, a click on a tab shows (or puts away) its commands, and no tab is marked while
    // none show.
    val fold = if (foldable) LocalRibbonFold.current else null
    val marked = selected && (fold == null || fold.shown)
    val shape = RoundedCornerShape(Aero.commandRadius)
    val accent = LocalAccent.current
    Box(
        modifier = Modifier
            .height(Aero.tabRowHeight - 4.dp)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { fold?.tabClicked(selected); onClick() }
            .controllerHapticFeedback(interactionSource = interaction),
        contentAlignment = Alignment.Center,
    ) {
        GazeFill(shape)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 14.dp)) {
            Text(
                title,
                style = AeroType.tab.let { if (marked) it.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else it },
                color = when { contextual -> accent; marked -> PicoTheme.colorScheme.labelPrimary; else -> PicoTheme.colorScheme.labelSecondary },
                maxLines = 1,
            )
            Box(
                Modifier.padding(top = 5.dp).width(24.dp).height(3.dp).clip(RoundedCornerShape(1.5.dp))
                    .background(if (marked) accent else Color.Transparent),
            )
        }
    }
}

/** The File tab: it opens the backstage, so it is drawn as the first tab with the accent when open. */
@Composable
internal fun FileTab(open: Boolean, onClick: () -> Unit) =
    RibbonTab(androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.menu_file), selected = open, foldable = false, onClick = onClick)

// --- Icons ---------------------------------------------------------------------------------------------
// A command's icon is a key: a vector from the app's set (AeroIcons — "ic_save", or an older key such as
// "💾" that now draws it), Office's letter icons set as type (B, I, U, x², Σ, ¥…), or — for a key nothing
// knows — the key itself as text.

@Composable
fun RibbonGlyph(key: String, color: Color, size: androidx.compose.ui.unit.Dp = 20.dp) {
    val res = AeroIcons.of(key)
    when {
        res != 0 -> com.pico.spatial.ui.design.Icon(androidx.compose.ui.res.painterResource(res), null, Modifier.size(size), tint = color)
        key in LETTER_GLYPHS -> LetterGlyph(key, color, size)
        else -> Text(key, fontSize = (size.value * 0.8f).sp, color = color, maxLines = 1, style = PicoTheme.typography.titleMedium)
    }
}

/** Office's letter icons — Bold, Italic, Underline, Strikethrough, x², x₂, Change Case — set as type of one weight. */
private val LETTER_GLYPHS = setOf("B", "I", "U", "S", "x²", "x₂", "Aa", "ᴀʙ", "123", "Ω", "π", "§", "Σ", "x_sigma", "x_fx",
    "简", "繁", "ab¹", "abⁱ", "¥", "%", "+.0", "-.0", "x_thousands", "§✕", "字数", "x_fn_financial", "x_fn_logical", "x_fn_text", "x_fn_math")

@Composable
private fun LetterGlyph(key: String, color: Color, size: androidx.compose.ui.unit.Dp) {
    val base = androidx.compose.ui.text.TextStyle(
        color = color, fontSize = (size.value * 0.78f).sp, lineHeight = (size.value * 0.9f).sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
    )
    val small = androidx.compose.ui.text.SpanStyle(fontSize = (size.value * 0.56f).sp)
    val text = when (key) {
        "B" -> androidx.compose.ui.text.AnnotatedString("B", androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Black))
        "I" -> androidx.compose.ui.text.AnnotatedString("I", androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif))
        "U" -> androidx.compose.ui.text.AnnotatedString("U", androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))
        "S" -> androidx.compose.ui.text.AnnotatedString("ab", androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough, fontSize = (size.value * 0.66f).sp))
        "x²" -> androidx.compose.ui.text.buildAnnotatedString { append("x"); pushStyle(small.copy(baselineShift = androidx.compose.ui.text.style.BaselineShift.Superscript)); append("2"); pop() }
        "x₂" -> androidx.compose.ui.text.buildAnnotatedString { append("x"); pushStyle(small.copy(baselineShift = androidx.compose.ui.text.style.BaselineShift.Subscript)); append("2"); pop() }
        "ᴀʙ" -> androidx.compose.ui.text.AnnotatedString("AB", androidx.compose.ui.text.SpanStyle(fontSize = (size.value * 0.56f).sp))
        "123" -> androidx.compose.ui.text.AnnotatedString("123", androidx.compose.ui.text.SpanStyle(fontSize = (size.value * 0.5f).sp))
        "Aa" -> androidx.compose.ui.text.AnnotatedString("Aa", androidx.compose.ui.text.SpanStyle(fontSize = (size.value * 0.66f).sp))
        "x_sigma", "Σ" -> androidx.compose.ui.text.AnnotatedString("Σ", androidx.compose.ui.text.SpanStyle(fontSize = (size.value * 0.86f).sp))
        "x_fx" -> androidx.compose.ui.text.AnnotatedString("fx", androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontSize = (size.value * 0.72f).sp))
        "π", "Ω" -> androidx.compose.ui.text.AnnotatedString(key, androidx.compose.ui.text.SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontSize = (size.value * 0.86f).sp))
        "简", "繁", "¥", "%" -> androidx.compose.ui.text.AnnotatedString(key, androidx.compose.ui.text.SpanStyle(fontSize = (size.value * 0.8f).sp))
        "ab¹", "abⁱ" -> androidx.compose.ui.text.AnnotatedString(key, androidx.compose.ui.text.SpanStyle(fontSize = (size.value * 0.56f).sp))
        // Increase / Decrease Decimal and Comma Style in the reader's symbols (,00 and .000 in German), as Excel draws them.
        "+.0", "-.0", "x_thousands" -> com.therealityteam.aeropages.model.NumberSymbols.current().let { sym ->
            val g = if (sym.group == '\u00A0') ' ' else sym.group
            androidx.compose.ui.text.AnnotatedString(when (key) { "+.0" -> "${sym.decimal}00"; "-.0" -> "${sym.decimal}0"; else -> "${g}000" },
                androidx.compose.ui.text.SpanStyle(fontSize = (size.value * 0.56f).sp))
        }
        "§✕" -> androidx.compose.ui.text.AnnotatedString("§", androidx.compose.ui.text.SpanStyle(fontSize = (size.value * 0.86f).sp, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough))
        "字数" -> androidx.compose.ui.text.AnnotatedString("123", androidx.compose.ui.text.SpanStyle(fontSize = (size.value * 0.5f).sp))
        "x_fn_financial" -> androidx.compose.ui.text.AnnotatedString("¥", androidx.compose.ui.text.SpanStyle(fontSize = (size.value * 0.8f).sp))
        "x_fn_logical" -> androidx.compose.ui.text.AnnotatedString("?", androidx.compose.ui.text.SpanStyle(fontSize = (size.value * 0.86f).sp))
        "x_fn_text" -> androidx.compose.ui.text.AnnotatedString("A", androidx.compose.ui.text.SpanStyle(fontSize = (size.value * 0.86f).sp))
        "x_fn_math" -> androidx.compose.ui.text.AnnotatedString("θ", androidx.compose.ui.text.SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontSize = (size.value * 0.86f).sp))
        else -> androidx.compose.ui.text.AnnotatedString(key)
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.text.BasicText(text, style = base, maxLines = 1, softWrap = false)
    }
}

/** A tick box and its label, the whole row toggling it (see [CheckOption]). */
@Composable
internal fun CheckRow(label: String, on: Boolean, toggle: () -> Unit) = CheckOption(label, on, toggle)

/** One choice of several, its label beside it, the whole row picking it (see [RadioOption]). */
@Composable
internal fun RadioRow(label: String, on: Boolean, pick: () -> Unit) = RadioOption(label, on, pick)
