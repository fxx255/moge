package com.moge.app.ui.capture

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class PaperFocusCandidate(val x: Float, val y: Float, val coverage: Float)

/** Small luminance grid only: a broad light region containing dark printed strokes. */
internal fun detectPaperFocus(luma: ByteArray, width: Int, height: Int): PaperFocusCandidate? {
    if (width < 8 || height < 8 || luma.size != width * height) return null
    val histogram = IntArray(256)
    luma.forEach { histogram[it.toInt() and 255]++ }
    var count = 0
    val paperLevel = histogram.indices.first { count += histogram[it]; count >= luma.size * .75f }
    if (paperLevel < 75) return null
    val threshold = max(65, paperLevel - 35)
    val visited = BooleanArray(luma.size)
    val queue = IntArray(luma.size)
    var largest = 0
    var left = 0; var right = 0; var top = 0; var bottom = 0
    for (start in luma.indices) {
        if (visited[start] || (luma[start].toInt() and 255) < threshold) continue
        var head = 0; var tail = 1
        queue[0] = start
        visited[start] = true
        var x0 = width; var x1 = 0; var y0 = height; var y1 = 0
        while (head < tail) {
            val at = queue[head++]
            val x = at % width; val y = at / width
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
            fun add(next: Int) {
                if (!visited[next] && (luma[next].toInt() and 255) >= threshold) {
                    visited[next] = true
                    queue[tail++] = next
                }
            }
            if (x > 0) add(at - 1)
            if (x + 1 < width) add(at + 1)
            if (y > 0) add(at - width)
            if (y + 1 < height) add(at + width)
        }
        if (tail > largest) {
            largest = tail; left = x0; right = x1; top = y0; bottom = y1
        }
    }
    val area = (right - left + 1) * (bottom - top + 1)
    if (largest < luma.size * .18f || largest < area * .6f ||
        right - left < width * .3f || bottom - top < height * .3f) return null
    // Exclude the outer boundary: a plain wall or blank bright rectangle is not printed paper.
    var strokes = 0; var strokeX = 0f; var strokeY = 0f
    for (y in top + 2 until bottom - 1) for (x in left + 2 until right - 1) {
        val at = y * width + x
        val value = luma[at].toInt() and 255
        val next = luma[at + 1].toInt() and 255
        if (abs(value - next) >= 30 && min(value, next) < threshold - 10 && max(value, next) >= threshold) {
            strokes++; strokeX += x; strokeY += y
        }
    }
    if (strokes < area * .008f || strokes > area * .25f) return null
    return PaperFocusCandidate((strokeX / strokes + .5f) / width,
        (strokeY / strokes + .5f) / height, area.toFloat() / luma.size)
}

/** Stable page acquisition, with no periodic refocusing of an unchanged page. Pure and clock-injected. */
internal class PaperFocusTracker {
    private var candidate: PaperFocusCandidate? = null
    private var focused: PaperFocusCandidate? = null
    private var stableSince = 0L
    private var lastSeen = 0L
    private var lastFocus = Long.MIN_VALUE
    private var blockedUntil = 0L
    private var samples = 0

    fun update(page: PaperFocusCandidate?, now: Long): PaperFocusCandidate? {
        if (page == null) {
            candidate = null; samples = 0
            if (now - lastSeen >= 1_500) focused = null
            return null
        }
        lastSeen = now
        if (now < blockedUntil) return null
        val previous = candidate
        if (previous == null || !near(previous, page, .045f, .12f)) {
            candidate = page; stableSince = now; samples = 1
            return null
        }
        samples++
        if (samples < 3 || now - stableSince < 800) return null
        if (focused?.let { near(it, page, .12f, .25f) } == true) return null
        if (lastFocus != Long.MIN_VALUE && now - lastFocus < 3_000) return null
        focused = page; lastFocus = now
        return page
    }

    /** Manual taps, dragging, zoom and capture own the camera while they are active. */
    fun suppress(now: Long) {
        if (candidate != null) focused = candidate
        candidate = null; samples = 0
        blockedUntil = now + 6_000
    }

    fun reset() {
        candidate = null; focused = null; samples = 0
        lastFocus = Long.MIN_VALUE; blockedUntil = 0; lastSeen = 0
    }

    private fun near(a: PaperFocusCandidate, b: PaperFocusCandidate, distance: Float, areaRatio: Float) =
        abs(a.x - b.x) <= distance && abs(a.y - b.y) <= distance &&
            abs(a.coverage - b.coverage) <= max(a.coverage, b.coverage) * areaRatio
}
