package com.moge.app.ui.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.moge.app.ui.theme.Paper
import com.moge.app.ui.theme.PaperGrid
import com.moge.app.ui.theme.Ink
import com.moge.app.ui.theme.InkText
import com.moge.app.ui.theme.InkMuted
import com.moge.app.ui.theme.Highlighter
import com.moge.app.ui.theme.PaperExtras
import android.view.View
import android.widget.TextView
import com.moge.app.data.parse.normalizeReplyMarkdown
import com.moge.app.data.parse.sanitizeReplyLatex
import com.moge.app.data.parse.wrapLongFormulas
import com.moge.app.ui.markdown.FigurePathResolver
import com.moge.app.ui.markdown.BaselineLatexSpan
import com.moge.app.ui.markdown.baselineAlignedLatex
import com.moge.app.ui.markdown.createMarkdownTextView
import com.moge.app.ui.markdown.splitMarkdownTableBlocks
import com.moge.app.ui.photo.decodeUprightPhoto
import io.noties.markwon.Markwon
import io.noties.markwon.image.AsyncDrawableSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

internal val EXPORT_INK = InkText.toArgb()
internal val EXPORT_PAPER = Paper.toArgb()
internal val EXPORT_TITLE = Ink.toArgb()
internal val EXPORT_MARGIN = PaperExtras.marginLine.toArgb()
private val EXPORT_LINK = Ink.toArgb()
private const val CELL_PADDING = 16

/**
 * Dedicated native layout, never a screenshot of Compose or its LazyColumn. TextViews and TeX
 * are prepared and drawn on Main; decoding, file work and PNG encoding are dispatched to IO.
 * Only the current bounded page and one sampled image are retained, regardless of page count.
 */
