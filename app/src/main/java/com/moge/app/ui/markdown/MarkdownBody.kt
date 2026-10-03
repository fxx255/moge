package com.moge.app.ui.markdown

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.Log
import android.widget.TextView
import com.moge.app.ui.components.excludePageSwipe
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.moge.app.R
import com.moge.app.data.llm.isPlaceholderReply
import com.moge.app.data.parse.findFigureAnchors
import com.moge.app.data.parse.normalizeReplyMarkdown
import com.moge.app.data.parse.sanitizeReplyLatex
import com.moge.app.data.parse.wrapLongFormulas
import io.noties.markwon.Markwon
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Stable, throttled rendering for an answer that is still arriving.
 *
 * Markwon and JLatexMath need a complete Markdown/LaTeX span before they can
 * measure it. Feeding the whole answer to Markwon for every token makes an
 * unfinished formula acquire a new height repeatedly, which moves the rest of
 * the chat while the answer is being generated. The tokenizer therefore keeps
 * the unfinished suffix as plain text and only hands closed blocks to the
 * normal Markdown renderer. A small ticker limits AndroidView/Markwon updates
 * to at most one batch roughly every 80 ms while preserving the final full
 * rendering path once streaming ends.
 */
@Composable
internal fun StreamingMarkdownBody(content: String) {
    val tokenizer = remember { StreamingMarkdownTokenizer() }
    val latestContent = rememberUpdatedState(content)
    var snapshot by remember { mutableStateOf(tokenizer.update(content)) }
    var renderedContent by remember { mutableStateOf(content) }

    LaunchedEffect(tokenizer) {
        while (isActive) {
            val nextContent = latestContent.value
            if (nextContent != renderedContent) {
                snapshot = tokenizer.update(nextContent)
                renderedContent = nextContent
            }
            delay(STREAMING_MARKDOWN_TICK_MS)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        snapshot.blocks.forEach { block ->
            key("stream-block-${block.id}") {
                MarkdownAnswer(block.text)
            }
        }
        if (snapshot.tail.isNotEmpty()) {
            key("stream-tail-${snapshot.tailId}") {
                var maxTailHeightPx by remember { mutableIntStateOf(0) }
                val density = LocalDensity.current
                Box(
                    modifier = Modifier
                        .heightIn(min = with(density) { maxTailHeightPx.toDp() })
                        .onSizeChanged { maxTailHeightPx = maxOf(maxTailHeightPx, it.height) },
                ) {
                    // The same TextView remains mounted while the paragraph
                    // grows. Closed formulas are parsed once, then subsequent
                    // source text is appended as plain text to the cached
                    // Spanned; opening another formula cannot make the first
                    // one disappear or schedule it for a fresh parse.
                    StreamingMarkdownChunk(snapshot)
                }
            }
        }
    }
}

private const val STREAMING_MARKDOWN_TICK_MS = 80L

private data class StreamingMarkdownRenderCache(
    val tailId: Long,
    val prefixLength: Int,
    val widthPx: Int,
    val textColor: Int,
    val linkColor: Int,
    val renderedPrefix: Spanned,
)

@Composable
internal fun StreamingMarkdownChunk(snapshot: StreamingMarkdownSnapshot) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val fontSizePx = with(LocalDensity.current) { MARKDOWN_TEXT_SIZE_SP.sp.toPx() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        key(textColor, linkColor, fontSizePx) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().clipToBounds(),
                factory = { context ->
                    createMarkdownTextView(context, textColor, linkColor, fontSizePx = fontSizePx).also { view ->
                        // LazyColumn can measure a View before its first attached update.
                        renderStreamingMarkdown(view, snapshot, widthPx, textColor, linkColor)
                    }
                },
                update = { view ->
                    renderStreamingMarkdown(view, snapshot, widthPx, textColor, linkColor)
                },
            )
        }
    }
}

