package com.moge.app.domain.diagram

import kotlin.math.abs
import kotlin.math.max

/** A horizontal main chain with auxiliary sources and branches in separate cells. */
object DiagramLayout {
    private const val GAP_X = 64f
    private const val GAP_Y = 64f
    // Keep the fixed textbook profile dense enough for a phone-sized preview.
    // The previous values left the empty stage columns and three rails much
    // farther apart than the reference diagrams, so labels became tiny after
    // the bitmap was fitted into the message bubble.
    private const val TEMPLATE_GAP_X = 36f
    private const val TEMPLATE_GAP_Y = 52f
    private const val TEMPLATE_COLUMN_WIDTH = 102f
    private const val TEMPLATE_ROW_HEIGHT = 78f
    private const val GENERIC_PADDING = 28f
    private const val TEMPLATE_PADDING = 18f
    private data class Cell(val col: Int, val row: Int)

    fun layout(spec: DiagramSpec): DiagramLayoutResult {
        require(spec.direction == DiagramDirection.LR) { "Only LR diagrams are supported" }
        if (spec.profile == DiagramLayoutProfile.TEXTBOOK_DUAL_BRANCH) {
            return textbookDualBranch(spec)
        }
        if (spec.profile == DiagramLayoutProfile.IQ_DEMODULATOR && IqDemodulatorLayout.supports(spec)) {
            return IqDemodulatorLayout.layout(spec)
        }
        val grid = grid(spec)
        val sizes = spec.nodes.associate { it.id to sizeOf(it) }
        val widths = grid.values.map { it.col }.distinct().associateWith { col ->
            spec.nodes.filter { grid.getValue(it.id).col == col }.maxOf { sizes.getValue(it.id).width }
        }
        val heights = grid.values.map { it.row }.distinct().associateWith { row ->
            spec.nodes.filter { grid.getValue(it.id).row == row }.maxOf { sizes.getValue(it.id).height }
        }
        val title = DiagramText.layout(spec.title, DiagramTextRole.TITLE, 600f)
        val edgeLabels = spec.edges.map { DiagramText.layout(it.label.orEmpty(), DiagramTextRole.EDGE_LABEL, 140f) }
        val gapX = max(GAP_X, (edgeLabels.maxOfOrNull { it.width } ?: 0f) + 24)
        val gapY = max(GAP_Y, (edgeLabels.maxOfOrNull { it.height } ?: 0f) + 24)
        val x = starts(widths, GENERIC_PADDING, gapX)
        val y = starts(heights, GENERIC_PADDING + title.height + (if (spec.title.isBlank()) 0f else 16f), gapY)
        val boxes = spec.nodes.map { node ->
            val cell = grid.getValue(node.id)
            val size = sizes.getValue(node.id)
            NodeBox(node,
                x.getValue(cell.col) + (widths.getValue(cell.col) - size.width) / 2,
                y.getValue(cell.row) + (heights.getValue(cell.row) - size.height) / 2,
                size.width, size.height, cell.col, cell.row)
        }
        val byId = boxes.associateBy { it.node.id }
        val routes = spec.edges.map { edge ->
            val from = byId.getValue(edge.from)
            val to = byId.getValue(edge.to)
            val departure = resolvePort(edge.fromPort, from, to)
            val arrival = resolvePort(edge.toPort, to, from)
            val start = anchor(from, departure)
            val end = anchor(to, arrival)
            EdgeRoute(edge, start, end, departure, arrival,
                DiagramRouting.route(start, end, departure, arrival, boxes,
                    from.node.renderShape() == DiagramNodeShape.JUNCTION,
                    to.node.renderShape() == DiagramNodeShape.JUNCTION))
        }
        val maxX = max(boxes.maxOfOrNull { it.x + it.width } ?: 0f,
            routes.flatMap { it.points }.maxOfOrNull { it.x } ?: 0f)
        val maxY = max(boxes.maxOfOrNull { it.y + it.height } ?: 0f,
            routes.flatMap { it.points }.maxOfOrNull { it.y } ?: 0f)
        val maxLabelWidth = edgeLabels.maxOfOrNull { it.width } ?: 0f
        val maxLabelHeight = edgeLabels.maxOfOrNull { it.height } ?: 0f
        return DiagramLayoutResult(
            maxOf(maxX + GENERIC_PADDING + maxLabelWidth / 2f + 8f,
                title.width + GENERIC_PADDING * 2, DiagramMetrics.MIN_WIDTH),
            maxOf(maxY + GENERIC_PADDING + maxLabelHeight + 16f, DiagramMetrics.MIN_HEIGHT),
            spec.title, boxes, routes,
            boxes.groupBy { it.row }.mapValues { (_, row) -> row.map { it.node.id } })
    }

