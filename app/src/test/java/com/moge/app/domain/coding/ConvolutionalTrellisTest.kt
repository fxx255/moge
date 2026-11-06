package com.moge.app.domain.coding

import com.moge.app.domain.diagram.DiagramParser
import com.moge.app.domain.diagram.DiagramSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ConvolutionalTrellisTest {
    private val code = CodingSpec(memory = 2, generators = listOf(listOf(0, 1, 2), listOf(0, 2)))

    @Test fun textbookTrellisExpandsAndTerminatesWithTheSameEncoderTransitions() {
        val result = ConvolutionalTrellis.build(code, TrellisSpec(inputBits = "10111"))
        assertEquals(listOf(1, 2, 4, 4, 4, 4, 2, 1), result.layers.map { it.size })
        assertEquals(listOf(0, 2, 1, 3), (0..3).sortedBy { ConvolutionalTrellis.row(it, 2) })
        assertEquals("11100001100111", result.highlightedPath.joinToString("") { it.transition.output.joinToString("") })
        assertEquals("1011100", result.highlightedPath.joinToString("") { it.transition.input.toString() })
        assertEquals(setOf(0), result.layers.last())
        result.branches.forEach { branch ->
            assertEquals(ConvolutionalCode.step(code, branch.transition.from, branch.transition.input), branch.transition)
            if (branch.time >= 5) assertEquals(0, branch.transition.input)
        }
    }

    @Test fun viterbiMatchesExhaustiveHammingDistancesForAllReceivedSequencesOfAShortCode() {
        val spec = TrellisSpec(steps = 4, mode = "survivors")
        val words = (0..3).map { value ->
            val information = value.toString(2).padStart(2, '0')
            information to ConvolutionalCode.encode(code, (information + "00").map(Char::digitToInt))
        }
        for (value in 0..255) {
            val received = value.toString(2).padStart(8, '0')
            val distances = words.associate { (bits, word) -> bits to word.zip(received.toList()).count { (bit, character) -> bit != character.digitToInt() } }
            val result = ConvolutionalTrellis.build(code, spec.copy(receivedBits = received))
            assertEquals(distances.values.min(), result.bestDistance)
            assertEquals(result.bestDistance, distances[result.decodedBits])
            assertEquals(4, result.highlightedPath.size)
            assertEquals(result.bestDistance, result.highlightedPath.sumOf { it.distance!! })
            assertTrue(result.highlightedPath.all { it in result.survivors })
        }
    }

    @Test fun openTrellisesAllowEveryReachableTerminalStateAndUseDeterministicTies() {
        val result = ConvolutionalTrellis.build(code, TrellisSpec(steps = 3, terminated = false,
            initialState = 3, mode = "survivors", receivedBits = "000000"))
        assertEquals(setOf(3), result.layers.first())
        assertEquals(setOf(0, 1, 2, 3), result.layers.last())
        assertEquals(result, ConvolutionalTrellis.build(code, TrellisSpec(steps = 3, terminated = false,
            initialState = 3, mode = "survivors", receivedBits = "000000")))
        assertEquals(3, result.decodedBits!!.length)
    }

    @Test fun parserRoundTripsTheParameterOnlyTemplateAndRejectsIncompleteDistanceProblems() {
        val parsed = DiagramParser.parseOne(Json.parseToJsonElement("""{"profile":"convolutional_trellis",
            "coding":{"memory":2,"generators":[[0,1,2],[0,2]]},"trellis":{"steps":7,"inputBits":"10111"}}""").jsonObject)
        assertEquals(code, parsed.coding)
        assertEquals("10111", parsed.trellis!!.inputBits)
        assertTrue(parsed.nodes.isEmpty() && parsed.edges.isEmpty())
        assertEquals(parsed, DiagramParser.parseOne(Json.parseToJsonElement(Json.encodeToString(DiagramSpec.serializer(), parsed)).jsonObject))
        val invalid = listOf(TrellisSpec(steps = 0), TrellisSpec(steps = 17), TrellisSpec(steps = 1),
            TrellisSpec(mode = "distances"), TrellisSpec(receivedBits = "011"), TrellisSpec(inputBits = "12"),
            TrellisSpec(inputBits = "1011"), TrellisSpec(initialState = 4), TrellisSpec(mode = "unknown"))
        invalid.forEach { options -> assertThrows(options.toString(), IllegalArgumentException::class.java) { options.validate(code) } }
        assertThrows(IllegalArgumentException::class.java) {
            TrellisSpec().validate(CodingSpec(memory = 4, generators = listOf(listOf(0, 4))))
        }
    }

    @Test fun branchCaptionsAreBoundedAndAvoidNodesAndOtherCaptionsInTheReferenceTrellis() {
        for (mode in listOf("outputs", "distances")) {
            val options = TrellisSpec(mode = mode, receivedBits = "01011001001011")
            val result = ConvolutionalTrellis.build(code, options)
            val layout = TrellisLayout.layout(code, options, result)
            val labels = layout.edges.mapNotNull { it.label }
            labels.forEach { label ->
                assertTrue(label.center.x > 0 && label.center.x < layout.width)
                assertTrue(label.center.y > 100 && label.center.y < layout.bottom)
                assertTrue(layout.nodes.values.none { point ->
                    kotlin.math.abs(point.x - label.center.x) < label.width / 2 + 7 &&
                        kotlin.math.abs(point.y - label.center.y) < label.height / 2 + 7
                })
            }
            labels.forEachIndexed { index, label -> assertTrue("$mode overlapping caption $index", labels.drop(index + 1).none(label::overlaps)) }
        }
    }

    @Test fun transparentCaptionsStayBesideTheirBranchesAndClearAllWiresAcrossSupportedSizes() {
        for (memory in 1..3) for (outputs in 1..4) for (mode in listOf("outputs", "distances", "survivors")) {
            val coding = CodingSpec(memory = memory, generators = List(outputs) { listOf(0, memory) })
            val options = TrellisSpec(steps = 9, mode = mode,
                receivedBits = (0 until 9 * outputs).joinToString("") { if (it % 3 == 0) "1" else "0" })
            val layout = TrellisLayout.layout(coding, options, ConvolutionalTrellis.build(coding, options))
            val labels = layout.edges.mapNotNull { it.label } + layout.nodeLabels.values
            val context = "memory=$memory outputs=$outputs mode=$mode"
            labels.forEachIndexed { index, caption ->
                assertTrue("$context caption $index crosses a wire", layout.edges.none { TrellisLayout.intersects(it.from, it.to, caption) })
                assertTrue("$context caption $index overlaps another caption", labels.drop(index + 1).none(caption::overlaps))
                assertTrue("$context caption $index overlaps a node", layout.nodes.values.none { node ->
                    kotlin.math.abs(node.x - caption.center.x) < caption.width / 2 + 7 &&
                        kotlin.math.abs(node.y - caption.center.y) < caption.height / 2 + 7
                })
            }
            layout.edges.forEach { edge -> edge.label?.let { caption ->
                val dx = edge.to.x - edge.from.x; val dy = edge.to.y - edge.from.y
                val length = kotlin.math.hypot(dx, dy)
                val distance = kotlin.math.abs((caption.center.x - edge.from.x) * dy -
                    (caption.center.y - edge.from.y) * dx) / length
                val halfExtent = kotlin.math.abs(dy / length) * caption.width / 2 +
                    kotlin.math.abs(dx / length) * caption.height / 2
                assertTrue("$context caption too far from its branch", distance - halfExtent in 3.9f..18.1f)
            } }
        }
    }
}
