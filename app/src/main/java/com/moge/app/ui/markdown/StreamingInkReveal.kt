package com.moge.app.ui.markdown

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Path
import android.view.animation.LinearInterpolator
import android.widget.TextView

/** Reveals already laid out ink; animation never feeds partial TeX back to the parser. */
internal class StreamingInkReveal {
    var enabled = false
    private var previous = ""
    private var visible = 0f
    private var animator: ValueAnimator? = null
    private val path = Path()
    private val segment = Path()

    fun update(view: TextView, catchingUp: Boolean) {
        val text = markdownTextSnapshot(view)
        if (text == previous) {
            if (!enabled) finish(view)
            return
        }
        val common = previous.commonPrefixWith(text).length
        visible = minOf(visible, common.toFloat())
        previous = text
        animator?.cancel()
        if (!enabled) { finish(view); return }
        val from = visible
        animator = ValueAnimator.ofFloat(from, text.length.toFloat()).apply {
            duration = ((text.length - from) * 10).toLong().coerceIn(70, if (catchingUp) 160 else 280)
            interpolator = LinearInterpolator()
            addUpdateListener { visible = it.animatedValue as Float; view.invalidate() }
            start()
        }
    }

    fun finish(view: TextView) {
        animator?.cancel()
        animator = null
        visible = view.text.length.toFloat()
        view.invalidate()
    }

    fun clip(canvas: Canvas, view: TextView) {
        val layout = view.layout ?: return
        if (!enabled || visible >= view.text.length) return
        streamingInkPath(layout, view.text, visible, path, segment)
        path.offset((view.totalPaddingLeft - view.scrollX).toFloat(), (view.totalPaddingTop - view.scrollY).toFloat())
        canvas.clipPath(path)
    }
}
