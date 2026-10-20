package com.moge.app.ui.photo

import android.graphics.Bitmap
import android.graphics.Matrix
import com.moge.app.data.image.decodePhotoBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Shared invalidation for thumbnails and viewers after an app-owned photo is edited. */
object PhotoEdits {
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    internal fun changed() { changes.update { it + 1 } }
}

fun decodeUprightPhoto(path: String, maxDimension: Int = 4096): Bitmap? =
    decodePhotoBitmap(path, maxDimension)

fun rotatePhotoBitmap(source: Bitmap, quarterTurns: Int): Bitmap {
    val turns = Math.floorMod(quarterTurns, 4)
    if (turns == 0) return source
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height,
        Matrix().apply { postRotate(turns * 90f) }, false)
}

/** Write beside the original, then replace atomically. Failure must never truncate the original. */
fun savePhotoBitmap(path: String, bitmap: Bitmap) {
    com.moge.app.data.image.writePhotoBitmap(path, bitmap)
    PhotoEdits.changed()
}

fun rotatePhotoAndSave(path: String, quarterTurns: Int = -1) {
    val rotated = decodePhotoBitmap(path, quarterTurns = quarterTurns, preserveResolution = true)
        ?: error("无法以原始分辨率读取照片，图片可能过大或格式不受支持")
    try { savePhotoBitmap(path, rotated) } finally { rotated.recycle() }
}
