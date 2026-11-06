package com.moge.app.domain.coding

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.cos
import kotlin.math.sin

/** Geometry for a time-expanded trellis; labels occupy clear space beside their own branch. */
internal object TrellisLayout {
    data class Point(val x: Float, val y: Float)
    data class Size(val width: Float, val height: Float)
    data class Label(val center: Point, val width: Float, val height: Float) {
        fun overlaps(other: Label): Boolean = abs(center.x - other.center.x) < (width + other.width) / 2 + 4 &&
            abs(center.y - other.center.y) < (height + other.height) / 2 + 4
    }
    data class Edge(val branch: TrellisBranch, val from: Point, val to: Point, val label: Label?)
    data class Plan(val width: Float, val height: Float, val bottom: Float, val x0: Float,
        val pitch: Float, val nodes: Map<Pair<Int, Int>, Point>, val edges: List<Edge>, val stateYs: List<Float>,
        val nodeLabels: Map<Pair<Int, Int>, Label>)

    fun layout(code: CodingSpec, spec: TrellisSpec, result: TrellisResult,
        measureLabel: (String, Boolean) -> Size = { text, circled ->
            if (circled) maxOf(30f, text.length * 12f + 12f).let { Size(it, it) }
            else Size(text.length * 13f + 2f, 22f)
        }): Plan {
        val count = 1 shl code.memory
        val pitch = if (count > 4) 270f else 190f
        val x0 = 150f
        val ys = List(count) { 155f + it * 130f }
        val bottom = ys.last() + 90f
        val width = x0 + spec.steps * pitch + 80f
        val nodes = result.layers.flatMapIndexed { time, states ->
            states.map { state -> (time to state) to Point(x0 + time * pitch, ys[ConvolutionalTrellis.row(state, code.memory)]) }
        }.toMap()
        val visible = result.branches.filter { spec.mode != "survivors" || it in result.survivors }
        val segments = visible.map { branch ->
            Triple(branch, nodes.getValue(branch.time to branch.transition.from), nodes.getValue(branch.time + 1 to branch.transition.to))
        }
        val occupied = mutableListOf<Label>()
        fun place(choices: List<Pair<Label, Float>>): Label {
            var best: Label? = null
            var bestScore = Float.POSITIVE_INFINITY
            for ((label, distance) in choices.sortedBy { it.second }) {
                val nodeCollisions = nodes.values.count { point ->
                    abs(point.x - label.center.x) < label.width / 2 + 10 &&
                        abs(point.y - label.center.y) < label.height / 2 + 10
                }
                val collisions = nodeCollisions + occupied.count(label::overlaps) +
                    segments.count { (_, a, b) -> intersects(a, b, label) } +
                    if (label.center.y - label.height / 2 < 103 || label.center.y + label.height / 2 > bottom - 30) 1 else 0
                if (collisions == 0) return label.also { occupied += it }
                val score = collisions * 100_000f + distance
                if (score < bestScore) { best = label; bestScore = score }
            }
            return requireNotNull(best).also { occupied += it }
        }
        val edges = segments.map { (branch, from, to) ->
            val label = if (spec.mode == "survivors") null else {
                val text = if (spec.mode == "distances") branch.distance.toString() else branch.transition.output.joinToString("")
                val size = measureLabel(text, spec.mode == "distances")
                val dx = to.x - from.x; val dy = to.y - from.y
                val length = hypot(dx, dy)
                val nx = -dy / length; val ny = dx / length
                // Rectangle support along the branch normal keeps the whole caption clear.
                val halfExtent = abs(nx) * size.width / 2 + abs(ny) * size.height / 2
                val choices = listOf(4f, 6f, 9f, 13f, 18f).flatMap { gap ->
                    listOf(.25f, .75f, .38f, .62f, .5f, .16f, .84f, .3f, .7f, .42f, .58f, .2f, .8f, .1f, .9f).flatMap { fraction ->
                        listOf(-1f, 1f).map { side ->
                            Label(Point(from.x + fraction * dx + side * nx * (halfExtent + gap),
                                from.y + fraction * dy + side * ny * (halfExtent + gap)), size.width, size.height) to
                                (gap * 10 + abs(.5f - fraction) * 2)
                        }
                    }
                }
                place(choices)
            }
            Edge(branch, from, to, label)
        }
        val shownNodes = edges.flatMap { listOf(it.branch.time to it.branch.transition.from,
            it.branch.time + 1 to it.branch.transition.to) }.toSet() + (0 to spec.initialState)
        val nodeLabels = if (spec.mode != "survivors") emptyMap() else nodes.filterKeys { it in shownNodes }.mapValues { (key, node) ->
            val size = measureLabel(result.metrics[key.first].getValue(key.second).toString(), true)
            place(listOf(4f, 6f, 10f, 14f, 20f).flatMap { gap ->
                listOf(-90, -120, -60, 90, 180, 0, -150, -30, 120, 60).mapIndexed { preference, angle ->
                    val radians = Math.toRadians(angle.toDouble())
                    val nx = cos(radians).toFloat(); val ny = sin(radians).toFloat()
                    val distance = abs(nx) * size.width / 2 + abs(ny) * size.height / 2 + 6.5f + gap
                    Label(Point(node.x + nx * distance, node.y + ny * distance), size.width, size.height) to
                        (gap * 10 + preference * .1f)
                }
            })
        }
        return Plan(width, bottom + if (spec.receivedBits.isEmpty()) 104f else 136f, bottom, x0, pitch, nodes, edges, ys, nodeLabels)
    }

    /** Slab intersection, used to keep a branch caption clear of unrelated lines. */
    fun intersects(a: Point, b: Point, label: Label): Boolean {
        var low = 0f; var high = 1f
        val dx = b.x - a.x; val dy = b.y - a.y
        val halfWidth = label.width / 2 + 2.5f; val halfHeight = label.height / 2 + 2.5f
        if (abs(dx) < .001f) {
            if (abs(a.x - label.center.x) > halfWidth) return false
        } else {
            val t1 = (label.center.x - halfWidth - a.x) / dx
            val t2 = (label.center.x + halfWidth - a.x) / dx
            low = maxOf(low, minOf(t1, t2)); high = minOf(high, maxOf(t1, t2))
            if (low > high) return false
        }
        if (abs(dy) < .001f) return abs(a.y - label.center.y) <= halfHeight
        val t1 = (label.center.y - halfHeight - a.y) / dy
        val t2 = (label.center.y + halfHeight - a.y) / dy
        return maxOf(low, minOf(t1, t2)) <= minOf(high, maxOf(t1, t2))
    }
}
