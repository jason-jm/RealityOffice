package com.therealityteam.aeropages.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.therealityteam.aeropages.platform.appInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.LocalIndication
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.AlertDialog
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.therealityteam.aeropages.format.DeskFiles
import com.therealityteam.aeropages.format.DeskKind
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.res.stringResource
import com.therealityteam.aeropages.R

private sealed interface Module {
    data object Launcher : Module
    // A new document from [template] when there is no path (null: a blank one).
    data class Pages(val path: String?, val template: DeskTemplate? = null) : Module
    data class Slides(val path: String?, val template: DeskTemplate? = null) : Module
    data class Sheets(val path: String?, val template: DeskTemplate? = null) : Module
}

/** Shared by the launcher's open commands, document links and the ribbon's New Window button. */
internal val LocalOpenSideWindow = staticCompositionLocalOf<(String?) -> Unit> { {} }
/** Switch this window's editor after the active document has passed its unsaved-changes guard. */
internal val LocalOpenInCurrentWindow = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * Aero Desk root. Shows a launcher to create a Pages / Slides / Sheets document or open
 * an existing file, then routes to the matching module. Opening a file picks the module
 * by extension (.docx→Pages, .pptx→Slides, .xlsx→Sheets).
 */
/**
 * Dev hook (debug builds only): `adb shell setprop debug.aero.module slides|sheets|pages` to deep-link a module,
 * or a template's name (`minutes`, `budget`, `deck_dark` …) to start a new document from it.
 */
@SuppressLint("PrivateApi")
private fun devInitialModule(): Module = if (!com.therealityteam.aeropages.BuildConfig.DEBUG) Module.Launcher else try {
    val v = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        .invoke(null, "debug.aero.module") as? String
    val template = DeskTemplate.entries.firstOrNull { it.name.equals(v, ignoreCase = true) }
    when {
        template != null -> moduleFor(template)
        v == "slides" -> Module.Slides(null)
        v == "sheets" -> Module.Sheets(null)
        v == "pages" -> Module.Pages(null)
        else -> Module.Launcher
    }
} catch (e: Throwable) { Module.Launcher }

private fun moduleFor(t: DeskTemplate): Module = when (t.kind) {
    DeskKind.PAGES -> Module.Pages(null, t)
    DeskKind.SLIDES -> Module.Slides(null, t)
    DeskKind.SHEETS -> Module.Sheets(null, t)
}

/** Dev hook (debug builds only): `adb shell setprop debug.aero.locale en|zh|system` pins the app language at launch. */
@SuppressLint("PrivateApi")
private fun devLocale(): AppLanguage? = if (!com.therealityteam.aeropages.BuildConfig.DEBUG) null else try {
    val v = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        .invoke(null, "debug.aero.locale") as? String
    if (v.isNullOrBlank()) null else AppLanguage.fromTag(v)
} catch (e: Throwable) { null }

private fun moduleFor(path: String): Module = when (DeskFiles.kindOf(path)) {
    DeskKind.PAGES -> Module.Pages(path)
    DeskKind.SLIDES -> Module.Slides(path)
    DeskKind.SHEETS -> Module.Sheets(path)
}

/**
 * @param external the window container this Aero Desk lives in ([OpenRequests.MAIN] or one of
 * [OpenRequests.SIDE_WINDOWS]): it takes the open requests addressed to it. The main window
 * hands a request to a free side window while it is showing a document, so an open document
 * is never replaced. Null = a window that takes no requests.
 */
