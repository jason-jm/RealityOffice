package com.therealityteam.aeropages.ui

import androidx.compose.animation.core.animateFloat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.AnimTimeline
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.SlideTransition
import androidx.compose.ui.res.stringResource
import com.therealityteam.aeropages.R
import java.util.Locale
/**
 * @param mirror true in the detached show window: the slide index, build step and clock are
 * published to [ShowSession] and the presenter console's next / previous / stop requests are acted on.
 * @param autoPlayMedia the first video or sound on the slide shown plays at once (a dev hook's, for verification).
 */
@Composable
internal fun SlideShow(editor: SlideEditor, fromStart: Boolean = true, rehearse: Boolean = false, mirror: Boolean = false,
                       autoPlayMedia: Boolean = false, custom: String? = null, presenter: Boolean = false, onExit: () -> Unit) {
    val slides = editor.presentation.slides
    val setup = editor.presentation.show
    // The slides played, in order: Set Up's (a range, a custom show, the visible slides), a custom show picked to play,
    // or — From Current Slide — the current slide and the visible slides after it.
    val plan = remember { com.therealityteam.aeropages.model.ShowPlan.of(editor.presentation, fromSlide = if (fromStart || rehearse) null else editor.current, custom = custom) }
    // At a kiosk the show loops, goes on by its timings and takes no clicks (links and the exit aside).
    val kiosk = setup.type == com.therealityteam.aeropages.model.ShowType.KIOSK && !rehearse
    val loop = editor.presentation.loopShow || kiosk
    fun firstVisible(from: Int, dir: Int): Int {
        var i = from
        while (i in slides.indices && slides[i].hidden) i += dir
        return i.coerceIn(0, slides.lastIndex)
    }
    var index by remember { mutableIntStateOf(plan.firstOrNull() ?: 0) }
    /** The plan's slide after (or before) [i] — a slide off the plan (reached by a link) goes on in the deck's order. */
    fun planNext(i: Int): Int? = plan.indexOf(i).let { at -> if (at >= 0) plan.getOrNull(at + 1) else plan.firstOrNull { it > i } }
    fun planPrev(i: Int): Int? = plan.indexOf(i).let { at -> if (at >= 0) plan.getOrNull(at - 1) else plan.lastOrNull { it < i } }
    /** The clicks slide [i]'s animations take (its timeline's steps after the one that starts with it); none with animations off. */
    fun clicksOf(i: Int) = if (!setup.animations && !rehearse) 0 else AnimTimeline.of(slides.getOrNull(i) ?: Slide()).clicks
    // How many of the slide's click steps have played (0: what starts with the slide), and whether
    // the step just reached plays its effects (forward) or only shows their outcome (stepping back).
    var step by remember { mutableIntStateOf(devStepProp()?.coerceIn(0, clicksOf(index)) ?: 0) }
    var animateStep by remember { mutableStateOf(devStepProp() == null) }
    // Past the last slide: PowerPoint's black "End of slide show" screen, which one more click (or Next) leaves.
    var ended by remember { mutableStateOf(devDialogProp() == "showend") }
    val focus = remember { FocusRequester() }

    // 排练计时 + 演示者时钟: per-slide dwell times and total elapsed seconds.
    var totalSec by remember { mutableIntStateOf(0) }
    val timings = remember { IntArray(slides.size) }
    var slideStart by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastIndex by remember { mutableIntStateOf(index) }
    fun finishShow() {
        if (rehearse) {
            if (index in timings.indices) timings[index] += (System.currentTimeMillis() - slideStart).toInt()
            editor.applyRehearseTimings(timings.toList())
        }
        onExit()
    }
    /** On to the plan's next slide — round to its first again when the show loops — else the show ends. */
    fun nextSlide() {
        val ni = planNext(index)
        when {
            ni != null -> { index = ni; step = 0 }
            loop -> { index = plan.firstOrNull() ?: 0; step = 0 }
            // A rehearsal ends at once (its timings are what it was for); a show ends on the black screen first.
            rehearse -> finishShow()
            else -> ended = true
        }
    }
    fun next() {
        if (ended || slides.getOrNull(index) == null) return finishShow()
        animateStep = true
        if (step < clicksOf(index)) step++ else nextSlide()
    }
    fun prev() {
        if (ended) { ended = false; return }
        animateStep = false
        if (step > 0) step-- else planPrev(index)?.let { index = it; step = clicksOf(it) }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    /**
     * A link in a slide's text, or a click on a shape or picture: one of the show's steps (to the next or
     * previous slide itself, not an effect), a slide — a hidden one too, as PowerPoint goes there —, or an
     * address, opened by whatever app opens it.
     */
    fun follow(link: String) {
        animateStep = true
        when (link) {
            com.therealityteam.aeropages.model.SlideLinks.NEXT -> nextSlide()
            com.therealityteam.aeropages.model.SlideLinks.PREVIOUS -> planPrev(index)?.let { index = it; step = 0 }
            com.therealityteam.aeropages.model.SlideLinks.FIRST -> { index = plan.firstOrNull() ?: firstVisible(0, 1); step = 0 }
            com.therealityteam.aeropages.model.SlideLinks.LAST -> { index = plan.lastOrNull() ?: firstVisible(slides.lastIndex, -1); step = 0 }
            com.therealityteam.aeropages.model.SlideLinks.END -> finishShow()
            else -> {
                val to = com.therealityteam.aeropages.model.SlideLinks.indexIn(editor.presentation, link)
                when {
                    to != null -> { index = to; step = 0 }
                    com.therealityteam.aeropages.model.SlideLinks.inShow(link) -> Unit          // a slide that is gone
                    else -> openExternalUrl(context, link)
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Dev hook (debug builds): the first link in the first slide's text followed, 3 s into the show (`showlink`).
    if (com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "showlink") LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3_000)
        val s = slides.getOrNull(index) ?: return@LaunchedEffect
        (listOf(s.title, s.body) + s.shapes.map { it.box }).firstNotNullOfOrNull { b -> b.paragraph.runs.firstNotNullOfOrNull { it.format.link } }?.let(::follow)
    }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); totalSec++ } }
    if (mirror) {
        LaunchedEffect(index, step) { ShowSession.index = index; ShowSession.step = step }
        LaunchedEffect(totalSec) { ShowSession.elapsedSec = totalSec }
        LaunchedEffect(ShowSession.navSeq) { if (ShowSession.navSeq > 0) { if (ShowSession.navDir > 0) next() else prev() } }
        LaunchedEffect(ShowSession.stopRequested) { if (ShowSession.stopRequested) finishShow() }
    }
    // Per-slide dwell accounting (drives 排练计时 results).
    LaunchedEffect(index) {
        if (lastIndex != index) {
            val now = System.currentTimeMillis()
            if (rehearse && lastIndex in timings.indices) timings[lastIndex] += (now - slideStart).toInt()
            slideStart = now
            lastIndex = index
        }
    }
    // 使用计时: auto-advance after the slide's rehearsed time (never while rehearsing; always at a kiosk).
    if (!rehearse && (editor.presentation.useTimings || kiosk)) {
        LaunchedEffect(index, step) {
            val ms = slides.getOrNull(index)?.advanceMs ?: 0
            if (ms > 0) { kotlinx.coroutines.delay(ms.toLong()); next() }
        }
    }

    Box(
        Modifier.fillMaxSize().background(ShowColors.surround)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionRight, Key.Spacebar, Key.Enter, Key.PageDown -> { if (!kiosk) next(); true }
                    Key.DirectionLeft, Key.PageUp -> { if (!kiosk) prev(); true }
                    Key.Escape, Key.Back -> { finishShow(); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Presenter view in the show: its panel takes the room under the slide (the slide never under the notes).
        var presenterMode by remember { mutableStateOf(presenter && !kiosk) }
        // Tap zones: left third = previous, rest = next — under the slide, so what on it takes a tap
        // (a video or sound) takes it, and anywhere else turns the page.
        val prevLabel = stringResource(R.string.a11y_previous_slide)
        val nextLabel = stringResource(R.string.a11y_next_slide)
        if (!kiosk) Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = prevLabel }.clickable { prev() })
            Box(Modifier.weight(2f).fillMaxHeight().semantics { contentDescription = nextLabel }.clickable { next() })
        }

        // The folder linked videos and sounds are looked for in (next to the presentation).
        val deckDir = remember(editor.presentation.sourcePath) { editor.presentation.sourcePath?.let { java.io.File(it).parentFile } }
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                val target = slides.getOrNull(targetState)
                val t = target?.transition ?: SlideTransition.NONE
                val d = (target?.transitionSpeed ?: com.therealityteam.aeropages.model.TransitionSpeed.MEDIUM).ms
                SlideTransitions.spec(t, target?.transitionDir ?: com.therealityteam.aeropages.model.TransitionDir.DEFAULT, d, targetState >= initialState)
            },
            label = "slide",
        ) { i ->
            val slide = slides.getOrNull(i) ?: Slide()
            // A drawn transition (random bars, a shape, a wheel, a dissolve) reveals the arriving slide as it runs.
            val reveal = if (SlideTransitions.drawn(slide.transition)) {
                val ms = slide.transitionSpeed.ms
                val progress = transition.animateFloat(transitionSpec = { tween(ms, easing = androidx.compose.animation.core.LinearEasing) }, label = "reveal") { state ->
                    if (state == androidx.compose.animation.EnterExitState.Visible) 1f else 0f
                }
                val arriving = transition.targetState == androidx.compose.animation.EnterExitState.Visible
                if (arriving) with(SlideTransitions) { Modifier.reveal(slide.transition, slide.transitionDir) { progress.value } } else Modifier
            } else Modifier
            // The slide as large as the screen allows either way (with a margin) above the show's
            // controls, drawn at that size — its text and lines too, as the audience sees it in PowerPoint.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = if (presenterMode) SHOW_BAR + PRESENTER_PANEL + 8.dp else SHOW_BAR).then(reveal), contentAlignment = Alignment.Center) {
                val aspect = slideAspect(editor)
                val shownWidth = minOf(maxWidth, maxHeight * aspect) * 0.94f
                Box(
                    Modifier.width(shownWidth).aspectRatio(aspect).clip(RoundedCornerShape(8.dp))
                        .slideBackground(editor.presentation, slide),
                ) { AtSlideScale(editor.presentation.slideSize.widthPt, shownWidth) {
                SlideDesignLayer(editor.presentation, slide)
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    val showW = maxWidth
                    val showH = maxHeight
                    // The slide being shown plays its current step; one on its way out (or back in) shows its last.
                    val timeline = remember(slide) { AnimTimeline.of(slide) }
                    // Shown without animations (Set Up), a slide is all there at once, as its effects leave it.
                    val animated = setup.animations || rehearse
                    val shownStep = if (i == index && animated) step else timeline.clicks
                    val play = i == index && animateStep && animated
                    val titleFrame = slide.title.frame
                    val bodyFrame = slide.body.frame
                    val hasTitle = slide.title.text.isNotBlank()
                    // Shown wherever it has text, as the editor and the PDF show it (a title slide's subtitle too).
                    val hasBody = slide.body.text.isNotBlank()
                    // Read-only free shapes, each drawn as its animations have it at this step.
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    slide.shapes.filterNot { it.hidden }.forEach { sh ->
                        // Drawn as the editor and the PDF draw it; a line gets room round it (a level one has no height).
                        val pad = if (sh.type == com.therealityteam.aeropages.model.ShapeType.LINE) 8.dp else 0.dp
                        ShowElement(timeline, sh.box, shownStep, play, Modifier.offset(x = showW * sh.xPct - pad, y = showH * sh.yPct - pad)) {
                            // A shape with a click of its own takes the tap and does it (PowerPoint's action button).
                            val click = sh.action
                            Box(
                                Modifier.size(width = showW * sh.wPct + pad * 2, height = showH * sh.hPct + pad * 2).graphicsLayer(rotationZ = sh.rotationDeg)
                                    .then(if (click != null && i == index) Modifier.clickable { follow(click) } else Modifier)
                                    .onPointerOver(sh.hoverAction.takeIf { i == index }, ::follow),
                                contentAlignment = anchorAlignment(sh.anchor),
                            ) {
                                ShapeGeometry(sh, density.density, editor.revision, Modifier.fillMaxSize(), inset = with(density) { pad.toPx() })
                                if (sh.box.text.isNotBlank())
                                    ShowText(timeline, sh.box, shownStep, play, TextStyling.annotatedOf(sh.box.paragraph, onLink = if (click == null && i == index) ::follow else null),
                                        slideBaseStyle(sh.box, editor.presentation.shapeTextOn(slide, sh)),
                                        (if (sh.noWrap) Modifier.fillMaxWidth().unwrapped(sh) else Modifier).padding(horizontal = 7.2.dp, vertical = 3.6.dp))
                            }
                        }
                    }
                    slide.tables.filterNot { it.hidden }.forEach { tb ->
                        ShowElement(timeline, tb, shownStep, play, Modifier.offset(x = showW * tb.xPct, y = showH * tb.yPct)) {
                            TableView(editor, tb, showW, showH, editable = false, atFrame = false, onLink = (::follow).takeIf { i == index })
                        }
                    }
                    slide.charts.filterNot { it.hidden }.forEach { ch ->
                        ShowElement(timeline, ch, shownStep, play, Modifier.offset(x = showW * ch.xPct, y = showH * ch.yPct)) {
                            ChartView(editor, ch, showW, showH, editable = false, background = editor.presentation.backgroundOf(slide), atFrame = false)
                        }
                    }
                    SlideFooter(editor.presentation, slide, i + 1)
                    val titleText: @Composable () -> Unit = {
                        ShowText(timeline, slide.title, shownStep, play, TextStyling.annotatedOf(slide.title.paragraph, onLink = (::follow).takeIf { i == index }),
                            slideBaseStyle(slide.title, editor.presentation.titleTextOn(slide)), Modifier.fillMaxWidth())
                    }
                    // Built by paragraph, the body's bullets come in one by one.
                    val bodyText: @Composable () -> Unit = {
                        ShowText(timeline, slide.body, shownStep, play, TextStyling.annotatedOf(slide.body.paragraph, onLink = (::follow).takeIf { i == index }),
                            slideBaseStyle(slide.body, editor.presentation.bodyTextOn(slide)), Modifier.fillMaxWidth())
                    }
                    // Placeholders without a frame flow per the layout; framed ones sit at their frame.
                    Box(Modifier.fillMaxSize().padding(48.dp)) {
                    val centerTitle = slide.layout == SlideLayout.TITLE || slide.layout == SlideLayout.SECTION
                    Column(Modifier.fillMaxSize(), verticalArrangement = if (centerTitle) Arrangement.Center else Arrangement.spacedBy(20.dp)) {
                        if (hasTitle && titleFrame == null) ShowElement(timeline, slide.title, shownStep, play) { titleText() }
                        if (hasBody && bodyFrame == null) ShowElement(timeline, slide.body, shownStep, play) { bodyText() }
                    }
                    }
                    if (hasTitle && titleFrame != null) {
                        ShowElement(timeline, slide.title, shownStep, play, Modifier.offset(x = showW * titleFrame.xPct, y = showH * titleFrame.yPct)) {
                            Box(Modifier.size(width = showW * titleFrame.wPct, height = showH * titleFrame.hPct).padding(horizontal = 6.dp, vertical = 4.dp), contentAlignment = Alignment.CenterStart) { titleText() }
                        }
                    }
                    if (hasBody && bodyFrame != null) {
                        ShowElement(timeline, slide.body, shownStep, play, Modifier.offset(x = showW * bodyFrame.xPct, y = showH * bodyFrame.yPct)) {
                            Box(Modifier.size(width = showW * bodyFrame.wPct, height = showH * bodyFrame.hPct).padding(horizontal = 6.dp, vertical = 4.dp), contentAlignment = Alignment.TopStart) { bodyText() }
                        }
                    }
                    // Pictures: positioned like shapes, above the placeholders (spTree order). A video or a sound plays when tapped.
                    val autoPlayed = if (autoPlayMedia && i == index) slide.images.firstOrNull { it.media != null } else null
                    slide.images.filterNot { it.hidden }.forEach { img ->
                        ShowElement(timeline, img, shownStep, play, Modifier.offset(x = showW * img.xPct, y = showH * img.yPct)) {
                            // A picture with a click of its own does it (a video or sound plays instead).
                            val click = img.action?.takeIf { img.media == null && i == index }
                            PlayableSlideImage(img, deckDir, Modifier.size(width = showW * img.wPct, height = showH * img.hPct).graphicsLayer(rotationZ = img.rotationDeg)
                                .then(if (click != null) Modifier.clickable { follow(click) } else Modifier)
                                .onPointerOver(img.hoverAction.takeIf { img.media == null && i == index }, ::follow),
                                autoPlay = img === autoPlayed)
                        }
                    }
                }
                } }
            }
        }


        // Presenter view (演示者视图) in the show: the notes and, beside them, the next slide — above the toolbar.
        if (presenterMode) {
            val cur = slides.getOrNull(index)
            val nxt = planNext(index)?.let { slides.getOrNull(it) }
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = SHOW_BAR + 4.dp).fillMaxWidth(0.92f).height(PRESENTER_PANEL)
                    .clip(RoundedCornerShape(16.dp)).background(ShowColors.panel).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.notes), style = AeroType.caption, color = ShowColors.muted)
                    Text(cur?.notes?.ifBlank { stringResource(R.string.label_no_notes) } ?: "", style = AeroType.heading.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                        color = ShowColors.ink, maxLines = 5)
                }
                Column(Modifier.width(180.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.label_next), style = AeroType.caption, color = ShowColors.muted)
                    if (nxt != null) SlidePreview(editor, nxt, Modifier.fillMaxWidth())
                    else Text(stringResource(R.string.status_last_slide), style = AeroType.caption, color = ShowColors.muted)
                }
            }
        }

        if (ended) {
            val endLabel = stringResource(R.string.show_end_screen)
            Box(
                Modifier.fillMaxSize().background(Color.Black).semantics { contentDescription = endLabel }.clickable { finishShow() },
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(endLabel, style = AeroType.body, color = Color(0xFFBDBDBD), modifier = Modifier.padding(top = 32.dp))
            }
        }

        // PowerPoint's slide show toolbar: quiet round buttons on dark glass at the foot — previous, where the show
        // is, next; the clock; presenter view; end the show.
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).clip(RoundedCornerShape(24.dp))
                .background(ShowColors.bar).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            // Where the show is: the slide's place in what it plays (its number in the deck when a link left the plan).
            val at = plan.indexOf(index)
            val place = if (at >= 0) "${at + 1} / ${plan.size}" else "${index + 1} / ${slides.size}"
            if (!kiosk) ShowButton("ic_chevron_left", stringResource(R.string.nav_previous), { prev() })
            Text(place, style = AeroType.body, color = ShowColors.ink, modifier = Modifier.padding(horizontal = 6.dp))
            if (!kiosk) ShowButton("ic_chevron_right", stringResource(R.string.nav_next), { next() })
            ShowDivider()
            Text(
                if (rehearse) stringResource(R.string.status_rehearsing, showClock(totalSec)) else showClock(totalSec),
                style = AeroType.body, color = if (rehearse) ShowColors.alert else ShowColors.muted,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            if (!kiosk) ShowButton("ic_notes", stringResource(R.string.presenter), { presenterMode = !presenterMode }, selected = presenterMode)
            ShowDivider()
            ShowButton("ic_close", stringResource(R.string.exit), { finishShow() })
        }
    }
}

