package com.therealityteam.aeropages.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Off-main-thread, subsampled image decoding for document/slide pictures. A photo straight
 * from a camera is 12+ MP; decoding it full-size on the UI thread stalls the frame and pins
 * ~50 MB per picture, so images are decoded on IO with an `inSampleSize` that keeps the
 * longest side at or under [MAX_DIM] px, and cached by the (identity of the) encoded bytes.
 */
internal object Images {
    private const val MAX_DIM = 2048

    /** The long side an SVG, EMF or WMF picture is drawn at on screen: sharp at any size a page shows it, without a photo's memory. */
    private const val SVG_DIM = 1600f

    private val cache = object : LruCache<ByteArray, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: ByteArray, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun cached(bytes: ByteArray): ImageBitmap? = cache.get(bytes)

    suspend fun decode(bytes: ByteArray): ImageBitmap? {
        cache.get(bytes)?.let { return it }
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                if (com.therealityteam.aeropages.format.VectorPictures.isVector(bytes)) {
                    return@runCatching com.therealityteam.aeropages.format.VectorPictures.rasterize(bytes, SVG_DIM, SVG_DIM)?.asImageBitmap()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIM) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
        if (decoded != null) cache.put(bytes, decoded)
        return decoded
    }
}

/** The decoded picture for [bytes]: immediately from cache, otherwise null until the IO decode lands. */
@Composable
internal fun rememberDecodedImage(bytes: ByteArray): ImageBitmap? {
    val state by produceState(Images.cached(bytes), bytes) { value = Images.decode(bytes) }
    return state
}