@Composable
fun AeroDesk(external: String? = null) {
    com.therealityteam.aeropages.platform.StartupClock.mark("compose:${external ?: "embedded"}")
    var module by remember { mutableStateOf<Module>(devInitialModule()) }
    val home = { module = Module.Launcher }
    val context = LocalContext.current
    val navigator = com.pico.spatial.ui.platform.containers.LocalSpatialNavigator.current
    androidx.compose.runtime.LaunchedEffect(Unit) { devLocale()?.let { AppLocale.set(context, it) } }
    var noFreeWindow by remember { mutableStateOf(false) }
    var failedDocument by remember { mutableStateOf<String?>(null) }
    fun showSide(id: String?, request: OpenRequests.Request? = null) {
        if (id == null) { noFreeWindow = true; return }
        try { navigator.openWindowContainer(id) }
        catch (e: Exception) {
            request?.let(OpenRequests::consume)
            failedDocument = request?.let { OpenRequests.documentUri(it.intent)?.lastPathSegment }.orEmpty()
            com.therealityteam.aeropages.platform.Diagnostics.log("openWindow", id, e)
        }
    }
    val openInNewWindow: (String?) -> Unit = { path ->
        if (path == null) showSide(OpenRequests.idleSideWindow(excluding = external)) else {
            val request = OpenRequests.openInSideWindow(path, from = external)
            showSide(request?.target, request)
        }
    }

    if (external != null) {
        val workDir = remember { (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() } }
        // Let "open in new window" know which side windows are free.
        SideEffect { OpenRequests.setBusy(external, module != Module.Launcher) }
        androidx.compose.runtime.DisposableEffect(external) { onDispose { OpenRequests.setBusy(external, false) } }
        androidx.compose.runtime.LaunchedEffect(external) {
            OpenRequests.pending.map { list -> list.firstOrNull { it.target == external } }.distinctUntilChanged().collect { req ->
                if (req == null) return@collect
                if (module != Module.Launcher) {
                    val next = OpenRequests.redirectToSide(req)
                    showSide(next?.target, next)
                    return@collect
                }
                try {
                    val path = OpenRequests.resolvePath(context, workDir, req.intent)
                    if (path == null) failedDocument = OpenRequests.documentUri(req.intent)?.lastPathSegment?.substringAfterLast('/').orEmpty()
                    else if (module == Module.Launcher) {
                        OpenRequests.setBusy(external, true)
                        module = moduleFor(path)
                    } else {
                        // The user opened an editor while the provider was reading. Route the completed local copy beside it.
                        openInNewWindow(path)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    failedDocument = OpenRequests.documentUri(req.intent)?.lastPathSegment?.substringAfterLast('/').orEmpty()
                    com.therealityteam.aeropages.platform.Diagnostics.log("import", "Unable to open the selected document", e)
                }
                OpenRequests.consume(req)
            }
        }
    }

    CompositionLocalProvider(LocalOpenSideWindow provides openInNewWindow,
        LocalOpenInCurrentWindow provides { path -> module = moduleFor(path) }) {
        when (val m = module) {
            Module.Launcher -> Launcher(
                onNew = { kind ->
                    module = when (kind) {
                        DeskKind.PAGES -> Module.Pages(null)
                        DeskKind.SLIDES -> Module.Slides(null)
                        DeskKind.SHEETS -> Module.Sheets(null)
                    }
                },
                onTemplate = { t -> module = moduleFor(t) },
                onOpenPath = { path -> module = moduleFor(path) },
                onOpenInNewWindow = openInNewWindow,
            )
            is Module.Pages -> WordApp(openPath = m.path, template = m.template, windowId = external ?: OpenRequests.MAIN, onHome = home)
            is Module.Slides -> SlidesApp(openPath = m.path, template = m.template, windowId = external ?: OpenRequests.MAIN, onHome = home)
            is Module.Sheets -> SheetsApp(openPath = m.path, template = m.template, windowId = external ?: OpenRequests.MAIN, onHome = home)
        }
    }
    if (noFreeWindow) AlertDialog(onDismissRequest = { noFreeWindow = false },
        title = { Text(stringResource(R.string.open_in_new_window)) },
        content = { Text(stringResource(R.string.no_free_document_window)) },
        buttons = { DialogButton(stringResource(R.string.btn_ok), { noFreeWindow = false }, primary = true) })
    failedDocument?.let { name ->
        OpenProblemDialog(name.ifBlank { stringResource(R.string.document_label) }, encrypted = false, details = null) { failedDocument = null }
    }
}

@Composable
private fun Launcher(
    onNew: (DeskKind) -> Unit,
    onTemplate: (DeskTemplate) -> Unit,
    onOpenPath: (String) -> Unit,
    onOpenInNewWindow: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val workDir = remember { (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() } }
    var showOpen by remember { mutableStateOf(false) }
    // When true, the file picked in the browser goes to a free side window instead of this one.
    var openSide by remember { mutableStateOf(false) }
    var storageAccess by remember { mutableStateOf(checkStorageAccess(context)) }
    val devHook = remember { devDialogProp() }
    var showSettings by remember { mutableStateOf(devHook == "homesettings") }
    var galleryKind by remember { mutableStateOf(if (devHook == "homeslides") DeskKind.SLIDES else if (devHook == "homesheets") DeskKind.SHEETS else DeskKind.PAGES) }
    // Documents opened or saved lately, and the app folder's own, newest first.
    var recents by remember { mutableStateOf(RecentFiles.forHome(context, workDir, limit = 40)) }
    // Office's Pinned: documents pinned to the list, and whether the list shows them instead of Recent.
    var pinned by remember { mutableStateOf(RecentFiles.pinned(context)) }
    var showPinned by remember { mutableStateOf(devHook == "homepinned") }
    val pinnedList = remember(pinned, recents) { RecentFiles.forPinned(context, recents) }
    val pin = { path: String, on: Boolean -> RecentFiles.setPinned(context, path, on); pinned = RecentFiles.pinned(context) }
    // Where a document is, in words: a storage shortcut's name (the app folder, Downloads …), else its folder's.
    val places = remember { storageShortcuts(workDir).associate { canonical(it.dir) to it.label } }

    var page by remember { mutableStateOf(when (devHook) { "homenew" -> HomePage.NEW; "homeopen" -> HomePage.OPEN; else -> HomePage.HOME }) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<DeskKind?>(null) }
    val openFile = { openSide = false; showOpen = true }
    val openBeside = { openSide = true; showOpen = true }
    val openFromDevice = { com.therealityteam.aeropages.platform.PickerActivity.openDocument(context) }

    // Office's start page: the places on the left, the page on the right; the window is the system glass.
    androidx.compose.runtime.CompositionLocalProvider(LocalAccent provides Aero.desk) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrowWindow = maxWidth < 940.dp
        val gutter = if (narrowWindow) 16.dp else 40.dp
        Row(Modifier.fillMaxSize()) {
            com.pico.spatial.ui.design.SideNavigation(
                modifier = Modifier.width(if (narrowWindow) 170.dp else 248.dp).fillMaxHeight(),
                header = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, top = 28.dp, bottom = 24.dp)) {
                        AppMark(Modifier.size(36.dp))
                        Text(stringResource(R.string.app_name), style = AeroType.title, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.padding(start = 12.dp))
                    }
                },
            ) {
                HomePage.entries.forEach { p ->
                    com.pico.spatial.ui.design.SideNavigationItem(
                        selected = page == p,
                        modifier = Modifier.sideNavRow { page = p },
                        leading = { RibbonGlyph(p.icon, PicoTheme.colorScheme.labelPrimary, 20.dp) },
                    ) { Text(stringResource(p.label), maxLines = 1) }
                }
                com.pico.spatial.ui.design.HorizontalDivider(Modifier.padding(vertical = 12.dp))
                com.pico.spatial.ui.design.SideNavigationItem(
                    selected = false,
                    modifier = Modifier.sideNavRow { showSettings = true },
                    leading = { RibbonGlyph("ic_settings", PicoTheme.colorScheme.labelPrimary, 20.dp) },
                ) { Text(stringResource(R.string.home_settings), maxLines = 1) }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = gutter, end = gutter, top = 30.dp)) {
                when (page) {
                    HomePage.HOME -> {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(greetingNow()), style = AeroType.pageTitle, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f))
                        }
                        // New: the three blank documents, then the featured templates of each module.
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 12.dp)) {
                            HomeHeading(stringResource(R.string.home_templates), Modifier.weight(1f))
                            com.pico.spatial.ui.design.Link(onClick = { page = HomePage.NEW }) { Text(stringResource(R.string.home_more_templates)) }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            NewCard(DeskKind.PAGES, "Pages", stringResource(R.string.home_blank_document)) { onNew(DeskKind.PAGES) }
                            NewCard(DeskKind.SLIDES, "Slides", stringResource(R.string.home_blank_presentation)) { onNew(DeskKind.SLIDES) }
                            NewCard(DeskKind.SHEETS, "Sheets", stringResource(R.string.home_blank_workbook)) { onNew(DeskKind.SHEETS) }
                            listOf(DeskKind.PAGES, DeskKind.SLIDES, DeskKind.SHEETS).mapNotNull { k -> DeskTemplate.of(k).firstOrNull { !it.blank } }
                                .forEach { t -> TemplateCard(t) { onTemplate(t) } }
                        }
                        RecentSection(if (showPinned) pinnedList else recents, query, { query = it }, filter, { filter = it }, places,
                            pinned = pinned, showPinned = showPinned, onShowPinned = { showPinned = it }, onPin = pin,
                            onOpenFile = openFile, onOpenBeside = openBeside, onOpenFromDevice = openFromDevice,
                            onOpen = onOpenPath, onOpenInNewWindow = onOpenInNewWindow,
                            onRemove = { path -> RecentFiles.remove(context, path); recents = RecentFiles.forHome(context, workDir, limit = 40) },
                            modifier = Modifier.weight(1f).padding(top = 28.dp))
                    }
                    HomePage.NEW -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.home_nav_new), style = AeroType.pageTitle, color = PicoTheme.colorScheme.labelPrimary)
                        listOf(
                            Triple(DeskKind.PAGES, R.string.home_tab_documents, R.string.home_blank_document),
                            Triple(DeskKind.SLIDES, R.string.home_tab_presentations, R.string.home_blank_presentation),
                            Triple(DeskKind.SHEETS, R.string.home_tab_spreadsheets, R.string.home_blank_workbook),
                        ).forEach { (kind, title, blank) ->
                            HomeHeading(stringResource(title), Modifier.padding(top = 24.dp, bottom = 12.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                NewCard(kind, kind.title, stringResource(blank)) { onNew(kind) }
                                DeskTemplate.of(kind).filter { !it.blank }.forEach { t -> TemplateCard(t) { onTemplate(t) } }
                            }
                        }
                        Box(Modifier.height(24.dp))
                    }
                    HomePage.OPEN -> {
                        Text(stringResource(R.string.open), style = AeroType.pageTitle, color = PicoTheme.colorScheme.labelPrimary)
                        RecentSection(if (showPinned) pinnedList else recents, query, { query = it }, filter, { filter = it }, places,
                            pinned = pinned, showPinned = showPinned, onShowPinned = { showPinned = it }, onPin = pin,
                            onOpenFile = openFile, onOpenBeside = openBeside, onOpenFromDevice = openFromDevice,
                            onOpen = onOpenPath, onOpenInNewWindow = onOpenInNewWindow,
                            onRemove = { path -> RecentFiles.remove(context, path); recents = RecentFiles.forHome(context, workDir, limit = 40) },
                            modifier = Modifier.weight(1f).padding(top = 20.dp))
                    }
                }
            }
        }
        // Dev hooks (debug builds): `setprop debug.aero.dialog syspicker` opens the system picker on launch; `crash` throws
        // on the launcher to exercise the crash report path; `homesettings`, `homeslides`, `homesheets` show those parts.
        if (com.therealityteam.aeropages.BuildConfig.DEBUG) {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (devHook == "syspicker") com.therealityteam.aeropages.platform.PickerActivity.openDocument(context)
                if (devHook == "crash") throw IllegalStateException("debug.aero.dialog=crash: deliberate test crash")
                // `homepin` pins the first two recent documents (Recent shows their pins); `homepinned` also shows the Pinned tab.
                if ((devHook == "homepin" || devHook == "homepinned") && pinned.isEmpty()) recents.take(2).forEach { pin(it.path, true) }
            }
        }
        if (showSettings) SettingsPanel(workDir) { showSettings = false }
    }
    }

    if (showOpen) {
        FileBrowser(
            mode = BrowserMode.OPEN,
            startDir = browseStartDir(workDir, storageAccess),
            shortcuts = storageShortcuts(workDir),
            suggestedName = "",
            hasStorageAccess = storageAccess,
            onRequestStorage = { requestAllFilesAccess(context) },
            onRefresh = { storageAccess = checkStorageAccess(context) },
            onDismiss = { showOpen = false },
            onOpen = { path -> showOpen = false; if (openSide) onOpenInNewWindow(path) else onOpenPath(path) },
            onSave = {},
            title = if (openSide) stringResource(R.string.open_in_new_window) else stringResource(R.string.title_open_file),
        )
    }
}

