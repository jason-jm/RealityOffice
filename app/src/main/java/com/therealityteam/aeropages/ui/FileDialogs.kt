package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.windows.AlertDialog
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import androidx.compose.foundation.layout.size
import com.pico.spatial.ui.platform.Material
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import com.pico.spatial.ui.design.VerticalDivider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import java.io.File
import androidx.compose.ui.res.stringResource
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.format.DeskFiles
import java.util.Locale

/** A simple file entry the dialogs list. */
data class FileEntry(val name: String, val path: String)

/** Solid, opaque panel surface for floating dialogs (independent of vibrant theming). */
// Partially translucent so the system glass material shows through; if the
// material is unsupported it still reads as a near-opaque light surface.

@Composable
private fun PathField(value: String, onChange: (String) -> Unit) {
    InputBox(value, onChange, Modifier.fillMaxWidth(), monospace = true)
}

/** List image files in [dir], newest first. */
fun listImages(dir: File): List<FileEntry> =
    (dir.listFiles()?.asList() ?: emptyList())
        .filter {
            it.isFile && listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg").any { ext -> it.name.endsWith(ext, true) }
        }
        .sortedByDescending { it.lastModified() }
        .map { FileEntry(it.name, it.absolutePath) }

/** List the video ([video]) or sound files in [dir], newest first. */
fun listMedia(dir: File, video: Boolean): List<FileEntry> =
    (dir.listFiles()?.asList() ?: emptyList())
        .filter { it.isFile && com.therealityteam.aeropages.format.MediaTypes.of(it.name)?.first?.let { k -> (k == com.therealityteam.aeropages.model.SlideMedia.Kind.VIDEO) == video } == true }
        .sortedByDescending { it.lastModified() }
        .map { FileEntry(it.name, it.absolutePath) }

/**
 * Pick a file to insert: those of the app's folder ([images]: pictures by default, or what [title] says it inserts),
 * marked with [icon], or a full path typed in.
 */
@Composable
fun ImageDialog(images: List<FileEntry>, onDismiss: () -> Unit, onPick: (String) -> Unit,
                title: String? = null, hint: String? = null, empty: String? = null, icon: String = "🖼") {
    var manualPath by remember { mutableStateOf("") }
    AeroDialog(title = title ?: stringResource(R.string.title_insert_picture), onDismiss = onDismiss, width = 440.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.insert_path), { if (manualPath.isNotBlank()) onPick(manualPath.trim()) }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(440.dp)) {
                Text(hint ?: stringResource(R.string.label_choose_picture_format), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (images.isEmpty()) {
                        Text(empty ?: stringResource(R.string.msg_no_pictures_in_folder), style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary)
                    }
                    images.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .background(PicoTheme.colorScheme.fillTertiary)
                                .press { onPick(entry.path) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RibbonGlyph(icon, PicoTheme.colorScheme.labelPrimary, 22.dp)
                            Text(entry.name, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
                        }
                    }
                }
                Text(stringResource(R.string.label_enter_full_path), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                PathField(manualPath, { manualPath = it })
            }
        }
}

/**
 * Proofing: the words the device's spell checker does not know — each with its suggestions, one tap
 * to put one in, and Ignore — then repeated words and double spaces; a row's tap goes to its
 * paragraph. While the spell checker works ([checking]) it says so; without one on the device
 * ([spellingAvailable] false) it says only AeroDesk's own checks ran.
 */
