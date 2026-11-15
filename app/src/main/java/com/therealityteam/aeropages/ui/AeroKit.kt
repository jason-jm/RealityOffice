package com.therealityteam.aeropages.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.graphics.SpatialHoverStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.VerticalDivider
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback

/**
 * AeroDesk's design tokens and the window chrome every module shares (docs/design/design-system.md):
 * the module accents, the type sizes, the title row, the tab row, the ribbon card with its labelled
 * groups, and the status bar. The PicoTheme roles stay as they are; the app's own colours are the
 * named tokens here.
 */
object Aero {
    /** Pages' accent: Word blue. */
    val pages = Color(0xFF185ABD) // design-style: brand accent (Word)
    /** Sheets' accent: Excel green. */
    val sheets = Color(0xFF107C41) // design-style: brand accent (Excel)
    /** Slides' accent: PowerPoint red-orange. */
    val slides = Color(0xFFC43E1C) // design-style: brand accent (PowerPoint)
    /** The launcher's accent. */
    val desk = Color(0xFF3B5BDB) // design-style: brand accent (AeroDesk)
    /** Text drawn on white paper (gallery previews, page thumbnails). */
    val ink = Color(0xFF1F1F1F) // design-style: ink on paper

    // The surfaces on the window's glass are PICO's own Vibrant fills (the theme's fill roles are Vibrant-encoded:
    // fillPrimary Darker, fillSecondary SemiLight, fillTertiary Neutral, fillLight Light), so they lift from the glass
    // the way PICO's own apps do and follow the room — fixed whites and greys over glass read as stickers on it.
    /** A dialog's secondary action (Cancel): PICO's secondary button, the Neutral fill. */
    val neutral: Color @Composable get() = PicoTheme.colorScheme.fillTertiary
    /** An input's own fill, and a list box's (a drop-down, a spin box, a text box, a dialog's list): the Neutral fill, recessed. */
    val field: Color @Composable get() = PicoTheme.colorScheme.fillTertiary
    /** A context bar's card over the canvas: white, so it reads on a page and on a busy slide alike. */
    val bar = Color(0xFFFFFFFF) // design-style: design-surface context bar
    /** A context bar's hairline edge. */
    val barEdge = Color(0x24000000) // design-style: context bar edge (14 % black)
    /** The ribbon's card and the bands under it (formula bar): the Light fill, a large region lifted from the glass. */
    val chrome: Color @Composable get() = PicoTheme.colorScheme.fillLight
    /** A card raised on a lifted region (a pane's comment thread, a footnote): the SemiLight fill, one step lighter. */
    val card: Color @Composable get() = PicoTheme.colorScheme.fillSecondary
    /** The gap between the window's bands: the ribbon card, the formula bar, the canvas. */
    val bandGap = 8.dp
    /** A hyperlink's text (Office's Hyperlink colour). */
    val link = Color(0xFF0563C1) // design-style: link text
    /** A destructive command's ink in a context bar or menu. */
    val danger = Color(0xFFC42B1C) // design-style: destructive ink

    /** A checked or selected command's fill: the accent, faint. */
    fun tint(accent: Color) = accent.copy(alpha = 0.16f)

    // Sizes (dp) — the table in the design system.
    val titleRowHeight = 48.dp
    val tabRowHeight = 40.dp
    val ribbonContentHeight = 96.dp
    val commandHeight = 30.dp
    val commandRadius = 8.dp
    val barRadius = 12.dp
    val cardRadius = 16.dp
    val statusBarHeight = 36.dp
}

/** The module's accent (Word blue in Pages, Excel green in Sheets, PowerPoint red in Slides). */
val LocalAccent = staticCompositionLocalOf { Aero.desk }

/** The type sizes of the design system, on PicoTheme's body face. */
object AeroType {
    /** A full page's title (the start page's greeting, the backstage's New / Open / Export). */
    val pageTitle: TextStyle @Composable @ReadOnlyComposable get() = PicoTheme.typography.bodyMedium.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
    val title: TextStyle @Composable @ReadOnlyComposable get() = PicoTheme.typography.bodyMedium.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    val heading: TextStyle @Composable @ReadOnlyComposable get() = PicoTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val tab: TextStyle @Composable @ReadOnlyComposable get() = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val body: TextStyle @Composable @ReadOnlyComposable get() = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)
    val command: TextStyle @Composable @ReadOnlyComposable get() = PicoTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 16.sp)
    val caption: TextStyle @Composable @ReadOnlyComposable get() = PicoTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp)
    /** A small heading or field label: the caption size, semibold (PICO's labelMedium). */
    val label: TextStyle @Composable @ReadOnlyComposable get() = PicoTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
}

/**
 * The title row (Office's title bar): Home and New Window on the left, the document's name and
 * whether it is saved in the middle, and [trailing] — Undo, Redo, Save, Present — on the right.
 */
@Composable
internal fun AppTitleRow(
    title: String,
    state: String,
    onHome: () -> Unit,
    /** Office's Search beside the name ([TitleSearchBox]), when the app offers it. */
    search: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(Aero.titleRowHeight).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TitleIconButton("ic_home", androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.tip_aerodesk_home), onHome)
        run {
            val openWindow = LocalOpenSideWindow.current
            TitleIconButton("ic_window_new", androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.tip_new_window), { openWindow(null) })
        }
        Row(Modifier.weight(1f).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(title, style = AeroType.tab.copy(fontWeight = FontWeight.SemiBold), color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 420.dp))
            if (state.isNotEmpty()) Text("  ·  $state", style = AeroType.command, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
            if (search != null) { Spacer(Modifier.width(20.dp)); search() }
        }
        trailing()
    }
}

/** An icon command of the title row and the status bar: a 40 dp target with a 20 dp icon, its name on gaze. */
@Composable
internal fun TitleIconButton(icon: String, tooltip: String, onClick: () -> Unit, enabled: Boolean = true, selected: Boolean = false, size: androidx.compose.ui.unit.Dp = 40.dp) =
    Tooltip(tooltip) { a11y ->
        val interaction = remember { MutableInteractionSource() }
        val shape = RoundedCornerShape(10.dp)
        Box(
            contentAlignment = Alignment.Center,
            modifier = a11y
                .size(size)
                .clip(shape)
                .background(if (selected) Aero.tint(LocalAccent.current) else Color.Transparent)
                .then(if (enabled) Modifier.gazeTooltip(tooltip, cornerDp = 10f) else Modifier)
                .clickable(enabled = enabled, interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
                .controllerHapticFeedback(interactionSource = interaction),
        ) {
            if (enabled) GazeFill(shape)
            RibbonGlyph(icon, PicoTheme.colorScheme.labelPrimary.copy(alpha = if (enabled) 1f else 0.3f), 20.dp)
        }
    }

/** The ribbon's tab row: File first (it opens the backstage), then the tabs, contextual ones in the accent. */
@Composable
internal fun RibbonTabRow(trailing: @Composable RowScope.() -> Unit = {}, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(Aero.tabRowHeight).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            trailing()
            LocalRibbonFold.current?.let { RibbonDisplayOptions(it) }
        }
    }
}

/**
 * A command at the end of the tab row, as Office 365's Comments button there: an outlined pill with an icon and a name,
 * the accent's tint while what it opens is open ([selected]).
 */
