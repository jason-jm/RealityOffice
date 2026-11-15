package com.therealityteam.aeropages.model

/**
 * A file's properties, as File → Info shows them in Word, Excel and PowerPoint: its title, subject, author, keywords
 * and comments, who saved it last, when it was made and last saved, and how many times it has been saved (OOXML's
 * `docProps/core.xml`, ODF's `meta.xml`). Dates are epoch milliseconds.
 */
data class DocProps(
    val title: String = "",
    val subject: String = "",
    val author: String = "",
    val keywords: String = "",
    val description: String = "",
    val lastModifiedBy: String = "",
    val created: Long? = null,
    val modified: Long? = null,
    val revision: Int? = null,
) {
    /** These properties as a save leaves them at [now] by [by] (who saved it last, when; one save more; made now if never). */
    fun saved(now: Long, by: String?): DocProps =
        copy(created = created ?: now, modified = now, revision = (revision ?: 0) + 1, lastModifiedBy = by?.takeIf { it.isNotBlank() } ?: lastModifiedBy)
}

/** A document, workbook or presentation: what has [DocProps]. */
interface HasProps {
    var props: DocProps
}
