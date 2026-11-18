package com.moge.app.ui.export

import com.moge.app.R

import com.moge.app.ui.markdown.parseMarkdownTable
import com.moge.app.ui.markdown.MarkdownTable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.moge.app.data.prefs.ListMarkerSettings
import com.moge.app.ui.theme.listMarkerAccent
import androidx.compose.ui.graphics.toArgb
import com.moge.app.core.LatexEngine
import com.moge.app.data.image.QuestionPhotoFiles
import com.moge.app.ui.theme.Paper
import com.moge.app.ui.theme.PaperGrid
import com.moge.app.ui.theme.Ink
import com.moge.app.ui.theme.InkText
import com.moge.app.ui.theme.InkMuted
import com.moge.app.ui.theme.Highlighter
import com.moge.app.ui.theme.PaperExtras
import android.view.View
import android.widget.TextView
import com.moge.app.data.parse.prepareReplyMarkdown
import com.moge.app.data.parse.wrapLongFormulas
import com.moge.app.ui.markdown.FigurePathResolver
import com.moge.app.ui.markdown.BaselineLatexSpan
import com.moge.app.ui.markdown.baselineAlignedLatex
import com.moge.app.ui.markdown.createMarkdownTextView
import com.moge.app.ui.markdown.splitMarkdownTableBlocks
import io.noties.markwon.Markwon
import io.noties.markwon.image.AsyncDrawableSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import ru.noties.jlatexmath.JLatexMathDrawable
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

internal val EXPORT_INK = InkText.toArgb()
internal val EXPORT_PAPER = Paper.toArgb()
internal val EXPORT_TITLE = Ink.toArgb()
internal val EXPORT_MARGIN = PaperExtras.marginLine.toArgb()
private val EXPORT_LINK = Ink.toArgb()
private const val CELL_PADDING = 16 * ExportLimits.SCALE

/**
 * Dedicated native layout, never a screenshot of Compose or its LazyColumn. TextViews and TeX
 * are prepared and drawn on Main; decoding, file work and PNG encoding are dispatched to IO.
 * Only the current page's drawing commands, one image and a 512px render band are retained.
 */
internal suspend fun renderAnswerExport(
    context: Context,
    content: AnswerExportContent,
    choice: ExportChoice,
    resolver: FigurePathResolver,
    bodyTypeface: Typeface = Typeface.DEFAULT,
    listMarkers: ListMarkerSettings = ListMarkerSettings(),
    onProgress: (String) -> Unit,
): ExportResult {
    val files = ExportFiles.create(context)
    try {
        return withContext(Dispatchers.Main.immediate) {
            LatexEngine.init(context)
            val renderer = NativeExportRenderer(context, files, onProgress, bodyTypeface, listMarkers)
            try {
                for (part in exportParts(content, choice, com.moge.app.ui.localization.AppStrings(context))) {
                    currentCoroutineContext().ensureActive()
                    when (part) {
                        is ExportPart.Text -> renderer.text(part)
                        is ExportPart.Image -> renderer.image(part, resolver)
                    }
                    yield()
                }
                renderer.finish()
            } finally { renderer.release() }
        }
    } catch (error: Throwable) { files.close(force = true); throw error }
}