@Composable
fun ProofingDialog(
    issues: List<com.therealityteam.aeropages.editor.ProofIssue>, onJump: (Int) -> Unit, onDismiss: () -> Unit,
    checking: Boolean = false, spellingAvailable: Boolean = true,
    onApply: (com.therealityteam.aeropages.editor.ProofIssue, String) -> Unit = { _, _ -> }, onIgnore: (String) -> Unit = {},
    /** Where an issue is, as the list names it: a document's paragraph number by default (a deck names its slide, a workbook its cell). */
    where: (com.therealityteam.aeropages.editor.ProofIssue) -> String = { Strings.get(R.string.label_paragraph_n, it.index + 1) },
) {
    AeroDialog(title = stringResource(R.string.title_spelling_grammar), onDismiss = onDismiss, width = 460.dp, buttons = { DialogButton(stringResource(R.string.close), onDismiss, primary = true) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(460.dp)) {
                if (checking) Text(stringResource(R.string.msg_checking_spelling), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                else if (!spellingAvailable) Text(stringResource(R.string.msg_no_spell_checker), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                if (issues.isEmpty()) {
                    if (!checking) Text(stringResource(R.string.msg_no_issues_found), style = AeroType.body, color = PicoTheme.colorScheme.passable)
                } else {
                    Text(stringResource(R.string.msg_issues_found, issues.size), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        issues.forEach { issue ->
                            // Word's Editor card: where it is and what is wrong, then the suggestions (the first the likeliest) and Ignore.
                            PaneCard(onClick = { onJump(issue.index); onDismiss() }) {
                                Text(where(issue), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                                Text(issue.message, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
                                val word = issue.word
                                if (word != null) WrapRow {
                                    issue.suggestions.take(4).forEachIndexed { i, s -> DialogButton(s, { onApply(issue, s) }, primary = i == 0, small = true) }
                                    DialogButton(stringResource(R.string.btn_ignore), { onIgnore(word) }, small = true)
                                }
                            }
                        }
                    }
                }
            }
        }
}

@Composable
fun LinkDialog(onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var url by remember { mutableStateOf("https://") }
    AeroDialog(title = stringResource(R.string.title_insert_hyperlink), onDismiss = onDismiss, width = 420.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.apply), { if (url.isNotBlank()) onApply(url.trim()) }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(420.dp)) {
                Text(stringResource(R.string.tip_hyperlink_selection),
                    style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                PathField(url, { url = it })
            }
        }
}

@Composable
fun WordCountDialog(words: Int, chars: Int, paragraphs: Int, onDismiss: () -> Unit, charsNoSpaces: Int = -1, cjk: Int = -1, pages: Int = -1, nonAsian: Int = -1) {
    AeroDialog(title = stringResource(R.string.title_word_count), onDismiss = onDismiss, width = 320.dp, buttons = { DialogButton(stringResource(R.string.close), onDismiss, primary = true) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(320.dp)) {
                // Word's order: pages, words, characters (no spaces / with spaces), paragraphs, then the words split into
                // non-Asian words and East Asian characters.
                if (pages >= 0) StatRow(stringResource(R.string.label_pages), pages.toString())
                StatRow(stringResource(R.string.label_words), words.toString())
                if (charsNoSpaces >= 0) StatRow(stringResource(R.string.label_chars_no_spaces), charsNoSpaces.toString())
                StatRow(stringResource(R.string.label_chars_with_spaces), chars.toString())
                StatRow(stringResource(R.string.label_paragraphs), paragraphs.toString())
                if (nonAsian >= 0) StatRow(stringResource(R.string.label_non_asian_words), nonAsian.toString())
                if (cjk >= 0) StatRow(stringResource(R.string.label_chinese_characters), cjk.toString())
            }
        }
}

/**
 * Office's statistics dialogs (PowerPoint's deck statistics, Excel's Workbook Statistics): rows of counts, in sections
 * under their own headings when there are several (Excel's current sheet, then the workbook).
 */
@Composable
internal fun StatisticsDialog(title: String, sections: List<Pair<String?, List<Pair<String, String>>>>, onDismiss: () -> Unit) {
    AeroDialog(title = title, onDismiss = onDismiss, width = 360.dp, buttons = { DialogButton(stringResource(R.string.close), onDismiss, primary = true) }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.width(360.dp)) {
            for ((heading, rows) in sections) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                heading?.let { DialogLabel(it) }
                rows.forEach { (label, value) -> StatRow(label, value) }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary)
        Text(value, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
    }
}

/**
 * The 文件 menu: a vertical dropdown anchored under the 文件 tab (Word/WPS style).
 * A transparent full-screen layer dismisses on outside tap; the menu card itself
 * is a solid opaque surface.
 */
@Composable
fun FileMenu(
    dirty: Boolean,
    autoSave: Boolean,
    recents: List<FileEntry>,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    /** The module's templates (New from template), and what picking one does. */
    templates: List<DeskTemplate>,
    onNewFrom: (DeskTemplate) -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onExportPdf: () -> Unit,
    onToggleAutoSave: () -> Unit,
    onOpenRecent: (String) -> Unit,
    newLabel: String = stringResource(R.string.new_document),
    onOpenSystem: (() -> Unit)? = null,
    onSaveCopy: (() -> Unit)? = null,
    onExportCsv: (() -> Unit)? = null,
    /** File → Info (the properties and statistics), when the module has it. */
    onInfo: (() -> Unit)? = null,
) {
    // Office's backstage: the whole window, the places and commands down the left, the page on the right.
    var page by remember { mutableStateOf(BackstagePage.NEW) }
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxSize()
            .background(PicoTheme.colorScheme.fillLight) // design-style: design-surface backstage
            .backgroundMaterial(style = Material.Thick)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        com.pico.spatial.ui.design.SideNavigation(
            modifier = Modifier.width(260.dp).fillMaxHeight(),
            header = {
                Box(Modifier.padding(top = 20.dp, bottom = 12.dp)) {
                    TitleIconButton("ic_arrow_left_long", stringResource(R.string.back), onDismiss, size = 44.dp)
                }
            },
        ) {
            BackstageItem("ic_plus", stringResource(R.string.home_nav_new), page == BackstagePage.NEW) { page = BackstagePage.NEW }
            BackstageItem("ic_folder", stringResource(R.string.open), page == BackstagePage.OPEN) { page = BackstagePage.OPEN }
            onInfo?.let { BackstageItem("ic_info", stringResource(R.string.file_info), false) { it(); onDismiss() } }
            com.pico.spatial.ui.design.HorizontalDivider(Modifier.padding(vertical = 10.dp))
            BackstageItem("ic_save", stringResource(R.string.save), false, mark = dirty) { onSave(); onDismiss() }
            BackstageItem("ic_save_as", stringResource(R.string.save_as), false) { onSaveAs(); onDismiss() }
            onSaveCopy?.let { BackstageItem("ic_copy", stringResource(R.string.save_copy_to), false) { it(); onDismiss() } }
            BackstageItem("ic_export_pdf", stringResource(R.string.export), page == BackstagePage.EXPORT) { page = BackstagePage.EXPORT }
            com.pico.spatial.ui.design.HorizontalDivider(Modifier.padding(vertical = 10.dp))
            // AutoSave, Office's switch at the top of the window, here among the commands.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.autosave), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f))
                com.pico.spatial.ui.design.Switch(checked = autoSave, onCheckedChange = { onToggleAutoSave() })
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 40.dp, end = 40.dp, top = 32.dp).verticalScroll(rememberScrollState())) {
            when (page) {
                BackstagePage.NEW -> {
                    Text(stringResource(R.string.home_nav_new), style = AeroType.pageTitle, color = PicoTheme.colorScheme.labelPrimary)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(top = 20.dp),
                    ) {
                        templates.firstOrNull { it.blank }?.let { blank ->
                            NewCard(blank.kind, stringResource(blank.label), "") { onNew(); onDismiss() }
                        }
                        templates.filter { !it.blank }.forEach { t -> TemplateCard(t) { onNewFrom(t); onDismiss() } }
                    }
                    if (recents.isNotEmpty()) {
                        Text(stringResource(R.string.recent), style = AeroType.heading, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.padding(top = 28.dp, bottom = 8.dp))
                        BackstageRecents(recents.take(6), onOpenRecent, onDismiss)
                    }
                }
                BackstagePage.OPEN -> {
                    Text(stringResource(R.string.open), style = AeroType.pageTitle, color = PicoTheme.colorScheme.labelPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 16.dp)) {
                        RibbonSmallButton("ic_folder", stringResource(R.string.open_ellipsis), { onOpen(); onDismiss() })
                        onOpenSystem?.let { RibbonSmallButton("ic_share", stringResource(R.string.open_from_device), { it(); onDismiss() }) }
                    }
                    // Office's Open: the pinned documents first, then the rest of Recent.
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val pinnedPaths = remember { RecentFiles.pinned(context) }
                    val pinnedFiles = remember { RecentFiles.forPinned(context, emptyList()).map { FileEntry(it.file.name, it.path) } }
                    if (pinnedFiles.isNotEmpty()) {
                        Text(stringResource(R.string.home_pinned), style = AeroType.heading, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                        BackstageRecents(pinnedFiles, onOpenRecent, onDismiss, pinned = true)
                    }
                    val rest = recents.filter { !RecentFiles.isPinned(pinnedPaths, it.path) }
                    Text(stringResource(R.string.recent), style = AeroType.heading, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                    if (rest.isEmpty()) PaneEmpty("ic_clock", stringResource(R.string.home_no_recents), Modifier.padding(top = 16.dp))
                    else BackstageRecents(rest, onOpenRecent, onDismiss)
                }
                BackstagePage.EXPORT -> {
                    Text(stringResource(R.string.export), style = AeroType.pageTitle, color = PicoTheme.colorScheme.labelPrimary)
                    Column(Modifier.padding(top = 16.dp).width(420.dp)) {
                        MenuCommand(stringResource(R.string.export_as_pdf), { onExportPdf(); onDismiss() }, icon = "ic_export_pdf", detail = stringResource(R.string.export_pdf_detail))
                        onExportCsv?.let { MenuCommand(stringResource(R.string.export_csv), { it(); onDismiss() }, icon = "ic_document", detail = stringResource(R.string.export_csv_detail)) }
                    }
                }
            }
            Box(Modifier.height(32.dp))
        }
    }
}

/** The backstage's pages: what the right side shows. */
private enum class BackstagePage { NEW, OPEN, EXPORT }

/** One place or command down the backstage's left side. */
@Composable
private fun BackstageItem(icon: String, label: String, selected: Boolean, mark: Boolean = false, onClick: () -> Unit) {
    com.pico.spatial.ui.design.SideNavigationItem(
        selected = selected,
        modifier = Modifier.sideNavRow(onClick),
        leading = { RibbonGlyph(icon, PicoTheme.colorScheme.labelPrimary, 20.dp) },
        // Unsaved changes: the accent dot after Save.
        trailing = if (mark) ({ Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(LocalAccent.current)) }) else null,
    ) { Text(label, maxLines = 1) }
}

