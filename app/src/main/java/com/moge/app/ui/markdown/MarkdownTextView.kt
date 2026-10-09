package com.moge.app.ui.markdown

import android.annotation.SuppressLint
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.Selection
import android.text.StaticLayout
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import com.moge.app.data.parse.normalizeReplyMarkdown
import com.moge.app.data.parse.sanitizeReplyLatex
import com.moge.app.data.parse.wrapLongFormulas
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import ru.noties.jlatexmath.JLatexMathDrawable

private data class MarkdownRenderSource(val markdown: String, val formulaWidthPx: Int)
private interface MarkdownRenderSourceOwner {
    var renderSource: MarkdownRenderSource?
    var onTextTap: (() -> Unit)?
}

/** Native selectable TextViews handle taps themselves, so an OnClickListener is insufficient. */
internal fun setMarkdownTextTap(view: TextView, onTap: (() -> Unit)?) {
    (view as? MarkdownRenderSourceOwner)?.onTextTap = onTap
}

/** Retain the complete render input before normalization/sanitization for failure diagnostics. */
internal fun rememberMarkdownRenderSource(view: TextView, source: String, widthPx: Int) {
    (view as? MarkdownRenderSourceOwner)?.renderSource = MarkdownRenderSource(source, formulaMaxWidthPx(view, widthPx))
}

