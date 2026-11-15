package com.therealityteam.aeropages.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.SlideLayout

/**
 * PowerPoint's context menu for a slide's thumbnail (a press and hold on it): New Slide, Duplicate Slide, Delete Slide,
 * Add / Rename Section, the layouts (the current one ticked), Reset Slide, Hide Slide (ticked while hidden), and
 * moving the slide up or down — the thumbnail pane has no dragging. The menu works on the current slide, which the
 * press made the slide pressed.
 */
@Composable
internal fun SlideThumbMenu(editor: SlideEditor, onSectionName: () -> Unit, onDismiss: () -> Unit) {
    fun run(action: () -> Unit): () -> Unit = { onDismiss(); action() }
    val slides = editor.presentation.slides
    FlyoutMenu(width = 264.dp, maxHeight = 560.dp) {
        MenuCommand(stringResource(R.string.new_slide), run { editor.addSlide() }, icon = "➕")
        MenuCommand(stringResource(R.string.duplicate_slide), run { editor.duplicateSlide() }, icon = "ic_copy")
        MenuCommand(stringResource(R.string.delete_slide), run { editor.deleteSlide() }, icon = "ic_delete", enabled = slides.size > 1)
        MenuDivider()
        MenuCommand(stringResource(if (editor.slide.sectionName != null) R.string.rename_section else R.string.add_section), run(onSectionName), icon = "§")
        MenuHeading(stringResource(R.string.layout_picker))
        listOf(SlideLayout.TITLE to R.string.layout_title_slide, SlideLayout.TITLE_CONTENT to R.string.layout_title_content,
            SlideLayout.SECTION to R.string.layout_section_header, SlideLayout.BLANK to R.string.layout_blank).forEach { (layout, name) ->
            MenuCommand(stringResource(name).replace('\n', ' '), run { editor.setLayout(layout) }, checked = editor.slide.layout == layout)
        }
        MenuCommand(stringResource(R.string.reset_slide), run { editor.resetSlide() }, icon = "↺")
        MenuDivider()
        MenuCommand(stringResource(R.string.hide_slide), run { editor.toggleHidden() }, icon = "x_hideslide", checked = editor.slide.hidden)
        MenuCommand(stringResource(R.string.move_slide_up), run { editor.moveSlide(-1) }, icon = "ic_arrow_up", enabled = editor.current > 0)
        MenuCommand(stringResource(R.string.move_slide_down), run { editor.moveSlide(1) }, icon = "ic_arrow_down", enabled = editor.current < slides.lastIndex)
    }
}

/**
 * PowerPoint's context menu for a picked object (the selection bar's More): Cut, Copy, the layer order — Bring to
 * Front, Bring Forward, Send Backward, Send to Back — for a shape or a picture, Link… and, for a picture, Alt Text….
 * [onLink] and [onAltText] open their dialogs.
 */
@Composable
internal fun SlideObjectMenu(editor: SlideEditor, clipboard: TextClipboard, onLink: () -> Unit, onAltText: () -> Unit, onDismiss: () -> Unit) {
    fun run(action: () -> Unit): () -> Unit = { onDismiss(); action() }
    val shape = editor.selectedShape
    val image = editor.selectedImage
    FlyoutMenu(width = 248.dp) {
        MenuCommand(stringResource(R.string.cut), run { if (editor.cutSelectedObject() != null) clipboard.copyCurrentObject() }, icon = "✂")
        MenuCommand(stringResource(R.string.copy), run { if (editor.copySelectedObject() != null) clipboard.copyCurrentObject() }, icon = "⧉")
        if (shape != null || image != null) {
            MenuDivider()
            MenuCommand(stringResource(R.string.bring_to_front), run { if (shape != null) editor.bringShapeToFront() else editor.bringImageToFront() }, icon = "ic_bring_front")
            MenuCommand(stringResource(R.string.bring_forward), run { if (shape != null) editor.stepShapeLayer(true) else editor.stepImageLayer(true) }, icon = "ic_bring_forward")
            MenuCommand(stringResource(R.string.send_backward), run { if (shape != null) editor.stepShapeLayer(false) else editor.stepImageLayer(false) }, icon = "ic_send_backward")
            MenuCommand(stringResource(R.string.send_to_back), run { if (shape != null) editor.sendShapeToBack() else editor.sendImageToBack() }, icon = "ic_send_back")
            MenuDivider()
            MenuCommand(stringResource(R.string.link), run(onLink), icon = "ic_link")
            if (image != null) MenuCommand(stringResource(R.string.alt_text), run(onAltText), icon = "ic_accessibility")
        }
    }
}