    /**
     * Deterministic geometry for the common SSB/IQ communication diagram.
     *
     * A language model is good at naming the blocks and edges but is not a
     * reliable graph-drawing engine.  This profile therefore assigns semantic
     * roles to fixed stage/rail cells and leaves the generic layout as a
     * backwards-compatible fallback.  Explicit row/column values are still
     * honored for nodes that do not have a recognized role.
     */
    private fun textbookDualBranch(spec: DiagramSpec): DiagramLayoutResult {
        val title = DiagramText.layout(spec.title, DiagramTextRole.TITLE, 600f)
        val sizes = spec.nodes.associate { it.id to sizeOf(it) }
        val cells = linkedMapOf<String, Cell>()
        val used = mutableSetOf<Cell>()
        val assigned = mutableSetOf<String>()

        fun place(node: DiagramNode, column: Int, row: Int) {
            if (node.id in assigned) return
            var cell = Cell(column.coerceAtLeast(0), row.coerceAtLeast(0))
            while (cell in used) cell = cell.copy(row = cell.row + 1)
            cells[node.id] = cell
            used += cell
            assigned += node.id
        }

        val byRole = spec.nodes.groupBy(::profileRole)
        fun take(role: String): DiagramNode? = byRole[role].orEmpty().firstOrNull { it.id !in assigned }
        fun fixed(role: String, column: Int, row: Int) {
            take(role)?.let { place(it, column, row) }
        }

        // Rows: 0 = upper signal path, 1 = central/control path,
        // 2 = lower signal path.  Columns are stages, including deliberate
        // empty columns so that long textbook wires do not collapse together.
        fixed("input", 0, 1)
        fixed("split", 1, 1)
        fixed("upper_mixer", 2, 0)
        fixed("lower_mixer", 2, 2)
        fixed("phase_shift", 2, 1)
        fixed("upper_filter", 4, 0)
        fixed("lower_filter", 4, 2)
        fixed("lower_hilbert", 5, 2)
        fixed("carrier", 4, 1)
        fixed("sum", 7, 1)
        fixed("output", 8, 1)
        // Test points are marks on a wire, not extra stages.  Placing A, E,
        // or G in a full grid cell collides with the split, Hilbert filter,
        // or summing circle and silently moves the mark to another rail.
        val testPoints = listOf("test_a", "test_b", "test_c", "test_d",
            "test_e", "test_f", "test_g").mapNotNull { role ->
            take(role)?.also { assigned += it.id }?.let { role to it }
        }

        // A profile may be used with an abbreviated graph.  Keep all
        // remaining nodes visible, preferring their explicit hint and then a
        // free cell to the right of the known template.
        var nextColumn = (cells.values.maxOfOrNull { it.col } ?: 0) + 1
        spec.nodes.filter { it.id !in assigned }.forEach { node ->
            val suggested = Cell(node.column ?: nextColumn, node.row ?: 1)
            var cell = suggested.copy(
                col = suggested.col.coerceAtLeast(0),
                row = suggested.row.coerceAtLeast(0),
            )
            while (cell in used) cell = cell.copy(row = cell.row + 1)
            cells[node.id] = cell
            used += cell
            assigned += node.id
            nextColumn = maxOf(nextColumn, cell.col + 1)
        }

        // (left stage, right stage, rail) describes the wire segment carrying
        // the marker; the marker itself remains a tiny junction box.
        val markerLocations = mapOf(
            "test_a" to Triple(0, 1, 1),
            "test_b" to Triple(2, 4, 0),
            "test_c" to Triple(4, 7, 0),
            "test_d" to Triple(2, 4, 2),
            "test_e" to Triple(4, 5, 2),
            "test_f" to Triple(5, 7, 2),
            "test_g" to Triple(7, 8, 1),
        )
        val maxColumn = maxOf(cells.values.maxOfOrNull { it.col } ?: 0,
            testPoints.maxOfOrNull { markerLocations.getValue(it.first).second } ?: 0)
        val maxRow = maxOf(cells.values.maxOfOrNull { it.row } ?: 0,
            testPoints.maxOfOrNull { markerLocations.getValue(it.first).third } ?: 0)
        val columns = 0..maxColumn
        val rows = 0..maxRow
        val widths = columns.associateWith { col ->
            maxOf(TEMPLATE_COLUMN_WIDTH,
                spec.nodes.filter { cells[it.id]?.col == col }
                    .maxOfOrNull { sizes.getValue(it.id).width } ?: 0f)
        }
        val heights = rows.associateWith { row ->
            maxOf(TEMPLATE_ROW_HEIGHT,
                spec.nodes.filter { cells[it.id]?.row == row }
                    .maxOfOrNull { sizes.getValue(it.id).height } ?: 0f)
        }
        // The carrier formula sits on the deliberately long control segment
        // between the phase block and the carrier extractor.  Reserving its
        // whole width between *every* pair of stages made the image sparse;
        // the label placer already checks the actual segment for collisions.
        // Keep stage spacing compact enough for a phone preview while
        // retaining a clear rectangular wire corridor.
        val gapX = max(TEMPLATE_GAP_X, 64f)
        val gapY = max(TEMPLATE_GAP_Y, 54f)
        val x = starts(widths, TEMPLATE_PADDING, gapX)
        val y = starts(heights, TEMPLATE_PADDING + title.height + (if (spec.title.isBlank()) 0f else 16f), gapY)
        fun railCenter(row: Int) = y.getValue(row) + heights.getValue(row) / 2f
        val upperRail = railCenter(0)
        val middleRail = railCenter(1.coerceAtMost(maxRow))
        val lowerRail = railCenter(2.coerceAtMost(maxRow))
        // In the reference drawing the summer and carrier branch are between
        // the upper and lower signal rails.  They are not all one center rail.
        val carrierRail = upperRail + (middleRail - upperRail) * 0.55f
        fun centerY(node: DiagramNode, row: Int): Float = when (profileRole(node)) {
            "carrier", "sum", "output" -> carrierRail
            else -> railCenter(row)
        }
        val stageBoxes = spec.nodes.filter { it.id in cells }.map { node ->
            val cell = cells.getValue(node.id)
            val size = sizes.getValue(node.id)
            NodeBox(node,
                x.getValue(cell.col) + (widths.getValue(cell.col) - size.width) / 2,
                centerY(node, cell.row) - size.height / 2f,
                size.width, size.height, cell.col, cell.row)
        }
        val testBoxes = testPoints.map { (role, node) ->
            val (leftColumn, rightColumn, row) = markerLocations.getValue(role)
            val size = sizes.getValue(node.id)
            val leftEdge = x.getValue(leftColumn) + widths.getValue(leftColumn)
            val rightEdge = x.getValue(rightColumn)
            val centerX = if (role == "test_f") {
                // F is the junction on the vertical feed into the summer.  It
                // must share the summer's x coordinate so both legs meet at
                // the marker instead of leaving a short diagonal segment.
                val summer = byRole["sum"].orEmpty().firstOrNull()
                val summerCell = summer?.let { cells[it.id] }
                if (summerCell != null) {
                    x.getValue(summerCell.col) + widths.getValue(summerCell.col) / 2f
                } else {
                    (leftEdge + rightEdge) / 2f
                }
            } else {
                // E is immediately before the Hilbert block in the reference.
                // Keeping it slightly toward the preceding LPF leaves room for
                // the marker dot, its letter, and the incoming arrowhead.
                val fraction = if (role == "test_e") 0.55f else 0.5f
                leftEdge + (rightEdge - leftEdge) * fraction
            }
            val centerY = when (role) {
                "test_f" -> (carrierRail + lowerRail) / 2f
                "test_g" -> carrierRail
                else -> railCenter(row)
            }
            NodeBox(node,
                centerX - size.width / 2f,
                centerY - size.height / 2f,
                size.width, size.height, rightColumn, row)
        }
        val byStageId = stageBoxes.associateBy { it.node.id }
        val byTestId = testBoxes.associateBy { it.node.id }
        val boxes = spec.nodes.map { node ->
            byStageId[node.id] ?: byTestId.getValue(node.id)
        }
        val byId = boxes.associateBy { it.node.id }
        val phaseBox = byRole["phase_shift"].orEmpty().firstOrNull()?.let { byId[it.id] }
        val controlTrunkX = phaseBox?.centerX
        val routes = spec.edges.map { edge ->
            val from = byId.getValue(edge.from)
            val to = byId.getValue(edge.to)
            val fromRole = profileRole(from.node)
            val toRole = profileRole(to.node)
            val departure = if (fromRole == "test_f" && toRole == "sum") DiagramPort.TOP
            else resolvePort(edge.fromPort, from, to)
            // In this fixed profile the phase shifter sits *above* the lower
            // mixer.  Its output enters that mixer from the top even when an
            // older model supplied the generic carrier "bottom" hint.
            val arrival = when {
                fromRole == "phase_shift" && toRole == "lower_mixer" -> DiagramPort.TOP
                fromRole == "carrier" && toRole == "phase_shift" -> DiagramPort.TOP
                toRole == "test_f" && fromRole in setOf("lower_hilbert", "lower_filter") -> DiagramPort.BOTTOM
                else -> resolvePort(edge.toPort, to, from)
            }
            val start = anchor(from, departure)
            val end = anchor(to, arrival)
            val textbookPoints = when {
                // Keep the entire upper rail level through C.  The generic
                // obstacle router can drop immediately after that marker,
                // leaving an unintended step in the rectangular outline.
                fromRole == "test_c" && toRole == "sum" -> listOf(
                    start,
                    DiagramPoint(to.centerX, start.y),
                    end,
                )
                // The carrier extractor feeds one vertical trunk.  Both
                // arrows share the horizontal carrier line, then split up to
                // the upper mixer and down into the phase shifter, matching
                // the reference drawing instead of creating two offset rails.
                fromRole == "carrier" && toRole == "upper_mixer" && controlTrunkX != null -> listOf(
                    start,
                    DiagramPoint(controlTrunkX, start.y),
                    end,
                )
                fromRole == "carrier" && toRole == "phase_shift" && controlTrunkX != null -> listOf(
                    start,
                    DiagramPoint(controlTrunkX, start.y),
                    end,
                )
                // F is a point on the vertical feed into the summer.  The
                // lower signal stays on its rail until the right-hand turn.
                toRole == "test_f" && fromRole in setOf("lower_hilbert", "lower_filter") -> listOf(
                    start,
                    DiagramPoint(end.x, start.y),
                    end,
                )
                else -> null
            }
            EdgeRoute(edge, start, end, departure, arrival,
                textbookPoints ?: DiagramRouting.route(start, end, departure, arrival, boxes,
                    from.node.renderShape() == DiagramNodeShape.JUNCTION,
                    to.node.renderShape() == DiagramNodeShape.JUNCTION))
        }
        val labelWidth = spec.edges.map { DiagramText.layout(it.label.orEmpty(), DiagramTextRole.EDGE_LABEL, 140f) }
            .maxOfOrNull { it.width } ?: 0f
        val labelHeight = spec.edges.map { DiagramText.layout(it.label.orEmpty(), DiagramTextRole.EDGE_LABEL, 140f) }
            .maxOfOrNull { it.height } ?: 0f
        val maxX = max(boxes.maxOfOrNull { it.x + it.width } ?: 0f,
            routes.flatMap { it.points }.maxOfOrNull { it.x } ?: 0f)
        val maxY = max(boxes.maxOfOrNull { it.y + it.height } ?: 0f,
            routes.flatMap { it.points }.maxOfOrNull { it.y } ?: 0f)
        return DiagramLayoutResult(
            maxOf(maxX + TEMPLATE_PADDING + labelWidth / 2f + 8f,
                title.width + TEMPLATE_PADDING * 2, DiagramMetrics.MIN_WIDTH),
            maxOf(maxY + TEMPLATE_PADDING + labelHeight / 2f + 8f, DiagramMetrics.MIN_HEIGHT),
            spec.title, boxes, routes,
            boxes.groupBy { it.row }.mapValues { (_, row) -> row.map { it.node.id } })
    }