/** Recent documents in the backstage: the module's mark, the name and its folder. */
@Composable
private fun BackstageRecents(recents: List<FileEntry>, onOpenRecent: (String) -> Unit, onDismiss: () -> Unit, pinned: Boolean = false) {
    Column(Modifier.widthIn(max = 720.dp)) {
        recents.forEach { entry ->
            val kind = com.therealityteam.aeropages.format.DeskFiles.kindOf(entry.name)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .press { onOpenRecent(entry.path); onDismiss() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(accentOf(kind)), contentAlignment = Alignment.Center) {
                    ModuleMark(kind, Color.White, 20.dp)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(entry.name, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
                    Text(File(entry.path).parent.orEmpty(), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
                }
                // A pinned document says so (pins are set on the home page's list).
                if (pinned) RibbonGlyph("ic_pin", LocalAccent.current, 18.dp)
            }
        }
    }
}

// --- Spatial UI file browser (open / save) ---------------------------------

enum class BrowserMode { OPEN, SAVE }

/** A shortcut location shown above the listing. */
/** What a sidebar shortcut points at; picks its glyph independently of the (localised) label. */
enum class ShortcutKind { ROOT, DOWNLOADS, PICTURES, APP, FOLDER }

data class Shortcut(val label: String, val dir: File, val kind: ShortcutKind = ShortcutKind.FOLDER)

/**
 * A directory-navigating file browser presented as a Spatial UI floating panel.
 * In [BrowserMode.OPEN] tapping a .doc/.docx file opens it; in [BrowserMode.SAVE]
 * the user navigates to a folder, types a name, and saves. Folders are always
 * navigable. When full-disk access is missing, a banner offers to grant it.
 */
@Composable
fun FileBrowser(
    mode: BrowserMode,
    startDir: File,
    shortcuts: List<Shortcut>,
    suggestedName: String,
    hasStorageAccess: Boolean,
    onRequestStorage: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onSave: (String) -> Unit,
    title: String = if (mode == BrowserMode.OPEN) stringResource(R.string.title_open_file) else stringResource(R.string.title_save_document),
    /** File types this module can write; the first is the default. */
    saveExtensions: List<String> = listOf("docx"),
    initialSaveExtension: String = saveExtensions.first(),
    openExtensions: Set<String> = DeskFiles.OPENABLE,
) {
    var currentDir by remember { mutableStateOf(if (startDir.isDirectory) startDir else (startDir.parentFile ?: startDir)) }
    var fileName by remember { mutableStateOf(suggestedName) }
    // The chosen file type; a document opened as .ods keeps that type when it is saved again.
    var saveExtension by remember { mutableStateOf(initialSaveExtension) }
    val target = remember(currentDir, fileName, saveExtension) { browserSaveTarget(currentDir, fileName, saveExtension) }
    val overwrite = target?.isFile == true
    // Re-list whenever the directory or permission state changes — on IO, since a camera
    // roll or Download folder with thousands of entries must not stall the frame.
    val listing by androidx.compose.runtime.produceState(DirListing(emptyList(), emptyList(), emptyMap()), currentDir, hasStorageAccess, openExtensions) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { listDir(currentDir, mode, openExtensions) }
    }

    // NOT the system AlertDialog: its spatial sub-window clamps width (~600dp token)
    // regardless of content. A full-window overlay card gives the browser real width.
    WideDialog(title = title, onDismiss = onDismiss, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogButton(stringResource(R.string.cancel), onDismiss)
            if (mode == BrowserMode.SAVE) {
                DialogButton(if (overwrite) stringResource(R.string.overwrite) else stringResource(R.string.save),
                    primary = true, enabled = target != null && !target.isDirectory, onClick = { target?.let { onSave(it.absolutePath) } })
            }
        }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (!hasStorageAccess) {
                MessageBar(stringResource(R.string.msg_no_storage_access), rounded = true) {
                    DialogButton(stringResource(R.string.grant_access), onRequestStorage, primary = true, small = true)
                    DialogButton(stringResource(R.string.refresh), onRefresh, small = true)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(430.dp)) {
                // 位置 sidebar (macOS-style locations column).
                Column(
                    modifier = Modifier.width(156.dp).fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    DialogLabel(stringResource(R.string.locations), Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp))
                    shortcuts.forEach { sc ->
                        SidebarItem(sc.label, sc.kind, selected = currentDir.absolutePath == sc.dir.absolutePath) { currentDir = sc.dir }
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Breadcrumb path: up button + last segments, each clickable.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TitleIconButton("ic_arrow_up", stringResource(R.string.up_one_level), { currentDir.parentFile?.let { currentDir = it } },
                            enabled = currentDir.parentFile != null, size = 34.dp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(Aero.commandRadius)).background(Aero.field)
                                .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                        ) {
                            val segs = currentDir.absolutePath.trim('/').split('/').filter { it.isNotEmpty() }
                            val shown = segs.takeLast(3)
                            if (segs.size > shown.size) Text("… ", style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                            shown.forEachIndexed { i, seg ->
                                val depth = segs.size - shown.size + i + 1
                                val target = "/" + segs.take(depth).joinToString("/")
                                Text(
                                    seg,
                                    style = AeroType.body,
                                    color = if (i == shown.lastIndex) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary,
                                    maxLines = 1,
                                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).press { currentDir = File(target) }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                                if (i != shown.lastIndex) RibbonGlyph("ic_chevron_right", PicoTheme.colorScheme.labelTertiary, 14.dp)
                            }
                        }
                    }

                    // Listing on a white card so entries read clearly against the dialog: Explorer's columns.
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp)).background(Aero.field),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(start = 46.dp, end = 16.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            DialogLabel(stringResource(R.string.home_col_name), Modifier.weight(1f))
                            DialogLabel(stringResource(R.string.col_modified), Modifier.width(132.dp))
                            DialogLabel(stringResource(R.string.col_size), Modifier.width(72.dp))
                        }
                        com.pico.spatial.ui.design.HorizontalDivider()
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(4.dp)) {
                            if (listing.dirs.isEmpty() && listing.files.isEmpty()) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                ) {
                                    FsGlyph(null, 40.dp)
                                    Text(stringResource(R.string.msg_folder_empty), style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary)
                                }
                            }
                            listing.dirs.forEach { dir ->
                                EntryRow({ FsGlyph(null, 22.dp) }, dir.name, date = fmtDate(dir.lastModified()),
                                    size = listing.childCounts[dir]?.let { stringResource(R.string.label_item_count, it) } ?: "", onClick = { currentDir = dir })
                            }
                            listing.files.forEach { file ->
                                EntryRow({ FsGlyph(file.name, 22.dp) }, file.name, date = fmtDate(file.lastModified()), size = fmtSize(file.length()),
                                    dim = mode == BrowserMode.SAVE && !file.name.endsWith(".$saveExtension", true),
                                    selected = mode == BrowserMode.SAVE && file == target,
                                    onClick = {
                                        if (mode == BrowserMode.OPEN) onOpen(file.absolutePath)
                                        else fileName = file.nameWithoutExtension
                                    })
                            }
                        }
                    }
                }
            }

            if (mode == BrowserMode.SAVE) {
                FieldRow(stringResource(R.string.file_name), labelWidth = 96.dp) {
                    Box(Modifier.weight(1f)) { PathField(fileName, { fileName = it }) }
                    Text(".$saveExtension", style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary)
                }
                if (saveExtensions.size > 1) {
                    FieldRow(stringResource(R.string.save_as_type), labelWidth = 96.dp) {
                        ComboField(".$saveExtension", saveExtensions, { ".$it" }, saveExtension, { saveExtension = it }, width = 160.dp)
                    }
                }
                if (fileName.isNotBlank() && (target == null || target.isDirectory)) {
                    MessageBar(stringResource(R.string.invalid_save_name), rounded = true)
                } else if (overwrite) {
                    MessageBar(stringResource(R.string.warn_file_exists, target.name), rounded = true)
                }
            }
        }
    }
}