@Composable
internal fun TabRowCommand(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.height(30.dp).clip(shape)
            .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (selected) accent.copy(alpha = 0.6f) else PicoTheme.colorScheme.dividerLine, shape)
            .semantics { this.selected = selected }
            .press(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RibbonGlyph(icon, if (selected) accent else PicoTheme.colorScheme.labelPrimary, 16.dp)
        Text(label, style = AeroType.command, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
    }
}

/**
 * Office's Ribbon Display Options: the ribbon always showing, or tabs only ([collapsed], kept per module) — then a click
 * on a tab shows its commands ([peek]) until the same tab is clicked again or the window below the ribbon is touched
 * ([ribbonPeekDismiss]). Ctrl+F1 switches ([ribbonShortcut]).
 */
@androidx.compose.runtime.Stable
internal class RibbonFold(private val tabsOnly: androidx.compose.runtime.MutableState<Boolean>) {
    var collapsed: Boolean
        get() = tabsOnly.value
        set(v) { tabsOnly.value = v; peek = false }
    var peek by mutableStateOf(false)
    /** Where the ribbon card ends, in the window: a touch below it puts a peeked ribbon away. */
    var cardBottom = Float.MAX_VALUE
    val shown get() = !collapsed || peek
    fun toggle() { collapsed = !collapsed }
    /** A tab clicked: with tabs only, its commands show, or go away when it was already showing. */
    fun tabClicked(alreadySelected: Boolean) { if (collapsed) peek = !(peek && alreadySelected) }
}

/** The window's ribbon display, per module ("pages", "slides", "sheets"); dev hooks `ribbontabsonly` and `ribbonpeek`
 *  start with tabs only (the latter with the Home tab's commands showing) without keeping it. */
@Composable
internal fun rememberRibbonFold(module: String): RibbonFold {
    val hook = devDialogProp()
    val flag = if (hook == "ribbontabsonly" || hook == "ribbonpeek") remember { mutableStateOf(true) } else rememberLayoutFlag("$module.ribbonTabsOnly")
    return remember(flag) { RibbonFold(flag).also { if (hook == "ribbonpeek") it.peek = true } }
}

internal val LocalRibbonFold = androidx.compose.runtime.staticCompositionLocalOf<RibbonFold?> { null }

/** Office's Ctrl+F1, for an app's root key handler: the ribbon between always showing and tabs only. */
internal fun ribbonShortcut(e: androidx.compose.ui.input.key.KeyEvent, fold: RibbonFold): Boolean {
    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || e.key != androidx.compose.ui.input.key.Key.F1) return false
    if (!e.isCtrlPressed && !e.isMetaPressed) return false
    fold.toggle(); return true
}

/** For an app's root: a touch below a peeked ribbon puts it away, as a click in the document does in Office. */
internal fun Modifier.ribbonPeekDismiss(fold: RibbonFold): Modifier = pointerInput(fold) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            if (fold.peek && e.type == androidx.compose.ui.input.pointer.PointerEventType.Press &&
                e.changes.any { it.position.y > fold.cardBottom }) fold.peek = false
        }
    }
}

/** The tab row's end: Office's Ribbon Display Options menu (always show the ribbon / show tabs only). */
@Composable
private fun RibbonDisplayOptions(fold: RibbonFold) {
    var open by remember { mutableStateOf(devDialogProp() == "ribbonmenu") }
    Box {
        TitleIconButton(if (fold.collapsed) "ic_chevron_down" else "ic_chevron_up",
            androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.ribbon_display_options), { open = true }, size = 30.dp)
        if (open) AnchoredFlyout({ open = false }, alignEnd = true) {
            FlyoutMenu(width = 320.dp) {
                MenuCommand(androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.ribbon_tabs_only), { fold.collapsed = true; open = false },
                    icon = "ic_chevron_up", checked = fold.collapsed, trailing = "Ctrl+F1",
                    detail = androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.ribbon_tabs_only_detail))
                MenuCommand(androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.ribbon_always), { fold.collapsed = false; open = false },
                    icon = "ic_chevron_down", checked = !fold.collapsed,
                    detail = androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.ribbon_always_detail))
            }
        }
    }
}

/**
 * The ribbon card: the one app-owned surface of the chrome, holding the selected tab's groups in a
 * row that scrolls sideways when the window is narrow.
 */
@Composable
internal fun RibbonCard(tab: Any? = null, content: @Composable RowScope.() -> Unit) {
    // Tabs only: no card unless a tab was clicked (Search's results show regardless).
    val fold = LocalRibbonFold.current
    if (fold != null && !fold.shown && tab != "search") return
    val scroll = rememberScrollState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val squeeze = remember { RibbonSqueeze() }
    // Another tab starts unsqueezed: its own commands decide.
    androidx.compose.runtime.LaunchedEffect(tab) { squeeze.level = 0 }
    val slackPx = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.roundToPx() }
    val realOverflow = scroll.maxValue > slackPx
    // Office's way with a narrow window: galleries show fewer choices first. While the commands still overflow, the
    // squeeze goes up a step (galleries read it) — judged after the last step has been laid out, so one step at a
    // time; a new window width or tab starts again from none.
    androidx.compose.runtime.LaunchedEffect(realOverflow, squeeze.level) {
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        if (scroll.maxValue > slackPx && squeeze.level < RibbonSqueeze.MAX) squeeze.level++
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Aero.bandGap)
            .chromeCard()
            .onSizeChanged { if (it.width != squeeze.width) { squeeze.width = it.width; squeeze.level = 0 } }
            .onGloballyPositioned { fold?.cardBottom = it.boundsInRoot().bottom },
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalRibbonSqueeze provides squeeze.level) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(scroll).padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.Top,
                content = content,
            )
        }
        // When a tab is wider than the window, a chevron at the edge says so and scrolls to the rest (Office collapses
        // groups instead; a scrolled-off group must at least be findable).
        // Only for a real overflow: a tab a few pixels wider than the window hides nothing but the card's end padding,
        // and the cue would then cover the last group instead.
        if (realOverflow && (scroll.canScrollForward || scroll.canScrollBackward)) Row(Modifier.matchParentSize(), verticalAlignment = Alignment.CenterVertically) {
            if (scroll.canScrollBackward) RibbonScrollCue("ic_chevron_left", androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.back)) {
                scope.launch { scroll.animateScrollTo(0) }
            }
            Spacer(Modifier.weight(1f))
            if (scroll.canScrollForward) RibbonScrollCue("ic_chevron_right", androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.ribbon_more)) {
                scope.launch { scroll.animateScrollTo(scroll.maxValue) }
            }
        }
    }
}

/** How far the ribbon has had to squeeze its galleries to fit the window: 0 none, up to [MAX]. */
private class RibbonSqueeze {
    var level by androidx.compose.runtime.mutableIntStateOf(0)
    var width = -1
    companion object { const val MAX = 3 }
}

/** The ribbon's squeeze level for its galleries (the Styles gallery shows a card fewer a step), 0 when all fits. */
internal val LocalRibbonSqueeze = androidx.compose.runtime.compositionLocalOf { 0 }

