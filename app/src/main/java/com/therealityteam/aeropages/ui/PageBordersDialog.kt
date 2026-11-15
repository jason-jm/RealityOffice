package com.therealityteam.aeropages.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.pdf.PageBorderPainter
import com.therealityteam.aeropages.model.CellBorder
import com.therealityteam.aeropages.model.LineStyle
import com.therealityteam.aeropages.model.PageBorderPages
import com.therealityteam.aeropages.model.PageBorders
import com.therealityteam.aeropages.model.PageSetup
import com.therealityteam.aeropages.model.ParagraphBorders

/** Word's four settings of the Page Border tab: none, a box, a box with a shadow, the sides one by one. */
private enum class BorderSetting { NONE, BOX, SHADOW, CUSTOM }

/** The line styles the dialog offers, as Word's list starts. */
private val PAGE_LINE_STYLES = listOf(LineStyle.THIN, LineStyle.DOTTED, LineStyle.DASHED, LineStyle.DASH_DOT, LineStyle.DASH_DOT_DOT, LineStyle.DOUBLE)

/** Word's widths, in points. */
private val PAGE_LINE_WIDTHS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2.25f, 3f, 4.5f, 6f)

/** The tabs of Word's Borders and Shading: the paragraphs' borders, the page's, the paragraphs' shading. */
internal enum class BordersTab(val label: Int) { BORDERS(R.string.bs_borders), PAGE(R.string.bs_page_border), SHADING(R.string.shading) }

/**
 * Word's Borders and Shading (Home ▸ Borders ▸ Borders and Shading…, Design ▸ Page Borders): the selected paragraphs'
 * borders (setting, line, preview with a button for each side and the lines between, distances from the text), the
 * page's borders, the paragraphs' shading. OK sets what changed.
 */
@Composable
internal fun BordersAndShadingDialog(editor: DocumentEditor, start: BordersTab, onDismiss: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val curPara = editor.paragraphBorders
    val curShade = editor.paragraphShading
    val curPage = editor.document.pageBorders
    var tab by remember { mutableStateOf(start) }
    // Taken afresh when the document changes under it (a file still loading when it opened).
    var para by remember(curPara) { mutableStateOf(curPara ?: ParagraphBorders()) }
    var paraLine by remember(curPara) { mutableStateOf(listOfNotNull(curPara?.top, curPara?.left, curPara?.bottom, curPara?.right, curPara?.between).firstOrNull() ?: editor.borderLine) }
    var shade by remember(curShade) { mutableStateOf(curShade) }
    var pageDraft by remember(curPage) { mutableStateOf(curPage ?: PageBorders()) }
    var pageLine by remember(curPage) { mutableStateOf(listOfNotNull(curPage?.top, curPage?.left, curPage?.bottom, curPage?.right).firstOrNull() ?: CellBorder(LineStyle.THIN, 0.5f, null)) }
    AeroDialog(
        title = stringResource(R.string.borders_and_shading),
        onDismiss = onDismiss,
        width = 620.dp,
        buttons = {
            DialogButton(stringResource(R.string.cancel), onDismiss)
            DialogButton(stringResource(R.string.btn_ok), {
                para.takeIf { !it.none }.let { if (it != curPara) editor.setParagraphBorders(it) }
                if (shade != curShade) editor.setParagraphShading(shade)
                pageDraft.takeIf { !it.none }.let { if (it != curPage) editor.setPageBorders(it) }
                editor.borderLine = paraLine
                onDismiss()
            }, primary = true)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DialogTabs(BordersTab.entries, tab, { stringResource(it.label) }, { tab = it }, width = 420.dp)
            when (tab) {
                BordersTab.BORDERS -> ParagraphBorderTab(para, { para = it }, paraLine, { paraLine = it })
                BordersTab.PAGE -> PageBorderTab(pageDraft, { pageDraft = it }, pageLine, { pageLine = it }, editor.document.page)
                BordersTab.SHADING -> ShadingTab(shade) { shade = it }
            }
        }
    }
}