/** The name field accepts a leaf name. Only a known document extension is replaced by the chosen type. */
internal fun browserSaveTarget(directory: File, entered: String, extension: String): File? {
    val name = entered.trim()
    if (name.isEmpty() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it.isISOControl() }) return null
    // A name consisting solely of an extension would create a hidden, nameless document.
    if (name.startsWith('.') && name.lastIndexOf('.') == 0 && name.drop(1).lowercase() in DeskFiles.OPENABLE) return null
    return File(DocumentSession.resolveSavePath(File(directory, name).path, extension))
}

/**
 * Office's dialog, in the window: the content behind dimmed, a light card with the title and a close button
 * on top, the content, and the actions at the bottom right. Used where the system AlertDialog's fixed-width
 * sub-window is too narrow; a tap outside the card dismisses it.
 */
@Composable
internal fun WideDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x52000000)) // design-style: modal scrim
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.74f)
                .widthIn(max = 960.dp)
                .clip(RoundedCornerShape(24.dp))
                // design-style: design-surface dialog card
                .background(PicoTheme.colorScheme.fillLight)
                .backgroundMaterial(style = Material.Thick)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = 28.dp, end = 20.dp, top = 18.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(title, style = AeroType.title, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f))
                TitleIconButton("ic_close", stringResource(R.string.close), onDismiss, size = 40.dp)
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
            Row(modifier = Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) { buttons() }
        }
    }
}