/** The ribbon's overflow chevron: a small round button on the chrome, at the edge where commands are scrolled off. */
@Composable
private fun RibbonScrollCue(icon: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 4.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Aero.chrome)
            .border(1.dp, PicoTheme.colorScheme.dividerLine, androidx.compose.foundation.shape.CircleShape),
    ) {
        TitleIconButton(icon, label, onClick, size = 28.dp)
    }
}

/**
 * One of the window's bands under the tabs — the ribbon, Sheets' formula bar — as its own card: near white over the
 * glass, rounded, a hairline edge, inset from the window's sides; so it never runs into what is under it.
 */
internal fun Modifier.chromeCard(): Modifier = composed {
    val shape = RoundedCornerShape(Aero.cardRadius)
    // design-style: design-surface ribbon-card (a Vibrant fill, no fixed colour over the glass)
    padding(horizontal = 12.dp).clip(shape).background(Aero.chrome).border(1.dp, PicoTheme.colorScheme.dividerLine, shape)
}

/**
 * A ribbon group (Office's Clipboard, Font, Paragraph…): its commands in the ribbon's height, its
 * [label] under them, and a divider after it. [last] leaves the divider off.
 */
@Composable
internal fun RibbonGroup(label: String, last: Boolean = false, launcher: (() -> Unit)? = null, launcherLabel: String = label, content: @Composable () -> Unit) {
    Row(Modifier.heightIn(min = Aero.ribbonContentHeight + 22.dp)) {
        // The commands over the group's name, both centred; with a [launcher], Office's dialog box launcher at the name
        // row's right end (the group's full dialog: Format Cells for Font, Alignment, Number…).
        androidx.compose.ui.layout.Layout(content = {
            Row(
                Modifier.height(Aero.ribbonContentHeight).padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) { content() }
            Text(
                label, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )
            if (launcher != null) Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).spatialHoverEffect()
                    .press(onClickLabel = launcherLabel, onClick = launcher).semantics { contentDescription = launcherLabel },
                contentAlignment = Alignment.Center,
            ) { RibbonGlyph("ic_dialog_launcher", PicoTheme.colorScheme.labelSecondary, 12.dp) }
        }) { parts, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val row = parts[0].measure(loose)
            val name = parts[1].measure(loose)
            val launch = parts.getOrNull(2)?.measure(loose)
            val w = maxOf(row.width, name.width + 2 * (launch?.width ?: 0))
            layout(w, row.height + name.height) {
                row.place((w - row.width) / 2, 0)
                name.place((w - name.width) / 2, row.height)
                launch?.place(w - launch.width, row.height + (name.height - launch.height) / 2)
            }
        }
        if (!last) VerticalDivider(Modifier.padding(horizontal = 6.dp, vertical = 8.dp).height(Aero.ribbonContentHeight + 4.dp))
    }
}

/** A column of stacked small commands (Office's three rows), evenly spread over the ribbon's height. */
@Composable
internal fun RibbonStack(content: @Composable () -> Unit) {
    Column(Modifier.height(Aero.ribbonContentHeight), verticalArrangement = Arrangement.SpaceEvenly) { content() }
}

/** A row of icon commands inside a stack (Bold, Italic, Underline…). */
@Composable
internal fun RibbonRow(content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

/** The status bar: [start] (page, words, language…) on the left, [end] (views, zoom) on the right. */
@Composable
internal fun AeroStatusBar(start: @Composable RowScope.() -> Unit, end: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(Aero.statusBarHeight).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), content = start)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), content = end)
    }
}

/** A status bar item: caption text in labelSecondary. */
@Composable
internal fun StatusText(text: String, modifier: Modifier = Modifier) =
    Text(text, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)

/**
 * A status bar item that opens something, as Office's do (Word's page number opens Go To, its word count Word Count):
 * the same caption, the system's gaze highlight and a press.
 */
@Composable
internal fun StatusLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    Text(text, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).spatialHoverEffect().press(onClick = onClick).padding(horizontal = 6.dp, vertical = 3.dp))

/**
 * Office's zoom control at the status bar's end: − / a slider with 100 % at its middle / + / the level.
 * [onSet] takes a level in percent ([min]..[max]; − and + move to the next ten); [onPercent] is the
 * level's own click (Word's Zoom dialog: here 100 % or the zoom menu).
 */
@Composable
internal fun StatusZoom(percent: Int, min: Int, max: Int, onSet: (Int) -> Unit, onPercent: () -> Unit, outLabel: String, inLabel: String) {
    TitleIconButton("ic_minus", outLabel, { onSet(((percent - 1) / 10 * 10).coerceIn(min, max)) }, enabled = percent > min, size = 28.dp)
    val zoomLabel = androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.zoom_level)
    com.pico.spatial.ui.design.Slider(
        value = ZoomScale.fraction(percent, min, max),
        onValueChange = { f -> onSet(ZoomScale.percent(f, min, max)) },
        modifier = Modifier.width(112.dp).semantics { contentDescription = "$zoomLabel $percent%" },
        sliderSpec = com.pico.spatial.ui.design.SliderDefaults.Small,
    )
    TitleIconButton("ic_plus", inLabel, { onSet((percent / 10 * 10 + 10).coerceIn(min, max)) }, enabled = percent < max, size = 28.dp)
    Box(
        Modifier.widthIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
            .semantics { contentDescription = "$zoomLabel $percent%" }
            .press(onClick = onPercent).padding(horizontal = 4.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { Text("$percent%", style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary) }
}

/** The zoom slider's scale, as Office's: 100 % at the middle, [min] at the left end, [max] at the right, linear on each side. */
internal object ZoomScale {
    fun fraction(percent: Int, min: Int, max: Int): Float = when {
        percent <= 100 -> 0.5f * (percent - min).coerceAtLeast(0) / (100 - min).coerceAtLeast(1)
        else -> 0.5f + 0.5f * (percent - 100).coerceAtMost(max - 100) / (max - 100).coerceAtLeast(1)
    }

    /** The level at slider position [f] — snapping to 100 % near the middle, to whole percents elsewhere. */
    fun percent(f: Float, min: Int, max: Int): Int = when {
        kotlin.math.abs(f - 0.5f) < 0.02f -> 100
        f < 0.5f -> Math.round(min + (100 - min) * (f / 0.5f))
        else -> Math.round(100 + (max - 100) * ((f - 0.5f) / 0.5f))
    }.coerceIn(min, max)
}

/** A view switch at the status bar's end (Word's Print Layout / Web Layout, PowerPoint's Normal / Slide Sorter…). */
@Composable
internal fun StatusView(icon: String, label: String, selected: Boolean, onClick: () -> Unit) =
    TitleIconButton(icon, label, onClick, selected = selected, size = 28.dp)

// --- Flyouts: a dropdown's panel opens at its command, in a spatial popup -----------------------

/** Where the last ribbon command pressed starts (window px): its flyout opens there. */
class FlyoutAnchor {
    var x by androidx.compose.runtime.mutableIntStateOf(0)
}

val LocalFlyoutAnchor = staticCompositionLocalOf<FlyoutAnchor?> { null }

/**
 * A command's click that first notes where the command is, so a flyout it opens appears under it.
 * Returns the modifier that tracks the position and the click to give `clickable`.
 */
@Composable
internal fun anchoredClick(onClick: () -> Unit): Pair<Modifier, () -> Unit> {
    val anchor = LocalFlyoutAnchor.current ?: return Modifier to onClick
    val left = remember { IntArray(1) }
    return Modifier.onGloballyPositioned { left[0] = it.boundsInWindow().left.toInt() } to { anchor.x = left[0]; onClick() }
}

/** The popup's left edge: at [x] (window px), kept inside the anchor's width. */
private data class AtX(val x: Int) : com.pico.spatial.ui.design.windows.popup.HorizontalPlacement {
    override fun calculatePosition(
        density: androidx.compose.ui.unit.Density,
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): Int = x.coerceAtMost(anchorBounds.right - popupContentSize.width).coerceAtLeast(anchorBounds.left)
}

/**
 * A ribbon flyout (Office's gallery or option panel under a dropdown): the system's glass popup,
 * placed under the ribbon at the command that opened it. Clicking outside dismisses it.
 */
@Composable
internal fun RibbonFlyout(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val x = LocalFlyoutAnchor.current?.x ?: 0
    com.pico.spatial.ui.design.windows.SpatialPopup(
        onDismissRequest = onDismiss,
        popupPositionProvider = com.pico.spatial.ui.design.windows.rememberSpatialPopupPositionProvider(
            AtX(x), com.pico.spatial.ui.design.windows.popup.VerticalPlacement.below(8.dp),
        ),
        cornerRadius = 16.dp,
        defaultMinWidth = androidx.compose.ui.unit.Dp.Unspecified,
    ) {
        // Gaze names are drawn by the window's overlay, in window coordinates: none in a popup.
        androidx.compose.runtime.CompositionLocalProvider(LocalTooltipHost provides null) {
            Box(Modifier.padding(16.dp).widthIn(max = 980.dp)) { content() }
        }
    }
}

// --- Flyout menus: Office's dropdown lists, on the system's menu item ------------------------------

/**
 * A dropdown's list of commands (Office's Change Case, Insert ▾, Freeze Panes ▾…), [width] wide,
 * scrolling past [maxHeight]. Fill it with [MenuCommand], [MenuHeading] and [MenuDivider].
 */
@Composable
internal fun FlyoutMenu(
    width: androidx.compose.ui.unit.Dp = 280.dp,
    maxHeight: androidx.compose.ui.unit.Dp = 520.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        Modifier.width(width).heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
        content = content,
    )
}

