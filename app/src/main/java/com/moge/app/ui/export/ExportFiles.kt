package com.moge.app.ui.export

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.UUID

internal class ExportFiles private constructor(val directory: File) {
    private var retainedForSharing = false

    suspend fun writePage(bitmap: Bitmap, number: Int): File = withContext(Dispatchers.IO) {
        val operation = currentCoroutineContext()
        operation.ensureActive()
        val target = File(directory, "page_${number.toString().padStart(4, '0')}.png")
        val partial = File(directory, "${target.name}.partial")
        try {
            partial.outputStream().use { raw ->
                val checked = object : OutputStream() {
                    override fun write(value: Int) { operation.ensureActive(); raw.write(value) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        operation.ensureActive(); raw.write(bytes, offset, length)
                    }
                }
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, checked)) { "无法编码导出图片" }
                raw.fd.sync()
            }
            operation.ensureActive()
            check(partial.renameTo(target)) { "无法保存导出图片" }
            target
        } finally { partial.delete() }
    }

    /** Keep shared URIs alive after dismissal; lazy expiry is limited to this dedicated cache root. */
    fun retainForSharing() { retainedForSharing = true }

    suspend fun close(force: Boolean = false) {
        withContext(NonCancellable + Dispatchers.IO) {
            if (force || !retainedForSharing) directory.deleteRecursively()
        }
    }

    companion object {
        private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
        suspend fun create(context: Context): ExportFiles {
            // Capture the session outside withContext: cancellation during its return must also
            // remove the newly-created directory, rather than losing the only reference to it.
            var created: ExportFiles? = null
            try {
                return withContext(Dispatchers.IO) {
                    val root = File(context.cacheDir, ExportLimits.CACHE_DIRECTORY)
                    check(root.isDirectory || root.mkdirs()) { "无法创建导出目录" }
                    val cutoff = System.currentTimeMillis() - MAX_AGE_MS
                    root.listFiles()?.filter { it.isDirectory && it.lastModified() < cutoff }
                        ?.forEach { it.deleteRecursively() }
                    val directory = File(root, UUID.randomUUID().toString())
                    check(directory.mkdir()) { "无法创建导出目录" }
                    ExportFiles(directory).also { created = it }
                }
            } catch (error: Throwable) { created?.close(force = true); throw error }
        }
    }
}

internal data class ExportResult(val files: ExportFiles, val pages: List<File>, val warnings: List<String>)
