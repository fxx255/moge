package com.moge.app.domain.diagram

import kotlin.math.abs

/**
 * Signal stages come from the DAG, lanes from branch semantics. Shared converters
 * span their connected lanes; sources/control feeds do not lengthen the signal chain.
 * This is also used when a new shape is supplied without an explicit profile.
 */
internal object CommunicationDiagramLayout {
    private const val PADDING = 28f
    private const val CONTROL_GAP = 40f
    private const val STAGE_GAP = 64f
    private val branchRole = Regex("(?:branch|lane)_(\\d+)(?:_|$)")

    fun supports(spec: DiagramSpec): Boolean = spec.profile == DiagramLayoutProfile.COMMUNICATION ||
        spec.nodes.any { it.shape in setOf(DiagramNodeShape.BUS, DiagramNodeShape.SAMPLER) ||
            branchRole.containsMatchIn(key(it)) }

    fun layoutOrNull(spec: DiagramSpec): DiagramLayoutResult? {
        val controls = spec.nodes.filter { isControl(it, spec) }.mapTo(mutableSetOf()) { it.id }
        val signal = spec.nodes.filter { it.id !in controls }
        if (signal.isEmpty()) return null
        val forward = spec.edges.filter { !it.dashed && it.from !in controls && it.to !in controls }
        val stage = stages(signal, forward) ?: return null
        val incoming = forward.groupBy { it.to }
        val outgoing = forward.groupBy { it.from }
        val hints = signal.mapNotNull { node -> laneHint(node)?.let { node.id to it } }.toMap().toMutableMap()

        // Without branch roles, the first fan-out supplies a stable lane order.
        // Never depend on the ordering of the model's JSON arrays.
        if (hints.isEmpty()) {
            signal.sortedWith(compareBy<DiagramNode> { stage.getValue(it.id) }.thenBy { it.id })
                .firstOrNull { outgoing[it.id].orEmpty().map { e -> e.to }.distinct().size > 1 }
                ?.let { fork -> outgoing[fork.id].orEmpty().map { it.to }.distinct().sorted()
                    .take(DiagramLimits.MAX_CHANNELS).forEachIndexed { lane, id -> hints[id] = lane } }
        }
        val nearestMemo = mutableMapOf<Pair<String, Boolean>, Set<Int>>()
        fun nearest(id: String, upstream: Boolean): Set<Int> {
            hints[id]?.let { return setOf(it) }
            return nearestMemo.getOrPut(id to upstream) {
                val edges = if (upstream) incoming[id].orEmpty() else outgoing[id].orEmpty()
                edges.flatMap { edge -> edge.channel?.let { listOf(it) } ?:
                    nearest(if (upstream) edge.from else edge.to, upstream).toList() }.toSet()
            }
        }
        val lanes = signal.associate { node ->
            val connected = hints[node.id]?.let { setOf(it) } ?: when (key(node)) {
                "real_imag", "serial_to_parallel", "splitter" -> nearest(node.id, false)
                "parallel_to_serial", "combiner" -> nearest(node.id, true)
                else -> nearest(node.id, true) + nearest(node.id, false)
            }
            node.id to connected.ifEmpty { setOf(0) }
        }
        val laneKeys = lanes.values.flatten().distinct().sorted()
        val baseSizes = spec.nodes.associate { it.id to DiagramLayout.sizeOf(it) }
        val bundledBlocks = forward.filter { it.channel != null }.flatMap { listOf(it.from to it.channel, it.to to it.channel) }
            .groupBy({ it.first }, { it.second }).filterValues { it.distinct().size > 1 }.keys
        fun spans(node: DiagramNode) = node.renderShape() == DiagramNodeShape.BUS ||
            key(node) in setOf("transform", "ifft", "fft", "real_imag", "parallel_transform") ||
            (node.renderShape() == DiagramNodeShape.BLOCK && node.id in bundledBlocks)
        val signalHeights = laneKeys.associateWith { lane ->
            maxOf(64f, signal.filter { !spans(it) && lanes.getValue(it.id) == setOf(lane) }
                .maxOfOrNull { baseSizes.getValue(it.id).height } ?: 0f)
        }
        data class Control(val node: DiagramNode, val target: String, val lane: Int, val above: Boolean)
        val feeds = spec.nodes.filter { it.id in controls }.sortedBy { it.id }.map { node ->
            val outgoingFeeds = spec.edges.filter { it.from == node.id }.sortedWith(
                compareBy<DiagramEdge> { stage[it.to] ?: Int.MAX_VALUE }.thenBy { it.to }.thenBy { it.toPort.name })
            val edge = outgoingFeeds.firstOrNull { it.to !in controls } ?: outgoingFeeds.firstOrNull()
            val target = edge?.to?.takeIf { it in stage } ?:
                signal.minWith(compareBy<DiagramNode> { stage.getValue(it.id) }.thenBy { it.id }).id
            Control(node, target, laneHint(node) ?: lanes.getValue(target).min(),
                edge?.toPort == DiagramPort.TOP)
        }
        fun extent(lane: Int, above: Boolean): Float = signalHeights.getValue(lane) / 2f +
            feeds.filter { it.lane == lane && it.above == above }
                .groupBy { stage.getValue(it.target) }.values
                .maxOfOrNull { group -> group.sumOf { (baseSizes.getValue(it.node.id).height + CONTROL_GAP).toDouble() }.toFloat() }
                .orZero()
        val title = DiagramText.layout(spec.title, DiagramTextRole.TITLE, 600f)
        val titleBottom = PADDING + title.height + if (spec.title.isBlank()) 0f else 24f
        val centers = mutableMapOf<Int, Float>()
        var lastLane: Int? = null
        laneKeys.forEach { lane ->
            val previous = lastLane
            centers[lane] = if (previous == null) titleBottom + extent(lane, true) + 38f
                else centers.getValue(previous) + extent(previous, false) + extent(lane, true) + 54f
            lastLane = lane
        }
        val maxStage = stage.values.max()
        // A bank may change arity (three bits -> I/Q, or N carriers -> I/Q).
        // Spread each stage's active lanes over the same vertical band so both
        // sides stay centered instead of leaving the lower half unused.
        val stageCenters = (0..maxStage).associateWith { col ->
            val active = signal.filter { stage[it.id] == col }.flatMap { lanes.getValue(it.id) }.distinct().sorted()
            active.mapIndexed { index, lane -> lane to if (active.size < 2) centers.getValue(lane)
                else centers.getValue(laneKeys.first()) + index *
                    (centers.getValue(laneKeys.last()) - centers.getValue(laneKeys.first())) / (active.size - 1) }.toMap()
        }
        fun center(id: String, lane: Int) = stageCenters.getValue(stage.getValue(id)).getValue(lane)
        val columnWidths = (0..maxStage).associateWith { col ->
            maxOf(48f, signal.filter { stage[it.id] == col }.maxOfOrNull { baseSizes.getValue(it.id).width } ?: 0f,
                feeds.filter { stage[it.target] == col }.maxOfOrNull { baseSizes.getValue(it.node.id).width } ?: 0f)
        }
        val columns = mutableMapOf<Int, Float>()
        var cursor = PADDING
        (0..maxStage).forEach { col ->
            columns[col] = cursor
            val labelWidth = forward.filter { stage[it.from] == col && stage[it.to] == col + 1 }
                .maxOfOrNull { DiagramText.layout(it.label.orEmpty(), DiagramTextRole.EDGE_LABEL, 180f).width } ?: 0f
            cursor += columnWidths.getValue(col) + maxOf(STAGE_GAP, labelWidth + 26f)
        }
        val signalBoxes = signal.map { node ->
            val connected = lanes.getValue(node.id)
            val firstY = center(node.id, connected.min())
            val lastY = center(node.id, connected.max())
            val size = baseSizes.getValue(node.id)
            val height = if (spans(node)) maxOf(size.height, lastY - firstY + 88f) else size.height
            val col = stage.getValue(node.id)
            NodeBox(node, columns.getValue(col) + (columnWidths.getValue(col) - size.width) / 2f,
                (firstY + lastY - height) / 2f, size.width, height, col, connected.min())
        }
        val signalById = signalBoxes.associateBy { it.node.id }
        val placedControls = mutableListOf<NodeBox>()
        feeds.forEach { feed ->
            val target = signalById.getValue(feed.target)
            val size = baseSizes.getValue(feed.node.id)
            var top = if (feed.above) target.y - CONTROL_GAP - size.height
                else target.y + target.height + CONTROL_GAP
            val x = target.centerX - size.width / 2f
            while (placedControls.any { it.x < x + size.width + 8f && it.x + it.width + 8f > x &&
                    it.y < top + size.height + 8f && it.y + it.height + 8f > top }) {
                top += if (feed.above) -(size.height + CONTROL_GAP) else size.height + CONTROL_GAP
            }
            placedControls += NodeBox(feed.node, x, top, size.width, size.height, target.column, feed.lane)
        }
        val boxes = signalBoxes + placedControls
        val boxById = boxes.associateBy { it.node.id }
        val feedbackChannels = spec.edges.filter { it.dashed && it.from !in controls && it.to !in controls &&
                stage.getValue(it.from) >= stage.getValue(it.to) }
            .sortedWith(compareBy<DiagramEdge> { it.from }.thenBy { it.to }.thenBy { it.channel })
            .withIndex().associate { it.value to it.index }
        val routes = spec.edges.map { edge ->
            val from = boxById.getValue(edge.from)
            val to = boxById.getValue(edge.to)
            val fromLanes = lanes[edge.from].orEmpty()
            val toLanes = lanes[edge.to].orEmpty()
            val control = edge.from in controls || edge.to in controls
            val returnPath = !control && edge.dashed && from.column >= to.column
            val merge = !control && to.node.renderShape() == DiagramNodeShape.SUM &&
                toLanes.size > 1 && fromLanes.size == 1
            val departure = when {
                returnPath -> DiagramPort.BOTTOM
                edge.fromPort != DiagramPort.AUTO -> edge.fromPort
                control -> if (from.centerY > to.centerY) DiagramPort.TOP else DiagramPort.BOTTOM
                else -> DiagramPort.RIGHT
            }
            val arrival = when {
                returnPath -> DiagramPort.BOTTOM
                edge.toPort != DiagramPort.AUTO -> edge.toPort
                control -> if (from.centerY > to.centerY) DiagramPort.BOTTOM else DiagramPort.TOP
                merge && from.centerY < to.centerY - 1f -> DiagramPort.TOP
                merge && from.centerY > to.centerY + 1f -> DiagramPort.BOTTOM
                else -> DiagramPort.LEFT
            }
            fun port(box: NodeBox, side: DiagramPort, otherLanes: Set<Int>): DiagramPoint {
                val anchor = DiagramLayout.anchor(box, side)
                if (!spans(box.node) || side !in setOf(DiagramPort.LEFT, DiagramPort.RIGHT)) return anchor
                edge.channel?.let { return anchor.copy(y = center(box.node.id, it)) }
                if (otherLanes.size == 1) {
                    val other = if (box === from) to else from
                    return anchor.copy(y = other.centerY)
                }
                return anchor
            }
            val start = port(from, departure, toLanes)
            val end = port(to, arrival, fromLanes)
            val proposed = when {
                returnPath -> {
                    val y = boxes.maxOf { it.y + it.height } + 32f + feedbackChannels.getValue(edge) * 18f
                    listOf(start, DiagramPoint(start.x, y), DiagramPoint(end.x, y), end)
                }
                !control && departure == DiagramPort.BOTTOM && arrival == DiagramPort.BOTTOM -> {
                    val y = boxes.filter { it.x < to.x + to.width && it.x + it.width > from.x }
                        .maxOf { it.y + it.height } + 28f
                    listOf(start, DiagramPoint(start.x, y), DiagramPoint(end.x, y), end)
                }
                !control && departure == DiagramPort.RIGHT && arrival in setOf(DiagramPort.TOP, DiagramPort.BOTTOM) ->
                    listOf(start, DiagramPoint(end.x, start.y), end)
                departure == DiagramPort.RIGHT && arrival == DiagramPort.LEFT && start.x < end.x -> {
                    // Fan-out turns just after the shared block; fan-in turns just before it.
                    val trunk = if (fromLanes.size > toLanes.size) start.x + 28f else end.x - 28f
                    listOf(start, DiagramPoint(trunk, start.y), DiagramPoint(trunk, end.y), end)
                }
                start.x == end.x || start.y == end.y -> listOf(start, end)
                else -> null
            }
            val points = if (proposed != null && clear(proposed, boxes, from, to)) compact(proposed)
                else DiagramRouting.route(start, end, departure, arrival, boxes,
                    from.node.renderShape() == DiagramNodeShape.JUNCTION,
                    to.node.renderShape() == DiagramNodeShape.JUNCTION)
            EdgeRoute(edge, start, end, departure, arrival, points)
        }
        // Titles and probe labels need a consistent safe band even for top-fed chains.
        val minY = minOf(boxes.minOf { it.y }, routes.flatMap { it.points }.minOfOrNull { it.y } ?: 0f)
        val shift = maxOf(0f, titleBottom + 16f - minY)
        val shiftedBoxes = boxes.map { it.copy(y = it.y + shift) }
        val shiftedRoutes = routes.map { route ->
            route.copy(start = route.start.copy(y = route.start.y + shift),
                end = route.end.copy(y = route.end.y + shift), points = route.points.map { it.copy(y = it.y + shift) })
        }
        val maxX = maxOf(shiftedBoxes.maxOf { it.x + it.width }, shiftedRoutes.flatMap { it.points }.maxOfOrNull { it.x } ?: 0f)
        val maxY = maxOf(shiftedBoxes.maxOf { it.y + it.height }, shiftedRoutes.flatMap { it.points }.maxOfOrNull { it.y } ?: 0f)
        val annotations = if (spec.repeatLastLane && laneKeys.size >= 3) {
            val previous = laneKeys[laneKeys.lastIndex - 1]
            val last = laneKeys.last()
            val branchMarks = signalBoxes.filter { lanes[it.node.id] == setOf(last) }.mapNotNull { next ->
                val previousBoxes = boxes.filter { it.column == next.column && it.row == previous && !spans(it.node) }
                val bottom = previousBoxes.maxOfOrNull { it.y + it.height } ?: return@mapNotNull null
                if (next.y - bottom < 44f) null
                else DiagramAnnotation("\$\\vdots\$", next.centerX, (bottom + next.y) / 2f + shift)
            }
            val bundleMarks = shiftedRoutes.filter { it.edge.channel != null }
                .groupBy { it.edge.from to it.edge.to }.values.mapNotNull { bundle ->
                    val sorted = bundle.sortedBy { it.edge.channel }
                    if (sorted.size < 3) return@mapNotNull null
                    val a = sorted[sorted.lastIndex - 1]
                    val b = sorted.last()
                    DiagramAnnotation("\$\\vdots\$", (b.start.x + b.end.x) / 2f, (a.start.y + b.start.y) / 2f)
                }
            branchMarks + bundleMarks
        } else emptyList()
        return DiagramLayoutResult(maxOf(maxX + PADDING, title.width + PADDING * 2, DiagramMetrics.MIN_WIDTH),
            maxOf(maxY + PADDING + 38f, DiagramMetrics.MIN_HEIGHT), spec.title, shiftedBoxes, shiftedRoutes,
            shiftedBoxes.groupBy { it.row }.mapValues { (_, row) -> row.map { it.node.id } }, annotations)
    }