/**
 * A dialog of the size of its content in [WideDialog]'s Office frame — title and close at the top, the actions at the
 * bottom right — in a system dialog window of its own (PICO's `BasicAlertDialog`), so it can be opened from anywhere:
 * a ribbon tab, a pane, a canvas bar. [width] is the content's width.
 */
@Composable
internal fun AeroDialog(
    title: String,
    onDismiss: () -> Unit,
    width: androidx.compose.ui.unit.Dp,
    buttons: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    com.pico.spatial.ui.design.windows.BasicAlertDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(width + 52.dp)
                .clip(RoundedCornerShape(24.dp))
                // design-style: design-surface dialog card
                .background(PicoTheme.colorScheme.fillLight)
                .backgroundMaterial(style = Material.Thick)
                .padding(start = 26.dp, end = 26.dp, top = 18.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(title, style = AeroType.title, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f))
                TitleIconButton("ic_close", stringResource(R.string.close), onDismiss, size = 40.dp)
            }
            content()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = buttons)
        }
    }
}

/** One sidebar location row: small glyph + single-line label; selected = accent tint. */
@Composable
private fun SidebarItem(label: String, kind: ShortcutKind, selected: Boolean, onClick: () -> Unit) {
    ListRow(selected, onClick) {
        ShortcutGlyph(kind, if (selected) LocalAccent.current else PicoTheme.colorScheme.labelSecondary)
        Box(Modifier.width(10.dp))
        Text(label, style = if (selected) AeroType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else AeroType.body,
            color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
    }
}

/** Small vector glyph for a sidebar location, picked by its [ShortcutKind]. */
@Composable
private fun ShortcutGlyph(kind: ShortcutKind, tint: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(15.dp)) {
        val s = size.minDimension
        val sw = s * 0.11f
        fun l(a: Float, b: Float, c: Float, d: Float) = drawLine(tint, androidx.compose.ui.geometry.Offset(a * s, b * s), androidx.compose.ui.geometry.Offset(c * s, d * s), sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        when (kind) {
            ShortcutKind.ROOT -> { // house
                l(0.5f, 0.12f, 0.1f, 0.48f); l(0.5f, 0.12f, 0.9f, 0.48f)
                l(0.2f, 0.42f, 0.2f, 0.88f); l(0.8f, 0.42f, 0.8f, 0.88f); l(0.2f, 0.88f, 0.8f, 0.88f)
            }
            ShortcutKind.DOWNLOADS -> { // arrow into tray
                l(0.5f, 0.1f, 0.5f, 0.56f); l(0.3f, 0.4f, 0.5f, 0.6f); l(0.7f, 0.4f, 0.5f, 0.6f)
                l(0.12f, 0.84f, 0.88f, 0.84f)
            }
            ShortcutKind.PICTURES -> { // picture
                drawRect(tint, androidx.compose.ui.geometry.Offset(0.12f * s, 0.18f * s), androidx.compose.ui.geometry.Size(0.76f * s, 0.64f * s), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                l(0.2f, 0.7f, 0.45f, 0.42f); l(0.45f, 0.42f, 0.62f, 0.62f); l(0.62f, 0.62f, 0.74f, 0.5f); l(0.74f, 0.5f, 0.86f, 0.66f)
            }
            ShortcutKind.APP -> { // app box
                drawRect(tint, androidx.compose.ui.geometry.Offset(0.16f * s, 0.16f * s), androidx.compose.ui.geometry.Size(0.68f * s, 0.68f * s), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                l(0.16f, 0.42f, 0.84f, 0.42f); l(0.5f, 0.42f, 0.5f, 0.84f)
            }
            ShortcutKind.FOLDER -> { // document
                l(0.24f, 0.12f, 0.62f, 0.12f); l(0.62f, 0.12f, 0.78f, 0.28f); l(0.78f, 0.28f, 0.78f, 0.88f)
                l(0.78f, 0.88f, 0.24f, 0.88f); l(0.24f, 0.88f, 0.24f, 0.12f)
                l(0.36f, 0.45f, 0.66f, 0.45f); l(0.36f, 0.62f, 0.66f, 0.62f)
            }
        }
    }
}

@Composable
private fun EntryRow(icon: @Composable () -> Unit, name: String, date: String = "", size: String = "", dim: Boolean = false, selected: Boolean = false, onClick: () -> Unit) {
    ListRow(selected, onClick, Modifier.heightIn(min = 38.dp)) {
        icon()
        Text(middleEllipsis(name), style = AeroType.body,
            color = if (dim) PicoTheme.colorScheme.labelTertiary else PicoTheme.colorScheme.labelPrimary,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp))
        Text(date, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, modifier = Modifier.width(132.dp))
        Text(size, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, modifier = Modifier.width(60.dp))
    }
}

/**
 * Middle-ellipsis for long file names so the END (extension / distinguishing tail)
 * stays visible. Width-weighted (CJK chars count double) against a budget that
 * comfortably fits the listing column; Compose tail-ellipsis remains as a fallback.
 */
internal fun middleEllipsis(name: String, budget: Int = 84): String {
    fun w(c: Char) = if (c.code > 0x2E7F) 2 else 1
    if (name.sumOf { w(it) } <= budget) return name
    val keepTail = 14
    val tail = StringBuilder()
    var tw = 0
    for (c in name.reversed()) { tw += w(c); if (tw > keepTail) break; tail.append(c) }
    val tailStr = tail.reverse().toString()
    val headBudget = budget - keepTail - 2
    val head = StringBuilder()
    var hw = 0
    for (c in name) { hw += w(c); if (hw > headBudget) break; head.append(c) }
    return "$head…$tailStr"
}

/** Canvas-drawn folder (name=null) or file glyph, file corner tinted by module kind. */
@Composable
private fun FsGlyph(name: String?, size: androidx.compose.ui.unit.Dp) {
    val kind = name?.let { com.therealityteam.aeropages.format.DeskFiles.kindOfOrNull(it) }
    val tint = when (kind) {
        com.therealityteam.aeropages.format.DeskKind.PAGES -> Color(0xFF2B579A)
        com.therealityteam.aeropages.format.DeskKind.SLIDES -> Color(0xFFC43E1C)
        com.therealityteam.aeropages.format.DeskKind.SHEETS -> Color(0xFF217346)
        else -> Color(0xFF8A8A8E)
    }
    val line = PicoTheme.colorScheme.labelSecondary
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val sw = s * 0.075f
        if (name == null) {
            // Folder: flat two-tone (darker tab behind, lighter body in front).
            drawRoundRect(Color(0xFFE3A82E), androidx.compose.ui.geometry.Offset(0.12f * s, 0.24f * s),
                androidx.compose.ui.geometry.Size(0.34f * s, 0.2f * s), androidx.compose.ui.geometry.CornerRadius(0.05f * s))
            drawRoundRect(Color(0xFFF6C453), androidx.compose.ui.geometry.Offset(0.1f * s, 0.32f * s),
                androidx.compose.ui.geometry.Size(0.8f * s, 0.48f * s), androidx.compose.ui.geometry.CornerRadius(0.07f * s))
        } else {
            // Page with folded corner, fold tinted per module kind, two text lines.
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(0.22f * s, 0.1f * s); lineTo(0.62f * s, 0.1f * s); lineTo(0.8f * s, 0.28f * s)
                lineTo(0.8f * s, 0.9f * s); lineTo(0.22f * s, 0.9f * s); close()
            }
            drawPath(p, Color.White)
            drawPath(p, line, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
            val fold = androidx.compose.ui.graphics.Path().apply {
                moveTo(0.62f * s, 0.1f * s); lineTo(0.62f * s, 0.28f * s); lineTo(0.8f * s, 0.28f * s); close()
            }
            drawPath(fold, tint)
            drawLine(tint, androidx.compose.ui.geometry.Offset(0.32f * s, 0.48f * s), androidx.compose.ui.geometry.Offset(0.7f * s, 0.48f * s), sw)
            drawLine(line, androidx.compose.ui.geometry.Offset(0.32f * s, 0.62f * s), androidx.compose.ui.geometry.Offset(0.7f * s, 0.62f * s), sw)
            drawLine(line, androidx.compose.ui.geometry.Offset(0.32f * s, 0.76f * s), androidx.compose.ui.geometry.Offset(0.56f * s, 0.76f * s), sw)
        }
    }
}

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun fmtDate(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))

