package com.moge.app.ui.viewer

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import androidx.core.content.FileProvider
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

/** 与 AndroidManifest 里 FileProvider 的 authorities 保持一致。 */
internal fun fileProviderAuthority(context: Context): String = "${context.packageName}.files"

/** Android 10 起 MediaStore 写自己的图片不需要任何存储权限；更早的系统要 WRITE_EXTERNAL_STORAGE。 */
internal fun galleryNeedsLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

private const val GALLERY_ALBUM = "Moge"

/**
 * 把图片复制进系统相册（Pictures/Moge），返回给用户看的相册名。
 * 只做 IO，必须在后台线程调用；失败时抛出带中文原因的异常。
 */
internal fun saveImageToGallery(context: Context, path: String): String {
    val entry = createGalleryEntry(context, path)
    try {
        entry.copy { }
        entry.publish()
    } catch (error: Throwable) {
        entry.delete()
        throw error
    }
    return "${Environment.DIRECTORY_PICTURES}/$GALLERY_ALBUM"
}

/** Saves the entire sequence, rolling back ALL new entries if any page fails or is cancelled. */
internal suspend fun saveImagesToGallery(
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
            "${Environment.DIRECTORY_PICTURES}/$GALLERY_ALBUM"
        }
    } catch (error: Throwable) {
        withContext(NonCancellable + Dispatchers.IO) { entries.forEach { runCatching { it.delete() } } }
        throw error
    }
}

private class GalleryEntry(
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

private fun createGalleryEntry(context: Context, path: String): GalleryEntry {
    val source = File(path)
    check(source.isFile && source.length() > 0) { "图片文件不存在" }
    val mime = imageMimeType(path)
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
            copy = { checkActive ->
                resolver.openOutputStream(uri)?.use { output ->
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

/** 通过 FileProvider 把应用私有图片交给系统分享面板。 */
internal fun shareImage(context: Context, path: String) {
    shareImages(context, listOf(path))
}

/** Grants only these image URIs. Sequential PNG pages use ACTION_SEND_MULTIPLE. */
internal fun imageShareIntent(context: Context, paths: List<String>): Intent {
    require(paths.isNotEmpty()) { "没有可分享的图片" }
    val uris = ArrayList<Uri>(paths.map { path ->
        val file = File(path)
        check(file.isFile && file.length() > 0) { "图片文件不存在" }
        FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
    })
    return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = paths.map(::imageMimeType).distinct().singleOrNull() ?: "image/*"
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
        else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        clipData = ClipData.newRawUri("墨格解答", uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal fun shareImages(context: Context, paths: List<String>) {
    context.startActivity(
        Intent.createChooser(imageShareIntent(context, paths), "分享图片").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

internal fun imageMimeType(path: String): String {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    return bounds.outMimeType?.takeIf { it.startsWith("image/") }
        ?: if (path.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
}