/** Reuse already parsed formula spans while appending the still-growing suffix. */
internal fun renderStreamingMarkdown(
    view: TextView,
    snapshot: StreamingMarkdownSnapshot,
    widthPx: Int,
    textColor: Int,
    linkColor: Int,
) {
    view.setTextColor(textColor)
    view.setLinkTextColor(linkColor)
    val prefixLength = (snapshot.mathPrefixEnd - snapshot.tailStart).coerceIn(0, snapshot.tail.length)
    if (prefixLength == 0 || widthPx <= 0) {
        // Before the first formula closes, there is no Markdown work to do.
        // Keep the same TextView so a later formula does not swap UI types.
        view.text = snapshot.tail
        view.setTag(R.id.streaming_markdown_cache, null)
        return
    }
    val old = view.getTag(R.id.streaming_markdown_cache) as? StreamingMarkdownRenderCache
    val cached = old?.takeIf {
        it.tailId == snapshot.tailId && it.prefixLength == prefixLength &&
            it.widthPx == widthPx && it.textColor == textColor && it.linkColor == linkColor
    }
    val prefix = cached?.renderedPrefix ?: runCatching {
        val source = snapshot.tail.substring(0, prefixLength)
        val prepared = wrapLongFormulas(
            sanitizeReplyLatex(normalizeReplyMarkdown(source)),
            formulaMaxWidthPx(view, widthPx),
            formulaWidthMeasurer(view),
        )
        (view.tag as Markwon).toMarkdown(prepared)
    }.onFailure { error ->
        Log.e(RENDER_LOG_TAG, "streaming formula render failed, fallback to plain text", error)
        appendRenderErrorLog(view.context, snapshot.tail, error)
    }.getOrNull()
    if (prefix == null) {
        view.text = snapshot.tail
        view.setTag(R.id.streaming_markdown_cache, null)
        return
    }
    if (cached == null) {
        view.setTag(R.id.streaming_markdown_cache,
            StreamingMarkdownRenderCache(snapshot.tailId, prefixLength, widthPx,
                textColor, linkColor, prefix))
    }
    val combined = SpannableStringBuilder(prefix)
        .append(snapshot.tail.substring(prefixLength))
    runCatching {
        (view.tag as Markwon).setParsedMarkdown(view, combined)
        view.scrollTo(0, 0)
    }.onFailure { error ->
        Log.e(RENDER_LOG_TAG, "streaming formula display failed, fallback to plain text", error)
        appendRenderErrorLog(view.context, snapshot.tail, error)
        view.text = snapshot.tail
        view.setTag(R.id.streaming_markdown_cache, null)
    }
}

internal fun isReplyPlaceholder(content: String): Boolean =
    isPlaceholderReply(content)

/**
 * 回答正文渲染。
 *
 * 模型偶尔会输出 JLatexMath 解析不了的 LaTeX（缺右括号、残留 \tag、aligned 前导非法字符等），
 * 插件默认行为是把 ParseException 包成 RuntimeException 抛出，会在 UI 线程把 App 带崩。
 * 这里做三层兜底：单条公式失败画占位 → 整段渲染失败回退纯文本 → 异常写入本地日志便于定位。
 */
/**
 * 表格画布相对气泡宽度的**最大**放大倍数。
 *
 * 只在表格自然宽度放不下时才撑宽，且撑到「刚好够」为止。早期版本是**无条件**乘 1.8，
 * 于是只有两三列短文字的窄表格也被拉成 1.8 倍 —— 用户反馈「宽度富裕很多但依然控了
 * 很大」，右侧一大半被顶到屏幕外，还得手动横拖才看得到。现在 1.8 只是上限。
 */
internal const val TABLE_MAX_WIDTH_FACTOR = 1.8f

/** 正文 Markdown 字号（sp）。估算表格自然宽度时必须与 TextView 的实际字号一致。 */
internal const val MARKDOWN_TEXT_SIZE_SP = 17f

/**
 * 估算表格宽度时的安全余量。
 *
 * 估算本身是近似的（中英文混排、行内公式都算不精确），留点余量避免「刚好差一点」
 * 导致单元格被换行挤压。宁可多撑一点点，也不要把公式挤变形。
 */

/** 一段回答的渲染片段。 */
internal data class MarkdownChunkSpec(val text: String, val isTable: Boolean)