/** [f]'s path with `/sdcard` and the like resolved, so one folder reached two ways is one folder. */
private fun canonical(f: File): String = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

internal fun accentOf(kind: DeskKind): Color = when (kind) {
    DeskKind.PAGES -> Aero.pages
    DeskKind.SLIDES -> Aero.slides
    DeskKind.SHEETS -> Aero.sheets
}

/** The launcher mark, enlarged on its tile for the home screen. */
@Composable
private fun AppMark(modifier: Modifier) {
    // Coordinates and colours follow tools/icons/app-icon.svg.
    androidx.compose.foundation.Canvas(modifier) {
        val s = size.minDimension
        val zoom = 1.3f
        fun at(v: Float) = (512f + (v - 512f) * zoom) / 1024f * s
        drawRoundRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFECF0F7))),
            size = androidx.compose.ui.geometry.Size(s, s), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.24f))
        val mark = androidx.compose.ui.graphics.Path().apply {
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
            moveTo(at(292f), at(278f))
            quadraticTo(at(292f), at(252f), at(318f), at(252f))
            lineTo(at(522f), at(252f))
            cubicTo(at(650f), at(252f), at(724f), at(319f), at(724f), at(417f))
            cubicTo(at(724f), at(487f), at(690f), at(540f), at(626f), at(570f))
            lineTo(at(742f), at(739f))
            quadraticTo(at(754f), at(756f), at(733f), at(756f))
            lineTo(at(632f), at(756f))
            quadraticTo(at(621f), at(756f), at(614f), at(746f))
            lineTo(at(501f), at(584f))
            lineTo(at(414f), at(584f))
            lineTo(at(414f), at(756f))
            lineTo(at(318f), at(756f))
            quadraticTo(at(292f), at(756f), at(292f), at(730f))
            close()
            moveTo(at(414f), at(358f))
            lineTo(at(414f), at(478f))
            lineTo(at(518f), at(478f))
            cubicTo(at(572f), at(478f), at(606f), at(459f), at(606f), at(417f))
            cubicTo(at(606f), at(377f), at(574f), at(358f), at(518f), at(358f))
            close()
        }
        val bands = listOf(
            Triple(252f, 408f, Color(0xFF134A9C) to Color(0xFF3B80E6)),
            Triple(424f, 582f, Color(0xFFA83218) to Color(0xFFEC6A3D)),
            Triple(598f, 756f, Color(0xFF0B6434) to Color(0xFF25A75C)),
        )
        clipPath(mark) {
            for ((top, bottom, ends) in bands) {
                drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(ends.first, ends.second), startX = at(292f), endX = at(754f)),
                    topLeft = Offset(at(292f), at(top)), size = androidx.compose.ui.geometry.Size(at(754f) - at(292f), at(bottom) - at(top)))
            }
        }
    }
}

