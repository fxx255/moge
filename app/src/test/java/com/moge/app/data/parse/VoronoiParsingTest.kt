package com.moge.app.data.parse

import org.junit.Assert.*
import org.junit.Test

class VoronoiParsingTest {
    @Test fun `single multiple and all selection parse without dropping competitors`() {
        val sites = """[{"x":-1,"y":-1},{"x":1,"y":-1},{"x":1,"y":1},{"x":-1,"y":1}]"""
        for ((selection, indices) in listOf("\"highlight\":[2]" to listOf(2), "\"highlight\":[0,2,0]" to listOf(0,2),
            "\"allRegions\":true" to listOf(0,1,2,3), "\"highlight\":\"all\"" to listOf(0,1,2,3))) {
            val plot = ReplyParser.parse("""{"reply":"图","plots":[{"voronoi":{"sites":$sites,$selection}}]}""").plots.single()
            assertEquals(4, plot.voronoi!!.sites.size)
            assertEquals(indices, plot.voronoi.selectedIndices)
        }
    }

    @Test fun `stored all regions survives serialization while old selections remain compatible`() {
        val old = """{"sites":[{"x":-1,"y":0},{"x":1,"y":0}],"highlight":[0]}"""
        val json = kotlinx.serialization.json.Json
        val original = json.decodeFromString<com.moge.app.domain.plot.VoronoiSpec>(old)
        assertFalse(original.allRegions)
        assertEquals(listOf(0), original.selectedIndices)
        val all = original.copy(allRegions = true)
        val restored = json.decodeFromString<com.moge.app.domain.plot.VoronoiSpec>(json.encodeToString(com.moge.app.domain.plot.VoronoiSpec.serializer(), all))
        assertEquals(all, restored)
    }
    @Test fun `oversized constellation is rejected rather than silently omitting competitors`() {
        val sites = (0..64).joinToString(",") { """{"x":$it,"y":0}""" }
        val reply = ReplyParser.parse("""{"reply":"正文","plots":[{"voronoi":{"sites":[$sites],"highlight":[0]}}]}""")
        assertTrue(reply.plots.isEmpty())
        assertTrue(reply.warnings.any { it.contains("64") })
        assertEquals("正文", reply.reply)
    }

    @Test fun `voronoi can be parsed without unrelated series and uses equal scale`() {
        val plot = ReplyParser.parse("""{"reply":"图", "plots":[{"voronoi":{"sites":[{"x":-1,"y":0,"label":"s₁"},{"x":1,"y":0,"label":"s₂"}],"highlight":[0]}}]}""").plots.single()
        assertTrue(plot.equalAspect)
        assertEquals("s₁", plot.voronoi!!.sites.first().label)
        assertEquals(listOf(0), plot.voronoi.highlight)
        assertTrue(plot.series.isEmpty())
    }

    @Test fun `invalid sites and targets produce a figure warning instead of a fabricated region`() {
        for (geometry in listOf(
            """{"sites":[{"x":1,"y":0},{"x":1,"y":0}],"highlight":[0]}""",
            """{"sites":[{"x":1},{"x":2,"y":0}],"highlight":[0]}""",
            """{"sites":[{"x":1,"y":0},{"x":2,"y":0}],"highlight":[2]}""")) {
            val reply = ReplyParser.parse("""{"reply":"正文","plots":[{"voronoi":$geometry}]}""")
            assertTrue(reply.plots.isEmpty())
            assertEquals("正文", reply.reply)
            assertTrue(reply.warnings.isNotEmpty())
        }
    }
}