    private fun Float?.orZero() = this ?: 0f

    private fun stages(nodes: List<DiagramNode>, edges: List<DiagramEdge>): Map<String, Int>? {
        val stage = nodes.associate { it.id to 0 }.toMutableMap()
        val degree = nodes.associate { node -> node.id to edges.count { it.to == node.id } }.toMutableMap()
        val next = edges.groupBy { it.from }
        val queue = java.util.PriorityQueue<String>().apply { addAll(degree.filterValues { it == 0 }.keys) }
        var visited = 0
        while (queue.isNotEmpty()) {
            val id = queue.remove()
            visited++
            next[id].orEmpty().forEach { edge ->
                stage[edge.to] = maxOf(stage.getValue(edge.to), stage.getValue(id) + 1)
                degree[edge.to] = degree.getValue(edge.to) - 1
                if (degree[edge.to] == 0) queue += edge.to
            }
        }
        return stage.takeIf { visited == nodes.size }
    }

    private fun laneHint(node: DiagramNode): Int? {
        val role = key(node)
        val lane = branchRole.find(role)?.groupValues?.get(1)?.toIntOrNull() ?: when {
            role.startsWith("i_") || role.startsWith("upper_") -> 0
            role.startsWith("q_") || role.startsWith("lower_") -> 1
            else -> node.row
        }
        return lane?.takeIf { it in 0 until DiagramLimits.MAX_CHANNELS }
    }

