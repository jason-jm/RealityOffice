package com.therealityteam.aeropages.format

/** Which Aero Desk module a file belongs to, decided by extension. */
enum class DeskKind { PAGES, SLIDES, SHEETS }

object DeskFiles {
    val SLIDES_EXT = setOf("pptx", "ppt", "pptm", "potx", "potm", "pot", "ppsx", "ppsm", "pps", "odp", "otp",
        // WPS Presentation's own (a PowerPoint 97 file inside) and its template.
        "dps", "dpt")
    val SHEETS_EXT = setOf("xlsx", "xls", "xlsm", "xltx", "xltm", "xlt", "xlsb", "ods", "ots", "csv",
        // WPS Spreadsheets' own (an Excel 97 workbook inside) and its template.
        "et", "ett")
    // Everything else openable (docx/doc/rtf/txt/md/html/csv/odt…) is a Pages document.
    val PAGES_EXT = DocumentRepository.OPENABLE_EXTENSIONS

    /** All extensions any module can open. */
    val OPENABLE: Set<String> = PAGES_EXT + SLIDES_EXT + SHEETS_EXT

    fun kindOf(path: String): DeskKind {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in SLIDES_EXT -> DeskKind.SLIDES
            in SHEETS_EXT -> DeskKind.SHEETS
            else -> DeskKind.PAGES
        }
    }

    /** MIME types of documents the editors can read, including common provider aliases. */
    val documentMimeTypes: Map<String, String> = mapOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-word.document.macroenabled.12" to "docm",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template" to "dotx",
        "application/vnd.ms-word.template.macroenabled.12" to "dotm",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-excel.sheet.macroenabled.12" to "xlsm",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.template" to "xltx",
        "application/vnd.ms-excel.template.macroenabled.12" to "xltm",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.ms-powerpoint.presentation.macroenabled.12" to "pptm",
        "application/vnd.openxmlformats-officedocument.presentationml.template" to "potx",
        "application/vnd.ms-powerpoint.template.macroenabled.12" to "potm",
        "application/vnd.openxmlformats-officedocument.presentationml.slideshow" to "ppsx",
        "application/vnd.ms-powerpoint.slideshow.macroenabled.12" to "ppsm",
        "application/msword" to "doc",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.oasis.opendocument.text" to "odt",
        "application/vnd.oasis.opendocument.spreadsheet" to "ods",
        "application/vnd.oasis.opendocument.presentation" to "odp",
        "application/vnd.oasis.opendocument.text-template" to "ott",
        "application/vnd.oasis.opendocument.spreadsheet-template" to "ots",
        "application/vnd.oasis.opendocument.presentation-template" to "otp",
        "application/wps-office.wps" to "wps",
        "application/kswps" to "wps",
        "application/vnd.ms-works" to "wps",
        "application/wps-office.wpt" to "wpt",
        "application/wps-office.et" to "et",
        "application/kset" to "et",
        "application/wps-office.ett" to "ett",
        "application/wps-office.dps" to "dps",
        "application/ksdps" to "dps",
        "application/wps-office.dpt" to "dpt",
        "text/csv" to "csv",
        "text/comma-separated-values" to "csv",
        "text/plain" to "txt",
        "application/rtf" to "rtf",
        "text/rtf" to "rtf",
        "text/markdown" to "md",
        "text/x-markdown" to "md",
        "text/html" to "html",
    )

    /** A suffix for a provider document that has a MIME type but no usable extension. */
    fun extensionForMime(mime: String?): String? = documentMimeTypes[mime?.substringBefore(';')?.trim()?.lowercase()]

    /** [kindOf], but null when the extension isn't openable by any module. */
    fun kindOfOrNull(path: String): DeskKind? {
        val ext = path.substringAfterLast('.', "").lowercase()
        return if (ext in OPENABLE) kindOf(path) else null
    }
}
