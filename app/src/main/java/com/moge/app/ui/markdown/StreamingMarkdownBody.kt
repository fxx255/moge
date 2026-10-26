package com.moge.app.ui.markdown

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.Log
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.moge.app.R
import com.moge.app.data.parse.prepareReplyMarkdown
import com.moge.app.data.parse.wrapLongFormulas
import com.moge.app.ui.localization.rememberAppStrings
import com.moge.app.ui.theme.LocalAppTypeface
import com.moge.app.ui.theme.MogeTheme
import io.noties.markwon.Markwon
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** One reading tree survives receive, completion and interruption. Only safe ink is exposed. */
@Composable
internal fun StreamingMarkdownBody(
    content: String,
    streaming: Boolean = true,
    imagePaths: List<String> = emptyList(),
    appendUnreferencedImages: Boolean = true,
    onImageClick: (List<String>, Int) -> Unit = { _, _ -> },
) {
    val tokenizer = remember { StreamingMarkdownTokenizer() }
    val latestContent = rememberUpdatedState(content)
    var snapshot by remember { mutableStateOf(tokenizer.update(content, flush = !streaming)) }
    var renderedContent by remember { mutableStateOf(content) }
    var hasStreamed by remember { mutableStateOf(streaming) }
    if (streaming) SideEffect { hasStreamed = true }
    LaunchedEffect(tokenizer, streaming, if (streaming) null else content) {
        if (!streaming) {
            snapshot = tokenizer.update(latestContent.value, flush = true)
            renderedContent = latestContent.value
        } else while (isActive) {
            val next = latestContent.value
            if (next != renderedContent) {
                snapshot = tokenizer.update(next)
                renderedContent = next
            }
            delay(80)
        }
    }
    val motion = MogeTheme.motionEnabled
    val animate = hasStreamed && motion
    val visiblePaths = remember(imagePaths) { imagePaths.filter(String::isNotBlank) }
    // Keep consecutive paragraphs in one native view so selection can continue
    // across them. Only actual tables and figures divide the reading surface.
    val displayed = renderedContent.take(snapshot.visibleEnd.coerceIn(0, renderedContent.length))
        .trimEnd('\r', '\n')
    val seen = remember(displayed) { mutableSetOf<String>() }
    val segments = remember(displayed) { splitFigureSegments(displayed, 512, seen) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            segments.forEachIndexed { index, segment ->
                if (segment.figureIndex != null) {
                    val figure = segment.figureIndex
                    key("figure-$figure") {
                        val path = imagePaths.getOrNull(figure)
                        StreamingFigureSlot(figure, path, streaming) {
                            val visibleIndex = imagePaths.take(figure).count(String::isNotBlank)
                            onImageClick(visiblePaths, visibleIndex)
                        }
                    }
                } else if (segment.text.isNotBlank()) {
                    key("ink-$index") {
                        val chunks = remember(segment.text) { splitMarkdownTableBlocks(segment.text) }
                        chunks.forEachIndexed { chunkIndex, chunk -> key(chunkIndex) {
                            if (chunk.isTable) MarkdownTableBlock(chunk.text, animateRows = animate, catchingUp = !streaming)
                            else {
                                val raw = remember(chunk.text) { StreamingMarkdownTokenizer().update(chunk.text) }
                                val whole = !streaming || index < segments.lastIndex || chunkIndex < chunks.lastIndex
                                val ink = raw.copy(blocks = emptyList(), tail = chunk.text, tailStart = 0,
                                    visibleEnd = chunk.text.length,
                                    renderedPrefixEnd = if (whole) chunk.text.length else raw.renderedPrefixEnd)
                                StreamingMarkdownChunk(ink, animate, catchingUp = !streaming)
                            }
                        } }
                    }
                }
            }
        if (streaming && snapshot.waiting) {
            val strings = rememberAppStrings()
            val modifier = Modifier.size(16.dp).semantics { contentDescription = strings.text(R.string.ui_generating_5f576) }
            if (motion) CircularProgressIndicator(modifier = modifier, strokeWidth = 2.dp)
            else CircularProgressIndicator(progress = { .65f }, modifier = modifier, strokeWidth = 2.dp)
        }
        if (!streaming && appendUnreferencedImages) imagePaths.forEachIndexed { index, path ->
            if ((index + 1).toString() !in seen) key("unreferenced-$index") {
                StreamingFigureSlot(index, path, false) {
                    onImageClick(visiblePaths, imagePaths.take(index).count(String::isNotBlank))
                }
            }
        }
    }
}

private data class StreamingMarkdownRenderCache(
    val tailId: Long, val prefix: String, val widthPx: Int,
    val textColor: Int, val linkColor: Int, val renderedPrefix: Spanned,
)