private class DirListing(val dirs: List<File>, val files: List<File>, val childCounts: Map<File, Int>)

/** Enumerate [dir] (call on IO): visible sub-folders with their entry counts, and the openable files. */
private fun listDir(dir: File, mode: BrowserMode, openExtensions: Set<String>): DirListing {
    val all = dir.listFiles()?.toList() ?: emptyList()
    val dirs = all.filter { it.isDirectory && !it.isHidden }.sortedBy { it.name.lowercase() }
    val files = all.filter {
        it.isFile && !it.isHidden &&
            (mode == BrowserMode.SAVE || it.extension.lowercase() in openExtensions)
    }.sortedBy { it.name.lowercase() }
    val counts = HashMap<File, Int>()
    dirs.forEach { d -> d.list()?.size?.let { counts[d] = it } }
    return DirListing(dirs, files, counts)
}

/** One keyboard shortcut: key combo (e.g. "Ctrl+B") + what it does. */
class ShortcutEntry(val keys: String, val action: String)

/** Shared 快捷键帮助 dialog: grouped two-column listing of a module's shortcuts. */
@Composable
fun ShortcutsDialog(module: String, groups: List<Pair<String, List<ShortcutEntry>>>, onDismiss: () -> Unit) {
    AeroDialog(title = stringResource(R.string.title_keyboard_shortcuts, module), onDismiss = onDismiss, width = 420.dp, buttons = { DialogButton(stringResource(R.string.close), onDismiss, primary = true) }) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.width(420.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            ) {
                groups.forEach { (title, entries) ->
                    Text(title, style = AeroType.label, color = PicoTheme.colorScheme.interaction,
                        modifier = Modifier.padding(top = 8.dp))
                    entries.forEach { e ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(e.action, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
                            Text(e.keys, style = AeroType.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = PicoTheme.colorScheme.labelPrimary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White)
                                    .border(1.dp, Aero.barEdge, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
}

/** Shortcuts common to all three modules. */
val COMMON_SHORTCUTS: List<Pair<String, List<ShortcutEntry>>> get() = listOf(
    Strings.get(R.string.general) to listOf(
        ShortcutEntry("Ctrl+Z", Strings.get(R.string.undo)),
        ShortcutEntry("Ctrl+Y", Strings.get(R.string.redo)),
        ShortcutEntry("Ctrl+S", Strings.get(R.string.save)),
    ),
)

/**
 * Office-style unsaved-changes prompt shown before a destructive action (leaving to
 * the launcher, new document, opening another file): 保存 / 不保存 / 取消.
 */
@Composable
fun UnsavedChangesDialog(docName: String, onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.title_save_changes)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(360.dp)) {
                Text(stringResource(R.string.msg_unsaved_changes, docName), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
                Text(stringResource(R.string.msg_changes_lost), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            }
        },
        buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogButton(stringResource(R.string.cancel), onCancel)
                DialogButton(stringResource(R.string.dont_save), onDiscard)
                DialogButton(stringResource(R.string.save), onSave, primary = true)
            }
        },
    )
}

/** The source file was modified by another app after we opened it: overwrite, save a copy, or cancel. */
@Composable
fun FileChangedDialog(docName: String, onOverwrite: () -> Unit, onSaveAs: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.title_file_changed)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(380.dp)) {
                Text(stringResource(R.string.msg_file_changed, docName), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
                Text(stringResource(R.string.msg_file_changed_choice), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            }
        },
        buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogButton(stringResource(R.string.cancel), onCancel)
                DialogButton(stringResource(R.string.save_as), onSaveAs)
                DialogButton(stringResource(R.string.overwrite), onOverwrite, primary = true)
            }
        },
    )
}

