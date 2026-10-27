package com.moge.app.ui.markdown

import android.graphics.Bitmap
import android.util.LruCache
import com.moge.app.ui.photo.ThumbnailCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class FigureImageKey(
    val path: String,
    val dark: Boolean,
    val revision: Long,
    val resolver: FigurePathResolver,
)

internal data class FigureGeometry(val width: Int, val height: Int, val scrolls: Boolean)

internal class LoadedFigure(val path: String, val bitmap: Bitmap, val geometry: FigureGeometry)

/** Bitmaps share the bounded photo cache; small geometry entries survive bitmap eviction. */
internal object FigureImageCache {
    private data class Metadata(val path: String, val geometry: FigureGeometry)
    private val metadata = LruCache<FigureImageKey, Metadata>(256)

    fun geometry(key: FigureImageKey): FigureGeometry? = metadata.get(key)?.geometry

    /** Memory only: a returning page can use its final image and height on the first frame. */
    fun peek(key: FigureImageKey): LoadedFigure? {
        val entry = metadata.get(key) ?: return null
        val bitmap = ThumbnailCache.peek(entry.path, DECODE_MAX_PX, key.revision) ?: return null
        return LoadedFigure(entry.path, bitmap, entry.geometry)
    }

    suspend fun load(key: FigureImageKey): LoadedFigure? = withContext(Dispatchers.IO) {
        val path = key.resolver.resolve(key.path, key.dark) ?: return@withContext null
        val bitmap = ThumbnailCache.load(path, DECODE_MAX_PX, key.revision) ?: return@withContext null
        val geometry = FigureGeometry(bitmap.width, bitmap.height,
            shouldScrollGeneratedImage(File(path), bitmap.width, bitmap.height))
        metadata.put(key, Metadata(path, geometry))
        LoadedFigure(path, bitmap, geometry)
    }

    internal fun clear() = metadata.evictAll()

    private const val DECODE_MAX_PX = 3200
}