    private fun isControl(node: DiagramNode, spec: DiagramSpec): Boolean {
        val role = key(node)
        if (listOf("carrier", "oscillator", "threshold", "noise", "clock", "constant", "timing", "phase_shift")
                .any(role::contains)) return true
        return spec.edges.none { it.to == node.id } && spec.edges.any { it.from == node.id } &&
            spec.edges.filter { it.from == node.id }.all { it.toPort in setOf(DiagramPort.TOP, DiagramPort.BOTTOM) }
    }

    private fun key(node: DiagramNode) = node.role.orEmpty().ifBlank { node.id }
        .lowercase().replace('-', '_').replace(' ', '_')

    private fun clear(points: List<DiagramPoint>, boxes: List<NodeBox>, from: NodeBox, to: NodeBox) =
        points.zipWithNext().all { (a, b) -> boxes.filter { it !== from && it !== to }.none { box ->
            if (a.x == b.x) a.x > box.x - 4f && a.x < box.x + box.width + 4f &&
                maxOf(a.y, b.y) > box.y - 4f && minOf(a.y, b.y) < box.y + box.height + 4f
            else if (a.y == b.y) a.y > box.y - 4f && a.y < box.y + box.height + 4f &&
                maxOf(a.x, b.x) > box.x - 4f && minOf(a.x, b.x) < box.x + box.width + 4f
            else true
        } }

    private fun compact(points: List<DiagramPoint>): List<DiagramPoint> {
        val result = mutableListOf<DiagramPoint>()
        points.forEach { p ->
            if (result.lastOrNull() != p) {
                while (result.size >= 2) {
                    val a = result[result.lastIndex - 1]
                    val b = result.last()
                    if ((abs(a.x - b.x) < .01f && abs(b.x - p.x) < .01f && (b.y - a.y) * (p.y - b.y) >= 0) ||
                        (abs(a.y - b.y) < .01f && abs(b.y - p.y) < .01f && (b.x - a.x) * (p.x - b.x) >= 0)) result.removeAt(result.lastIndex)
                    else break
                }
                result += p
            }
        }
        return result
    }
}