/**
 * One command of a [FlyoutMenu], on the system's menu item: its [icon] (or a tick when [checked]
 * and it has none), its [label] and an optional second line [detail] (Office's descriptions under
 * Page Break, Column…), and [trailing] text such as a shortcut. A command that cannot run now is
 * dimmed and does nothing.
 */
@Composable
internal fun MenuCommand(
    label: String,
    onClick: () -> Unit,
    icon: String? = null,
    checked: Boolean = false,
    enabled: Boolean = true,
    detail: String? = null,
    trailing: String = "",
    leading: (@Composable () -> Unit)? = null,
) {
    val ink = PicoTheme.colorScheme.labelPrimary
    com.pico.spatial.ui.design.menu.MenuItem(
        title = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.fillMaxWidth()
            .then(if (enabled) Modifier else Modifier.alpha(0.35f))
            .then(if (checked) Modifier.clip(RoundedCornerShape(12.dp)).background(Aero.tint(LocalAccent.current)) else Modifier),
        subtitle = detail?.let { { Text(it, maxLines = 2) } },
        onClick = { if (enabled) onClick() },
        leadingIcon = when {
            leading != null -> ({ Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { leading() } })
            icon != null -> ({ RibbonGlyph(icon, ink, 20.dp) })
            checked -> ({ RibbonGlyph("ic_check", ink, 20.dp) })
            else -> ({ Box(Modifier.size(20.dp)) })
        },
        trailingIcon = when {
            (icon != null || leading != null) && checked -> ({ RibbonGlyph("ic_check", ink, 18.dp) })
            trailing.isNotEmpty() -> ({ Text(trailing, color = PicoTheme.colorScheme.labelSecondary) })
            else -> null
        },
    )
}

/**
 * A tick of a [FlyoutMenu] that turns something on or off (Word's Show Markup ▾): Office's square box before its [label],
 * [leading] after the box (a reviewer's avatar), no selection tint — several can be on at once. The menu stays open.
 */
@Composable
internal fun MenuToggle(label: String, on: Boolean, onToggle: () -> Unit, enabled: Boolean = true, leading: (@Composable () -> Unit)? = null) {
    val accent = LocalAccent.current
    val ring = PicoTheme.colorScheme.labelTertiary
    com.pico.spatial.ui.design.menu.MenuItem(
        title = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.fillMaxWidth()
            .semantics { role = androidx.compose.ui.semantics.Role.Checkbox; toggleableState = androidx.compose.ui.state.ToggleableState(on) }
            .then(if (enabled) Modifier else Modifier.alpha(0.35f)),
        onClick = { if (enabled) onToggle() },
        leadingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckBoxMark(on, accent, ring, 18.dp)
                leading?.invoke()
            }
        },
    )
}

/** A section heading in a [FlyoutMenu] (Office's grey band: Theme Fonts, Cell Size…). */
@Composable
internal fun MenuHeading(text: String) = Text(
    text, style = AeroType.caption.copy(fontWeight = FontWeight.SemiBold), color = PicoTheme.colorScheme.labelSecondary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
)

/** A line between the groups of a [FlyoutMenu]. */
@Composable
internal fun MenuDivider() = com.pico.spatial.ui.design.HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

// --- Office's colour palette -----------------------------------------------------------------------

/** The Office theme's ten colours (the top row of every colour menu) and their tints and shades. */
internal object OfficePalette {
    val THEME = listOf(0xFFFFFFFF, 0xFF000000, 0xFFE7E6E6, 0xFF44546A, 0xFF4472C4, 0xFFED7D31, 0xFFA5A5A5, 0xFFFFC000, 0xFF5B9BD5, 0xFF70AD47)
    val STANDARD = listOf(0xFFC00000, 0xFFFF0000, 0xFFFFC000, 0xFFFFFF00, 0xFF92D050, 0xFF00B050, 0xFF00B0F0, 0xFF0070C0, 0xFF002060, 0xFF7030A0)
    /** Word's highlighter colours. */
    val HIGHLIGHT = listOf(0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFFFF00FF, 0xFF0000FF, 0xFFFF0000, 0xFF000080, 0xFF008080, 0xFF008000, 0xFF800080, 0xFF800000, 0xFF808000, 0xFF808080, 0xFFC0C0C0, 0xFF000000)