/** Visible source fallback is also used in the preview/export when a formula cannot be drawn. */
internal fun exportTextView(
    context: Context,
    source: String,
    width: Int,
    markdown: Boolean = true,
    heading: Boolean = false,
    bodyTypeface: Typeface = Typeface.DEFAULT,
    listMarkers: ListMarkerSettings = ListMarkerSettings(),
    warning: (String) -> Unit = {},
    fontSizePx: Float? = null,
    printLayout: PrintTextLayout? = null,
    preparedMarkdown: Boolean = false,
): TextView {
    val print = printLayout != null
    val size = fontSizePx ?: if (heading) 44f * ExportLimits.SCALE else ExportLimits.TEXT_SIZE
    val view = printLayout?.view(size, markdown) ?: createMarkdownTextView(context,
        if (heading) EXPORT_TITLE else EXPORT_INK, EXPORT_LINK, selectable = false,
        fontSizePx = size, bodyTypeface = bodyTypeface, markerColor = listMarkerAccent(false), listMarkers = listMarkers)
    view.apply {
        includeFontPadding = !print
        setPadding(0, 0, 0, 0)
        setLineSpacing(if (print) ConversationPrintLayout.pt(1.25f).toFloat() else 8f * ExportLimits.SCALE, 1f)
        if (heading) setTypeface(if (!print && bodyTypeface == Typeface.DEFAULT) Typeface.SERIF else bodyTypeface, Typeface.BOLD)
    }
    if (!markdown) view.text = source
    else {
        try {
            val normalized = if (preparedMarkdown) source else prepareReplyMarkdown(source)
            val wrapped = wrapLongFormulas(normalized, width - 8 * ExportLimits.SCALE) { latex ->
                require(latex.length <= ExportLimits.FORMULA_CHARS) { context.getString(R.string.ui_formula_too_long_eb69b) }
                JLatexMathDrawable.builder(latex).textSize(view.textSize).build().intrinsicWidth
            }
            val markwon = view.tag as Markwon
            val parsed = baselineAlignedLatex(markwon.toMarkdown(wrapped), markwon.configuration().theme())
            // toMarkdown + direct setText deliberately avoid Markwon's asynchronous scheduler.
            // Finish EVERY inline formula before measuring, including table cells. Detached views
            // otherwise have no draw/layout callback to await, and can export empty formula boxes.
            for (span in parsed.getSpans(0, parsed.length, AsyncDrawableSpan::class.java)) {
                require(span is BaselineLatexSpan) { context.getString(R.string.ui_external_markdown_image_kept_as_source_fa547) }
                val formula = span.drawable.destination
                require(formula.length <= ExportLimits.FORMULA_CHARS) { context.getString(R.string.ui_formula_too_long_eb69b) }
                val drawable = JLatexMathDrawable.builder(formula).textSize(view.textSize).color(if (print) Color.BLACK else EXPORT_INK).build()
                span.drawable.initWithKnownDimensions(width, view.textSize)
                span.drawable.setResult(drawable)
                // This is a drawable, not an inline formula. External Markdown images have no
                // loader in this renderer; preserve the complete source instead of hiding it.
                check(span.drawable.hasResult()) { context.getString(R.string.ui_incomplete_formula_5afb8) }
            }
            view.text = parsed
        } catch (_: Exception) {
            val message = context.getString(R.string.ui_some_markdown_or_formulas_could_not_be_laid_out_full_source_8108d)
            warning(message)
            view.text = "$source\n\n[$message]"
        }
    }
    measureExportView(view, width)
    return view
}

internal fun measureExportView(view: TextView, width: Int) {
    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    view.layout(0, 0, width, view.measuredHeight.coerceAtLeast(1))
}

internal fun exportViewSlices(view: TextView, capacity: Int, firstCapacity: Int = capacity): List<VerticalSlice> {
    val layout = checkNotNull(view.layout)
    val height = view.height
    val offsets = buildList {
        add(0)
        // getLineTop includes ReplacementSpan/formula metrics. Keep a whole formula line together.
        for (line in 1 until layout.lineCount) {
            val top = view.totalPaddingTop + layout.getLineTop(line)
            if (top > last() && top < height) add(top)
        }
        if (height > last()) add(height)
    }
    return paginateBoundaries(offsets, capacity, firstCapacity)
}

