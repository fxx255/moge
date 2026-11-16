package com.moge.app.ui.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.moge.app.R
import com.moge.app.data.prefs.ListMarkerSettings
import com.moge.app.ui.markdown.FigurePathResolver
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FilterOutputStream

/** Flush each native A4 page before merging, so selecting an album cannot retain all photo bitmaps. */
internal class PdfPageOutput private constructor(private val directory: File) : AutoCloseable {
    private fun scratch() = MemoryUsageSetting.setupTempFileOnly().setTempDir(directory)
    private val document = PDDocument(scratch())
    private val merger = PDFMergerUtility()
    val layoutHeight = (ExportLimits.WIDTH * 842f / 595f).toInt()
    var pageCount: Int = 0
        private set

    suspend fun writePage(number: Int, draw: suspend (Canvas, Int, Int) -> Unit) {
        currentCoroutineContext().ensureActive()
        val temporary = File(directory, "page-$number.pdf")
        try {
            val native = PdfDocument()
            try {
                val page = native.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
                try {
                    page.canvas.scale(595f / ExportLimits.WIDTH, 842f / layoutHeight)
                    draw(page.canvas, 0, layoutHeight)
                } finally { native.finishPage(page) }
                withContext(Dispatchers.IO) {
                    temporary.outputStream().use { native.writeTo(checkedOutput(it)) }
                }
            } finally { native.close() }
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                PDDocument.load(temporary, scratch()).use { source -> merger.appendDocument(document, source) }
            }
        } finally { withContext(NonCancellable + Dispatchers.IO) { temporary.delete() } }
        pageCount++
        yield()
    }

    suspend fun save(file: File) = withContext(Dispatchers.IO) {
        val partial = File(file.parentFile, "${file.name}.partial")
        try {
            partial.outputStream().use { raw ->
                document.documentInformation.title = file.nameWithoutExtension
                document.documentInformation.creator = "墨格"
                document.save(checkedOutput(raw))
                raw.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            check(partial.renameTo(file)) { "无法保存 PDF" }
        } finally { partial.delete() }
    }

    override fun close() { document.close() }

    companion object {
        suspend fun create(context: Context, directory: File): PdfPageOutput {
            var created: PdfPageOutput? = null
            try {
                return withContext(Dispatchers.IO) {
                    PDFBoxResourceLoader.init(context)
                    PdfPageOutput(directory).also { created = it }
                }
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) { created?.close() }
                throw error
            }
        }
    }
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

internal data class ConversationPdfSection(val title: String, val metadata: String, val parts: List<ExportPart>)
internal data class ConversationPdfResult(val files: ExportFiles, val file: File, val pages: Int, val warnings: List<String>)

/** Load and release one conversation at a time, sharing Markdown/table/photo layout with image export. */
internal suspend fun renderConversationPdf(
    context: Context,
    count: Int,
    fileName: String,
    resolver: FigurePathResolver,
    typeface: Typeface = Typeface.DEFAULT,
    listMarkers: ListMarkerSettings = ListMarkerSettings(),
    section: suspend (Int) -> ConversationPdfSection,
    onProgress: (String) -> Unit,
): ConversationPdfResult {
    require(count > 0)
    val files = ExportFiles.create(context)
    try {
        return withContext(Dispatchers.Main.immediate) {
            com.moge.app.core.LatexEngine.init(context)
            val pdf = PdfPageOutput.create(context, files.directory)
            try {
                val renderer = NativeExportRenderer(context, files, onProgress, typeface, listMarkers, pdf)
                try {
                    for (index in 0 until count) {
                        currentCoroutineContext().ensureActive()
                        onProgress(context.getString(R.string.pdf_preparing_conversation, index + 1, count))
                        val next = section(index)
                        renderer.pageBreak()
                        renderer.text(ExportPart.Text("${index + 1}. ${next.title}", markdown = false, heading = true))
                        renderer.text(ExportPart.Text(next.metadata, markdown = false))
                        for (part in next.parts) {
                            currentCoroutineContext().ensureActive()
                            when (part) {
                                is ExportPart.Text -> renderer.text(part)
                                is ExportPart.Image -> renderer.image(part, resolver)
                            }
                            yield()
                        }
                    }
                    val rendered = renderer.finish()
                    onProgress(context.getString(R.string.pdf_saving))
                    val target = File(files.directory, fileName)
                    pdf.save(target)
                    ConversationPdfResult(files, target, pdf.pageCount, rendered.warnings)
                } finally { renderer.release() }
            } finally { withContext(NonCancellable + Dispatchers.IO) { pdf.close() } }
        }
    } catch (error: Throwable) { files.close(force = true); throw error }
}