    private fun profileRole(node: DiagramNode): String {
        val values = listOfNotNull(node.role, node.id, node.label)
            .map { it.trim().lowercase().replace('-', '_').replace(' ', '_') }
        val explicitRole = node.role?.trim()?.lowercase()
            ?.replace('-', '_')?.replace(' ', '_')
        fun has(vararg aliases: String) = values.any { value ->
            aliases.any { alias -> value == alias || (alias.length >= 4 && value.contains(alias)) }
        }
        // An explicit semantic role always wins over an id/label inference.
        // This matters for the legacy shorthand `id=split,label=A`: the A is
        // a split marker there, while `role=test_a` must remain a test point.
        if (explicitRole != null && hasExplicitTestRole(explicitRole)) {
            return explicitRole
        }
        if (explicitRole != null && (explicitRole == "split" || explicitRole.contains("branch"))) {
            return "split"
        }
        if (node.renderShape() == DiagramNodeShape.SUM || has("sum", "adder", "merge")) return "sum"
        // Check split/branch before bare A~G labels.  Older prompts used a
        // junction labelled A as the branch node, not as test point A.
        if (has("split", "branch")) return "split"
        if (has("test_a") || (node.shape == DiagramNodeShape.JUNCTION && values.any { it == "a" })) return "test_a"
        if (has("test_b") || (node.shape == DiagramNodeShape.JUNCTION && values.any { it == "b" })) return "test_b"
        if (has("test_c") || (node.shape == DiagramNodeShape.JUNCTION && values.any { it == "c" })) return "test_c"
        if (has("test_d") || (node.shape == DiagramNodeShape.JUNCTION && values.any { it == "d" })) return "test_d"
        if (has("test_e") || (node.shape == DiagramNodeShape.JUNCTION && values.any { it == "e" })) return "test_e"
        if (has("test_f") || (node.shape == DiagramNodeShape.JUNCTION && values.any { it == "f" })) return "test_f"
        if (has("test_g") || (node.shape == DiagramNodeShape.JUNCTION && values.any { it == "g" })) return "test_g"
        if (has("upper_mixer", "top_mixer", "mixer_i", "mix_i", "mi")) return "upper_mixer"
        if (has("lower_mixer", "bottom_mixer", "mixer_q", "mix_q", "mq")) return "lower_mixer"
        if (has("upper_filter", "top_filter", "filter_i", "lpf_i", "fi")) return "upper_filter"
        if (has("lower_filter", "bottom_filter", "filter_q", "lpf_q", "fq")) return "lower_filter"
        if (has("hilbert", "hilbert_filter", "q_filter")) return "lower_hilbert"
        if (has("phase_shift", "phase", "shift", "minus_90")) return "phase_shift"
        if (has("carrier_extract", "carrier_source", "carrier", "oscillator", "car")) return "carrier"
        if (node.shape == DiagramNodeShape.JUNCTION) return "split"
        if (has("output", "out", "result")) return "output"
        if (has("input", "source", "signal_in", "in") || values.any { it == "s" }) return "input"
        return "other"
    }