internal suspend fun renderAnswerExport(
    context: Context,
    content: AnswerExportContent,
    choice: ExportChoice,
    resolver: FigurePathResolver,
    onProgress: (String) -> Unit,
): ExportResult {
    val files = ExportFiles.create(context)
    try {
        return withContext(Dispatchers.Main.immediate) {
            JLatexMathAndroid.init(context.applicationContext)
            val renderer = NativeExportRenderer(context, files, onProgress)
            try {
                for (part in exportParts(content, choice)) {
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
    warning: (String) -> Unit = {},
): TextView {
    val view = createMarkdownTextView(context, if (heading) EXPORT_TITLE else EXPORT_INK, EXPORT_LINK, selectable = false,
        fontSizePx = if (heading) 44f else ExportLimits.TEXT_SIZE).apply {
        includeFontPadding = true
        setPadding(0, 0, 0, 0)
        setLineSpacing(8f, 1f)
        if (heading) setTypeface(Typeface.SERIF, Typeface.BOLD)
    }
    if (!markdown) view.text = source
    else {
        try {
            val normalized = sanitizeReplyLatex(normalizeReplyMarkdown(source))
            val wrapped = wrapLongFormulas(normalized, width - 8) { latex ->
                require(latex.length <= ExportLimits.FORMULA_CHARS) { "公式过长" }
                JLatexMathDrawable.builder(latex).textSize(view.textSize).build().intrinsicWidth
            }
            val markwon = view.tag as Markwon
            val parsed = baselineAlignedLatex(markwon.toMarkdown(wrapped), markwon.configuration().theme())
            // toMarkdown + direct setText deliberately avoid Markwon's asynchronous scheduler.
            // Finish EVERY inline formula before measuring, including table cells. Detached views
            // otherwise have no draw/layout callback to await, and can export empty formula boxes.
            for (span in parsed.getSpans(0, parsed.length, AsyncDrawableSpan::class.java)) {
                require(span is BaselineLatexSpan) { "外部 Markdown 图片需保留原文" }
                val formula = span.drawable.destination
                require(formula.length <= ExportLimits.FORMULA_CHARS) { "公式过长" }
                val drawable = JLatexMathDrawable.builder(formula).textSize(view.textSize).color(EXPORT_INK).build()
                span.drawable.initWithKnownDimensions(width, view.textSize)
                span.drawable.setResult(drawable)
                // This is a drawable, not an inline formula. External Markdown images have no
                // loader in this renderer; preserve the complete source instead of hiding it.
                check(span.drawable.hasResult()) { "公式未完成" }
            }
            view.text = parsed
        } catch (_: Exception) {
            val message = "部分 Markdown / 公式无法排版，已保留完整原文"
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

internal fun exportViewSlices(view: TextView, capacity: Int): List<VerticalSlice> {
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
    return paginateBoundaries(offsets, capacity)
}

private class NativeExportRenderer(
    private val context: Context,
    private val files: ExportFiles,
    private val progress: (String) -> Unit,
) {
    private var page: Bitmap? = null
    private var canvas: Canvas? = null
    private var y = ExportLimits.MARGIN
    private val pages = mutableListOf<File>()
    private val warnings = linkedSetOf<String>()
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PaperGrid.toArgb(); strokeWidth = 2f }
    private val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = InkMuted.toArgb(); textSize = 25f; typeface = Typeface.MONOSPACE }
    private val marginRule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = EXPORT_MARGIN; strokeWidth = 2f }
    private val gridRule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PaperGrid.copy(alpha = 0.48f).toArgb(); strokeWidth = 1f }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Highlighter.copy(alpha = 0.65f).toArgb() }

    private fun warning(message: String) { warnings += message }

    private fun ensurePage() {
        if (page != null) return
        page = Bitmap.createBitmap(ExportLimits.WIDTH, ExportLimits.HEIGHT, Bitmap.Config.ARGB_8888)
        canvas = Canvas(page!!).apply {
            drawColor(EXPORT_PAPER)
            var step = 48f
            while (step < ExportLimits.WIDTH) {
                drawLine(step, 0f, step, ExportLimits.HEIGHT.toFloat(), gridRule)
                step += 48f
            }
            step = 48f
            while (step < ExportLimits.HEIGHT) {
                drawLine(0f, step, ExportLimits.WIDTH.toFloat(), step, gridRule)
                step += 48f
            }
            val margin = ExportLimits.MARGIN - 28f
            drawLine(margin, ExportLimits.MARGIN / 2f, margin, ExportLimits.HEIGHT.toFloat(), marginRule)
            drawLine(margin + 7f, ExportLimits.MARGIN / 2f, margin + 7f, ExportLimits.HEIGHT.toFloat(),
                Paint(marginRule).apply { alpha = 90 })
        }
        y = ExportLimits.MARGIN
    }

    private suspend fun space(height: Int) {
        require(height <= ExportLimits.CONTENT_HEIGHT) { "导出分块超出安全高度" }
        ensurePage()
        if (y + height > ExportLimits.HEIGHT - ExportLimits.MARGIN - ExportLimits.FOOTER) {
            flush(); ensurePage()
        }
    }

    private suspend fun flush() {
        val bitmap = page ?: return
        var cropped: Bitmap? = null
        try {
            val actualHeight = (y + ExportLimits.MARGIN + ExportLimits.FOOTER)
                .coerceAtMost(ExportLimits.HEIGHT).coerceAtLeast(ExportLimits.MARGIN * 2 + ExportLimits.FOOTER)
            val bottom = actualHeight - ExportLimits.MARGIN
            canvas!!.drawLine(ExportLimits.MARGIN.toFloat(), (bottom - 40).toFloat(),
                (ExportLimits.WIDTH - ExportLimits.MARGIN).toFloat(), (bottom - 40).toFloat(), rule)
            canvas!!.drawText("墨格 · ${pages.size + 1} · 按页码连续阅读", ExportLimits.MARGIN.toFloat(), bottom.toFloat(), footer)
            progress("正在编码第 ${pages.size + 1} 张图片…")
            val output = if (actualHeight == bitmap.height) bitmap else
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, actualHeight).also { cropped = it }
            pages += files.writePage(output, pages.size + 1)
        } finally {
            if (cropped !== bitmap) cropped?.recycle()
            bitmap.recycle(); page = null; canvas = null
        }
    }

    suspend fun text(part: ExportPart.Text) {
        val sections = if (part.markdown) splitMarkdownTableBlocks(normalizeReplyMarkdown(part.source))
            else listOf(com.moge.app.ui.markdown.MarkdownChunkSpec(part.source, false))
        for (section in sections) {
            currentCoroutineContext().ensureActive()
            if (section.isTable) {
                val table = parseExportTable(section.text)
                if (table != null) { table(table); continue }
            }
            for (chunk in boundedSourceChunks(section.text)) {
                if (chunk.plain) {
                    warning("过长内容已分块保留原文")
                    plainNotice("[过长内容，以下分块保留完整原文]")
                }
                val view = exportTextView(context, chunk.source, ExportLimits.CONTENT_WIDTH,
                    markdown = part.markdown && !chunk.plain, heading = part.heading, warning = ::warning)
                drawText(view, heading = part.heading)
                yield()
            }
        }
    }

    private suspend fun plainNotice(source: String) {
        drawText(exportTextView(context, source, ExportLimits.CONTENT_WIDTH, markdown = false))
    }

    private suspend fun drawText(view: TextView, heading: Boolean = false) {
        for (slice in exportViewSlices(view, ExportLimits.CONTENT_HEIGHT)) {
            space(slice.height)
            if (heading) {
                val layout = view.layout
                for (line in 0 until layout.lineCount) {
                    val baseline = layout.getLineBaseline(line)
                    if (baseline in slice.top until slice.bottom) {
                        val left = ExportLimits.MARGIN - 5f
                        val top = y + (baseline - slice.top) * slice.scale - 8f
                        val right = (left + layout.getLineWidth(line) * slice.scale + 10f).coerceAtMost(ExportLimits.WIDTH - ExportLimits.MARGIN.toFloat())
                        canvas!!.drawRect(left, top, right, top + 16f, highlight)
                    }
                }
            }
            drawSlice(view, slice, ExportLimits.MARGIN.toFloat(), y.toFloat())
            y += slice.height + ExportLimits.GAP
            currentCoroutineContext().ensureActive()
        }
    }

    private fun drawSlice(view: TextView, slice: VerticalSlice, x: Float, top: Float) {
        val target = canvas!!
        val save = target.save()
        try {
            target.translate(x, top)
            target.scale(slice.scale, slice.scale)
            target.clipRect(0, 0, view.width, slice.bottom - slice.top)
            target.translate(0f, -slice.top.toFloat())
            view.draw(target)
        } finally { target.restoreToCount(save) }
    }

    private suspend fun table(table: ExportTable) {
        if (table.headers.any { it.length > ExportLimits.SOURCE_CHUNK }) {
            tableAsCards(table, "表头过长，按行逐列续排以保留全部内容")
            return
        }
        for (columns in tableColumnGroups(table.columns)) {
            if (table.columns > columns.count()) {
                plainNotice("表格：第 ${columns.first + 1}–${columns.last + 1} 列 / 共 ${table.columns} 列（按列组连续阅读）")
            }
            val cellWidth = ExportLimits.CONTENT_WIDTH / columns.count()
            // Repeat headings for each data row/continuation. Formula cells use the same native
            // Markwon/JLatexMath TextViews as prose, unlike TableRowSpan's hidden async layouts.
            for (row in table.rows.ifEmpty { listOf(List(table.columns) { "" }) }) {
                if (columns.any { row[it].length > ExportLimits.SOURCE_CHUNK }) {
                    plainNotice("超长表格行，按列续排以保留全部内容")
                    for (column in columns) {
                        text(ExportPart.Text(table.headers[column], heading = true))
                        text(ExportPart.Text(row[column]))
                    }
                    continue
                }
                val headers = columns.map { column -> cell(table.headers[column], cellWidth) }
                val cells = columns.map { column -> cell(row[column], cellWidth) }
                val headerBands = cellBands(headers, ExportLimits.CONTENT_HEIGHT / 3 - CELL_PADDING * 2)
                val headerHeight = headerBands.sumOf { band -> band.filterNotNull().maxOf { it.height } + CELL_PADDING * 2 }
                val capacity = (ExportLimits.CONTENT_HEIGHT - headerHeight - CELL_PADDING * 2)
                if (capacity < 100) {
                    plainNotice("表头较长，按列续排以保留全部内容")
                    for (column in columns) {
                        text(ExportPart.Text(table.headers[column], heading = true))
                        text(ExportPart.Text(row[column]))
                    }
                    continue
                }
                for ((bandIndex, band) in cellBands(cells, capacity).withIndex()) {
                    if (bandIndex > 0) plainNotice("表格行续页（同一行的剩余内容）")
                    val bandHeight = band.filterNotNull().maxOf { it.height } + CELL_PADDING * 2
                    space(headerHeight + bandHeight)
                    for (headerBand in headerBands) drawCells(headers, headerBand, cellWidth, header = true)
                    drawCells(cells, band, cellWidth, header = false)
                }
                y += ExportLimits.GAP
                yield()
            }
        }
    }

    private suspend fun tableAsCards(table: ExportTable, notice: String) {
        plainNotice(notice)
        for (row in table.rows.ifEmpty { listOf(List(table.columns) { "" }) }) {
            for (column in table.headers.indices) {
                text(ExportPart.Text(table.headers[column], heading = true))
                text(ExportPart.Text(row[column]))
            }
        }
    }

    private fun cell(source: String, width: Int): TextView {
        return exportTextView(context, source, width - CELL_PADDING * 2, warning = ::warning)
    }

    private fun cellBands(views: List<TextView>, height: Int): List<List<VerticalSlice?>> {
        val slices = views.map { exportViewSlices(it, height) }
        return List(slices.maxOf { it.size }) { band -> slices.map { it.getOrNull(band) } }
    }

    private suspend fun drawCells(views: List<TextView>, slices: List<VerticalSlice?>, width: Int, header: Boolean) {
        val height = (slices.filterNotNull().maxOfOrNull { it.height } ?: 1) + CELL_PADDING * 2
        space(height)
        val background = Paint().apply { color = if (header) 0xFFDCE4F7.toInt() else EXPORT_PAPER }
        for ((index, view) in views.withIndex()) {
            val left = ExportLimits.MARGIN + index * width
            val rect = RectF(left.toFloat(), y.toFloat(), (left + width).toFloat(), (y + height).toFloat())
            canvas!!.drawRect(rect, background)
            rule.style = Paint.Style.STROKE
            canvas!!.drawRect(rect, rule)
            slices[index]?.let { drawSlice(view, it, (left + CELL_PADDING).toFloat(), (y + CELL_PADDING).toFloat()) }
        }
        y += height
    }

    suspend fun image(part: ExportPart.Image, resolver: FigurePathResolver) {
        var bitmap: Bitmap? = null
        try {
            try {
                withContext(Dispatchers.IO) {
                    val path = if (part.generated) resolver.resolve(part.path, false) else part.path
                    if (!path.isNullOrBlank()) bitmap = decodeUprightPhoto(path, ExportLimits.IMAGE_DIMENSION)
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { /* Decode/resolve failures get a visible placeholder below. */ }
            val image = bitmap
            if (image == null) {
                val message = "${part.label}缺失或无法读取，请核对原题 / 重试生成图形"
                warning(message); plainNotice("[$message]"); return
            }
            plainNotice(part.label)
            val scale = minOf(ExportLimits.CONTENT_WIDTH.toFloat() / image.width,
                ExportLimits.CONTENT_HEIGHT.toFloat() / image.height, 1f)
            val width = (image.width * scale).roundToInt().coerceAtLeast(1)
            val height = ceil(image.height * scale).toInt().coerceAtMost(ExportLimits.CONTENT_HEIGHT)
            space(height)
            val left = (ExportLimits.WIDTH - width) / 2f
            val rect = RectF(left, y.toFloat(), left + width, (y + height).toFloat())
            canvas!!.drawRect(rect, Paint().apply { color = android.graphics.Color.WHITE })
            canvas!!.drawBitmap(image, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas!!.drawRect(rect, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = PaperExtras.cardStroke.copy(alpha = 0.45f).toArgb(); style = Paint.Style.STROKE; strokeWidth = 2f
            })
            if (!part.generated) {
                val tape = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PaperExtras.tape.toArgb() }
                val save = canvas!!.save()
                canvas!!.rotate(-7f, rect.right - 48f, rect.top + 4f)
                canvas!!.drawRect(rect.right - 94f, rect.top - 8f, rect.right - 4f, rect.top + 22f, tape)
                canvas!!.restoreToCount(save)
            }
            y += height + ExportLimits.GAP
        } finally { bitmap?.recycle() }
    }

    suspend fun finish(): ExportResult {
        flush()
        return ExportResult(files, pages.toList(), warnings.toList())
    }

    fun release() { page?.recycle(); page = null; canvas = null }
}