@Composable
internal fun StreamingMarkdownChunk(snapshot: StreamingMarkdownSnapshot, animate: Boolean = false, catchingUp: Boolean = false) {
    val selectionScrollHost = LocalSelectionScrollHost.current
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val surface = MaterialTheme.colorScheme.surface.toArgb()
    val typeface = LocalAppTypeface.current
    val locale = LocalConfiguration.current.locales.toLanguageTags()
    val fontSize = with(LocalDensity.current) { MARKDOWN_TEXT_SIZE_SP.sp.toPx() }
    val reveal = remember { StreamingInkReveal() }
    reveal.enabled = animate
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        key(typeface, locale, textColor, linkColor, surface, fontSize, selectionScrollHost) {
            AndroidView(modifier = Modifier.fillMaxWidth().clipToBounds(), factory = { context ->
                createMarkdownTextView(context, textColor, linkColor, fontSizePx = fontSize,
                    selectionScrollHost = selectionScrollHost, selectionSurfaceColor = surface,
                    bodyTypeface = typeface, inkReveal = reveal).also {
                    renderStreamingMarkdown(it, snapshot, width, textColor, linkColor)
                    reveal.update(it, catchingUp)
                }
            }, update = {
                renderStreamingMarkdown(it, snapshot, width, textColor, linkColor)
                reveal.update(it, catchingUp)
            })
        }
    }
}

/** Cache closed syntax, append only safe literal prose, and premeasure new formula ink. */
internal fun renderStreamingMarkdown(view: TextView, snapshot: StreamingMarkdownSnapshot,
    widthPx: Int, textColor: Int, linkColor: Int) {
    val tail = snapshot.tail.take((snapshot.visibleEnd - snapshot.tailStart).coerceIn(0, snapshot.tail.length))
    rememberMarkdownRenderSource(view, tail, widthPx)
    view.setTextColor(textColor)
    view.setLinkTextColor(linkColor)
    val prefixLength = (snapshot.renderedPrefixEnd - snapshot.tailStart).coerceIn(0, tail.length)
    if (prefixLength == 0 || widthPx <= 0) {
        if (view.text.toString() != tail) view.text = tail
        view.setTag(R.id.streaming_markdown_cache, null)
        return
    }
    val source = tail.substring(0, prefixLength)
    val previous = (view.getTag(R.id.streaming_markdown_cache) as? StreamingMarkdownRenderCache)?.takeIf {
        it.widthPx == widthPx && it.textColor == textColor && it.linkColor == linkColor
    }
    val cached = previous?.takeIf {
        it.tailId == snapshot.tailId && it.prefix == source && it.widthPx == widthPx &&
            it.textColor == textColor && it.linkColor == linkColor
    }
    val prefix = cached?.renderedPrefix ?: runCatching {
        val prepared = wrapLongFormulas(prepareReplyMarkdown(source),
            formulaMaxWidthPx(view, widthPx), formulaWidthMeasurer(view))
        val parsed = (view.tag as Markwon).toMarkdown(prepared)
        val resolved = if (previous != null && source.startsWith(previous.prefix))
            reuseReadyLatex(parsed, previous.renderedPrefix) else parsed
        readyLatex(view, resolved, widthPx, textColor, tail)
        // Markwon removes trailing block whitespace. The still-growing literal
        // suffix needs those paragraph separators before its first character.
        val whitespace = source.takeLastWhile(Char::isWhitespace)
        val retained = resolved.toString().takeLastWhile(Char::isWhitespace)
        if (whitespace.endsWith(retained)) SpannableStringBuilder(resolved)
            .append(whitespace.dropLast(retained.length)) else resolved
    }.onFailure {
        Log.e(RENDER_LOG_TAG, "buffered markdown render failed", it)
        appendRenderErrorLog(view.context, tail, it)
    }.getOrNull()
    if (prefix == null) { view.text = tail; return }
    if (cached == null) view.setTag(R.id.streaming_markdown_cache,
        StreamingMarkdownRenderCache(snapshot.tailId, source, widthPx, textColor, linkColor, prefix))
    val combined = SpannableStringBuilder(prefix).append(tail.substring(prefixLength))
    if (cached != null && view.text.toString() == combined.toString()) return
    runCatching {
        (view.tag as Markwon).setParsedMarkdown(view, combined)
        view.scrollTo(0, 0)
    }.onFailure {
        Log.e(RENDER_LOG_TAG, "buffered markdown display failed", it)
        appendRenderErrorLog(view.context, tail, it)
        view.text = tail
        view.setTag(R.id.streaming_markdown_cache, null)
    }
}