/** The setting the sides make: none, a box (four sides alike), or one by one. */
private fun settingOf(sides: List<CellBorder>, shadow: Boolean = false): BorderSetting = when {
    sides.isEmpty() -> BorderSetting.NONE
    sides.size == 4 && sides.toSet().size == 1 -> if (shadow) BorderSetting.SHADOW else BorderSetting.BOX
    else -> BorderSetting.CUSTOM
}

/** The line's style, colour and width, as both border tabs set them. */
@Composable
private fun LineColumn(line: CellBorder, onLine: (CellBorder) -> Unit) {
    Column(Modifier.width(212.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DialogLabel(stringResource(R.string.border_line_style))
        ListBox(Modifier.fillMaxWidth(), height = 168.dp) {
            PAGE_LINE_STYLES.forEach { st ->
                ListRow(line.style == st, { onLine(line.copy(style = st)) }, Modifier.fillMaxWidth()) {
                    LineArt(st, Modifier.fillMaxWidth().padding(vertical = 4.dp).size(width = 180.dp, height = 12.dp))
                }
            }
        }
        DialogLabel(stringResource(R.string.border_line_color), Modifier.padding(top = 4.dp))
        ColorField(line.color, { onLine(line.copy(color = it)) }, noneLabel = stringResource(R.string.border_automatic),
            description = stringResource(R.string.border_line_color), width = 212.dp)
        DialogLabel(stringResource(R.string.line_weight), Modifier.padding(top = 4.dp))
        ComboField(widthLabel(line.widthPt), PAGE_LINE_WIDTHS, { widthLabel(it) }, line.widthPt, { onLine(line.copy(widthPt = it)) }, width = 212.dp)
    }
}

/**
 * The Borders tab: the selected paragraphs' setting (none, box, custom), the line, a preview of two paragraphs boxed
 * as the page will show them with a button for each side and the line between, and how far each side stands from the text.
 */
@Composable
private fun ParagraphBorderTab(draft: ParagraphBorders, onDraft: (ParagraphBorders) -> Unit, line: CellBorder, onLine: (CellBorder) -> Unit) {
    val sides = listOfNotNull(draft.top, draft.bottom, draft.left, draft.right)
    val setting = if (draft.between != null && sides.size == 4) BorderSetting.CUSTOM else settingOf(sides)
    fun withLine(l: CellBorder) {
        onLine(l)
        onDraft(draft.copy(top = draft.top?.let { l }, bottom = draft.bottom?.let { l }, left = draft.left?.let { l }, right = draft.right?.let { l }, between = draft.between?.let { l }))
    }
    fun pick(s: BorderSetting) = onDraft(when (s) {
        BorderSetting.NONE -> draft.copy(top = null, bottom = null, left = null, right = null, between = null)
        BorderSetting.CUSTOM -> if (draft.none) draft.copy(top = line, bottom = line, left = line, right = line) else draft
        else -> draft.copy(top = line, bottom = line, left = line, right = line, between = null)
    })
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.width(112.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DialogLabel(stringResource(R.string.pb_setting))
                SettingTile(BorderSetting.NONE, stringResource(R.string.none), setting) { pick(it) }
                SettingTile(BorderSetting.BOX, stringResource(R.string.pb_box), setting) { pick(it) }
                SettingTile(BorderSetting.CUSTOM, stringResource(R.string.pb_custom), setting) { pick(it) }
            }
            LineColumn(line) { withLine(it) }
            Column(Modifier.width(184.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DialogLabel(stringResource(R.string.preview))
                Text(stringResource(R.string.pb_preview_hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                // Word's arrangement: the top, between and bottom buttons beside the preview, the left and right ones under it.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SideToggle(Side.TOP, draft.top != null, stringResource(R.string.border_top)) { onDraft(draft.copy(top = if (draft.top != null) null else line)) }
                        SideToggle(Side.BETWEEN, draft.between != null, stringResource(R.string.border_inside_horizontal)) { onDraft(draft.copy(between = if (draft.between != null) null else line)) }
                        SideToggle(Side.BOTTOM, draft.bottom != null, stringResource(R.string.border_bottom)) { onDraft(draft.copy(bottom = if (draft.bottom != null) null else line)) }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ParagraphPreview(draft, null, Modifier.size(128.dp, 120.dp))
                        Row(Modifier.width(128.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            SideToggle(Side.LEFT, draft.left != null, stringResource(R.string.border_left)) { onDraft(draft.copy(left = if (draft.left != null) null else line)) }
                            SideToggle(Side.RIGHT, draft.right != null, stringResource(R.string.border_right)) { onDraft(draft.copy(right = if (draft.right != null) null else line)) }
                        }
                    }
                }
            }
        }
        DialogLabel(stringResource(R.string.pb_distance), Modifier.padding(top = 12.dp, bottom = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val w = labelWidth(stringResource(R.string.ps_top), stringResource(R.string.ps_bottom), stringResource(R.string.ps_left), stringResource(R.string.ps_right), min = 28.dp, max = 72.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SpaceField(stringResource(R.string.ps_top), draft.spaceTop, w) { onDraft(draft.copy(spaceTop = it)) }
                SpaceField(stringResource(R.string.ps_bottom), draft.spaceBottom, w) { onDraft(draft.copy(spaceBottom = it)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SpaceField(stringResource(R.string.ps_left), draft.spaceLeft, w) { onDraft(draft.copy(spaceLeft = it)) }
                SpaceField(stringResource(R.string.ps_right), draft.spaceRight, w) { onDraft(draft.copy(spaceRight = it)) }
            }
        }
    }
}

/** The Shading tab: the fill (Office's palette, "No Color" first) and a preview of the paragraphs filled. */
@Composable
private fun ShadingTab(fill: Long?, onFill: (Long?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DialogLabel(stringResource(R.string.bs_fill))
            ColorPalette(fill, onFill, noneLabel = stringResource(R.string.no_color))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DialogLabel(stringResource(R.string.preview))
            ParagraphPreview(null, fill, Modifier.size(150.dp, 150.dp))
        }
    }
}

/** Two paragraphs in small — grey lines of text — boxed and filled as the page shows them (ParagraphBoxPainter). */
@Composable
private fun ParagraphPreview(b: ParagraphBorders?, fill: Long?, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(6.dp))) {
        drawRect(Color.White, size = size)
        val scale = size.width / 150f
        // In so small a sample a hairline shows: each line drawn at least about a point wide.
        fun bold(l: CellBorder?) = l?.let { it.copy(widthPt = maxOf(it.widthPt, 1.2f / scale)) }
        val shown = b?.copy(top = bold(b.top), bottom = bold(b.bottom), left = bold(b.left), right = bold(b.right), between = bold(b.between))
        val f = com.therealityteam.aeropages.model.ParagraphFormat(spaceBeforePt = 0f, spaceAfterPt = 6f, indentLeftPt = 18f, indentRightPt = 18f, borders = shown?.takeIf { !it.none }, shading = fill)
        val lineH = 9f
        val paraH = 3 * lineH
        var y = 14f
        listOf(com.therealityteam.aeropages.model.BoxEdge(true, false), com.therealityteam.aeropages.model.BoxEdge(false, true)).forEachIndexed { k, e ->
            val before = com.therealityteam.aeropages.model.ParagraphBoxes.beforePt(f, e)
            val after = com.therealityteam.aeropages.model.ParagraphBoxes.afterPt(f, e)
            val h = before + paraH + after
            drawIntoCanvas { c -> com.therealityteam.aeropages.format.pdf.ParagraphBoxPainter.draw(c.nativeCanvas, f, e, 0f, y, 150f, h, scale) }
            for (i in 0 until 3) {
                val ly = (y + before + i * lineH + lineH / 2f) * scale
                val end = if (i == 2) 150f - 18f - 40f + k * 20f else 150f - 18f
                drawLine(Color(0xFFBDBDBD), Offset(18f * scale, ly), Offset(end * scale, ly), 3f * scale)
            }
            y += h
        }
    }
}

/** The Page Border tab: Word's setting (none, box, shadow, custom), the line, a preview of the page, which pages, and how far from the edge or the text. */
@Composable
private fun PageBorderTab(draft: PageBorders, onDraft: (PageBorders) -> Unit, line: CellBorder, onLine: (CellBorder) -> Unit, page: PageSetup) {
    val sides = listOfNotNull(draft.top, draft.bottom, draft.left, draft.right)
    val setting = settingOf(sides, draft.shadow)
    fun withLine(l: CellBorder) {
        onLine(l)
        // A new style, colour or width: every side there is takes it (Word's way with a box).
        onDraft(draft.copy(top = draft.top?.let { l }, bottom = draft.bottom?.let { l }, left = draft.left?.let { l }, right = draft.right?.let { l }))
    }
    fun pick(s: BorderSetting) = onDraft(when (s) {
        BorderSetting.NONE -> draft.copy(top = null, bottom = null, left = null, right = null, shadow = false)
        BorderSetting.BOX -> draft.copy(top = line, bottom = line, left = line, right = line, shadow = false)
        BorderSetting.SHADOW -> draft.copy(top = line, bottom = line, left = line, right = line, shadow = true)
        BorderSetting.CUSTOM -> if (sides.isEmpty()) draft.copy(top = line, bottom = line, left = line, right = line) else draft
    })
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // Setting.
            Column(Modifier.width(112.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DialogLabel(stringResource(R.string.pb_setting))
                SettingTile(BorderSetting.NONE, stringResource(R.string.none), setting) { pick(it) }
                SettingTile(BorderSetting.BOX, stringResource(R.string.pb_box), setting) { pick(it) }
                SettingTile(BorderSetting.SHADOW, stringResource(R.string.shape_shadow), setting) { pick(it) }
                SettingTile(BorderSetting.CUSTOM, stringResource(R.string.pb_custom), setting) { pick(it) }
            }
            LineColumn(line) { withLine(it) }
            // Preview and its side buttons.
            Column(Modifier.width(236.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DialogLabel(stringResource(R.string.preview))
                Text(stringResource(R.string.pb_preview_hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SideToggle(Side.TOP, draft.top != null, stringResource(R.string.border_top)) { onDraft(draft.copy(top = if (draft.top != null) null else line)) }
                        SideToggle(Side.BOTTOM, draft.bottom != null, stringResource(R.string.border_bottom)) { onDraft(draft.copy(bottom = if (draft.bottom != null) null else line)) }
                        SideToggle(Side.LEFT, draft.left != null, stringResource(R.string.border_left)) { onDraft(draft.copy(left = if (draft.left != null) null else line)) }
                        SideToggle(Side.RIGHT, draft.right != null, stringResource(R.string.border_right)) { onDraft(draft.copy(right = if (draft.right != null) null else line)) }
                    }
                    PagePreview(draft, page, Modifier.size(150.dp, 176.dp))
                }
            }
        }
        // Which pages, measured from what, and how far: across the dialog's width.
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DialogLabel(stringResource(R.string.ps_apply_to))
                val pagesLabel: @Composable (PageBorderPages) -> String = { p ->
                    when (p) {
                        PageBorderPages.ALL -> stringResource(R.string.ps_whole_document)
                        PageBorderPages.FIRST -> stringResource(R.string.pb_first_only)
                        PageBorderPages.NOT_FIRST -> stringResource(R.string.pb_not_first)
                    }
                }
                ComboField(pagesLabel(draft.pages), PageBorderPages.entries, { pagesLabel(it) }, draft.pages, { onDraft(draft.copy(pages = it)) }, width = 220.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DialogLabel(stringResource(R.string.pb_measure))
                ChoiceSegments(listOf(false, true), draft.fromText, { stringResource(if (it) R.string.pb_text else R.string.pb_edge) }, { text ->
                    // Word's distances for each: 24 pt from the page's edge, 4 pt from the text.
                    if (text != draft.fromText) {
                        val d = if (text) 4f else 24f
                        onDraft(draft.copy(fromText = text, spaceTop = d, spaceBottom = d, spaceLeft = d, spaceRight = d))
                    }
                }, width = 240.dp)
            }
        }
        DialogLabel(stringResource(R.string.pb_distance), Modifier.padding(top = 12.dp, bottom = 4.dp))
        // Two by two, as Word's Options dialog sets them: top and bottom, left and right.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val w = labelWidth(stringResource(R.string.ps_top), stringResource(R.string.ps_bottom), stringResource(R.string.ps_left), stringResource(R.string.ps_right), min = 28.dp, max = 72.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SpaceField(stringResource(R.string.ps_top), draft.spaceTop, w) { onDraft(draft.copy(spaceTop = it)) }
                SpaceField(stringResource(R.string.ps_bottom), draft.spaceBottom, w) { onDraft(draft.copy(spaceBottom = it)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SpaceField(stringResource(R.string.ps_left), draft.spaceLeft, w) { onDraft(draft.copy(spaceLeft = it)) }
                SpaceField(stringResource(R.string.ps_right), draft.spaceRight, w) { onDraft(draft.copy(spaceRight = it)) }
            }
        }
    }
}