/** The start page's places, Office's left column: Home, New, Open. */
private enum class HomePage(val label: Int, val icon: String) {
    HOME(R.string.home_nav_home, "ic_home"), NEW(R.string.home_nav_new, "ic_plus"), OPEN(R.string.open, "ic_folder")
}

/** Good morning / afternoon / evening, as Office greets. */
private fun greetingNow(): Int = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
    in 5..11 -> R.string.home_greeting_morning
    in 12..17 -> R.string.home_greeting_afternoon
    else -> R.string.home_greeting_evening
}

private val DeskKind.title: String get() = when (this) { DeskKind.PAGES -> "Pages"; DeskKind.SLIDES -> "Slides"; DeskKind.SHEETS -> "Sheets" }

/** A section's heading on the start page. */
@Composable
private fun HomeHeading(text: String, modifier: Modifier = Modifier) =
    Text(text, style = AeroType.heading, color = PicoTheme.colorScheme.labelPrimary, modifier = modifier)

/** One of Office's tabs over the file list (Recent, Pinned): a heading, the accent's bar under the one shown. */
@Composable
private fun HomeTab(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).press(onClick = onClick)
            .semantics { this.selected = selected; role = androidx.compose.ui.semantics.Role.Tab }.padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = AeroType.heading, color = if (selected) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary)
        Box(Modifier.padding(top = 4.dp).size(width = 28.dp, height = 3.dp).clip(RoundedCornerShape(2.dp))
            .background(if (selected) LocalAccent.current else Color.Transparent))
    }
}

