package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph

/**
 * Text copied in Pages with its formatting: the parts of the paragraphs it ran through — each but the last with its
 * paragraph mark, so its paragraph formatting comes along as in Word — and the plain [text] that went to the system
 * clipboard (a paste whose clipboard text is still that pastes these instead). [source] is the document copied from
 * (null for a slide's text box, whose lines come as paragraphs).
 */
class DocClip(val text: String, val paragraphs: List<Paragraph>, val source: Document?)

/** The last [DocClip], for every window of the process (the system clipboard only carries plain text). */
object DocClipboard {
    @Volatile var clip: DocClip? = null

    /** [clip] when the clipboard's [text] is still what was copied with it (line endings aside). */
    fun matching(text: String): DocClip? = clip?.takeIf { normalize(it.text) == normalize(text) }

    private fun normalize(t: String) = t.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')
}
