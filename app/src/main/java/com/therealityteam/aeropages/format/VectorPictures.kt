package com.therealityteam.aeropages.format

import android.graphics.Bitmap
import com.therealityteam.aeropages.format.metafile.MetafilePainter
import com.therealityteam.aeropages.format.metafile.Metafiles
import com.therealityteam.aeropages.format.svg.SvgPainter
import com.therealityteam.aeropages.format.svg.SvgReader

/**
 * Pictures drawn rather than decoded — SVG, and Windows' EMF and WMF — rendered to a bitmap where a
 * picture shows as one (the editors, the thumbnails, the PDF), at the size it is shown.
 */
internal object VectorPictures {

    fun isVector(data: ByteArray): Boolean = SvgReader.isSvg(data) || Metafiles.isMetafile(data)

    /** [data] as large as fits [maxW] × [maxH] pixels (or, [cover], as small as covers them); null when it is none of them or does not draw. */
    fun rasterize(data: ByteArray, maxW: Float, maxH: Float, cover: Boolean = false): Bitmap? =
        if (SvgReader.isSvg(data)) SvgPainter.rasterize(data, maxW, maxH, cover) else MetafilePainter.rasterize(data, maxW, maxH, cover)
}
