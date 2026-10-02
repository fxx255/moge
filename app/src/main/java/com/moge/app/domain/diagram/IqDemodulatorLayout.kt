package com.moge.app.domain.diagram

/**
 * Layout for coherent receivers. The graph supplies the actual blocks; this class only
 * interprets I/Q lane roles, derives processing stages from signal edges, and routes
 * the shared sources and return paths. QPSK and 16QAM use the same geometry.
 */
internal object IqDemodulatorLayout {
    private const val PADDING = 28f
    private const val STAGE_GAP = 66f
    private const val LANE_GAP = 70f
    private const val MIN_STAGE_WIDTH = 94f
    private const val MIN_LANE_HEIGHT = 64f

    private enum class Lane(val row: Int) { I(0), COMMON(1), Q(2), AUX(3) }
    private data class Placement(val column: Int, val lane: Lane)

    fun supports(spec: DiagramSpec): Boolean =
        spec.nodes.any { lane(it) == Lane.I } && spec.nodes.any { lane(it) == Lane.Q }

    fun layout(spec: DiagramSpec): DiagramLayoutResult {
        val lanes = spec.nodes.associate { it.id to lane(it) }
        val controls = spec.nodes.filter(::isControl).mapTo(mutableSetOf()) { it.id }
        // Recovery fits in the opening between the split and the mixers. It
        // cannot occupy the split's grid cell: that puts its wire on the split trunk.
        val recovery = spec.nodes.firstOrNull(::isCarrierRecovery)
            ?.takeIf {
                val splitId = spec.nodes.firstOrNull { node -> key(node) == "split" }?.id
                val mixerId = spec.nodes.firstOrNull { node ->
                    node.renderShape() == DiagramNodeShape.MIXER && lane(node) == Lane.I
                }?.id
                spec.edges.any { edge -> edge.from == splitId && edge.to == mixerId }
            }
        val signalIds = spec.nodes.map { it.id }.filterTo(mutableSetOf()) { it !in controls }
        val stages = signalStages(spec, signalIds)

        // A control block lives beside the stage it drives. Upstream recovery
        // blocks precede that stage; phase and timing blocks can use the lower
        // auxiliary lane without displacing either signal chain.
        repeat(spec.nodes.size) {
            spec.nodes.filter { it.id in controls }.forEach { node ->
                val targetStage = spec.edges.filter { it.from == node.id }
                    .mapNotNull { stages[it.to] }.minOrNull()
                if (targetStage != null) {
                    val offset = when {
                        key(node).contains("recovery") && !key(node).contains("timing") -> 2
                        key(node).contains("phase") -> 1
                        else -> 0
                    }
                    stages[node.id] = (targetStage - offset).coerceAtLeast(0)
                }
            }
        }

        val placements = linkedMapOf<String, Placement>()
        val occupied = mutableSetOf<Placement>()
        // Reserve signal stages first. The model may list a carrier before the
        // input/split; JSON order must not let that control box move the chain.
        spec.nodes.filter { it.id != recovery?.id }.sortedWith(compareBy<DiagramNode>(
            { it.id in controls }, { stages[it.id] ?: 0 },
            { lanes.getValue(it.id).row }, { it.id },
        )).forEach { node ->
            val lane = lanes.getValue(node.id)
            var column = (node.column ?: stages[node.id] ?: 0).coerceAtLeast(0)
            var placement = Placement(column, lane)
            // A collision creates another stage on the same lane. Moving a
            // block to a different lane would change the diagram's meaning.
            while (placement in occupied) {
                column++
                placement = Placement(column, lane)
            }
            placements[node.id] = placement
            occupied += placement
        }

        val title = DiagramText.layout(spec.title, DiagramTextRole.TITLE, 600f)
        val sizes = spec.nodes.associate { it.id to DiagramLayout.sizeOf(it) }
        val maxColumn = placements.values.maxOfOrNull { it.column } ?: 0
        val maxRow = placements.values.maxOfOrNull { it.lane.row } ?: 2
        val widths = (0..maxColumn).associateWith { column ->
            maxOf(MIN_STAGE_WIDTH, spec.nodes.filter { placements[it.id]?.column == column }
                .maxOfOrNull { sizes.getValue(it.id).width } ?: 0f)
        }
        val heights = (0..maxRow).associateWith { row ->
            maxOf(MIN_LANE_HEIGHT, spec.nodes.filter { placements[it.id]?.lane?.row == row }
                .maxOfOrNull { sizes.getValue(it.id).height } ?: 0f)
        }
        val mixerColumn = spec.nodes.filter {
            it.renderShape() == DiagramNodeShape.MIXER && lanes[it.id] == Lane.I
        }.mapNotNull { placements[it.id]?.column }.minOrNull()
        val x = mutableMapOf<Int, Float>()
        var cursor = PADDING
        widths.forEach { (column, width) ->
            if (recovery != null && column == mixerColumn) {
                cursor += maxOf(130f, sizes.getValue(recovery.id).width + 10f)
            }
            x[column] = cursor
            cursor += width + STAGE_GAP
        }
        val y = mutableMapOf<Int, Float>()
        cursor = PADDING + title.height + if (spec.title.isBlank()) 0f else 16f
        heights.forEach { (row, height) ->
            y[row] = cursor
            cursor += height + LANE_GAP
        }
        val gridBoxes = spec.nodes.filter { it.id != recovery?.id }.map { node ->
            val place = placements.getValue(node.id)
            val size = sizes.getValue(node.id)
            NodeBox(
                node,
                x.getValue(place.column) + (widths.getValue(place.column) - size.width) / 2f,
                y.getValue(place.lane.row) + (heights.getValue(place.lane.row) - size.height) / 2f,
                size.width, size.height, place.column, place.lane.row,
            )
        }
        val gridById = gridBoxes.associateBy { it.node.id }
        val recoveryBox = recovery?.let { node ->
            val split = gridBoxes.first { key(it.node) == "split" }
            val size = sizes.getValue(node.id)
            NodeBox(node, split.x + split.width + 56f,
                y.getValue(Lane.COMMON.row) + (heights.getValue(Lane.COMMON.row) - size.height) / 2f,
                size.width, size.height, split.column, Lane.COMMON.row)
        }
        val boxes = spec.nodes.map { node ->
            if (node.id == recovery?.id) recoveryBox!! else gridById.getValue(node.id)
        }
        val boxById = boxes.associateBy { it.node.id }
        val mergeIds = spec.nodes.filter { target ->
            val sources = spec.edges.filter { it.to == target.id }.mapNotNull { lanes[it.from] }.toSet()
            Lane.I in sources && Lane.Q in sources
        }.mapTo(mutableSetOf()) { it.id }
        val routes = spec.edges.map { edge ->
            val from = boxById.getValue(edge.from)
            val to = boxById.getValue(edge.to)
            val fromLane = lanes.getValue(edge.from)
            val toLane = lanes.getValue(edge.to)
            val split = key(from.node) == "split" && toLane in setOf(Lane.I, Lane.Q)
            val merge = edge.to in mergeIds && fromLane in setOf(Lane.I, Lane.Q)
            val controlFeed = edge.from in controls && toLane in setOf(Lane.I, Lane.Q)
            val returnPath = edge.dashed && edge.to == recovery?.id &&
                fromLane == Lane.I && from.x > to.x

            val departure = when {
                returnPath -> DiagramPort.BOTTOM
                split || merge -> DiagramPort.RIGHT
                controlFeed -> if (from.centerY < to.centerY) DiagramPort.BOTTOM else DiagramPort.TOP
                else -> DiagramLayout.resolvePort(edge.fromPort, from, to)
            }
            val arrival = when {
                returnPath -> DiagramPort.TOP
                split -> DiagramPort.LEFT
                merge -> if (fromLane == Lane.I) DiagramPort.TOP else DiagramPort.BOTTOM
                controlFeed -> if (from.centerY < to.centerY) DiagramPort.TOP else DiagramPort.BOTTOM
                else -> DiagramLayout.resolvePort(edge.toPort, to, from)
            }
            val start = DiagramLayout.anchor(from, departure)
            val end = DiagramLayout.anchor(to, arrival)
            val proposed = when {
                returnPath -> {
                    val channelY = (start.y + end.y) / 2f
                    listOf(start, DiagramPoint(start.x, channelY),
                        DiagramPoint(end.x, channelY), end)
                }
                split && start.x < end.x -> {
                    val trunkX = start.x + 32f
                    listOf(start, DiagramPoint(trunkX, start.y), DiagramPoint(trunkX, end.y), end)
                }
                merge && start.x < to.centerX ->
                    listOf(start, DiagramPoint(to.centerX, start.y), end)
                key(from.node).contains("phase_shift") && toLane == Lane.Q -> {
                    val channelY = (start.y + end.y) / 2f
                    listOf(start, DiagramPoint(start.x, channelY),
                        DiagramPoint(end.x, channelY), end)
                }
                else -> null
            }
            val points = if (proposed != null && clearOfOtherNodes(proposed, boxes, from, to)) {
                proposed
            } else {
                DiagramRouting.route(start, end, departure, arrival, boxes,
                    from.node.renderShape() == DiagramNodeShape.JUNCTION,
                    to.node.renderShape() == DiagramNodeShape.JUNCTION)
            }
            EdgeRoute(edge, start, end, departure, arrival, points)
        }
        val labelSizes = spec.edges.map {
            DiagramText.layout(it.label.orEmpty(), DiagramTextRole.EDGE_LABEL, 140f)
        }
        val maxX = maxOf(boxes.maxOf { it.x + it.width }, routes.flatMap { it.points }.maxOf { it.x })
        val maxY = maxOf(boxes.maxOf { it.y + it.height }, routes.flatMap { it.points }.maxOf { it.y })
        return DiagramLayoutResult(
            maxOf(maxX + PADDING + (labelSizes.maxOfOrNull { it.width } ?: 0f) / 2f,
                title.width + PADDING * 2, DiagramMetrics.MIN_WIDTH),
            maxOf(maxY + PADDING + (labelSizes.maxOfOrNull { it.height } ?: 0f),
                DiagramMetrics.MIN_HEIGHT),
            spec.title, boxes, routes,
            boxes.groupBy { it.row }.mapValues { (_, row) -> row.map { it.node.id } },
        )
    }

