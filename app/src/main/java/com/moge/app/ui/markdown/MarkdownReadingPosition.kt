package com.moge.app.ui.markdown

import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import kotlin.math.abs

internal val LocalReadingScrollHost = staticCompositionLocalOf<ReadingScrollHost?> { null }
private val LocalReadingPosition = staticCompositionLocalOf<MarkdownReadingPosition?> { null }

/** Browsing deltas exclude layout corrections, so a finishing reply can preserve a moving reader. */
internal class ReadingScrollHost(val scrollBy: (Float) -> Float) {
    var enabled = true
    var viewport = RectF()
    var readerScrollY = 0f
    var contentVersion = 0
        private set
    private var messageIds: List<String> = emptyList()

    fun updateMessages(ids: List<String>) {
        if (messageIds != ids) {
            messageIds = ids
            contentVersion++
        }
    }

}

/** Keep only an ephemeral on-screen anchor; reasoning and reading text are never persisted here. */
@Composable
internal fun PreserveMarkdownReadingPosition(generating: Boolean, content: @Composable () -> Unit) {
    val host = LocalReadingScrollHost.current
    val view = LocalView.current
    val position = remember(host, view) { host?.let { MarkdownReadingPosition(it, view) } }
    SideEffect { position?.setGenerating(generating) }
    DisposableEffect(position, view) {
        val observer = view.viewTreeObserver
        if (position != null) observer.addOnPreDrawListener(position)
        onDispose {
            if (position != null && observer.isAlive) observer.removeOnPreDrawListener(position)
        }
    }
    CompositionLocalProvider(LocalReadingPosition provides position, content = content)
}

internal class ReadingBlock {
    var contentKey: String? = null
    var view: TextView? = null
    var coordinates: LayoutCoordinates? = null

    fun bounds(): RectF? {
        // AndroidView's native position is synchronized during Compose drawing. Its Compose
        // slot already has the new lazy-list position before pre-draw, including scroll deltas.
        coordinates?.takeIf { it.isAttached }?.let { placed ->
            val origin = placed.positionInWindow()
            return RectF(origin.x, origin.y, origin.x + placed.size.width, origin.y + placed.size.height)
        }
        view?.let {
            if (!it.isAttachedToWindow || it.layout == null) return null
            val origin = IntArray(2)
            it.getLocationInWindow(origin)
            return RectF(origin[0].toFloat(), origin[1].toFloat(),
                (origin[0] + it.width).toFloat(), (origin[1] + it.height).toFloat())
        }
        return null
    }
}

@Composable
internal fun rememberReadingBlock(): ReadingBlock? {
    val position = LocalReadingPosition.current
    val block = remember(position) { position?.let { ReadingBlock() } }
    DisposableEffect(position, block) {
        if (block != null) position?.blocks?.add(block)
        onDispose { if (block != null) position?.blocks?.remove(block) }
    }
    return block
}

/** Figures and tables have a stable block edge even when no native prose line is visible. */
@Composable
internal fun markdownReadingBlockModifier(contentKey: String): Modifier {
    val block = rememberReadingBlock()
    SideEffect { block?.contentKey = contentKey }
    return Modifier.onGloballyPositioned { block?.coordinates = it }
}

private data class ReadingAnchor(
    val block: ReadingBlock,
    val text: CharSequence?,
    val offset: Int,
    val y: Float,
    val readerScrollY: Float,
    val version: Int,
    val blockKey: String?,
)

/**
 * Capture the last displayed line before completion removes the scratch pad or inserts a summary.
 * Correct after native/Compose layout and before drawing; raw deltas leave ongoing drags/flings alive.
 * A correction also requests Android layout, so the next pre-draw sees updated Compose placement.
 */
