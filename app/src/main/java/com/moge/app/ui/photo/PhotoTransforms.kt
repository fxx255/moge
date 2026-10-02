package com.moge.app.ui.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Shared invalidation for thumbnails and viewers after an app-owned photo is edited. */
object PhotoEdits {
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    internal fun changed() { changes.update { it + 1 } }
}

fun decodeUprightPhoto(path: String, maxDimension: Int = Int.MAX_VALUE): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
    val source = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return@runCatching null
    val exif = runCatching { ExifInterface(path) }.getOrNull()
    val transform = Matrix().apply {
        if (exif?.isFlipped == true) postScale(-1f, 1f)
        postRotate((exif?.rotationDegrees ?: 0).toFloat())
    }
    if (transform.isIdentity) source else Bitmap.createBitmap(source, 0, 0, source.width, source.height, transform, true)
}.getOrNull()

fun rotatePhotoBitmap(source: Bitmap, quarterTurns: Int): Bitmap {
    val turns = Math.floorMod(quarterTurns, 4)
    if (turns == 0) return source
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height,
        Matrix().apply { postRotate(turns * 90f) }, false)
}

/** Write beside the original, then replace atomically. Failure must never truncate the original. */
fun savePhotoBitmap(path: String, bitmap: Bitmap) {
    val original = File(path)
    val temporary = File(original.parentFile, ".${original.name}.${UUID.randomUUID()}.editing")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val format = if (bounds.outMimeType == "image/png" || bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
    try {
        temporary.outputStream().use { output ->
            check(bitmap.compress(format, 100, output)) { "无法保存照片" }
            output.fd.sync()
        }
        Files.move(temporary.toPath(), original.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        PhotoEdits.changed()
    } finally { temporary.delete() }
}

fun rotatePhotoAndSave(path: String, quarterTurns: Int = -1) {
    val source = decodeUprightPhoto(path) ?: error("无法读取照片")
    savePhotoBitmap(path, rotatePhotoBitmap(source, quarterTurns))
}
