package com.moge.app.domain.diagram

/** Upgrade only complete, unambiguous old Fibonacci topologies; never invent a missing wire. */
internal object LegacyLfsrAdapter {
    fun sequences(spec: DiagramSpec): List<LfsrSpec>? {
        if (spec.profile !in setOf(DiagramLayoutProfile.GENERIC, DiagramLayoutProfile.COMMUNICATION)) return null
        if (spec.nodes.isEmpty() || spec.edges.any { it.dashed || it.polarity in setOf("-", "−") }) return null
        if (!Regex("(?i)(?:m\\s*序列|移位寄存|线性反馈|lfsr|shift.?register|伪随机)").containsMatchIn(spec.title)) return null
        val byId = spec.nodes.associateBy { it.id }
        fun register(n: DiagramNode) = n.shape == DiagramNodeShape.BLOCK &&
            (n.role.orEmpty().lowercase() in setOf("register", "shift_register", "delay") ||
                Regex("(?i)(?:D_?\\{?\\d|z\\^|移位|寄存器)").containsMatchIn(n.label))
        fun xor(n: DiagramNode) = n.shape == DiagramNodeShape.SUM ||
            n.glyph in setOf("⊕", "+") || n.role.orEmpty().lowercase() in setOf("xor", "mod2", "modulo2")
        fun junction(n: DiagramNode) = n.shape == DiagramNodeShape.JUNCTION
        fun clock(n: DiagramNode) = n.role.orEmpty().lowercase() == "clock" || n.label.trim().uppercase() in setOf("CP", "CLK", "时钟")
        val registers = spec.nodes.filter(::register)
        if (registers.size < 2 || spec.nodes.any { !register(it) && !xor(it) && !junction(it) && !clock(it) &&
                !(it.shape == DiagramNodeShape.IO && it.label in setOf("输出", "output")) }) return null
        val signalEdges = spec.edges.filterNot { edge -> clock(byId.getValue(edge.from)) }
        val incoming = signalEdges.groupBy { it.to }
        val outgoing = signalEdges.groupBy { it.from }
        val consumed = mutableSetOf<DiagramEdge>()
        fun sources(id: String, active: Set<String> = emptySet()): List<String>? {
            if (id in active) return null
            val n = byId.getValue(id)
            if (!junction(n)) return listOf(id)
            val edges = incoming[id].orEmpty()
            if (edges.size != 1) return null
            consumed += edges
            return sources(edges.single().from, active + id)
        }
        val predecessor = registers.associate { r ->
            r.id to incoming[r.id].orEmpty().flatMap { consumed += it; sources(it.from) ?: return null }
        }
        if (predecessor.values.any { it.size != 1 }) return null
        val registerIds = registers.map { it.id }.toSet()
        val heads = registers.filter { predecessor.getValue(it.id).single() !in registerIds }
        if (heads.isEmpty() || heads.size > 4) return null
        val used = mutableSetOf<String>()
        val usedXors = mutableSetOf<String>()
        val sequences = heads.map { head ->
            val chain = mutableListOf(head)
            while (true) {
                val next = registers.filter { predecessor.getValue(it.id).single() == chain.last().id }
                if (next.isEmpty()) break
                if (next.size != 1 || next.single() in chain) return null
                chain += next.single()
            }
            if (chain.size !in 2..16 || chain.any { !used.add(it.id) }) return null
            val index = chain.mapIndexed { i, n -> n.id to i + 1 }.toMap()
            val visited = mutableSetOf<String>()
            fun taps(id: String): List<Int>? {
                index[id]?.let { return listOf(it) }
                if (!visited.add(id)) return null
                val n = byId.getValue(id)
                if (!xor(n) && !junction(n)) return null
                if (xor(n)) usedXors += id
                val edges = incoming[id].orEmpty()
                if (edges.size != if (xor(n)) 2 else 1) return null
                consumed += edges
                return edges.flatMap { taps(it.from) ?: return null }
            }
            val taps = taps(predecessor.getValue(head.id).single()) ?: return null
            // Verify every outgoing signal terminates at the next register, an XOR, or an output.
            // This excludes mixed encoders and partial drawings with an unrepresented bypass.
            for (n in chain) for (edge in outgoing[n.id].orEmpty()) {
                if (byId.getValue(edge.to).let { !register(it) && !xor(it) && !junction(it) && it.shape != DiagramNodeShape.IO }) return null
            }
            LfsrSpec(stages = chain.size, taps = taps.sorted(), registerLabels = chain.map { listOfNotNull(it.label, it.subLabel).joinToString(" ") },
                clockLabel = spec.nodes.firstOrNull(::clock)?.label.orEmpty()).also {
                if (runCatching { it.validate() }.isFailure) return null
            }
        }
        if (used != registerIds || spec.nodes.filter(::xor).any { it.id !in usedXors }) return null
        // All original edges must be accounted for, including output/clock wiring.
        // Terminal IOs must be driven by the last stage, rather than an internal state or XOR.
        for (n in spec.nodes.filter { it.shape == DiagramNodeShape.IO && !clock(it) }) {
            val src = incoming[n.id].orEmpty().flatMap { consumed += it; sources(it.from) ?: return null }
            val tails = registers.filter { r -> registers.none { predecessor.getValue(it.id).single() == r.id } }.map { it.id }
            if (src.size != 1 || src.single() !in tails) return null
        }
        if (consumed != signalEdges.toSet()) return null
        val clocks = spec.nodes.filter(::clock)
        val clockEdges = spec.edges.filter { clock(byId.getValue(it.from)) }
        if (clocks.isNotEmpty() && (clocks.size != 1 || clockEdges.size != registers.size || clockEdges.map { it.to }.toSet() != registerIds)) return null
        for (edge in clockEdges) {
            if (edge.toPort !in setOf(DiagramPort.TOP, DiagramPort.AUTO)) return null
        }
        return sequences
    }
}
