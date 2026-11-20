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
internal fun PreserveMarkdownReadingPosition(generating: Boolean, reflowKey: Any? = null, content: @Composable () -> Unit) {
    val host = LocalReadingScrollHost.current
    val view = LocalView.current
    val position = remember(host, view) { host?.let { MarkdownReadingPosition(it, view) } }
    SideEffect { position?.update(generating, reflowKey) }
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
    val text: String?,
    val lineTop: Int?,
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
internal class MarkdownReadingPosition(private val host: ReadingScrollHost, private val root: View) :
    ViewTreeObserver.OnPreDrawListener {
    val blocks = mutableListOf<ReadingBlock>()
    private var generating = false
    private var reflowKey: Any? = null
    private var last: ReadingAnchor? = null
    private var restoring: ReadingAnchor? = null
    private var started = 0L
    private var frames = 0
    private var stableFrames = 0

    fun update(value: Boolean, key: Any?) {
        if (generating && (!value || reflowKey != key)) {
            // A summary can arrive in the last streaming update, before the saved
            // answer changes state. Keep an in-progress correction across that boundary.
            restoring = last ?: restoring
            last = null
            started = -1L
            frames = 0
            stableFrames = 0
        } else if (!generating && value) {
            last = null
            restoring = null
        }
        generating = value
        reflowKey = key
    }

    override fun onPreDraw(): Boolean {
        if (!host.enabled) {
            last = null
            restoring = null
            return true
        }
        if (generating && restoring == null) {
            val anchor = last
            // DynamicLayout can be replaced when only the tail changes. A replacement
            // is harmless if the displayed characters and their actual line stay put.
            val view = anchor?.block?.view
            val layout = view?.layout
            val currentText = view?.let(::markdownTextSnapshot)
            val reflowed = anchor != null && (anchor.block !in blocks ||
                (layout != null && anchor.text != null &&
                    ((currentText !== anchor.text && currentText?.startsWith(anchor.text) != true) ||
                        layout.getLineTop(layout.getLineForOffset(anchor.offset.coerceAtMost(view.text.length))) != anchor.lineTop)))
            if (!reflowed) {
                last = capture()
                return true
            }
            restoring = anchor
            last = null
            started = -1L
            frames = 0
            stableFrames = 0
        }
        val anchor = restoring ?: return true
        // Recreating a long native table can itself take over a second. The settling window
        // starts only once that first completed layout is ready, not while it is being built.
        val now = SystemClock.uptimeMillis()
        if (started < 0) started = now
        if (anchor.version != host.contentVersion || ++frames > 120 || now - started > 2_000) {
            restoring = null
            last = if (generating) capture() else null
            return true
        }
        val y = locate(anchor)
        if (y == null) {
            restoring = null
            last = if (generating) capture() else null
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
            // A later layout or gesture schedules its own pre-draw. Do not keep the
            // entire answer drawing at display refresh rate just to count stable frames.
        }
        // Only adopt a frame we actually allow on screen. If the reader moved
        // during settling, the next reflow belongs to this line, not the old block.
        if (generating || restoring != null) {
            last = capture()
            if (restoring != null && host.readerScrollY != anchor.readerScrollY) restoring = last
        }
        return true
    }

    private fun capture(): ReadingAnchor? {
        val viewport = host.viewport
        if (viewport.isEmpty) return null
        // Pin only a block crossing the reading edge. Prefer prose over an enclosing
        // non-text slot. If the question/header is at that edge, let it stay there.
        var candidate: ReadingBlock? = null
        var top = 0f
        for (block in blocks) {
            if (block.view?.text?.isEmpty() == true) continue
            val bounds = block.bounds() ?: continue
            if (bounds.top > viewport.top || !RectF.intersects(bounds, viewport)) continue
            candidate = block
            top = bounds.top
            if (block.view != null) break
        }
        val block = candidate ?: return null
        val view = block.view
        val layout = view?.layout
        val textTop = top + (view?.totalPaddingTop ?: 0) - (view?.scrollY ?: 0)
        val line = layout?.getLineForVertical((viewport.top - textTop).toInt().coerceAtLeast(0))
        return ReadingAnchor(block, view?.let(::markdownTextSnapshot), line?.let { layout.getLineTop(it) },
            line?.let { layout.getLineStart(it) } ?: 0,
            if (line != null) textTop + layout.getLineTop(line) else top,
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
        fun lineY(block: ReadingBlock): Float? {
            val view = block.view ?: return null
            val layout = view.layout ?: return null
            val offset = readingOffsetAfterEdit(anchor.text, markdownTextSnapshot(view), anchor.offset) ?: return null
            val bounds = block.bounds() ?: return null
            return bounds.top + view.totalPaddingTop - view.scrollY + layout.getLineTop(layout.getLineForOffset(offset))
        }
        if (anchor.block in blocks) lineY(anchor.block)?.let { return it }
        for (block in blocks) if (block !== anchor.block) lineY(block)?.let { return it }
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