    /** The five variants under theme colour [c], lightest first — Office's "Lighter 80 %…Darker 50 %" (for white: darker 5…50 %; black: lighter 50…5 %). */
    fun variants(c: Long): List<Long> {
        val r = ((c shr 16) and 0xFF).toInt(); val g = ((c shr 8) and 0xFF).toInt(); val b = (c and 0xFF).toInt()
        fun tint(f: Float) = rgb(r + (255 - r) * f, g + (255 - g) * f, b + (255 - b) * f)
        fun shade(f: Float) = rgb(r * (1 - f), g * (1 - f), b * (1 - f))
        val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255
        return when {
            c == 0xFFFFFFFF -> listOf(shade(0.05f), shade(0.15f), shade(0.25f), shade(0.35f), shade(0.5f))
            c == 0xFF000000 -> listOf(tint(0.5f), tint(0.35f), tint(0.25f), tint(0.15f), tint(0.05f))
            lum > 0.85 -> listOf(shade(0.1f), shade(0.25f), shade(0.5f), shade(0.75f), shade(0.9f))
            lum < 0.2 -> listOf(tint(0.9f), tint(0.75f), tint(0.5f), tint(0.25f), tint(0.1f))
            else -> listOf(tint(0.8f), tint(0.6f), tint(0.4f), shade(0.25f), shade(0.5f))
        }
    }

    private fun rgb(r: Float, g: Float, b: Float): Long =
        0xFF000000 or (r.toInt().coerceIn(0, 255).toLong() shl 16) or (g.toInt().coerceIn(0, 255).toLong() shl 8) or b.toInt().coerceIn(0, 255).toLong()
}

/**
 * Office's colour menu: Theme Colors (ten columns with their tints and shades), Standard Colors,
 * and [noneLabel] (Automatic, No Color) when given. [current] is outlined.
 */
@Composable
internal fun ColorPalette(current: Long?, onPick: (Long?) -> Unit, noneLabel: String? = null, highlight: Boolean = false) {
    val cell = 26.dp
    Column(Modifier.width(cell * 10 + 4.dp * 9 + 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (noneLabel != null) MenuCommand(noneLabel, { onPick(null) }, checked = current == null)
        if (highlight) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OfficePalette.HIGHLIGHT.forEach { c -> PaletteCell(c, current == c, cell) { onPick(c) } }
            }
            return@Column
        }
        MenuHeading(androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.theme_colors))
        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OfficePalette.THEME.forEach { base ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    PaletteCell(base, current == base, cell) { onPick(base) }
                    Box(Modifier.height(3.dp))
                    OfficePalette.variants(base).forEach { v -> PaletteCell(v, current == v, cell) { onPick(v) } }
                }
            }
        }
        MenuHeading(androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.standard_colors))
        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OfficePalette.STANDARD.forEach { c -> PaletteCell(c, current == c, cell) { onPick(c) } }
        }
    }
}

/** One square of the [ColorPalette]: the colour, a faint edge so white shows, the accent ring when it is the current one. */
@Composable
private fun PaletteCell(color: Long, selected: Boolean, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val name = androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.a11y_color, String.format(java.util.Locale.ROOT, "#%06X", color and 0xFFFFFF))
    val accent = LocalAccent.current
    Box(
        Modifier.size(size)
            .semantics { contentDescription = name }
            .then(if (selected) Modifier.border(2.dp, accent, RoundedCornerShape(3.dp)) else Modifier)
            .padding(if (selected) 3.dp else 0.dp)
            .clip(RoundedCornerShape(2.dp))
            .spatialHoverEffect(style = SpatialHoverStyle.Highlight)
            .background(Color(color))
            .border(1.dp, Color(0x1F000000), RoundedCornerShape(2.dp)) // design-style: swatch edge so white reads
            .press(onClick = onClick),
    )
}

// --- Dialogs: Office's dialog frame on the system button ------------------------------------------

/**
 * A dialog's action (OK, Cancel, Insert…) on SpatialUI's button. The [primary] one — the default action
 * of the dialog — is filled with the module accent, the others are the system's plain button.
 */
@Composable
internal fun DialogButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, danger: Boolean = false, small: Boolean = false) {
    com.pico.spatial.ui.design.Button(
        onClick = onClick,
        modifier = modifier.widthIn(min = if (small) 64.dp else 96.dp),
        enabled = enabled,
        size = if (small) com.pico.spatial.ui.design.ButtonDefaults.Small else com.pico.spatial.ui.design.ButtonDefaults.Regular,
        colors = if (primary || danger) com.pico.spatial.ui.design.ButtonDefaults.buttonColors(
            containerColor = if (danger) Aero.danger else LocalAccent.current, contentColor = PicoTheme.colorScheme.labelPrimaryLight,
        ) else com.pico.spatial.ui.design.ButtonDefaults.buttonColors(
            containerColor = Aero.neutral, contentColor = PicoTheme.colorScheme.labelPrimary,
        ),
    ) { Text(text, maxLines = 1) }
}

/** A dialog's tabs (Office's Margins / Paper / Layout…): SpatialUI's segmented control, [width] wide. */
@Composable
internal fun <T> DialogTabs(tabs: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit, width: androidx.compose.ui.unit.Dp = 520.dp) {
    com.pico.spatial.ui.design.SegmentControl(Modifier.width(width)) {
        tabs.forEach { t ->
            SegmentItem(selected = t == selected, onClick = { onSelect(t) }, title = { Text(label(t), maxLines = 1) })
        }
    }
}

// --- Task panes: Office's panes beside the document ---------------------------------------------

/**
 * A task pane's head (Office's Navigation, Comments, Animation Pane…): its title, [actions] before the
 * close button, and the close button when the pane can close itself.
 */
@Composable
internal fun PaneHeader(title: String, onClose: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = AeroType.heading, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
        if (onClose != null) TitleIconButton("ic_close", androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.close), onClose, size = 36.dp)
    }
}

/**
 * The strip over a side window that holds several task panes (Find, Comments, Footnotes…): SpatialUI's segmented
 * control naming each, the one in [front] shown below it at full height.
 */
@Composable
internal fun <T> PaneSwitcher(panes: List<T>, front: T, onPick: (T) -> Unit, label: @Composable (T) -> String) {
    com.pico.spatial.ui.design.SegmentControl(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
        panes.forEach { p -> SegmentItem(selected = p == front, onClick = { onPick(p) }, title = { Text(label(p), maxLines = 1) }) }
    }
}

/** A heading inside a task pane (Comments, Changes…). */
@Composable
internal fun PaneSection(text: String, modifier: Modifier = Modifier) =
    Text(text, style = AeroType.tab.copy(fontWeight = FontWeight.SemiBold), color = PicoTheme.colorScheme.labelSecondary, modifier = modifier.padding(top = 8.dp))

/**
 * A card in a task pane — a comment thread, a footnote, a source, a change — as Office's panes draw them: white with a
 * hairline edge; [selected] (the one the reader is on) framed in the module accent. [onClick] makes the whole card pick it.
 */
@Composable
internal fun PaneCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier.fillMaxWidth().clip(shape).background(Aero.card)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) LocalAccent.current else Color.Transparent, shape)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
                .controllerHapticFeedback(interactionSource = interaction) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** A person's initials in a circle of a colour of their own (Office's comment and presence avatars). */
@Composable
internal fun Avatar(name: String, size: androidx.compose.ui.unit.Dp = 28.dp) {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val initials = when {
        words.isEmpty() -> "?"
        words.first().first().let { Character.isIdeographic(it.code) } -> words.first().take(1)
        else -> words.take(2).joinToString("") { it.take(1).uppercase() }
    }
    val colors = listOf(0xFF2B579A, 0xFF217346, 0xFFB7472A, 0xFF7719AA, 0xFF038387, 0xFFC239B3, 0xFF8764B8, 0xFFCA5010)
    val color = Color(colors[Math.floorMod(name.trim().hashCode(), colors.size)])
    Box(Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(initials, color = Color.White, style = AeroType.caption.copy(fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.42f).sp), maxLines = 1)
    }
}

