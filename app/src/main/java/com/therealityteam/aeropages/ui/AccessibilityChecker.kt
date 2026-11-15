package com.therealityteam.aeropages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.AccessibilityCheck
import com.therealityteam.aeropages.model.AccessibilityCheck.Issue
import com.therealityteam.aeropages.model.AccessibilityCheck.Kind
import com.therealityteam.aeropages.model.AccessibilityCheck.Severity

/** What each finding is called, as Office's Accessibility pane calls it. */
@Composable
private fun titleOf(kind: Kind): String = stringResource(when (kind) {
    Kind.MISSING_ALT_TEXT -> R.string.a11y_missing_alt
    Kind.MISSING_TABLE_HEADER -> R.string.a11y_missing_table_header
    Kind.MISSING_SLIDE_TITLE -> R.string.a11y_missing_slide_title
    Kind.UNCLEAR_LINK -> R.string.a11y_unclear_link
    Kind.DUPLICATE_SLIDE_TITLE -> R.string.a11y_duplicate_slide_title
    Kind.MERGED_CELLS -> R.string.a11y_merged_cells
    Kind.SKIPPED_HEADING -> R.string.a11y_skipped_heading
    Kind.DEFAULT_SHEET_NAME -> R.string.a11y_default_sheet_name
})

/** Where a finding is: the slide or sheet it is on, then the object (picture 2, table 1, a heading's or link's text, a range). */
@Composable
private fun whereOf(issue: Issue, sheetName: (Int) -> String): String {
    val place = when {
        issue.slide >= 0 -> stringResource(R.string.a11y_where_slide, issue.slide + 1)
        issue.sheet >= 0 && issue.kind != Kind.DEFAULT_SHEET_NAME -> sheetName(issue.sheet)
        else -> null
    }
    val what = when (issue.kind) {
        Kind.MISSING_ALT_TEXT -> issue.label.ifEmpty { stringResource(R.string.a11y_where_picture, issue.ordinal) }
        Kind.MISSING_TABLE_HEADER -> stringResource(R.string.a11y_where_table, issue.ordinal)
        Kind.MISSING_SLIDE_TITLE -> null
        else -> issue.label.takeIf { it.isNotEmpty() }
    }
    return listOfNotNull(place, what).joinToString(" · ")
}

/**
 * Office's Check Accessibility results: Errors, Warnings and Tips with how many of each, every finding a row — what it
 * is and where — that goes to it; a picture missing its alt text offers Add alt text right there. None: the all-clear.
 */
