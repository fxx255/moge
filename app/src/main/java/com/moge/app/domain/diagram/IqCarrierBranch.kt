package com.moge.app.domain.diagram

/** Shared modulator carrier and phase shifter, kept between the two signal rails. */
internal class IqCarrierBranch private constructor(
    val carrier: DiagramNode,
    val phase: DiagramNode,
    val directMixer: DiagramNode,
    val shiftedMixer: DiagramNode,
) {
    val controlIds = setOf(carrier.id, phase.id)
    private val mixerIds = setOf(directMixer.id, shiftedMixer.id)

    fun fits(stage: Map<String, Int>, lanes: Map<String, Set<Int>>): Boolean =
        lanes.values.flatten().distinct().size == 2 &&
            lanes[directMixer.id]?.size == 1 && lanes[shiftedMixer.id]?.size == 1 &&
            lanes[directMixer.id] != lanes[shiftedMixer.id] &&
            stage[directMixer.id] == stage[shiftedMixer.id] &&
            stage.filterValues { it == stage[directMixer.id] }.keys == mixerIds

    fun railClearance(sizes: Map<String, Size>): Float =
        sizes.getValue(carrier.id).height + sizes.getValue(phase.id).height + GAP * 3

    fun mixerInset(sizes: Map<String, Size>): Float =
        maxOf(sizes.getValue(phase.id).width, sizes.getValue(directMixer.id).width,
            sizes.getValue(shiftedMixer.id).width) / 2f

    fun columnWidth(sizes: Map<String, Size>): Float =
        mixerInset(sizes) * 2 + GAP + sizes.getValue(carrier.id).width

    fun place(signalBoxes: List<NodeBox>, sizes: Map<String, Size>): List<NodeBox> {
        val direct = signalBoxes.first { it.node.id == directMixer.id }
        val shifted = signalBoxes.first { it.node.id == shiftedMixer.id }
        val (upper, lower) = listOf(direct, shifted).sortedBy { it.centerY }
        val carrierSize = sizes.getValue(carrier.id)
        val phaseSize = sizes.getValue(phase.id)
        val contentHeight = carrierSize.height + GAP + phaseSize.height
        val top = upper.y + upper.height
        val bottom = lower.y
        val margin = (bottom - top - contentHeight) / 2f
        val carrierY = if (direct === upper) top + margin + carrierSize.height / 2f
            else bottom - margin - carrierSize.height / 2f
        val phaseY = carrierY + (if (direct === upper) 1 else -1) *
            (carrierSize.height / 2f + GAP + phaseSize.height / 2f)
        return listOf(
            NodeBox(carrier, direct.centerX + mixerInset(sizes) + GAP, carrierY - carrierSize.height / 2f,
                carrierSize.width, carrierSize.height, direct.column, upper.row),
            NodeBox(phase, direct.centerX - phaseSize.width / 2f, phaseY - phaseSize.height / 2f,
                phaseSize.width, phaseSize.height, direct.column, upper.row),
        )
    }

    fun route(edge: DiagramEdge, boxes: Map<String, NodeBox>): EdgeRoute? {
        val from = boxes.getValue(edge.from)
        val to = boxes.getValue(edge.to)
        val direct = boxes.getValue(directMixer.id)
        val shifted = boxes.getValue(shiftedMixer.id)
        val directAbove = direct.centerY < shifted.centerY
        val departure: DiagramPort
        val arrival: DiagramPort
        when (edge.from to edge.to) {
            carrier.id to directMixer.id -> {
                departure = DiagramPort.LEFT
                arrival = if (directAbove) DiagramPort.BOTTOM else DiagramPort.TOP
            }
            carrier.id to phase.id -> {
                departure = DiagramPort.LEFT
                arrival = if (directAbove) DiagramPort.TOP else DiagramPort.BOTTOM
            }
            phase.id to shiftedMixer.id -> {
                departure = if (directAbove) DiagramPort.BOTTOM else DiagramPort.TOP
                arrival = if (directAbove) DiagramPort.TOP else DiagramPort.BOTTOM
            }
            else -> return null
        }
        val start = DiagramLayout.anchor(from, departure)
        val end = DiagramLayout.anchor(to, arrival)
        val points = if (edge.from == carrier.id) listOf(start, DiagramPoint(direct.centerX, start.y), end)
            else listOf(start, end)
        return EdgeRoute(edge, start, end, departure, arrival, points)
    }

    companion object {
        private const val GAP = 36f

        fun find(spec: DiagramSpec): IqCarrierBranch? {
            val nodes = spec.nodes.associateBy { it.id }
            return spec.nodes.sortedBy { it.id }.firstNotNullOfOrNull { phase ->
                val key = phase.role.orEmpty().ifBlank { phase.id }.lowercase().replace('-', '_')
                if (!(key.contains("phase") || key.contains("shift") || phase.label.contains("移相")))
                    return@firstNotNullOfOrNull null
                val sourceEdge = spec.edges.singleOrNull { it.to == phase.id } ?: return@firstNotNullOfOrNull null
                val carrier = nodes.getValue(sourceEdge.from)
                if (spec.edges.any { it.to == carrier.id }) return@firstNotNullOfOrNull null
                val shifted = spec.edges.singleOrNull { it.from == phase.id }?.to?.let(nodes::getValue)
                    ?.takeIf { it.renderShape() == DiagramNodeShape.MIXER } ?: return@firstNotNullOfOrNull null
                val feeds = spec.edges.filter { it.from == carrier.id }
                val direct = feeds.singleOrNull { it.to != phase.id }?.to?.let(nodes::getValue)
                    ?.takeIf { it.renderShape() == DiagramNodeShape.MIXER } ?: return@firstNotNullOfOrNull null
                if (feeds.size != 2 || direct.id == shifted.id) return@firstNotNullOfOrNull null
                val directOutputs = spec.edges.filter { it.from == direct.id }.map { it.to }
                val sums = spec.edges.filter { it.from == shifted.id && it.to in directOutputs }
                    .map { nodes.getValue(it.to) }.filter { it.renderShape() == DiagramNodeShape.SUM }
                if (sums.size != 1) null else IqCarrierBranch(carrier, phase, direct, shifted)
            }
        }
    }
}
