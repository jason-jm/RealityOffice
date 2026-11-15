package com.therealityteam.aeropages.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.therealityteam.aeropages.model.Block
import com.therealityteam.aeropages.model.BoxLoc
import com.therealityteam.aeropages.model.CellLoc
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.ParaLoc
import com.therealityteam.aeropages.model.PageSetup
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.ResolvedStyle
import com.therealityteam.aeropages.model.StyleDef
import com.therealityteam.aeropages.model.StyleSheet
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextId
import com.therealityteam.aeropages.model.Texts
import com.therealityteam.aeropages.model.TextRun
import com.therealityteam.aeropages.model.TopLoc

/** Case-conversion modes for [DocumentEditor.changeCase]. */
enum class CaseMode { UPPER, LOWER, TITLE, TOGGLE }

/** A proofing issue: the flat paragraph index plus a human-readable message. */
/**
 * Something proofing found in paragraph [index] (in [DocumentEditor.proofread]'s order): a repeated
 * word, a double space — or a [word] at [start] the spell checker does not know, with its [suggestions].
 */
class ProofIssue(val index: Int, val message: String, val word: String? = null, val start: Int = -1, val suggestions: List<String> = emptyList())

/**
 * Whether [text] still has the whole word [word] at [start] — not a part of a longer one (a typo
 * corrected to a longer word, "Revenu" → "Revenue", is not there any more to correct again).
 */
internal fun wordAt(text: String, start: Int, word: String): Boolean {
    if (start < 0 || start + word.length > text.length || !text.regionMatches(start, word, 0, word.length)) return false
    val end = start + word.length
    return (start == 0 || !text[start - 1].isLetterOrDigit()) && (end == text.length || !text[end].isLetterOrDigit())
}

/** AeroDesk's own writing checks on one text (number [index] of those proofed): a word written twice in a row, double spaces. */
internal fun ownProofIssues(index: Int, text: String): List<ProofIssue> {
    val issues = ArrayList<ProofIssue>(2)
    val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
    for (i in 1 until words.size) {
        if (words[i].equals(words[i - 1], ignoreCase = true) && words[i].length > 1) {
            issues.add(ProofIssue(index, Texts.get(TextId.PROOF_REPEATED_WORD, words[i]))); break
        }
    }
    if (text.contains("  ")) issues.add(ProofIssue(index, Texts.get(TextId.PROOF_DOUBLE_SPACE)))
    return issues
}

/** The widest a paragraph's left or right indent gets (five inches). */
private const val MAX_INDENT_PT = 360f

/**
 * Live editing state for one document. The document is block-level (paragraphs +
 * tables); the editor addresses paragraphs by a **flat reading-order index**
 * ([active]) and resolves the owning container via [Document.locations] for
 * structural edits. Character formatting operates on the active paragraph's
 * selection range (true Word inline formatting).
 */
class DocumentEditor {

    var document by mutableStateOf(Document.blank())
        private set

    var active by mutableIntStateOf(0)
        private set

    var selStart by mutableIntStateOf(0)
        private set
    var selEnd by mutableIntStateOf(0)
        private set

    /** A place in the text: the flat paragraph index and a character offset in that paragraph. */
    data class DocPos(val index: Int, val offset: Int) : Comparable<DocPos> {
        override fun compareTo(other: DocPos): Int = if (index != other.index) index.compareTo(other.index) else offset.compareTo(other.offset)
    }

    /**
     * The fixed end of a selection that runs into other paragraphs; the caret's paragraph
     * ([active]) holds the moving end at [selEnd], and [selStart] clips the selection to that
     * paragraph (its start or end) so the text field shows its own part. Null: the selection is
     * the active paragraph's own, [selStart]..[selEnd].
     */
    var spanAnchor by mutableStateOf<DocPos?>(null)
        private set

    /** Bumped when the caret moved somewhere the canvas should bring into view (navigation, find, arrows across paragraphs, undo). */
    var revealRequest by mutableIntStateOf(0)
        private set

    /**
     * Whether the selection was made with the pointer (a held word, a drag, a handle): the canvas
     * then shows its handles even inside the text field, which only draws handles for selections
     * it made itself. Any other caret or selection change clears it.
     */
    var pointerSelection by mutableStateOf(false)
        private set

    var revision by mutableIntStateOf(0)
        private set

    internal val saveState = EditState()
    var dirty by saveState::dirty
        private set

    /** File → Info: the file's properties, a change to save (not an undo step, as in Office). */
    fun setProps(p: com.therealityteam.aeropages.model.DocProps) {
        if (p == document.props) return
        document.props = p; dirty = true; bump()
    }

    /** Caret / selection restored alongside an undo step. */
    private class Sel(val active: Int, val start: Int, val end: Int)
    private val history = History<Document, Sel>(200)
    /**
     * Baseline copy of [document] as of the last recorded undo step. Every command diffs the
     * live document against it — block by block, plus the page/header/footer/notes metadata —
     * and stores only the differing run of blocks, so a keystroke in a 500-page document costs
     * one paragraph, not a whole-document snapshot (see [diff]).
     */
    private var shadow: Document = document.copyOf()
    /** Selection at the start of an open (coalesced) typing session, restored by its undo step. */
    private var sessionSel: Sel? = null
    private var lastEditKey: String? = null

    val canUndo: Boolean get() = history.canUndo || sessionSel != null
    val canRedo: Boolean get() = history.canRedo && sessionSel == null

    private fun locs(): List<ParaLoc> = document.locations()
    fun paragraphCount(): Int = locs().size

    private fun locAt(index: Int): ParaLoc {
        val all = locs()
        return all[index.coerceIn(0, all.lastIndex)]
    }

    val activeParagraph: Paragraph get() = locAt(active).paragraph

    /**
     * Word-style pending caret format: armed when a character-format toggle happens with a
     * collapsed caret; consumed by the next text typed at exactly that (paragraph, caret).
     */
    var pendingFormat: CharacterFormat? = null
        private set
    private var pendingPos: Pair<Int, Int>? = null

    private fun clearPending() { pendingFormat = null; pendingPos = null }

    /** Pending-aware so ribbon toggle states reflect an armed-but-untyped format. */
    val currentFormat: CharacterFormat get() = pendingFormat ?: ParagraphOps.formatAt(activeParagraph, selStart)

    private fun selRange(): Pair<Int, Int>? {
        val s = minOf(selStart, selEnd); val e = maxOf(selStart, selEnd)
        return if (e > s) s to e else null
    }

    // --- lifecycle -------------------------------------------------------------

    fun load(doc: Document, recovered: Boolean = false) {
        commandRevision++; edits++
        endWithParagraph(doc)
        document = doc
        selectedEquation = null; selectedChart = null; selectedImage = null; selectedBox = null
        clearPending()
        active = 0; selStart = 0; selEnd = 0; spanAnchor = null
        history.clear(); shadow = doc.copyOf(); sessionSel = null
        lastEditKey = null; dirty = recovered
        trackChanges = doc.trackRevisions
        syncMarkups()
        bump()
    }

    fun markSaved() { dirty = false }

    /** The document was just written to [path] (always as .docx): remember it and clear dirty. */
    fun markSaved(path: String, clean: Boolean = true) {
        document.sourcePath = path
        document.sourceFormat = com.therealityteam.aeropages.model.DocFormat.DOCX
        if (clean) dirty = false
        bump()
    }

    fun focusParagraph(index: Int) {
        if (index != active) clearPending()
        spanAnchor = null
        pointerSelection = false
        active = index.coerceIn(0, locs().lastIndex)
        lastEditKey = null
        revealRequest++
    }

    fun setSelection(index: Int, start: Int, end: Int) {
        spanAnchor = null
        pointerSelection = false
        active = index.coerceIn(0, locs().lastIndex)
        val len = ParagraphOps.length(activeParagraph)
        selStart = start.coerceIn(0, len)
        selEnd = end.coerceIn(0, len)
        // Moving the caret away from the armed position (or selecting) drops the pending format.
        val p = pendingPos
        if (p != null && (active != p.first || selStart != p.second || selStart != selEnd)) clearPending()
    }

    // --- undo / redo -----------------------------------------------------------

    private fun sel() = Sel(active, selStart, selEnd)

    /**
     * Record the live document's changes since the baseline as one undo step (no-op when
     * nothing changed) and move the baseline forward. Called at every step boundary: around a
     * [command], when a coalesced typing session starts, and before undo/redo.
     */
    private fun sync(selBefore: Sel? = null) {
        val entrySel = selBefore ?: sessionSel ?: sel()
        sessionSel = null
        val delta = diff() ?: return
        history.push(delta, entrySel)
    }

    /** Begin (or continue) a coalesced edit session keyed by [key]: only its first edit starts an undo step. */
    private fun beginSession(key: String) {
        if (lastEditKey != key) { sync(); sessionSel = sel(); lastEditKey = key }
        dirty = true
    }