/** The show's control bar at the foot (clock, previous / next, presenter, exit): the slide sits above it. */
private val SHOW_BAR = 64.dp

/** The presenter panel's height in the show (notes beside the next slide), between the slide and the toolbar. */
private val PRESENTER_PANEL = 150.dp

/** The show's and the presenter view's own colours: they sit on the dark surround a show is played on. */
internal object ShowColors {
    val surround = Color(0xFF101010) // design-style: show surround
    val bar = Color(0xCC2A2A2E) // design-style: show toolbar glass
    val panel = Color(0xE6222226) // design-style: presenter panel
    val console = Color(0xFF1B1B1F) // design-style: presenter view background
    val ink = Color(0xFFF2F2F2) // design-style: text on the show surround
    val muted = Color(0xB3F2F2F2) // design-style: secondary text on the surround
    val hover = Color(0x1FFFFFFF) // design-style: hover on dark
    val on = Color(0x33FFFFFF) // design-style: toggled on, on dark
    val timer = Color(0xFFFFD166) // design-style: presenter timer
    val alert = Color(0xFFFF6B6B) // design-style: rehearsing clock
}

/** A round icon button of the show's toolbar and the presenter view (white on dark; [selected]: a lighter disc). */
@Composable
internal fun ShowButton(icon: String, tooltip: String, onClick: () -> Unit, selected: Boolean = false, size: androidx.compose.ui.unit.Dp = 40.dp) =
    Tooltip(tooltip) { a11y ->
        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        Box(
            Modifier.then(a11y).size(size).clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (selected) ShowColors.on else if (hovered) ShowColors.hover else Color.Transparent)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick)
                .controllerHapticFeedback(interactionSource = interaction),
            contentAlignment = Alignment.Center,
        ) { RibbonGlyph(icon, ShowColors.ink, 20.dp) }
    }