/**
 * 把回答按「表格块 / 非表格块」切开。
 *
 * 目的是让渲染层单独对表格启用横向滚动：Markwon 的表格会压缩到容器宽度，
 * 窄气泡里单元格内容（尤其行内公式）会被挤成一团——公式被压成小字号、
 * 中文竖排。切开后表格按更宽的画布绘制，放不下时可以左右滑。
 * 代码围栏内的 `|` 不算表格。
 */
internal fun splitMarkdownTableBlocks(markdown: String): List<MarkdownChunkSpec> {
    val lines = markdown.split('\n')
    val chunks = mutableListOf<MarkdownChunkSpec>()
    val text = StringBuilder()
    var fence: String? = null

    fun flushText() {
        if (text.isNotEmpty()) {
            chunks += MarkdownChunkSpec(text.toString().trimEnd('\n'), isTable = false)
            text.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            val marker = if (trimmed.startsWith("```")) "```" else "~~~"
            fence = if (fence == null) marker else if (fence == marker) null else fence
            text.append(line).append('\n')
            i++
            continue
        }
        if (fence != null) {
            text.append(line).append('\n')
            i++
            continue
        }
        val header = lines.getOrNull(i + 1)
        if ('|' in line && header != null && isTableSeparator(header)) {
            flushText()
            val table = StringBuilder().append(line).append('\n').append(header).append('\n')
            var j = i + 2
            while (j < lines.size && '|' in lines[j]) {
                table.append(lines[j]).append('\n')
                j++
            }
            chunks += MarkdownChunkSpec(table.toString().trimEnd('\n'), isTable = true)
            i = j
            continue
        }
        text.append(line).append('\n')
        i++
    }
    flushText()
    return chunks
}

/** Markdown 表格的分隔行：`| --- | :--: |` 这类。 */
private fun isTableSeparator(line: String): Boolean {
    val trimmed = line.trim()
    if ('-' !in trimmed) return false
    val cells = trimmed.trim('|').split('|')
    return cells.isNotEmpty() && cells.all { cell ->
        val token = cell.trim()
        token.isNotEmpty() && token.all { it == '-' || it == ':' } && '-' in token
    }
}

/**
 * 助手回答正文：把生成的图表**插进正文里**，而不是统一堆在气泡末尾。
 *
 * v1.0.23 的 bug：`InlineGeneratedImage` 直接追加在 `MarkdownAnswer` 之后的
 * Column 末尾，于是「正文里说『见图 1』」和真正的图隔着好几段文字，
 * 用户反馈「图像没有嵌入在文字中间，而是附加在消息气泡末尾」。
 *
 * 现在的做法是解析正文里的插图锚点，按锚点把内容切成
 * 「文字段 / 图 / 文字段 / 图 …」交替渲染：
 * - 识别独立成行或夹在句子里的 `[[FIGURE:1]]`（1-based，对应图片槽位）
 * - 同一正文里重复引用只显示一次图，Markdown 代码示例里的标记保持原样
 * - 没有锚点时：正文照常渲染，图表统一接在末尾（旧行为兜底，不会丢图）
 *
 * @param segments 已解析出的「文字/图」交替片段
 */
@Composable
internal fun AnswerMarkdownBody(
    content: String,
    imagePaths: List<String>,
    onImageClick: (List<String>, Int) -> Unit,
) = AnswerMarkdownBody(content, imagePaths, onImageClick, appendUnreferencedImages = true)