    private fun hasExplicitTestRole(role: String): Boolean =
        role == "test_a" || role == "test_b" || role == "test_c" ||
            role == "test_d" || role == "test_e" || role == "test_f" || role == "test_g"

    private fun grid(spec: DiagramSpec): Map<String, Cell> {
        // Vertical ports identify auxiliary branches, even if the carrier appears first.
        val horizontal = spec.edges.filter {
            it.fromPort !in setOf(DiagramPort.TOP, DiagramPort.BOTTOM) &&
                it.toPort !in setOf(DiagramPort.TOP, DiagramPort.BOTTOM)
        }
        val outgoing = horizontal.groupBy { it.from }
        // Memoize paths to keep even dense diagrams bounded. A cycle is cut at the active node.
        val memo = mutableMapOf<String, List<String>>()
        fun longest(id: String, active: Set<String>): List<String> {
            if (id in active) return emptyList()
            memo[id]?.let { return it }
            val tail = outgoing[id].orEmpty().map { longest(it.to, active + id) }
                .maxByOrNull { it.size }.orEmpty()
            return (listOf(id) + tail).distinct().also { memo[id] = it }
        }
        val roots = spec.nodes.filter { node -> horizontal.none { it.to == node.id } }
        val chain = roots.ifEmpty { spec.nodes.take(1) }.map { longest(it.id, emptySet()) }
            .maxByOrNull { it.size }.orEmpty()
        val grid = linkedMapOf<String, Cell>()
        fun place(id: String, suggested: Cell) {
            val node = spec.nodes.first { it.id == id }
            var cell = Cell(node.column ?: suggested.col, node.row ?: suggested.row)
            while (cell in grid.values) cell = cell.copy(row = cell.row + 1)
            grid[id] = cell
        }
        var nextColumn = 0
        chain.forEach { id ->
            place(id, Cell(nextColumn, 0))
            nextColumn = grid.getValue(id).col + 1
        }
        val pending = spec.nodes.filter { it.id !in grid }.toMutableList()
        while (pending.isNotEmpty()) {
            val node = pending.firstOrNull { n -> spec.edges.any {
                (it.from == n.id && it.to in grid) || (it.to == n.id && it.from in grid)
            } } ?: pending.first()
            val incoming = spec.edges.firstOrNull { it.to == node.id && it.from in grid }
            val outgoingEdge = spec.edges.firstOrNull { it.from == node.id && it.to in grid }
            val suggested = when {
                incoming != null -> {
                    val base = grid.getValue(incoming.from)
                    when {
                        incoming.fromPort == DiagramPort.TOP || incoming.toPort == DiagramPort.BOTTOM -> Cell(base.col, base.row - 1)
                        incoming.fromPort == DiagramPort.BOTTOM || incoming.toPort == DiagramPort.TOP -> Cell(base.col, base.row + 1)
                        incoming.fromPort == DiagramPort.LEFT || incoming.toPort == DiagramPort.RIGHT -> Cell(base.col - 1, base.row)
                        else -> Cell(base.col + 1, base.row)
                    }
                }
                outgoingEdge != null -> {
                    val base = grid.getValue(outgoingEdge.to)
                    when {
                        outgoingEdge.toPort == DiagramPort.BOTTOM || outgoingEdge.fromPort == DiagramPort.TOP -> Cell(base.col, base.row + 1)
                        outgoingEdge.toPort == DiagramPort.TOP || outgoingEdge.fromPort == DiagramPort.BOTTOM -> Cell(base.col, base.row - 1)
                        outgoingEdge.toPort == DiagramPort.RIGHT || outgoingEdge.fromPort == DiagramPort.LEFT -> Cell(base.col + 1, base.row)
                        else -> Cell(base.col - 1, base.row)
                    }
                }
                else -> Cell(0, (grid.values.maxOfOrNull { it.row } ?: -1) + 1)
            }
            place(node.id, suggested)
            pending.remove(node)
        }
        return grid
    }