/** A pane with nothing in it yet: its icon in a soft circle and a line of what will show here (Office's empty states). */
@Composable
internal fun PaneEmpty(icon: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(56.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Aero.tint(LocalAccent.current)), contentAlignment = Alignment.Center) {
            RibbonGlyph(icon, LocalAccent.current, 26.dp)
        }
        Text(text, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, textAlign = TextAlign.Center)
    }
}

/**
 * Office's message bar: a warning's pale yellow strip with its icon and a line or two of [text], then [actions] (buttons)
 * and, with [onClose], a close button. [rounded] for one inside a dialog; flush under the ribbon otherwise.
 */
@Composable
internal fun MessageBar(
    text: String,
    modifier: Modifier = Modifier,
    rounded: Boolean = false,
    onClose: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().then(if (rounded) Modifier.clip(RoundedCornerShape(10.dp)) else Modifier)
            .background(Color(0xFFFFF4CE)).padding(start = 14.dp, end = if (onClose != null) 4.dp else 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RibbonGlyph("ic_warning", Color(0xFF8A6100), 18.dp)
        Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = AeroType.caption, color = Color(0xFF323130), modifier = Modifier.weight(1f))
        actions()
        if (onClose != null) TitleIconButton("ic_close", androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.close), onClose, size = 32.dp)
    }
}

/**
 * Office's colour drop-down (Font Color, Fill in dialogs): a field showing the colour — or [noneLabel] for none — and a
 * caret; it opens the Office palette under it.
 */
@Composable
internal fun ColorField(current: Long?, onPick: (Long?) -> Unit, noneLabel: String, description: String, width: androidx.compose.ui.unit.Dp = 120.dp) {
    var open by remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        val interaction = remember { MutableInteractionSource() }
        val shape = RoundedCornerShape(Aero.commandRadius)
        Row(
            Modifier.width(width).height(32.dp).clip(shape).background(Aero.field)
                .semantics { contentDescription = description }
                .clickable(interactionSource = interaction, indication = LocalIndication.current) { open = !open }
                .controllerHapticFeedback(interactionSource = interaction).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (current != null) Box(Modifier.size(28.dp, 18.dp).clip(RoundedCornerShape(4.dp)).background(Color(current)).border(1.dp, Aero.barEdge, RoundedCornerShape(4.dp)))
            else Text(noneLabel, style = AeroType.command, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Box(Modifier.weight(1f))
            DropCaret(PicoTheme.colorScheme.labelSecondary)
        }
        if (open) AnchoredFlyout(onDismiss = { open = false }) {
            ColorPalette(current, { c -> onPick(c); open = false }, noneLabel = noneLabel)
        }
    }
}

/**
 * A text box in the app's look: the field grey of drop-downs and spin boxes, rounded, an accent ring while it has the
 * keyboard, and [placeholder] while it is empty — for numbers, references, short notes ([minLines] for longer ones; a
 * [maxLines] above one lets a one-line box grow as the text wraps).
 */
@Composable
internal fun InputBox(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    description: String? = null,
    minLines: Int = 1,
    maxLines: Int = if (minLines > 1) 8 else 1,
    monospace: Boolean = false,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    /** A password: shown as dots, the keyboard's password layout (no suggestions). */
    password: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Aero.commandRadius)
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onChange, singleLine = maxLines <= 1, minLines = minLines, maxLines = maxLines,
        keyboardOptions = if (password) keyboardOptions.copy(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false) else keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        interactionSource = interaction,
        textStyle = AeroType.body.copy(color = PicoTheme.colorScheme.labelPrimary,
            fontFamily = if (monospace) androidx.compose.ui.text.font.FontFamily.Monospace else null),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalAccent.current),
        modifier = modifier.heightIn(min = 32.dp).clip(shape).background(Aero.field)
            .border(if (focused) 1.5.dp else 0.dp, if (focused) LocalAccent.current else Color.Transparent, shape)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder != null) Text(placeholder, style = AeroType.body, color = PicoTheme.colorScheme.labelTertiary, maxLines = maxLines)
                inner()
            }
        },
    )
}

/** [InputBox] for a value that keeps its selection (text put in and selected for editing). */
@Composable
internal fun InputBox(
    value: androidx.compose.ui.text.input.TextFieldValue,
    onChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    minLines: Int = 1,
    maxLines: Int = if (minLines > 1) 8 else 1,
    monospace: Boolean = false,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Aero.commandRadius)
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onChange, singleLine = maxLines <= 1, minLines = minLines, maxLines = maxLines,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        interactionSource = interaction,
        textStyle = AeroType.body.copy(color = PicoTheme.colorScheme.labelPrimary,
            fontFamily = if (monospace) androidx.compose.ui.text.font.FontFamily.Monospace else null),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalAccent.current),
        modifier = modifier.heightIn(min = 32.dp).clip(shape).background(Aero.field)
            .border(if (focused) 1.5.dp else 0.dp, if (focused) LocalAccent.current else Color.Transparent, shape)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

/**
 * A SideNavigationItem's row contract, as PICO's templates (and the VideoPlayer app) give it: a little air between the
 * pills, the item's own 24 dp pill clipping the hover highlight and the press, the system hover and controller haptics.
 * SideNavigationItem draws the row and its selected fill but takes its click (and hover) from the caller.
 */
internal fun Modifier.sideNavRow(onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    padding(vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .spatialHoverEffect()
        .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
        .controllerHapticFeedback(interactionSource = interaction)
}

/**
 * A custom control's click as PICO asks for one: the press indication and the controller's haptic tick ([onClickLabel]
 * names the action for screen readers). Built-ins (Button, IconButton, MenuItem…) already do this themselves.
 */
internal fun Modifier.press(enabled: Boolean = true, onClickLabel: String? = null, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, onClickLabel = onClickLabel, onClick = onClick)
        .controllerHapticFeedback(interactionSource = interaction)
}

/** A task pane's own surface: the system's thick glass over a light fill, and its padding. */
internal fun Modifier.paneSurface(): Modifier = composed {
    // design-style: design-surface task pane — the Light fill only: a Subwindow already has the system's glass, and
    // a second material over it would blur twice.
    background(PicoTheme.colorScheme.fillLight).padding(horizontal = 16.dp, vertical = 14.dp)
}

// --- Context bars ---------------------------------------------------------------------------------

/**
 * The card of every context bar and canvas popover (Office's mini toolbar, Word's Layout Options):
 * white with a hairline edge and a soft shadow. (Not the system glass: over a white page it reads grey.)
 */
internal fun Modifier.barSurface(): Modifier {
    val shape = RoundedCornerShape(Aero.barRadius)
    return shadow(6.dp, shape, ambientColor = Color(0x22000000), spotColor = Color(0x22000000))
        .clip(shape).background(Aero.bar)
        .border(androidx.compose.foundation.BorderStroke(1.dp, Aero.barEdge), shape)
}

