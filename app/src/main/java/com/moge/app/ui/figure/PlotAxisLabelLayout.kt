package com.moge.app.ui.figure

import com.moge.app.domain.plot.LabelRect
import kotlin.math.abs

/** Keep transparent axis labels beside the lines using their measured text bounds. */
internal fun avoidPlotAxisLines(
    label: LabelRect,
    bounds: LabelRect,
    gap: Float,
    verticalAxis: Float? = null,
    horizontalAxis: Float? = null,
): LabelRect {
    val width = label.width.coerceAtMost(bounds.width)
    val height = label.height.coerceAtMost(bounds.height)
    var left = label.left.coerceIn(bounds.left, bounds.right - width)
    var top = label.top.coerceIn(bounds.top, bounds.bottom - height)
    if (verticalAxis != null && verticalAxis in (left - gap)..(left + width + gap)) {
        left = listOf(verticalAxis - gap - width, verticalAxis + gap)
            .filter { it >= bounds.left && it + width <= bounds.right }
            .minByOrNull { abs(it - left) } ?: left
    }
    if (horizontalAxis != null && horizontalAxis in (top - gap)..(top + height + gap)) {
        top = listOf(horizontalAxis - gap - height, horizontalAxis + gap)
            .filter { it >= bounds.top && it + height <= bounds.bottom }
            .minByOrNull { abs(it - top) } ?: top
    }
    return LabelRect(left, top, left + width, top + height)
}
