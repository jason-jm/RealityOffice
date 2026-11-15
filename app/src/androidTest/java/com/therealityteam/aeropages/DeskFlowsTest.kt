package com.therealityteam.aeropages

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import com.therealityteam.aeropages.ui.OpenRequests
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.DocumentRepository
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Workbook
import com.therealityteam.aeropages.ui.AeroDesk
import com.therealityteam.aeropages.ui.RecentFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The main flows through the real composables: launcher → new document → edit → save → home → open
 * from the recent list, for each module. The suite hosts [AeroDesk] in a plain ComponentActivity; the
 * spatial shell is replaced by a recording [SpatialNavigator], which is all the UI reaches for outside
 * a WindowContainer. Labels come from the app's own resources, so the tests pass in every language.
 *
 * Run on a PICO emulator or device:
 *   ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class DeskFlowsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workDir: File
    private val navigator = RecordingNavigator()
    private val windowWidth = mutableStateOf<Dp?>(null)

    private fun str(id: Int): String = context.getString(id)

    @Before
    fun setUp() {
        TestSupport.installCrashLogger()
        TestSupport.clearDevProps()
        workDir = (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() }
        cleanWorkDir()
        // No remembered side panes (they would open Subwindows) and no recovered-draft prompt.
        context.getSharedPreferences("aeropages", 0).edit().clear().commit()
        rule.setContent {
            PicoTheme {
                CompositionLocalProvider(LocalSpatialNavigator provides navigator) {
                    Box(windowWidth.value?.let { Modifier.width(it).fillMaxHeight() } ?: Modifier) { AeroDesk() }
                }
            }
        }
    }

    @After
    fun tearDown() {
        // RecordingNavigator has no second composition to consume a request.
        OpenRequests.pending.value.filter { it.target in navigator.opened }.forEach(OpenRequests::consume)
        cleanWorkDir()
    }

    private fun cleanWorkDir() {
        workDir.listFiles()?.filter { it.name.startsWith(PREFIX) || it.name.startsWith(".draft-") }?.forEach { it.delete() }
    }

    // --- flows ------------------------------------------------------------------------------

    @Test
    fun launcherOffersTheThreeModulesAndOpen() = guarded("launcher") {
        rule.onNodeWithText(str(R.string.app_name)).assertIsDisplayed()
        rule.onNodeWithText("Pages").assertIsDisplayed()
        rule.onNodeWithText("Slides").assertIsDisplayed()
        rule.onNodeWithText("Sheets").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.open_file_button)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.open_in_new_window)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.home_templates)).assertIsDisplayed()
    }

    @Test
    fun pagesNewTypeSaveHomeReopen() = guarded("pages") {
        rule.onNodeWithText("Pages").performClick()
        val body = "Hello from the UI test"
        paragraphField().performTextInput(body)
        waitFor(hasText(body, substring = true))
        val file = saveAs(name = "$PREFIX-Pages.v2", ext = "docx")
        goHome()
        openRecent(file.name)
        waitFor(hasText(body, substring = true))
    }

    @Test
    fun sheetsNewFormulaSaveHomeReopen() = guarded("sheets") {
        rule.onNodeWithText("Sheets").performClick()
        // The formula bar edits the selected cell (A1) as you type; the grid shows the value.
        formulaBar().performTextInput("=200+222")
        waitFor(hasText("422"))
        val file = saveAs(name = "$PREFIX-Sheets", ext = "xlsx")
        assertEquals("=200+222", XlsxReader.read(file.readBytes()).sheets[0].cell(0, 0)?.text)
        goHome()
        openRecent(file.name)
        waitFor(hasText("422"))
    }

    @Test
    fun openingAWorkbookFromPagesProtectsEditsAndSwitchesEditor() = guarded("open-workbook-from-pages") {
        val workbook = File(workDir, "$PREFIX-from-pages.xlsx")
        val cellText = "Cross-editor workbook cell"
        val model = Workbook.blank().also { it.sheets[0].ensure(0, 0).text = cellText }
        workbook.outputStream().use { XlsxWriter.write(model, it) }
        rule.onNodeWithText("Pages").performClick()
        val edit = "Keep this Pages edit"
        paragraphField().performTextInput(edit)

        fun chooseWorkbook() {
            rule.onNodeWithText(str(R.string.menu_file)).performClick()
            pill(R.string.open)
            button(R.string.open_ellipsis)
            val location = hasText(str(R.string.storage_app_folder)) and hasClickAction()
            waitFor(location)
            rule.onNode(location).performClick()
            val row = hasText(workbook.name) and hasClickAction()
            waitFor(row)
            rule.onNode(row).performScrollTo().performClick()
            waitFor(hasText(str(R.string.title_save_changes)))
        }

        chooseWorkbook()
        pill(R.string.cancel)
        waitFor(hasText(edit, substring = true))
        chooseWorkbook()
        pill(R.string.save)
        val file = File(workDir, "$PREFIX-before-switch.docx")
        val nameField = hasSetTextAction() and hasAnySibling(hasText(str(R.string.file_name)))
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(nameField, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(nameField, useUnmergedTree = true).performTextReplacement(file.nameWithoutExtension)
        rule.onAllNodes(hasText(str(R.string.save)) and hasClickAction()).onLast().performClick()
        waitFor(hasText(cellText))
        assertEquals(edit, DocumentRepository.open(file.path).paragraphs().first().plainText())
        assertEquals(cellText, XlsxReader.read(workbook.readBytes()).sheets[0].cell(0, 0)?.text)
    }

    @Test
    fun openingAPinnedDocumentFromSheetsUsesPages() = guarded("open-pinned-pages") {
        val file = File(workDir, "$PREFIX-pinned.txt").apply { writeText("Pinned Pages document") }
        RecentFiles.setPinned(context, file.path, true)
        rule.onNodeWithText("Sheets").performClick()
        rule.onNodeWithText(str(R.string.menu_file)).performClick()
        pill(R.string.open)
        val row = hasText(file.name) and hasClickAction()
        waitFor(row)
        rule.onNode(row).performClick()
        waitFor(hasText("Pinned Pages document"))
        waitFor(hasText(str(R.string.tab_layout)))
    }

    @Test
    fun compareBrowserOffersFilesTheDocumentReaderCanUse() = guarded("compare-file-types") {
        val revised = File(workDir, "$PREFIX-revised.txt").apply { writeText("Revised text") }
        val deck = File(workDir, "$PREFIX-other.pptx").apply { createNewFile() }
        rule.onNodeWithText("Pages").performClick()
        tab(R.string.tab_review)
        button(R.string.compare)
        pill(R.string.compare_more)
        pill(R.string.compare_browse)
        rule.onAllNodes(hasText(str(R.string.compare_documents))).assertCountEquals(0)
        pill(R.string.cancel)
        waitFor(hasText(str(R.string.compare_settings)))
        pill(R.string.compare_browse)
        val location = hasText(str(R.string.storage_app_folder)) and hasClickAction()
        waitFor(location)
        rule.onNode(location).performClick()
        val row = hasText(revised.name) and hasClickAction()
        waitFor(row)
        rule.onAllNodes(hasText(deck.name)).assertCountEquals(0)
        rule.onNode(row).performScrollTo().performClick()
        waitFor(hasText(revised.name))
        waitFor(hasText(str(R.string.compare_settings)))
    }

    @Test
    fun slidesNewTitleSaveHomeReopen() = guarded("slides") {
        rule.onNodeWithText("Slides").performClick()
        val title = "UI test deck title"
        // A blank deck's title box holds the placeholder as real text: replace it.
        titleBox().performTextReplacement(title)
        waitFor(hasText(title))
        val file = saveAs(name = "$PREFIX-Slides", ext = "pptx")
        assertEquals(title, PptxReader.read(file.readBytes()).slides[0].title.text)
        goHome()
        openRecent(file.name)
        waitFor(hasText(title))
    }

    @Test
    fun sheetsSaveAsOpenDocument() = guarded("ods") {
        rule.onNodeWithText("Sheets").performClick()
        formulaBar().performTextInput("=6*7")
        waitFor(hasText("42"))
        val file = saveAs(name = "$PREFIX-Ods", ext = "ods")
        // The .ods keeps the formula (of:=6*7) and caches its value; the reader restores the formula.
        assertEquals("=6*7", OdsReader.read(file.readBytes()).sheets[0].cell(0, 0)?.text)
        rule.onNodeWithText(str(R.string.menu_file)).performClick()
        rule.onNode(hasText(str(R.string.save_as)) and hasClickAction()).performClick()
        rule.onNode(hasContentDescription(".ods") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun openInNewWindowAsksTheNavigatorForASideWindow() = guarded("side") {
        // A saved document appears in the recent list; its open-beside button goes through the spatial navigator.
        rule.onNodeWithText("Pages").performClick()
        paragraphField().performTextInput("side window")
        val file = saveAs(name = "$PREFIX-Side", ext = "docx")
        goHome()
        rule.onNode(hasContentDescription(str(R.string.open_in_new_window)) and hasAnyAncestor(hasAnyDescendant(hasText(file.name)) and hasClickAction()), useUnmergedTree = true).performClick()
        rule.waitUntil(TIMEOUT) { navigator.opened.isNotEmpty() }
        assertTrue(navigator.opened.first().startsWith("aux"))
        // The launcher stays on screen: the document went to the side window, not this one.
        rule.onNodeWithText(str(R.string.app_name)).assertIsDisplayed()
    }

    @Test
    fun narrowHomeSupportsSearchFilterAndReset() = guarded("narrow-home") {
        rule.runOnIdle { windowWidth.value = 650.dp }
        rule.onNodeWithText(str(R.string.open)).performClick()
        val filter = hasContentDescription(str(R.string.home_filter_all)) and hasClickAction()
        rule.onNode(filter).assertIsDisplayed().performClick()
        rule.onNode(hasText(str(R.string.home_tab_spreadsheets)) and hasClickAction()).performClick()
        rule.onNode(hasSetTextAction()).assertIsDisplayed().performTextInput("no-document-matches-this-name")
        rule.onNodeWithText(str(R.string.clear_filter)).assertIsDisplayed().performClick()
        rule.onNode(filter).assertIsDisplayed()
        rule.onNodeWithText("no-document-matches-this-name").assertDoesNotExist()
    }

    @Test
    fun pagesEndnoteThenFindReplace() = guarded("endnote") {
        rule.onNodeWithText("Pages").performClick()
        paragraphField().performTextInput("alpha beta")
        waitFor(hasText("alpha beta", substring = true))
        // References ▸ Insert Endnote puts a roman marker at the caret; the pane lists the new entry.
        tab(R.string.tab_references)
        button(R.string.insert_endnote)
        waitFor(hasText("alpha beta[i]", substring = true))
        button(R.string.footnote_pane)
        waitFor(hasText(str(R.string.txt_endnote_placeholder), substring = true))
        // Home ▸ Editing ▸ Replace opens Find and Replace on its Replace tab: replace all, the paragraph follows.
        tab(R.string.tab_home)
        button(R.string.replace)
        fieldBelow(R.string.find_what).performTextInput("beta")
        fieldBelow(R.string.replace_with).performTextInput("gamma")
        pill(R.string.replace_all)
        waitFor(hasText("alpha gamma[i]", substring = true))
    }

    @Test
    fun slidesMasterBackgroundRoundTrips() = guarded("master") {
        rule.onNodeWithText("Slides").performClick()
        titleBox()
        tab(R.string.tab_design)
        button(R.string.slide_master)
        waitFor(hasText(str(R.string.master_title_style)))
        // The background's colour drop-down (named by its row), then a theme colour from Office's palette in its menu.
        rule.onNode(hasContentDescription(str(R.string.master_background)) and hasClickAction()).performClick()
        val swatch = hasContentDescription(context.getString(R.string.a11y_color, "#44546A")) and hasClickAction()
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(swatch).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(swatch).onFirst().performClick()
        pill(R.string.done_button)
        val file = saveAs(name = "$PREFIX-Master", ext = "pptx")
        assertEquals(0xFF44546A, PptxReader.read(file.readBytes()).master.background)
    }

    @Test
    fun slidesShowFromBeginningAndEnd() = guarded("show") {
        rule.onNodeWithText("Slides").performClick()
        titleBox()
        tab(R.string.tab_slide_show)
        button(R.string.show_from_beginning)
        // The in-window show's own toolbar: its Exit icon button ends it and the ribbon returns.
        described(R.string.exit)
        waitFor(hasText(str(R.string.tab_home)) and hasClickAction())
    }

    @Test
    fun slidesInsertChartOpensChartDesign() = guarded("chart") {
        rule.onNodeWithText("Slides").performClick()
        titleBox()
        tab(R.string.tab_insert)
        button(R.string.chart_label)
        // Type pills carry a glyph before the name ("📊 Column").
        val column = hasText(str(R.string.chart_type_column), substring = true) and hasClickAction()
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(column).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(column).onFirst().performClick()
        // The new chart is selected, so its contextual tab appears.
        waitFor(hasText(str(R.string.tab_chart_design)) and hasClickAction())
    }

    @Test
    fun slidesPresentOnWallAsksForTheStageAndFallsBack() = guarded("wall") {
        rule.onNodeWithText("Slides").performClick()
        titleBox()
        tab(R.string.tab_slide_show)
        button(R.string.present_in_space)
        // The recording navigator refuses the Stage: the detached show window is requested instead.
        rule.waitUntil(TIMEOUT) { navigator.opened.contains("show") }
        assertEquals(listOf(com.therealityteam.aeropages.ui.SPATIAL_SHOW_STAGE), navigator.stages)
        waitFor(hasText(str(R.string.msg_space_show_unavailable)))
    }

    // --- helpers ----------------------------------------------------------------------------

    /** Click a ribbon tab (a clickable with the tab's title). */
    private fun tab(id: Int) {
        val m = hasText(str(id)) and hasClickAction()
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(m).onFirst().performClick()
        rule.waitForIdle()
    }

    /** Click a ribbon / tool button: its name is the content description of the merged node (scrolled into view first — ribbon tabs scroll sideways). */
    private fun button(id: Int) {
        val m = hasContentDescription(str(id)) and hasClickAction()
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }
        val node = rule.onAllNodes(m).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
        rule.waitForIdle()
    }

    /** Click an icon button named (content description) by the given string, through its click action. */
    private fun described(id: Int) {
        val m = hasContentDescription(str(id)) and hasClickAction()
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(m).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    /**
     * Click a pill / chip whose text is the given string — through its click action, not an injected touch: the
     * pill may sit in a Subwindow (a side pane), which injected touches do not reach in the PICO shell.
     */
    private fun pill(id: Int) {
        val m = hasText(str(id)) and hasClickAction()
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(m).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    /**
     * A labelled text field: the editable node on the same line as the label. (A label's Row has
     * no semantics node of its own, so every field in a pane is a "sibling" of every label —
     * the vertical overlap with the label is what tells them apart.)
     */
    private fun field(labelId: Int): SemanticsNodeInteraction {
        val label = hasText(str(labelId))
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val bounds = rule.onAllNodes(label, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        val beside = SemanticsMatcher("text field beside '${str(labelId)}'") { node ->
            node.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText) &&
                node.boundsInRoot.top < bounds.bottom && node.boundsInRoot.bottom > bounds.top
        }
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(beside, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        return rule.onAllNodes(beside, useUnmergedTree = true).onFirst()
    }


    /** A text field under its label (the Find and Replace pane's labels stand above their fields): the nearest one below it. */
    private fun fieldBelow(labelId: Int): SemanticsNodeInteraction {
        val label = hasText(str(labelId))
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val bounds = rule.onAllNodes(label, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        val below = SemanticsMatcher("text field below '${str(labelId)}'") { node ->
            node.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText) &&
                node.boundsInRoot.top >= bounds.bottom - 1f && node.boundsInRoot.top < bounds.bottom + 80f
        }
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(below, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        return rule.onAllNodes(below, useUnmergedTree = true).onFirst()
    }

    /**
     * Pages' paragraph editor. A paragraph is plain text until it is tapped (a blank document has
     * one empty paragraph); the tap arms the editor, which then takes focus.
     */
    private fun paragraphField(): SemanticsNodeInteraction {
        // An empty paragraph — not the title bar's search box, which is an empty text field too (named Search).
        val notSearch = !hasContentDescription(str(R.string.search))
        val paragraph = hasClickAction() and hasText("") and notSearch
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(paragraph).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(paragraph).onFirst().performClick()
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(hasSetTextAction() and notSearch).fetchSemanticsNodes().isNotEmpty() }
        val focused = rule.onAllNodes(hasSetTextAction() and isFocused() and notSearch).fetchSemanticsNodes()
        return if (focused.isNotEmpty()) rule.onNode(hasSetTextAction() and isFocused() and notSearch) else rule.onAllNodes(hasSetTextAction() and notSearch).onFirst()
    }

    /** Sheets' formula bar: the text field next to the "fx" label. */
    private fun formulaBar(): SemanticsNodeInteraction {
        val matcher = hasSetTextAction() and hasAnySibling(hasText("fx"))
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        return rule.onNode(matcher, useUnmergedTree = true)
    }

    /** Slides' title box of a blank deck: the empty editor showing the "Click to add title" prompt. */
    private fun titleBox(): SemanticsNodeInteraction {
        val matcher = hasSetTextAction() and hasText(str(R.string.placeholder_add_title))
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return rule.onNode(matcher)
    }

    /** File ▸ Save on a never-saved document opens the save browser; name the file, pick the type, confirm. */
    private fun saveAs(name: String, ext: String): File {
        val file = File(workDir, "$name.$ext")
        file.delete()
        rule.onNodeWithText(str(R.string.menu_file)).performClick()
        // Match clickable nodes only: the tooltip layer holds a plain "Save" label as well.
        val saveButton = hasText(str(R.string.save)) and hasClickAction()
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(saveButton).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(saveButton).onFirst().performClick()
        // The name field sits beside the "File Name" label (the suffix beside it follows the type).
        val nameField = hasSetTextAction() and hasAnySibling(hasText(str(R.string.file_name)))
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(nameField, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(nameField, useUnmergedTree = true).performTextReplacement(name)
        // Non-default types (.ods / .odt / .odp / .csv) are chosen in the "Save as type" drop-down: its field is
        // named by the current type; the choices open in a popup.
        val typeField = hasClickAction() and SemanticsMatcher("save-as type field") { n ->
            n.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.singleOrNull()?.matches(Regex("\\.[a-z]+")) == true
        }
        val current = rule.onAllNodes(typeField).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.singleOrNull()
        if (current != null && current != ".$ext") {
            rule.onAllNodes(typeField).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            val choice = hasText(".$ext") and hasClickAction()
            rule.waitUntil(TIMEOUT) { rule.onAllNodes(choice).fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodes(choice).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            rule.waitForIdle()
        }
        rule.onAllNodes(saveButton).onLast().performClick()
        rule.waitUntil(TIMEOUT) { file.exists() && file.length() > 0 }
        // The title row says "Saved" once the session has recorded the write.
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithText(str(R.string.doc_state_saved), substring = true).fetchSemanticsNodes().isNotEmpty() }
        return file
    }

    private fun goHome() {
        rule.onNode(hasContentDescription(str(R.string.tip_aerodesk_home)) and hasClickAction()).performClick()
        waitFor(hasText(str(R.string.app_name)))
    }

    private fun openRecent(fileName: String) {
        rule.onNode(hasText(fileName) and hasClickAction()).performClick()
    }

    /** On failure, leave the merged and unmerged semantics trees in the work folder for inspection. */
    private fun guarded(name: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { runCatching { dump("$name-fail") }; throw t }
    }

    private fun dump(tag: String) {
        // No waitForIdle here: a failure may leave the UI busy, and the tree is wanted as it is.
        // Side panes open as Subwindows — separate roots — so every root is printed.
        fun trees(unmerged: Boolean): String = runCatching {
            val roots = rule.onAllNodes(isRoot(), useUnmergedTree = unmerged)
            val n = roots.fetchSemanticsNodes().size
            (0 until n).joinToString("\n") { i -> "-- root $i\n" + roots[i].printToString(Int.MAX_VALUE) }
        }.getOrElse { "tree unavailable: $it" }
        val text = "== unmerged\n${trees(true)}\n== merged\n${trees(false)}"
        // The app's own folder is wiped when the test APKs are reinstalled: keep the file in Download too.
        runCatching { File("/sdcard/Download", "aerodesk-ui-dump-$tag.txt").writeText(text) }
        runCatching { File(workDir, "ui-dump-$tag.txt").writeText(text) }
        text.lines().forEach { android.util.Log.i("DeskFlowsDump", "[$tag] $it") }
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val PREFIX = "UiTest"
        const val TIMEOUT = 20_000L
    }
}