    private fun signalStages(spec: DiagramSpec, signalIds: Set<String>): MutableMap<String, Int> {
        val stage = signalIds.associateWith { 0 }.toMutableMap()
        val forward = spec.edges.filter { !it.dashed && it.from in signalIds && it.to in signalIds }
        val incoming = signalIds.associateWith { id -> forward.count { it.to == id } }.toMutableMap()
        val outgoing = forward.groupBy { it.from }
        val queue = ArrayDeque(spec.nodes.map { it.id }.filter { it in signalIds && incoming[it] == 0 })
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            outgoing[id].orEmpty().forEach { edge ->
                stage[edge.to] = maxOf(stage.getValue(edge.to), stage.getValue(id) + 1)
                incoming[edge.to] = incoming.getValue(edge.to) - 1
                if (incoming[edge.to] == 0) queue.addLast(edge.to)
            }
        }
        // A malformed cycle cannot be layered. Keep those nodes visible in
        // their supplied columns rather than allowing an unbounded relaxation.
        return stage
    }

    private fun lane(node: DiagramNode): Lane {
        val role = key(node)
        return when {
            role.startsWith("i_") || role.startsWith("upper_") ||
                role.startsWith("in_phase_") || role.endsWith("_i") -> Lane.I
            role.startsWith("q_") || role.startsWith("lower_") ||
                role.startsWith("quadrature_") || role.endsWith("_q") -> Lane.Q
            role.contains("phase_shift") || role.contains("carrier_recovery") ||
                role.contains("timing") || role.contains("clock") -> Lane.COMMON
            node.row == 0 -> Lane.I
            node.row == 2 -> Lane.Q
            node.row == 3 -> Lane.AUX
            else -> Lane.COMMON
        }
    }

    private fun isControl(node: DiagramNode): Boolean {
        val role = key(node)
        return listOf("carrier", "oscillator", "phase_shift", "timing", "clock", "recovery", "nco")
            .any(role::contains)
    }

    private fun isCarrierRecovery(node: DiagramNode): Boolean =
        key(node).contains("recovery") && !key(node).contains("timing")

    private fun key(node: DiagramNode): String = node.role.orEmpty().ifBlank { node.id }
        .trim().lowercase().replace('-', '_').replace(' ', '_')

    private fun clearOfOtherNodes(points: List<DiagramPoint>, boxes: List<NodeBox>,
                                  from: NodeBox, to: NodeBox): Boolean =
        points.zipWithNext().all { (a, b) ->
            boxes.filter { it !== from && it !== to }.none { box ->
                if (a.x == b.x) a.x > box.x && a.x < box.x + box.width &&
                    maxOf(a.y, b.y) > box.y && minOf(a.y, b.y) < box.y + box.height
                else if (a.y == b.y) a.y > box.y && a.y < box.y + box.height &&
                    maxOf(a.x, b.x) > box.x && minOf(a.x, b.x) < box.x + box.width
                else true
            }
        }
}