/** A context bar laid out where it stands: a row of 30 dp commands on [barSurface]. */
@Composable
internal fun ContextBar(description: String, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.barSurface().semantics { contentDescription = description }.padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Word's Focus, the one thing left above the page: a quiet pill on the window's glass with the document's [title] and
 * [detail] (its word count), and Exit Focus. The gaze lifts it, as the system's own controls; Esc exits too.
 */
@Composable
internal fun FocusBar(title: String, detail: String, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.clip(shape).background(Aero.chrome).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = AeroType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 280.dp))
        if (detail.isNotEmpty()) StatusText(detail)
        val exit = androidx.compose.ui.res.stringResource(com.therealityteam.aeropages.R.string.focus_exit)
        Row(
            Modifier.clip(RoundedCornerShape(16.dp)).background(Aero.neutral).spatialHoverEffect()
                .press(onClickLabel = exit, onClick = onExit).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RibbonGlyph("ic_focus_exit", PicoTheme.colorScheme.labelPrimary, 16.dp)
            Text(exit, style = AeroType.caption, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
        }
    }
}

/** The rule between a context bar's groups of commands. */
@Composable
internal fun BarDivider() = Box(Modifier.padding(horizontal = 3.dp).width(1.dp).height(18.dp).background(Aero.neutral))

// --- Spin box -------------------------------------------------------------------------------------

/**
 * Office's spin box: − and + inside one field around [content] (the value, or a field to type it in).
 * [onStep] gets −1 or +1; a side that cannot go further is dimmed. [description] names what is stepped
 * for screen readers ("Spacing before −").
 */
@Composable
internal fun SpinFrame(
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    description: String = "",
    canLess: Boolean = true,
    canMore: Boolean = true,
    compact: Boolean = false,
    lessLabel: String? = null,
    moreLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Aero.commandRadius)
    Row(
        modifier.height(if (compact) 26.dp else 32.dp).clip(shape).background(Aero.field),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpinButton("ic_minus", lessLabel ?: "$description −".trim(), canLess, compact) { onStep(-1) }
        content()
        SpinButton("ic_plus", moreLabel ?: "$description +".trim(), canMore, compact) { onStep(1) }
    }
}

/** A [SpinFrame] showing [value] as text, at least [valueWidth] wide ([dim]: a value that does not apply now). */
@Composable
internal fun SpinBox(
    value: String,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    description: String = "",
    canLess: Boolean = true,
    canMore: Boolean = true,
    valueWidth: androidx.compose.ui.unit.Dp = 48.dp,
    compact: Boolean = false,
    dim: Boolean = false,
    lessLabel: String? = null,
    moreLabel: String? = null,
) = SpinFrame(onStep, modifier, description, canLess, canMore, compact, lessLabel, moreLabel) {
    Text(
        value, style = if (compact) AeroType.caption else AeroType.command, maxLines = 1,
        color = if (dim) PicoTheme.colorScheme.labelTertiary else PicoTheme.colorScheme.labelPrimary,
        textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = valueWidth).padding(horizontal = 2.dp),
    )
}

@Composable
private fun SpinButton(icon: String, description: String, enabled: Boolean, compact: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Aero.commandRadius)
    Box(
        Modifier.size(if (compact) 26.dp else 32.dp).clip(shape)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .controllerHapticFeedback(interactionSource = interaction),
        contentAlignment = Alignment.Center,
    ) {
        if (enabled) GazeFill(shape)
        RibbonGlyph(icon, PicoTheme.colorScheme.labelPrimary.copy(alpha = if (enabled) 1f else 0.3f), if (compact) 14.dp else 16.dp)
    }
}

// --- Canvas handles -------------------------------------------------------------------------------

/** A sizing handle's dot, as Office draws one: white, with a ring in the selection colour and a soft shadow. */
@Composable
internal fun HandleDot(size: androidx.compose.ui.unit.Dp = 11.dp) = Box(
    Modifier.size(size).shadow(1.dp, androidx.compose.foundation.shape.CircleShape)
        .background(Color.White, androidx.compose.foundation.shape.CircleShape)
        .border(1.5.dp, PicoTheme.colorScheme.interaction, androidx.compose.foundation.shape.CircleShape),
)

// --- Choices in dialogs ---------------------------------------------------------------------------

/**
 * Office's check box: SpatialUI's [com.pico.spatial.ui.design.Checkbox] and its label, the whole row
 * toggling it. [small] is a ribbon's size, three to a column.
 */
@Composable
internal fun CheckOption(label: String, on: Boolean, toggle: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false) {
    val accent = LocalAccent.current
    val ring = PicoTheme.colorScheme.labelTertiary
    Row(
        modifier.clip(RoundedCornerShape(Aero.commandRadius))
            .semantics { role = androidx.compose.ui.semantics.Role.Checkbox; toggleableState = androidx.compose.ui.state.ToggleableState(on) }
            .then(if (enabled) Modifier.press(onClick = toggle) else Modifier.alpha(com.pico.spatial.ui.design.LocalDisableAlpha.current))
            .padding(horizontal = 4.dp, vertical = if (small) 2.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (small) 6.dp else 10.dp),
    ) {
        CheckBoxMark(on, accent, ring, if (small) 16.dp else 20.dp)
        Text(label, style = if (small) AeroType.caption else AeroType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
    }
}

/** Office's square check box (PICO's own is round, which reads as an option button among them): filled and ticked when [on]. */
@Composable
private fun CheckBoxMark(on: Boolean, accent: Color, ring: Color, size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val inset = 1.dp.toPx()
        val box = androidx.compose.ui.geometry.Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
        val corner = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
        val at = androidx.compose.ui.geometry.Offset(inset, inset)
        if (on) {
            drawRoundRect(accent, at, box, corner)
            val w = this.size.width
            val tick = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.27f, w * 0.52f); lineTo(w * 0.43f, w * 0.68f); lineTo(w * 0.74f, w * 0.34f) }
            drawPath(tick, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        } else {
            drawRoundRect(ring, at, box, corner, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        }
    }
}

