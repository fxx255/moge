package com.moge.app.data.image

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

internal data class GallerySaveResult(val album: String, val alreadySaved: Boolean)

/** Durable content receipts. Only completed, still-existing gallery copies suppress a save. */
internal class GallerySaveRepository(context: Context) {
    private val context = context.applicationContext
    private val receipts = this.context.getSharedPreferences("gallery_save_receipts_v1", Context.MODE_PRIVATE)

    /** Blocking IO; serialize lookup + publish + receipt across all viewer instances. */
    fun save(path: String): GallerySaveResult = synchronized(lock) {
        val source = File(path)
        check(source.isFile && source.length() > 0) { "图片文件不存在" }
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        val key = digest.digest().joinToString("") { "%02x".format(it) }
        val album = galleryAlbumPath
        val previous = receipts.getString(key, null)
        if (previous != null && exists(previous)) return@synchronized GallerySaveResult(album, alreadySaved = true)
        val entry = createGalleryEntry(context, path)
        try {
            entry.copy { }
            entry.publish()
            check(receipts.edit().putString(key, entry.location).commit()) { "无法记录相册保存状态，请重试" }
        } catch (failure: Throwable) {
            runCatching { entry.delete() }
            throw failure
        }
        GallerySaveResult(album, alreadySaved = false)
    }

    private fun exists(location: String): Boolean = runCatching {
        if (location.startsWith("content://")) {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(location), "r")?.use { it.length != 0L } == true
        } else File(location).let { it.isFile && it.length() > 0 }
    }.getOrDefault(false)

    private companion object { val lock = Any() }
}
