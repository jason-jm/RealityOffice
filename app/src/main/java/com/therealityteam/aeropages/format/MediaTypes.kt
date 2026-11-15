package com.therealityteam.aeropages.format

/** Sound and video files: their media types by extension, and an extension for a type. */
object MediaTypes {

    private val BY_EXT = linkedMapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "wmv" to "video/x-ms-wmv", "avi" to "video/x-msvideo",
        "mpg" to "video/mpeg", "mpeg" to "video/mpeg", "mkv" to "video/x-matroska", "webm" to "video/webm", "ogv" to "video/ogg",
        "asf" to "video/x-ms-asf", "3gp" to "video/3gpp",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "wav" to "audio/wav", "wma" to "audio/x-ms-wma", "aac" to "audio/aac",
        "ogg" to "audio/ogg", "oga" to "audio/ogg", "flac" to "audio/flac", "mid" to "audio/midi", "midi" to "audio/midi",
        "aif" to "audio/aiff", "aiff" to "audio/aiff",
    )

    /** The media type of a sound or video file named [name], or null when it is neither. */
    fun mimeOf(name: String): String? = BY_EXT[name.substringAfterLast('.', "").lowercase()]

    /** Whether [name] is a sound or video file. */
    fun isMedia(name: String): Boolean = mimeOf(name) != null

    /** [name]'s kind (Insert → Video / Audio) and media type, or null for a file of neither. */
    fun of(name: String): Pair<com.therealityteam.aeropages.model.SlideMedia.Kind, String>? {
        val mime = mimeOf(name) ?: return null
        return (if (mime.startsWith("video/")) com.therealityteam.aeropages.model.SlideMedia.Kind.VIDEO else com.therealityteam.aeropages.model.SlideMedia.Kind.AUDIO) to mime
    }

    /** The largest file Insert → Video / Audio embeds (the slide keeps it in memory and in the file). */
    const val MAX_BYTES = 150L * 1024 * 1024

    /** An extension for [mime] (the first one listed for it), "bin" for a type not listed. */
    fun extOf(mime: String): String = BY_EXT.entries.firstOrNull { it.value == mime }?.key ?: "bin"

    /** The type older LibreOffice declares for any sound or video it plays (newer declares the file's own). */
    private const val LIBREOFFICE_MEDIA = "application/vnd.sun.star.media"

    /**
     * The media type of the file an OpenDocument plug-in (`draw:plugin`) plays — [href], declared as
     * [declared] (its `draw:mime-type`) — or null when it plays no sound or video (Flash, an applet …):
     * the declared type when it is a sound's or a video's, else the file's by its name, else a video's
     * for LibreOffice's catch-all type.
     */
    fun ofPlugin(href: String, declared: String?): String? {
        val d = declared?.trim()?.lowercase()
        if (d != null && (d.startsWith("video/") || d.startsWith("audio/"))) return d
        return mimeOf(href.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('\\'))
            ?: "video/mp4".takeIf { d == LIBREOFFICE_MEDIA }
    }
}