/**
 * Saving into a plain [type] (CSV, text) that cannot keep [what] of the document: as [betterName]
 * of the module's own type instead (the default), in the plain type anyway, or not at all.
 */
@Composable
fun LossyFormatDialog(fileName: String, type: String, what: String, betterType: String, betterName: String, onSaveBetter: () -> Unit, onKeep: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.title_lossy_format)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(440.dp)) {
                Text(stringResource(R.string.msg_lossy_format, fileName, type, what), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
                Text(stringResource(R.string.msg_lossy_format_choice, betterName), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            }
        },
        buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogButton(stringResource(R.string.cancel), onCancel)
                DialogButton(stringResource(R.string.btn_keep_format, type), onKeep)
                DialogButton(stringResource(R.string.btn_save_better, betterType), onSaveBetter, primary = true)
            }
        },
    )
}

/**
 * A file that could not be opened: one locked with a password (what to do about it), or one that
 * could not be read (perhaps damaged), with the reader's [details] in small print.
 */
@Composable
fun OpenProblemDialog(name: String, encrypted: Boolean, details: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_cannot_open)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(440.dp)) {
                Text(stringResource(if (encrypted) R.string.msg_open_encrypted else R.string.msg_open_unreadable, name),
                    style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
                if (!encrypted && !details.isNullOrBlank()) Text(stringResource(R.string.msg_open_details, details),
                    style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
            }
        },
        buttons = { DialogButton(stringResource(R.string.btn_ok), onDismiss, primary = true) },
    )
}

