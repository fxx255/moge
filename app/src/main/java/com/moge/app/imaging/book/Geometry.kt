package com.moge.app.imaging.book

import kotlin.math.abs
import kotlin.math.hypot

internal data class Corner(val x: Float, val y: Float)

internal object Geometry {
    val fullFrame = listOf(Corner(0f, 0f), Corner(1f, 0f), Corner(1f, 1f), Corner(0f, 1f))

    /** Points are clockwise: top-left, top-right, bottom-right, bottom-left. */
    fun isValid(points: List<Corner>): Boolean {
        if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return false
        val crosses = points.indices.map { i ->
            val a = points[i]
            val b = points[(i + 1) % 4]
            val c = points[(i + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        return crosses.all { it > 0.0001f } && area(points) >= 0.015f
    }

    fun area(points: List<Corner>): Float = abs(points.indices.sumOf { i ->
        val a = points[i]
        val b = points[(i + 1) % points.size]
        (a.x * b.y - b.x * a.y).toDouble()
    }.toFloat()) / 2f

    fun distance(a: Corner, b: Corner, width: Int, height: Int): Double =
        hypot((a.x - b.x).toDouble() * width, (a.y - b.y).toDouble() * height)
}
