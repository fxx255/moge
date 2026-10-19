package com.moge.app.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Save beside the original and replace atomically, preserving it on any processing/write failure. */
internal fun writePhotoBitmap(path: String, bitmap: Bitmap) {
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
    } finally { temporary.delete() }
}
