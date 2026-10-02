package com.moge.app.ui.photo

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID

/** Copies a picker result into app-owned storage so it remains available after URI access expires. */
fun importPhoto(
    context: Context,
    source: Uri,
    directory: File,
    filePrefix: String,
): File {
    directory.mkdirs()
    check(directory.isDirectory) { "无法创建照片目录" }

    val extension = context.contentResolver.getType(source)
        ?.let(MimeTypeMap.getSingleton()::getExtensionFromMimeType)
        ?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
        ?: "jpg"
    val destination = File(directory, "${filePrefix}_${UUID.randomUUID()}.$extension")
    val temporary = File(directory, ".${destination.name}.importing")

    try {
        val bytes = context.contentResolver.openInputStream(source)?.use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取所选照片")
        check(bytes > 0L) { "所选照片为空" }
        check(temporary.renameTo(destination)) { "无法保存所选照片" }
        return destination
    } catch (error: Throwable) {
        temporary.delete()
        destination.delete()
        throw error
    }
}
