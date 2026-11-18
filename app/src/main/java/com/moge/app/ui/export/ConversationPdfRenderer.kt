package com.moge.app.ui.export

import android.content.Context
import android.graphics.Typeface
import com.moge.app.R
import com.moge.app.data.prefs.ListMarkerStyle
import com.moge.app.data.prefs.ListMarkerSettings
import com.moge.app.ui.markdown.FigurePathResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File

internal data class ConversationPdfSection(val title: String, val metadata: String, val parts: List<ExportPart>)
internal data class ConversationPdfResult(val files: ExportFiles, val file: File, val pages: Int, val warnings: List<String>)

/** Load and release one conversation at a time, sharing Markdown/table/photo layout with image export. */
internal suspend fun renderConversationPdf(
    context: Context,
    count: Int,
    fileName: String,
    resolver: FigurePathResolver,
    typeface: Typeface = Typeface.DEFAULT,
    listMarkers: ListMarkerSettings = ListMarkerSettings(ListMarkerStyle.CLASSIC_DOT),
    section: suspend (Int) -> ConversationPdfSection,
    onProgress: (String) -> Unit,
): ConversationPdfResult {
    require(count > 0)
    val files = ExportFiles.create(context)
    try {
        return withContext(Dispatchers.Main.immediate) {
            com.moge.app.core.LatexEngine.init(context)
            val pdf = PdfPageOutput(context, files.directory)
            try {
                val renderer = NativeExportRenderer(context, files, onProgress, typeface, listMarkers, pdf)
                try {
                    for (index in 0 until count) {
                        currentCoroutineContext().ensureActive()
                        onProgress(context.getString(R.string.pdf_preparing_conversation, index + 1, count))
                        val next = section(index)
                        renderer.startConversation("${index + 1}. ${next.title}", next.metadata)
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