internal class NativeExportRenderer(
    private val context: Context,
    private val files: ExportFiles,
    private val progress: (String) -> Unit,
    private val bodyTypeface: Typeface,
    private val listMarkers: ListMarkerSettings,
    private val pdfOutput: PdfPageOutput? = null,
) {
    private val print = pdfOutput != null
    private val printText = if (print) PrintTextLayout(context, bodyTypeface, listMarkers) else null
    private val margin = if (print) ConversationPrintLayout.margin else ExportLimits.MARGIN
    private val footerHeight = if (print) ConversationPrintLayout.footer else ExportLimits.FOOTER
    private val gap = if (print) ConversationPrintLayout.gap else ExportLimits.GAP
    private val cellPadding = if (print) ConversationPrintLayout.cellPadding else CELL_PADDING
    private val contentWidth = ExportLimits.WIDTH - margin * 2
    private val pageHeight = if (print) ConversationPrintLayout.height else ExportLimits.HEIGHT
    private val contentHeight = pageHeight - margin * 2 - footerHeight
    private val remaining get() = pageHeight - margin - footerHeight - y
    private var pageCount = 0
    private var pageHasImage = false
    private fun fontSize(heading: Boolean = false): Float? = if (!print) null
        else if (heading) ConversationPrintLayout.labelSize else ConversationPrintLayout.bodySize
    private data class Drawing(val top: Float, val bottom: Float, val draw: suspend (Canvas) -> Unit)
    private val drawings = mutableListOf<Drawing>()
    private var hasPage = false
    private var cachedImage: Bitmap? = null
    private var cachedImagePath: String? = null
    private var y = margin
    private val pages = mutableListOf<File>()
    private val warnings = linkedSetOf<String>()
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (print) Color.LTGRAY else PaperGrid.toArgb(); strokeWidth = if (print) 1.5f else 2f * ExportLimits.SCALE }
    private val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (print) Color.DKGRAY else InkMuted.toArgb(); textSize = if (print) ConversationPrintLayout.captionSize else 25f * ExportLimits.SCALE; typeface = if (print) Typeface.DEFAULT else Typeface.MONOSPACE
        if (print) textAlign = Paint.Align.RIGHT }
    private val marginRule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = EXPORT_MARGIN; strokeWidth = 2f * ExportLimits.SCALE }
    private val gridRule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PaperGrid.copy(alpha = 0.48f).toArgb(); strokeWidth = 1f * ExportLimits.SCALE }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Highlighter.copy(alpha = 0.65f).toArgb() }

    private fun warning(message: String) { warnings += message }

    private fun ensurePage() {
        if (hasPage) return
        hasPage = true
        y = margin
    }

    private fun draw(top: Float, bottom: Float, action: suspend (Canvas) -> Unit) {
        drawings += Drawing(top, bottom, action)
    }

    private fun paper(canvas: Canvas) {
        if (print) return // PDF pages already have a white paper background.
        canvas.drawColor(EXPORT_PAPER)
        var step = 48f * ExportLimits.SCALE
        while (step < ExportLimits.WIDTH) {
            canvas.drawLine(step, 0f, step, pageHeight.toFloat(), gridRule)
            step += 48f * ExportLimits.SCALE
        }
        step = 48f * ExportLimits.SCALE
        while (step < pageHeight) {
            canvas.drawLine(0f, step, ExportLimits.WIDTH.toFloat(), step, gridRule)
            step += 48f * ExportLimits.SCALE
        }
        val ruleX = margin - 28f * ExportLimits.SCALE
        canvas.drawLine(ruleX, margin / 2f, ruleX, pageHeight.toFloat(), marginRule)
        canvas.drawLine(ruleX + 7f * ExportLimits.SCALE, margin / 2f,
            ruleX + 7f * ExportLimits.SCALE, pageHeight.toFloat(), Paint(marginRule).apply { alpha = 90 })
    }

    private suspend fun space(height: Int) {
        require(height <= contentHeight) { context.getString(R.string.ui_export_block_exceeds_safe_height_ef7e0) }
        ensurePage()
        if (y + height > pageHeight - margin - footerHeight) {
            flush(); ensurePage()
        }
    }

    private suspend fun flush() {
        if (!hasPage) return
        try {
            val actualHeight = if (pdfOutput != null) pageHeight else (y + margin + footerHeight)
                .coerceAtMost(pageHeight).coerceAtLeast(margin * 2 + footerHeight)
            val bottom = actualHeight - margin
            val footerLabel = if (pdfOutput != null) context.getString(R.string.pdf_page_number, pageCount + 1)
                else context.getString(R.string.ui_moge_1_s_read_in_page_order_873c2, pageCount + 1)
            draw(bottom - footerHeight.toFloat(), bottom.toFloat() + 4 * ExportLimits.SCALE) { canvas ->
                if (!print) canvas.drawLine(margin.toFloat(), (bottom - 40 * ExportLimits.SCALE).toFloat(),
                    (ExportLimits.WIDTH - margin).toFloat(), (bottom - 40 * ExportLimits.SCALE).toFloat(), rule)
                canvas.drawText(footerLabel, if (print) (ExportLimits.WIDTH - margin).toFloat() else margin.toFloat(), bottom.toFloat(), footer)
            }
            progress(context.getString(if (pdfOutput != null) R.string.pdf_rendering_page else R.string.ui_encoding_image_1_s_4dee9, pageCount + 1))
            val drawPage: suspend (Canvas, Int, Int) -> Unit = { canvas, top, end ->
                withContext(Dispatchers.Main.immediate) {
                    paper(canvas)
                    for (drawing in drawings) {
                        if (drawing.bottom > top && drawing.top < end) drawing.draw(canvas)
                    }
                }
            }
            if (pdfOutput != null) pdfOutput.writePage(pageCount + 1, pageHasImage, drawPage)
            else pages += files.writePage(ExportLimits.WIDTH, actualHeight, pageCount + 1, drawPage)
            pageCount++
        } finally {
            drawings.clear(); hasPage = false; pageHasImage = false
            cachedImage?.recycle(); cachedImage = null; cachedImagePath = null
        }
    }

    suspend fun text(part: ExportPart.Text) {
        val sections = if (part.markdown) splitMarkdownTableBlocks(prepareReplyMarkdown(part.source))
            else listOf(com.moge.app.ui.markdown.MarkdownChunkSpec(part.source, false))
        for (section in sections) {
            currentCoroutineContext().ensureActive()
            if (section.isTable) {
                val table = parseMarkdownTable(section.text)
                if (table != null) { table(table); continue }
            }
            for (chunk in boundedSourceChunks(section.text)) {
                if (chunk.plain) {
                    warning(context.getString(R.string.ui_long_content_split_with_full_source_preserved_f9bbf))
                    plainNotice(context.getString(R.string.ui_long_content_full_source_continues_below_9fde0))
                }
                val view = exportTextView(context, chunk.source, contentWidth,
                    markdown = part.markdown && !chunk.plain, heading = part.heading, bodyTypeface = bodyTypeface,
                    listMarkers = listMarkers, warning = ::warning, fontSizePx = fontSize(part.heading), printLayout = printText, preparedMarkdown = print)
                drawText(view, heading = part.heading)
                yield()
            }
        }
    }

    private suspend fun plainNotice(source: String) {
        drawText(exportTextView(context, source, contentWidth, markdown = false, bodyTypeface = bodyTypeface,
            fontSizePx = fontSize(), printLayout = printText))
    }

    private suspend fun drawText(view: TextView, heading: Boolean = false) {
        if (print) {
            val firstLine = view.totalPaddingTop + view.layout.getLineBottom(0)
            val keep = if (heading) view.height + gap + ConversationPrintLayout.pt(15f) else firstLine
            space(keep.coerceAtMost(contentHeight))
        }
        val firstCapacity = if (print) remaining.coerceAtLeast(1) else contentHeight
        for (slice in exportViewSlices(view, contentHeight, firstCapacity)) {
            space(slice.height)
            if (heading && !print) {
                val layout = view.layout
                for (line in 0 until layout.lineCount) {
                    val baseline = layout.getLineBaseline(line)
                    if (baseline in slice.top until slice.bottom) {
                        val left = margin - 5f * ExportLimits.SCALE
                        val top = y + (baseline - slice.top) * slice.scale - 8f * ExportLimits.SCALE
                        val right = (left + layout.getLineWidth(line) * slice.scale + 10f * ExportLimits.SCALE).coerceAtMost(ExportLimits.WIDTH - margin.toFloat())
                        draw(top, top + 16f * ExportLimits.SCALE) { it.drawRect(left, top, right, top + 16f * ExportLimits.SCALE, highlight) }
                    }
                }
            }
            drawSlice(view, slice, margin.toFloat(), y.toFloat())
            y += slice.height + gap
            currentCoroutineContext().ensureActive()
        }
    }

    private fun drawSlice(view: TextView, slice: VerticalSlice, x: Float, top: Float) {
        draw(top, top + slice.height) { target ->
            val save = target.save()
            try {
                target.translate(x, top)
                target.scale(slice.scale, slice.scale)
                target.clipRect(0, 0, view.width, slice.bottom - slice.top)
                target.translate(0f, -slice.top.toFloat())
                view.draw(target)
            } finally { target.restoreToCount(save) }
        }
    }

    private suspend fun table(table: MarkdownTable) {
        if (table.headers.any { it.length > ExportLimits.SOURCE_CHUNK }) {
            tableAsCards(table, context.getString(R.string.ui_long_header_continues_by_column_to_preserve_all_content_e6fbd))
            return
        }
        for (columns in tableColumnGroups(table.columns, maxColumns = if (print) 5 else 3)) {
            if (table.columns > columns.count()) {
                plainNotice(context.getString(R.string.ui_table_columns_1_s_2_s_of_3_s_read_by_column_group_27537, columns.first + 1, columns.last + 1, table.columns))
            }
            val cellWidth = contentWidth / columns.count()
            // Reuse measured headers. Print repeats them only when the table reaches a new page.
            var headerOnPage = -1
            val headers = columns.map { column -> cell(table.headers[column], cellWidth) }
            val headerBands = cellBands(headers, contentHeight / 3 - cellPadding * 2)
            val headerHeight = headerBands.sumOf { band -> band.filterNotNull().maxOf { it.height } + cellPadding * 2 }
            val capacity = (contentHeight - headerHeight - cellPadding * 2)
            for (row in table.rows.ifEmpty { listOf(List(table.columns) { "" }) }) {
                if (columns.any { row[it].length > ExportLimits.SOURCE_CHUNK }) {
                    plainNotice(context.getString(R.string.ui_long_table_row_continues_by_column_36ba5))
                    for (column in columns) {
                        text(ExportPart.Text(table.headers[column], heading = true))
                        text(ExportPart.Text(row[column]))
                    }
                    continue
                }
                if (capacity < 100 * ExportLimits.SCALE) {
                    plainNotice(context.getString(R.string.ui_long_table_header_continues_by_column_8b254))
                    for (column in columns) {
                        text(ExportPart.Text(table.headers[column], heading = true))
                        text(ExportPart.Text(row[column]))
                    }
                    continue
                }
                val cells = columns.map { column -> cell(row[column], cellWidth) }
                for ((bandIndex, band) in cellBands(cells, capacity).withIndex()) {
                    if (bandIndex > 0 && !print) plainNotice(context.getString(R.string.ui_table_row_continued_remaining_content_187b3))
                    val bandHeight = band.filterNotNull().maxOf { it.height } + cellPadding * 2
                    val needHeader = !print || headerOnPage != pageCount || bandHeight > remaining
                    space(bandHeight + if (needHeader) headerHeight else 0)
                    if (needHeader) {
                        for (headerBand in headerBands) drawCells(headers, headerBand, cellWidth, header = true)
                        headerOnPage = pageCount
                    }
                    drawCells(cells, band, cellWidth, header = false)
                }
                if (!print) y += gap
                yield()
            }
            if (print) y += gap
        }
    }

    private suspend fun tableAsCards(table: MarkdownTable, notice: String) {
        plainNotice(notice)
        for (row in table.rows.ifEmpty { listOf(List(table.columns) { "" }) }) {
            for (column in table.headers.indices) {
                text(ExportPart.Text(table.headers[column], heading = true))
                text(ExportPart.Text(row[column]))
            }
        }
    }

    private fun cell(source: String, width: Int): TextView {
        return exportTextView(context, source, width - cellPadding * 2, bodyTypeface = bodyTypeface,
            listMarkers = listMarkers, warning = ::warning, fontSizePx = fontSize(), printLayout = printText)
    }

    private fun cellBands(views: List<TextView>, height: Int): List<List<VerticalSlice?>> {
        val slices = views.map { exportViewSlices(it, height) }
        return List(slices.maxOf { it.size }) { band -> slices.map { it.getOrNull(band) } }
    }

    private suspend fun drawCells(views: List<TextView>, slices: List<VerticalSlice?>, width: Int, header: Boolean) {
        val height = (slices.filterNotNull().maxOfOrNull { it.height } ?: 1) + cellPadding * 2
        space(height)
        val background = Paint().apply { color = if (print) { if (header) 0xFFF2F2F2.toInt() else Color.WHITE } else if (header) 0xFFDCE4F7.toInt() else EXPORT_PAPER }
        for ((index, view) in views.withIndex()) {
            val left = margin + index * width
            val rect = RectF(left.toFloat(), y.toFloat(), (left + width).toFloat(), (y + height).toFloat())
            val border = Paint(rule).apply { style = Paint.Style.STROKE }
            draw(rect.top - border.strokeWidth, rect.bottom + border.strokeWidth) { canvas ->
                canvas.drawRect(rect, background)
                canvas.drawRect(rect, border)
            }
            slices[index]?.let { drawSlice(view, it, (left + cellPadding).toFloat(), (y + cellPadding).toFloat()) }
        }
        y += height
    }

    suspend fun image(part: ExportPart.Image, resolver: FigurePathResolver) {
        val resolved = try {
            withContext(Dispatchers.IO) {
                val path = if (part.generated) resolver.resolve(part.path, false)
                    else QuestionPhotoFiles.displayPath(part.path)
                path?.let { source -> exportImageInfo(source)?.let { source to it } }
            }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { null }
        if (resolved == null) {
            val message = context.getString(R.string.ui_1_s_is_unavailable_check_the_question_or_regenerate_the_figu_056e4, part.label)
            warning(message); plainNotice("[$message]"); return
        }
        val (path, info) = resolved
        val label = if (pdfOutput != null) exportTextView(context, part.label, contentWidth,
            markdown = false, bodyTypeface = bodyTypeface, fontSizePx = ConversationPrintLayout.captionSize, printLayout = printText) else null
        val labelHeight = label?.let { it.height + gap } ?: 0
        val imageCapacity = if (!print) contentHeight else {
            // A portrait photo can share a page with its question and answer. Use a little less
            // space when that keeps the intact image and caption on the current page.
            val cap = (contentHeight * if (part.generated) .38f else .46f).roundToInt()
            (remaining - labelHeight).takeIf { it in (cap * .8f).roundToInt()..cap } ?: cap
        }
        val scale = minOf(contentWidth.toFloat() / info.width,
            imageCapacity.toFloat() / info.height, ExportLimits.SCALE.toFloat())
        val width = (info.width * scale).roundToInt().coerceAtLeast(1)
        val height = ceil(info.height * scale).toInt().coerceAtMost(imageCapacity)
        if (label != null) {
            space((height + labelHeight).coerceAtMost(contentHeight))
            drawText(label)
        } else plainNotice(part.label)
        space(height)
        pageHasImage = true
        val left = (ExportLimits.WIDTH - width) / 2f
        val rect = RectF(left, y.toFloat(), left + width, (y + height).toFloat())
        draw(rect.top - 24f * ExportLimits.SCALE, rect.bottom + 2f * ExportLimits.SCALE) { canvas ->
            val image = image(path)
            canvas.drawRect(rect, Paint().apply { color = android.graphics.Color.WHITE })
            canvas.drawBitmap(image, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            if (!print) canvas.drawRect(rect, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = PaperExtras.cardStroke.copy(alpha = 0.45f).toArgb()
                style = Paint.Style.STROKE; strokeWidth = 2f * ExportLimits.SCALE
            })
            if (!print && !part.generated) {
                val tape = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PaperExtras.tape.toArgb() }
                val tapeWidth = minOf(90f * ExportLimits.SCALE, rect.width())
                val tapeRect = RectF(rect.centerX() - tapeWidth / 2, rect.top - 8f * ExportLimits.SCALE,
                    rect.centerX() + tapeWidth / 2, rect.top + 22f * ExportLimits.SCALE)
                val save = canvas.save()
                canvas.rotate(-7f, tapeRect.centerX(), tapeRect.centerY())
                canvas.drawRect(tapeRect, tape)
                canvas.restoreToCount(save)
            }
        }
        y += height + gap
    }

    private suspend fun image(path: String): Bitmap {
        if (cachedImagePath == path) return checkNotNull(cachedImage)
        cachedImage?.recycle(); cachedImage = null; cachedImagePath = null
        withContext(Dispatchers.IO) { cachedImage = decodeExportImage(path) }
        cachedImagePath = path
        return checkNotNull(cachedImage) { context.getString(R.string.ui_could_not_read_image_add_it_again_before_exporting_43dec) }
    }

    suspend fun finish(): ExportResult {
        flush()
        return ExportResult(files, pages.toList(), warnings.toList())
    }

    /** Keep a compact title and its metadata with the beginning of the conversation. */
    suspend fun startConversation(title: String, metadata: String) {
        val heading = exportTextView(context, title, contentWidth, markdown = false, heading = true,
            bodyTypeface = bodyTypeface, fontSizePx = ConversationPrintLayout.titleSize, printLayout = printText)
        val caption = metadata.takeIf { it.isNotBlank() }?.let {
            exportTextView(context, it, contentWidth, markdown = false, bodyTypeface = bodyTypeface,
                fontSizePx = ConversationPrintLayout.captionSize, printLayout = printText)
        }
        val separation = ConversationPrintLayout.pt(7f)
        space((heading.height + (caption?.height ?: 0) + 2 * gap + separation + ConversationPrintLayout.pt(32f)).coerceAtMost(contentHeight))
        if (y > margin) y += separation
        drawText(heading, heading = true)
        if (caption != null) drawText(caption)
    }

    fun release() { drawings.clear(); cachedImage?.recycle(); cachedImage = null; cachedImagePath = null }
}
