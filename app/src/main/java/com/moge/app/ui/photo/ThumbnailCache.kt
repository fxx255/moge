package com.moge.app.ui.photo

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.unit.IntSize
import com.moge.app.data.image.decodePhotoThumbnail
import com.moge.app.data.image.QuestionPhotoFiles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 缩略图的三种状态：解码中、可显示、读不出来（文件丢了或损坏）。 */
sealed interface Thumbnail {
    data object Loading : Thumbnail
    data class Ready(val bitmap: Bitmap) : Thumbnail
    data object Failed : Thumbnail
}

/**
 * 题册封面与题目缩略图共用的内存缓存：瀑布流来回滑动、解题页卡片滑出又滑回时不再重复解码原图。
 *
 * 键里带 [PhotoEdits.revision]：裁剪 / 旋转原地改写照片后键就变了，不会显示旧图；
 * 也不必在主线程上 stat 文件。
 */
object ThumbnailCache {
    private val cache = object : LruCache<String, Bitmap>(maxBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private fun maxBytes(): Int = (Runtime.getRuntime().maxMemory() / 16).coerceIn(4L shl 20, 48L shl 20).toInt()

    private fun keyOf(path: String, maxPx: Int, revision: Long, cropSize: IntSize, preferProcessed: Boolean, processedRevision: Long) =
        "$revision|$preferProcessed:$processedRevision|$maxPx|${cropSize.width}x${cropSize.height}|$path"

    fun peek(
        path: String, maxPx: Int, revision: Long = PhotoEdits.revision.value, cropSize: IntSize = IntSize.Zero,
        preferProcessed: Boolean = false,
        processedRevision: Long = if (preferProcessed) QuestionPhotoFiles.revision(path) else 0L,
    ): Bitmap? = cache.get(keyOf(path, maxPx, revision, cropSize, preferProcessed, processedRevision))

    /** 在 IO 线程解码并放进缓存；读不出来返回 null，失败不缓存（文件可能稍后才写完）。 */
    suspend fun load(
        path: String, maxPx: Int, revision: Long = PhotoEdits.revision.value, cropSize: IntSize = IntSize.Zero,
        preferProcessed: Boolean = false,
        processedRevision: Long = if (preferProcessed) QuestionPhotoFiles.revision(path) else 0L,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val key = keyOf(path, maxPx, revision, cropSize, preferProcessed, processedRevision)
        cache.get(key) ?: run {
            val display = if (preferProcessed) QuestionPhotoFiles.displayPath(path) else path
            if (cropSize.width > 0 && cropSize.height > 0)
                decodePhotoThumbnail(display, cropSize.width, cropSize.height) else decodeUprightPhoto(display, maxPx)
        }?.also { cache.put(key, it) }
    }

    internal fun clear() = cache.evictAll()
}

/** 读取缩略图：命中缓存时首帧就是 [Thumbnail.Ready]，没有「加载中」的闪烁。 */
@Composable
fun rememberThumbnail(path: String, maxPx: Int, cropSize: IntSize = IntSize.Zero, preferProcessed: Boolean = false): Thumbnail =
    key(path, maxPx, cropSize, preferProcessed) {
        val revision by PhotoEdits.revision.collectAsStateWithLifecycle()
        val processedRevision = if (preferProcessed) rememberProcessedPhotoRevision(path) else 0L
        val cached = ThumbnailCache.peek(path, maxPx, revision, cropSize, preferProcessed, processedRevision)
        val state by produceState(cached?.let(Thumbnail::Ready) ?: Thumbnail.Loading, revision, processedRevision) {
            // Keep the current photo visible during a background replacement; key() resets it on a different source.
            if (cached != null) value = Thumbnail.Ready(cached)
            else {
                val loaded = ThumbnailCache.load(path, maxPx, revision, cropSize, preferProcessed, processedRevision)
                if (loaded != null) value = Thumbnail.Ready(loaded)
                else if (value !is Thumbnail.Ready) value = Thumbnail.Failed
            }
        }
        state
    }

@Composable
internal fun rememberProcessedPhotoRevision(path: String): Long {
    val updates = remember(path) { QuestionPhotoFiles.observeRevision(path) }
    return updates.collectAsStateWithLifecycle(initialValue = QuestionPhotoFiles.revision(path)).value
}
