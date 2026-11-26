package com.moge.app.ui.markdown

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Path
import android.view.animation.LinearInterpolator
import android.widget.TextView

/** Reveals already laid out ink; animation never feeds partial TeX back to the parser. */
internal class StreamingInkReveal(
    private val host: ReadingScrollHost? = null,
    private val block: ReadingBlock? = null,
) {
    var enabled = false
    private var previous = ""
    private var visible = 0f
    private var painted = 0f
    private var animator: ValueAnimator? = null
    private val path = Path()
    private val segment = Path()

    fun update(view: TextView, catchingUp: Boolean) {
        val text = markdownTextSnapshot(view)
        if (text == previous) {
            if (!enabled) finish(view)
            return
        }
        val append = text.startsWith(previous)
        previous = text
        animator?.cancel()
        animator = null
        // A prepended summary or a Markdown replacement edits already displayed prose.
        // Show that edit atomically instead of hiding and replaying the rest of the answer.
        if (!enabled || !append) { finish(view); return }
        val from = visible
        animator = ValueAnimator.ofFloat(from, text.length.toFloat()).apply {
            duration = ((text.length - from) * 10).toLong().coerceIn(70, if (catchingUp) 160 else 280)
            interpolator = LinearInterpolator()
            addUpdateListener {
                visible = it.animatedValue as Float
                // Keep the clock running offscreen. Repaint once the reader approaches the
                // last painted line, and always commit the final unclipped display list.
                if (visible >= text.length || !belowViewport(view, painted)) view.invalidate()
            }
            start()
        }
    }

    fun finish(view: TextView) {
        animator?.cancel()
        animator = null
        val end = view.text.length.toFloat()
        if (visible != end || painted != end) {
            visible = end
            view.invalidate()
        }
    }

    private fun belowViewport(view: TextView, offset: Float): Boolean {
        val viewport = host?.viewport ?: return false
        if (viewport.isEmpty) return false
        val layout = view.layout ?: return false
        val top = block?.bounds()?.top ?: return false
        val line = layout.getLineForOffset(offset.toInt().coerceIn(0, layout.text.length))
        return top + view.totalPaddingTop - view.scrollY + layout.getLineTop(line) > viewport.bottom
    }

    fun clip(canvas: Canvas, view: TextView) {
        painted = visible
        val layout = view.layout ?: return
        if (!enabled || visible >= view.text.length) return
        // Scrolling may replay this display list before another draw. The viewport can
        // suppress animation invalidations, but must never erase an already painted row.
        streamingInkPath(layout, view.text, visible, path, segment)
        path.offset((view.totalPaddingLeft - view.scrollX).toFloat(), (view.totalPaddingTop - view.scrollY).toFloat())
        canvas.clipPath(path)
    }
}
