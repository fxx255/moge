package com.moge.app.data.image

import android.graphics.Bitmap
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Lossless edits, atomically replacing the original only after every output byte is saved. */
internal fun writePhotoBitmap(path: String, bitmap: Bitmap) {
    val original = File(path)
    val temporary = File(original.parentFile, ".${original.name}.${UUID.randomUUID()}.editing")
    try {
        temporary.outputStream().use { output ->
            // JPEG quality 100 still discards detail and introduces ringing on
            // glyph edges. Decoders identify PNG from its header, not the path.
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法保存照片" }
            output.fd.sync()
        }
        Files.move(temporary.toPath(), original.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { temporary.delete() }
}
