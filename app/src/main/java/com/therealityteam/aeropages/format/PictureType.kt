package com.therealityteam.aeropages.format

import com.therealityteam.aeropages.format.svg.SvgReader

/**
 * What kind of picture some bytes are — by what the bytes say (PNG, JPEG, GIF, BMP, WebP, TIFF,
 * EMF, WMF, SVG), by the name they came under only when the bytes say nothing — and the file
 * extension a picture of a type is saved under. The readers label pictures with it, so an SVG
 * saved as "image3.png" is still known for what it is.
 */
object PictureType {

    fun mimeOf(data: ByteArray, name: String? = null): String {
        fun u8(i: Int) = if (i < data.size) data[i].toInt() and 0xFF else -1
        fun ascii(at: Int, s: String) = s.indices.all { u8(at + it) == s[it].code }
        return when {
            u8(0) == 0x89 && ascii(1, "PNG") -> "image/png"
            u8(0) == 0xFF && u8(1) == 0xD8 && u8(2) == 0xFF -> "image/jpeg"
            ascii(0, "GIF8") -> "image/gif"
            ascii(0, "BM") && data.size > 26 -> "image/bmp"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            (ascii(0, "II") && u8(2) == 0x2A && u8(3) == 0) || (ascii(0, "MM") && u8(2) == 0 && u8(3) == 0x2A) -> "image/tiff"
            u8(0) == 1 && u8(1) == 0 && u8(2) == 0 && u8(3) == 0 && ascii(40, " EMF") -> "image/x-emf"
            (u8(0) == 0xD7 && u8(1) == 0xCD && u8(2) == 0xC6 && u8(3) == 0x9A) || ((u8(0) == 1 || u8(0) == 2) && u8(1) == 0 && u8(2) == 9 && u8(3) == 0) -> "image/x-wmf"
            SvgReader.isSvg(data) -> "image/svg+xml"
            else -> when (name?.substringAfterLast('.', "")?.lowercase()) {
                "jpg", "jpeg", "jpe" -> "image/jpeg"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                "webp" -> "image/webp"
                "tif", "tiff" -> "image/tiff"
                "emf" -> "image/x-emf"
                "wmf" -> "image/x-wmf"
                "svg", "svgz" -> "image/svg+xml"
                else -> "image/png"
            }
        }
    }

    /** The extension a picture of [mime] is saved under. */
    fun extOf(mime: String): String {
        val m = mime.lowercase()
        return when {
            "jpeg" in m || "jpg" in m -> "jpeg"
            "gif" in m -> "gif"
            "bmp" in m -> "bmp"
            "webp" in m -> "webp"
            "tif" in m -> "tiff"
            "emf" in m -> "emf"
            "wmf" in m -> "wmf"
            "svg" in m -> "svg"
            else -> "png"
        }
    }

    fun isSvg(mime: String?) = mime?.contains("svg", ignoreCase = true) == true
}
