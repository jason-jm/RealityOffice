package com.therealityteam.aeropages.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Count every edit, including edits made while the document is already dirty. View changes do not count. */
internal class EditState {
    var version = 0L
        private set
    private var modified by mutableStateOf(false)
    var dirty: Boolean
        get() = modified
        set(value) {
            if (value) version++
            modified = value
        }
}
