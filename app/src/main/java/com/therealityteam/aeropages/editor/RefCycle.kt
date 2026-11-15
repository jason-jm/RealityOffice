package com.therealityteam.aeropages.editor

/**
 * Excel's F4 while a formula is typed: the cell reference at the caret — both ends of a range — goes round
 * A1 → $A$1 → A$1 → $A1 → A1. References inside quoted text are left alone.
 */
object RefCycle {

    // A reference not glued to a name before it (letters, digits, _, ., $) nor followed by more of one or by "(" (LOG10().
    private val REF = Regex("(?<![A-Za-z0-9_.$])(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]{1,7})(?![A-Za-z0-9_(])")

    /** The formula with the reference at [caret] turned, and the caret after it; null when the caret is at no reference. */
    fun cycle(text: String, caret: Int): Pair<String, Int>? {
        if (!text.startsWith("=")) return null
        val refs = REF.findAll(text).filter { !inQuotes(text, it.range.first) }.toList()
        val hit = refs.firstOrNull { caret in it.range.first..it.range.last + 1 } ?: return null
        val group = mutableListOf(hit)
        refs.firstOrNull { text.getOrNull(hit.range.last + 1) == ':' && it.range.first == hit.range.last + 2 }?.let { group.add(it) }
        refs.firstOrNull { text.getOrNull(hit.range.first - 1) == ':' && it.range.last == hit.range.first - 2 }?.let { group.add(0, it) }
        val (colAbs, rowAbs) = when (hit.groupValues[1].isNotEmpty() to hit.groupValues[3].isNotEmpty()) {
            false to false -> true to true
            true to true -> false to true
            false to true -> true to false
            else -> false to false
        }
        val out = StringBuilder(text)
        var end = 0
        for (m in group.asReversed()) {
            val ref = (if (colAbs) "$" else "") + m.groupValues[2] + (if (rowAbs) "$" else "") + m.groupValues[4]
            out.replace(m.range.first, m.range.last + 1, ref)
        }
        // The caret after the last reference of the group, in the new text.
        var shift = 0
        for (m in group) {
            val ref = (if (colAbs) "$" else "") + m.groupValues[2] + (if (rowAbs) "$" else "") + m.groupValues[4]
            end = m.range.first + shift + ref.length
            shift += ref.length - m.value.length
        }
        return out.toString() to end
    }

    private fun inQuotes(text: String, at: Int): Boolean = text.substring(0, at).count { it == '"' } % 2 == 1
}