/** A width as Word names it: "½ pt" is "0.5 pt", in the language's digits. */
@Composable
private fun widthLabel(w: Float): String =
    stringResource(R.string.line_weight_pt, java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(w))

/** One of a page's or a box's sides, or the line between a box's paragraphs. */
private enum class Side { TOP, BOTTOM, LEFT, RIGHT, BETWEEN }

/** A setting: a small page as it would look, and its name; the current one in the accent's tint. */
@Composable
private fun SettingTile(s: BorderSetting, label: String, current: BorderSetting, onPick: (BorderSetting) -> Unit) {
    val accent = LocalAccent.current
    val on = s == current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (on) accent.copy(alpha = 0.12f) else Color.Transparent)
            .semantics { selected = on; role = Role.RadioButton }
            .press { onPick(s) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val ink = PicoTheme.colorScheme.labelPrimary
        Canvas(Modifier.size(26.dp, 32.dp)) {
            drawRect(Color.White, size = size)
            drawRect(Color(0xFFBDBDBD), size = size, style = Stroke(1.dp.toPx()))
            val i = 4.dp.toPx(); val w = 1.5.dp.toPx()
            when (s) {
                BorderSetting.NONE -> {}
                BorderSetting.BOX, BorderSetting.SHADOW -> {
                    if (s == BorderSetting.SHADOW) drawRect(ink, Offset(i + w * 1.5f, i + w * 1.5f), androidx.compose.ui.geometry.Size(size.width - 2 * i, size.height - 2 * i))
                    drawRect(Color.White, Offset(i, i), androidx.compose.ui.geometry.Size(size.width - 2 * i, size.height - 2 * i))
                    drawRect(ink, Offset(i, i), androidx.compose.ui.geometry.Size(size.width - 2 * i, size.height - 2 * i), style = Stroke(w))
                }
                BorderSetting.CUSTOM -> {
                    drawLine(ink, Offset(i, i), Offset(size.width - i, i), w)
                    drawLine(ink, Offset(i, size.height - i), Offset(size.width - i, size.height - i), w)
                }
            }
        }
        Text(label, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
    }
}