@Composable
internal fun AccessibilityDialog(
    issues: List<Issue>,
    onDismiss: () -> Unit,
    onGo: (Issue) -> Unit,
    onAddAltText: ((Issue) -> Unit)?,
    sheetName: (Int) -> String = { "" },
) {
    AeroDialog(stringResource(R.string.group_accessibility), onDismiss, width = 520.dp, buttons = {
        DialogButton(stringResource(R.string.close), onDismiss, primary = true)
    }) {
        Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (issues.isEmpty()) {
                PaneEmpty("ic_accessibility", stringResource(R.string.a11y_check_none))
                return@Column
            }
            Text(stringResource(R.string.a11y_check_hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            ListBox(Modifier.fillMaxWidth(), height = 360.dp) {
                for (severity in Severity.entries) {
                    val group = issues.filter { it.severity == severity }
                    if (group.isEmpty()) continue
                    DialogLabel(stringResource(when (severity) {
                        Severity.ERROR -> R.string.a11y_errors
                        Severity.WARNING -> R.string.a11y_warnings
                        Severity.TIP -> R.string.a11y_tips
                    }, group.size), Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp))
                    for (issue in group) {
                        Row(
                            Modifier.fillMaxWidth().press { onGo(issue) }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            val (icon, tint) = when (severity) {
                                Severity.ERROR -> "ic_minus_circle" to PicoTheme.colorScheme.error
                                Severity.WARNING -> "ic_warning" to Aero.danger
                                Severity.TIP -> "ic_info" to PicoTheme.colorScheme.labelSecondary
                            }
                            RibbonGlyph(icon, tint, 18.dp)
                            Column(Modifier.weight(1f)) {
                                Text(titleOf(issue.kind), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
                                val where = whereOf(issue, sheetName)
                                if (where.isNotEmpty()) Text(where, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
                            }
                            if (issue.kind == Kind.MISSING_ALT_TEXT && onAddAltText != null)
                                DialogButton(stringResource(R.string.a11y_add_alt_text), { onAddAltText(issue) }, small = true)
                        }
                    }
                }
            }
        }
    }
}

/** Pages' Check Accessibility: the document's findings; going to one puts the caret (or the pick) on it. */
@Composable
internal fun PagesAccessibility(editor: DocumentEditor, onDismiss: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    var fixing by remember { mutableStateOf<com.therealityteam.aeropages.model.ImageBlock?>(null) }
    fun focus(p: com.therealityteam.aeropages.model.Paragraph) {
        val i = editor.document.locations().indexOfFirst { it.paragraph === p }
        if (i >= 0) editor.focusParagraph(i)
    }
    fun go(issue: Issue) {
        when (val t = issue.target) {
            is com.therealityteam.aeropages.model.Paragraph -> focus(t)
            is com.therealityteam.aeropages.model.TableBlock -> t.table.rows.firstOrNull()?.cells?.firstOrNull()?.content?.firstOrNull()?.let(::focus)
            is com.therealityteam.aeropages.model.ImageBlock -> {
                // The paragraph before a picture of its own (the cell's first, for one in a table) brings it into view.
                val blocks = editor.document.blocks
                val at = blocks.indexOfFirst { it === t }
                val near = if (at >= 0) (at downTo 0).firstNotNullOfOrNull { (blocks[it] as? com.therealityteam.aeropages.model.ParagraphBlock)?.paragraph }
                    ?: blocks.firstNotNullOfOrNull { (it as? com.therealityteam.aeropages.model.ParagraphBlock)?.paragraph }
                else blocks.firstNotNullOfOrNull { b -> (b as? com.therealityteam.aeropages.model.TableBlock)?.table?.rows?.flatMap { it.cells }?.firstOrNull { c -> c.images.any { it === t } }?.content?.firstOrNull() }
                near?.let(::focus)
                editor.selectImage(t)
            }
        }
    }
    AccessibilityDialog(AccessibilityCheck.of(editor.document), onDismiss, onGo = { go(it); onDismiss() },
        onAddAltText = { (it.target as? com.therealityteam.aeropages.model.ImageBlock)?.let { img -> fixing = img } })
    fixing?.let { img ->
        AltTextDialog(img.altText, img.decorative, onDismiss = { fixing = null }) { t, d -> editor.setImageAltText(img, t, d); fixing = null }
    }
}

/** Slides' Check Accessibility: going to a finding opens its slide and picks its picture or table. */
@Composable
internal fun SlidesAccessibility(editor: SlideEditor, onDismiss: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    var fixing by remember { mutableStateOf<Issue?>(null) }
    fun go(issue: Issue) {
        if (issue.slide >= 0) editor.select(issue.slide)
        when (val t = issue.target) {
            is com.therealityteam.aeropages.model.SlideImage -> editor.selectImage(t)
            is com.therealityteam.aeropages.model.SlideTable -> editor.selectTable(t)
        }
    }
    AccessibilityDialog(AccessibilityCheck.of(editor.presentation), onDismiss, onGo = { go(it); onDismiss() }, onAddAltText = { fixing = it })
    fixing?.let { issue ->
        val img = issue.target as? com.therealityteam.aeropages.model.SlideImage
        if (img == null) fixing = null
        else AltTextDialog(img.altText, img.decorative, onDismiss = { fixing = null }) { t, d -> go(issue); editor.setImageAltText(t, d); fixing = null }
    }
}

/** Sheets' Check Accessibility: going to a finding opens its sheet and picks its picture or merged range. */
@Composable
internal fun SheetsAccessibility(editor: SheetEditor, onDismiss: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    var fixing by remember { mutableStateOf<com.therealityteam.aeropages.model.SheetImage?>(null) }
    fun go(issue: Issue) {
        if (issue.sheet >= 0 && issue.sheet != editor.sheetIndex) editor.selectSheet(issue.sheet)
        when (val t = issue.target) {
            is com.therealityteam.aeropages.model.SheetImage -> editor.selectImage(t)
            is com.therealityteam.aeropages.model.MergeRegion -> { editor.select(t.r1, t.c1); editor.extendSelection(t.r2 - t.r1, t.c2 - t.c1) }
        }
    }
    AccessibilityDialog(AccessibilityCheck.of(editor.workbook), onDismiss, onGo = { go(it); onDismiss() },
        onAddAltText = { (it.target as? com.therealityteam.aeropages.model.SheetImage)?.let { img -> fixing = img } },
        sheetName = { editor.workbook.sheets.getOrNull(it)?.name.orEmpty() })
    fixing?.let { img ->
        AltTextDialog(img.altText, img.decorative, onDismiss = { fixing = null }) { t, d -> editor.setImageAltText(img, t, d); fixing = null }
    }
}

/**
 * Office's status bar entry for accessibility: "Accessibility: Investigate" while the file has errors or warnings
 * ([findings] of them), "Good to go" otherwise (tips do not count); a click opens Check Accessibility ([onClick]).
 */
@Composable
internal fun AccessibilityStatus(findings: Int, onClick: () -> Unit) {
    Row(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).spatialHoverEffect().press(onClick = onClick).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RibbonGlyph("ic_accessibility", PicoTheme.colorScheme.labelSecondary, 14.dp)
        Text(stringResource(if (findings > 0) R.string.a11y_status_investigate else R.string.a11y_status_good), style = AeroType.caption,
            color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
    }
}

/** How many findings Office's status bar counts for [issues]: the errors and warnings. */
internal fun statusFindings(issues: List<Issue>): Int = issues.count { it.severity != Severity.TIP }
