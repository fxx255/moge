package com.moge.app.ui.figure

import com.moge.app.domain.coding.CodingSpec
import com.moge.app.ui.figure.CodingDiagramRenderer.P
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class CodingReferenceTemplateTest {
    @Test fun k3KeepsTheReferenceLeftBottomAndRightAdderInputs() {
        val code = CodingSpec(memory = 2, generators = listOf(listOf(0, 1, 2), listOf(0, 2)))
        val plan = CodingDiagramRenderer.convolutionalLayout(code)
        val ports = plan.branches.filter { it.output == 0 }.associate { it.tap to it.points.last() }
        assertEquals(2, plan.radii.count { it > 0 })
        assertEquals(P(plan.sumX - plan.radii[0], plan.rows[0]), ports.getValue(0))
        assertEquals(plan.sumX, ports.getValue(1).x, .001f)
        assertEquals(plan.rows[0] + plan.radii[0], ports.getValue(1).y, .001f)
        assertEquals(P(plan.sumX + plan.radii[0], plan.rows[0]), ports.getValue(2))
    }

    @Test fun expandedTapWiresNeverPassThroughAnotherOutputAdder() {
        for (memory in 1..8) for (outputs in 1..4) {
            val code = code(memory, outputs)
            val plan = CodingDiagramRenderer.convolutionalLayout(code)
            assertEquals(code.generators.sumOf { it.size }, plan.branches.size)
            plan.branches.forEach { branch ->
                assertEquals(P(plan.taps[branch.tap], plan.y), branch.points.first())
                assertEquals(plan.radii[branch.output], distance(branch.points.last(),
                    P(plan.sumX, plan.rows[branch.output])), .01f)
                for (other in plan.rows.indices.filter { it != branch.output && plan.radii[it] > 0 }) {
                    val center = P(plan.sumX, plan.rows[other])
                    branch.points.zipWithNext().forEach { (a, b) ->
                        assertTrue("memory=$memory outputs=$outputs tap=${branch.tap} output=${branch.output} crosses adder $other",
                            segmentDistance(a, b, center) >= plan.radii[other] - .01f)
                    }
                }
            }
        }
    }

    @Test fun outputWiresHaveSeparateLanesWithoutSharedSegments() {
        for (memory in 1..8) for (outputs in 1..4) {
            val wires = CodingDiagramRenderer.convolutionalOutputs(
                CodingDiagramRenderer.convolutionalLayout(code(memory, outputs)), 2020f)
            for (i in wires.indices) for (j in i + 1 until wires.size) {
                wires[i].zipWithNext().forEach { (a, b) -> wires[j].zipWithNext().forEach { (c, d) ->
                    val horizontal = abs(a.y - b.y) < .001 && abs(c.y - d.y) < .001 && abs(a.y - c.y) < .001 &&
                        overlap(a.x, b.x, c.x, d.x) > .001
                    val vertical = abs(a.x - b.x) < .001 && abs(c.x - d.x) < .001 && abs(a.x - c.x) < .001 &&
                        overlap(a.y, b.y, c.y, d.y) > .001
                    assertFalse("memory=$memory outputs=$outputs wires $i/$j share a segment", horizontal || vertical)
                } }
            }
        }
    }

    private fun code(memory: Int, outputs: Int) = CodingSpec(memory = memory,
        generators = (0 until outputs).map { output ->
            if (output == 0) (0..memory).toList()
            else (0..memory).filter { (it + output) % 3 != 0 }.ifEmpty { listOf(memory) }
        })
    private fun overlap(a: Float, b: Float, c: Float, d: Float): Float =
        minOf(maxOf(a, b), maxOf(c, d)) - maxOf(minOf(a, b), minOf(c, d))
    private fun distance(a: P, b: P) = hypot(a.x - b.x, a.y - b.y)
    private fun segmentDistance(a: P, b: P, p: P): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = dx * dx + dy * dy
        val t = if (length == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / length).coerceIn(0f, 1f)
        return distance(p, P(a.x + dx * t, a.y + dy * t))
    }
}
