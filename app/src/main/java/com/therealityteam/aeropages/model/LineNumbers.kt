package com.therealityteam.aeropages.model

/**
 * Word's line numbers counted down the laid-out pages — the canvas and the PDF count alike: every line of a body
 * paragraph in a numbered section, column by column, page by page (tables, text boxes, headers and footnotes are not
 * counted, nor a paragraph that suppresses them), starting again as the section says.
 */
object LineNumbers {

    /** Word's Auto distance from the text: a quarter inch. */
    const val AUTO_DISTANCE_PT = 18f

    /** The distance [n]'s numbers stand from the text. */
    fun distanceOf(n: LineNumbering): Float = n.distancePt ?: AUTO_DISTANCE_PT

    /** Counts lines; [page] at each page, then [line] for each counted line. */
    class Counter {
        private var count = 0
        private var section: PageSetup? = null
        private var numbering: LineNumbering? = null

        /** A page of the section [setup] begins: the count starts again where its numbering says. */
        fun page(setup: PageSetup) {
            val n = setup.lineNumbers
            val newSection = setup !== section
            if (n != null && (n.restart == LineNumberRestart.PAGE || (newSection && (n.restart == LineNumberRestart.SECTION || numbering == null)))) count = 0
            section = setup
            numbering = n
        }

        /** Whether this page's lines are numbered at all. */
        val numbered: Boolean get() = numbering != null

        /** The next counted line: the number it shows, or 0 when it shows none (not a multiple of Count by, or no numbering). */
        fun line(): Int {
            val n = numbering ?: return 0
            count++
            val number = n.start + count - 1
            return if (number % n.countBy.coerceAtLeast(1) == 0) number else 0
        }
    }
}