private class MarkdownReadingPosition(private val host: ReadingScrollHost, private val root: View) :
    ViewTreeObserver.OnPreDrawListener {
    val blocks = mutableListOf<ReadingBlock>()
    private var generating = false
    private var last: ReadingAnchor? = null
    private var restoring: ReadingAnchor? = null
    private var started = 0L
    private var frames = 0
    private var stableFrames = 0

    fun setGenerating(value: Boolean) {
        if (generating && !value) {
            restoring = last
            last = null
            started = -1L
            frames = 0
            stableFrames = 0
        } else if (!generating && value) {
            last = null
            restoring = null
        }
        generating = value
    }

    override fun onPreDraw(): Boolean {
        if (!host.enabled) {
            last = null
            restoring = null
            return true
        }
        if (generating) {
            // Observe every frame, including lazy placement that reuses a recorded draw layer.
            last = capture()
            return true
        }
        val anchor = restoring ?: return true
        // Recreating a long native table can itself take over a second. The settling window
        // starts only once that first completed layout is ready, not while it is being built.
        val now = SystemClock.uptimeMillis()
        if (started < 0) started = now
        if (anchor.version != host.contentVersion || ++frames > 120 || now - started > 2_000) {
            restoring = null
            return true
        }
        val y = locate(anchor)
        if (y == null) {
            restoring = null
            return true
        }
        val target = anchor.y + host.readerScrollY - anchor.readerScrollY
        val delta = y - target
        if (abs(delta) > 1f) {
            stableFrames = 0
            val consumed = host.scrollBy(delta)
            if (abs(consumed) > 0.5f) {
                // A lazy scroll may schedule placement only. Request an Android traversal before
                // suppressing this draw, otherwise Compose's pending placement waits for drawing
                // and repeated pre-draws would keep correcting the same stale coordinates.
                root.requestLayout()
                root.postInvalidateOnAnimation()
                return false
            }
            // At a list boundary the exact old position can be impossible. Never trap rendering.
            restoring = null
        } else {
            stableFrames++
            // Final buffered ink and figure sizing may land on a later frame.
            if (stableFrames >= 3 && now - started >= 300) restoring = null
            else root.postInvalidateOnAnimation()
        }
        return true
    }

    private fun capture(): ReadingAnchor? {
        val viewport = host.viewport
        if (viewport.isEmpty) return null
        val (block, bounds) = blocks.mapNotNull { block -> block.bounds()?.let { block to it } }
            .filter { (block, _) -> block.view?.text?.isEmpty() != true }
            .filter { (_, bounds) -> RectF.intersects(bounds, viewport) }
            .minWithOrNull(compareBy<Pair<ReadingBlock, RectF>> { (_, bounds) -> maxOf(bounds.top, viewport.top) }
                .thenBy { (block, _) -> if (block.view != null) 0 else 1 }) ?: return null
        // If the question, header or draft is at the reading edge, let that edge remain in place.
        // Pinning a paragraph below it would unnecessarily move content the reader is still viewing.
        if (bounds.top > viewport.top) return null
        val view = block.view
        val layout = view?.layout
        val textTop = bounds.top + (view?.totalPaddingTop ?: 0) - (view?.scrollY ?: 0)
        val line = layout?.getLineForVertical((viewport.top - textTop).toInt().coerceAtLeast(0))
        return ReadingAnchor(block, view?.text, line?.let { layout.getLineStart(it) } ?: 0,
            if (line != null) textTop + layout.getLineTop(line) else bounds.top,
            host.readerScrollY, host.contentVersion, block.contentKey)
    }

    private fun locate(anchor: ReadingAnchor): Float? {
        if (anchor.text == null) {
            val block = anchor.block.takeIf { it in blocks } ?: anchor.blockKey?.let { key ->
                // Inserting a prose summary can recreate the table at a different chunk index.
                // An identical, unambiguous block still represents the same reading surface.
                blocks.singleOrNull { it.contentKey == key }
            }
            return block?.bounds()?.top
        }
        // Usually the original TextView survives. Context also covers a summary being prepended
        // or a final table/figure splitting the original native text into different blocks.
        val candidates = listOf(anchor.block) + blocks.filter { it !== anchor.block }
        for (block in candidates) {
            if (block !in blocks) continue
            val view = block.view ?: continue
            val offset = readingOffsetAfterEdit(anchor.text, view.text, anchor.offset) ?: continue
            val bounds = block.bounds() ?: continue
            val layout = view.layout ?: continue
            return bounds.top + view.totalPaddingTop - view.scrollY + layout.getLineTop(layout.getLineForOffset(offset))
        }
        return null
    }
}

/** Match surrounding prose instead of retaining a character offset into a newly prepended summary. */
internal fun readingOffsetAfterEdit(before: CharSequence, after: CharSequence, offset: Int): Int? {
    if (before.isEmpty() || after.isEmpty()) return null
    val at = offset.coerceIn(0, before.length - 1)
    if (before === after) return at
    val old = before.toString()
    val new = after.toString()
    if (new.startsWith(old)) return at
    if (new.endsWith(old)) return new.length - old.length + at
    for (radius in listOf(64, 24, 8)) {
        val start = (at - radius).coerceAtLeast(0)
        val end = (at + radius).coerceAtMost(old.length)
        val context = old.substring(start, end)
        var found = new.indexOf(context)
        var closest: Int? = null
        while (found >= 0) {
            val candidate = found + at - start
            if (closest == null || abs(candidate - at) < abs(closest - at)) closest = candidate
            found = new.indexOf(context, found + 1)
        }
        if (closest != null) return closest
    }
    return null
}
