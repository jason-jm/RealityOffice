package com.therealityteam.aeropages.format.pptx

import com.therealityteam.aeropages.format.PictureType
import com.therealityteam.aeropages.format.svg.SvgPainter
import com.therealityteam.aeropages.format.svg.SvgReader
import java.io.ByteArrayOutputStream

/**
 * A picture as Office saves one in pptx, docx and xlsx: the part its blip embeds — the picture
 * itself, or for an SVG a PNG drawn from it (what Office before 2016 and other readers show) — and,
 * for an SVG, the SVG itself beside it (`asvg:svgBlip` in the blip's extension list), which
 * Office draws. Where the PNG cannot be drawn, the SVG is the blip's own part.
 */
internal class OoxmlPicture private constructor(val main: ByteArray, val mainExt: String, val svg: ByteArray?) {

    companion object {
        /** The blip extension Office keeps an SVG picture's original in. */
        private const val SVG_EXT = "{96DAC541-7B7A-43D3-8B79-37D633B846F1}"

        /** The long side of the PNG an SVG is saved beside. */
        private const val STAND_IN = 1024f

        fun of(data: ByteArray, mime: String): OoxmlPicture {
            if (!PictureType.isSvg(mime) && !SvgReader.isSvg(data)) return OoxmlPicture(data, PictureType.extOf(mime), null)
            val png = runCatching {
                SvgPainter.rasterize(data, STAND_IN, STAND_IN)?.let { bmp ->
                    ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                }
            }.getOrNull()
            return if (png != null && png.isNotEmpty()) OoxmlPicture(png, "png", data) else OoxmlPicture(data, "svg", null)
        }

        /** The `a:blip` embedding [embed], with the SVG original [svgEmbed] names when there is one. */
        fun blip(embed: String, svgEmbed: String?): String =
            if (svgEmbed == null) """<a:blip r:embed="$embed"/>"""
            else """<a:blip r:embed="$embed"><a:extLst><a:ext uri="$SVG_EXT"><asvg:svgBlip xmlns:asvg="http://schemas.microsoft.com/office/drawing/2016/SVG/main" r:embed="$svgEmbed"/></a:ext></a:extLst></a:blip>"""

        /** Content-type defaults for every picture extension a package may hold. */
        const val CONTENT_TYPES = """<Default Extension="png" ContentType="image/png"/><Default Extension="jpeg" ContentType="image/jpeg"/>""" +
            """<Default Extension="jpg" ContentType="image/jpeg"/><Default Extension="gif" ContentType="image/gif"/>""" +
            """<Default Extension="bmp" ContentType="image/bmp"/><Default Extension="tiff" ContentType="image/tiff"/>""" +
            """<Default Extension="webp" ContentType="image/webp"/><Default Extension="emf" ContentType="image/x-emf"/>""" +
            """<Default Extension="wmf" ContentType="image/x-wmf"/><Default Extension="svg" ContentType="image/svg+xml"/>"""
    }
}