    /**
     * Diff [document] against [shadow]: returns the delta that turns the document back into
     * the baseline (null when equal) and re-baselines. The changed run of blocks is found by
     * trimming the common prefix and suffix, so one edited paragraph yields a one-block delta.
     */
    private fun diff(): Delta<Document>? {
        val parts = ArrayList<Delta<Document>>(2)
        val a = shadow.blocks; val b = document.blocks
        var p = 0
        while (p < a.size && p < b.size && ModelDiff.sameBlock(a[p], b[p])) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && ModelDiff.sameBlock(a[a.size - 1 - s], b[b.size - 1 - s])) s++
        if (a.size - p - s > 0 || b.size - p - s > 0) {
            val old = a.subList(p, a.size - s)
            val stored = ArrayList(old) // baseline-owned copies move into the delta
            val fresh = b.subList(p, b.size - s).map { it.copyOf() }
            old.clear(); a.addAll(p, fresh)
            parts.add(BlocksDelta(p, b.size - p - s, stored))
        }
        if (!ModelDiff.sameDocumentMeta(shadow, document)) {
            parts.add(DocumentMetaDelta.capture(shadow))
            DocumentMetaDelta.capture(document).install(shadow)
        }
        return when (parts.size) { 0 -> null; 1 -> parts[0]; else -> CompositeDelta(parts) }
    }

    fun undo() {
        sync()
        val s = history.undo(document, sel()) ?: return
        afterHistoryStep(s)
    }

    fun redo() {
        sync()
        val s = history.redo(document, sel()) ?: return
        afterHistoryStep(s)
    }

    private fun afterHistoryStep(s: Sel) {
        commandRevision++; edits++
        diff() // re-baseline to the restored document
        selectedEquation = null; selectedChart = null; selectedImage = null; selectedBox = null   // the step restored copies of the blocks
        active = s.active; selStart = s.start; selEnd = s.end; spanAnchor = null
        clearPending(); clampActive(); lastEditKey = null; dirty = true; revealRequest++; bump()
    }

    /** Undoable steps recorded so far (tests). */
    internal val undoDepth: Int get() = history.size

    /** Blocks stored by the most recent undo step — tests assert deltas stay proportional to the edit. */
    internal fun lastUndoBlockCount(): Int = when (val d = history.peek()) {
        is BlocksDelta -> d.stored.size
        is CompositeDelta -> (0 until d.size).sumOf { (d[it] as? BlocksDelta)?.stored?.size ?: 0 }
        else -> 0
    }

    private fun clampActive() {
        active = active.coerceIn(0, locs().lastIndex)
        val len = ParagraphOps.length(activeParagraph)
        selStart = selStart.coerceIn(0, len); selEnd = selEnd.coerceIn(0, len)
    }

    /**
     * Advanced by every command, undo, redo and load (not by typing): a command may change any paragraph where it
     * stands, and the canvas's paragraphs, tables and text boxes — each given the same objects as before — read this to
     * be drawn again (Compose would otherwise skip them as unchanged).
     */
    var commandRevision by mutableIntStateOf(0)
        private set
    /** Bumped when a shape or text box is inserted: Word then opens Shape Format. */
    var shapeInserts by mutableIntStateOf(0)
        private set
    /** Bumped when a table is inserted (drawn, typed in as text, or pasted): Word then opens Table Design. */
    var tableInserts by mutableIntStateOf(0)
        private set
    /** Bumped when a chart is inserted: Word then opens Chart Design. */
    var chartInserts by mutableIntStateOf(0)
        private set
    /** Bumped when a picture is inserted: Word then opens Picture Format. */
    var imageInserts by mutableIntStateOf(0)
        private set

    private inline fun command(block: () -> Unit) {
        clearPending(); sync(); lastEditKey = null; edits++
        val before = sel()
        block()
        commandRevision++
        endWithParagraph(document)
        sync(before); lastEditKey = null; dirty = true; bump()
        if (VERIFY_UNDO) check(ModelDiff.sameDocument(shadow, document)) { "undo baseline out of sync" }
    }

    private fun bump() { revision++ }

    /**
     * Word's rule (LibreOffice's too): a document ends with a paragraph, so after a table, a chart or a
     * picture at its end there is always a line to type on — added when an edit or a file leaves it without.
     */
    private fun endWithParagraph(doc: Document) {
        if (doc.blocks.lastOrNull() !is ParagraphBlock) doc.blocks.add(ParagraphBlock(Paragraph.of("")))
    }

    /**
     * A run of typing confined to one paragraph: the document at revision [to] differs from every
     * revision in [from]..[to] only in [paragraph]'s text — so the page layout can re-measure just
     * that paragraph instead of the whole document.
     */
    class TypingRun(val paragraph: Paragraph, val from: Int, val to: Int)

    /** The latest [TypingRun] (null until something is typed); stale once any other change bumps [revision]. */
    var typingRun: TypingRun? = null
        private set

    // --- text editing ----------------------------------------------------------

    fun replaceText(index: Int, oldText: String, newText: String) {
        if (oldText == newText) return
        beginSession("text@$index")

        var p = 0
        val minLen = minOf(oldText.length, newText.length)
        while (p < minLen && oldText[p] == newText[p]) p++
        var s = 0
        while (s < minLen - p && oldText[oldText.length - 1 - s] == newText[newText.length - 1 - s]) s++
        val removedStart = p
        val removedEnd = oldText.length - s
        val inserted = newText.substring(p, newText.length - s)

        val para = locAt(index).paragraph
        // A pure insertion at the armed caret position consumes the pending format.
        val pf = pendingFormat
        val usePending = pf != null && removedStart == removedEnd && inserted.isNotEmpty() &&
            pendingPos == (index to removedStart)
        val fmt = if (usePending) pf!!.copy() else ParagraphOps.formatAt(para, (removedStart - 1).coerceAtLeast(0))
        if (usePending) clearPending()
        // Typing at a link's end goes after the link, not into it (Word's way).
        if (!usePending && fmt.link != null && removedStart == removedEnd && inserted.isNotEmpty()) {
            val next = if (removedEnd < ParagraphOps.length(para)) ParagraphOps.formatAt(para, removedEnd).link else null
            if (next != fmt.link) fmt.link = null
        }
        if (trackChanges && (removedStart < removedEnd || inserted.isNotEmpty())) {
            // Don't remove deleted text — mark it struck-through and keep it; the new
            // caret is in model coordinates (which include the struck text).
            val caret = ParagraphOps.trackedReplace(para, removedStart, removedEnd, inserted, fmt, reviewerName, System.currentTimeMillis())
            active = index; selStart = caret; selEnd = caret
        } else {
            // Typed with tracking off: plain text, not part of a change it is typed into or next to.
            if (fmt.inserted || fmt.formatChanged) { fmt.inserted = false; fmt.formatChanged = false; fmt.before = null; fmt.revAuthor = null; fmt.revDate = null }
            ParagraphOps.replace(para, removedStart, removedEnd, inserted, fmt)
        }
        val before = revision
        edits++
        bump()
        val run = typingRun
        typingRun = if (run != null && run.paragraph === para && run.to == before) TypingRun(para, run.from, revision) else TypingRun(para, before, revision)
    }

    /** Split the paragraph at [index] into two within its own container. */
    fun splitParagraph(index: Int, at: Int) = command {
        val loc = locAt(index)
        // Capture length BEFORE the split (splitAt truncates loc.paragraph to [0,at)).
        val origLen = ParagraphOps.length(loc.paragraph)
        val tail = ParagraphOps.splitAt(loc.paragraph, at)
        // Only a split at the very end of a heading demotes the new paragraph to Normal.
        if (loc.paragraph.format.style != ParagraphStyle.NORMAL && at >= origLen) {
            tail.format.style = ParagraphStyle.NORMAL
            tail.format.rawStyleId = null
        }
        when (loc) {
            is TopLoc -> document.blocks.add(loc.blockIndex + 1, ParagraphBlock(tail))
            is CellLoc -> loc.cell.content.add(loc.indexInCell + 1, tail)
            is BoxLoc -> loc.box.paragraphs.add(loc.indexInBox + 1, tail)
        }
        active = index + 1; selStart = 0; selEnd = 0
    }

    /** Merge paragraph [index] into the previous one when they share a container. */
    fun mergeWithPrevious(index: Int) {
        if (index <= 0) return
        val cur = locAt(index)
        val prev = locAt(index - 1)
        val sameContainer = (cur is TopLoc && prev is TopLoc) ||
            (cur is CellLoc && prev is CellLoc && cur.cell === prev.cell) ||
            (cur is BoxLoc && prev is BoxLoc && cur.box === prev.box)
        if (!sameContainer) { active = index - 1; selStart = ParagraphOps.length(prev.paragraph); selEnd = selStart; bump(); return }
        command {
            val caret = ParagraphOps.length(prev.paragraph)
            ParagraphOps.append(prev.paragraph, cur.paragraph)
            removeAtLoc(cur)
            active = index - 1; selStart = caret; selEnd = caret
        }
    }

    fun deleteParagraph(index: Int) = command {
        val loc = locAt(index)
        when (loc) {
            is TopLoc -> {
                val topParagraphBlocks = document.blocks.count { it is ParagraphBlock }
                if (topParagraphBlocks <= 1) clearParagraph(loc.paragraph)
                else { document.blocks.removeAt(loc.blockIndex); active = (index - 1).coerceAtLeast(0) }
            }
            is CellLoc -> {
                if (loc.cell.content.size <= 1) clearParagraph(loc.paragraph)
                else { loc.cell.content.removeAt(loc.indexInCell); active = (index - 1).coerceAtLeast(0) }
            }
            is BoxLoc -> {
                if (loc.box.paragraphs.size <= 1) clearParagraph(loc.paragraph)
                else { loc.box.paragraphs.removeAt(loc.indexInBox); active = (index - 1).coerceAtLeast(0) }
            }
        }
        selStart = 0; selEnd = 0
    }

    private fun removeAtLoc(loc: ParaLoc) {
        when (loc) {
            is TopLoc -> document.blocks.removeAt(loc.blockIndex)
            is CellLoc -> loc.cell.content.removeAt(loc.indexInCell)
            is BoxLoc -> loc.box.paragraphs.removeAt(loc.indexInBox)
        }
    }

    private fun clearParagraph(p: Paragraph) {
        p.runs.clear(); p.runs.add(TextRun(""))
        p.bookmarks.forEach { it.offset = 0 }
    }

    /** The selected text — across paragraphs one line each — or empty when nothing is selected. */
    fun selectedText(): String {
        span()?.let { sp ->
            return buildString {
                var first = true
                forSpan(sp) { p, a, b ->
                    if (!first) append('\n')
                    first = false
                    val t = p.plainText()
                    append(t, a.coerceIn(0, t.length), b.coerceIn(0, t.length))
                }
            }
        }
        val t = activeParagraph.plainText()
        val s = minOf(selStart, selEnd).coerceIn(0, t.length)
        val e = maxOf(selStart, selEnd).coerceIn(0, t.length)
        return t.substring(s, e)
    }

    /** Delete the current selection (used by cut) — one undo step. */
    fun deleteSelection() {
        val sp = span()
        if (sp != null) { command { deleteRange(sp.first, sp.second) }; return }
        val r = selRange() ?: return
        command {
            val p = activeParagraph
            val fmt = ParagraphOps.formatAt(p, (r.first - 1).coerceAtLeast(0))
            ParagraphOps.replace(p, r.first, r.second, "", fmt)
            selStart = r.first; selEnd = r.first
        }
    }

    /** Pastes / inserts [text] over the selection; its line breaks start new paragraphs. */
    fun insertAtCaret(text: String) = typeOver(text)

    // --- selections across paragraphs ------------------------------------------------------------

    /** The selection when it runs across paragraphs: its start and end (exclusive), in reading order. */
    fun span(): Pair<DocPos, DocPos>? {
        val a = spanAnchor ?: return null
        if (a.index == active) return null
        val focus = DocPos(active, selEnd)
        return if (a < focus) a to focus else focus to a
    }

    val hasSpan: Boolean get() = span() != null

    /** The paragraphs a span runs through, each with the part of it the span covers ([start, end) offsets). */
    private inline fun forSpan(sp: Pair<DocPos, DocPos>, each: (Paragraph, Int, Int) -> Unit) {
        val all = locs()
        for (i in sp.first.index..sp.second.index) {
            val p = all[i].paragraph
            val a = if (i == sp.first.index) sp.first.offset else 0
            val b = if (i == sp.second.index) sp.second.offset else ParagraphOps.length(p)
            each(p, a, b)
        }
    }

    /**
     * Where paragraph [index] (of [length] characters) is covered by the current span: its
     * [start, end) offsets, or null outside it — the canvas paints the highlight from it.
     */
    fun spanPartOf(index: Int, length: Int): IntRange? {
        val sp = span() ?: return null
        if (index < sp.first.index || index > sp.second.index) return null
        val a = if (index == sp.first.index) sp.first.offset else 0
        val b = if (index == sp.second.index) sp.second.offset else length
        return a until b
    }

    /**
     * The selected part of paragraph [index] — a span's part, or the caret paragraph's own
     * selection — for paragraphs drawn as plain text (not the text field) to paint; null when none.
     */
    fun selectionPartOf(index: Int, length: Int): IntRange? {
        if (spanAnchor != null && spanAnchor?.index != active) return spanPartOf(index, length)
        if (index != active) return null
        val a = minOf(selStart, selEnd).coerceIn(0, length)
        val b = maxOf(selStart, selEnd).coerceIn(0, length)
        return if (b > a) a until b else null
    }

    /** The selection's fixed end (where it began) and its moving end (the caret). */
    fun selectionAnchor(): DocPos = spanAnchor?.takeIf { it.index != active } ?: DocPos(active, selStart)
    fun selectionFocus(): DocPos = DocPos(active, selEnd)

    /** The selection's two ends in reading order (equal for a caret). */
    fun selectionEnds(): Pair<DocPos, DocPos> =
        span() ?: (DocPos(active, minOf(selStart, selEnd)) to DocPos(active, maxOf(selStart, selEnd)))

    /** Selects from [anchor] to [focus] — a pointer drag or a selection handle; the caret goes to [focus]. */
    fun selectRange(anchor: DocPos, focus: DocPos) {
        val all = locs()
        val ai = anchor.index.coerceIn(0, all.lastIndex)
        spanAnchor = null
        active = ai
        selStart = anchor.offset.coerceIn(0, ParagraphOps.length(all[ai].paragraph))
        selEnd = selStart
        extendTo(focus.index, focus.offset, reveal = false)
        pointerSelection = true
    }

    /**
     * The paragraphs the selection touches, for paragraph formatting: every one a span runs
     * through (not the last when the span ends at its very start, as in Word), else the active one.
     */
    fun selectedParagraphs(): List<Paragraph> {
        val sp = span() ?: return listOf(activeParagraph)
        val all = locs()
        val last = if (sp.second.offset == 0) sp.second.index - 1 else sp.second.index
        return (sp.first.index..maxOf(last, sp.first.index)).map { all[it].paragraph }
    }

    /**
     * Moves the moving end of the selection to ([index], [offset]) while the other end stays put
     * (Shift+arrows, Shift+click). Back in the anchor's own paragraph the selection is that
     * paragraph's own again.
     */
    fun extendTo(index: Int, offset: Int, reveal: Boolean = true) {
        val all = locs()
        val anchor = spanAnchor ?: DocPos(active, selStart)
        val i = index.coerceIn(0, all.lastIndex)
        val len = ParagraphOps.length(all[i].paragraph)
        val off = offset.coerceIn(0, len)
        clearPending()
        pointerSelection = false
        if (i == anchor.index) {
            spanAnchor = null
            active = i; selStart = anchor.offset.coerceIn(0, len); selEnd = off
        } else {
            spanAnchor = anchor
            active = i; selStart = if (anchor.index < i) 0 else len; selEnd = off
        }
        lastEditKey = null
        if (reveal) revealRequest++
        bump()
    }

    /** Selects the whole document (Ctrl+A). */
    fun selectAll() {
        val all = locs()
        spanAnchor = null
        active = 0; selStart = 0; selEnd = 0
        extendTo(all.lastIndex, ParagraphOps.length(all.last().paragraph), reveal = false)
    }

    /**
     * Moves the caret to ([index], [offset]) and drops the selection — arrow keys crossing into
     * another paragraph; with [extend] only the selection's moving end goes there (Shift+arrows).
     */
    fun moveCaret(index: Int, offset: Int, extend: Boolean = false) {
        if (extend) { extendTo(index, offset); return }
        val all = locs()
        val i = index.coerceIn(0, all.lastIndex)
        val off = offset.coerceIn(0, ParagraphOps.length(all[i].paragraph))
        if (i != active) clearPending()
        spanAnchor = null
        pointerSelection = false
        active = i; selStart = off; selEnd = off
        lastEditKey = null
        revealRequest++
        bump()
    }

    /**
     * Replaces the selection — a span across paragraphs, or the active paragraph's own — with
     * [text]; each line break in it starts a new paragraph shaped like the one it breaks. The
     * caret ends after the text. One undo step.
     */
    fun typeOver(text: String) = command { replaceSelectionWith(text) }

    private fun replaceSelectionWith(text: String) {
        val sp = span()
        if (sp != null) deleteRange(sp.first, sp.second)
        else selRange()?.let { (a, b) ->
            val p = activeParagraph
            val fmt = ParagraphOps.formatAt(p, (a - 1).coerceAtLeast(0))
            if (trackChanges) {
                val caret = ParagraphOps.trackedReplace(p, a, b, "", fmt, reviewerName, System.currentTimeMillis())
                selStart = caret; selEnd = caret
            } else {
                ParagraphOps.replace(p, a, b, "", fmt)
                selStart = a; selEnd = a
            }
        }
        if (text.isNotEmpty()) insertLines(active, selStart, text)
    }

    /** Puts [text] at ([index], [offset]); its line breaks split the paragraph there. */
    private fun insertLines(index: Int, offset: Int, text: String) {
        val loc = locAt(index)
        val para = loc.paragraph
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val base = ParagraphOps.formatAt(para, (offset - 1).coerceAtLeast(0))
        // New text: an insertion of this reviewer's while tracking, else plain — never part of the change it is typed next to.
        val fmt = base.copyOf().also {
            it.deleted = false; it.formatChanged = false; it.before = null
            if (trackChanges) { it.inserted = true; it.revAuthor = reviewerName; it.revDate = System.currentTimeMillis() }
            else { it.inserted = false; it.revAuthor = null; it.revDate = null }
        }
        if (lines.size == 1) {
            ParagraphOps.replace(para, offset, offset, text, fmt)
            active = index; selStart = offset + text.length; selEnd = selStart
            return
        }
        val tail = ParagraphOps.splitAt(para, offset)
        ParagraphOps.replace(para, offset, offset, lines.first(), fmt)
        ParagraphOps.replace(tail, 0, 0, lines.last(), fmt)
        val middle = lines.subList(1, lines.size - 1).map { line -> Paragraph(mutableListOf(TextRun(line, fmt.copyOf())), para.format.copyOf().also { it.dropCap = null }) }
        val added = middle + tail
        when (loc) {
            is TopLoc -> document.blocks.addAll(loc.blockIndex + 1, added.map { ParagraphBlock(it) })
            is CellLoc -> loc.cell.content.addAll(loc.indexInCell + 1, added)
            is BoxLoc -> loc.box.paragraphs.addAll(loc.indexInBox + 1, added)
        }
        active = index + added.size; selStart = lines.last().length; selEnd = selStart
    }

    // --- Word's AutoCorrect as you type -----------------------------------------------------------

    /** A correction just made: what, where (its start, the button's place) and what it was — while nothing changed since. */
    class AutoFix internal constructor(val kind: AutoCorrect.Kind, val at: DocPos, val was: String, val now: String, internal val edits: Int)

    private var lastAutoFix: AutoFix? = null

    /** The correction Word's AutoCorrect Options button is for: the last one made, while nothing has changed since. */
    val autoFixMark: AutoFix? get() = lastAutoFix?.takeIf { it.edits == edits }

    /**
     * After a character typed at the caret in paragraph [index]: what AutoCorrect makes of it, as its own undo step
     * (Ctrl+Z right after puts back what was typed, as in Word). True when anything changed. Not with changes
     * tracked, not inside a field or a formula.
     */
    fun autoCorrectTyped(index: Int): Boolean {
        if (trackChanges || index != active || selStart != selEnd || hasSpan) return false
        val para = locAt(index).paragraph
        val text = para.plainText()
        val fixes = AutoCorrect.after(text, selStart, AutoCorrect.languageOf(text))
        return applyAutoFixes(para, index, text, fixes, selStart) != null
    }

    /**
     * Enter at [at] in paragraph [index]: the word it ends is corrected as a space would (a web address linked, a
     * sentence's first word capitalised) — its own undo step. The offset to split at after the correction.
     */
    fun autoCorrectAtEnter(index: Int, at: Int): Int {
        if (trackChanges || hasSpan) return at
        val para = locAt(index).paragraph
        val text = para.plainText()
        if (at !in 1..text.length) return at
        val fixes = AutoCorrect.after(text.substring(0, at) + " ", at + 1, AutoCorrect.languageOf(text))
            .filter { it !is AutoCorrect.MakeList && (it !is AutoCorrect.Replace || it.end <= at) }
        return applyAutoFixes(para, index, text, fixes, at) ?: at
    }

    /** Makes [fixes] (from right to left, each before [caret]) in one step; the caret after, or null with nothing done. */
    private fun applyAutoFixes(para: Paragraph, index: Int, text: String, fixes: List<AutoCorrect.Fix>, caret: Int): Int? {
        val doable = AutoCorrect.doable(para, fixes)
        if (doable.isEmpty()) return null
        var at = caret
        val first = doable.minOf { AutoCorrect.startOf(it) }
        val (main, was, now) = AutoCorrect.named(text, doable)
        command {
            for (fx in doable) {
                at += AutoCorrect.make(para, fx)
                if (fx is AutoCorrect.MakeList) autoList(para, fx)
            }
            active = index; selStart = at.coerceAtLeast(0); selEnd = selStart
        }
        lastAutoFix = AutoFix(main.kind, DocPos(index, first.coerceAtMost(at)), was, now, edits)
        return selStart
    }

    /** "* " or "1. " typed at a paragraph's start: it becomes a list item in that look (joining a list just before). */
    private fun autoList(para: Paragraph, fx: AutoCorrect.MakeList) {
        val numbering = fx.numbering
        when {
            fx.bullet == "•" -> setListKind(listOf(para), ListKind.BULLET)
            numbering == AutoCorrect.Numbering.DECIMAL_DOT -> setListKind(listOf(para), ListKind.NUMBER)
            else -> {
                val base = if (fx.bullet != null) com.therealityteam.aeropages.model.ListDef.BULLETS else com.therealityteam.aeropages.model.ListDef.NUMBERS
                val first = if (fx.bullet != null) {
                    com.therealityteam.aeropages.model.ListLevel(com.therealityteam.aeropages.model.NumFormat.BULLET, fx.bullet)
                } else {
                    val f = when (numbering) {
                        AutoCorrect.Numbering.LOWER_DOT, AutoCorrect.Numbering.LOWER_PAREN -> com.therealityteam.aeropages.model.NumFormat.LOWER_LETTER
                        AutoCorrect.Numbering.UPPER_DOT, AutoCorrect.Numbering.UPPER_PAREN -> com.therealityteam.aeropages.model.NumFormat.UPPER_LETTER
                        else -> com.therealityteam.aeropages.model.NumFormat.DECIMAL
                    }
                    com.therealityteam.aeropages.model.ListLevel(f, "%1" + numbering!!.closer)
                }
                val def = com.therealityteam.aeropages.model.ListDef(listOf(first) + base.levels.drop(1))
                // Right after a list of the same look: more of its items.
                val all = document.paragraphs()
                val before = all.indexOfFirst { it === para }.let { if (it > 0) all[it - 1] else null }
                val same = before?.format?.listId?.takeIf { it != 0 && document.lists[it] == def }
                val id = same ?: document.newListId().also { document.lists[it] = def }
                para.format.listId = id
                para.format.listKind = if (fx.bullet != null) ListKind.BULLET else ListKind.NUMBER
                markParaRevised()
            }
        }
    }

    /** The AutoCorrect Options button's "Undo …": the correction undone, what was typed back. */
    fun undoAutoFix(): Boolean {
        if (autoFixMark == null) return false
        lastAutoFix = null
        undo()
        return true
    }

    // --- formatted copy and Word's Paste Options ------------------------------------------------

    /** Word's three ways to paste text: as copied, in the formatting where it lands (keeping bold, italic…), or plain. */
    enum class PasteMode { KEEP_SOURCE, MERGE, TEXT_ONLY }

    /** A paste just made: how, where it ended (the button's place), and what is needed to make it again another way. */
    class PasteMark internal constructor(
        val mode: PasteMode, val end: DocPos,
        internal val anchor: DocPos, internal val focus: DocPos, internal val clip: DocClip, internal val edits: Int,
    )

    /** Counts every change to the text — commands, typing, undo — so a paste knows whether anything came after it. */
    private var edits = 0
    private var lastPaste: PasteMark? = null

    /** The paste Word's Paste Options button is for: the last formatted paste, while nothing has changed since. */
    val pasteOptions: PasteMark? get() = lastPaste?.takeIf { it.edits == edits }

    /**
     * The selection as a [DocClip]: each paragraph's selected part with its runs' formats and its paragraph format —
     * tracked deletions left out, revision marks and fields dropped (a pasted field result is plain text), bookmarks
     * not copied. Null with nothing selected.
     */
    fun copiedClip(): DocClip? {
        val text = selectedText()
        if (text.isEmpty()) return null
        val parts = ArrayList<Paragraph>()
        val sp = span()
        if (sp != null) forSpan(sp) { p, a, b -> parts.add(clipPiece(p, a, b)) }
        else selRange()?.let { (a, b) -> parts.add(clipPiece(activeParagraph, a, b)) }
        if (parts.isEmpty()) return null
        return DocClip(text, parts, document)
    }

    private fun clipPiece(p: Paragraph, a: Int, b: Int): Paragraph = TableText.slice(p, a until b).also { q ->
        q.runs.removeAll { it.format.deleted }
        q.runs.forEach { r ->
            r.format.field = null; r.format.inserted = false; r.format.formatChanged = false; r.format.before = null; r.format.revAuthor = null; r.format.revDate = null
        }
        if (q.runs.isEmpty()) q.runs.add(TextRun(""))
    }

    /**
     * Pastes [clip] over the selection [mode]'s way (Word's default: as copied) — one undo step — and keeps it for the
     * Paste Options button. Pasted paragraphs bring their paragraph formatting as copied (the first paragraph's onto
     * the paragraph pasted into, as Word's paragraph mark does); merged or plain, they take the formatting there.
     */
    fun pasteClip(clip: DocClip, mode: PasteMode = PasteMode.KEEP_SOURCE) {
        val anchor = selectionAnchor(); val focus = selectionFocus()
        command { replaceSelectionWith(""); insertClip(clip, mode) }
        lastPaste = PasteMark(mode, DocPos(active, selEnd), anchor, focus, clip, edits)
    }

    /**
     * Paste Options: the last paste made again [mode]'s way — undone, its selection put back, pasted again. One undo
     * step remains. False when there is no such paste.
     */
    fun repaste(mode: PasteMode): Boolean {
        val mark = pasteOptions ?: return false
        if (mark.mode == mode) return true
        undo()
        restoreSelection(mark.anchor, mark.focus)
        pasteClip(mark.clip, mode)
        return true
    }

    /** Puts the selection back to [anchor]..[focus] (they name the same places again after an undo). */
    private fun restoreSelection(anchor: DocPos, focus: DocPos) {
        val all = locs()
        spanAnchor = null
        active = anchor.index.coerceIn(0, all.lastIndex)
        val len = ParagraphOps.length(all[active].paragraph)
        selStart = anchor.offset.coerceIn(0, len); selEnd = selStart
        if (focus.index != anchor.index) extendTo(focus.index, focus.offset, reveal = false)
        else selEnd = focus.offset.coerceIn(0, len)
        pointerSelection = false
        bump()
    }

    /** [clip]'s paragraphs at the caret (the selection already gone), [mode]'s way; the caret ends after them. */
    private fun insertClip(clip: DocClip, mode: PasteMode) {
        val index = active
        val loc = locAt(index)
        val para = loc.paragraph
        val offset = selStart
        val base = ParagraphOps.formatAt(para, (offset - 1).coerceAtLeast(0))
        val sameDocument = clip.source === document
        fun formatOf(src: com.therealityteam.aeropages.model.CharacterFormat) = when (mode) {
            PasteMode.KEEP_SOURCE -> src.copyOf()
            // Merge Formatting: what is there, with the copied text's emphasis and links.
            PasteMode.MERGE -> base.copyOf().also {
                it.bold = src.bold; it.italic = src.italic; it.underline = src.underline; it.strikethrough = src.strikethrough
                it.superscript = src.superscript; it.subscript = src.subscript; it.link = src.link
            }
            PasteMode.TEXT_ONLY -> base.copyOf().also { it.math = null }
        }.also {
            it.deleted = false; it.formatChanged = false; it.before = null; it.field = null
            if (trackChanges) { it.inserted = true; it.revAuthor = reviewerName; it.revDate = System.currentTimeMillis() }
            else { it.inserted = false; it.revAuthor = null; it.revDate = null }
        }
        fun put(p: Paragraph, at: Int, src: Paragraph): Int {
            var o = at
            for (r in src.runs) if (r.text.isNotEmpty()) { ParagraphOps.replace(p, o, o, r.text, formatOf(r.format)); o += r.text.length }
            return o
        }
        // A copied paragraph's format here: as copied (a list of another document's becomes a plain one), else this one's.
        fun paragraphFormat(src: Paragraph, into: Paragraph) {
            if (mode != PasteMode.KEEP_SOURCE) return
            into.format.assignFrom(src.format)
            if (!sameDocument && into.format.listId != 0 && into.format.listId !in document.lists) into.format.listId = 0
        }
        val pieces = clip.paragraphs
        if (pieces.size == 1) {
            val end = put(para, offset, pieces[0])
            selStart = end; selEnd = end
            return
        }
        val tail = ParagraphOps.splitAt(para, offset)
        put(para, offset, pieces.first())
        paragraphFormat(pieces.first(), para)
        val middle = pieces.subList(1, pieces.size - 1).map { src ->
            Paragraph(mutableListOf(TextRun("", formatOf(src.runs.firstOrNull()?.format ?: base))), para.format.copyOf().also { it.sectionBreak = null; it.dropCap = null }).also { np ->
                paragraphFormat(src, np)
                put(np, 0, src)
                if (np.runs.size > 1 && np.runs[0].text.isEmpty()) np.runs.removeAt(0)
            }
        }
        val end = put(tail, 0, pieces.last())
        val added = middle + tail
        when (loc) {
            is TopLoc -> document.blocks.addAll(loc.blockIndex + 1, added.map { ParagraphBlock(it) })
            is CellLoc -> loc.cell.content.addAll(loc.indexInCell + 1, added)
            is BoxLoc -> loc.box.paragraphs.addAll(loc.indexInBox + 1, added)
        }
        active = index + added.size; selStart = end; selEnd = end
    }

    /**
     * Deletes the text from [s] to [e]. Within one container (the body, a table cell, a text box)
     * the two end paragraphs join and whatever lay between them goes — in the body tables and
     * pictures too, as in Word; a range running into another container only loses its text. With
     * 修订 on the text is struck through instead. The caret lands at [s].
     */
    private fun deleteRange(s: DocPos, e: DocPos) {
        val all = locs()
        val first = all[s.index]
        val last = all[e.index]
        val fmt = ParagraphOps.formatAt(first.paragraph, (s.offset - 1).coerceAtLeast(0))
        val joined = !trackChanges && s.index != e.index && when {
            first is TopLoc && last is TopLoc -> true
            first is CellLoc && last is CellLoc -> first.cell === last.cell
            first is BoxLoc && last is BoxLoc -> first.box === last.box
            else -> false
        }
        if (joined) {
            ParagraphOps.replace(first.paragraph, s.offset, ParagraphOps.length(first.paragraph), "", fmt)
            ParagraphOps.replace(last.paragraph, 0, e.offset, "", fmt)
            ParagraphOps.append(first.paragraph, last.paragraph)
            when (first) {
                is TopLoc -> for (k in (last as TopLoc).blockIndex downTo first.blockIndex + 1) document.blocks.removeAt(k)
                is CellLoc -> for (k in (last as CellLoc).indexInCell downTo first.indexInCell + 1) first.cell.content.removeAt(k)
                is BoxLoc -> for (k in (last as BoxLoc).indexInBox downTo first.indexInBox + 1) first.box.paragraphs.removeAt(k)
            }
        } else {
            for (i in e.index downTo s.index) {
                val p = all[i].paragraph
                val a = if (i == s.index) s.offset else 0
                val b = if (i == e.index) e.offset else ParagraphOps.length(p)
                if (b <= a) continue
                if (trackChanges) ParagraphOps.trackedReplace(p, a, b, "", fmt, reviewerName, System.currentTimeMillis())
                else ParagraphOps.replace(p, a, b, "", fmt)
            }
        }
        spanAnchor = null
        active = s.index; selStart = s.offset; selEnd = s.offset
    }

    /** Calls [each] with the format of every run the range [a, b) of [p] touches. */
    private inline fun runsIn(p: Paragraph, a: Int, b: Int, each: (CharacterFormat) -> Unit) {
        var pos = 0
        for (r in p.runs) {
            val end = pos + r.text.length
            if (r.text.isNotEmpty() && end > a && pos < b) each(r.format)
            pos = end
        }
    }

    /** Whether all the selected text passes [test] — Word's rule for Bold & co.: all bold → unbold, else bold. A caret tests its own format. */
    private fun selectionAll(test: (CharacterFormat) -> Boolean): Boolean {
        var all = true
        var any = false
        val sp = span()
        if (sp != null) forSpan(sp) { p, a, b -> runsIn(p, a, b) { any = true; if (!test(it)) all = false } }
        else {
            val r = selRange() ?: return test(currentFormat)
            runsIn(activeParagraph, r.first, r.second) { any = true; if (!test(it)) all = false }
        }
        return any && all
    }

    /** Insert a new paragraph after the active one, in its own container. */
    /**
     * Word's Page Break (Insert ▸ Page Break, Ctrl+Enter): the selection gone, the paragraph split at the caret with a
     * page break between the two halves, the caret at the start of the second — the top of the new page. One undo step.
     */
    fun insertPageBreak() = command {
        replaceSelectionWith("")
        val index = active
        val loc = locAt(index)
        val para = loc.paragraph
        val tail = ParagraphOps.splitAt(para, selStart)
        tail.format.pageBreakBefore = false
        val brk = Paragraph(mutableListOf(TextRun(com.therealityteam.aeropages.model.PAGE_BREAK)))
        val added = listOf(brk, tail)
        when (loc) {
            is TopLoc -> document.blocks.addAll(loc.blockIndex + 1, added.map { ParagraphBlock(it) })
            is CellLoc -> loc.cell.content.addAll(loc.indexInCell + 1, added)
            is BoxLoc -> loc.box.paragraphs.addAll(loc.indexInBox + 1, added)
        }
        active = index + 2; selStart = 0; selEnd = 0
    }

    /**
     * Word's Insert ▸ Blank Page: the paragraph split at the caret with a page of its own between (a page break, an empty
     * paragraph, a page break); the caret goes to the blank page. One undo step.
     */
    fun insertBlankPage() = command {
        replaceSelectionWith("")
        val index = active
        val loc = locAt(index)
        val tail = ParagraphOps.splitAt(loc.paragraph, selStart)
        tail.format.pageBreakBefore = false
        fun brk() = Paragraph(mutableListOf(TextRun(com.therealityteam.aeropages.model.PAGE_BREAK)))
        val added = listOf(brk(), Paragraph.of(""), brk(), tail)
        when (loc) {
            is TopLoc -> document.blocks.addAll(loc.blockIndex + 1, added.map { ParagraphBlock(it) })
            is CellLoc -> loc.cell.content.addAll(loc.indexInCell + 1, added)
            is BoxLoc -> loc.box.paragraphs.addAll(loc.indexInBox + 1, added)
        }
        active = index + 2; selStart = 0; selEnd = 0
    }

    /** Whether the document starts with a cover page (Word's Remove Current Cover Page is then there). */
    val hasCoverPage: Boolean get() = com.therealityteam.aeropages.model.CoverPages.end(document) != null

    /**
     * Word's Insert ▸ Cover Page: a [design] cover at the start of the document — replacing the one it has — saying
     * [fields], in the colour of its first heading style; the caret goes to its title, a placeholder chosen whole so
     * typing replaces it. One undo step.
     */
    fun insertCoverPage(design: com.therealityteam.aeropages.model.CoverDesign, fields: com.therealityteam.aeropages.model.CoverPages.Fields) = command {
        com.therealityteam.aeropages.model.CoverPages.end(document)?.let { end -> repeat(end + 1) { document.blocks.removeAt(0) } }
        val pg = document.blocks.firstNotNullOfOrNull { (it as? ParagraphBlock)?.paragraph?.format?.sectionBreak } ?: document.page
        val accent = document.styles.resolve(document.styles.idOf(com.therealityteam.aeropages.model.ParagraphStyle.HEADING1)).color ?: 0xFF2F5496
        val blocks = com.therealityteam.aeropages.model.CoverPages.blocks(design, pg, fields, accent)
        document.blocks.addAll(0, blocks)
        document.coverPage = true
        // As Word does: the cover carries no header or footer (Different First Page, its first-page ones left as they are).
        document.differentFirstPage = true
        val title = blocks.filterIsInstance<ParagraphBlock>().first { it.paragraph.format.style == com.therealityteam.aeropages.model.ParagraphStyle.TITLE }.paragraph
        active = locs().indexOfFirst { it.paragraph === title }.coerceAtLeast(0)
        val len = title.plainText().length
        selStart = if (com.therealityteam.aeropages.model.CoverPages.isPlaceholder(title.plainText())) 0 else len; selEnd = len
    }

    /** Word's Remove Current Cover Page: the cover's blocks taken away, one undo step; false when there is none. */
    fun removeCoverPage(): Boolean {
        val end = com.therealityteam.aeropages.model.CoverPages.end(document) ?: return false
        command {
            repeat(end + 1) { document.blocks.removeAt(0) }
            document.coverPage = false
            active = 0; selStart = 0; selEnd = 0
        }
        return true
    }

    fun insertParagraphAfter(text: String, format: CharacterFormat = CharacterFormat()) = command {
        val loc = locAt(active)
        val para = Paragraph(mutableListOf(TextRun(text, format)))
        when (loc) {
            is TopLoc -> document.blocks.add(loc.blockIndex + 1, ParagraphBlock(para))
            is CellLoc -> loc.cell.content.add(loc.indexInCell + 1, para)
            is BoxLoc -> loc.box.paragraphs.add(loc.indexInBox + 1, para)
        }
        active += 1; selStart = 0; selEnd = ParagraphOps.length(activeParagraph)
    }

    // --- track changes (insertions) --------------------------------------------

    var trackChanges by mutableStateOf(false)
        private set
    /** Name stamped on this session's revisions and comments (Word's user name); blank = the localised "Me". */
    var reviewer by mutableStateOf("")
        private set
    val reviewerName: String get() = reviewer.ifBlank { Texts.get(TextId.COMMENT_AUTHOR_ME) }
    fun changeReviewer(name: String) { reviewer = name.trim(); bump() }
    /** Flag [f] as a format revision by the current reviewer (an existing attribution is kept), remembering [prior]: its look before. */
    private fun stampFormat(f: CharacterFormat, prior: CharacterFormat? = f.look()) {
        if (!f.formatChanged) f.before = prior   // a second change still goes back to the look before the first
        f.formatChanged = true
        if (f.revAuthor == null) { f.revAuthor = reviewerName; f.revDate = System.currentTimeMillis() }
    }

    /** [change] applied to [f]; with 修订 on, a format revision that remembers how [f] looked before. */
    private inline fun reformat(f: CharacterFormat, change: (CharacterFormat) -> Unit) {
        if (!trackChanges) { change(f); return }
        val prior = f.look()
        change(f)
        stampFormat(f, prior)
    }

    enum class RevKind { INSERT, DELETE, FORMAT }
    /** What a format revision changed (Word's "Formatted: Font: Bold"): a property, now [on] or off, with its new [value] for a font or a size. */
    enum class TraitKind { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, SUPERSCRIPT, SUBSCRIPT, SMALL_CAPS, FONT, SIZE, COLOR, HIGHLIGHT, SPACING, LINK }
    data class Trait(val kind: TraitKind, val on: Boolean, val value: String? = null)
    /** One tracked change: a run of characters of one kind by one reviewer inside a paragraph; a format revision's [traits] when its old look is known. */
    class Revision(
        val paragraphId: Long, val paragraphIndex: Int, val start: Int, val end: Int, val kind: RevKind, val author: String, val dateMs: Long, val text: String,
        val traits: List<Trait> = emptyList(),
    )

    /** What [f]'s format revision changed from the look it remembers; empty when it remembers none. */
    private fun traitsOf(f: CharacterFormat): List<Trait> {
        val b = f.before ?: return emptyList()
        val out = ArrayList<Trait>()
        fun flag(k: TraitKind, was: Boolean, now: Boolean) { if (was != now) out.add(Trait(k, now)) }
        flag(TraitKind.BOLD, b.bold, f.bold); flag(TraitKind.ITALIC, b.italic, f.italic); flag(TraitKind.UNDERLINE, b.underline, f.underline)
        flag(TraitKind.STRIKETHROUGH, b.strikethrough, f.strikethrough); flag(TraitKind.SUPERSCRIPT, b.superscript, f.superscript)
        flag(TraitKind.SUBSCRIPT, b.subscript, f.subscript); flag(TraitKind.SMALL_CAPS, b.smallCaps, f.smallCaps)
        if (b.fontFamily != f.fontFamily) out.add(Trait(TraitKind.FONT, f.fontFamily != null, f.fontFamily))
        if (b.fontSizePt != f.fontSizePt) out.add(Trait(TraitKind.SIZE, f.fontSizePt != null, f.fontSizePt?.let { com.therealityteam.aeropages.model.NumberSymbols.current().local(if (it % 1f == 0f) it.toInt().toString() else it.toString()) }))
        if (b.color != f.color) out.add(Trait(TraitKind.COLOR, f.color != null))
        if (b.highlight != f.highlight) out.add(Trait(TraitKind.HIGHLIGHT, f.highlight != null))
        if (b.letterSpacingPt != f.letterSpacingPt) out.add(Trait(TraitKind.SPACING, f.letterSpacingPt != null))
        if (b.link != f.link) out.add(Trait(TraitKind.LINK, f.link != null))
        return out
    }

    /** Every tracked change in reading order; adjacent runs of one kind and reviewer are merged. */
    fun revisions(): List<Revision> {
        val out = ArrayList<Revision>()
        locs().forEachIndexed { index, loc ->
            val p = loc.paragraph
            var pos = 0
            var cur: Revision? = null
            for (run in p.runs) {
                val f = run.format
                val kind = when { f.deleted -> RevKind.DELETE; f.inserted -> RevKind.INSERT; f.formatChanged -> RevKind.FORMAT; else -> null }
                val end = pos + run.text.length
                if (kind != null) {
                    val author = f.revAuthor ?: ""
                    val c = cur
                    val traits = if (kind == RevKind.FORMAT) traitsOf(f) else emptyList()
                    cur = if (c != null && c.end == pos && c.kind == kind && c.author == author)
                        Revision(p.id, index, c.start, end, kind, author, c.dateMs, c.text + run.text, (c.traits + traits).distinctBy { it.kind })
                    else { c?.let(out::add); Revision(p.id, index, pos, end, kind, author, f.revDate ?: 0L, run.text, traits) }
                } else { cur?.let(out::add); cur = null }
                pos = end
            }
            cur?.let(out::add)
        }
        return out
    }
    /** Reviewers with pending changes, in order of first appearance ("" = unattributed). */
    fun revisionAuthors(): List<String> = revisions().map { it.author }.distinct()
    fun acceptRevision(r: Revision) = resolveRevision(r, accept = true)
    fun rejectRevision(r: Revision) = resolveRevision(r, accept = false)
    private fun resolveRevision(r: Revision, accept: Boolean) {
        if (trackingLocked) return
        val loc = locs().firstOrNull { it.paragraph.id == r.paragraphId } ?: return
        command { if (accept) ParagraphOps.acceptRange(loc.paragraph, r.start, r.end) else ParagraphOps.rejectRange(loc.paragraph, r.start, r.end) }
    }

    /** Track Changes on or off — the document's setting, so it opens as it was saved (Word's). */
    fun toggleTrackChanges() { turnTrackChanges(!trackChanges) }

    fun turnTrackChanges(on: Boolean) {
        if (!on && trackingLocked) return   // Lock Tracking: it stays on
        if (on == trackChanges && on == document.trackRevisions) return
        trackChanges = on; document.trackRevisions = on; dirty = true; bump()
    }

    /** Word's Lock Tracking is on: Track Changes stays on, the changes can be neither accepted nor rejected. */
    val trackingLocked: Boolean get() { revision; return document.trackLock != null }
    /** The lock keeps a password (set in Word): it can't be taken off here — we can't check Word's legacy password hash. */
    val trackingLockHasPassword: Boolean get() = document.trackLockHasPassword

    /** Word's Lock Tracking without a password: Track Changes on and kept on. */
    fun lockTracking() {
        if (trackingLocked) return
        turnTrackChanges(true)
        document.trackLock = emptyMap(); dirty = true; bump()
    }

    /** Takes Lock Tracking off; false (and nothing changes) when the lock keeps a password. */
    fun unlockTracking(): Boolean {
        if (!trackingLocked) return true
        if (trackingLockHasPassword) return false
        document.trackLock = null; dirty = true; bump()
        return true
    }

    /** The tracked changes the selection touches (the one at the caret when nothing is selected), in reading order. */
    fun selectionChanges(): List<Revision> {
        val sp = span()
        if (sp == null) {
            val a = minOf(selStart, selEnd); val b = maxOf(selStart, selEnd)
            if (a == b) return listOfNotNull(changeAtCaret())
            return shownRevisions().filter { it.paragraphIndex == active && it.start < b && it.end > a }
        }
        return shownRevisions().filter { r ->
            val i = r.paragraphIndex
            i in sp.first.index..sp.second.index && (i != sp.first.index || r.end > sp.first.offset) && (i != sp.second.index || r.start < sp.second.offset)
        }
    }

    /**
     * Word's Accept / Reject on a change's shortcut menu: the part of the changes the selection covers (with nothing
     * selected, the whole change at the caret); one undo step, the caret at the selection's start. False when nothing is there.
     */
    fun resolveSelection(accept: Boolean): Boolean {
        if (trackingLocked) return false
        val changes = selectionChanges()
        if (changes.isEmpty()) return false
        val sp = span()
        val a = minOf(selStart, selEnd); val b = maxOf(selStart, selEnd)
        val startAt = sp?.first ?: DocPos(active, if (a == b) changes.first().start else a)
        fun resolve(p: Paragraph, s: Int, e: Int) { if (e > s) { if (accept) ParagraphOps.acceptRange(p, s, e) else ParagraphOps.rejectRange(p, s, e) } }
        command {
            when {
                sp != null -> forSpan(sp) { p, s, e -> resolve(p, s, e) }
                a == b -> changes.first().let { r -> resolve(activeParagraph, r.start, r.end) }
                else -> resolve(activeParagraph, a, b)
            }
            spanAnchor = null
            active = startAt.index.coerceAtMost(locs().lastIndex)
            selStart = startAt.offset.coerceAtMost(ParagraphOps.length(activeParagraph)); selEnd = selStart
        }
        return true
    }

    /** Accept (or reject) every change and turn Track Changes off (Word's "Accept All Changes and Stop Tracking"), one undo step for the changes. */
    fun resolveAllAndStopTracking(accept: Boolean) {
        if (trackingLocked) return
        if (accept) acceptAllChanges() else rejectAllChanges()
        turnTrackChanges(false)
    }

    /**
     * Display for Review (Word's): every change marked (All Markup), the text as it would be with the changes taken and a
     * bar beside each changed paragraph (Simple Markup), as it would be with no marks (No Markup), or as it was before the
     * changes (Original).
     */
    enum class Markup { ALL, SIMPLE, NONE, ORIGINAL }

    var markup by mutableStateOf(Markup.ALL)
        private set

    fun showMarkup(m: Markup) { markup = m; com.therealityteam.aeropages.model.Markups.shown = m.ordinal; bump() }

    /**
     * Show Markup (Word's): which changes show marked — insertions and deletions, formatting — whether comments show, and
     * the reviewers whose changes and comments do not ("" the unattributed). A change not shown reads as taken.
     */
    var showInsertionsDeletions by mutableStateOf(true)
        private set
    var showFormatting by mutableStateOf(true)
        private set
    var showComments by mutableStateOf(true)
        private set
    var hiddenReviewers by mutableStateOf(emptySet<String>())
        private set

    fun setShowMarkup(insertionsDeletions: Boolean = showInsertionsDeletions, formatting: Boolean = showFormatting,
                      comments: Boolean = showComments, hidden: Set<String> = hiddenReviewers) {
        showInsertionsDeletions = insertionsDeletions; showFormatting = formatting; showComments = comments; hiddenReviewers = hidden
        syncMarkups()
        bump()
    }

    /** The display's state as the canvas and the PDF read it (shared, so set again when this document comes in). */
    private fun syncMarkups() {
        val m = com.therealityteam.aeropages.model.Markups
        m.shown = markup.ordinal
        m.insertionsDeletions = showInsertionsDeletions; m.formatting = showFormatting; m.hiddenReviewers = hiddenReviewers
        m.version++
    }

    /** Whether Show Markup leaves some changes out (Accept ▾ / Reject ▾ then take only the ones shown). */
    val filteringMarkup: Boolean get() = !showInsertionsDeletions || !showFormatting || hiddenReviewers.isNotEmpty()

    /** What the comment tint keys on: changes to Show Markup's comments and reviewers. */
    val markupFilter: Any get() = Triple(showComments, hiddenReviewers, showInsertionsDeletions)

    /** Whether comment [c] shows: comments ticked and its author not hidden. */
    fun commentShown(c: com.therealityteam.aeropages.model.Comment): Boolean = showComments && c.author !in hiddenReviewers

    /** Whether change [r] shows: its kind ticked, its reviewer not hidden. */
    private fun shown(r: Revision): Boolean =
        (if (r.kind == RevKind.FORMAT) showFormatting else showInsertionsDeletions) && r.author !in hiddenReviewers

    /** The changes Show Markup shows, in reading order. */
    fun shownRevisions(): List<Revision> = revisions().filter(::shown)

    /** Everyone with a change or a comment in the document (Show Markup's Specific People), in order of first appearance. */
    fun reviewersInDocument(): List<String> =
        (revisionAuthors() + locs().mapNotNull { l -> l.paragraph.format.revisionMark?.let { it.author ?: "" } } + document.comments.map { it.author }).distinct()

    /** Accept (or reject) the changes shown ("All Changes Shown"): the shown reviewers', of the shown kinds — one undo step. */
    fun resolveShownChanges(accept: Boolean) {
        if (trackingLocked) return
        command {
        val authors = reviewersInDocument().filter { it !in hiddenReviewers }
        for (a in authors) {
            allParagraphs().forEach { p ->
                if (showInsertionsDeletions) {
                    if (accept) ParagraphOps.removeDeleted(p, a) else ParagraphOps.removeInserted(p, a)
                    ParagraphOps.clearMarks(p, clearInserted = accept, clearDeleted = !accept, author = a, clearFormat = showFormatting, restoreFormat = !accept)
                } else if (showFormatting) ParagraphOps.clearMarks(p, clearInserted = false, clearDeleted = false, author = a, clearFormat = true, restoreFormat = !accept)
            }
            if (showInsertionsDeletions) resolveMarks(insertedGo = !accept, a)
        }
    }
    }

    /** Where the caret is, as a place in the text (paragraph index, offset) — a selection's start. */
    private fun caretPlace(): Pair<Int, Int> = active to minOf(selStart, selEnd)

    /**
     * Next (or [back], Previous) change from the caret (Word's Next / Previous): selected and brought into view; false when
     * there is none that way.
     */
    fun goToChange(back: Boolean = false): Boolean {
        val (pi, at) = caretPlace()
        val all = shownRevisions()
        val r = if (back) all.lastOrNull { it.paragraphIndex < pi || (it.paragraphIndex == pi && it.end < at) }
        else all.firstOrNull { it.paragraphIndex > pi || (it.paragraphIndex == pi && it.start > at) || (it.paragraphIndex == pi && it.start == at && selStart == selEnd) }
        r ?: return false
        active = r.paragraphIndex; selStart = r.start; selEnd = r.end
        revealRequest++; bump()
        return true
    }

    /** The change at the caret or in the selection (the first it meets; a caret just after one: that one), or null. */
    fun changeAtCaret(): Revision? {
        val a = minOf(selStart, selEnd); val b = maxOf(selStart, selEnd)
        val here = shownRevisions().filter { it.paragraphIndex == active }
        return if (b > a) here.firstOrNull { it.start < b && it.end > a }
        else here.firstOrNull { a in it.start until it.end } ?: here.firstOrNull { it.end == a }
    }

    /** Accept (or reject) the change at the caret, then on to the next (Word's Accept and Move to Next); false when there is none. */
    fun resolveAtCaret(accept: Boolean): Boolean {
        val r = changeAtCaret() ?: return false
        if (accept) acceptRevision(r) else rejectRevision(r)
        // On from where it was: the change is gone, so the next one is the first after its start.
        active = r.paragraphIndex.coerceAtMost(locs().lastIndex); selStart = r.start.coerceAtMost(ParagraphOps.length(activeParagraph)); selEnd = selStart
        goToChange()
        return true
    }

    /** Every paragraph including header/footer (which are outside [Document.locations]). */
    private fun allParagraphs(): List<Paragraph> =
        document.paragraphs() + listOfNotNull(document.header, document.footer, document.evenHeader, document.evenFooter, document.firstHeader, document.firstFooter)

    /** Accept all tracked changes: keep insertions, apply deletions (remove struck text). */
    fun acceptAllChanges() = acceptChangesBy(null)
    /** Accept the changes of one reviewer ("" = unattributed; null = everyone). */
    fun acceptChangesBy(author: String?) {
        if (trackingLocked) return
        command {
        allParagraphs().forEach { p ->
            ParagraphOps.removeDeleted(p, author)
            ParagraphOps.clearMarks(p, clearInserted = true, clearDeleted = false, author = author)
        }
        resolveMarks(insertedGo = false, author)
    }
    }

    /**
     * Paragraph marks' tracked changes (by [author]; null: everyone): inserted ones go on Reject ([insertedGo]), deleted
     * ones on Accept — the paragraph joins the next in its container, or goes when it is empty — the others just lose their mark.
     */
    private fun resolveMarks(insertedGo: Boolean, author: String?) {
        val all = locs()
        for (i in all.indices.reversed()) {
            val loc = all[i]
            val m = loc.paragraph.format.revisionMark ?: continue
            if (author != null && (m.author ?: "") != author) continue
            loc.paragraph.format.revisionMark = null
            if (m.inserted != insertedGo) continue
            fun along(o: ParaLoc?) = o != null && ((loc is TopLoc && o is TopLoc) || (loc is CellLoc && o is CellLoc && loc.cell === o.cell) ||
                (loc is BoxLoc && o is BoxLoc && loc.box === o.box))
            val next = all.getOrNull(i + 1)
            val together = along(next)
            // The last paragraph of its container: it goes when it is left empty and one comes before it.
            if (!together) { if (loc.paragraph.isEmpty() && along(all.getOrNull(i - 1))) removeAtLoc(loc); continue }
            if (loc.paragraph.isEmpty()) removeAtLoc(loc)
            else { ParagraphOps.append(loc.paragraph, next!!.paragraph); removeAtLoc(next) }
        }
        active = active.coerceAtMost(locs().lastIndex.coerceAtLeast(0))
    }

    /** Reject all tracked changes: remove insertions, restore deletions (un-strike). */
    fun rejectAllChanges() = rejectChangesBy(null)
    /** Reject the changes of one reviewer ("" = unattributed; null = everyone). */
    fun rejectChangesBy(author: String?) {
        if (trackingLocked) return
        command {
        allParagraphs().forEach { p ->
            ParagraphOps.removeInserted(p, author)
            ParagraphOps.clearMarks(p, clearInserted = false, clearDeleted = true, author = author, restoreFormat = true)
        }
        resolveMarks(insertedGo = true, author)
    }
    }

    // --- comments --------------------------------------------------------------

    /**
     * A new comment on what is selected (across paragraphs too) — with nothing selected, on the word at the caret, as
     * Word does — its range kept with the text through every edit; on the paragraph where there is no word.
     */
    fun addComment(text: String, author: String = reviewerName) = command {
        val range = commentSpan()
        val all = locs()
        val target = range?.first?.index?.let { all.getOrNull(it)?.paragraph } ?: activeParagraph
        val c = com.therealityteam.aeropages.model.Comment(author, text, target.id)
        document.comments.add(c)
        if (range != null) {
            val name = com.therealityteam.aeropages.model.Bookmarks.commentMark(c.id)
            com.therealityteam.aeropages.model.Bookmarks.place(all[range.first.index].paragraph, com.therealityteam.aeropages.model.BookmarkMark(name, range.first.offset, false))
            com.therealityteam.aeropages.model.Bookmarks.place(all[range.second.index].paragraph, com.therealityteam.aeropages.model.BookmarkMark(name, range.second.offset, true))
        }
    }

    /** What a new comment is about: the selection, else the word at the caret (as the platform's word breaks find it); null for neither. */
    private fun commentSpan(): Pair<DocPos, DocPos>? {
        val (a, b) = selectionEnds()
        if (a != b) return a to b
        val text = activeParagraph.plainText()
        if (text.isEmpty()) return null
        val at = selStart.coerceIn(0, text.length)
        val words = java.text.BreakIterator.getWordInstance().apply { setText(text) }
        val start = if (at < text.length && words.isBoundary(at) && text[at].isLetterOrDigit()) at else words.preceding(at.coerceAtLeast(1)).coerceAtLeast(0)
        val end = words.following(start).let { if (it == java.text.BreakIterator.DONE) text.length else it }
        if (end <= start || text.substring(start, end).none { it.isLetterOrDigit() } || at !in start..end) return null
        return DocPos(active, start) to DocPos(active, end)
    }

    /** Reply to a comment thread (replies hang off the top-level comment). */
    fun replyComment(parent: com.therealityteam.aeropages.model.Comment, text: String, author: String = reviewerName) = command {
        val root = document.comments.firstOrNull { it.id == parent.parentId } ?: parent
        document.comments.add(com.therealityteam.aeropages.model.Comment(author, text, root.targetId, parentId = root.id))
    }
    fun toggleResolved(comment: com.therealityteam.aeropages.model.Comment) = command { comment.resolved = !comment.resolved }
    /** Replies of a top-level comment, oldest first. */
    fun repliesOf(comment: com.therealityteam.aeropages.model.Comment): List<com.therealityteam.aeropages.model.Comment> =
        document.comments.filter { it.parentId == comment.id }

    /** The comment the reader is on (Previous / Next, a margin mark tapped), highlighted in the pane; null for none. */
    var currentComment by mutableStateOf<com.therealityteam.aeropages.model.Comment?>(null)

    /** The top-level comments in the order of the paragraphs they are on. */
    fun commentsInOrder(): List<com.therealityteam.aeropages.model.Comment> {
        val order = locs().withIndex().associate { (i, l) -> l.paragraph.id to i }
        return document.comments.filter { it.parentId == null && it.targetId in order }.sortedBy { order[it.targetId] }
    }

    /** Bumped when something asks for the comments pane (a comment's mark in the margin tapped). */
    var commentsPaneRequest by mutableIntStateOf(0)
        private set
    fun requestCommentsPane() { commentsPaneRequest++ }

    /** Next (or [back], Previous) comment from the current one or the caret: its paragraph brought into view; false when there is none. */
    fun goToComment(back: Boolean = false): Boolean {
        val list = commentsInOrder()
        if (list.isEmpty()) return false
        val cur = currentComment?.let { list.indexOf(it) }?.takeIf { it >= 0 }
        val next = if (cur != null) list.getOrNull(if (back) cur - 1 else cur + 1) else {
            val order = locs().withIndex().associate { (i, l) -> l.paragraph.id to i }
            if (back) list.lastOrNull { (order[it.targetId] ?: 0) < active } else list.firstOrNull { (order[it.targetId] ?: 0) > active } ?: list.firstOrNull { (order[it.targetId] ?: 0) == active }
        } ?: return false
        currentComment = next
        // The text it is about selected (Word's), else its paragraph.
        val range = commentRange(next)
        if (range != null) selectRange(range.first, range.second)
        else { val idx = indexOfParagraphId(next.targetId); if (idx >= 0) focusParagraph(idx) }
        return true
    }

    /** Delete All Comments in Document; how many threads went. */
    fun deleteAllComments(): Int {
        val n = document.comments.count { it.parentId == null }
        if (n > 0) command {
            com.therealityteam.aeropages.model.Bookmarks.dropCommentMarks(document, document.comments.mapTo(HashSet()) { it.id })
            document.comments.clear()
        }
        currentComment = null
        return n
    }

    /** Delete a comment and, for a top-level comment, its replies. */
    fun deleteComment(comment: com.therealityteam.aeropages.model.Comment) = command {
        val gone = document.comments.filter { it === comment || it.parentId == comment.id }
        com.therealityteam.aeropages.model.Bookmarks.dropCommentMarks(document, gone.mapTo(HashSet()) { it.id })
        document.comments.removeAll(gone.toSet())
    }

    /** The text comment [comment] (or its thread's) is about, as its two ends in reading order; null for a comment on a paragraph. */
    fun commentRange(comment: com.therealityteam.aeropages.model.Comment): Pair<DocPos, DocPos>? {
        val f = com.therealityteam.aeropages.model.Bookmarks.commentRanges(document)[comment.parentId ?: comment.id] ?: return null
        return DocPos(f.startPara, f.start) to DocPos(f.endPara, f.end)
    }

    /** Live-edit a comment's text (keystrokes coalesce into one undo step). */
    fun updateComment(comment: com.therealityteam.aeropages.model.Comment, text: String) {
        beginSession("comment@${System.identityHashCode(comment)}"); comment.text = text; bump()
    }

    /** Flat reading-order index of the paragraph with [id], or -1. */
    fun indexOfParagraphId(id: Long): Int = locs().indexOfFirst { it.paragraph.id == id }

    // --- proofing ---------------------------------------------------------------

    /** The paragraphs' texts in [proofread]'s order: what the spell checker is asked about. */
    fun proofTexts(): List<String> = locs().map { it.paragraph.plainText() }

    /**
     * A spelling suggestion taken: [word] at [start] of paragraph [index] becomes [replacement], in the
     * word's own formatting, one undo step. False when the text there has changed since it was checked.
     */
    fun applySpelling(index: Int, start: Int, word: String, replacement: String): Boolean {
        val para = locs().getOrNull(index)?.paragraph ?: return false
        if (!wordAt(para.plainText(), start, word)) return false
        command { ParagraphOps.replace(para, start, start + word.length, replacement, ParagraphOps.formatAt(para, start)) }
        return true
    }

    /** Find simple writing issues: doubled words and double spaces (the spell checker's typos come on top). */
    fun proofread(): List<ProofIssue> {
        val issues = ArrayList<ProofIssue>()
        locs().forEachIndexed { index, loc -> issues += ownProofIssues(index, loc.paragraph.plainText()) }
        return issues
    }

    // --- references (TOC / footnotes / captions / citations) -------------------

    /** A table of contents where the caret is (before its paragraph), as Word inserts one; a document has one. */
    fun insertToc() = command {
        if (document.blocks.none { it is com.therealityteam.aeropages.model.TocBlock && it.caption == null }) {
            document.blocks.add(topBlockIndexOf(active), com.therealityteam.aeropages.model.TocBlock())
        }
    }

    /** Whether the document has a table of contents (of its headings). */
    val hasToc: Boolean get() = document.blocks.any { it is com.therealityteam.aeropages.model.TocBlock && it.caption == null }

    /** Word's Remove Table of Contents. */
    fun removeToc() = command { document.blocks.removeAll { it is com.therealityteam.aeropages.model.TocBlock && it.caption == null } }

    /**
     * Word's Insert Table of Figures: a live list of the captions numbered by sequence [label] ("Figure", "Table"), each
     * with its page, before the caret's paragraph; one for that label where there was one already (it moves here).
     */
    fun insertTableOfFigures(label: String) = command {
        // The label as the document's captions spell it (Word's \c is not case-sensitive).
        val name = CrossReferences.labels(document).firstOrNull { it.equals(label, ignoreCase = true) } ?: label
        document.blocks.removeAll { it is com.therealityteam.aeropages.model.TocBlock && it.caption.equals(name, ignoreCase = true) }
        val at = topBlockIndexOf(active).coerceIn(0, document.blocks.size)
        document.blocks.add(at, com.therealityteam.aeropages.model.TocBlock(name))
    }

    // --- index (Word's References ▸ Index) ------------------------------------------------------------------------

    /** The document's index, if it has one. */
    val indexBlock: com.therealityteam.aeropages.model.IndexBlock?
        get() = document.blocks.firstOrNull { it is com.therealityteam.aeropages.model.IndexBlock } as? com.therealityteam.aeropages.model.IndexBlock

    /**
     * Word's Mark Entry: index entry [main] (and under it [sub]) marked where the selection ends — Word puts its XE field
     * right after the marked text. False (nothing marked) without a main entry.
     */
    fun markIndexEntry(main: String, sub: String? = null): Boolean {
        val m = com.therealityteam.aeropages.model.IndexEntries.clean(main)
        if (m.isEmpty()) return false
        val s = sub?.let(com.therealityteam.aeropages.model.IndexEntries::clean)?.ifEmpty { null }
        command {
            val (_, b) = selectionEnds()
            val p = locs()[b.index.coerceIn(0, locs().lastIndex)].paragraph
            com.therealityteam.aeropages.model.Bookmarks.place(p, com.therealityteam.aeropages.model.BookmarkMark(
                com.therealityteam.aeropages.model.IndexEntries.markName(com.therealityteam.aeropages.model.IndexEntries.Entry(m, s)), b.offset.coerceIn(0, ParagraphOps.length(p)), false))
        }
        return true
    }

    /**
     * Word's Mark All: the entry marked after the first time [text] appears (as it is written, case and all) in each
     * paragraph that has it and has no such entry yet; how many it marked (0 changes nothing).
     */
    fun markAllIndexEntries(text: String, main: String, sub: String? = null): Int {
        val m = com.therealityteam.aeropages.model.IndexEntries.clean(main)
        val needle = text.trim()
        if (m.isEmpty() || needle.isEmpty()) return 0
        val entry = com.therealityteam.aeropages.model.IndexEntries.Entry(m, sub?.let(com.therealityteam.aeropages.model.IndexEntries::clean)?.ifEmpty { null })
        val targets = locs().mapNotNull { loc ->
            val p = loc.paragraph
            val at = p.plainText().indexOf(needle).takeIf { it >= 0 } ?: return@mapNotNull null
            if (p.bookmarks.any { com.therealityteam.aeropages.model.IndexEntries.entryOf(it.name) == entry }) return@mapNotNull null
            p to at + needle.length
        }
        if (targets.isEmpty()) return 0
        command {
            targets.forEach { (p, at) ->
                com.therealityteam.aeropages.model.Bookmarks.place(p, com.therealityteam.aeropages.model.BookmarkMark(com.therealityteam.aeropages.model.IndexEntries.markName(entry), at, false))
            }
        }
        return targets.size
    }

    /** The entries marked, in reading order. */
    fun indexEntries(): List<com.therealityteam.aeropages.model.IndexEntries.Ref> = com.therealityteam.aeropages.model.IndexEntries.refs(document)

    /** Takes index entry [ref] out (its text stays). */
    fun removeIndexEntry(ref: com.therealityteam.aeropages.model.IndexEntries.Ref) = command {
        locs().getOrNull(ref.para)?.paragraph?.bookmarks?.removeAll { !it.end && it.offset == ref.offset && com.therealityteam.aeropages.model.IndexEntries.entryOf(it.name) == ref.entry }
    }

    /** Puts the caret where index entry [ref] is marked and brings it into view. */
    fun goToIndexEntry(ref: com.therealityteam.aeropages.model.IndexEntries.Ref) {
        if (ref.para !in locs().indices) return
        focusParagraph(ref.para)
        setSelection(ref.para, ref.offset, ref.offset)
    }

    /**
     * Word's Insert Index: a live index of the marked entries before the caret's paragraph — or, where the document has
     * one already, that one laid out anew (Word asks to replace it).
     */
    fun insertIndex(rightAlign: Boolean, headings: Boolean) = command {
        val block = com.therealityteam.aeropages.model.IndexBlock(rightAlign, headings)
        val at = document.blocks.indexOfFirst { it is com.therealityteam.aeropages.model.IndexBlock }
        if (at >= 0) document.blocks[at] = block
        else document.blocks.add(topBlockIndexOf(active).coerceIn(0, document.blocks.size), block)
    }

    /** Takes the index out (the entries stay marked). */
    fun removeIndex() = command { document.blocks.removeAll { it is com.therealityteam.aeropages.model.IndexBlock } }

    /** Insert a live bibliography after the active block. */
    fun insertBibliography() = command {
        if (document.blocks.none { it is com.therealityteam.aeropages.model.BibliographyBlock }) {
            document.blocks.add(topBlockIndexOf(active) + 1, com.therealityteam.aeropages.model.BibliographyBlock())
        }
    }

    /** Insert a footnote: a superscript marker at the caret + a new footnote entry. */
    fun insertFootnote() = command {
        // Empty, as Word adds it: the footnotes pane shows its prompt until it is typed in.
        document.footnotes.add(com.therealityteam.aeropages.model.Footnote(""))
        val n = document.footnotes.size
        val p = activeParagraph
        val (s, e) = selRange() ?: (selStart to selStart)
        val fmt = ParagraphOps.formatAt(p, (s - 1).coerceAtLeast(0)); fmt.superscript = true
        val marker = "[$n]"
        ParagraphOps.replace(p, s, e, marker, fmt)
        val caret = s + marker.length; selStart = caret; selEnd = caret
    }

    fun updateFootnote(footnote: com.therealityteam.aeropages.model.Footnote, text: String) {
        beginSession("footnote@${System.identityHashCode(footnote)}"); footnote.text = text; bump()
    }

    fun deleteFootnote(footnote: com.therealityteam.aeropages.model.Footnote) = command {
        val idx = document.footnotes.indexOf(footnote)
        if (idx < 0) return@command
        document.footnotes.removeAt(idx)
        // Word behavior: drop the deleted note's [n] anchor, renumber the later ones.
        // Adjacent markers coalesce into one superscript run ("[1][2][3]"), so rewrite
        // marker occurrences inside each superscript run rather than whole-run matching.
        val n = idx + 1
        val markerRx = Regex("""\[(\d+)]""")
        document.paragraphs().forEach { p ->
            p.runs.forEach { r ->
                if (!r.format.superscript || !r.text.contains('[')) return@forEach
                r.text = markerRx.replace(r.text) { m ->
                    val k = m.groupValues[1].toInt()
                    when {
                        k == n -> ""
                        k > n -> "[${k - 1}]"
                        else -> m.value
                    }
                }
            }
            p.runs.removeAll { it.text.isEmpty() }
            if (p.runs.isEmpty()) p.runs.add(TextRun(""))
        }
    }

    /** Insert an endnote: a superscript roman marker at the caret + a new entry printed after the body. */
    fun insertEndnote() = command {
        document.endnotes.add(com.therealityteam.aeropages.model.Endnote(""))
        val n = document.endnotes.size
        val p = activeParagraph
        val (s, e) = selRange() ?: (selStart to selStart)
        val fmt = ParagraphOps.formatAt(p, (s - 1).coerceAtLeast(0)); fmt.superscript = true
        val marker = "[${com.therealityteam.aeropages.model.Roman.of(n)}]"
        ParagraphOps.replace(p, s, e, marker, fmt)
        val caret = s + marker.length; selStart = caret; selEnd = caret
    }

    fun updateEndnote(endnote: com.therealityteam.aeropages.model.Endnote, text: String) {
        beginSession("endnote@${System.identityHashCode(endnote)}"); endnote.text = text; bump()
    }

    fun deleteEndnote(endnote: com.therealityteam.aeropages.model.Endnote) = command {
        val idx = document.endnotes.indexOf(endnote)
        if (idx < 0) return@command
        document.endnotes.removeAt(idx)
        val n = idx + 1
        document.paragraphs().forEach { p ->
            p.runs.forEach { r ->
                if (!r.format.superscript || !r.text.contains('[')) return@forEach
                r.text = com.therealityteam.aeropages.model.Roman.marker.replace(r.text) { m ->
                    val k = com.therealityteam.aeropages.model.Roman.parse(m.groupValues[1]) ?: return@replace m.value
                    when {
                        k == n -> ""
                        k > n -> "[${com.therealityteam.aeropages.model.Roman.of(k - 1)}]"
                        else -> m.value
                    }
                }
            }
            p.runs.removeAll { it.text.isEmpty() }
            if (p.runs.isEmpty()) p.runs.add(TextRun(""))
        }
    }

    /** The page (1-based) each location's paragraph is on, as the canvas last laid the document out; null before it has. */
    var pageOfLocation: (Int) -> Int? = { null }

    /** How a page (1-based, as [pageOfLocation] counts) is numbered on it: its section's format and start ("iii", "1"). */
    var pageLabelOf: (Int) -> String = { it.toString() }

    /**
     * Insert Caption (Word's): a caption for sequence [label] — the label unless not [withLabel], the sequence's next
     * number as [format] writes it (`ARABIC`, `ALPHABETIC`, `roman` …) — [below] (or above) the picked picture, the
     * table the caret is in, or the caret's paragraph; the captions after it renumbered, the caret at its end for its text.
     */
    fun insertCaption(label: String, below: Boolean = true, format: String = "ARABIC", withLabel: Boolean = true, chapterSeparator: String? = null) = command {
        val para = CrossReferences.captionParagraph(document, label, format, withLabel, chapterSeparator)
        val img = selectedImage?.let { im -> document.blocks.indexOfFirst { it === im } }?.takeIf { it >= 0 }
        when (val loc = locAt(active)) {
            is TopLoc -> document.blocks.add(img?.let { if (below) it + 1 else it } ?: if (below) loc.blockIndex + 1 else loc.blockIndex, ParagraphBlock(para))
            is CellLoc -> { val bi = topBlockIndexOf(active); document.blocks.add(if (below) bi + 1 else bi, ParagraphBlock(para)) }
            is BoxLoc -> loc.box.paragraphs.add(if (below) loc.indexInBox + 1 else loc.indexInBox, para)
        }
        com.therealityteam.aeropages.model.Fields.update(document, null, setOf("SEQ", "STYLEREF", "REF"))
        val at = locs().indexOfFirst { it.paragraph === para }
        if (at >= 0) { active = at; val len = ParagraphOps.length(para); selStart = len; selEnd = len }
        selectedImage = null
    }

    /**
     * Insert Cross-reference (Word's): a field at the caret (in place of the selection) showing [content] of [target] —
     * its text, a caption's label and number or its text, its number, its page, above or below — that links to it when
     * [link]; a hidden `_Ref…` bookmark is put round what it shows when it needs one.
     */
    fun insertCrossReference(target: CrossReferences.Target, content: CrossReferences.Content, link: Boolean) = command {
        val name = CrossReferences.bookmarkFor(document, target, content)
        val field = com.therealityteam.aeropages.model.DocField(CrossReferences.codeFor(name, content, link))
        val p = activeParagraph
        val (s, e) = selRange() ?: (selStart to selStart)
        val fmt = ParagraphOps.formatAt(p, (s - 1).coerceAtLeast(0)).copyOf().also { it.field = field; it.math = null; it.link = if (link) "#$name" else null }
        ParagraphOps.replaceKeeping(p, s, e, "?", fmt)
        // Typed at a bookmark's start, text goes into it — but a reference must not be inside what it shows.
        p.bookmarks.forEach { if (it.name == name && !it.end && it.offset == s) it.offset = s + 1 }
        com.therealityteam.aeropages.model.Fields.update(document, pageOfLocation, pageShown = pageLabelOf)
        val end = com.therealityteam.aeropages.model.Fields.placed(document).firstOrNull { it.field === field }?.end ?: (s + 1)
        selStart = end; selEnd = end
    }

    /** Update Field (F9) for the whole document: captions renumbered, cross-references worked out again; how many changed. */
    fun updateFields(): Int {
        var n = 0
        command { n = com.therealityteam.aeropages.model.Fields.update(document, pageOfLocation, pageShown = pageLabelOf) }
        return n
    }

    fun addSource(): com.therealityteam.aeropages.model.Source {
        // Blank, as Word's Create Source starts (the pane's fields show what goes where), dated this year.
        val src = com.therealityteam.aeropages.model.Source("", "", java.time.Year.now().value.toString())
        command { document.sources.add(src) }
        return src
    }

    fun updateSource(source: com.therealityteam.aeropages.model.Source, author: String, title: String, year: String) {
        beginSession("source@${System.identityHashCode(source)}")
        source.author = author; source.title = title; source.year = year; bump()
    }

    fun deleteSource(source: com.therealityteam.aeropages.model.Source) = command { document.sources.remove(source) }

    /** Insert an in-text citation "(author, year)" for [source] at the caret. */
    /** An author-year citation, "(Author, 2026)"; a part the source leaves blank is left out (its title stands in for no author). */
    fun insertCitation(source: com.therealityteam.aeropages.model.Source) =
        insertAtCaret("(" + listOf(source.author.ifBlank { source.title }, source.year).filter { it.isNotBlank() }.joinToString(", ").ifEmpty { "?" } + ")")

    // --- images ----------------------------------------------------------------

    // --- displayed equations ----------------------------------------------------------

    /** The displayed equation picked on the page (its frame and actions show), or null. */
    var selectedEquation: com.therealityteam.aeropages.model.EquationBlock? = null
        private set

    /** Bumped when the picked equation's editor should open (its Edit action on the page). */
    var equationEditRequest by mutableIntStateOf(0)
        private set

    fun requestEquationEdit() { equationEditRequest++ }

    /** Pick [block] on the page (null: none). */
    fun selectEquation(block: com.therealityteam.aeropages.model.EquationBlock?) {
        selectedEquation = block?.takeIf { b -> document.blocks.any { it === b } }
        if (selectedEquation != null) selectedImage = null
        bump()
    }

    /** The equation in the text at the caret (the caret on it, or the selection starting in it), or null. */
    val inlineMathAtCaret: com.therealityteam.aeropages.model.InlineMath?
        get() = ParagraphOps.mathAt(activeParagraph, minOf(selStart, selEnd))?.first

    /**
     * Insert [math] at the caret: displayed, on its own line after the paragraph ([display], and the
     * caret in the body — a table cell or a text box takes it in the text), or in the text, replacing
     * the selection. A displayed one comes up picked.
     */
    fun insertEquation(math: com.therealityteam.aeropages.model.MathNode, display: Boolean) = command {
        val loc = locAt(active)
        val p = loc.paragraph
        val size = p.runs.firstNotNullOfOrNull { it.format.fontSizePt } ?: com.therealityteam.aeropages.model.EquationBlock.DEFAULT_SIZE
        if (display && loc is TopLoc) {
            val block = com.therealityteam.aeropages.model.EquationBlock(math, TextAlign.CENTER, size)
            document.blocks.add(loc.blockIndex + 1, block)
            selectedEquation = block
        } else {
            val a = minOf(selStart, selEnd); val b = maxOf(selStart, selEnd)
            val base = ParagraphOps.formatAt(p, (a - 1).coerceAtLeast(0))
            if (b > a) ParagraphOps.replace(p, a, b, "", base)
            val runs = com.therealityteam.aeropages.model.MathText.runs(com.therealityteam.aeropages.model.InlineMath(math),
                CharacterFormat(fontSizePt = base.fontSizePt ?: size, color = base.color))
            ParagraphOps.insertRuns(p, a, runs)
            selStart = a + runs.sumOf { it.text.length }; selEnd = selStart
        }
    }

    /** A displayed equation now reads [math]. */
    fun updateEquation(block: com.therealityteam.aeropages.model.EquationBlock, math: com.therealityteam.aeropages.model.MathNode) {
        if (document.blocks.none { it === block } || block.math == math) return
        command { block.math = math }
    }

    /** The equation in the text at the caret now reads [math]: its runs are set anew, in place. */
    fun updateInlineMath(math: com.therealityteam.aeropages.model.MathNode) {
        val p = activeParagraph
        val (old, range) = ParagraphOps.mathAt(p, minOf(selStart, selEnd)) ?: return
        if (old.math == math) return
        command {
            val first = ParagraphOps.formatAt(p, range.first)
            ParagraphOps.replace(p, range.first, range.last + 1, "", first)
            val runs = com.therealityteam.aeropages.model.MathText.runs(com.therealityteam.aeropages.model.InlineMath(math),
                CharacterFormat(fontSizePt = first.fontSizePt, color = first.color))
            ParagraphOps.insertRuns(p, range.first, runs)
            selStart = range.first; selEnd = selStart
        }
    }

    // --- charts ---------------------------------------------------------------------

    /** The chart picked on the page (its frame and actions show), or null. */
    var selectedChart: com.therealityteam.aeropages.model.ChartBlock? = null
        private set

    fun selectChart(block: com.therealityteam.aeropages.model.ChartBlock?) {
        selectedChart = block?.takeIf { b -> document.blocks.any { it === b } }
        if (selectedChart != null) selectedImage = null
        bump()
    }

    /** Insert → Chart: Word's sample column chart after the paragraph at the caret (in the body), picked. */
    fun insertChart() = command {
        val block = com.therealityteam.aeropages.model.ChartBlock(com.therealityteam.aeropages.model.SlideChart.sample(com.therealityteam.aeropages.model.ChartType.COLUMN),
            com.therealityteam.aeropages.model.ChartBlock.DEFAULT_W, com.therealityteam.aeropages.model.ChartBlock.DEFAULT_H)
        document.blocks.add(topBlockIndexOf(active) + 1, block)
        selectedChart = block
        chartInserts++
    }

    /** Bumped when the picked chart's data editor should open. */
    var chartDataRequest by mutableIntStateOf(0)
        private set

    fun requestChartData() { chartDataRequest++ }

    /** A change to [block]'s chart (its data, its titles) as one undo step. */
    fun editChart(block: com.therealityteam.aeropages.model.ChartBlock, change: (com.therealityteam.aeropages.model.SlideChart) -> Unit) {
        if (document.blocks.none { it === block }) return
        command { change(block.chart) }
    }

    /** Remove the chart [block]. */
    fun deleteChart(block: com.therealityteam.aeropages.model.ChartBlock) {
        if (document.blocks.none { it === block }) return
        command {
            document.blocks.removeAt(document.blocks.indexOfFirst { it === block })
            if (document.blocks.isEmpty()) document.blocks.add(ParagraphBlock(Paragraph.of("")))
            if (selectedChart === block) selectedChart = null
        }
    }

    /**
     * Chart Elements on [block]: its title, axis titles, legend and value axis, as one undo step (nothing when all are as
     * they were).
     */
    fun setChartElements(
        block: com.therealityteam.aeropages.model.ChartBlock, title: String, catAxisTitle: String, valAxisTitle: String,
        showLegend: Boolean, legendPos: com.therealityteam.aeropages.model.LegendPos, axis: com.therealityteam.aeropages.model.AxisFormat,
    ) {
        val c = block.chart
        if (c.title == title && c.catAxisTitle == catAxisTitle && c.valAxisTitle == valAxisTitle &&
            c.showLegend == showLegend && c.legendPos == legendPos && c.axisFormat == axis) return
        editChart(block) {
            it.title = title; it.catAxisTitle = catAxisTitle; it.valAxisTitle = valAxisTitle
            it.showLegend = showLegend; it.legendPos = legendPos; it.axisFormat = axis
        }
    }

    /** Show [block]'s data as another kind of chart (column, bar, line, pie …). */
    fun setChartType(block: com.therealityteam.aeropages.model.ChartBlock, type: com.therealityteam.aeropages.model.ChartType) {
        if (document.blocks.none { it === block } || block.chart.type == type) return
        command { block.chart.type = type }
    }

    // --- the object clipboard: charts and pictures copied from a page, objects pasted from any module ---

    /** Copy the picked chart or picture for any module's paste; the chart's title (a picture's: ""), or null when none is picked. */
    fun copySelectedObject(): String? {
        selectedImage?.let { copyImage(it); return "" }
        val chart = selectedChart ?: return null
        ObjectClipboard.put(ObjectClipboard.Chart(chart.chart.copyOf(), chart.widthPt, chart.heightPt))
        bump()
        return chart.chart.title
    }

    /** Cut the picked chart or picture: copied, then removed. */
    fun cutSelectedObject(): String? {
        val image = selectedImage; val chart = selectedChart
        val text = copySelectedObject() ?: return null
        if (image != null) deleteImage(image) else if (chart != null) deleteChart(chart)
        return text
    }

    /** Copy [image] (at the size and crop it is shown with) for any module's paste. */
    fun copyImage(image: com.therealityteam.aeropages.model.ImageBlock) {
        ObjectClipboard.put(ObjectClipboard.Picture(image.data, image.mime, image.widthPt, image.heightPt, image.cropL, image.cropT, image.cropR, image.cropB))
        bump()
    }

    /**
     * Paste [item] after the caret's paragraph, as Word pastes from PowerPoint or Excel: a picture,
     * a drawn shape as a shape box holding its text, a chart with its data, a slide's table as a table.
     */
    fun pasteItem(item: ObjectClipboard.Item) = command {
        val block: com.therealityteam.aeropages.model.Block = when (item) {
            is ObjectClipboard.Picture -> com.therealityteam.aeropages.model.ImageBlock(item.data, item.mime, item.widthPt, item.heightPt,
                item.cropL, item.cropT, item.cropR, item.cropB)
            is ObjectClipboard.Shape -> com.therealityteam.aeropages.model.TextBoxBlock(
                if (item.shape.type == com.therealityteam.aeropages.model.ShapeType.LINE) mutableListOf() else paragraphsOf(item.shape.box),
                item.widthPt, item.heightPt, item.shape.copyOf())
            is ObjectClipboard.Chart -> com.therealityteam.aeropages.model.ChartBlock(item.chart.copyOf().also { it.sourceRef = null }, item.widthPt, item.heightPt)
            is ObjectClipboard.Table -> com.therealityteam.aeropages.model.TableBlock(com.therealityteam.aeropages.model.Table(
                item.table.cells.mapIndexedTo(mutableListOf()) { r, row ->
                    com.therealityteam.aeropages.model.TableRow(row.mapTo(mutableListOf()) { box -> com.therealityteam.aeropages.model.TableCell(paragraphsOf(box)) },
                        header = r == 0 && item.table.headerRow)
                }))
        }
        document.blocks.add(topBlockIndexOf(active) + 1, block)
        if (block is com.therealityteam.aeropages.model.ChartBlock) selectedChart = block
    }

    /** A slide box's text as paragraphs, a line each, their runs' looks and the box's alignment kept. */
    private fun paragraphsOf(box: com.therealityteam.aeropages.model.SlideBox): MutableList<Paragraph> {
        val out = mutableListOf(Paragraph())
        for (run in box.paragraph.runs) {
            run.text.split('\n').forEachIndexed { i, part ->
                if (i > 0) out.add(Paragraph())
                if (part.isNotEmpty()) out.last().runs.add(TextRun(part, run.format.copyOf()))
            }
        }
        out.forEach { it.format.align = box.align }
        return out
    }

    /** Remove the displayed equation [block]. */
    fun deleteEquation(block: com.therealityteam.aeropages.model.EquationBlock) {
        if (document.blocks.none { it === block }) return
        command {
            val at = document.blocks.indexOfFirst { it === block }
            document.blocks.removeAt(at)
            if (document.blocks.isEmpty()) document.blocks.add(ParagraphBlock(Paragraph.of("")))
            if (selectedEquation === block) selectedEquation = null
        }
    }

    /** Insert an image block after the active paragraph's top-level block. */
    /** Insert a picture after the active paragraph's block — inside the cell when the caret is in a table. */
    fun insertImage(data: ByteArray, mime: String, widthPt: Float, heightPt: Float) = command {
        val image = com.therealityteam.aeropages.model.ImageBlock(data, mime, widthPt, heightPt)
        when (val loc = locAt(active)) {
            is CellLoc -> loc.cell.images.add(image)
            else -> document.blocks.add(topBlockIndexOf(active) + 1, image)
        }
        // Word's: the new picture comes picked, its frame, handles and Picture Format at hand.
        selectImage(image)
        imageInserts++
    }

    /** The picture picked on the page (its frame and actions show), or null. */
    var selectedImage: com.therealityteam.aeropages.model.ImageBlock? = null
        private set

    /** Whether [image] is in the document: a block of its own or under a table cell's text. */
    private fun holds(image: com.therealityteam.aeropages.model.ImageBlock) = document.blocks.any { b ->
        b === image || (b is TableBlock && b.table.rows.any { r -> r.cells.any { c -> c.images.any { it === image } } })
    }

    /** Pick [image] on the page (null: none); a picked chart or equation is let go. */
    fun selectImage(image: com.therealityteam.aeropages.model.ImageBlock?) {
        if (image !== selectedImage) imageOptionsOpen = false
        selectedImage = image?.takeIf { holds(it) }
        if (selectedImage != null) { selectedChart = null; selectedEquation = null; selectedBox = null }
        bump()
    }

    /** Whether the picked picture's wrapping options are open (they close when another is picked). */
    var imageOptionsOpen = false
        private set

    fun showImageOptions(open: Boolean) {
        if (imageOptionsOpen == open) return
        imageOptionsOpen = open
        bump()
    }

    /**
     * Where [image] stands — floating at [pos], or (null) in line with the text — and how the text goes by it.
     * One undo step; a picture in a table's cell stays in it.
     */
    fun setImageFloat(image: com.therealityteam.aeropages.model.ImageBlock, pos: com.therealityteam.aeropages.model.FloatPosition?) {
        if (document.blocks.none { it === image } || image.floating == pos) return
        command { image.floating = pos?.copy() }
    }

    /** Remove [image], a block of its own or a picture in a cell. One undo step. */
    fun deleteImage(image: com.therealityteam.aeropages.model.ImageBlock) {
        if (!holds(image)) return
        command {
            val at = document.blocks.indexOfFirst { it === image }
            if (at >= 0) document.blocks.removeAt(at)
            else document.blocks.forEach { b -> if (b is TableBlock) b.table.rows.forEach { r -> r.cells.forEach { c -> c.images.removeAll { it === image } } } }
            if (document.blocks.isEmpty()) document.blocks.add(ParagraphBlock(Paragraph.of("")))
            if (selectedImage === image) selectedImage = null
        }
    }

    /**
     * Scale [image] by [factor], its aspect kept: the factor stops where either side would pass
     * 1200 × 1600 pt or drop under 16 pt (a picture already past a limit only moves back toward it).
     */
    fun scaleImage(image: com.therealityteam.aeropages.model.ImageBlock, factor: Float) = command {
        val w = image.widthPt.coerceAtLeast(1f); val h = image.heightPt.coerceAtLeast(1f)
        val most = minOf(1200f / w, 1600f / h).coerceAtLeast(1f)
        val least = maxOf(16f / w, 16f / h).coerceAtMost(1f)
        val f = factor.coerceIn(least, most)
        image.widthPt = w * f
        image.heightPt = h * f
    }

    /** [image]'s outer shadow — Picture Styles' offset one, or none. One undo step. */
    fun setImageShadow(image: com.therealityteam.aeropages.model.ImageBlock, shadow: com.therealityteam.aeropages.model.ShapeShadow?) {
        if (image.shadow == shadow) return
        command { image.shadow = shadow }
    }

    /** Alt Text on [image]: what a screen reader says for it, or ([decorative]) that it passes it by. One undo step. */
    fun setImageAltText(image: com.therealityteam.aeropages.model.ImageBlock, text: String, decorative: Boolean) {
        val t = if (decorative) "" else text.trim()
        if (!holds(image) || (image.altText == t && image.decorative == decorative)) return
        command { image.altText = t; image.decorative = decorative }
    }

    /** [image] mirrored left to right ([h]) and upside down ([v]) — Rotate's Flip Horizontal and Flip Vertical. One undo step. */
    fun setImageFlip(image: com.therealityteam.aeropages.model.ImageBlock, h: Boolean, v: Boolean) {
        if (!holds(image) || (image.flipH == h && image.flipV == v)) return
        command { image.flipH = h; image.flipV = v }
    }

    /**
     * Word's Reset Picture on [image]: its crop, shadow and flips gone, the whole picture back at the scale it was shown
     * at; with [size], also back at the size it came in at (its pixels at 96 dpi, no wider than 400 pt — Insert Picture's).
     * One undo step; nothing when there is nothing to reset.
     */
    fun resetImage(image: com.therealityteam.aeropages.model.ImageBlock, size: Boolean) {
        if (!holds(image)) return
        val (fullW, fullH) = image.uncroppedSize(image.widthPt, image.heightPt)
        val natural = if (!size) null else com.therealityteam.aeropages.format.ImageSize.of(image.data)?.let { (wpx, hpx) ->
            val s = minOf(1f, 400f / (wpx * 0.75f).coerceAtLeast(1f))
            wpx * 0.75f * s to hpx * 0.75f * s
        }
        val (w, h) = natural ?: (fullW to fullH)
        if (!image.cropped && image.shadow == null && !image.flipH && !image.flipV && w == image.widthPt && h == image.heightPt) return
        command {
            image.setCrop(0f, 0f, 0f, 0f)
            image.shadow = null; image.flipH = false; image.flipV = false
            image.widthPt = w.coerceAtLeast(4f); image.heightPt = h.coerceAtLeast(4f)
        }
    }

    /**
     * Word's Crop on [image]: [l], [t], [r], [b] of the source cut away (fractions, each pair leaving
     * at least 5%). The picture keeps its scale, so its size follows the new edges. One undo step.
     */
    fun cropImage(image: com.therealityteam.aeropages.model.ImageBlock, l: Float, t: Float, r: Float, b: Float) {
        val (nl, nt, nr, nb) = com.therealityteam.aeropages.model.Croppable.clamp(l, t, r, b)
        if (nl == image.cropL && nt == image.cropT && nr == image.cropR && nb == image.cropB) return
        command {
            val (fullW, fullH) = image.uncroppedSize(image.widthPt, image.heightPt)
            image.setCrop(nl, nt, nr, nb)
            image.widthPt = (fullW * image.keptWidth()).coerceAtLeast(4f)
            image.heightPt = (fullH * image.keptHeight()).coerceAtLeast(4f)
        }
    }

    // --- tables ----------------------------------------------------------------

    /** Insert a [rows] x [cols] table after the active paragraph's top-level block. */
    fun insertTable(rows: Int, cols: Int) = command {
        val table = Table.of(rows, cols)
        val insertAfter = topBlockIndexOf(active)
        document.blocks.add(insertAfter + 1, TableBlock(table))
        // Move the caret into the first cell of the new table.
        val firstCellPara = table.rows.first().cells.first().content.first()
        active = locs().indexOfFirst { it.paragraph === firstCellPara }.coerceAtLeast(0)
        selStart = 0; selEnd = 0
        tableInserts++
    }

    /**
     * Insert a table built from tab-separated text (a Sheets selection on the system
     * clipboard). Returns false when [text] doesn't look tabular (caller pastes plain).
     */
    fun insertTableFromTsv(text: String): Boolean {
        val lines = text.trimEnd('\n').split('\n')
        if (lines.size < 2 && !text.contains('\t')) return false
        val grid = lines.map { it.split('\t') }
        val cols = grid.maxOf { it.size }
        if (cols < 2 && lines.size < 2) return false
        command {
            val table = Table.of(grid.size, cols)
            grid.forEachIndexed { r, row ->
                row.forEachIndexed { c, value ->
                    table.rows[r].cells[c].content[0].runs.add(TextRun(value, CharacterFormat()))
                }
            }
            document.blocks.add(topBlockIndexOf(active) + 1, TableBlock(table))
        }
        return true
    }

    /** Add a row to the table containing the active cell, below it. */
    fun addTableRow() = insertTableRow(above = false)

    /** Add a column to the table containing the active cell, to its right. */
    fun addTableColumn() = insertTableColumn(left = false)

    /** An empty paragraph set as [like] is (its paragraph format, its first run's character format): what Word gives a new cell. */
    private fun emptyLike(like: com.therealityteam.aeropages.model.TableCell?): Paragraph {
        val p = like?.content?.firstOrNull() ?: return Paragraph.of("")
        return Paragraph(mutableListOf(TextRun("", p.runs.firstOrNull()?.format?.copyOf() ?: CharacterFormat())), p.format.copyOf().also { it.dropCap = null })
    }

    /**
     * Word's Insert Above / Insert Below: a row like the caret's (its cells' spans, shading, borders and type) above
     * the caret's row or below the last row its cell reaches; a row put inside a vertical merge carries the merge on.
     */
    fun insertTableRow(above: Boolean) = command {
        val ctx = cellContextOf(active) ?: return@command
        val t = ctx.table
        val keep = locAt(active).paragraph
        val likeIndex = if (above) ctx.rowIndex else ctx.rowIndex + ctx.cell.rowSpan.coerceAtLeast(1) - 1
        val like = t.rows[likeIndex]
        val at = if (above) ctx.rowIndex else likeIndex + 1
        val cells = like.cells.mapIndexed { ci, c ->
            val g = t.gridColOf(likeIndex, ci)
            // The cell the new one sits on top of continues a merge from above it: the new one does too.
            val belowIndex = if (at < t.rows.size) t.cellAtGrid(at, g) else null
            val inMerge = belowIndex != null && t.rows[at].cells[belowIndex].vMerged
            if (inMerge) t.anchorOf(at, belowIndex!!).rowSpan += 1
            com.therealityteam.aeropages.model.TableCell(mutableListOf(emptyLike(c)), shade = c.shade, gridSpan = c.gridSpan, vMerged = inMerge, borders = c.borders)
        }
        val header = like.header && (above || t.rows.getOrNull(at)?.header == true)
        t.rows.add(at, com.therealityteam.aeropages.model.TableRow(cells.toMutableList(), header = header))
        active = locs().indexOfFirst { it.paragraph === keep }.coerceAtLeast(0)
    }

    /**
     * Word's Insert Left / Insert Right: a column beside the caret's cell (to the right of all the columns it spans),
     * each row's new cell like its neighbour; a cell spanning across the new column's place widens over it instead.
     */
    fun insertTableColumn(left: Boolean) = command {
        val ctx = cellContextOf(active) ?: return@command
        val t = ctx.table
        val keep = locAt(active).paragraph
        val g0 = t.gridColOf(ctx.rowIndex, ctx.colIndex)
        val g = if (left) g0 else g0 + ctx.cell.gridSpan.coerceAtLeast(1)
        val oldGrid = t.rows.maxOfOrNull { r -> r.cells.sumOf { it.gridSpan.coerceAtLeast(1) } } ?: 0
        for (row in t.rows) {
            var start = 0
            var at = row.cells.size
            var widened = false
            for ((ci, c) in row.cells.withIndex()) {
                val span = c.gridSpan.coerceAtLeast(1)
                if (start == g) { at = ci; break }
                if (g in start + 1 until start + span) { c.gridSpan = span + 1; widened = true; break }
                start += span
            }
            if (widened) continue
            val near = row.cells.getOrNull(if (left) at else at - 1) ?: row.cells.getOrNull(at - 1) ?: row.cells.getOrNull(at)
            row.cells.add(at, com.therealityteam.aeropages.model.TableCell(mutableListOf(emptyLike(near)), shade = near?.shade, borders = near?.borders))
        }
        if (t.columnWidths.size == oldGrid && oldGrid > 0) t.columnWidths.add(g, t.columnWidths[(if (left) g else g - 1).coerceIn(0, oldGrid - 1)])
        active = locs().indexOfFirst { it.paragraph === keep }.coerceAtLeast(0)
    }

    /** Word's Delete Columns: the grid columns the caret's cell spans go (cells across them narrow); a table left with none goes too. */
    fun deleteTableColumn() = command {
        val ctx = cellContextOf(active) ?: return@command
        val t = ctx.table
        val g = t.gridColOf(ctx.rowIndex, ctx.colIndex)
        val n = ctx.cell.gridSpan.coerceAtLeast(1)
        val oldGrid = t.rows.maxOfOrNull { r -> r.cells.sumOf { it.gridSpan.coerceAtLeast(1) } } ?: 0
        for (row in t.rows) {
            var start = 0
            val it = row.cells.iterator()
            while (it.hasNext()) {
                val c = it.next()
                val span = c.gridSpan.coerceAtLeast(1)
                val overlap = (minOf(start + span, g + n) - maxOf(start, g)).coerceAtLeast(0)
                if (overlap == span) it.remove() else if (overlap > 0) c.gridSpan = span - overlap
                start += span
            }
        }
        if (t.rows.any { it.cells.isEmpty() }) { removeTableAt(ctx.table); return@command }
        if (t.columnWidths.size == oldGrid) repeat(n) { if (g < t.columnWidths.size) t.columnWidths.removeAt(g) }
        val row = t.rows[ctx.rowIndex]
        val target = row.cells.getOrNull(ctx.colIndex) ?: row.cells.last()
        active = locs().indexOfFirst { it.paragraph === target.content.first() }.coerceAtLeast(0)
        selStart = 0; selEnd = 0
    }

    /** Word's Delete Table: the caret goes to the paragraph before the table (or after it). */
    // --- text to table and back, sorting a table (Word's Insert ▸ Table, Table Tools ▸ Data) ---------

    /** The selected body paragraphs — the caret's alone without a selection — or null when any is in a table, a text box or a cell. */
    fun selectedBodyParagraphs(): List<Paragraph>? {
        val all = locs()
        val sp = span()
        val range = if (sp == null) active..active else sp.first.index..(if (sp.second.offset == 0 && sp.second.index > sp.first.index) sp.second.index - 1 else sp.second.index)
        if (range.any { all.getOrNull(it) !is com.therealityteam.aeropages.model.TopLoc }) return null
        return range.map { all[it].paragraph }
    }

    /**
     * The paragraphs Sort Text sorts: the selected body paragraphs, else — nothing selected — the run of body paragraphs
     * the caret is in, between tables and other blocks (Word sorts the document then). Null when there is nothing to sort.
     */
    fun sortTextParagraphs(): List<Paragraph>? {
        if (span() != null) return selectedBodyParagraphs()?.takeIf { it.size > 1 }
        val p = (locs().getOrNull(active) as? com.therealityteam.aeropages.model.TopLoc)?.paragraph ?: return null
        val i = document.blocks.indexOfFirst { it is ParagraphBlock && it.paragraph === p }.takeIf { it >= 0 } ?: return null
        var a = i; var b = i
        fun plain(k: Int) = (document.blocks.getOrNull(k) as? ParagraphBlock)?.paragraph?.takeIf { it.format.sectionBreak == null && '\u000C' !in it.plainText() }
        while (plain(a - 1) != null) a--
        while (plain(b + 1) != null && (document.blocks[b] as ParagraphBlock).paragraph.format.sectionBreak == null) b++
        return (a..b).map { (document.blocks[it] as ParagraphBlock).paragraph }.takeIf { it.size > 1 }
    }

    /**
     * Sort Text (Word's Home ▸ Paragraph ▸ Sort outside a table): [sortTextParagraphs] reordered by [keys] — whole
     * paragraphs, so their look, lists, bookmarks and comments go with them; a section break the last one carried stays
     * at the end. False when there is nothing to sort or a section or page break falls among them. One undo step.
     */
    fun sortParagraphs(keys: List<TextSortKey>, sep: TableSeparator = TableSeparator.TAB, other: Char? = null, header: Boolean = false, caseSensitive: Boolean = false): Boolean {
        val paras = sortTextParagraphs() ?: return false
        val at = paras.map { p -> document.blocks.indexOfFirst { it is ParagraphBlock && it.paragraph === p } }
        if (at.any { it < 0 } || at.zipWithNext().any { (x, y) -> y != x + 1 }) return false
        if (paras.dropLast(1).any { it.format.sectionBreak != null } || paras.any { '\u000C' in it.plainText() }) return false
        val sorted = TableText.sortParagraphs(paras, keys, sep, other, header, caseSensitive)
        if (sorted.zip(paras).all { (x, y) -> x === y }) return true
        command {
            val blocks = at.map { document.blocks[it] as ParagraphBlock }
            val byPara = blocks.associateBy { it.paragraph }
            val sectionBreak = paras.last().format.sectionBreak
            paras.last().format.sectionBreak = null
            sorted.forEachIndexed { k, p -> document.blocks[at[0] + k] = byPara.getValue(p) }
            sorted.last().format.sectionBreak = sectionBreak
            spanAnchor = null
            active = locs().indexOfFirst { it.paragraph === sorted[0] }.coerceAtLeast(0)
            selStart = 0; selEnd = 0
        }
        return true
    }

    /** How many columns Convert Text to Table would make of the selected paragraphs with [sep] (0: nothing to convert). */
    fun textToTableColumns(sep: TableSeparator, other: Char? = null): Int = selectedBodyParagraphs()?.let { TableText.columnsFor(it, sep, other) } ?: 0

    /** Convert Text to Table: the selected body paragraphs become a table [columns] wide, split at [sep]; one undo step. */
    fun convertTextToTable(sep: TableSeparator, other: Char?, columns: Int): Boolean {
        val paras = selectedBodyParagraphs() ?: return false
        var done = false
        command {
            val at = paras.map { p -> document.blocks.indexOfFirst { it is ParagraphBlock && it.paragraph === p } }
            if (at.any { it < 0 }) return@command
            val table = TableText.toTable(paras, sep, other, columns)
            // A section break the last paragraph carried stays, in an empty paragraph after the table.
            val sectionBreak = paras.last().format.sectionBreak
            at.sortedDescending().forEach { document.blocks.removeAt(it) }
            val first = at.min()
            document.blocks.add(first, TableBlock(table))
            if (sectionBreak != null) document.blocks.add(first + 1, ParagraphBlock(Paragraph.of("").also { it.format.sectionBreak = sectionBreak }))
            spanAnchor = null
            active = locs().indexOfFirst { it.paragraph === table.rows[0].cells[0].content[0] }.coerceAtLeast(0)
            selStart = 0; selEnd = 0
            done = true; tableInserts++
        }
        return done
    }

    /** Convert Table to Text: the caret's table becomes paragraphs, its cells joined by [sep]; one undo step. */
    fun convertTableToText(sep: TableSeparator, other: Char?): Boolean {
        val ctx = tableContextOf(active) ?: return false
        command {
            val i = document.blocks.indexOf(ctx.block)
            if (i < 0) return@command
            val paras = TableText.toText(ctx.table, sep, other).ifEmpty { listOf(Paragraph.of("")) }
            document.blocks.removeAt(i)
            paras.forEachIndexed { k, p -> document.blocks.add(i + k, ParagraphBlock(p)) }
            spanAnchor = null
            active = locs().indexOfFirst { it.paragraph === paras[0] }.coerceAtLeast(0)
            selStart = 0; selEnd = 0
        }
        return true
    }

    /** The caret's table's columns as Sort names them: its first row's text ("" for an empty cell), by grid column. */
    val activeTableColumns: List<String>
        get() {
            val t = tableContextOf(active)?.table ?: return emptyList()
            val first = t.rows.firstOrNull() ?: return emptyList()
            return first.cells.flatMap { c -> List(c.gridSpan.coerceAtLeast(1)) { k -> if (k == 0) c.content.joinToString(" ") { it.plainText() }.trim() else "" } }
        }

    /** Whether the caret's table can be sorted (no cell spans rows). */
    val activeTableSortable: Boolean get() = tableContextOf(active)?.table?.let { TableText.sortable(it) } == true

    /** Sort: the caret's table's rows by grid column [column]; false when it cannot be sorted. One undo step. */
    fun sortTable(column: Int, kind: SortKind, descending: Boolean, header: Boolean): Boolean {
        val ctx = tableContextOf(active) ?: return false
        if (!TableText.sortable(ctx.table)) return false
        command { TableText.sort(ctx.table, column, kind, descending, header) }
        return true
    }

    /** The table, row and grid column the caret's cell is, or null outside tables. */
    private val caretCell get() = com.therealityteam.aeropages.model.TableFormulas.cellOf(document, locs(), active)

    /** Word's Formula dialog's first guess for the caret's cell: =SUM(ABOVE) under numbers, =SUM(LEFT) beside them, else "=". */
    val suggestedFormula: String
        get() {
            val (t, r, c) = caretCell ?: return "="
            return when {
                com.therealityteam.aeropages.model.TableFormulas.number(t, r - 1, c) != null -> "=SUM(ABOVE)"
                com.therealityteam.aeropages.model.TableFormulas.number(t, r, c - 1) != null -> "=SUM(LEFT)"
                else -> "="
            }
        }

    /** What [formula] would show in the caret's cell with [picture]; null outside a table or when it cannot be worked out. */
    fun formulaPreview(formula: String, picture: String?): String? {
        val (t, r, c) = caretCell ?: return null
        val v = com.therealityteam.aeropages.model.TableFormulas.evaluate(com.therealityteam.aeropages.model.TableFormulas.formulaOf(formula), t, r, c) ?: return null
        return com.therealityteam.aeropages.model.TableFormulas.format(v, picture)
    }

    /** Formula (Word's Table Tools ▸ Data): a `=` field at the caret in a table cell — worked out at once, kept live; one undo step. */
    fun insertTableFormula(formula: String, picture: String?): Boolean {
        if (caretCell == null) return false
        command {
            val code = "=" + com.therealityteam.aeropages.model.TableFormulas.formulaOf(formula) + (picture?.takeIf { it.isNotBlank() }?.let { " \\# \"$it\"" } ?: "")
            val field = com.therealityteam.aeropages.model.DocField(code)
            val p = activeParagraph
            val (s, e) = selRange() ?: (selStart to selStart)
            val fmt = ParagraphOps.formatAt(p, (s - 1).coerceAtLeast(0)).copyOf().also { it.field = field; it.link = null; it.math = null }
            ParagraphOps.replaceKeeping(p, s, e, "0", fmt)
            com.therealityteam.aeropages.model.Fields.update(document, kinds = setOf("="))
            val end = com.therealityteam.aeropages.model.Fields.placed(document).firstOrNull { it.field === field }?.end ?: (s + 1)
            selStart = end; selEnd = end
        }
        return true
    }

    fun deleteTable() = command {
        val ctx = cellContextOf(active) ?: return@command
        removeTableAt(ctx.table)
    }

    private fun removeTableAt(table: Table) {
        val bi = document.blocks.indexOfFirst { it is TableBlock && it.table === table }
        if (bi < 0) return
        document.blocks.removeAt(bi)
        if (document.blocks.none { it is ParagraphBlock }) document.blocks.add(bi.coerceAtMost(document.blocks.size), ParagraphBlock(Paragraph.of("")))
        val near = (document.blocks.getOrNull(bi - 1) as? ParagraphBlock ?: document.blocks.getOrNull(bi) as? ParagraphBlock
            ?: document.blocks.filterIsInstance<ParagraphBlock>().first()).paragraph
        active = locs().indexOfFirst { it.paragraph === near }.coerceAtLeast(0)
        selStart = near.plainText().length; selEnd = selStart
    }

    /** Word's Distribute Columns: every column as wide as the others. */
    fun distributeTableColumns() = command {
        cellContextOf(active)?.table?.columnWidths?.clear()
    }

    /** Whether the caret is in a table (the Table tab shows then). */
    val activeInTable: Boolean get() = cellContextOf(active) != null

    /**
     * A table style from the gallery: the header row's fill and text, banded rows, the borders — set on the
     * caret's table as its cells' own shading and borders and its header row's text, so every format keeps it.
     */
    fun applyTableLook(look: TableLook) = command {
        val t = cellContextOf(active)?.table ?: return@command
        val last = t.rows.lastIndex
        t.rows.forEachIndexed { r, row ->
            val head = r == 0 && look.header != null
            if (r == 0) row.header = look.header != null || row.header
            row.cells.forEach { c ->
                c.shade = when {
                    head -> look.header
                    look.band != null && r > 0 && r % 2 == 1 -> look.band
                    else -> null
                }
                for (p in c.content) {
                    if (p.runs.isEmpty()) p.runs.add(TextRun("", CharacterFormat()))
                    if (r == 0) p.runs.forEach { it.format.bold = look.boldHeader; it.format.color = look.headerText }
                }
            }
        }
        val line = look.line
        t.ownBorders()
        when (look.rules) {
            TableLook.Rules.GRID -> t.rows.forEach { row -> row.cells.forEach { it.borders = com.therealityteam.aeropages.model.CellBorders.all(line) } }
            TableLook.Rules.NONE -> t.rows.forEach { row -> row.cells.forEach { it.borders = com.therealityteam.aeropages.model.CellBorders() } }
            TableLook.Rules.HORIZONTAL -> t.rows.forEachIndexed { r, row -> row.cells.forEach { it.borders = com.therealityteam.aeropages.model.CellBorders(top = line.takeIf { r > 0 }, bottom = line) } }
            TableLook.Rules.THREE_LINE -> {
                val heavy = line.copy(widthPt = 1.5f)
                t.rows.forEachIndexed { r, row -> row.cells.forEach { it.borders = com.therealityteam.aeropages.model.CellBorders(
                    top = if (r == 0) heavy else if (r == 1) line else null,
                    bottom = if (r == 0) line else if (r == last) heavy else null) } }
            }
        }
        t.adoptBorders { r, c -> t.rows[r].cells[c].borders ?: com.therealityteam.aeropages.model.CellBorders() }
    }

    /** Delete the row containing the active cell (or the whole table if last row). */
    fun deleteTableRow() = command {
        val ctx = tableContextOf(active) ?: return@command
        if (ctx.table.rows.size <= 1) {
            document.blocks.remove(ctx.block)
        } else {
            ctx.table.rows.removeAt(ctx.rowIndex)
        }
        active = active.coerceIn(0, locs().lastIndex)
    }

    /** Mark / unmark the active cell's row as a header row: leading header rows repeat when the table runs onto the next page. */
    fun toggleHeaderRow() = command {
        val ctx = tableContextOf(active) ?: return@command
        val row = ctx.table.rows[ctx.rowIndex]
        row.header = !row.header
    }

    /** True when the caret's table row is a header row (ribbon toggle state). */
    val activeRowIsHeader: Boolean get() = tableContextOf(active)?.let { it.table.rows[it.rowIndex].header } == true

    /** Set (or clear) the background shade of the active cell. */
    fun setCellShade(color: Long?) = command {
        cellContextOf(active)?.cell?.shade = color
    }

    /** The line new table borders take (the Borders menu's kind, width and colour). */
    var borderLine by androidx.compose.runtime.mutableStateOf(com.therealityteam.aeropages.model.CellBorder())

    // --- paragraph borders and shading (Home ▸ Paragraph ▸ Borders, Shading) ---------------

    /** Word's Borders menu for paragraphs: a side turned on or off, none, the box with or without the lines between, the lines between. */
    enum class ParaBorderPreset { BOTTOM, TOP, LEFT, RIGHT, NONE, ALL, OUTSIDE, INSIDE }

    /** The borders of the selection's first paragraph (the caret's), which the Borders menu shows and changes. */
    val paragraphBorders: com.therealityteam.aeropages.model.ParagraphBorders?
        get() = selectedParagraphs().firstOrNull()?.format?.borders

    /** The shading of the selection's first paragraph. */
    val paragraphShading: Long? get() = selectedParagraphs().firstOrNull()?.format?.shading

    /**
     * [preset] in [line] on the selected paragraphs, which share the result (one box, Word's way): a side turns off when
     * the first paragraph has it, else on; the box (Outside) and the box with the lines between (All) likewise. One undo step.
     */
    fun applyParagraphBorders(preset: ParaBorderPreset, line: com.therealityteam.aeropages.model.CellBorder = borderLine) {
        val cur = paragraphBorders ?: com.therealityteam.aeropages.model.ParagraphBorders()
        fun flip(side: com.therealityteam.aeropages.model.CellBorder?) = if (side != null) null else line
        val boxed = cur.top != null && cur.bottom != null && cur.left != null && cur.right != null
        val next = when (preset) {
            ParaBorderPreset.BOTTOM -> cur.copy(bottom = flip(cur.bottom))
            ParaBorderPreset.TOP -> cur.copy(top = flip(cur.top))
            ParaBorderPreset.LEFT -> cur.copy(left = flip(cur.left))
            ParaBorderPreset.RIGHT -> cur.copy(right = flip(cur.right))
            ParaBorderPreset.INSIDE -> cur.copy(between = flip(cur.between))
            ParaBorderPreset.NONE -> null
            ParaBorderPreset.OUTSIDE -> if (boxed) cur.copy(top = null, bottom = null, left = null, right = null) else cur.copy(top = line, bottom = line, left = line, right = line)
            ParaBorderPreset.ALL -> if (boxed && cur.between != null) null else cur.copy(top = line, bottom = line, left = line, right = line, between = line)
        }
        setParagraphBorders(next)
    }

    /** [b] on the selected paragraphs (none: off); nothing to do, no step. */
    fun setParagraphBorders(b: com.therealityteam.aeropages.model.ParagraphBorders?) {
        val v = b?.takeIf { !it.none }
        if (selectedParagraphs().all { it.format.borders == v }) return
        formatParagraphs { it.borders = v }
    }

    /** [fill] behind the selected paragraphs (null: none); nothing to do, no step. */
    fun setParagraphShading(fill: Long?) {
        if (selectedParagraphs().all { it.format.shading == fill }) return
        formatParagraphs { it.shading = fill }
    }

    /**
     * Word's horizontal line: an empty paragraph after the caret's with a line under it (what Word's AutoFormat makes of
     * "---"), the caret in it. One undo step.
     */
    fun insertHorizontalLine() = command {
        val loc = locAt(active)
        val para = Paragraph(mutableListOf(TextRun("", activeParagraph.runs.lastOrNull()?.format?.copyOf()?.also { it.link = null; it.field = null } ?: CharacterFormat())))
        para.format.borders = com.therealityteam.aeropages.model.ParagraphBorders(
            bottom = com.therealityteam.aeropages.model.CellBorder(com.therealityteam.aeropages.model.LineStyle.THIN, 0.75f), spaceBottom = 1f)
        when (loc) {
            is TopLoc -> document.blocks.add(loc.blockIndex + 1, ParagraphBlock(para))
            is CellLoc -> loc.cell.content.add(loc.indexInCell + 1, para)
            is BoxLoc -> loc.box.paragraphs.add(loc.indexInBox + 1, para)
        }
        active += 1; selStart = 0; selEnd = 0
    }

    /** Word's Borders menu: sides of the caret's cell (and its neighbours' facing ones), or the whole table's. */
    enum class TableBorderPreset { BOTTOM, TOP, LEFT, RIGHT, CELL_NONE, ALL, OUTSIDE, INSIDE, NONE }

    /**
     * Draws [preset]'s sides in [line] — the caret cell's, its neighbours' facing sides with it (an edge two
     * cells share is one line), or the whole table's — the table's cells then holding their own borders unless
     * they all come out one line again (or none).
     */
    fun applyTableBorders(preset: TableBorderPreset, line: com.therealityteam.aeropages.model.CellBorder = borderLine) = command {
        val ctx = cellContextOf(active) ?: return@command
        val t = ctx.table
        t.ownBorders()
        val cols = t.rows.maxOfOrNull { r -> r.cells.sumOf { it.gridSpan.coerceAtLeast(1) } } ?: 0
        fun cell(r: Int, c: Int) = t.rows[r].cells[c]
        fun edit(r: Int, c: Int, change: (com.therealityteam.aeropages.model.CellBorders) -> com.therealityteam.aeropages.model.CellBorders) {
            val x = cell(r, c); x.borders = change(x.borders ?: com.therealityteam.aeropages.model.CellBorders())
        }
        val ri = ctx.rowIndex; val ci = ctx.colIndex
        val col = t.gridColOf(ri, ci)
        val below = ri + ctx.cell.rowSpan.coerceAtLeast(1)
        fun bottom(l: com.therealityteam.aeropages.model.CellBorder?) { edit(ri, ci) { it.copy(bottom = l) }; t.cellAtGrid(below, col)?.let { edit(below, it) { b -> b.copy(top = l) } } }
        fun top(l: com.therealityteam.aeropages.model.CellBorder?) { edit(ri, ci) { it.copy(top = l) }; if (ri > 0) t.cellAtGrid(ri - 1, col)?.let { edit(ri - 1, it) { b -> b.copy(bottom = l) } } }
        fun left(l: com.therealityteam.aeropages.model.CellBorder?) { edit(ri, ci) { it.copy(left = l) }; if (ci > 0) edit(ri, ci - 1) { it.copy(right = l) } }
        fun right(l: com.therealityteam.aeropages.model.CellBorder?) { edit(ri, ci) { it.copy(right = l) }; if (ci + 1 < t.rows[ri].cells.size) edit(ri, ci + 1) { it.copy(left = l) } }
        when (preset) {
            TableBorderPreset.BOTTOM -> bottom(line)
            TableBorderPreset.TOP -> top(line)
            TableBorderPreset.LEFT -> left(line)
            TableBorderPreset.RIGHT -> right(line)
            TableBorderPreset.CELL_NONE -> { bottom(null); top(null); left(null); right(null) }
            TableBorderPreset.ALL, TableBorderPreset.NONE -> {
                val l = line.takeIf { preset == TableBorderPreset.ALL }
                t.rows.forEach { r -> r.cells.forEach { it.borders = com.therealityteam.aeropages.model.CellBorders.all(l) } }
            }
            TableBorderPreset.OUTSIDE, TableBorderPreset.INSIDE -> {
                val outside = preset == TableBorderPreset.OUTSIDE
                t.rows.forEachIndexed { r, row ->
                    var g = 0
                    row.cells.forEachIndexed { c, x ->
                        val span = x.gridSpan.coerceAtLeast(1)
                        val firstRow = r == 0; val lastRow = r + x.rowSpan.coerceAtLeast(1) - 1 >= t.rows.size - 1
                        val firstCol = g == 0; val lastCol = g + span >= cols
                        edit(r, c) { b ->
                            fun pick(edge: Boolean, current: com.therealityteam.aeropages.model.CellBorder?) = if (edge == outside) line else current
                            b.copy(top = pick(firstRow, b.top), bottom = pick(lastRow, b.bottom), left = pick(firstCol, b.left), right = pick(lastCol, b.right))
                        }
                        g += span
                    }
                }
            }
        }
        t.adoptBorders { r, c -> cell(r, c).borders ?: com.therealityteam.aeropages.model.CellBorders() }
    }

    /** Shade the header (first) row of the active table. */
    fun shadeHeaderRow(color: Long) = command {
        tableContextOf(active)?.table?.rows?.firstOrNull()?.cells?.forEach { it.shade = color }
    }

    /** Widen/narrow the active cell's column (toolbar buttons). */
    fun adjustActiveColumnWidth(deltaWeight: Float) = command {
        val ctx = cellContextOf(active) ?: return@command
        resizeColumnInternal(ctx.table, ctx.colIndex, deltaWeight)
    }

    /** Live column resize from a drag handle (the whole drag coalesces into one undo step). */
    fun resizeColumn(table: Table, col: Int, deltaWeight: Float) {
        beginSession("colResize@${System.identityHashCode(table)}:$col")
        resizeColumnInternal(table, col, deltaWeight)
        bump()
    }

    private fun resizeColumnInternal(table: Table, col: Int, deltaWeight: Float) {
        while (table.columnWidths.size < table.columnCount) table.columnWidths.add(1f)
        if (col in table.columnWidths.indices) {
            table.columnWidths[col] = (table.columnWidths[col] + deltaWeight).coerceIn(0.3f, 5f)
        }
    }

    /** Merge the active cell with the next cell in its row (horizontal merge). */
    fun mergeCellRight() = command {
        val ctx = cellContextOf(active) ?: return@command
        val row = ctx.table.rows[ctx.rowIndex]
        val next = row.cells.getOrNull(ctx.colIndex + 1) ?: return@command
        ctx.cell.gridSpan += next.gridSpan
        // Preserve the merged cell's content (drop empty placeholder paragraphs).
        next.content.filter { it.plainText().isNotEmpty() }.forEach { ctx.cell.content.add(it) }
        row.cells.removeAt(ctx.colIndex + 1)
        active = active.coerceIn(0, locs().lastIndex)
    }

    /** Vertically merge the active cell with the cell directly below (OOXML vMerge). */
    fun mergeCellDown() = command {
        val ctx = cellContextOf(active) ?: return@command
        val span = ctx.cell.rowSpan.coerceAtLeast(1)
        val belowRow = ctx.table.rows.getOrNull(ctx.rowIndex + span) ?: return@command
        val below = belowRow.cells.getOrNull(ctx.colIndex) ?: return@command
        if (below.vMerged) return@command
        // Keep the swallowed cell in place (layout grid stays rectangular) but mark it
        // covered; its text moves up into the anchor.
        below.content.filter { it.plainText().isNotEmpty() }.forEach { ctx.cell.content.add(it) }
        below.content.clear(); below.content.add(Paragraph.of(""))
        below.vMerged = true
        ctx.cell.rowSpan = span + below.rowSpan.coerceAtLeast(1)
        active = active.coerceIn(0, locs().lastIndex)
    }

    /** Undo a vertical merge on the active cell. */
    fun splitCellVertical() = command {
        val ctx = cellContextOf(active) ?: return@command
        if (ctx.cell.rowSpan <= 1) return@command
        for (r2 in ctx.rowIndex + 1 until (ctx.rowIndex + ctx.cell.rowSpan).coerceAtMost(ctx.table.rows.size)) {
            ctx.table.rows[r2].cells.getOrNull(ctx.colIndex)?.vMerged = false
        }
        ctx.cell.rowSpan = 1
    }

    /** Split a merged active cell back into single columns. */
    fun splitCell() = command {
        val ctx = cellContextOf(active) ?: return@command
        if (ctx.cell.gridSpan > 1) {
            val extra = ctx.cell.gridSpan - 1
            ctx.cell.gridSpan = 1
            val row = ctx.table.rows[ctx.rowIndex]
            repeat(extra) { row.cells.add(ctx.colIndex + 1, com.therealityteam.aeropages.model.TableCell(borders = ctx.cell.borders)) }
        }
    }

    /** Word's Split Cells on a merged cell: back to the single cells it covers, across and down. */
    fun splitMergedCell() = command {
        val ctx = cellContextOf(active) ?: return@command
        if (ctx.cell.gridSpan > 1) {
            val extra = ctx.cell.gridSpan - 1
            ctx.cell.gridSpan = 1
            repeat(extra) { ctx.table.rows[ctx.rowIndex].cells.add(ctx.colIndex + 1, com.therealityteam.aeropages.model.TableCell(borders = ctx.cell.borders, shade = ctx.cell.shade)) }
        }
        if (ctx.cell.rowSpan > 1) {
            for (r2 in ctx.rowIndex + 1 until (ctx.rowIndex + ctx.cell.rowSpan).coerceAtMost(ctx.table.rows.size)) ctx.table.rows[r2].cells.getOrNull(ctx.colIndex)?.vMerged = false
            ctx.cell.rowSpan = 1
        }
    }

    private class CellCtx(val table: Table, val rowIndex: Int, val colIndex: Int, val cell: com.therealityteam.aeropages.model.TableCell)

    private fun cellContextOf(index: Int): CellCtx? {
        val para = locAt(index).paragraph
        document.blocks.forEach { block ->
            if (block is TableBlock) {
                block.table.rows.forEachIndexed { ri, row ->
                    row.cells.forEachIndexed { ci, cell ->
                        if (cell.content.any { it === para }) return CellCtx(block.table, ri, ci, cell)
                    }
                }
            }
        }
        return null
    }

    private class TableCtx(val block: TableBlock, val table: Table, val rowIndex: Int)

    private fun tableContextOf(index: Int): TableCtx? {
        val para = locAt(index).paragraph
        document.blocks.forEach { block ->
            if (block is TableBlock) {
                block.table.rows.forEachIndexed { ri, row ->
                    if (row.cells.any { cell -> cell.content.any { it === para } }) {
                        return TableCtx(block, block.table, ri)
                    }
                }
            }
        }
        return null
    }

    /** Top-level block index that owns the paragraph at [index] (its own block, or its table). */
    /** The location index of the caret's top-level paragraph (a table's or box's caret: the paragraph before its block, else its own). */
    private fun topParagraphIndex(): Int {
        val bi = topBlockIndexOf(active)
        val block = document.blocks.getOrNull(bi) as? ParagraphBlock ?: return active
        return locs().indexOfFirst { it.paragraph === block.paragraph }.coerceAtLeast(0)
    }

    private fun topBlockIndexOf(index: Int): Int {
        val para = locAt(index).paragraph
        document.blocks.forEachIndexed { bi, block ->
            when (block) {
                is ParagraphBlock -> if (block.paragraph === para) return bi
                is TableBlock -> if (block.table.rows.any { r -> r.cells.any { c -> c.content.any { it === para } } }) return bi
                is com.therealityteam.aeropages.model.TextBoxBlock -> if (block.paragraphs.any { it === para }) return bi
                else -> { /* image / toc / bibliography are not paragraph containers */ }
            }
        }
        return document.blocks.lastIndex.coerceAtLeast(0)
    }

    // --- character formatting (selection-aware) --------------------------------

    private inline fun applyChar(crossinline change: (CharacterFormat) -> Unit) {
        val sp = span()
        if (sp != null) {
            command { forSpan(sp) { p, a, b -> if (b > a) ParagraphOps.applyFormat(p, a, b) { reformat(it, change) } } }
            return
        }
        val range = selRange()
        if (range == null) {
            // Collapsed caret: arm a pending format for the next typed text instead of
            // reformatting the whole paragraph (Word behavior). No document change yet.
            val base = (pendingFormat ?: ParagraphOps.formatAt(activeParagraph, selStart)).copy()
            change(base)
            pendingFormat = base
            pendingPos = active to selStart
            bump()
            return
        }
        command {
            // With 修订 on, any formatting is itself a tracked revision → mark it (shown red).
            ParagraphOps.applyFormat(activeParagraph, range.first, range.second) { reformat(it, change) }
        }
    }

    /** When 修订 is on, flag the selected paragraphs' runs as a format revision (shown red). */
    private fun markParaRevised() {
        if (trackChanges) selectedParagraphs().forEach { p -> p.runs.forEach { stampFormat(it.format) } }
    }

    // Word's toggles: when all the selected text already has it, it comes off; otherwise it goes on.
    fun toggleBold() { val on = !selectionAll { it.bold }; applyChar { it.bold = on } }
    fun toggleItalic() { val on = !selectionAll { it.italic }; applyChar { it.italic = on } }
    fun toggleUnderline() { val on = !selectionAll { it.underline }; applyChar { it.underline = on } }
    fun toggleStrike() { val on = !selectionAll { it.strikethrough }; applyChar { it.strikethrough = on } }
    fun toggleSuperscript() { val on = !selectionAll { it.superscript }; applyChar { it.superscript = on; if (on) it.subscript = false } }
    fun toggleSubscript() { val on = !selectionAll { it.subscript }; applyChar { it.subscript = on; if (on) it.superscript = false } }
    /** Change case of the selection (or whole paragraph), like Word's Aa menu. */
    fun changeCase(mode: CaseMode) = command {
        val convert = { str: String ->
            when (mode) {
                CaseMode.UPPER -> str.uppercase()
                CaseMode.LOWER -> str.lowercase()
                CaseMode.TITLE -> titleCase(str)
                CaseMode.TOGGLE -> str.map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.joinToString("")
            }
        }
        val sp = span()
        if (sp != null) forSpan(sp) { p, a, b -> if (b > a) ParagraphOps.mapText(p, a, b, convert) }
        else {
            val p = activeParagraph
            val (s, e) = selRange() ?: (0 to ParagraphOps.length(p))
            ParagraphOps.mapText(p, s, e, convert)
        }
    }

    /**
     * Review ▸ Chinese Conversion (Word's 繁转简 / 简转繁): the selection — or, with none, the whole document, its headers
     * and footers too — in Traditional ([toTraditional]) or Simplified characters, with [vocabulary] the words the
     * mainland and Taiwan say differently; each run keeps its look; one undo step. How many characters changed; -1
     * when the device cannot convert.
     */
    fun convertChinese(toTraditional: Boolean, vocabulary: Boolean = true): Int {
        if (!com.therealityteam.aeropages.model.ChineseConversion.available) return -1
        val convert = { s: String -> com.therealityteam.aeropages.model.ChineseConversion.convert(s, toTraditional, vocabulary) }
        // What to convert: the selection's parts of its paragraphs, or every paragraph — nothing done when none would change.
        val targets = ArrayList<Triple<Paragraph, Int, Int>>()
        val sp = span()
        val sel = selRange()
        when {
            sp != null -> forSpan(sp) { p, a, b -> if (b > a) targets.add(Triple(p, a, b)) }
            sel != null -> targets.add(Triple(activeParagraph, sel.first, sel.second))
            else -> (document.paragraphs() + listOfNotNull(document.header, document.footer, document.evenHeader, document.evenFooter, document.firstHeader, document.firstFooter))
                .forEach { targets.add(Triple(it, 0, ParagraphOps.length(it))) }
        }
        if (targets.none { (p, a, b) -> p.plainText().substring(a, b).let { convert(it) != it } }) return 0
        var changed = 0
        command {
            targets.forEach { (p, a, b) -> changed += ParagraphOps.mapRuns(p, a, b, convert) }
            // A word longer or shorter than the one it stands for moves what follows it.
            val len = ParagraphOps.length(activeParagraph)
            selStart = selStart.coerceIn(0, len); selEnd = selEnd.coerceIn(0, len)
        }
        return changed
    }

    private fun titleCase(s: String): String = buildString {
        var prevBoundary = true
        for (c in s) {
            append(if (prevBoundary && c.isLetter()) c.uppercaseChar() else c.lowercaseChar())
            prevBoundary = !c.isLetterOrDigit()
        }
    }

    fun setFontSize(pt: Float) = applyChar { it.fontSizePt = pt }
    fun setFontFamily(name: String) = applyChar { it.fontFamily = name }
    fun setColor(color: Long?) = applyChar { it.color = color }
    fun setHighlight(color: Long?) = applyChar { it.highlight = color }
    fun toggleSmallCaps() { val on = !selectionAll { it.smallCaps }; applyChar { it.smallCaps = on } }
    fun setLetterSpacing(pt: Float?) = applyChar { it.letterSpacingPt = pt }

    /**
     * Word's Font dialog: [change] made to the selection's characters — or, at a bare caret, to what is typed next — as
     * one undo step (the dialog passes only what was changed in it).
     */
    fun formatCharacters(change: (CharacterFormat) -> Unit) = applyChar { change(it) }

    /** Word's Paragraph dialog: [change] made to each selected paragraph's format, one undo step. */
    fun formatParagraphs(change: (ParagraphFormat) -> Unit) = command { selectedParagraphs().forEach { change(it.format) }; markParaRevised() }

    /**
     * Links the selection to [url] — an address, or `#name` for a bookmark in this document — or, with
     * nothing selected, puts [display] (the address itself by default) at the caret, linked. One undo step.
     */
    fun applyLink(url: String, display: String? = null) {
        if (url.isBlank()) { applyChar { it.link = null }; return }
        command { linkSelection(url, display ?: url) }
    }

    private fun linkSelection(target: String, display: String) {
        val sp = span()
        if (sp != null) {
            forSpan(sp) { p, a, b -> if (b > a) ParagraphOps.applyFormat(p, a, b) { reformat(it) { f -> f.link = target } } }
            return
        }
        val r = selRange()
        if (r == null) {
            val p = activeParagraph
            val fmt = ParagraphOps.formatAt(p, (selStart - 1).coerceAtLeast(0))
            fmt.link = target
            ParagraphOps.replace(p, selStart, selStart, display, fmt)
            val caret = selStart + display.length
            selStart = caret; selEnd = caret
        } else ParagraphOps.applyFormat(activeParagraph, r.first, r.second) { reformat(it) { f -> f.link = target } }
    }

    /** Takes the link off the selection — or, with only a caret, off the whole link it is at. */
    fun removeLink() {
        if (!hasSpan && selRange() == null && linkAtCaret() != null) removeLinkAtCaret() else applyChar { it.link = null }
    }

    // --- links within the document and bookmarks ---------------------------------------------

    /**
     * The link the caret stands at — the character after it, else the one before — or the selection
     * (within one paragraph) lies in: its target and the characters of the caret's paragraph it covers.
     */
    fun linkAtCaret(): Pair<String, IntRange>? {
        if (hasSpan) return null
        val p = activeParagraph
        val len = ParagraphOps.length(p)
        if (len == 0) return null
        val links = arrayOfNulls<String>(len)
        var pos = 0
        for (r in p.runs) { for (k in r.text.indices) links[pos + k] = r.format.link; pos += r.text.length }
        val a = minOf(selStart, selEnd).coerceIn(0, len)
        val b = maxOf(selStart, selEnd).coerceIn(0, len)
        val at = when {
            a < b -> a.takeIf { links[a] != null && (a until b).all { links[it] == links[a] } }
            a < len && links[a] != null -> a
            a > 0 && links[a - 1] != null -> a - 1
            else -> null
        } ?: return null
        val link = links[at] ?: return null
        var s = at
        while (s > 0 && links[s - 1] == link) s--
        var e = at + 1
        while (e < len && links[e] == link) e++
        return link to (s until e)
    }

    /** The link at the caret ([linkAtCaret]) now goes to [target] — the whole link, not just the caret's run. */
    fun setLinkAtCaret(target: String) {
        val (_, range) = linkAtCaret() ?: return
        if (target.isBlank()) return removeLinkAtCaret()
        command { ParagraphOps.applyFormat(activeParagraph, range.first, range.last + 1) { it.link = target } }
    }

    /** The link at the caret is plain text again (its text stays). */
    fun removeLinkAtCaret() {
        val (_, range) = linkAtCaret() ?: return
        command { ParagraphOps.applyFormat(activeParagraph, range.first, range.last + 1) { it.link = null } }
    }

    /** The document's bookmarks in text order — Word's hidden ones (`_Toc…`) only when [hidden]. */
    fun bookmarks(hidden: Boolean = false): List<com.therealityteam.aeropages.model.Bookmarks.Found> =
        com.therealityteam.aeropages.model.Bookmarks.of(document).filter { hidden || !it.hidden }

    private fun removeMarks(name: String) = locs().forEach { loc -> loc.paragraph.bookmarks.removeAll { it.name == name } }

    /**
     * Marks the selection (a point at the caret) as bookmark [name] — one undo step; a bookmark of that
     * name moves here, as in Word. False when [name] is not one Word takes (a letter first, then letters,
     * digits and underscores, 40 at most).
     */
    fun addBookmark(name: String): Boolean {
        if (!com.therealityteam.aeropages.model.Bookmarks.isValidName(name)) return false
        command {
            removeMarks(name)
            val (a, b) = selectionEnds()
            val all = locs()
            com.therealityteam.aeropages.model.Bookmarks.place(all[a.index].paragraph, com.therealityteam.aeropages.model.BookmarkMark(name, a.offset, false))
            com.therealityteam.aeropages.model.Bookmarks.place(all[b.index].paragraph, com.therealityteam.aeropages.model.BookmarkMark(name, b.offset, true))
        }
        return true
    }

    /** Bookmark [name] goes (its text stays; links to it lead nowhere). */
    fun deleteBookmark(name: String) = command { removeMarks(name) }

    /** Selects bookmark [name]'s text (puts the caret at it, for a point) and brings it into view; false when there is none. */
    fun goToBookmark(name: String): Boolean {
        val b = com.therealityteam.aeropages.model.Bookmarks.of(document).firstOrNull { it.name == name } ?: return false
        if (b.startPara == b.endPara) {
            focusParagraph(b.startPara)
            setSelection(b.startPara, b.start, b.end)
        } else {
            selectRange(DocPos(b.startPara, b.start), DocPos(b.endPara, b.end))
            revealRequest++
        }
        return true
    }

    /**
     * Follows a link within the document: to bookmark `#name`, or to the heading a LibreOffice link
     * names (`#1.2.Heading|outline`). False for a link elsewhere, or to a place that is gone.
     */
    fun followLink(link: String): Boolean {
        val name = com.therealityteam.aeropages.model.Bookmarks.targetOf(link) ?: return false
        if (goToBookmark(name)) return true
        val heading = com.therealityteam.aeropages.model.Bookmarks.outlineTarget(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val i = locs().indexOfFirst { loc ->
            val p = loc.paragraph
            p.format.style in LINKABLE_HEADINGS && p.plainText().trim().let { t -> t.isNotEmpty() && (t == heading || heading.endsWith(t)) }
        }
        if (i < 0) return false
        focusParagraph(i)
        setSelection(i, 0, 0)
        return true
    }

    /**
     * Links the selection (or puts the heading's text, linked, at the caret) to [heading] — through the
     * bookmark round it: one it has (a `_Toc` one Word made), else a new hidden one. One undo step.
     */
    fun linkToHeading(heading: Paragraph) {
        val own = heading.bookmarks.firstOrNull { !it.end && it.offset == 0 }?.name
        command {
            val name = own ?: com.therealityteam.aeropages.model.Bookmarks.headingName(heading.plainText(), com.therealityteam.aeropages.model.Bookmarks.of(document).mapTo(HashSet()) { it.name }).also { n ->
                com.therealityteam.aeropages.model.Bookmarks.place(heading, com.therealityteam.aeropages.model.BookmarkMark(n, 0, false))
                com.therealityteam.aeropages.model.Bookmarks.place(heading, com.therealityteam.aeropages.model.BookmarkMark(n, ParagraphOps.length(heading), true))
            }
            linkSelection("#$name", heading.plainText().trim().ifEmpty { name })
        }
    }
    fun clearFormatting() = applyChar {
        it.bold = false; it.italic = false; it.underline = false; it.strikethrough = false
        it.superscript = false; it.subscript = false
        it.color = null; it.highlight = null; it.fontSizePt = null; it.fontFamily = null
    }

    // --- format painter --------------------------------------------------------

    var painterArmed by mutableStateOf(false)
        private set
    private var painterFormat: CharacterFormat? = null

    /**
     * Format-painter toggle: first press captures the caret format and arms;
     * second press applies it to the current selection and disarms.
     */
    fun formatPainter() {
        if (!painterArmed) {
            painterFormat = currentFormat.copyOf()
            painterArmed = true
            bump()
        } else {
            val src = painterFormat
            painterArmed = false
            painterFormat = null
            if (src != null) applyChar { copyFormatInto(src, it) }
            bump()
        }
    }

    private fun copyFormatInto(src: CharacterFormat, dst: CharacterFormat) {
        dst.bold = src.bold; dst.italic = src.italic; dst.underline = src.underline
        dst.strikethrough = src.strikethrough; dst.superscript = src.superscript; dst.subscript = src.subscript
        dst.fontSizePt = src.fontSizePt; dst.fontFamily = src.fontFamily
        dst.color = src.color; dst.highlight = src.highlight
    }

    // --- paragraph formatting --------------------------------------------------

    fun setAlign(align: TextAlign) = command { selectedParagraphs().forEach { it.format.align = align }; markParaRevised() }

    fun setStyle(style: ParagraphStyle) = command {
        for (p in selectedParagraphs()) {
            val f = p.format
            f.style = style; f.rawStyleId = document.styles.idOf(style)
            applyResolved(p, document.styles.resolve(f.rawStyleId))
        }
    }

    /**
     * Word's Styles pane ▸ Clear All: the selected paragraphs go back to the Normal style with their direct character
     * formatting gone (bold, colour, size, font… — links and fields stay). One undo step.
     */
    fun clearAll() = command {
        val normal = document.styles.idOf(ParagraphStyle.NORMAL)
        for (p in selectedParagraphs()) {
            ParagraphOps.applyFormat(p, 0, ParagraphOps.length(p)) { f ->
                reformat(f) {
                    it.bold = false; it.italic = false; it.underline = false; it.strikethrough = false
                    it.superscript = false; it.subscript = false; it.smallCaps = false; it.letterSpacingPt = null
                    it.color = null; it.highlight = null; it.fontSizePt = null; it.fontFamily = null
                }
            }
            p.format.style = ParagraphStyle.NORMAL; p.format.rawStyleId = normal
            applyResolved(p, document.styles.resolve(normal))
        }
    }

    /** How many paragraphs (body, tables, text boxes) carry [style]. */
    fun styleUses(style: ParagraphStyle): Int = allParagraphs().count { it.format.style == style }

    // --- outline (导航窗格) --------------------------------------------------------

    private fun headingLevel(style: ParagraphStyle): Int? = when (style) {
        ParagraphStyle.HEADING1 -> 1; ParagraphStyle.HEADING2 -> 2; ParagraphStyle.HEADING3 -> 3; else -> null
    }
    private fun headingStyle(level: Int): ParagraphStyle = when (level) { 1 -> ParagraphStyle.HEADING1; 2 -> ParagraphStyle.HEADING2; else -> ParagraphStyle.HEADING3 }
    private fun levelOfBlock(bi: Int): Int? = (document.blocks.getOrNull(bi) as? ParagraphBlock)?.let { headingLevel(it.paragraph.format.style) }

    /** Promote the heading at location [index] one level (Heading 2 → Heading 1); Heading 1 stays. */
    fun promoteHeading(index: Int) = shiftHeading(index, -1)
    /** Demote the heading at location [index] one level (Heading 1 → Heading 2); Heading 3 stays. */
    fun demoteHeading(index: Int) = shiftHeading(index, +1)
    private fun shiftHeading(index: Int, delta: Int) {
        val para = locAt(index).paragraph
        val level = headingLevel(para.format.style) ?: return
        val next = (level + delta).coerceIn(1, 3)
        if (next == level) return
        command {
            val f = para.format
            f.style = headingStyle(next); f.rawStyleId = document.styles.idOf(f.style)
            applyResolved(para, document.styles.resolve(f.rawStyleId))
        }
    }

    /** Blocks of the section a top-level heading block starts: itself through the block before the next heading of its level or higher. */
    private fun sectionEnd(bi: Int, level: Int): Int {
        var end = bi + 1
        while (end < document.blocks.size) { val l = levelOfBlock(end); if (l != null && l <= level) break; end++ }
        return end
    }

    /** Move the section of the heading at location [index] above the previous sibling section (Word's 上移); false when it cannot move. */
    fun moveSectionUp(index: Int): Boolean {
        val bi = topBlockIndexOf(index)
        val level = levelOfBlock(bi) ?: return false
        if ((locAt(index) as? TopLoc)?.blockIndex != bi) return false
        var prev = bi - 1
        while (prev >= 0) { val l = levelOfBlock(prev); if (l != null && l <= level) break; prev-- }
        if (prev < 0 || levelOfBlock(prev) != level) return false      // no sibling above (a parent or nothing)
        val end = sectionEnd(bi, level)
        val para = (document.blocks[bi] as ParagraphBlock).paragraph
        command {
            val moved = ArrayList(document.blocks.subList(bi, end))
            repeat(end - bi) { document.blocks.removeAt(bi) }
            document.blocks.addAll(prev, moved)
        }
        refocus(para)
        return true
    }

    /** Move the section of the heading at location [index] below the next sibling section (Word's 下移); false when it cannot move. */
    fun moveSectionDown(index: Int): Boolean {
        val bi = topBlockIndexOf(index)
        val level = levelOfBlock(bi) ?: return false
        if ((locAt(index) as? TopLoc)?.blockIndex != bi) return false
        val end = sectionEnd(bi, level)
        if (end >= document.blocks.size || levelOfBlock(end) != level) return false   // no sibling below
        val end2 = sectionEnd(end, level)
        val para = (document.blocks[bi] as ParagraphBlock).paragraph
        command {
            val moved = ArrayList(document.blocks.subList(bi, end))
            repeat(end - bi) { document.blocks.removeAt(bi) }
            document.blocks.addAll(bi + (end2 - end), moved)
        }
        refocus(para)
        return true
    }

    private fun refocus(para: Paragraph) {
        val idx = document.locations().indexOfFirst { it.paragraph === para }
        if (idx >= 0) focusParagraph(idx)
    }

    // --- styles (样式) ---------------------------------------------------------

    /** The document's definition of a gallery style (the built-in default when the file lacks it). */
    fun styleDef(style: ParagraphStyle): StyleDef = document.styles.defOf(style)
    /** [style] with every inherited property resolved: what the gallery previews and [setStyle] applies. */
    fun resolvedStyle(style: ParagraphStyle): ResolvedStyle = document.styles.resolve(style)
    /** The style id a paragraph carries in this document (foreign ids kept). */
    private fun styleIdOf(p: Paragraph): String = p.format.rawStyleId ?: document.styles.idOf(p.format.style)

    /** Apply a resolved style to a paragraph: size/bold/italic follow the style; font and colour only when it defines them. */
    private fun applyResolved(p: Paragraph, r: ResolvedStyle) {
        ParagraphOps.applyFormat(p, 0, ParagraphOps.length(p)) { f ->
            reformat(f) {
                it.fontSizePt = r.sizePt; it.bold = r.bold; it.italic = r.italic
                r.fontFamily?.let { ff -> it.fontFamily = ff }
                r.color?.let { c -> it.color = c }
            }
        }
        r.align?.let { p.format.align = it }
        r.spaceBeforePt?.let { p.format.spaceBeforePt = it }
        r.spaceAfterPt?.let { p.format.spaceAfterPt = it }
        if (r.lineSpacing != null || r.lineHeightPt != null) {
            p.format.lineSpacing = r.lineSpacing ?: 1f; p.format.lineHeightPt = r.lineHeightPt; p.format.lineExact = r.lineExact
        }
        // A list keeps its own indent (its level's); everything else takes the style's indents.
        if (p.format.listKind == ListKind.NONE) {
            p.format.indentLeftPt = r.indentLeftPt ?: 0f
            p.format.firstLineIndentPt = r.firstLineIndentPt ?: 0f
        }
        p.format.indentRightPt = r.indentRightPt ?: 0f
        p.format.borders = r.borders
        p.format.shading = r.shading
    }

    /**
     * Change a style definition and re-apply it (Word's 修改样式). Every paragraph whose style — or
     * a style based on it — resolves differently afterwards is re-baked: run and paragraph traits
     * still equal to the old definition take the new values; direct formatting that differed is kept.
     */
    fun modifyStyle(style: ParagraphStyle, edit: (StyleDef) -> Unit) = modifyStyleId(document.styles.idOf(style), edit)

    fun modifyStyleId(id: String, edit: (StyleDef) -> Unit) = command {
        val sheet = document.styles
        val def = sheet[id] ?: run {
            val builtin = ParagraphStyle.entries.firstOrNull { it.styleId.equals(id, ignoreCase = true) }?.let { StyleSheet.builtin(it) }
            (builtin ?: StyleDef(id, basedOn = sheet.idOf(ParagraphStyle.NORMAL).takeIf { !it.equals(id, ignoreCase = true) })).also { sheet.put(it) }
        }
        val paras = allParagraphs()
        val before = paras.map { styleIdOf(it) }.toSet().associateWith { sheet.resolve(it) }
        edit(def)
        for (p in paras) {
            val pid = styleIdOf(p)
            val old = before[pid] ?: continue
            val new = sheet.resolve(pid)
            if (!new.sameAs(old)) rebake(p, old, new)
        }
    }

    private fun rebake(p: Paragraph, old: ResolvedStyle, new: ResolvedStyle) {
        p.runs.forEach { r ->
            reformat(r.format) { f ->
                if (f.fontSizePt == null || f.fontSizePt == old.sizePt) f.fontSizePt = new.sizePt
                if (f.bold == old.bold) f.bold = new.bold
                if (f.italic == old.italic) f.italic = new.italic
                if (f.fontFamily == old.fontFamily) f.fontFamily = new.fontFamily
                if (f.color == old.color) f.color = new.color
            }
        }
        val pf = p.format
        new.align?.let { if (old.align == null || pf.align == old.align) pf.align = it }
        new.spaceBeforePt?.let { if (old.spaceBeforePt == null || pf.spaceBeforePt == old.spaceBeforePt) pf.spaceBeforePt = it }
        new.spaceAfterPt?.let { if (old.spaceAfterPt == null || pf.spaceAfterPt == old.spaceAfterPt) pf.spaceAfterPt = it }
        new.lineSpacing?.let { if (old.lineSpacing == null || pf.lineSpacing == old.lineSpacing) pf.lineSpacing = it }
        // An exact or least line height the paragraph has from the old definition follows the new one (gone when it is).
        if ((new.lineHeightPt != old.lineHeightPt || new.lineExact != old.lineExact) && pf.lineHeightPt == old.lineHeightPt) {
            pf.lineHeightPt = new.lineHeightPt; pf.lineExact = new.lineExact
        }
        // Indents the paragraph still has from the old definition follow the new one.
        if (pf.indentLeftPt == (old.indentLeftPt ?: 0f)) pf.indentLeftPt = new.indentLeftPt ?: 0f
        if (pf.firstLineIndentPt == (old.firstLineIndentPt ?: 0f)) pf.firstLineIndentPt = new.firstLineIndentPt ?: 0f
        if (pf.indentRightPt == (old.indentRightPt ?: 0f)) pf.indentRightPt = new.indentRightPt ?: 0f
    }

    /**
     * Make the active paragraph's formatting the definition of [style] — its own style by default (Word's 更新样式以匹配所选内容,
     * also on every style of the Styles pane).
     */
    fun updateStyleFromSelection(style: ParagraphStyle? = null) = updateStyleIdFromSelection(style?.let { document.styles.idOf(it) })

    /** [updateStyleFromSelection] for a style by its id — a document's own (null: the caret's paragraph's style). */
    fun updateStyleIdFromSelection(id: String?) {
        val p = activeParagraph
        val run = (p.runs.firstOrNull { it.text.isNotBlank() } ?: p.runs.firstOrNull())?.format ?: return
        modifyStyleId(id ?: styleIdOf(p)) { d ->
            run.fontSizePt?.let { d.sizePt = it }
            d.bold = run.bold; d.italic = run.italic; d.fontFamily = run.fontFamily; d.color = run.color
            d.align = p.format.align; d.spaceBeforePt = p.format.spaceBeforePt; d.spaceAfterPt = p.format.spaceAfterPt; d.lineSpacing = p.format.lineSpacing
            d.lineHeightPt = p.format.lineHeightPt; d.lineExact = p.format.lineExact
            if (p.format.listKind == ListKind.NONE) {
                d.indentLeftPt = p.format.indentLeftPt.takeIf { it != 0f || d.indentLeftPt != null }
                d.firstLineIndentPt = p.format.firstLineIndentPt.takeIf { it != 0f || d.firstLineIndentPt != null }
            }
            d.indentRightPt = p.format.indentRightPt.takeIf { it != 0f || d.indentRightPt != null }
            d.borders = p.format.borders; d.shading = p.format.shading
        }
    }

    /** Restore a built-in style's default definition. */
    fun resetStyle(style: ParagraphStyle) = modifyStyle(style) { d ->
        val b = StyleSheet.builtin(style)
        d.fontFamily = b.fontFamily; d.sizePt = b.sizePt; d.bold = b.bold; d.italic = b.italic; d.color = b.color
        d.align = b.align; d.spaceBeforePt = b.spaceBeforePt; d.spaceAfterPt = b.spaceAfterPt; d.lineSpacing = b.lineSpacing
        d.lineHeightPt = b.lineHeightPt; d.lineExact = b.lineExact
        d.indentLeftPt = b.indentLeftPt; d.firstLineIndentPt = b.firstLineIndentPt; d.indentRightPt = b.indentRightPt
        d.borders = b.borders; d.shading = b.shading
    }

    // --- the document's own styles (Word's New Style, a file's styles) -----------------------------

    /** Whether [d] is one of the six built-in styles (by id, or by Word's name as a localised file has it). */
    private fun builtinStyle(d: StyleDef): Boolean = StyleSheet.builtinOf(d) != null

    /** The document's own paragraph styles — from its file, or made here — in file order (the built-in six aside). */
    val ownStyles: List<StyleDef> get() = document.styles.styles.values.filter { !builtinStyle(it) }

    /** The style id of the caret's paragraph (a built-in's id as this document has it, or its own). */
    val activeStyleId: String get() = styleIdOf(activeParagraph)

    fun resolvedStyle(id: String): ResolvedStyle = document.styles.resolve(id)

    /** How many paragraphs carry the style [id]. */
    fun styleUses(id: String): Int = allParagraphs().count { styleIdOf(it).equals(id, ignoreCase = true) }

    /** What is wrong with [name] for a new style: empty, or a style's name already (any case); null when it will do. */
    fun newStyleIssue(name: String): StyleNameIssue? {
        val n = name.trim()
        if (n.isEmpty()) return StyleNameIssue.EMPTY
        if (document.styles.styles.values.any { it.name.equals(n, ignoreCase = true) || it.id.equals(n, ignoreCase = true) } ||
            ParagraphStyle.entries.any { it.display.equals(n, ignoreCase = true) || it.wordName.equals(n, ignoreCase = true) }) return StyleNameIssue.TAKEN
        return null
    }
    enum class StyleNameIssue { EMPTY, TAKEN }

    /** Applies the style [id] to the selected paragraphs (a heading's semantics when it is one, or based on one). */
    fun setStyleId(id: String) = command {
        val sheet = document.styles
        for (p in selectedParagraphs()) {
            p.format.rawStyleId = sheet[id]?.id ?: id
            p.format.style = styleKindOf(id)
            applyResolved(p, sheet.resolve(id))
        }
    }

    /** The built-in style [id] stands for, or the one it is based on (a heading's custom look keeps it a heading). */
    private fun styleKindOf(id: String): ParagraphStyle {
        val sheet = document.styles
        var cur: String? = id
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur.lowercase())) {
            val kind = sheet.styleFor(cur)
            if (kind != ParagraphStyle.NORMAL || ParagraphStyle.NORMAL.styleId.equals(cur, ignoreCase = true)) return kind
            cur = sheet[cur]?.basedOn
        }
        return ParagraphStyle.NORMAL
    }

    /**
     * Word's Create New Style from Formatting: a paragraph style named [name], based on the caret's paragraph's style and
     * set as that paragraph looks — its first run's font, size, bold, italic and colour, its alignment, spacing, line
     * spacing and indents where they differ from the base — then applied to the selected paragraphs. One undo step. The
     * new style's id (its name without spaces, made unique); null when the name will not do.
     */
    fun createStyle(name: String): String? {
        if (newStyleIssue(name) != null) return null
        val n = name.trim()
        val p = activeParagraph
        val f = p.format
        val base = styleIdOf(p)
        val r = document.styles.resolve(base)
        val run = (p.runs.firstOrNull { it.text.isNotBlank() } ?: p.runs.firstOrNull())?.format ?: CharacterFormat()
        val stem = n.filter { it.isLetterOrDigit() }.ifEmpty { "Style" }
        var id = stem
        var k = 1
        while (document.styles[id] != null) id = stem + (++k)
        val def = StyleDef(
            id, n, basedOn = base,
            fontFamily = run.fontFamily?.takeIf { it != r.fontFamily },
            sizePt = run.fontSizePt?.takeIf { it != r.sizePt },
            bold = run.bold.takeIf { it != r.bold },
            italic = run.italic.takeIf { it != r.italic },
            color = run.color?.takeIf { it != r.color },
            align = f.align.takeIf { it != (r.align ?: TextAlign.LEFT) },
            spaceBeforePt = f.spaceBeforePt.takeIf { it != r.spaceBeforePt },
            spaceAfterPt = f.spaceAfterPt.takeIf { it != r.spaceAfterPt },
            lineSpacing = if (f.lineHeightPt == null) f.lineSpacing.takeIf { it != r.lineSpacing || r.lineHeightPt != null } else null,
            lineHeightPt = f.lineHeightPt?.takeIf { it != r.lineHeightPt }, lineExact = f.lineExact,
            indentLeftPt = if (f.listKind == ListKind.NONE) f.indentLeftPt.takeIf { it != (r.indentLeftPt ?: 0f) } else null,
            firstLineIndentPt = if (f.listKind == ListKind.NONE) f.firstLineIndentPt.takeIf { it != (r.firstLineIndentPt ?: 0f) } else null,
            indentRightPt = f.indentRightPt.takeIf { it != (r.indentRightPt ?: 0f) },
            borders = f.borders?.takeIf { it != r.borders }, shading = f.shading?.takeIf { it != r.shading },
        )
        val kind = styleKindOf(base)
        command {
            document.styles.put(def)
            for (q in selectedParagraphs()) {
                q.format.rawStyleId = id; q.format.style = kind
                if (q !== p) applyResolved(q, document.styles.resolve(id))
            }
        }
        return id
    }

    /**
     * Deletes the document's own style [id] (Word's Delete "X"): its paragraphs take the style it was based on (Normal at
     * the root), styles based on it take its base. Built-in styles are not deleted. One undo step.
     */
    fun deleteStyle(id: String) {
        val def = document.styles[id] ?: return
        if (builtinStyle(def)) return
        command {
            val sheet = document.styles
            val base = def.basedOn?.takeIf { sheet[it] != null } ?: sheet.idOf(ParagraphStyle.NORMAL)
            sheet.styles.values.forEach { if (it.basedOn.equals(def.id, ignoreCase = true)) it.basedOn = base }
            sheet.styles.remove(def.id)
            for (p in allParagraphs()) if (styleIdOf(p).equals(def.id, ignoreCase = true)) {
                p.format.rawStyleId = base; p.format.style = styleKindOf(base)
                applyResolved(p, sheet.resolve(base))
            }
        }
    }

    // Paragraph formatting acts on every paragraph the selection touches; toggles follow the first.
    fun toggleBullet() = command {
        val paras = selectedParagraphs()
        setListKind(paras, if (paras.first().format.listKind == ListKind.BULLET) ListKind.NONE else ListKind.BULLET)
    }

    fun toggleNumber() = command {
        val paras = selectedParagraphs()
        setListKind(paras, if (paras.first().format.listKind == ListKind.NUMBER) ListKind.NONE else ListKind.NUMBER)
    }

    private fun setListKind(paras: List<Paragraph>, kind: ListKind) {
        // Next to a list of this kind (the paragraph before the first): these become more of its items, as in Word.
        val all = document.paragraphs()
        val before = all.indexOfFirst { it === paras.first() }.let { if (it > 0) all[it - 1] else null }
        val join = before?.format?.takeIf { it.listKind == kind && it.listId != 0 && document.lists.containsKey(it.listId) }
        paras.forEach { p ->
            // Out of a list, the hanging indent its marker stood in goes with it (as Word's numbering indent does).
            if (kind == ListKind.NONE && p.format.listKind != ListKind.NONE && p.format.firstLineIndentPt < 0f) p.format.firstLineIndentPt = 0f
            if (p.format.listKind != kind) p.format.listId = join?.listId ?: 0
            if (kind == ListKind.NONE) p.format.listId = 0
            p.format.listKind = kind
        }
        dropUnusedLists()
        markParaRevised()
    }

    /** List definitions no paragraph uses any more go. */
    private fun dropUnusedLists() {
        if (document.lists.isEmpty()) return
        val used = allParagraphs().map { it.format.listId }.toSet()
        document.lists.keys.retainAll(used)
    }

    /** The list the active paragraph is an item of: its id (0: a plain one) and definition. */
    val activeList: Pair<Int, com.therealityteam.aeropages.model.ListDef>?
        get() {
            val f = activeParagraph.format
            if (f.listKind == ListKind.NONE) return null
            return f.listId to com.therealityteam.aeropages.model.Lists.defOf(f, document.lists)
        }

    /**
     * Numbers or bullets the selection as [def] has them (the gallery's 一、 （一） 1.1 ➢ …): a list
     * already selected takes the new look throughout; other paragraphs become a new list.
     */
    fun applyListDef(def: com.therealityteam.aeropages.model.ListDef) = command {
        val paras = selectedParagraphs()
        val kind = if (def.level(0).format == com.therealityteam.aeropages.model.NumFormat.BULLET) ListKind.BULLET else ListKind.NUMBER
        val id0 = paras.first().format.listId
        val whole = id0 != 0 && document.lists.containsKey(id0) && paras.all { it.format.listId == id0 }
        val id = if (whole) id0 else document.newListId()
        document.lists[id] = def
        val items = if (whole) allParagraphs().filter { it.format.listId == id } else paras
        items.forEach { p ->
            p.format.listId = id
            p.format.listKind = if (def.level(p.format.listLevel).format == com.therealityteam.aeropages.model.NumFormat.BULLET) ListKind.BULLET else kind
        }
        dropUnusedLists()
        markParaRevised()
    }

    /** The active list item and the rest of its list after it become a new list, numbered from its first number again. */
    fun restartNumbering() = command {
        val p = activeParagraph
        val f = p.format
        if (f.listKind == ListKind.NONE) return@command
        val all = document.paragraphs()
        val at = all.indexOfFirst { it === p }.coerceAtLeast(0)
        val rest = sameListFrom(all, at)
        val id = document.newListId()
        document.lists[id] = com.therealityteam.aeropages.model.Lists.defOf(f, document.lists)
        rest.forEach { it.format.listId = id }
        dropUnusedLists()
    }

    /** The active list goes on counting from the list of its kind before it (Word's 继续编号). */
    fun continueNumbering() = command {
        val p = activeParagraph
        val f = p.format
        if (f.listKind == ListKind.NONE) return@command
        val all = document.paragraphs()
        val at = all.indexOfFirst { it === p }.coerceAtLeast(0)
        val mine = sameListFrom(all, firstOfList(all, at))
        val prev = (firstOfList(all, at) - 1 downTo 0).map { all[it] }.firstOrNull { it.format.listKind == f.listKind && it !in mine } ?: return@command
        val target = if (prev.format.listId != 0 && document.lists.containsKey(prev.format.listId)) prev.format.listId else {
            // A plain list before: it becomes a list of its own for this one to join.
            val id = document.newListId()
            document.lists[id] = com.therealityteam.aeropages.model.Lists.defOf(prev.format, document.lists)
            sameListFrom(all, firstOfList(all, all.indexOfFirst { it === prev })).forEach { it.format.listId = id }
            id
        }
        mine.forEach { it.format.listId = target }
        dropUnusedLists()
    }

    /** The items of the list [all]`[at]` is in, from it on: the same list id, or the run of plain ones it is in. */
    private fun sameListFrom(all: List<Paragraph>, at: Int): List<Paragraph> {
        val f = all[at].format
        if (f.listId != 0 && document.lists.containsKey(f.listId)) return all.drop(at).filter { it.format.listId == f.listId }
        return all.drop(at).takeWhile { it.format.listKind == f.listKind && it.format.listId == 0 }
    }

    /** Where the list [all]`[at]` is in begins (a list with an id: its first item; a plain one: its run's). */
    private fun firstOfList(all: List<Paragraph>, at: Int): Int {
        val f = all[at].format
        if (f.listId != 0 && document.lists.containsKey(f.listId)) return all.indexOfFirst { it.format.listId == f.listId }.coerceAtLeast(0)
        var i = at
        while (i > 0 && all[i - 1].format.listKind == f.listKind && all[i - 1].format.listId == 0) i--
        return i
    }

    fun indent() = command {
        for (p in selectedParagraphs()) {
            val f = p.format
            f.indentLeftPt = (f.indentLeftPt + 36f).coerceAtMost(360f)
            if (f.listKind != ListKind.NONE) f.listLevel = (f.listLevel + 1).coerceAtMost(8)
        }
        markParaRevised()
    }

    fun outdent() = command {
        for (p in selectedParagraphs()) {
            val f = p.format
            f.indentLeftPt = (f.indentLeftPt - 36f).coerceAtLeast(0f)
            if (f.listKind != ListKind.NONE) f.listLevel = (f.listLevel - 1).coerceAtLeast(0)
        }
        markParaRevised()
    }

    fun setLineSpacing(multiplier: Float) = command { selectedParagraphs().forEach { it.format.lineSpacing = multiplier; it.format.lineHeightPt = null }; markParaRevised() }

    // --- indents (Word's Paragraph ▸ Indentation, counted in characters as Chinese Word counts them) ---

    /** How wide one character (字符) of [p] is: its text's size — the unit its indents are counted in. */
    fun charWidthPt(p: Paragraph): Float =
        (p.runs.firstOrNull { it.text.isNotBlank() } ?: p.runs.firstOrNull())?.format?.fontSizePt ?: p.format.style.sizePt

    /** The active paragraph's first-line indent in characters: positive 首行缩进, negative 悬挂缩进. */
    val firstLineIndentChars: Float get() = activeParagraph.let { it.format.firstLineIndentPt / charWidthPt(it) }

    /** The active paragraph's left edge (a hanging first line's) and its right indent, in characters. */
    val leftIndentChars: Float get() = activeParagraph.let { (it.format.indentLeftPt + minOf(it.format.firstLineIndentPt, 0f)) / charWidthPt(it) }
    val rightIndentChars: Float get() = activeParagraph.let { it.format.indentRightPt / charWidthPt(it) }

    /**
     * Gives the selected paragraphs a first-line indent of [chars] characters — negative hangs the
     * first line out by that much, 0 removes it — their left edge staying where it was.
     */
    fun setFirstLineIndent(chars: Float) = command {
        for (p in selectedParagraphs()) {
            val f = p.format
            val edge = f.indentLeftPt + minOf(f.firstLineIndentPt, 0f)
            val by = chars * charWidthPt(p)
            f.indentLeftPt = edge - minOf(by, 0f)
            f.firstLineIndentPt = by
        }
        markParaRevised()
    }

    /** Moves the selected paragraphs' left edge — or, [right], their right indent — by [chars] characters, never into the margin. */
    fun stepIndent(chars: Float, right: Boolean = false) = command {
        for (p in selectedParagraphs()) {
            val f = p.format
            val by = chars * charWidthPt(p)
            if (right) f.indentRightPt = (f.indentRightPt + by).coerceIn(0f, MAX_INDENT_PT)
            else {
                val edge = f.indentLeftPt + minOf(f.firstLineIndentPt, 0f)
                f.indentLeftPt += (edge + by).coerceIn(0f, MAX_INDENT_PT) - edge
            }
        }
        markParaRevised()
    }

    // --- the ruler: indents and tab stops dragged into place ---------------------------------

    /**
     * The ruler's indents for the selected paragraphs, in points from the margins: where the lines after the first start
     * ([leftPt]), the first line's offset from there ([firstPt]: positive indents it, negative hangs it out) and the right
     * indent — none of them into the margin (one undo step).
     */
    fun setIndents(leftPt: Float, firstPt: Float, rightPt: Float) = command {
        val left = leftPt.coerceIn(0f, MAX_INDENT_PT)
        val first = firstPt.coerceAtLeast(-left)
        for (p in selectedParagraphs()) {
            p.format.indentLeftPt = left
            p.format.firstLineIndentPt = first
            p.format.indentRightPt = rightPt.coerceIn(0f, MAX_INDENT_PT)
        }
        markParaRevised()
    }

    /** The selected paragraphs' tab stops gain [stop] (one already within a point of it is replaced). */
    fun addTabStop(stop: com.therealityteam.aeropages.model.TabStop) = command {
        for (p in selectedParagraphs()) p.format.tabStops = (p.format.tabStops.filterNot { kotlin.math.abs(it.posPt - stop.posPt) < 1f } + stop).sortedBy { it.posPt }
        markParaRevised()
    }

    /** The selected paragraphs' tab stop at [fromPt] moves to [toPt] — keeping its alignment and leader — or, [toPt] null, goes. */
    fun moveTabStop(fromPt: Float, toPt: Float?) = command {
        for (p in selectedParagraphs()) {
            val old = p.format.tabStops.firstOrNull { kotlin.math.abs(it.posPt - fromPt) < 1f } ?: continue
            val rest = p.format.tabStops - old
            p.format.tabStops = if (toPt == null) rest
                else (rest.filterNot { kotlin.math.abs(it.posPt - toPt) < 1f } + old.copy(posPt = toPt.coerceAtLeast(0f))).sortedBy { it.posPt }
        }
        markParaRevised()
    }

    /** Word's Tabs dialog: the selected paragraphs' tab stops become [stops] and the document's default stops [defaultTabPt] apart (one undo step). */
    fun setTabs(stops: List<com.therealityteam.aeropages.model.TabStop>, defaultTabPt: Float) = command {
        val sorted = stops.sortedBy { it.posPt }
        for (p in selectedParagraphs()) p.format.tabStops = sorted
        if (defaultTabPt > 1f) document.defaultTabPt = defaultTabPt
        markParaRevised()
    }

    // --- line and page breaks (Word's Paragraph ▸ Line and Page Breaks) ----------------------

    /** Whether the active paragraph keeps with the next one (its own setting, else its style's). */
    val keepsWithNext: Boolean get() = com.therealityteam.aeropages.model.PageMetrics.keepsWithNext(activeParagraph)

    /** Toggles "keep with next" on the active paragraph (one undo step). */
    fun toggleKeepWithNext() = command { val on = !keepsWithNext; selectedParagraphs().forEach { it.format.keepWithNext = on }; markParaRevised() }

    /** Toggles "keep lines together": the paragraph will not break across pages. */
    fun toggleKeepTogether() = command { val on = !activeParagraph.format.keepTogether; selectedParagraphs().forEach { it.format.keepTogether = on }; markParaRevised() }

    /** Toggles "page break before": the paragraph starts a new page. */
    fun togglePageBreakBefore() = command { val on = !activeParagraph.format.pageBreakBefore; selectedParagraphs().forEach { it.format.pageBreakBefore = on }; markParaRevised() }

    /** Toggles widow/orphan control on the active paragraph. */
    fun toggleWidowControl() = command { val on = !activeParagraph.format.widowControl; selectedParagraphs().forEach { it.format.widowControl = on }; markParaRevised() }

    // --- design (themes / page background) -------------------------------------

    private fun isHeadingStyle(style: ParagraphStyle) =
        style != ParagraphStyle.NORMAL && style != ParagraphStyle.QUOTE

    /** Apply theme fonts document-wide: [headingFont] to headings, [bodyFont] to the rest. */
    fun applyThemeFonts(bodyFont: String, headingFont: String) = command {
        allParagraphs().forEach { p ->
            val font = if (isHeadingStyle(p.format.style)) headingFont else bodyFont
            p.runs.forEach { it.format.fontFamily = font }
        }
    }

    /** Apply a theme accent colour to all heading text. */
    fun applyThemeColor(accent: Long) = command {
        document.paragraphs().forEach { p ->
            if (isHeadingStyle(p.format.style)) p.runs.forEach { it.format.color = accent }
        }
    }

    /** Apply a full style set: heading colour + heading/body fonts. */
    fun applyStyleSet(headingColor: Long, bodyFont: String, headingFont: String) = command {
        allParagraphs().forEach { p ->
            val heading = isHeadingStyle(p.format.style)
            p.runs.forEach {
                it.format.fontFamily = if (heading) headingFont else bodyFont
                if (heading) it.format.color = headingColor
            }
        }
    }

    /** Apply paragraph-spacing preset document-wide. */
    fun applyDocSpacing(beforePt: Float, afterPt: Float, line: Float) = command {
        allParagraphs().forEach { p ->
            p.format.spaceBeforePt = beforePt; p.format.spaceAfterPt = afterPt; p.format.lineSpacing = line; p.format.lineHeightPt = null
        }
    }

    fun setPageColor(color: Long?) = command { document.pageColor = color }
    fun setWatermark(text: String?) = command { document.watermark = text }
    /** Design ▸ Page Borders: the pages' borders (null or none: no borders), one undo step; nothing when unchanged. */
    fun setPageBorders(b: com.therealityteam.aeropages.model.PageBorders?) {
        val next = b?.takeIf { !it.none }
        if (next == document.pageBorders) return
        command { document.pageBorders = next }
    }

    // --- page setup ------------------------------------------------------------

    /**
     * The page setup the caret sits in. A document without section breaks has one setup
     * ([Document.page]); with breaks, each command below changes only the section being edited.
     */
    val currentPage: PageSetup get() = document.setupAt(blockIndexOfActive())

    /** The caret's section numbers its pages in [format], from [start] (null: on from the section before) — Word's 页码格式. */
    fun setPageNumbering(format: com.therealityteam.aeropages.model.NumFormat, start: Int?) = command {
        val pg = currentPage
        pg.pageNumberFormat = format; pg.pageNumberStart = start
    }

    private fun blockIndexOfActive(): Int = when (val loc = locAt(active)) {
        is TopLoc -> loc.blockIndex
        is CellLoc -> document.blocks.indexOfFirst { b -> b is TableBlock && b.table.rows.any { r -> r.cells.any { it === loc.cell } } }
            .coerceAtLeast(0)
        is BoxLoc -> document.blocks.indexOfFirst { it === loc.box }.coerceAtLeast(0)
    }

    /** Insert a text box (its own framed paragraphs) after the active paragraph's block and move the caret into it. */
    fun insertTextBox() = command {
        val box = com.therealityteam.aeropages.model.TextBoxBlock()
        document.blocks.add(topBlockIndexOf(active) + 1, box)
        active = locs().indexOfFirst { it.paragraph === box.paragraphs[0] }.coerceAtLeast(0)
        selStart = 0; selEnd = 0
        shapeInserts++
    }

    /** True when the caret sits inside a text box. */
    val activeInTextBox: Boolean get() = locAt(active) is BoxLoc

    // --- text boxes and shapes ---------------------------------------------------------

    /** A text box or shape picked on the page (one holding no text — a line, an arrow — has no caret to show it). */
    var selectedBox: com.therealityteam.aeropages.model.TextBoxBlock? = null
        private set

    /** The text box or shape being worked on: the one the caret is in, else the one picked. */
    val activeBox: com.therealityteam.aeropages.model.TextBoxBlock?
        get() = (locAt(active) as? BoxLoc)?.box?.takeIf { selectedImage == null && selectedChart == null && selectedEquation == null } ?: selectedBox

    /** Pick [box] (null: none); a picked picture, chart or equation is let go. */
    fun selectBox(box: com.therealityteam.aeropages.model.TextBoxBlock?) {
        if (box !== selectedBox) imageOptionsOpen = false
        selectedBox = box?.takeIf { b -> document.blocks.any { it === b } }
        if (selectedBox != null) { selectedImage = null; selectedChart = null; selectedEquation = null }
        bump()
    }

    /** Where [box] stands — floating at [pos], or (null) in the text's flow — and how the text goes by it. One undo step. */
    fun setBoxFloat(box: com.therealityteam.aeropages.model.TextBoxBlock, pos: com.therealityteam.aeropages.model.FloatPosition?) {
        if (document.blocks.none { it === box } || box.floating == pos) return
        command { box.floating = pos?.copy() }
    }

    /**
     * A change to [box]'s look — its fill, outline, shadow — one undo step. A plain text box first takes a
     * look of its own: Word's text box, white with a thin black rule.
     */
    fun editBoxShape(box: com.therealityteam.aeropages.model.TextBoxBlock, change: (com.therealityteam.aeropages.model.SlideShape) -> Unit) {
        if (document.blocks.none { it === box }) return
        command {
            val sh = box.shape ?: com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.TEXTBOX,
                fill = 0xFFFFFFFF, border = 0xFF000000, borderWidthPt = 0.75f).also { box.shape = it }
            change(sh)
        }
    }

    /** [box] made [factor] times as large, its shape with it (its text keeps its size); within 16 pt and a page. One undo step. */
    fun scaleBox(box: com.therealityteam.aeropages.model.TextBoxBlock, factor: Float) {
        if (document.blocks.none { it === box }) return
        command {
            val w = box.widthPt ?: 320f
            val f = factor.coerceIn(16f / w, 1200f / w)
            box.widthPt = w * f
            box.heightPt = box.heightPt?.let { (it * f).coerceAtLeast(4f) }
        }
    }

    /**
     * [box] [widthPt] wide and [heightPt] tall (null leaves a side; a box without a height of its own grows with its
     * text) — Shape Format's Height and Width; within 16 pt and a page. One undo step.
     */
    fun setBoxSize(box: com.therealityteam.aeropages.model.TextBoxBlock, widthPt: Float?, heightPt: Float?) {
        if (document.blocks.none { it === box }) return
        command {
            widthPt?.let { box.widthPt = it.coerceIn(16f, 1200f) }
            heightPt?.let { box.heightPt = it.coerceIn(4f, 1600f) }
        }
    }

    /** A Shape Styles choice on [box]: its fill and outline, and its text in [text] (null leaves the text's colour). One undo step. */
    fun applyBoxStyle(box: com.therealityteam.aeropages.model.TextBoxBlock, fill: Long?, border: Long?, text: Long?) {
        if (document.blocks.none { it === box }) return
        command {
            val sh = box.shape ?: com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.TEXTBOX, borderWidthPt = 0.75f).also { box.shape = it }
            sh.fill = fill; sh.border = border
            if (text != null) box.paragraphs.forEach { p -> ParagraphOps.applyFormat(p, 0, ParagraphOps.length(p).coerceAtLeast(1)) { f -> f.color = text } }
        }
    }

    /** Remove the text box or shape [box] and its text; the caret goes to the paragraph before it. One undo step. */
    fun deleteBox(box: com.therealityteam.aeropages.model.TextBoxBlock) {
        val at = document.blocks.indexOfFirst { it === box }
        if (at < 0) return
        command {
            val before = document.blocks.subList(0, at).flatMap { b ->
                when (b) {
                    is ParagraphBlock -> listOf(b.paragraph)
                    is TableBlock -> b.table.rows.flatMap { r -> r.cells.flatMap { it.content } }
                    is com.therealityteam.aeropages.model.TextBoxBlock -> b.paragraphs
                    else -> emptyList()
                }
            }.lastOrNull()
            document.blocks.removeAt(at)
            if (document.blocks.isEmpty()) document.blocks.add(ParagraphBlock(Paragraph.of("")))
            if (selectedBox === box) selectedBox = null
            active = before?.let { p -> locs().indexOfFirst { it.paragraph === p } }?.coerceAtLeast(0) ?: 0
            selStart = 0; selEnd = 0
            clampActive()
        }
    }

    /**
     * How tall the page shows a paragraph, in points (set by the canvas, which measures it; null: not laid
     * out) — for placing what floats from a paragraph just below it.
     */
    var paragraphHeightPt: (Paragraph) -> Float? = { null }

    /**
     * Insert → Shapes: [preset] in Word's default look (accent blue, a darker rule, white text in its
     * middle), 144 × 96 pt, in front of the text — floating from the caret's paragraph, just below it
     * ([belowPt] down from its top), centred in its column; the caret goes into its text.
     */
    fun insertShape(preset: String, belowPt: Float = paragraphHeightPt(document.locations()[topParagraphIndex()].paragraph)?.plus(6f) ?: 24f) {
        val own = OWN_PRESETS[preset]
        if (own == null && !com.therealityteam.aeropages.format.pptx.PresetGeometries.has(preset)) return
        command {
            val w = 144f; val h = 96f
            val shape = com.therealityteam.aeropages.model.SlideShape(own ?: com.therealityteam.aeropages.model.ShapeType.FREEFORM,
                fill = 0xFF4472C4, border = 0xFF2F528F, borderWidthPt = 1f)
            if (own == null) {
                shape.preset = preset
                shape.path = com.therealityteam.aeropages.format.pptx.PresetGeometries.freeform(preset, null, (w * 12700f).toDouble(), (h * 12700f).toDouble())
            }
            shape.anchor = com.therealityteam.aeropages.model.ShapeAnchor.CENTER
            val text = Paragraph(mutableListOf(TextRun("", CharacterFormat(color = 0xFFFFFFFF))),
                com.therealityteam.aeropages.model.ParagraphFormat(align = com.therealityteam.aeropages.model.TextAlign.CENTER, spaceBeforePt = 0f, spaceAfterPt = 0f))
            val box = com.therealityteam.aeropages.model.TextBoxBlock(mutableListOf(text), w, h, shape,
                com.therealityteam.aeropages.model.FloatPosition(0f, com.therealityteam.aeropages.model.FloatFrame.COLUMN, com.therealityteam.aeropages.model.FloatAlign.CENTER,
                    belowPt, com.therealityteam.aeropages.model.FloatFrame.PARAGRAPH, null, com.therealityteam.aeropages.model.FloatWrap.FRONT))
            document.blocks.add(topBlockIndexOf(active) + 1, box)
            active = locs().indexOfFirst { it.paragraph === text }.coerceAtLeast(0)
            selStart = 0; selEnd = 0
            shapeInserts++
        }
    }

    fun setPageSize(widthPt: Float, heightPt: Float) = command {
        val pg = currentPage
        pg.widthPt = widthPt; pg.heightPt = heightPt
    }

    fun setOrientationLandscape(landscape: Boolean) = command {
        val pg = currentPage
        val w = minOf(pg.widthPt, pg.heightPt); val h = maxOf(pg.widthPt, pg.heightPt)
        if (landscape) { pg.widthPt = h; pg.heightPt = w } else { pg.widthPt = w; pg.heightPt = h }
    }

    fun setMargins(top: Float, bottom: Float, left: Float, right: Float) = command {
        val pg = currentPage
        pg.marginTopPt = top; pg.marginBottomPt = bottom; pg.marginLeftPt = left; pg.marginRightPt = right
    }

    /**
     * Word's Page Setup dialog: [edit] made to the caret's section's page — or, [wholeDocument], to every section's —
     * as one step back.
     */
    fun applyPageSetup(wholeDocument: Boolean, differentOddEven: Boolean = document.differentOddEven,
                       differentFirstPage: Boolean = document.differentFirstPage, edit: (PageSetup) -> Unit) = command {
        if (differentOddEven && !document.differentOddEven) {
            if (document.evenHeader == null) document.evenHeader = document.header?.copyOf()
            if (document.evenFooter == null) document.evenFooter = document.footer?.copyOf()
        }
        document.differentOddEven = differentOddEven
        document.differentFirstPage = differentFirstPage
        if (!wholeDocument) { edit(currentPage); return@command }
        edit(document.page)
        document.blocks.forEach { b -> (b as? ParagraphBlock)?.paragraph?.format?.sectionBreak?.let(edit) }
    }

    /** Word's Layout ▸ Spacing: the selected paragraphs' space before and after moved by so many points (never below 0). */
    fun stepSpacing(beforePt: Float, afterPt: Float) = command {
        for (p in selectedParagraphs()) {
            p.format.spaceBeforePt = (p.format.spaceBeforePt + beforePt).coerceIn(0f, 1584f)
            p.format.spaceAfterPt = (p.format.spaceAfterPt + afterPt).coerceIn(0f, 1584f)
        }
        markParaRevised()
    }

    /** 分栏: text columns per page (1..[com.therealityteam.aeropages.model.PageMetrics.MAX_COLUMNS]). */
    fun setColumns(n: Int) = command { currentPage.columns = n.coerceIn(1, com.therealityteam.aeropages.model.PageMetrics.MAX_COLUMNS) }

    /**
     * Word's Columns dialog: [count] columns [gapPt] apart, a line between them or not, for the caret's section or the
     * whole document — one undo step; nothing when it changes nothing.
     */
    /** The caret's section's line numbers (Layout ▸ Line Numbers), or null. */
    val lineNumbering: com.therealityteam.aeropages.model.LineNumbering? get() = currentPage.lineNumbers

    /** [n] as the line numbers of the caret's section, or of every section ([wholeDocument]); null: none. Nothing to do, no step. */
    fun setLineNumbers(n: com.therealityteam.aeropages.model.LineNumbering?, wholeDocument: Boolean = false) {
        val pages = if (wholeDocument) listOf(document.page) + document.blocks.mapNotNull { (it as? ParagraphBlock)?.paragraph?.format?.sectionBreak } else listOf(currentPage)
        if (pages.all { it.lineNumbers == n }) return
        applyPageSetup(wholeDocument) { it.lineNumbers = n }
    }

    /** Word's Line Numbers menu: [restart] numbering (the section's other settings kept), or none. */
    fun setLineNumberRestart(restart: com.therealityteam.aeropages.model.LineNumberRestart?) =
        setLineNumbers(restart?.let { (lineNumbering ?: com.therealityteam.aeropages.model.LineNumbering()).copy(restart = it) })

    /** Whether the selection's first paragraph suppresses line numbers. */
    val lineNumbersSuppressed: Boolean get() = selectedParagraphs().firstOrNull()?.format?.suppressLineNumbers == true

    /** Word's "Suppress for Current Paragraph": the selected paragraphs' lines neither numbered nor counted, or again. One undo step. */
    fun toggleSuppressLineNumbers() {
        val on = !lineNumbersSuppressed
        formatParagraphs { it.suppressLineNumbers = on }
    }

    /**
     * Whether the caret's paragraph can take a drop cap (Word's Insert ▸ Drop Cap): a body paragraph — not a table's, a
     * text box's or a list item — that starts with a letter to drop.
     */
    val canDropCap: Boolean get() {
        val p = (locAt(active) as? TopLoc)?.paragraph ?: return false
        if (p.format.listKind != com.therealityteam.aeropages.model.ListKind.NONE || p.isPageBreak()) return false
        val first = p.runs.firstOrNull { it.text.isNotEmpty() }?.text?.first() ?: return false
        return !first.isWhitespace() && first != '\u000C'
    }

    /** The caret's paragraph's drop cap (null: none, or not a body paragraph). */
    val dropCap: com.therealityteam.aeropages.model.DropCap? get() = (locAt(active) as? TopLoc)?.paragraph?.format?.dropCap

    /**
     * Sets the caret's paragraph's drop cap ([cap] null: none) — one step back. False, with nothing recorded, when it is
     * already so or the paragraph cannot take one ([canDropCap]).
     */
    fun setDropCap(cap: com.therealityteam.aeropages.model.DropCap?): Boolean {
        val p = (locAt(active) as? TopLoc)?.paragraph ?: return false
        if (cap != null && !canDropCap) return false
        // A new drop cap takes the first letter with the quotes or brackets that open it; a changed one keeps its letters.
        val letters = p.format.dropCap?.length ?: com.therealityteam.aeropages.model.DropCaps.firstLetters(p).coerceAtLeast(1)
        val fixed = cap?.let { it.copy(lines = it.lines.coerceIn(1, com.therealityteam.aeropages.model.DropCap.MAX_LINES), distancePt = it.distancePt.coerceIn(0f, 72f), length = letters) }
        if (p.format.dropCap == fixed) return false
        command { p.format.dropCap = fixed; markParaRevised() }
        return true
    }

    fun setColumnLayout(count: Int, gapPt: Float, line: Boolean, wholeDocument: Boolean) {
        val n = count.coerceIn(1, com.therealityteam.aeropages.model.PageMetrics.MAX_COLUMNS)
        val g = gapPt.coerceIn(0f, 288f)
        val pages = if (wholeDocument) listOf(document.page) + document.blocks.mapNotNull { (it as? ParagraphBlock)?.paragraph?.format?.sectionBreak } else listOf(currentPage)
        if (pages.all { it.columns == n && it.columnGapPt == g && it.columnLine == line }) return
        applyPageSetup(wholeDocument) { it.columns = n; it.columnGapPt = g; it.columnLine = line }
    }

    /**
     * Start a new section after the caret's paragraph (分节符). The section that ends here keeps
     * the page setup in force; the new one starts as a copy of it, so orientation, margins and
     * columns can then differ from this point on.
     */
    fun insertSectionBreak() = command {
        val loc = locAt(active)
        if (loc !is TopLoc) return@command                 // sections are a body-level concept
        val setup = document.setupAt(loc.blockIndex).copy()
        loc.paragraph.format.sectionBreak = setup
        // Everything after the break belongs to the next section; the tail keeps its own setup.
        val para = Paragraph(mutableListOf(TextRun("")))
        document.blocks.add(loc.blockIndex + 1, ParagraphBlock(para))
        active += 1; selStart = 0; selEnd = 0
    }

    /** Remove the section break the caret's paragraph carries, merging it into the next section. */
    fun removeSectionBreak() = command {
        (locAt(active) as? TopLoc)?.paragraph?.format?.sectionBreak = null
    }

    /** 1-based index of the section the caret is in, and how many there are (for the status line). */
    fun sectionPosition(): Pair<Int, Int> {
        val sections = document.sections()
        val blockIndex = blockIndexOfActive()
        var seen = 0
        var index = 0
        for ((i, section) in sections.withIndex()) {
            if (blockIndex < seen + section.first.size) { index = i; break }
            seen += section.first.size
            index = i
        }
        return (index + 1) to sections.size
    }

    // --- header / footer -------------------------------------------------------

    /** Which header / footer variant a page uses (Word's default, even-page and first-page headers). */
    enum class HeaderKind { DEFAULT, EVEN, FIRST }
    fun headerKindFor(pageNumber: Int): HeaderKind = when {
        document.differentFirstPage && pageNumber == 1 -> HeaderKind.FIRST
        document.differentOddEven && pageNumber % 2 == 0 -> HeaderKind.EVEN
        else -> HeaderKind.DEFAULT
    }
    private fun headerOf(kind: HeaderKind, header: Boolean): Paragraph? = when (kind) {
        HeaderKind.DEFAULT -> if (header) document.header else document.footer
        HeaderKind.EVEN -> if (header) document.evenHeader else document.evenFooter
        HeaderKind.FIRST -> if (header) document.firstHeader else document.firstFooter
    }
    private fun setHeaderOf(kind: HeaderKind, header: Boolean, p: Paragraph?) {
        when (kind) {
            HeaderKind.DEFAULT -> if (header) document.header = p else document.footer = p
            HeaderKind.EVEN -> if (header) document.evenHeader = p else document.evenFooter = p
            HeaderKind.FIRST -> if (header) document.firstHeader = p else document.firstFooter = p
        }
    }

    fun addHeader(kind: HeaderKind = HeaderKind.DEFAULT) = command {
        // Empty, as Word opens it: the field shows "Header" faintly until typed in, and an empty one is dropped when editing ends.
        if (headerOf(kind, true) == null) setHeaderOf(kind, true, Paragraph(mutableListOf(TextRun("")), centeredFmt()))
    }

    fun addFooter(kind: HeaderKind = HeaderKind.DEFAULT) = command {
        if (headerOf(kind, false) == null) setHeaderOf(kind, false, Paragraph(mutableListOf(TextRun("")), centeredFmt()))
    }

    fun removeHeader(kind: HeaderKind = HeaderKind.DEFAULT) = command { setHeaderOf(kind, true, null) }
    fun removeFooter(kind: HeaderKind = HeaderKind.DEFAULT) = command { setHeaderOf(kind, false, null) }

    /** Word's 奇偶页不同: even pages get their own header / footer (seeded from the default ones). */
    fun toggleOddEvenHeaders() = command {
        document.differentOddEven = !document.differentOddEven
        if (document.differentOddEven) {
            if (document.evenHeader == null) document.evenHeader = document.header?.copyOf()
            if (document.evenFooter == null) document.evenFooter = document.footer?.copyOf()
        }
    }
    /** Word's 首页不同: the first page gets its own header / footer (empty until filled in). */
    fun toggleFirstPageHeader() = command { document.differentFirstPage = !document.differentFirstPage }

    fun setHeaderText(text: String, kind: HeaderKind = HeaderKind.DEFAULT) = setHeaderFooterText(text, header = true, kind = kind)
    fun setFooterText(text: String, kind: HeaderKind = HeaderKind.DEFAULT) = setHeaderFooterText(text, header = false, kind = kind)

    /** The header's or footer's text made [text] as it is typed: only what changed is replaced, the rest keeps its formatting. */
    private fun setHeaderFooterText(text: String, header: Boolean, kind: HeaderKind) {
        beginSession((if (header) "header" else "footer") + kind.name)
        val para = headerOf(kind, header) ?: Paragraph(mutableListOf(TextRun("")), centeredFmt()).also { setHeaderOf(kind, header, it) }
        HeaderFooterEdits.setText(para, text)
        bump()
    }

    /** Part of the header's or footer's text ([start]..[end], one slot of a line) made [text] as it is typed. */
    fun setHeaderFooterRange(header: Boolean, kind: HeaderKind, start: Int, end: Int, text: String) {
        val para = headerOf(kind, header) ?: return
        beginSession((if (header) "header" else "footer") + kind.name)
        HeaderFooterEdits.replaceRange(para, start.coerceIn(0, ParagraphOps.length(para)), end.coerceIn(start, ParagraphOps.length(para)), text)
        bump()
    }

    /**
     * A header or footer the canvas should open for typing (the gallery just filled it in): which one, and its
     * kind. The first page showing it takes the request and clears it.
     */
    class HfEdit(val header: Boolean, val kind: HeaderKind)
    var hfEditRequest: HfEdit? = null
        private set

    fun requestHeaderFooterEdit(header: Boolean, kind: HeaderKind = HeaderKind.DEFAULT) { hfEditRequest = HfEdit(header, kind); bump() }
    fun takeHeaderFooterEdit(header: Boolean, kind: HeaderKind): Boolean {
        val r = hfEditRequest ?: return false
        if (r.header != header || r.kind != kind) return false
        hfEditRequest = null
        return true
    }

    /**
     * Word's built-in headers and footers: the header's (or footer's) text made [text] — its formatting kept —
     * and aligned [align]; opened for typing, its first "[…]" placeholder selected. The even pages' one too when
     * they have their own.
     */
    fun applyHeaderFooterLayout(header: Boolean, text: String, align: TextAlign) = command {
        val kinds = if (document.differentOddEven) listOf(HeaderKind.DEFAULT, HeaderKind.EVEN) else listOf(HeaderKind.DEFAULT)
        for (kind in kinds) {
            val para = headerOf(kind, header) ?: Paragraph(mutableListOf(TextRun("")), centeredFmt()).also { setHeaderOf(kind, header, it) }
            HeaderFooterEdits.setText(para, text)
            para.format.align = align
        }
        hfEditRequest = HfEdit(header, HeaderKind.DEFAULT)
    }

    /**
     * Word's Page Number gallery: [number] (its text with the page field, "{PAGE}", "Page {PAGE} of {PAGES}" …) at the
     * top of the page ([header]) or its bottom, left / centre / right ([align]); a page number already in the header or
     * footer moves there (one step). The even pages' header or footer too when they have their own; not the first
     * page's — a first page of its own usually goes without one.
     */
    fun insertPageNumber(header: Boolean, align: TextAlign, number: String, phrases: List<String>) = command {
        val kinds = if (document.differentOddEven) listOf(HeaderKind.DEFAULT, HeaderKind.EVEN) else listOf(HeaderKind.DEFAULT)
        for (kind in kinds) {
            for (h in listOf(true, false)) headerOf(kind, h)?.let { if (HeaderFooterEdits.removePageNumbers(it, phrases)) dropIfEmpty(kind, h) }
            val para = headerOf(kind, header) ?: Paragraph(mutableListOf(TextRun("")), centeredFmt()).also { setHeaderOf(kind, header, it) }
            HeaderFooterEdits.placePageNumber(para, align, number, header)
        }
    }

    /** Word's Remove Page Numbers: every header's and footer's page numbers taken out; one left empty goes. */
    fun removePageNumbers(phrases: List<String>) = command {
        for (kind in HeaderKind.entries) for (h in listOf(true, false)) {
            headerOf(kind, h)?.let { if (HeaderFooterEdits.removePageNumbers(it, phrases)) dropIfEmpty(kind, h) }
        }
    }

    private fun dropIfEmpty(kind: HeaderKind, header: Boolean) {
        if (headerOf(kind, header)?.plainText()?.isBlank() == true) setHeaderOf(kind, header, null)
    }

    /** The page number's text where it is ("{PAGE}", "Page {PAGE} of {PAGES}" …), the footer's first; null without one. */
    val pageNumberText: String?
        get() = HeaderFooterEdits.pageNumberText(document.footer) ?: HeaderFooterEdits.pageNumberText(document.header)

    /** Where the page numbers are: in the header (true) or footer, and how aligned; null when there are none. */
    val pageNumberPlace: Pair<Boolean, TextAlign>?
        get() = HeaderFooterEdits.pageNumberAlign(document.footer)?.let { false to it }
            ?: HeaderFooterEdits.pageNumberAlign(document.header)?.let { true to it }

    private fun centeredFmt() = com.therealityteam.aeropages.model.ParagraphFormat(
        align = TextAlign.CENTER, spaceAfterPt = 0f,
    )

    // --- find & replace (Word's Find and Replace, the navigation pane's search) ---------------

    /** What Find looks for now (the find panel and the navigation pane share it); the canvas shades its matches. Empty: none. */
    var searchQuery by mutableStateOf("")
        private set
    var searchOptions by mutableStateOf(SearchOptions())
        private set

    /** Asked for the find panel on a tab (0 Find, 1 Replace, 2 Go To: Ctrl+H, Ctrl+G, the ribbon); the app opens it and clears this. */
    /** A dialog a key asked for ("font", "link") — the canvas opens it and clears the request. */
    var dialogRequest by mutableStateOf<String?>(null)
    fun requestDialog(kind: String) { dialogRequest = kind }

    var findPanelRequest by mutableStateOf<Int?>(null)
    fun requestFindPanel(tab: Int) { findPanelRequest = tab }

    /** Looks for [query] with [options] from now on (the matches are shaded on the page); "" stops. */
    fun setSearch(query: String, options: SearchOptions = searchOptions) {
        if (query == searchQuery && options == searchOptions) return
        searchQuery = query; searchOptions = options
        bump()
    }

    /** The current search's pattern; null with no query or with wildcards Word would not take. */
    val searchPattern: Regex? get() = DocSearch.pattern(searchQuery, searchOptions)

    private var hitsKey: Any? = null
    private var hitsCache: List<DocSearch.Hit> = emptyList()

    /** Every match of the current search, in document order — text deleted with Track Changes is no match. */
    val searchHits: List<DocSearch.Hit>
        get() {
            val key = Triple(revision, searchQuery, searchOptions)
            if (key != hitsKey) {
                hitsKey = key
                val regex = searchPattern
                hitsCache = if (regex == null) emptyList() else {
                    val locs = document.locations()
                    DocSearch.hits(document, regex).filterNot { h -> deletedWithin(locs[h.para].paragraph, h.start, h.end) }
                }
            }
            return hitsCache
        }

    private fun deletedWithin(p: Paragraph, a: Int, b: Int): Boolean {
        var pos = 0
        for (r in p.runs) {
            val e = pos + r.text.length
            if (r.format.deleted && e > a && pos < b) return true
            pos = e
        }
        return false
    }

    /** Which of [searchHits] is selected (0-based), or null. */
    fun currentHit(): Int? {
        if (hasSpan) return null
        val (a, b) = minOf(selStart, selEnd) to maxOf(selStart, selEnd)
        return searchHits.indexOfFirst { it.para == active && it.start == a && it.end == b }.takeIf { it >= 0 }
    }

    /** Selects match [h] and brings it into view (a click on a result). */
    fun showHit(h: DocSearch.Hit) = select(h)

    private fun select(h: DocSearch.Hit) {
        spanAnchor = null
        active = h.para; selStart = h.start; selEnd = h.end; revealRequest++; bump()
    }

    /**
     * Find Next (or, [back], Previous) for the current search: the match after the selection (before it), round the
     * document's end to its start; selected and shown. Which match it is (0-based), or null when there is none.
     */
    fun find(back: Boolean = false): Int? {
        val hits = searchHits
        if (hits.isEmpty()) return null
        val a = minOf(selStart, selEnd); val b = maxOf(selStart, selEnd)
        val i = if (!back) hits.indexOfFirst { it.para > active || (it.para == active && it.start >= b && !(it.start == a && it.end == b)) }.takeIf { it >= 0 } ?: 0
            else hits.indexOfLast { it.para < active || (it.para == active && it.end <= a && !(it.start == a && it.end == b)) }.takeIf { it >= 0 } ?: hits.lastIndex
        select(hits[i])
        return i
    }

    /** Replace: the selected match becomes [replacement] (its `^&`, groups and codes worked out), then the next is found. */
    fun replaceCurrent(replacement: String): Int? {
        val regex = searchPattern ?: return null
        val cur = currentHit()?.let { searchHits[it] }
        if (cur != null) {
            command {
                val p = activeParagraph
                val m = regex.find(p.plainText(), cur.start)?.takeIf { it.range.first == cur.start } ?: return@command
                val caret = replaceHit(p, cur.start, cur.end, DocSearch.expand(replacement, m, searchOptions))
                selStart = caret; selEnd = caret
            }
        }
        return find()
    }

    /** Replace All: every match of the current search, one undo step; how many. */
    fun replaceAllHits(replacement: String): Int {
        val regex = searchPattern ?: return 0
        val hits = searchHits
        if (hits.isEmpty()) return 0
        command {
            val locs = document.locations()
            // From the last back, so the offsets before each stay right.
            for (h in hits.asReversed()) {
                val p = locs[h.para].paragraph
                val m = regex.find(p.plainText(), h.start)?.takeIf { it.range.first == h.start && it.range.last + 1 == h.end } ?: continue
                replaceHit(p, h.start, h.end, DocSearch.expand(replacement, m, searchOptions))
            }
            selStart = minOf(selStart, activeParagraph.plainText().length); selEnd = selStart
        }
        return hits.size
    }

    /** [start] until [end] of [p] becomes [text] in the found text's look — a tracked change while tracking; where it ends. */
    private fun replaceHit(p: Paragraph, start: Int, end: Int, text: String): Int {
        val fmt = ParagraphOps.formatAt(p, start).also { it.deleted = false }
        return if (trackChanges) ParagraphOps.trackedReplace(p, start, end, text, fmt, reviewerName, System.currentTimeMillis())
        else { ParagraphOps.replace(p, start, end, text, fmt); start + text.length }
    }

    // The old single-shot calls (plain text, case only), kept for their callers.
    fun findNext(query: String, matchCase: Boolean): Boolean { setSearch(query, SearchOptions(matchCase)); return find() != null }
    fun replaceCurrentThenFind(query: String, replacement: String, matchCase: Boolean): Boolean { setSearch(query, SearchOptions(matchCase)); return replaceCurrent(replacement) != null }
    fun replaceAll(query: String, replacement: String, matchCase: Boolean): Int { setSearch(query, SearchOptions(matchCase)); return replaceAllHits(replacement) }

    // --- Go To (Word's Find and Replace ▸ Go To) ----------------------------------------------

    /** What Go To moves between. */
    enum class GoTo { PAGE, HEADING, TABLE, COMMENT, BOOKMARK }

    /**
     * Go To page [page] (1-based, as the canvas lays the pages out): its first paragraph's start; false when the
     * document has no such page yet.
     */
    fun goToPage(page: Int): Boolean {
        val n = document.locations().size
        for (i in 0 until n) {
            val pg = pageOfLocation(i) ?: continue
            if (pg >= page) {
                if (pg > page && i > 0) return false
                focusParagraph(i); return true
            }
        }
        return false
    }

    /** The page the caret is on, as the canvas laid it out (1 before it has). */
    val caretPage: Int get() = pageOfLocation(active) ?: 1

    /** Go To the next (or, [back], previous) heading or table from the caret; false when there is none that way. */
    fun goToNext(kind: GoTo, back: Boolean = false): Boolean {
        val locs = document.locations()
        fun isTarget(i: Int): Boolean = when (kind) {
            GoTo.HEADING -> locs[i].paragraph.format.style.let { it == ParagraphStyle.HEADING1 || it == ParagraphStyle.HEADING2 || it == ParagraphStyle.HEADING3 }
            // A table's first cell paragraph (the first after something that is not that table's).
            GoTo.TABLE -> (locs[i] as? CellLoc)?.let { c -> i == 0 || (locs[i - 1] as? CellLoc)?.let { prev -> tableOf(prev.cell) !== tableOf(c.cell) } ?: true } ?: false
            else -> false
        }
        val order = if (back) (active - 1 downTo 0) else (active + 1 until locs.size)
        val i = order.firstOrNull(::isTarget) ?: return false
        focusParagraph(i)
        return true
    }

    private fun tableOf(cell: com.therealityteam.aeropages.model.TableCell) = document.blocks.firstOrNull { b -> b is TableBlock && b.table.rows.any { r -> r.cells.any { it === cell } } }

    // --- statistics ------------------------------------------------------------    // --- statistics ------------------------------------------------------------

    /** Word's word count of the document: its East Asian characters and its other words ([WordCounts]). */
    fun wordCounts(): com.therealityteam.aeropages.model.WordCounts.Counts =
        document.paragraphs().fold(com.therealityteam.aeropages.model.WordCounts.NONE) { acc, p -> acc + com.therealityteam.aeropages.model.WordCounts.of(p.plainText()) }

    fun wordCount(): Int = wordCounts().words

    /** The words in the selection (0 with none), as Word's status bar shows them beside the document's ("12/345"). */
    fun selectedWordCount(): Int = com.therealityteam.aeropages.model.WordCounts.of(selectedText()).words

    /** Pages as the canvas last laid them out (the status bar's "Page 1 of 3"). */
    var pageTotal by mutableIntStateOf(1)

    fun charCount(): Int = document.paragraphs().sumOf { it.plainText().length }

    /** Characters excluding whitespace (Word's 字符数(不计空格)). */
    fun charCountNoSpaces(): Int = document.paragraphs().sumOf { p -> p.plainText().count { !it.isWhitespace() } }

    /** Word's 中文字符和朝鲜语单词: the East Asian characters, full-width punctuation among them. */
    fun cjkCount(): Int = wordCounts().asian
}

/** The heading styles a link to a heading can go to (Title is not one). */
private val LINKABLE_HEADINGS = setOf(ParagraphStyle.HEADING1, ParagraphStyle.HEADING2, ParagraphStyle.HEADING3)