internal fun createMarkdownTextView(
    context: Context,
    textColor: Int,
    linkColor: Int,
    selectable: Boolean = true,
    fontSizePx: Float? = null,
): TextView =
    object : TextView(context), MarkdownRenderSourceOwner {
        @Volatile override var renderSource: MarkdownRenderSource? = null
        override var onTextTap: (() -> Unit)? = null
        private var adjustingSelection = false
        private var selectionActionMode: ActionMode? = null
        private var handlingNativeTouch = false
        private var tapStartedWithSelection = false
        private val tapDetector by lazy {
            GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent) = true
                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    if (!tapStartedWithSelection && !hasSelection()) performClick()
                    return false
                }
            })
        }

        override fun performClick(): Boolean {
            val onTap = onTextTap
            if (onTap == null || handlingNativeTouch) return super.performClick()
            if (hasSelection()) return false
            super.performClick()
            onTap()
            return true
        }

        init {
            customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    selectionActionMode = mode
                    return true
                }
                override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
                override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
                override fun onDestroyActionMode(mode: ActionMode) { selectionActionMode = null }
            }
        }

        override fun onSelectionChanged(start: Int, end: Int) {
            super.onSelectionChanged(start, end)
            if (adjustingSelection || start < 0 || end < 0 || start == end) return
            val spannable = text as? Spannable ?: return
            val complete = completeFormulaSelection(spannable, start, end)
            if (complete.start == minOf(start, end) && complete.end == maxOf(start, end)) return
            adjustingSelection = true
            try {
                if (start < end) Selection.setSelection(spannable, complete.start, complete.end)
                else Selection.setSelection(spannable, complete.end, complete.start)
            } finally { adjustingSelection = false }
        }

        override fun onTextContextMenuItem(id: Int): Boolean {
            if (id == android.R.id.copy && hasSelection()) {
                val selected = selectedMarkdownText(text, selectionStart, selectionEnd)
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("选中的文字", selected))
                selectionActionMode?.finish()
                    ?: (text as? Spannable)?.let { Selection.setSelection(it, selectionEnd) }
                return true
            }
            return super.onTextContextMenuItem(id)
        }

        /**
         * 表格块不得抢占触摸：它没有文本选区，普通落点交给外层横向/纵向滚动。
         * 可选中的回答正文直接走 TextView 默认处理，以保留长按选字和链接点击。
         * 点击仍经 super.onTouchEvent → performClick，放行的落点本就不是本 View 的点击。
         */
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            // 可选中文本必须让 TextView 自己收到文字区域内的 ACTION_DOWN/MOVE/UP；
            // 如果公式异步加载导致原生 View 比 Compose 格位更高，超出实际文字行的
            // ACTION_DOWN 仍要放行，避免把下方图片的点击区域吃掉。
            if (selectable) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN && !isPointInsideText(event)) {
                    return false
                }
                if (onTextTap != null) {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        val spannable = text as? Spannable
                        tapStartedWithSelection = hasSelection() ||
                            (spannable != null && isPointInsideClickableSpan(spannable, event))
                    }
                    // Let the editor process selection first. Its own click dispatch
                    // must not also invoke editing; the detector handles only short taps.
                    handlingNativeTouch = true
                    val handled = try { super.onTouchEvent(event) }
                        finally { handlingNativeTouch = false }
                    tapDetector.onTouchEvent(event)
                    return handled
                }
                return super.onTouchEvent(event)
            }
            val text = text as? Spannable
            if (text != null &&
                (event.actionMasked == MotionEvent.ACTION_DOWN ||
                    event.actionMasked == MotionEvent.ACTION_UP)
            ) {
                if (!isPointInsideClickableSpan(text, event)) {
                    // 没有落在可点片段上 ⇒ 明确放行，交给父级（图片的 clickable、
                    // 表格的 horizontalScroll、页面的纵向滚动）。
                    // ACTION_DOWN 返回 false 会让后续 MOVE/UP 不再派发给本 View，
                    // 这正合我们要的语义。
                    return false
                }
            }
            return super.onTouchEvent(event)
        }
    }.apply {
        // The database owns answer text; restoring a detached View's saved text can revive a partial.
        isSaveEnabled = false
        setTextColor(textColor)
        setLinkTextColor(linkColor)
        // selectable=false 用于表格块：setTextIsSelectable(true) 会顺带 setClickable(true)
        // + setLongClickable(true)，把 TextView 变成一个「点击可聚焦」的 View。在真机的
        // 触摸管线上，这类 View 有可能先于父级手势吃掉触摸事件，让外层的横向滚动拖不动。
        // 表格以「读 + 横向拖动」为主，牺牲单元格内的长按选中是划算的。
        if (selectable) {
            setTextIsSelectable(true)
            // TextView 的可聚焦/长按属性必须保留，系统才能在原气泡中显示选区和复制菜单。
            // 图片和表格是独立的 Compose 子项，不会再被气泡级长按处理器拦截。
            movementMethod = LinkMovementMethod.getInstance()
        } else {
            // 表格块：**尽量不给 MovementMethod**。
            // LinkMovementMethod 继承 ScrollingMovementMethod，只要表格内容比格位高，
            // 用户就能在表格里上下拖动文字，和页面的纵向滚动直接打架（用户反馈
            // 「表格上下拖动与整个页面上下滚动冲突」）。表格的横向滚动由外层 Compose 的
            // horizontalScroll 负责，纵向完全交给页面；单元格内既不需要滚动也不需要选中。
            //
            // ⚠️ 但「设成 null」**拦不住 Markwon**：`CorePlugin.afterSetText()` 会在每次
            // setText 之后检查，只要发现 `getMovementMethod() == null` 就**强行塞进**
            // LinkMovementMethod：
            //
            //     public void afterSetText(TextView view) {
            //         if (!hasExplicitMovementMethod && view.getMovementMethod() == null) {
            //             view.setMovementMethod(LinkMovementMethod.getInstance());
            //         }
            //     }
            //
            // 而 `setMovementMethod` 又会通过 AOSP 的 `fixFocusableAndClickableSettings()`
            // 把 clickable / longClickable / focusable 一并打开（探针实测：表格块渲染后
            // 三个属性全是 true）。所以表格块同样会抢占触摸 —— 这正是「末尾表格拖不动」
            // 反复不愈的机制。**唯一可靠的防线是上面重写的 `onTouchEvent`**：表格块的内容
            // 里没有 `ClickableSpan`，于是任何触摸都会被它判定为「未命中可点片段」而放行。
            //
            // 这里仍然设置成 null（意图明确、且 Markwon 若未来版本尊重该值就能直接生效），
            // 但不再依赖它 —— 真正的保障在 `onTouchEvent`。
            movementMethod = null
        }
        if (fontSizePx == null) textSize = MARKDOWN_TEXT_SIZE_SP
        else setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontSizePx)
        // Italic glyphs can extend past their measured advance (especially f/t).
        // Reserve an em-relative gutter, including when the user enlarges text.
        // TextView also clips *inside* its padding: a transparent shadow expands
        // that native clip into the gutter without painting a visible shadow.
        // Keep the outer Compose clip so text never covers adjacent image/table blocks.
        val glyphGutter = kotlin.math.ceil(this.textSize * 0.25f).toInt()
        setPadding(glyphGutter, paddingTop, glyphGutter, paddingBottom)
        setShadowLayer(glyphGutter.toFloat(), 0f, 0f, android.graphics.Color.TRANSPARENT)
        val fallbackSizePx = this.textSize * 14f / MARKDOWN_TEXT_SIZE_SP
        // A previous asynchronous load can fail after the View has started rendering new input.
        // Keep its original input alive with its drawable, rather than logging only the latest source.
        val latexSources = Collections.synchronizedMap(WeakHashMap<AsyncDrawable, MarkdownRenderSource>())
        val renderer = Markwon.builder(context)
            .usePlugin(object : io.noties.markwon.AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: io.noties.markwon.MarkwonConfiguration.Builder) {
                    builder.linkResolver { view, link ->
                        val uri = android.net.Uri.parse(link)
                        if (uri.scheme == "moge-document") com.moge.app.ui.document.DocumentPreviewActivity.openLink(view.context, uri)
                        else runCatching { view.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
                    }
                }
            })
            .textSetter { view, markdown, bufferType, onComplete ->
                (view as? MarkdownRenderSourceOwner)?.renderSource?.let { source ->
                    synchronized(latexSources) {
                        markdown.getSpans(0, markdown.length, JLatexAsyncDrawableSpan::class.java).forEach { span ->
                            latexSources[span.drawable] = source
                        }
                    }
                }
                val theme = (view.tag as Markwon).configuration().theme()
                view.setText(baselineAlignedLatex(markdown, theme), bufferType)
                onComplete.run()
            }
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(TablePlugin.create(tableThemeFor(context)))
            .usePlugin(
                JLatexMathPlugin.create(this.textSize) { builder ->
                    builder.inlinesEnabled(true)
                    builder.theme().textColor(textColor)
                    // 单条公式解析失败时画占位，绝不让 ParseException 冒泡成整页闪退。
                    // Preserve complete TeX (including Chinese text) in the source fallback,
                    // and retain the original input in the log before any display-only repairs.
                    builder.errorHandler { latex, error ->
                        val source = synchronized(latexSources) {
                            latexSources.entries.firstOrNull { it.key.destination == latex }?.value
                        } ?: renderSource
                        Log.w(RENDER_LOG_TAG, "latex render failed: $latex", error)
                        appendRenderErrorLog(
                            context,
                            "LATEX-PIECE:\n$latex\n---",
                            error,
                            originalMarkdown = source?.markdown,
                        )
                        LatexFallbackDrawable(
                            textColor,
                            fallbackSizePx,
                            latex,
                            error.javaClass.simpleName,
                            maxWidthPx = source?.formulaWidthPx ?: (fallbackSizePx * 28).toInt(),
                        )
                    }
                },
            )
            .build()
        tag = renderer
    }

