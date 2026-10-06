package com.moge.app.domain.coding

import com.moge.app.domain.diagram.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class CodingSpecTest {
    private val convolutional = CodingSpec(memory = 2, generators = listOf(listOf(0, 1, 2), listOf(0, 2)))
    @Test fun textbookFourStateTransitionsAndSerialOutput() {
        val expected = listOf("0:0:0:00(0)", "0:1:2:11(1)", "1:0:0:11(0)", "1:1:2:00(1)",
            "2:0:1:10(0)", "2:1:3:01(1)", "3:0:1:01(0)", "3:1:3:10(1)")
        assertEquals(expected, ConvolutionalCode.transitions(convolutional).map {
            listOf(it.from, it.input, it.to, it.label).joinToString(":")
        })
        assertEquals("111000", ConvolutionalCode.encode(convolutional, listOf(1, 0, 1)).joinToString(""))
        val higher = CodingSpec(memory = 3, generators = listOf(listOf(0, 2, 3), listOf(0, 1, 2, 3)))
        assertEquals(16, ConvolutionalCode.transitions(higher).size)
        assertEquals(listOf(0, 1), ConvolutionalCode.step(higher, 5, 1).output)
    }

    /** Independent long division, compared with the feedback-register implementation. */
    private fun divide(value: Long, divisor: Long): Long {
        var rest = value
        val degree = 63 - java.lang.Long.numberOfLeadingZeros(divisor)
        while (rest != 0L) {
            val top = 63 - java.lang.Long.numberOfLeadingZeros(rest)
            if (top < degree) break
            rest = rest xor (divisor shl (top - degree))
        }
        return rest
    }

    @Test fun cyclicEncoderMatchesPolynomialDivisionAndProducesValidCodewords() {
        val short = CodingSpec(n = 7, k = 4, generatorExponents = listOf(0, 1, 3))
        assertEquals("1001110", CyclicCode.encode(short, listOf(1, 0, 0, 1)).joinToString(""))
        val cases = listOf(short, CodingSpec(n = 15, k = 11, generatorExponents = listOf(0, 1, 4)),
            CodingSpec(n = 63, k = 57, generatorExponents = listOf(0, 1, 6)))
        cases.forEach { spec ->
            val mask = (1L shl spec.k) - 1
            val values = if (spec.k <= 11) (0L..mask).toList()
                else listOf(0L, 1L, mask, mask / 3, 0x123456789ABCL)
            val divisor = spec.generatorExponents.fold(0L) { v, e -> v or (1L shl e) }
            values.forEach { value ->
                val message = (spec.k - 1 downTo 0).map { ((value shr it) and 1).toInt() }
                val code = CyclicCode.encode(spec, message)
                assertEquals(message, code.take(spec.k))
                val parity = code.drop(spec.k).fold(0L) { v, bit -> (v shl 1) or bit.toLong() }
                assertEquals(divide(value shl (spec.n - spec.k), divisor), parity)
                assertEquals(0L, divide(code.fold(0L) { v, bit -> (v shl 1) or bit.toLong() }, divisor))
            }
        }
    }

    @Test fun invalidOrUnspecifiedParametersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { convolutional.copy(generators = emptyList()).validateConvolutional() }
        assertThrows(IllegalArgumentException::class.java) { convolutional.copy(memory = 3).validateConvolutional() }
        assertThrows(IllegalArgumentException::class.java) { convolutional.copy(generators = listOf(listOf(0, 2, 2))).validateConvolutional() }
        assertThrows(IllegalArgumentException::class.java) {
            CodingSpec(memory = 4, generators = listOf(listOf(0, 4))).validateConvolutional(true)
        }
        assertThrows(IllegalArgumentException::class.java) { CodingSpec(n = 7, k = 3, generatorExponents = listOf(0, 1, 4)).validateCyclic() }
        assertThrows(IllegalArgumentException::class.java) { CodingSpec(n = 7, k = 4, generatorExponents = listOf(1, 3)).validateCyclic() }
    }

    @Test fun codingProfilesNeedNoNodesButInvalidSlotsKeepTheirNumber() {
        val array = Json.parseToJsonElement("""[
          {"profile":"convolutional_encoder","coding":{"memory":2,"generators":[[0,1,2],[0,2]]}},
          {"profile":"cyclic_encoder","coding":{"n":7,"k":4}},
          {"profile":"cyclic_encoder","coding":{"n":7,"k":4,"generatorExponents":[0,1,3]}}
        ]""") as kotlinx.serialization.json.JsonArray
        val warnings = mutableListOf<String>()
        val slots = DiagramParser.parseSlots(array, warnings)
        assertEquals(3, slots.size)
        assertEquals(convolutional, slots.first()!!.coding)
        assertNull(slots[1])
        assertEquals(DiagramLayoutProfile.CYCLIC_ENCODER, slots[2]!!.profile)
        assertTrue(warnings.single().contains("生成多项式"))
        val json = Json.encodeToString(DiagramSpec.serializer(), slots.first()!!)
        assertEquals(slots.first(), DiagramParser.parseOne(Json.parseToJsonElement(json).jsonObject))
        assertThrows(IllegalStateException::class.java) {
            DiagramParser.parseOne(Json.parseToJsonElement("""{"title":"旧图缺少节点"}""").jsonObject)
        }
    }
}
