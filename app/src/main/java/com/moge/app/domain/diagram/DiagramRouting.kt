package com.moge.app.domain.diagram

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Orthogonal routing around padded node boxes, with a bend penalty. */
internal object DiagramRouting {
    private data class Obstacle(val left: Float, val top: Float, val right: Float, val bottom: Float)
    private data class Step(val state: Int, val distance: Float)

    fun route(start: DiagramPoint, end: DiagramPoint, from: DiagramPort, to: DiagramPort,
              boxes: List<NodeBox>, fromIsJunction: Boolean = false,
              toIsJunction: Boolean = false): List<DiagramPoint> {
        fun stub(p: DiagramPoint, port: DiagramPort, isJunction: Boolean): DiagramPoint {
            // A marker is itself the wire junction.  A short 8 px clearance
            // keeps arrowheads outside its dot while leaving a segment when
            // the marker sits between two compact blocks (especially E before
            // the Hilbert filter).
            val length = if (isJunction) 8f else 12f
            return when (port) {
                DiagramPort.LEFT -> p.copy(x = p.x - length)
                DiagramPort.RIGHT -> p.copy(x = p.x + length)
                DiagramPort.TOP -> p.copy(y = p.y - length)
                DiagramPort.BOTTOM -> p.copy(y = p.y + length)
                DiagramPort.AUTO -> error("Unresolved port")
            }
        }
        val a = stub(start, from, fromIsJunction)
        val b = stub(end, to, toIsJunction)
        val obstacles = boxes.map { Obstacle(it.x - 6, it.y - 6, it.x + it.width + 6, it.y + it.height + 6) }
        fun clear(p: DiagramPoint, q: DiagramPoint): Boolean = obstacles.none { box ->
            if (p.x == q.x) p.x > box.left && p.x < box.right &&
                max(p.y, q.y) > box.top && min(p.y, q.y) < box.bottom
            else p.y > box.top && p.y < box.bottom &&
                max(p.x, q.x) > box.left && min(p.x, q.x) < box.right
        }
        if ((a.x == b.x || a.y == b.y) && clear(a, b)) return compact(listOf(start, a, b, end))
        val xs = (listOf(a.x, b.x) + obstacles.flatMap { listOf(it.left - 6, it.right + 6) }).distinct().sorted()
        val ys = (listOf(a.y, b.y) + obstacles.flatMap { listOf(it.top - 6, it.bottom + 6) }).distinct().sorted()
        val columns = xs.size
        val size = columns * ys.size * 3
        val distance = FloatArray(size) { Float.POSITIVE_INFINITY }
        val previous = IntArray(size) { -1 }
        val queue = PriorityQueue<Step>(compareBy { it.distance })
        fun point(cell: Int) = DiagramPoint(xs[cell % columns], ys[cell / columns])
        val first = (ys.indexOf(a.y) * columns + xs.indexOf(a.x)) * 3
        val target = ys.indexOf(b.y) * columns + xs.indexOf(b.x)
        distance[first] = 0f
        queue += Step(first, 0f)
        var last = -1
        while (queue.isNotEmpty()) {
            val current = queue.remove()
            if (current.distance != distance[current.state]) continue
            val cell = current.state / 3
            if (cell == target) { last = current.state; break }
            val x = cell % columns
            val y = cell / columns
            val p = point(cell)
            val nextCells = buildList {
                if (x > 0) add(cell - 1)
                if (x + 1 < columns) add(cell + 1)
                if (y > 0) add(cell - columns)
                if (y + 1 < ys.size) add(cell + columns)
            }
            for (next in nextCells) {
                val q = point(next)
                if (!clear(p, q)) continue
                val direction = if (p.x == q.x) 2 else 1
                val state = next * 3 + direction
                val bend = if (current.state % 3 != 0 && current.state % 3 != direction) 20f else 0f
                val cost = current.distance + abs(p.x - q.x) + abs(p.y - q.y) + bend
                if (cost < distance[state]) {
                    distance[state] = cost
                    previous[state] = current.state
                    queue += Step(state, cost)
                }
            }
        }
        require(last >= 0) { "框图连线无法避开节点，请简化布局" }
        val path = mutableListOf<DiagramPoint>()
        while (last >= 0) {
            path += point(last / 3)
            last = previous[last]
        }
        return compact(listOf(start) + path.reversed() + end)
    }

    private fun compact(points: List<DiagramPoint>): List<DiagramPoint> {
        val result = mutableListOf<DiagramPoint>()
        for (p in points) {
            if (result.lastOrNull() == p) continue
            while (result.size >= 2) {
                val a = result[result.lastIndex - 1]
                val b = result.last()
                if ((a.x == b.x && b.x == p.x && (b.y - a.y) * (p.y - b.y) >= 0) ||
                    (a.y == b.y && b.y == p.y && (b.x - a.x) * (p.x - b.x) >= 0)) result.removeAt(result.lastIndex)
                else break
            }
            result += p
        }
        return result
    }
}