/**
 * 触摸点是否落在一个 `ClickableSpan` 上（判定用 widget 内坐标，与本 View 的滚动偏移无关）。
 *
 * 计算方式与 AOSP `LinkMovementMethod.onTouchEvent` 保持一致：先把坐标换算到
 * `Layout` 的坐标系（减内边距、加滚动量），再交给 `Layout` 反查字符偏移。
 *
 * 任何一步越界都返回 false —— 「判不出来」时必须**放行**触摸，宁可让链接偶尔点不到，
 * 也不能因为判定异常而把下方图片的点击整片吃掉。
 */
private fun TextView.isPointInsideClickableSpan(text: Spannable, event: MotionEvent): Boolean {
    val textLayout = layout ?: return false
    return runCatching {
        val x = event.x.toInt() - totalPaddingLeft + scrollX
        val y = event.y.toInt() - totalPaddingTop + scrollY
        if (y < 0 || y >= textLayout.height) return@runCatching false
        val line = textLayout.getLineForVertical(y)
        if (x < textLayout.getLineLeft(line) || x > textLayout.getLineRight(line)) {
            return@runCatching false
        }
        val offset = textLayout.getOffsetForHorizontal(line, x.toFloat())
        text.getSpans(offset, offset, ClickableSpan::class.java).isNotEmpty()
    }.getOrDefault(false)
}

/** 只判断触点是否落在实际文字布局的垂直范围内，横向空白仍允许选中邻近字符。 */
private fun TextView.isPointInsideText(event: MotionEvent): Boolean {
    val textLayout = layout ?: return false
    val y = event.y.toInt() - totalPaddingTop + scrollY
    return y >= 0 && y < textLayout.height
}

/**
 * 渲染一次回答。
 *
 * 宽度为 0（还没完成布局）时**直接跳过**：以前这里会用屏幕宽度兜底，
 * 而气泡很可能只有 480～760dp，于是长公式不拆、直接溢出被裁；
 * 现在等 [widthPx] 到位后再渲染，最多晚一帧，不会错。
 */
internal fun renderMarkdown(
    view: TextView,
    content: String,
    widthPx: Int,
    textColor: Int,
    linkColor: Int,
) {
    if (widthPx <= 0) return
    rememberMarkdownRenderSource(view, content, widthPx)
    view.setTextColor(textColor)
    view.setLinkTextColor(linkColor)
    val rendered = wrapLongFormulas(
        sanitizeReplyLatex(normalizeReplyMarkdown(content)),
        formulaMaxWidthPx(view, widthPx),
        formulaWidthMeasurer(view),
    )
    runCatching {
        (view.tag as Markwon).setMarkdown(view, rendered)
        // 重渲染后复位内部滚动：LinkMovementMethod 继承自 ScrollingMovementMethod，
        // 内容比框高的瞬间用户能把文字拖出偏移，重新渲染时必须清零，
        // 否则新内容会带着旧的滚动偏移显示（头尾被挡的观感来源之一）。
        view.scrollTo(0, 0)
    }.onFailure { error ->
        Log.e(RENDER_LOG_TAG, "markdown render failed, fallback to plain text", error)
        appendRenderErrorLog(view.context, rendered, error, originalMarkdown = content)
        view.text = buildString {
            append(content)
            append("\n\n[部分内容无法渲染，已回退为纯文本]")
        }
    }
}