/** A side's button: a small box with that side drawn strong (the accent's when it is on). */
@Composable
private fun SideToggle(side: Side, on: Boolean, label: String, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val faint = PicoTheme.colorScheme.labelTertiary
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
            .background(if (on) accent.copy(alpha = 0.14f) else Aero.field)
            .then(if (on) Modifier.border(1.dp, accent, RoundedCornerShape(8.dp)) else Modifier)
            .semantics(mergeDescendants = true) { selected = on; role = Role.Checkbox; this.contentDescription = label }
            .press(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val thin = 1.dp.toPx(); val strong = 2.5.dp.toPx()
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))
            val w = size.width; val h = size.height
            val edges = mapOf(
                Side.TOP to (Offset(0f, 0f) to Offset(w, 0f)), Side.BOTTOM to (Offset(0f, h) to Offset(w, h)),
                Side.LEFT to (Offset(0f, 0f) to Offset(0f, h)), Side.RIGHT to (Offset(w, 0f) to Offset(w, h)),
            )
            edges.forEach { (k, e) ->
                if (k == side) drawLine(if (on) accent else faint.copy(alpha = 1f), e.first, e.second, strong)
                else drawLine(faint, e.first, e.second, thin, pathEffect = dash)
            }
            // The line between a box's paragraphs: across the middle.
            if (side == Side.BETWEEN) drawLine(if (on) accent else faint.copy(alpha = 1f), Offset(0f, h / 2f), Offset(w, h / 2f), strong)
        }
    }
}

