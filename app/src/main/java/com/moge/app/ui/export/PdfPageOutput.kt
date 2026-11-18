package com.moge.app.ui.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FilterOutputStream

/** Share fonts across a bounded run of text pages. Flush before the page after a photo so
 * PDF generation cannot retain an entire album; short exports never need a PDFBox merge. */
internal class PdfPageOutput(private val context: Context, private val directory: File) : AutoCloseable {
    private fun scratch() = MemoryUsageSetting.setupTempFileOnly().setTempDir(directory)
    private var merged: PDDocument? = null
    private val merger by lazy { PDFMergerUtility() }
    private var batch: PdfDocument? = null
    private var batchPages = 0
    private var flushBeforeNext = false
    var pageCount = 0
        private set

    suspend fun writePage(number: Int, containsImage: Boolean, draw: suspend (Canvas, Int, Int) -> Unit) {
        currentCoroutineContext().ensureActive()
        if (flushBeforeNext) appendBatch()
        val native = batch ?: PdfDocument().also { batch = it }
        val page = native.startPage(PdfDocument.PageInfo.Builder(ConversationPrintLayout.WIDTH_PT,
            ConversationPrintLayout.HEIGHT_PT, number).create())
        try {
            page.canvas.scale(ConversationPrintLayout.WIDTH_PT.toFloat() / ExportLimits.WIDTH,
                ConversationPrintLayout.HEIGHT_PT.toFloat() / ConversationPrintLayout.height)
            draw(page.canvas, 0, ConversationPrintLayout.height)
        } finally { native.finishPage(page) }
        batchPages++
        pageCount++
        flushBeforeNext = containsImage || batchPages >= 8
        yield()
    }

    private suspend fun appendBatch() = withContext(Dispatchers.IO) {
        val native = checkNotNull(batch)
        val target = merged ?: run {
            PDFBoxResourceLoader.init(context)
            PDDocument(scratch()).also { merged = it }
        }
        val temp = File(directory, "batch.pdf")
        try {
            temp.outputStream().buffered().use { native.writeTo(checkedOutput(it)) }
            native.close(); batch = null
            currentCoroutineContext().ensureActive()
            PDDocument.load(temp, scratch()).use { merger.appendDocument(target, it) }
            batchPages = 0; flushBeforeNext = false
        } finally { temp.delete() }
    }

    suspend fun save(file: File) = withContext(Dispatchers.IO) {
        val target = merged
        if (target != null) appendBatch()
        val partial = File(file.parentFile, "${file.name}.partial")
        try {
            partial.outputStream().use { raw ->
                val buffered = raw.buffered()
                val out = checkedOutput(buffered)
                if (target == null) checkNotNull(batch).writeTo(out)
                else {
                    target.documentInformation.title = file.nameWithoutExtension
                    target.documentInformation.creator = "墨格"
                    target.save(out)
                }
                buffered.flush()
                raw.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            check(partial.renameTo(file)) { "无法保存 PDF" }
        } finally { partial.delete() }
    }

    override fun close() { try { batch?.close() } finally { merged?.close() } }
}

private suspend fun checkedOutput(output: java.io.OutputStream): java.io.OutputStream {
    val operation = currentCoroutineContext()
    return object : FilterOutputStream(output) {
        override fun close() { flush() } // PDFBox may close its writer; the caller owns fsync/close.
        override fun write(value: Int) { operation.ensureActive(); out.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            operation.ensureActive(); out.write(bytes, offset, length)
        }
    }
}
