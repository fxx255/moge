package com.moge.app.ui.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal class ExportFiles private constructor(val directory: File) {
    private var retainedForSharing = false

    suspend fun writePage(width: Int, height: Int, number: Int, drawBand: suspend (Canvas, Int, Int) -> Unit): File = withContext(Dispatchers.IO) {
        val operation = currentCoroutineContext()
        operation.ensureActive()
        val target = File(directory, "page_${number.toString().padStart(4, '0')}.png")
        val partial = File(directory, "${target.name}.partial")
        try {
            partial.outputStream().use { raw ->
                StreamingPngWriter(raw, width, height) { operation.ensureActive() }.use { writer ->
                    var top = 0
                    while (top < height) {
                        operation.ensureActive()
                        val bandHeight = minOf(ExportLimits.RENDER_BAND_HEIGHT, height - top)
                        val band = Bitmap.createBitmap(width, bandHeight, Bitmap.Config.ARGB_8888)
                        band.density = Bitmap.DENSITY_NONE
                        try {
                            val canvas = Canvas(band).apply { translate(0f, -top.toFloat()) }
                            drawBand(canvas, top, top + bandHeight)
                            writer.append(band)
                        } finally { band.recycle() }
                        top += bandHeight
                    }
                    writer.finish()
                }
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