internal const val RENDER_LOG_TAG = "MarkdownAnswer"

/**
 * Formula failure remains explicit, but the full source is readable at the available width.
 * Android text layout renders CJK text and wraps source without deleting tokens or newlines.
 */
internal class LatexFallbackDrawable(
    private val textColor: Int,
    textSizePx: Float,
    val rawLatex: String,
    errorType: String? = null,
    maxWidthPx: Int = (textSizePx * 28).toInt(),
) : Drawable() {
    private val title = if (errorType != null) "⚠ 公式无法渲染 ($errorType)" else "⚠ 公式无法渲染"
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1F000000
        style = Paint.Style.FILL
    }
    private val foreground = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        alpha = 0xB0
        this.textSize = textSizePx
    }
    private val sourcePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        alpha = 0x90
        this.textSize = textSizePx * 0.85f
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val layoutWidth = (minOf(
        maxWidthPx.coerceAtLeast(25),
        kotlin.math.ceil(maxOf(foreground.measureText(title),
            rawLatex.lineSequence().maxOfOrNull { sourcePaint.measureText(it) } ?: 0f) + 24).toInt(),
    ) - 24).coerceAtLeast(1)
    private val titleLayout = StaticLayout.Builder.obtain(title, 0, title.length, foreground, layoutWidth)
        .setIncludePad(true).build()
    internal val sourceLayout = StaticLayout.Builder.obtain(rawLatex, 0, rawLatex.length, sourcePaint, layoutWidth)
        .setIncludePad(true).build()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val saved = canvas.save()
        try {
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.scale(b.width().toFloat() / intrinsicWidth, b.height().toFloat() / intrinsicHeight)
            canvas.drawRoundRect(0f, 0f, intrinsicWidth.toFloat(), intrinsicHeight.toFloat(), 10f, 10f, background)
            canvas.translate(12f, 10f)
            titleLayout.draw(canvas)
            canvas.translate(0f, titleLayout.height + 4f)
            sourceLayout.draw(canvas)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    override fun setAlpha(alpha: Int) {
        foreground.alpha = alpha
        sourcePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        foreground.colorFilter = colorFilter
        sourcePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = layoutWidth + 24

    override fun getIntrinsicHeight(): Int = titleLayout.height + sourceLayout.height + 24
}

/** 渲染失败的原文与异常写入本机日志，便于事后定位是哪类回答触发的。 */
private val RENDER_LOG_EXECUTOR = Executors.newSingleThreadExecutor()

internal fun appendRenderErrorLog(context: Context, markdown: String, error: Throwable, originalMarkdown: String? = null) {
    runCatching {
        RENDER_LOG_EXECUTOR.execute {
            runCatching {
                val file = File(context.filesDir, "assistant-render.log")
                val entry = formatRenderErrorEntry(markdown, error, originalMarkdown)
                val kept = if (file.isFile && file.length() <= 200_000) file.readText() else ""
                file.writeText(kept + entry)
            }
        }
    }
}

/** Full source is diagnostic data: never shorten it to the old 60/1500-character limits. */
internal fun formatRenderErrorEntry(markdown: String, error: Throwable, originalMarkdown: String? = null): String = buildString {
    appendLine("=== ${Instant.now()} ===")
    appendLine("error: ${error::class.java.simpleName}: ${error.message}")
    if (originalMarkdown != null) {
        appendLine("ORIGINAL-MARKDOWN:")
        appendLine(originalMarkdown)
    }
    appendLine(markdown)
    appendLine()
}

/**
 * 公式可用宽度：TextView 实测宽度减去左右留白。
 *
 * 只认实测宽度——屏幕宽度在平板上远大于气泡宽度，用它兜底等于「不拆公式」。
 */
internal fun formulaMaxWidthPx(view: TextView, measuredWidthPx: Int): Int {
    val metrics = view.resources.displayMetrics
    val padding = (metrics.density * 24).toInt()
    return (measuredWidthPx - view.totalPaddingLeft - view.totalPaddingRight - padding)
        .coerceAtLeast((metrics.density * 120).toInt())
}

/** 优先用 JLatexMath 真实测量公式宽度；测量失败时按字符数估算。 */
internal fun formulaWidthMeasurer(view: TextView): (String) -> Int {
    val textSizePx = 17f * view.resources.displayMetrics.scaledDensity
    return { latex ->
        runCatching { JLatexMathDrawable.builder(latex).textSize(textSizePx).build().intrinsicWidth }
            .getOrDefault((latex.length * textSizePx * 0.62f).toInt())
    }
}