@Composable
internal fun AnswerMarkdownBody(
    content: String,
    imagePaths: List<String>,
    onImageClick: (List<String>, Int) -> Unit,
    appendUnreferencedImages: Boolean,
) {
    val segments = remember(content, imagePaths.size) { splitFigureSegments(content, imagePaths.size) }
    val visibleIndices = remember(imagePaths) { imagePaths.indices.filter { imagePaths[it].isNotBlank() } }
    val viewerImages = remember(imagePaths) { visibleIndices.map { imagePaths[it] } }
    val hasInlineFigure = segments.any { it.figureIndex != null }
    if (!hasInlineFigure) {
        // 没有可用的图锚点时，正文中的缺图提示仍然保留，未引用图片照常接在末尾。
        val plainText = remember(segments) {
            segments.joinToString("\n\n") { it.text }.trim()
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (plainText.isNotBlank()) key("text") { MarkdownAnswer(plainText) }
            (if (appendUnreferencedImages) imagePaths else emptyList()).forEachIndexed { index, path ->
                key("figure-$index-$path") {
                    if (path.isBlank()) FailedFigureHint(index)
                    else InlineFigure(path = path, number = index + 1, onClick = { onImageClick(viewerImages, visibleIndices.indexOf(index)) })
                }
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEachIndexed { segmentIndex, segment ->
            key("seg-$segmentIndex-${segment.figureIndex?.let { imagePaths.getOrNull(it) }.orEmpty()}") {
                if (segment.figureIndex != null) {
                    val index = segment.figureIndex
                    // 槽位为空串表示该图渲染失败，保留编号并显示缺图提示。
                    val path = imagePaths.getOrNull(index)
                    if (!path.isNullOrBlank() && index in imagePaths.indices) {
                        InlineFigure(path = path, number = index + 1, onClick = { onImageClick(viewerImages, visibleIndices.indexOf(index)) })
                    } else if (index in imagePaths.indices) {
                        FailedFigureHint(index)
                    }
                } else if (segment.text.isNotBlank()) {
                    MarkdownAnswer(segment.text)
                }
            }
        }
        val anchored = segments.mapNotNull { it.figureIndex }.toSet()
        imagePaths.indices.filter { appendUnreferencedImages && it !in anchored }.forEach { index ->
            key("tail-$index-${imagePaths[index]}") {
                if (imagePaths[index].isBlank()) FailedFigureHint(index)
                else InlineFigure(
                    path = imagePaths[index],
                    number = index + 1,
                    onClick = { onImageClick(viewerImages, visibleIndices.indexOf(index)) },
                )
            }
        }
    }
}

/** 正文片段：要么是一段 Markdown 文字，要么指向某张生成图（0-based）。 */
internal data class FigureSegment(val text: String, val figureIndex: Int?)

/**
 * 按正文里的 `[[FIGURE:n]]` 切分，代码示例不参与。行内引用改为可读图号，
 * 图片放在该行完整文字之后，避免把标点或括号拆到图片的另一侧。
 * `figureCount` 是包含失败图片的槽位数；没有对应槽位时留下可读提示。
 * 同一张图只在首次引用处显示，后续行内引用转为「图 n」。
 */
internal fun splitFigureSegments(content: String, figureCount: Int): List<FigureSegment> {
    val anchors = findFigureAnchors(content)
    if (anchors.isEmpty()) return listOf(FigureSegment(content, null))

    val segments = mutableListOf<FigureSegment>()
    val seen = mutableSetOf<String>()
    val text = StringBuilder()
    val pendingInline = mutableListOf<Int>()
    var cursor = 0

    fun flushText() {
        val value = text.toString().trim('\r', '\n')
        if (value.isNotBlank()) segments += FigureSegment(value, null)
        text.clear()
    }

    fun flushInlineFigures() {
        if (pendingInline.isEmpty()) return
        flushText()
        pendingInline.forEach { segments += FigureSegment("", it) }
        pendingInline.clear()
    }

    fun appendUntil(end: Int) {
        val lineEnd = content.indexOf('\n', cursor)
        if (pendingInline.isNotEmpty() && lineEnd >= cursor && lineEnd < end) {
            text.append(content, cursor, lineEnd)
            flushInlineFigures()
            cursor = lineEnd + 1
        }
        text.append(content, cursor, end)
        cursor = end
    }

    anchors.forEach { anchor ->
        appendUntil(anchor.range.first)
        val number = anchor.numberText.trimStart('0').ifEmpty { "0" }
        val index = anchor.number?.takeIf { it > 0 }?.minus(1)
        if (!seen.add(number)) {
            if (!anchor.isStandalone) text.append("图 $number")
        } else if (index != null && index in 0 until figureCount) {
            if (anchor.isStandalone) {
                flushInlineFigures()
                flushText()
                segments += FigureSegment("", index)
            } else {
                text.append("图 $number")
                pendingInline += index
            }
        } else {
            text.append("（图 $number 未能生成）")
        }
        cursor = anchor.range.last + 1
    }
    appendUntil(content.length)
    flushInlineFigures()
    flushText()
    return segments.ifEmpty { listOf(FigureSegment("", null)) }
}

/**
 * 回答正文渲染。
 *
 * 模型偶尔会输出 JLatexMath 解析不了的 LaTeX（缺右括号、残留 \tag、aligned 前导非法字符等），
 * 插件默认行为是把 ParseException 包成 RuntimeException 抛出，会在 UI 线程把 App 带崩。
 * 这里做三层兜底：单条公式失败画占位 → 整段渲染失败回退纯文本 → 异常写入本地日志便于定位。
 *
 * 含表格的回答按块拆开渲染，表格单独走「更宽画布 + 横向滚动」。
 */
@Composable
internal fun MarkdownAnswer(content: String) {
    val chunks = remember(content) { splitMarkdownTableBlocks(content) }
    // 绝大多数回答不含表格：沿用原来的单块路径，零回归
    if (chunks.none { it.isTable }) {
        MarkdownChunk(content, fixedWidthPx = null)
        return
    }
    var containerWidthPx by remember { mutableIntStateOf(0) }
    // 估算表格自然宽度用的画笔。字号必须与 createMarkdownTextView 里的一致
    // （那里单位是 sp，这里要换算成 px），否则估算出来的宽度对不上真实渲染。
    val resources = LocalResources.current
    val metrics = resources.displayMetrics
    // 与 tableThemeFor() 保持同一套算法，避免两处算出不同的内边距
    val cellPaddingPx = (TABLE_CELL_PADDING_DP * metrics.density).toInt().coerceAtLeast(1)
    val fontSizePx = with(LocalDensity.current) { MARKDOWN_TEXT_SIZE_SP.sp.toPx() }
    val measurePaint = remember(fontSizePx) {
        android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizePx
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { containerWidthPx = it.width },
    ) {
        chunks.forEachIndexed { index, chunk ->
            // 显式 key：Column 的内容没有 key 时按**位置**做差量复用，上一帧的
            // [文字块, 表格块] 变成这一帧的 [文字块, 图, 表格块] 时，第 2 个槽位会被
            // 原地复用成「图」——而里面挂的 AndroidView 是真实 Android 视图，
            // 复用/重建时机比纯 Compose 节点脆弱得多，容易留下尺寸与位置都错配的旧视图，
            // 表现为后面的图与表格点不动、拖不动。给 key 让 Compose 按身份匹配。
            key(index) {
                // 注意：key 的 lambda 里**不能写 return@key**——那会让 Kotlin 给这个
                // 匿名函数生成非法方法名 `<anonymous>`，运行期直接 ClassFormatError
                // （编译能过、全套单测一起爆）。所以这里用 if/else 而不是提前返回。
                if (!chunk.isTable || containerWidthPx <= 0) {
                    // 非表格块正常铺满；表格块等测量到宽度再渲染，避免按 0 宽拆分公式
                    if (!chunk.isTable) MarkdownChunk(chunk.text, fixedWidthPx = null)
                } else {
                    // 按需撑宽：先估算表格的自然宽度，放得下就不撑，放不下才撑到刚好够，
                    // 上限 TABLE_MAX_WIDTH_FACTOR。
                    //
                    // 旧版是**无条件**乘 1.8，只有两三列短文字的窄表格也被拉成 1.8 倍
                    // （用户反馈「宽度富裕很多但依然控了很大」，右侧大半在屏幕外）。
                    // 估算本身是纯文本计算（微秒级），可以安全地在流式期间反复调用 ——
                    // 这也是没有改用「试排 + 实测」的原因，那样每帧都要完整排版一次。
                    val tableWidthPx = remember(chunk.text, containerWidthPx, cellPaddingPx, fontSizePx) {
                        val natural = estimateTableNaturalWidthPx(chunk.text, measurePaint, cellPaddingPx)
                        resolveTableWidthPx(containerWidthPx, natural)
                    }
                    val scroll = rememberScrollState()
                    // 宽表格可左右拖动。
                    //
                    // 两个必须同时成立的条件（缺一个就拖不动）：
                    // ① 外层 Box 用 wrapContentWidth 而不是 fillMaxWidth —— horizontalScroll 只在
                    //    「内容宽度 > 容器宽度」时才产生可滚动区间；若外层被 fillMaxWidth 撑满，
                    //    可滚动距离就是 0，横滑毫无反应；
                    // ② 内层 TextView 给足固定宽度 tableWidthPx（1.8 倍气泡宽），它才是那个「更宽的内容」。
                    //
                    // clipToBounds：内容是 1.8 倍宽的真实 Android 视图，若不显式裁剪，
                    // 溢出视口的部分会横向画到气泡外面，盖住旁边的文字（用户反馈的
                    // 「表格遮挡了部分文字内容」）。滚动容器本身不保证裁剪主轴。
                    Box(
                        modifier = Modifier
                            .wrapContentWidth()
                            // 与图片同理：抬高 zIndex，保证表格的横向拖动不会因为
                            // 上方文本块的高度偏差而被吃掉（用户反馈「末尾的表格拖不动」）。
                            .zIndex(FIGURE_Z_INDEX)
                            .excludePageSwipe().horizontalScroll(scroll, reverseScrolling = false)
                            .clipToBounds(),
                    ) {
                        // selectable=false：见 createMarkdownTextView 内注释。
                        // 表格块交给外层滚动处理手势，TextView 自己不参与触摸消费。
                        MarkdownChunk(
                            chunk.text,
                            fixedWidthPx = tableWidthPx,
                            selectable = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 由 AndroidView 的正常测量流程决定高度。
 * Markwon 在表格首次绘制和公式异步加载后会 setText/requestLayout，Interop 随之重新测量。
 * 不能用固定 height 缓存截住该请求，也不能在 Compose 布局之外反复 measure 同一个 View：
 * 前者留下陈旧的兄弟节点位置，后者让 View 的 measuredHeight 与 Compose 的格位不一致。
 */
private data class MarkdownRenderStamp(val key: String, val renderedText: String)

internal fun renderMarkdownIfChanged(
    view: android.widget.TextView,
    content: String,
    widthPx: Int,
    textColor: Int,
    linkColor: Int,
) {
    if (widthPx <= 0) return
    val renderKey = "$content|$widthPx|$textColor|$linkColor"
    val stamp = view.getTag(R.id.markdown_render_key) as? MarkdownRenderStamp
    if (stamp?.key == renderKey && stamp.renderedText == view.text.toString()) return
    renderMarkdown(view, content, widthPx, textColor, linkColor)
    view.setTag(R.id.markdown_render_key, MarkdownRenderStamp(renderKey, view.text.toString()))
}

@Composable
internal fun MarkdownChunk(
    content: String,
    fixedWidthPx: Int?,
    selectable: Boolean = true,
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val density = LocalDensity.current
    val fontSizePx = with(density) { MARKDOWN_TEXT_SIZE_SP.sp.toPx() }
    val widthModifier = if (fixedWidthPx != null) {
        Modifier.width(with(density) { fixedWidthPx.coerceAtLeast(1).toDp() })
    } else {
        Modifier.fillMaxWidth()
    }
    // Obtain the actual constrained width before creating the native View. Waiting
    // for onSizeChanged left its first LazyColumn placement measured as empty text;
    // detached/premeasured items could keep that single-line slot until another scroll.
    BoxWithConstraints(widthModifier) {
        val renderWidthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        key(textColor, linkColor, fontSizePx, selectable) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().clipToBounds(),
                factory = { context ->
                    createMarkdownTextView(context, textColor, linkColor,
                        selectable = selectable, fontSizePx = fontSizePx).also { view ->
                        renderMarkdownIfChanged(view, content, renderWidthPx, textColor, linkColor)
                    }
                },
                update = { view ->
                    // Also recover stale native text when an existing View reattaches.
                    renderMarkdownIfChanged(view, content, renderWidthPx, textColor, linkColor)
                },
            )
        }
    }
}