    private fun starts(sizes: Map<Int, Float>, start: Float, gap: Float): Map<Int, Float> {
        var cursor = start
        return sizes.keys.sorted().associateWith { key -> cursor.also { cursor += sizes.getValue(key) + gap } }
    }

    internal fun label(node: DiagramNode) = DiagramText.layout(node.label, DiagramTextRole.LABEL, 210f)
    internal fun subLabel(node: DiagramNode) = DiagramText.layout(node.subLabel.orEmpty(), DiagramTextRole.SUB_LABEL, 210f)

    internal fun sizeOf(node: DiagramNode): Size = when (node.renderShape()) {
        // Keep circular operators large enough to remain legible after the
        // diagram is fitted into a message bubble.  DiagramMetrics is the
        // single source of truth for the renderer and layout geometry.
        DiagramNodeShape.MIXER, DiagramNodeShape.SUM ->
            Size(DiagramMetrics.MIXER_DIAMETER, DiagramMetrics.MIXER_DIAMETER)
        DiagramNodeShape.JUNCTION -> Size(12f, 12f)
        else -> {
            val main = label(node)
            val sub = subLabel(node)
            val padding = if (node.renderShape() == DiagramNodeShape.IO) 8f else 16f
            Size(maxOf(main.width, sub.width, if (node.renderShape() == DiagramNodeShape.IO) 30f else 72f) + padding * 2,
                maxOf(main.height + sub.height + (if (sub.height > 0) 4f else 0f), 22f) + padding * 2)
        }
    }

    internal fun resolvePort(port: DiagramPort, self: NodeBox, other: NodeBox): DiagramPort {
        if (port != DiagramPort.AUTO) return port
        val dx = other.centerX - self.centerX
        val dy = other.centerY - self.centerY
        return if (abs(dx) < 1f) {
            if (dy >= 0) DiagramPort.BOTTOM else DiagramPort.TOP
        } else if (dx >= 0) DiagramPort.RIGHT else DiagramPort.LEFT
    }

    internal fun anchor(box: NodeBox, port: DiagramPort) = when (port) {
        DiagramPort.LEFT -> DiagramPoint(box.x, box.centerY)
        DiagramPort.RIGHT -> DiagramPoint(box.x + box.width, box.centerY)
        DiagramPort.TOP -> DiagramPoint(box.centerX, box.y)
        DiagramPort.BOTTOM -> DiagramPoint(box.centerX, box.y + box.height)
        DiagramPort.AUTO -> error("Unresolved port")
    }
}