/** The page in small, its text as grey lines, the borders drawn as on the page itself; a tap near a side turns it. */
@Composable
private fun PagePreview(b: PageBorders, page: PageSetup, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(132.dp * (page.widthPt / page.heightPt).coerceIn(0.5f, 1.4f).let { if (it > 1f) 1f else it }, 172.dp * (page.heightPt / page.widthPt).let { if (it < 1f) it else 1f }.coerceAtMost(1f))) {
            val scale = size.width / page.widthPt
            drawRect(Color.White, size = size)
            drawRect(Color(0xFFBDBDBD), size = size, style = Stroke(1f))
            // The text: grey lines between the margins.
            val l = page.marginLeftPt * scale; val r = size.width - page.marginRightPt * scale
            var y = page.marginTopPt * scale + 3.dp.toPx()
            val bottom = size.height - page.marginBottomPt * scale
            var k = 0
            while (y < bottom) {
                drawLine(Color(0xFFD5D5D5), Offset(l, y), Offset(if (k % 5 == 4) l + (r - l) * 0.6f else r, y), 2.dp.toPx())
                y += 5.dp.toPx(); k++
            }
            if (!b.none) drawIntoCanvas { c -> PageBorderPainter.draw(c.nativeCanvas, b.copy(
                // In so small a page a hairline shows: each line drawn at least a point and a half wide.
                top = b.top?.let { it.copy(widthPt = maxOf(it.widthPt, 1.5f / scale / 1.2f)) },
                bottom = b.bottom?.let { it.copy(widthPt = maxOf(it.widthPt, 1.5f / scale / 1.2f)) },
                left = b.left?.let { it.copy(widthPt = maxOf(it.widthPt, 1.5f / scale / 1.2f)) },
                right = b.right?.let { it.copy(widthPt = maxOf(it.widthPt, 1.5f / scale / 1.2f)) },
            ), page, scale) }
        }
    }
}

