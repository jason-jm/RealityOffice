package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.SlideEditor
import org.junit.Assert.*
import org.junit.Test

class EditorSaveStateTest {
    @Test fun editingADirtyCellAdvancesTheSaveVersionWhileMovingTheSelectionDoesNot() {
        val editor = SheetEditor()
        editor.setCell(0, 0, "A")
        val written = editor.saveState.version
        editor.select(1, 0)
        assertEquals(written, editor.saveState.version)
        editor.setCell(0, 0, "B")
        assertTrue(editor.saveState.version > written)
        editor.markSaved("Budget.xlsx", clean = false)
        assertTrue(editor.dirty)
        assertEquals("Budget.xlsx", editor.workbook.sourcePath)
        editor.markSaved()
        assertFalse(editor.dirty)
    }

    @Test fun editingDirtySpeakerNotesKeepsTheNewDeckPathAndTheUnsavedEdit() {
        val editor = SlideEditor()
        editor.setNotes("A")
        val written = editor.saveState.version
        editor.setNotes("B")
        assertTrue(editor.saveState.version > written)
        editor.markSaved("Talk.pptx", clean = false)
        assertTrue(editor.dirty)
        assertEquals("Talk.pptx", editor.presentation.sourcePath)
        editor.markSaved()
        assertFalse(editor.dirty)
    }
}