/** A template in the gallery: a sketch of what it makes on a white page, and its name under it. */
@Composable
internal fun TemplateCard(t: DeskTemplate, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.width(132.dp).clip(shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .controllerHapticFeedback(interactionSource = interaction),
    ) {
        Box(Modifier.fillMaxWidth().height(164.dp).clip(RoundedCornerShape(10.dp)).background(Color.White)) { // design-style: paper
            TemplatePreview(t, Modifier.fillMaxSize().padding(10.dp))
            GazeFill(RoundedCornerShape(10.dp))
        }
        Text(stringResource(t.label), style = AeroType.command, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1,
            modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 4.dp))
    }
}

/**
 * Recent documents as Office lists them: the ways to open one above (a file, beside this window, from another
 * app), a search box and a filter by kind, then a table of name, place and when — with Open in New Window and
 * Remove from List on each row.
 */
@Composable
private fun RecentSection(
    recents: List<RecentFiles.Recent>, query: String, onQuery: (String) -> Unit, filter: DeskKind?, onFilter: (DeskKind?) -> Unit,
    places: Map<String, String>, pinned: Set<String>, showPinned: Boolean, onShowPinned: (Boolean) -> Unit, onPin: (String, Boolean) -> Unit,
    onOpenFile: () -> Unit, onOpenBeside: () -> Unit, onOpenFromDevice: () -> Unit,
    onOpen: (String) -> Unit, onOpenInNewWindow: (String) -> Unit, onRemove: (String) -> Unit, modifier: Modifier = Modifier,
) {
    val filters = remember { listOf(null to R.string.home_filter_all, DeskKind.PAGES to R.string.home_tab_documents,
        DeskKind.SLIDES to R.string.home_tab_presentations, DeskKind.SHEETS to R.string.home_tab_spreadsheets) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compact = maxWidth < 740.dp
        val searchWidth = minOf(maxWidth, if (compact) 240.dp else 260.dp)
        Column(Modifier.fillMaxWidth()) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeTab(stringResource(R.string.recent), !showPinned) { onShowPinned(false) }
                    HomeTab(stringResource(R.string.home_pinned), showPinned) { onShowPinned(true) }
                }
                if (compact) {
                    val selected = filters.first { it.first == filter }
                    ComboField(stringResource(selected.second), filters, { stringResource(it.second) }, selected,
                        { onFilter(it.first) }, width = 156.dp)
                } else {
                    com.pico.spatial.ui.design.SegmentControl(Modifier.width(440.dp)) {
                        filters.forEach { (kind, label) ->
                            SegmentItem(selected = filter == kind, onClick = { onFilter(kind) }, title = { Text(stringResource(label), maxLines = 1) })
                        }
                    }
                }
                com.pico.spatial.ui.design.SearchField(
                    value = query, onValueChange = onQuery, onSearch = onQuery,
                    modifier = Modifier.width(searchWidth),
                    placeholder = { Text(stringResource(R.string.home_search_recent), maxLines = 1) },
                )
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RibbonSmallButton("ic_folder", stringResource(R.string.open_file_button), onOpenFile)
                RibbonSmallButton("ic_window_new", stringResource(R.string.open_in_new_window), onOpenBeside)
                RibbonSmallButton("ic_share", stringResource(R.string.open_from_device), onOpenFromDevice)
            }
            val shown = recents.filter { r ->
                (filter == null || DeskFiles.kindOf(r.file.name) == filter) && (query.isBlank() || r.file.name.contains(query.trim(), ignoreCase = true))
            }
            if (shown.isEmpty()) {
                // Office's empty Recent: no table headers over nothing, just what the list will hold (or that the search found none).
                PaneEmpty(if (recents.isNotEmpty()) "ic_find" else if (showPinned) "ic_pin" else "ic_clock",
                    stringResource(if (recents.isNotEmpty()) R.string.home_no_matches else if (showPinned) R.string.home_no_pinned else R.string.home_no_recents), Modifier.padding(top = 24.dp))
                if (query.isNotBlank() || filter != null) {
                    com.pico.spatial.ui.design.Link(onClick = { onQuery(""); onFilter(null) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.clear_filter))
                    }
                }
            } else {
                if (!compact) Row(Modifier.fillMaxWidth().padding(top = 14.dp, start = 12.dp, end = 12.dp, bottom = 6.dp)) {
                    Text(stringResource(R.string.home_col_name), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.weight(1f).padding(start = 44.dp))
                    Text(stringResource(R.string.home_col_location), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(180.dp))
                    Text(stringResource(R.string.home_col_opened), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(120.dp))
                    Box(Modifier.width(116.dp))
                } else Box(Modifier.height(10.dp))
                com.pico.spatial.ui.design.HorizontalDivider()
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    shown.forEach { r ->
                        val f = r.file
                        val place = f.parentFile?.let { places[canonical(it)] ?: it.name }.orEmpty()
                        val isPinned = RecentFiles.isPinned(pinned, r.path)
                        RecentRow(f.name, DeskFiles.kindOf(f.name), place, RecentFiles.whenText(r.at), compact, isPinned,
                            onPin = { onPin(r.path, !isPinned) },
                            onOpenInNewWindow = { onOpenInNewWindow(f.absolutePath) }, onRemove = if (showPinned) null else ({ onRemove(r.path) })) { onOpen(f.absolutePath) }
                    }
                }
            }
        }
    }
}