/** A distance in points (0–31, Word's range) with − and +. */
@Composable
private fun SpaceField(label: String, value: Float, labelWidth: androidx.compose.ui.unit.Dp, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, modifier = Modifier.width(labelWidth))
        SpinBox(
            stringResource(R.string.line_weight_pt, Math.round(value).toString()),
            onStep = { d -> onChange((Math.round(value) + d).coerceIn(0, 31).toFloat()) },
            description = label, canLess = value > 0f, canMore = value < 31f, valueWidth = 52.dp,
        )
    }
}

/** A line style drawn across: dashes and dots as the page will show them, a double line as two. */
@Composable
private fun LineArt(style: LineStyle, modifier: Modifier) {
    val ink = PicoTheme.colorScheme.labelPrimary
    Canvas(modifier) {
        val y = size.height / 2f
        val w = 1.5.dp.toPx()
        if (style == LineStyle.DOUBLE) {
            drawLine(ink, Offset(0f, y - w), Offset(size.width, y - w), w * 0.7f)
            drawLine(ink, Offset(0f, y + w), Offset(size.width, y + w), w * 0.7f)
        } else drawLine(ink, Offset(0f, y), Offset(size.width, y), w,
            pathEffect = style.dash?.let { d -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(FloatArray(d.size) { d[it] * w * 1.5f }) })
    }
}
