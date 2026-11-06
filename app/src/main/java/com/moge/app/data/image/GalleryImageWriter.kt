package com.moge.app.data.image

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val GALLERY_ALBUM = "Moge"
internal val galleryAlbumPath: String get() = "${Environment.DIRECTORY_PICTURES}/$GALLERY_ALBUM"

/** Saves the entire sequence, rolling back ALL new entries if any page fails or is cancelled. */
internal suspend fun writeGalleryImages(
    context: Context,
    paths: List<String>,
    onProgress: suspend (saved: Int, total: Int) -> Unit = { _, _ -> },
): String {
    require(paths.isNotEmpty()) { "没有可保存的图片" }
    val entries = mutableListOf<GalleryEntry>()
    try {
        return withContext(Dispatchers.IO) {
            val operation = currentCoroutineContext()
            paths.forEachIndexed { index, path ->
                operation.ensureActive()
                val entry = createGalleryEntry(context, path)
                entries += entry
                entry.copy { operation.ensureActive() }
                withContext(Dispatchers.Main.immediate) { onProgress(index + 1, paths.size) }
            }
            operation.ensureActive()
            entries.forEach { operation.ensureActive(); it.publish() }
            galleryAlbumPath
        }
    } catch (error: Throwable) {
        withContext(NonCancellable + Dispatchers.IO) { entries.forEach { runCatching { it.delete() } } }
        throw error
    }
}

internal class GalleryEntry(
    val location: String,
    val copy: (() -> Unit) -> Unit,
    val publish: () -> Unit,
    val delete: () -> Unit,
)

private fun InputStream.copyChecked(output: OutputStream, checkActive: () -> Unit) {
    val buffer = ByteArray(64 * 1024)
    while (true) {
        checkActive()
        val count = read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
    }
}

internal fun createGalleryEntry(context: Context, path: String): GalleryEntry {
    val source = File(path)
    check(source.isFile && source.length() > 0) { "图片文件不存在" }
    val mime = galleryImageMimeType(path)
    val extension = if (mime == "image/png") "png" else "jpg"
    val displayName = "moge_${System.currentTimeMillis()}_${UUID.randomUUID()}.$extension"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$GALLERY_ALBUM")
            // 写完之前对其他应用不可见，写一半被杀也不会在相册里留下坏图
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法写入相册")
        return GalleryEntry(
            location = uri.toString(),
            copy = { checkActive ->
                resolver.openOutputStream(uri, "w")?.use { output ->
                    source.inputStream().use { it.copyChecked(output, checkActive) }
                } ?: error("无法写入相册")
            },
            publish = {
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) > 0) {
                    "无法完成相册保存"
                }
            },
            delete = { resolver.delete(uri, null, null); Unit },
        )
    } else {
        @Suppress("DEPRECATION")
        val album = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), GALLERY_ALBUM)
        check(album.isDirectory || album.mkdirs()) { "无法创建相册目录" }
        val target = File(album, displayName)
        val discarded = AtomicBoolean(false)
        return GalleryEntry(
            location = target.absolutePath,
            copy = { checkActive ->
                check(target.createNewFile()) { "无法创建相册图片" }
                target.outputStream().use { output -> source.inputStream().use { it.copyChecked(output, checkActive) } }
            },
            publish = {
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime)) { _, uri ->
                    if (discarded.get() && uri != null) context.contentResolver.delete(uri, null, null)
                }
            },
            delete = {
                discarded.set(true)
                target.delete()
                // A published legacy page may already have a MediaStore row when a later page fails.
                @Suppress("DEPRECATION")
                context.contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Images.Media.DATA} = ?", arrayOf(target.absolutePath))
                Unit
            },
        )
    }
}

internal fun galleryImageMimeType(path: String): String {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    return bounds.outMimeType?.takeIf { it.startsWith("image/") }
        ?: if (path.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
}
