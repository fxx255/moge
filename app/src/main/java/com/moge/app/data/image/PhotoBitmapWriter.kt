package com.moge.app.data.image

import android.graphics.Bitmap
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.Deflater

/** Lossless edits, atomically replacing the original only after every output byte is saved. */
internal fun writePhotoBitmap(path: String, bitmap: Bitmap) {
    writePhotoFileAtomically(path) { temporary ->
        temporary.outputStream().use { output ->
            // JPEG quality 100 still discards detail and introduces ringing on
            // glyph edges. Decoders identify PNG from its header, not the path.
            if (bitmap.hasAlpha()) {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法保存照片" }
            } else {
                // A fast deflate stream retains every RGB pixel, with row-sized buffers.
                // No OpenCV startup or full-image native copy is needed for a crop save.
                StreamingPngWriter(output, bitmap.width, bitmap.height, Deflater.BEST_SPEED).use {
                    it.append(bitmap)
                    it.finish()
                }
            }
        }
    }
}

/** Encoders share durability and replacement rules without depending on each other. */
internal fun writePhotoFileAtomically(path: String, encode: (File) -> Unit) {
    val original = File(path)
    // Native file encoders identify their format from the temporary extension.
    val temporary = File(original.parentFile, ".${original.name}.${UUID.randomUUID()}.editing.png")
    try {
        encode(temporary)
        check(temporary.isFile && temporary.length() > 0) { "无法保存照片" }
        java.io.FileOutputStream(temporary, true).use { it.fd.sync() }
        Files.move(temporary.toPath(), original.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { temporary.delete() }
}
