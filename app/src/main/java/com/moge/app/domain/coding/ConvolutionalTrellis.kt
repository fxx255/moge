package com.moge.app.domain.coding

data class TrellisBranch(val time: Int, val transition: CodeTransition, val distance: Int?)

data class TrellisResult(
    val layers: List<Set<Int>>,
    val branches: List<TrellisBranch>,
    val metrics: List<Map<Int, Int>>,
    val survivors: Set<TrellisBranch>,
    val highlightedPath: List<TrellisBranch>,
    val decodedBits: String?,
    val bestDistance: Int?,
)

/** Shares exactly the encoder's state/bit convention. No coordinates or hand-authored graph nodes. */
object ConvolutionalTrellis {
    fun build(code: CodingSpec, spec: TrellisSpec): TrellisResult {
        spec.validate(code)
        val transitions = ConvolutionalCode.transitions(code).groupBy { it.from }
        val reachable = MutableList(spec.steps + 1) { mutableSetOf<Int>() }
        reachable[0] += spec.initialState
        val candidates = List(spec.steps) { time ->
            val tail = spec.terminated && time >= spec.steps - code.memory
            reachable[time].sorted().flatMap { state -> transitions.getValue(state).filter { !tail || it.input == 0 } }
                .onEach { reachable[time + 1] += it.to }
        }
        // Remove branches which cannot reach the zero terminal state, including the closing tail.
        val valid = MutableList(spec.steps + 1) { mutableSetOf<Int>() }
        valid.last() += if (spec.terminated) setOf(0) else reachable.last()
        for (time in spec.steps - 1 downTo 0) {
            candidates[time].filter { it.to in valid[time + 1] }.forEach { valid[time] += it.from }
        }
        val received = spec.receivedBits.takeIf(String::isNotEmpty)?.chunked(code.generators.size)
        val branches = candidates.flatMapIndexed { time, edges ->
            edges.filter { it.from in valid[time] && it.to in valid[time + 1] }.map { transition ->
                TrellisBranch(time, transition, received?.get(time)?.let { bits ->
                    transition.output.zip(bits.toList()).count { (bit, character) -> bit != character.digitToInt() }
                })
            }
        }
        val metrics = MutableList(spec.steps + 1) { mutableMapOf<Int, Int>() }
        val predecessors = MutableList(spec.steps + 1) { mutableMapOf<Int, TrellisBranch>() }
        if (received != null) {
            metrics[0][spec.initialState] = 0
            for (time in 0 until spec.steps) {
                // Ties retain the lower source-state number, then input 0. The choice is reproducible.
                branches.filter { it.time == time }.sortedWith(compareBy<TrellisBranch> { it.transition.from }.thenBy { it.transition.input })
                    .forEach { branch ->
                        val edge = branch.transition
                        val cost = metrics[time].getValue(edge.from) + requireNotNull(branch.distance)
                        if (cost < (metrics[time + 1][edge.to] ?: Int.MAX_VALUE)) {
                            metrics[time + 1][edge.to] = cost
                            predecessors[time + 1][edge.to] = branch
                        }
                    }
            }
        }
        val bestEnd = metrics.last().entries.minWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })?.key
        val decodedPath = if (bestEnd == null) emptyList() else {
            var state: Int = bestEnd
            (spec.steps downTo 1).map { time ->
                predecessors[time].getValue(state).also { state = it.transition.from }
            }.reversed()
        }
        val highlighted = if (spec.inputBits.isEmpty()) decodedPath else {
            var state = spec.initialState
            val bits = spec.inputBits + if (spec.terminated) "0".repeat(code.memory) else ""
            bits.mapIndexed { time, bit ->
                branches.single { it.time == time && it.transition.from == state && it.transition.input == bit.digitToInt() }
                    .also { state = it.transition.to }
            }
        }
        val decoded = if (received == null) null else decodedPath.joinToString("") { it.transition.input.toString() }
            .let { if (spec.terminated) it.dropLast(code.memory) else it }
        return TrellisResult(valid.map { it.toSet() }, branches, metrics.map { it.toMap() },
            predecessors.flatMap { it.values }.toSet(), highlighted, decoded, bestEnd?.let { metrics.last()[it] })
    }

    /** a=00, b=10, c=01, d=11 in the reference textbook. */
    fun row(state: Int, memory: Int): Int = Integer.reverse(state) ushr (32 - memory)
}