@Composable
private fun ShowDivider() = Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(Color(0x33FFFFFF)))

/**
 * Presenter view (PowerPoint's), in the editor window while the show plays in its own: the timer and the
 * slide count across the top with End Slide Show, the current slide large, the next one and the notes beside
 * it (their text sized with A+ / A−), and previous / next round buttons under the current slide.
 */
@Composable
internal fun PresenterConsole(editor: SlideEditor, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val slides = editor.presentation.slides
    val index = ShowSession.index.coerceIn(0, slides.lastIndex.coerceAtLeast(0))
    val cur = slides.getOrNull(index)
    val nxt = slides.drop(index + 1).firstOrNull { !it.hidden }
    var notesScale by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    Column(modifier.fillMaxSize().background(ShowColors.console).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.presenter_view), style = AeroType.title, color = ShowColors.ink)
            Text(showClock(ShowSession.elapsedSec), style = AeroType.title, color = ShowColors.timer)
            Box(Modifier.weight(1f))
            DialogButton(stringResource(R.string.end_show), { ShowSession.requestStop() }, danger = true, small = true)
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // The current slide, and the way through the show under it.
            Column(Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (cur != null) SlidePreview(editor, cur, Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShowButton("ic_chevron_left", stringResource(R.string.nav_previous), { ShowSession.requestPrev() }, size = 44.dp)
                    Text(stringResource(R.string.label_slide_of, index + 1, slides.size), style = AeroType.body, color = ShowColors.ink)
                    ShowButton("ic_chevron_right", stringResource(R.string.nav_next), { ShowSession.requestNext() }, size = 44.dp)
                }
            }
            // The next slide, then the notes.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.label_next), style = AeroType.caption, color = ShowColors.muted)
                if (nxt != null) SlidePreview(editor, nxt, Modifier.fillMaxWidth(0.8f))
                else Text(stringResource(R.string.status_last_slide), style = AeroType.body, color = ShowColors.muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.notes), style = AeroType.caption, color = ShowColors.muted, modifier = Modifier.weight(1f))
                    ShowButton("ic_font_grow", stringResource(R.string.increase_font_size), { notesScale = (notesScale + 0.15f).coerceAtMost(2.2f) }, size = 32.dp)
                    ShowButton("ic_font_shrink", stringResource(R.string.decrease_font_size), { notesScale = (notesScale - 0.15f).coerceAtLeast(0.7f) }, size = 32.dp)
                }
                Text(
                    cur?.notes?.ifBlank { stringResource(R.string.label_no_notes) } ?: "",
                    style = AeroType.heading.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                        fontSize = AeroType.heading.fontSize * notesScale, lineHeight = AeroType.heading.lineHeight * notesScale),
                    color = ShowColors.ink,
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

/** A slide as it prints, for the presenter console (current and next). */
@Composable
internal fun SlidePreview(editor: SlideEditor, slide: Slide, modifier: Modifier = Modifier) {
    val number = editor.presentation.slides.indexOfFirst { it === slide } + 1
    Box(modifier.aspectRatio(slideAspect(editor)).clip(RoundedCornerShape(6.dp))) {
        SlideThumbnail(editor, slide, number, editor.revision, Modifier.fillMaxSize())
    }
}

/** mm:ss elapsed-time clock used by the slideshow / presenter console. */
internal fun showClock(sec: Int): String = String.format(Locale.getDefault(), "%02d:%02d", sec / 60, sec % 60)

/**
 * PowerPoint's mouse-over action: when a pointer (a mouse, or a controller ray where the system passes hover on) comes
 * over the object, [follow] its [link] — once each time it comes in. Nothing for a null link.
 */
private fun Modifier.onPointerOver(link: String?, follow: (String) -> Unit): Modifier =
    if (link == null) this else pointerInput(link) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Enter) follow(link)
            }
        }
    }
