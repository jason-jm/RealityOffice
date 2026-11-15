package com.therealityteam.aeropages.editor

/**
 * Undo infrastructure shared by the three editors.
 *
 * History entries are **deltas**, not snapshots: each holds only the part of the model it
 * changes back (a cell range, one slide, a run of document blocks, …). Applying a delta
 * restores its stored state into the model and returns the *inverse* delta, which now holds
 * the state it just replaced. Undo and redo are therefore the same operation, and memory
 * per step is proportional to the edit rather than to the document.
 *
 * Deltas are positional (indices / keys, never object identity) and always install *copies*
 * of what they store, so a delta stays valid after it has been applied — the test-only
 * [VERIFY_UNDO] self-check relies on that to dry-run a delta against a throwaway copy.
 */
internal interface Delta<M> {
    /** Restore this delta's stored state into [model]; return the delta that undoes that. */
    fun apply(model: M): Delta<M>
}

/** Several deltas applied together as one undo step (inverse applies them in reverse order). */
internal class CompositeDelta<M>(private val parts: List<Delta<M>>) : Delta<M> {
    override fun apply(model: M): Delta<M> = CompositeDelta(parts.map { it.apply(model) }.asReversed())
    val size: Int get() = parts.size
    operator fun get(i: Int): Delta<M> = parts[i]
}

/**
 * A bounded undo/redo stack of [Delta]s plus the editor selection ([S]) to restore alongside
 * each step. [push] drops the redo branch, as every editor does.
 */
internal class History<M, S>(private val capacity: Int) {
    private class Step<M, S>(val delta: Delta<M>, val sel: S)

    private val undoStack = ArrayDeque<Step<M, S>>()
    private val redoStack = ArrayDeque<Step<M, S>>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    /** Number of undoable steps (tests). */
    val size: Int get() = undoStack.size
    /** The most recent undo step's delta (tests). */
    fun peek(): Delta<M>? = undoStack.lastOrNull()?.delta

    fun push(delta: Delta<M>, sel: S) {
        undoStack.addLast(Step(delta, sel))
        if (undoStack.size > capacity) undoStack.removeFirst()
        redoStack.clear()
    }

    /** Undo the latest step on [model]; returns the selection to restore, or null when empty. */
    fun undo(model: M, currentSel: S): S? {
        val step = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(Step(step.delta.apply(model), currentSel))
        return step.sel
    }

    /** Take back the latest step on [model] for good — nothing to redo (an entry refused as it was made). */
    fun dropLast(model: M): S? {
        val step = undoStack.removeLastOrNull() ?: return null
        step.delta.apply(model)
        return step.sel
    }

    /** Redo the latest undone step on [model]; returns the selection to restore, or null when empty. */
    fun redo(model: M, currentSel: S): S? {
        val step = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(Step(step.delta.apply(model), currentSel))
        return step.sel
    }

    fun clear() { undoStack.clear(); redoStack.clear() }
}

/**
 * Test-only self-check of the scoped undo deltas (enabled by the Gradle unit-test task via
 * `-Daero.verifyUndo=true`). While on, every mutation is dry-run backwards on a throwaway copy
 * of the model and compared with a full snapshot taken before the edit, so a delta whose scope
 * is too narrow fails loudly in the test suite instead of silently corrupting undo on device.
 */
internal val VERIFY_UNDO: Boolean = System.getProperty("aero.verifyUndo") == "true"

/**
 * Check that [delta] turns a copy of [current] back into [before] and forward again into
 * [current]; throws [IllegalStateException] naming [what] when it does not.
 */
internal fun <M> verifyDelta(what: String, delta: Delta<M>, before: M, current: M, copy: (M) -> M, same: (M, M) -> Boolean) {
    val probe = copy(current)
    val inverse = delta.apply(probe)
    check(same(probe, before)) { "undo delta for '$what' does not restore the pre-edit state (scope too narrow?)" }
    inverse.apply(probe)
    check(same(probe, current)) { "redo of '$what' does not reproduce the post-edit state" }
}