/**
 * Settings that are not about one document: the app's language, the diagnostics bundle for
 * reporting a problem, and the version.
 */
@Composable
private fun SettingsPanel(workDir: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    WideDialog(
        title = stringResource(R.string.home_settings),
        onDismiss = onDismiss,
        buttons = { DialogButton(stringResource(R.string.close), onDismiss, primary = true) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Language: follow the system or pin one (per-app locale; the window reopens in the new language).
            val language = remember { AppLocale.current(context) }
            HomeHeading(stringResource(R.string.language_label))
            // Office's display-language list: each language in its own name, three across; a pick reopens the window in it.
            androidx.compose.foundation.layout.FlowRow(Modifier.width(600.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = 3) {
                AppLanguage.entries.forEach { lang ->
                    ListRow(language == lang, { AppLocale.set(context, lang) }, Modifier.width(194.dp)) {
                        Text(if (lang == AppLanguage.SYSTEM) stringResource(R.string.follow_system) else lang.label, maxLines = 1,
                            style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
            // Proofing: Word's AutoCorrect options (File ▸ Options ▸ Proofing there).
            var autoCorrect by remember { mutableStateOf(false) }
            HomeHeading(stringResource(R.string.group_proofing), Modifier.padding(top = 12.dp))
            DialogButton(stringResource(R.string.ac_control), { autoCorrect = true })
            if (autoCorrect) AutoCorrectOptionsDialog { autoCorrect = false }
            // Diagnostics: crash reports + session log, exported as one zip into the work folder.
            var diagStatus by remember { mutableStateOf("") }
            var crashCount by remember { mutableIntStateOf(com.therealityteam.aeropages.platform.Diagnostics.crashFiles().size) }
            HomeHeading(stringResource(R.string.diagnostics), Modifier.padding(top = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.label_crash_reports, crashCount), style = AeroType.body,
                    color = if (crashCount > 0) PicoTheme.colorScheme.error else PicoTheme.colorScheme.labelSecondary)
                DialogButton(stringResource(R.string.export_diagnostics), {
                    val target = File(workDir, "RealityOffice-diagnostics-${java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())}.zip")
                    diagStatus = runCatching { com.therealityteam.aeropages.platform.Diagnostics.export(context.appInfo(), target) }
                        .fold({ Strings.get(R.string.status_diagnostics_exported, it.name) }, { Strings.get(R.string.err_export_failed, "zip", it.message) })
                })
                if (crashCount > 0) DialogButton(stringResource(R.string.clear_reports), { com.therealityteam.aeropages.platform.Diagnostics.clearCrashes(); crashCount = 0 })
            }
            if (diagStatus.isNotEmpty()) Text(diagStatus, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            Text(stringResource(R.string.home_version, com.therealityteam.aeropages.BuildConfig.VERSION_NAME), style = AeroType.caption,
                color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/** A blank document of one kind, Office's first card in New: a white page with the module's mark, its name and what it makes. */
@Composable
internal fun NewCard(kind: DeskKind, title: String, subtitle: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.width(132.dp).clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .controllerHapticFeedback(interactionSource = interaction),
    ) {
        Box(Modifier.fillMaxWidth().height(164.dp).clip(RoundedCornerShape(10.dp)).background(Color.White), contentAlignment = Alignment.Center) { // design-style: paper
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(accentOf(kind)), contentAlignment = Alignment.Center) {
                ModuleMark(kind, Color.White, 30.dp)
            }
            GazeFill(RoundedCornerShape(10.dp))
        }
        Text(title, style = AeroType.command.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = PicoTheme.colorScheme.labelPrimary,
            maxLines = 1, modifier = Modifier.padding(start = 2.dp, top = 8.dp))
        if (subtitle.isNotEmpty()) Text(subtitle, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
    }
}

/** Canvas-drawn module mark: document page (Pages), slide+chart (Slides), grid (Sheets). */
@Composable
internal fun ModuleMark(kind: DeskKind, color: Color, size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val sw = s * 0.075f
        fun l(a: Float, b: Float, c: Float, d: Float) = drawLine(color, Offset(a * s, b * s), Offset(c * s, d * s), sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        when (kind) {
            DeskKind.PAGES -> {
                // Page with a folded corner + text lines.
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0.24f * s, 0.10f * s); lineTo(0.62f * s, 0.10f * s)
                    lineTo(0.78f * s, 0.26f * s); lineTo(0.78f * s, 0.90f * s)
                    lineTo(0.24f * s, 0.90f * s); close()
                }
                drawPath(p, color, style = androidx.compose.ui.graphics.drawscope.Stroke(sw, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                l(0.62f, 0.10f, 0.62f, 0.26f); l(0.62f, 0.26f, 0.78f, 0.26f)
                l(0.34f, 0.40f, 0.68f, 0.40f); l(0.34f, 0.54f, 0.68f, 0.54f); l(0.34f, 0.68f, 0.56f, 0.68f)
            }
            DeskKind.SLIDES -> {
                // Slide frame with title bar and a rising column chart.
                drawRect(color, Offset(0.14f * s, 0.18f * s), androidx.compose.ui.geometry.Size(0.72f * s, 0.56f * s),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(sw, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                l(0.24f, 0.30f, 0.58f, 0.30f)
                drawRect(color, Offset(0.26f * s, 0.54f * s), androidx.compose.ui.geometry.Size(0.09f * s, 0.12f * s))
                drawRect(color, Offset(0.42f * s, 0.46f * s), androidx.compose.ui.geometry.Size(0.09f * s, 0.20f * s))
                drawRect(color, Offset(0.58f * s, 0.40f * s), androidx.compose.ui.geometry.Size(0.09f * s, 0.26f * s))
                l(0.42f, 0.74f, 0.58f, 0.86f); l(0.58f, 0.74f, 0.42f, 0.86f)
            }
            DeskKind.SHEETS -> {
                // Spreadsheet grid with a header band.
                drawRect(color, Offset(0.16f * s, 0.16f * s), androidx.compose.ui.geometry.Size(0.68f * s, 0.68f * s),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(sw, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                drawRect(color, Offset(0.16f * s, 0.16f * s), androidx.compose.ui.geometry.Size(0.68f * s, 0.16f * s))
                l(0.16f, 0.55f, 0.84f, 0.55f); l(0.16f, 0.70f, 0.84f, 0.70f)
                l(0.39f, 0.32f, 0.39f, 0.84f); l(0.62f, 0.32f, 0.62f, 0.84f)
            }
        }
    }
}

/** A recent document's row: its module's mark and name, where it is and when it was used; open it beside this window, or take it off the list. */
@Composable
private fun RecentRow(name: String, kind: DeskKind, place: String, at: String, compact: Boolean, pinned: Boolean, onPin: () -> Unit,
                      onOpenInNewWindow: () -> Unit, onRemove: (() -> Unit)?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .controllerHapticFeedback(interactionSource = interaction),
    ) {
        GazeFill(RoundedCornerShape(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(accentOf(kind)), contentAlignment = Alignment.Center) {
                ModuleMark(kind, Color.White, 20.dp)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                Text(name, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (compact) Text(listOf(place, at).filter(String::isNotBlank).joinToString(" · "),
                    style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (!compact) {
                Text(place, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, modifier = Modifier.width(180.dp))
                Text(at, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, modifier = Modifier.width(120.dp))
            }
            // The pin, in the accent's tint and always shown while pinned (Office's), quiet until looked at otherwise.
            Box(if (pinned) Modifier else Modifier.spatialHoverEffect { alpha(if (it.isActive) 1f else 0.4f) }) {
                TitleIconButton("ic_pin", stringResource(if (pinned) R.string.unpin_from_list else R.string.pin_to_list), onPin, selected = pinned, size = 36.dp)
            }
            // The row's other commands stay quiet until looked at (Office shows them on hover).
            Row(Modifier.width(80.dp).spatialHoverEffect { alpha(if (it.isActive) 1f else 0.4f) }) {
                TitleIconButton("ic_window_new", stringResource(R.string.open_in_new_window), onOpenInNewWindow, size = 36.dp)
                if (onRemove != null) TitleIconButton("ic_close", stringResource(R.string.home_remove_recent), onRemove, size = 36.dp)
            }
        }
    }
}
