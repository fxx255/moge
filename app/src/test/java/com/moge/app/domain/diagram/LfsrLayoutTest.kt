package com.moge.app.domain.diagram

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class LfsrLayoutTest {
    @Test fun `all tap combinations have two input XORs and an external return path without block crossings`() {
        for (n in 2..8) for (mask in 1 until (1 shl n)) {
            val taps = (1..n).filter { mask and (1 shl (it - 1)) != 0 }
            checkLayout(LfsrSpec(stages = n, taps = taps))
        }
        checkLayout(LfsrSpec(stages = 16, taps = (1..16).toList()))
    }

    private fun checkLayout(spec: LfsrSpec) {
        val p = LfsrLayout.layout(spec)
        assertEquals(spec.taps.size - 1, p.adders.size)
        assertEquals(spec.taps.sorted().map { p.registers[it - 1].let { r -> r.x + r.width + 30f } }, p.taps.map { it.x })
        p.wires.take(spec.stages).forEach { wire -> assertTrue(wire.points.first().x < wire.points.last().x) }
        assertEquals(DiagramPoint(p.registers.first().x, p.registerY), p.wires.last().points.last())
        assertTrue(p.wires.last().points.maxOf { it.y } > (p.adders.maxOfOrNull { it.y } ?: p.registerY))
        for (wire in p.wires) for ((a, b) in wire.points.zipWithNext()) {
            assertTrue(a.x == b.x || a.y == b.y)
            assertTrue(listOf(a, b).all { it.x > 0 && it.x < p.width && it.y > 0 && it.y < p.height })
            for (box in p.registers) {
                val intersects = if (a.y == b.y) a.y > p.registerY - 29 && a.y < p.registerY + 29 &&
                    maxOf(a.x, b.x) > box.x && minOf(a.x, b.x) < box.x + box.width
                else a.x > box.x && a.x < box.x + box.width && maxOf(a.y, b.y) > p.registerY - 29 && minOf(a.y, b.y) < p.registerY + 29
                assertFalse("$spec: wire crosses register ${box.index}", intersects)
            }
            for (adder in p.adders) {
                val dx = b.x - a.x; val dy = b.y - a.y
                val t = (((adder.x - a.x) * dx + (adder.y - a.y) * dy) / (dx * dx + dy * dy).coerceAtLeast(.001f)).coerceIn(0f, 1f)
                assertTrue("$spec: wire crosses XOR", hypot(a.x + t * dx - adder.x, a.y + t * dy - adder.y) >= LfsrLayout.XOR_RADIUS - .01f)
            }
        }
        p.adders.forEach { adder ->
            assertEquals(2, p.wires.count { hypot(it.points.last().x - adder.x, it.points.last().y - adder.y) == LfsrLayout.XOR_RADIUS })
            assertEquals(1, p.wires.count { hypot(it.points.first().x - adder.x, it.points.first().y - adder.y) == LfsrLayout.XOR_RADIUS })
        }
    }

    @Test fun `parser round trips explicit taps and rejects ambiguous or unsupported parameters`() {
        val spec = DiagramParser.parseOne(Json.parseToJsonElement("""{"profile":"lfsr","lfsr":{"stages":3,"taps":[1,3],"registerLabels":["D0","D1","D2"],"stageOutputLabels":["a0","a1","a2"]}}""").jsonObject)
        assertEquals(DiagramLayoutProfile.LFSR, spec.profile)
        assertEquals(listOf(1, 3), spec.lfsr!!.taps)
        assertEquals("D0", spec.lfsr.registerLabel(0))
        assertEquals("a0", spec.lfsr.stageOutputLabel(0))
        assertEquals(spec, DiagramParser.parseOne(Json.parseToJsonElement(Json.encodeToString(DiagramSpec.serializer(), spec)).jsonObject))
        for (params in listOf("{}", "{\"stages\":3}", "{\"stages\":3,\"taps\":[0,3]}", "{\"stages\":3,\"taps\":[1,1]}",
            "{\"stages\":17,\"taps\":[1,17]}", "{\"version\":2,\"stages\":3,\"taps\":[1,3]}", "{\"stages\":3,\"taps\":[1,3],\"registerLabels\":[\"D0\"]}",
            "{\"stages\":3,\"taps\":[1,3],\"stageOutputLabels\":[\"a0\"]}",
            "{\"stages\":3,\"taps\":[1,3],\"stageOutputLabels\":[\"a0\",\"\",\"a2\"]}")) {
            assertTrue(params, runCatching { DiagramParser.parseOne(Json.parseToJsonElement("{\"profile\":\"lfsr\",\"lfsr\":$params}").jsonObject) }.isFailure)
        }
    }

    @Test fun `register devices and output signals have separate defaults and legacy states stay on wires`() {
        val defaults = LfsrSpec(stages = 3, taps = listOf(1, 3))
        val devices = listOf("\$D_{0}\$", "\$D_{1}\$", "\$D_{2}\$")
        assertEquals(devices, (0..2).map(defaults::registerLabel))
        val previous = defaults.copy(registerLabels = List(3) { "寄存器" })
        assertEquals(devices, (0..2).map(previous::registerLabel))
        assertEquals(listOf("\$a_{0}\$", "\$a_{1}\$", "\$a_{2}\$"), (0..2).map(defaults::stageOutputLabel))
        val stored = defaults.copy(registerLabels = listOf("\$a_{n-1}\$", "a₁", "D2"))
        assertEquals(listOf("\$D_{0}\$", "\$D_{1}\$", "D2"), (0..2).map(stored::registerLabel))
        assertEquals(listOf("\$a_{n-1}\$", "a₁", "\$a_{2}\$"), (0..2).map(stored::stageOutputLabel))
    }

    @Test fun `stage signal labels fit between devices and before the separate terminal output label`() {
        val widths = listOf(28f, 180f, 350f)
        val plan = LfsrLayout.layout(LfsrSpec(stages = 3, taps = listOf(1, 3)),
            outputWidth = 200f, stageOutputWidths = widths)
        plan.stageOutputs.forEachIndexed { index, label ->
            val left = label.x - widths[index] / 2
            val right = label.x + widths[index] / 2
            val register = plan.registers[index]
            assertTrue(left > register.x + register.width)
            assertTrue(label.y < plan.registerY)
            val next = plan.registers.getOrNull(index + 1)
            if (next != null) assertTrue(right < next.x)
            else assertTrue(right < plan.output.x - 14 - 200)
        }
    }

    @Test fun `long output labels reserve space beyond the last register and tap`() {
        val plan = LfsrLayout.layout(LfsrSpec(stages = 3, taps = listOf(1, 3)), outputWidth = 570f)
        val last = plan.registers.last()
        val labelLeft = plan.output.x - 14 - 570
        assertTrue(labelLeft > last.x + last.width + 30)
        assertTrue(plan.width > plan.output.x)
    }

    @Test fun `legacy conversion follows actual shift direction and retains the two independent tap sets`() {
        val spec = legacy()
        assertEquals(listOf(2, 3), LegacyLfsrAdapter.sequences(spec)!!.single().taps)
        val second = spec.copy(nodes = spec.nodes.map { it.copy(id = "b${it.id}") },
            edges = spec.edges.map { it.copy(from = "b${it.from}", to = "b${it.to}") })
        val pair = spec.copy(nodes = spec.nodes + second.nodes, edges = spec.edges + second.edges)
        assertEquals(2, LegacyLfsrAdapter.sequences(pair)!!.size)
        val reversedLabels = spec.copy(nodes = spec.nodes.map { if (it.id == "d0") it.copy(label = "D2") else if (it.id == "d2") it.copy(label = "D0") else it })
        assertEquals(listOf("D2", "D1", "D0"), LegacyLfsrAdapter.sequences(reversedLabels)!!.single().registerLabels)
    }

    @Test fun `legacy conversion refuses a broken feedback or an extra ambiguous connection`() {
        val spec = legacy()
        assertNull(LegacyLfsrAdapter.sequences(spec.copy(edges = spec.edges.dropLast(1))))
        assertNull(LegacyLfsrAdapter.sequences(spec.copy(edges = spec.edges + DiagramEdge("d2", "d1"))))
        assertNull(LegacyLfsrAdapter.sequences(spec.copy(edges = spec.edges + DiagramEdge("d0", "out"))))
    }

    companion object {
        fun legacy() = DiagramSpec("三阶 m 序列发生器", nodes = listOf(
            DiagramNode("d0", "D0", DiagramNodeShape.BLOCK), DiagramNode("d1", "D1", DiagramNodeShape.BLOCK),
            DiagramNode("d2", "D2", DiagramNodeShape.BLOCK), DiagramNode("s", "模2加", DiagramNodeShape.SUM),
            DiagramNode("out", "输出", DiagramNodeShape.IO)), edges = listOf(
            DiagramEdge("d0", "d1"), DiagramEdge("d1", "d2"), DiagramEdge("d2", "out"),
            DiagramEdge("d1", "s"), DiagramEdge("d2", "s"), DiagramEdge("s", "d0")))
    }
}
