package com.therealityteam.aeropages.ui

/**
 * One axis of the sheet grid, in px: indices [from] until [until] laid end to end, the skipped
 * ones (hidden, filtered, folded) taking no room. Pointer positions map to rows / columns through
 * it, and selections to the rectangles drawn around them.
 */
internal object GridAxis {
    /**
     * The index whose span holds [pos] (px from where [from] starts). Before the start it is the
     * first index shown, past the end the last one; -1 when none is shown.
     */
    fun indexAt(pos: Float, from: Int, until: Int, skip: Set<Int>, size: (Int) -> Float): Int {
        var acc = 0f
        var last = -1
        for (i in from until until) {
            if (i in skip) continue
            if (last < 0 && pos < 0f) return i
            val s = size(i)
            if (pos < acc + s) return i
            acc += s
            last = i
        }
        return last
    }

    /** Where [index] starts, in px from where [from] starts. */
    fun offsetOf(index: Int, from: Int, skip: Set<Int>, size: (Int) -> Float): Float {
        var acc = 0f
        for (i in from until index) if (i !in skip) acc += size(i)
        return acc
    }
}