/** Office's option button: one choice of several (a drawn radio, SpatialUI has none), the whole row picking it; [detail] a line under it. */
@Composable
internal fun RadioOption(label: String, on: Boolean, pick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, detail: String? = null) {
    val accent = LocalAccent.current
    val ring = PicoTheme.colorScheme.labelTertiary
    Row(
        modifier.clip(RoundedCornerShape(Aero.commandRadius))
            .semantics { role = androidx.compose.ui.semantics.Role.RadioButton; selected = on }
            .then(if (enabled) Modifier.press(onClick = pick) else Modifier.alpha(com.pico.spatial.ui.design.LocalDisableAlpha.current))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = if (detail != null) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.foundation.Canvas(Modifier.padding(top = if (detail != null) 1.dp else 0.dp).size(20.dp)) {
            val r = size.minDimension / 2
            drawCircle(if (on) accent else ring, r - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            if (on) drawCircle(accent, r * 0.45f)
        }
        Column {
            Text(label, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
            if (detail != null) Text(detail, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
        }
    }
}

/** A short set of exclusive choices (two to five) as SpatialUI's segmented control, [width] wide. */
@Composable
internal fun <T> ChoiceSegments(options: List<T>, selected: T?, label: @Composable (T) -> String, onPick: (T) -> Unit, width: androidx.compose.ui.unit.Dp) {
    com.pico.spatial.ui.design.SegmentControl(Modifier.width(width)) {
        options.forEach { o -> SegmentItem(selected = o == selected, onClick = { onPick(o) }, title = { Text(label(o), maxLines = 1) }) }
    }
}

/** A popup's place: its left edge on its anchor's. */
private object AtAnchorStart : com.pico.spatial.ui.design.windows.popup.HorizontalPlacement {
    override fun calculatePosition(
        density: androidx.compose.ui.unit.Density,
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): Int = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
}

/** The flyout's end at its anchor's end (for an anchor at the window's right edge). */
private object AtAnchorEnd : com.pico.spatial.ui.design.windows.popup.HorizontalPlacement {
    override fun calculatePosition(
        density: androidx.compose.ui.unit.Density,
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): Int = (anchorBounds.right - popupContentSize.width).coerceAtLeast(0)
}

/**
 * Office's drop-down box in a dialog (Font, Size, Paper size, Sort by…): the current choice in a field
 * with a caret; a click opens the [options] as a menu under it, the [selected] one ticked.
 * Pass [label] as a lambda literal: a plain `(T) -> String` value given for this composable type
 * compiles but throws ClassCastException when the menu opens.
 */
@Composable
internal fun <T> ComboField(
    value: String,
    options: List<T>,
    label: @Composable (T) -> String,
    selected: T?,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 200.dp,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    var open by remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(modifier.width(width)) {
        Row(
            Modifier.fillMaxWidth().height(if (compact) 26.dp else 32.dp).clip(RoundedCornerShape(Aero.commandRadius)).background(Aero.field)
                .then(if (enabled) Modifier.press { open = true } else Modifier.alpha(com.pico.spatial.ui.design.LocalDisableAlpha.current))
                .semantics { contentDescription = value }
                .padding(horizontal = if (compact) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = if (compact) AeroType.caption else AeroType.command, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            DropCaret(PicoTheme.colorScheme.labelSecondary, Modifier.padding(start = 8.dp), 9.dp)
        }
        if (open) com.pico.spatial.ui.design.windows.SpatialPopup(
            onDismissRequest = { open = false },
            popupPositionProvider = com.pico.spatial.ui.design.windows.rememberSpatialPopupPositionProvider(
                AtAnchorStart, com.pico.spatial.ui.design.windows.popup.VerticalPlacement.below(4.dp),
            ),
            cornerRadius = 16.dp,
            defaultMinWidth = androidx.compose.ui.unit.Dp.Unspecified,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalTooltipHost provides null) {
                Box(Modifier.padding(vertical = 8.dp)) {
                    FlyoutMenu(width = maxOf(width, 180.dp), maxHeight = 360.dp) {
                        options.forEach { o -> MenuCommand(label(o), { onPick(o); open = false }, checked = o == selected) }
                    }
                }
            }
        }
    }
}

/**
 * The width a column of dialog [labels] needs in the reader's language: its widest label on one line, kept within [min]
 * and [max] — but never narrower than its longest single word, so a label wraps between words (Междустрочный /
 * интервал), never inside one (Выравнивани/е).
 */
@Composable
internal fun labelWidth(vararg labels: String, min: androidx.compose.ui.unit.Dp, max: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val style = AeroType.body
    return remember(labels.toList(), min, max, density) {
        fun w(t: String) = with(density) { measurer.measure(t, style, softWrap = false).size.width.toDp() } + 1.dp
        val whole = labels.maxOfOrNull { w(it) } ?: min
        val word = labels.flatMap { it.split(' ', '\u00A0') }.filter { it.isNotEmpty() }.maxOfOrNull { w(it) } ?: min
        whole.coerceIn(maxOf(min, word), maxOf(max, word))
    }
}

/** A dialog's labelled row: [label] in a fixed column (see [labelWidth]), then its control. */
@Composable
internal fun FieldRow(label: String, labelWidth: androidx.compose.ui.unit.Dp = 112.dp, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(labelWidth))
        content()
    }
}

/** A row of a dialog's list box (Category, Type, Sort by…): the picked one in the accent's tint. */
@Composable
internal fun ListRow(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Aero.commandRadius)
    Row(
        modifier.fillMaxWidth().heightIn(min = 32.dp).clip(shape)
            .background(if (selected) Aero.tint(LocalAccent.current) else Color.Transparent)
            .semantics { this.selected = selected }
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .controllerHapticFeedback(interactionSource = interaction)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A list box row of plain text ([color]: the look's own colour, such as a red negative number). */
@Composable
internal fun ListRow(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color? = null) =
    ListRow(selected, onClick, modifier) {
        Text(text, style = if (selected) AeroType.body.copy(fontWeight = FontWeight.SemiBold) else AeroType.body,
            color = color ?: PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

/**
 * Office's list box: rows ([ListRow]) scrolling in a field-coloured well [height] tall. With [empty] set
 * the well shows that line (under [emptyIcon]) centred instead, the way Office says a list has nothing yet.
 */
@Composable
internal fun ListBox(
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 240.dp,
    empty: String? = null,
    emptyIcon: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(10.dp)).background(Aero.field)) {
        if (empty != null) Column(
            Modifier.matchParentSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            if (emptyIcon != null) RibbonGlyph(emptyIcon, PicoTheme.colorScheme.labelTertiary, 28.dp)
            Text(empty, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, textAlign = TextAlign.Center)
        } else Column(
            Modifier.matchParentSize().verticalScroll(rememberScrollState()).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

/** A line under a field saying what is wrong with it (a taken name, a bad reference), in Office's red with its warning icon. */
@Composable
internal fun FieldError(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RibbonGlyph("ic_warning", Aero.danger, 16.dp)
        Text(text, style = AeroType.caption, color = Aero.danger)
    }
}

/** A dialog's small section heading (Horizontal, Font style…). */
@Composable
internal fun DialogLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary, modifier = modifier)

/**
 * A flyout under (or [above]) the composable it is placed in (the Name Box's names, a drop-down's list): the system's glass
 * popup, its start at the composable's start — or its end at the composable's end ([alignEnd]) for one at the window's
 * right edge (the Ribbon Display Options).
 */
@Composable
internal fun AnchoredFlyout(onDismiss: () -> Unit, above: Boolean = false, alignEnd: Boolean = false, content: @Composable () -> Unit) {
    com.pico.spatial.ui.design.windows.SpatialPopup(
        onDismissRequest = onDismiss,
        popupPositionProvider = com.pico.spatial.ui.design.windows.rememberSpatialPopupPositionProvider(
            if (alignEnd) AtAnchorEnd else AtAnchorStart,
            // Above for what sits at the window's foot (the status bar's menus), below for the rest.
            if (above) com.pico.spatial.ui.design.windows.popup.VerticalPlacement.above(4.dp) else com.pico.spatial.ui.design.windows.popup.VerticalPlacement.below(4.dp),
        ),
        cornerRadius = 16.dp,
        defaultMinWidth = androidx.compose.ui.unit.Dp.Unspecified,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalTooltipHost provides null) {
            Box(Modifier.padding(vertical = 8.dp)) { content() }
        }
    }
}