/**
 * Saving over a file that holds content AeroDesk cannot edit ([what]): the changes saved as a copy
 * named [copyName] beside it (the default), the original replaced anyway, or nothing.
 */
@Composable
fun KeepOriginalDialog(docName: String, copyName: String, what: String, onSaveCopy: () -> Unit, onReplace: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.title_keep_original)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(440.dp)) {
                Text(stringResource(R.string.msg_keep_original, docName, what), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
                Text(stringResource(R.string.msg_keep_original_choice, copyName), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            }
        },
        buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogButton(stringResource(R.string.cancel), onCancel)
                DialogButton(stringResource(R.string.btn_replace_original), onReplace)
                DialogButton(stringResource(R.string.btn_save_copy), onSaveCopy, primary = true)
            }
        },
    )
}

/** Office AutoRecover prompt: a draft of a never-saved document was found on entry. */
@Composable
fun DraftRecoverDialog(savedAtMillis: Long, onRecover: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDiscard,
        title = { Text(stringResource(R.string.title_recover_document)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(380.dp)) {
                Text(stringResource(R.string.msg_draft_detected), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
                Text(stringResource(R.string.msg_draft_location, fmtDate(savedAtMillis)), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            }
        },
        buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogButton(stringResource(R.string.discard_draft), onDiscard)
                DialogButton(stringResource(R.string.recover), onRecover, primary = true)
            }
        },
    )
}
