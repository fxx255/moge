package com.moge.app.domain.diagram

/** All signal paths are explicit and orthogonal. Geometry never infers or changes tap semantics. */
internal object LfsrLayout {
    data class Register(val index: Int, val x: Float, val width: Float)
    data class Wire(val points: List<DiagramPoint>, val arrow: Boolean = true)
    data class Plan(
        val width: Float, val height: Float, val registerY: Float,
        val registers: List<Register>, val wires: List<Wire>,
        val taps: List<DiagramPoint>, val adders: List<DiagramPoint>,
        val stageOutputs: List<DiagramPoint>,
        val clockY: Float?, val output: DiagramPoint,
    )

    const val BOX_HEIGHT = 58f
    const val XOR_RADIUS = 19f

    fun layout(
        spec: LfsrSpec,
        widths: List<Float> = List(spec.stages) { 108f },
        outputWidth: Float = 48f,
        stageOutputWidths: List<Float> = List(spec.stages) { 30f },
    ): Plan {
        spec.validate()
        require(widths.size == spec.stages && widths.all { it.isFinite() && it >= 80f })
        require(stageOutputWidths.size == spec.stages && stageOutputWidths.all { it.isFinite() && it >= 0f })
        require(outputWidth.isFinite() && outputWidth >= 0f)
        val registers = mutableListOf<Register>()
        var x = 80f
        widths.forEachIndexed { index, width ->
            registers += Register(index, x, width)
            x += width + maxOf(78f, stageOutputWidths[index] + 36f)
        }
        val y = if (spec.clockLabel.isEmpty()) 105f else 155f
        val sumY = y + 136f
        val returnY = sumY + 72f
        val endX = registers.last().let { it.x + it.width }
        val wires = mutableListOf<Wire>()
        fun wire(vararg points: DiagramPoint, arrow: Boolean = true) { wires += Wire(points.toList(), arrow) }
        fun p(x: Float, y: Float) = DiagramPoint(x, y)
        registers.zipWithNext().forEach { (a, b) -> wire(p(a.x + a.width, y), p(b.x, y)) }
        val lastSignalSpan = maxOf(78f, stageOutputWidths.last() + 36f)
        val output = p(endX + lastSignalSpan + if (outputWidth > 0f) outputWidth + 32f else 16f, y)
        wire(p(endX, y), output)
        val stageOutputs = registers.mapIndexed { index, register ->
            val nextX = registers.getOrNull(index + 1)?.x ?: (endX + lastSignalSpan)
            p((register.x + register.width + nextX) / 2f, y - 27f)
        }
        val taps = spec.taps.sorted().map { tap -> registers[tap - 1].let { p(it.x + it.width + 30f, y) } }
        val adders = mutableListOf<DiagramPoint>()
        // Fold the taps from right to left. Each XOR has exactly two inputs and one output.
        // The lower return rail starts at the final XOR's bottom port, never on the shift rail.
        if (taps.size == 1) {
            wire(taps.single(), p(taps.single().x, returnY), p(34f, returnY), p(34f, y), p(80f, y))
        } else {
            taps.dropLast(1).forEach { tap -> adders += p(tap.x, sumY) }
            val right = taps.last()
            val lastAdder = adders.last()
            wire(right, p(right.x, sumY), p(lastAdder.x + XOR_RADIUS, sumY))
            taps.dropLast(1).zip(adders).forEach { (tap, adder) ->
                wire(tap, p(tap.x, adder.y - XOR_RADIUS))
            }
            adders.zipWithNext().forEach { (left, rightAdder) ->
                wire(p(rightAdder.x - XOR_RADIUS, sumY), p(left.x + XOR_RADIUS, sumY))
            }
            val first = adders.first()
            wire(p(first.x, first.y + XOR_RADIUS), p(first.x, returnY), p(34f, returnY), p(34f, y), p(80f, y))
        }
        return Plan(output.x + 76f, returnY + 38f, y, registers, wires, taps, adders, stageOutputs,
            if (spec.clockLabel.isEmpty()) null else y - 72f, output)
    }
}
