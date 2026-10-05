package com.moge.app.domain.diagram

import android.app.Application
import android.graphics.Bitmap
import com.moge.app.ui.figure.DiagramRenderer
import java.io.File
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

/** Desktop textbook references, exercised through the public JSON/layout/render pipeline. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommunicationDiagramAcceptanceTest {
    private data class Sample(val slug: String, val spec: DiagramSpec, val json: String)
    private fun resource(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream(
        "diagrams/communication/$name")).bufferedReader().use { it.readText() }
    private fun samples() = Json.parseToJsonElement(resource("manifest.json")).jsonArray.map { item ->
        val slug = item.jsonObject.getValue("slug").jsonPrimitive.content
        val text = resource("$slug.json")
        Sample(slug, DiagramParser.parseOne(Json.parseToJsonElement(text).jsonObject), text)
    }

    @Before fun initializeMath() { JLatexMathAndroid.init(RuntimeEnvironment.getApplication()) }

    @Test fun `all reference families parse and have clear orthogonal geometry`() {
        val samples = samples()
        assertEquals(16, samples.size)
        samples.forEach { sample ->
            val layout = DiagramLayout.layout(sample.spec)
            assertEquals(sample.slug, sample.spec.nodes.size, layout.nodes.size)
            assertEquals(sample.slug, sample.spec.edges.size, layout.edges.size)
            layout.nodes.forEachIndexed { index, box ->
                assertTrue("${sample.slug} clipped node ${box.node.id}", box.x >= 0 && box.y >= 0 &&
                    box.x + box.width <= layout.width && box.y + box.height <= layout.height)
                layout.nodes.drop(index + 1).forEach { other ->
                    assertFalse("${sample.slug} overlapping ${box.node.id}/${other.node.id}",
                        box.x < other.x + other.width && box.x + box.width > other.x &&
                            box.y < other.y + other.height && box.y + box.height > other.y)
                }
            }
            layout.edges.forEach { route ->
                assertEquals(route.start, route.points.first())
                assertEquals(route.end, route.points.last())
                route.points.forEach { p -> assertTrue("${sample.slug} clipped wire", p.x in 0f..layout.width && p.y in 0f..layout.height) }
                route.points.zipWithNext().forEach { (a, b) ->
                    assertTrue("${sample.slug} diagonal wire", a.x == b.x || a.y == b.y)
                    layout.nodes.filter { it.node.id != route.edge.from && it.node.id != route.edge.to }.forEach { box ->
                        val hits = if (a.x == b.x) a.x > box.x && a.x < box.x + box.width &&
                            maxOf(a.y, b.y) > box.y && minOf(a.y, b.y) < box.y + box.height
                        else a.y > box.y && a.y < box.y + box.height &&
                            maxOf(a.x, b.x) > box.x && minOf(a.x, b.x) < box.x + box.width
                        assertFalse("${sample.slug} ${route.edge.from}->${route.edge.to} crosses ${box.node.id}", hits)
                    }
                }
            }
        }
    }

    @Test fun `JSON array order cannot change stage or lane assignment`() {
        samples().forEach { sample ->
            val baseline = DiagramLayout.layout(sample.spec)
            val random = Random(471)
            repeat(4) {
                val reordered = DiagramLayout.layout(sample.spec.copy(nodes = sample.spec.nodes.shuffled(random),
                    edges = sample.spec.edges.shuffled(random)))
                assertEquals(sample.slug, baseline.nodes.associate { it.node.id to listOf(it.x, it.y, it.width, it.height) },
                    reordered.nodes.associate { it.node.id to listOf(it.x, it.y, it.width, it.height) })
                fun edgeKey(edge: DiagramEdge) = "${edge.from}:${edge.to}:${edge.channel}"
                assertEquals(sample.slug, baseline.edges.associate { edgeKey(it.edge) to it.points },
                    reordered.edges.associate { edgeKey(it.edge) to it.points })
            }
        }
    }

    @Test fun `FFT and IFFT bundles have three distinct level ports`() {
        samples().filter { it.slug.startsWith("11-") || it.slug.startsWith("12-") }.forEach { sample ->
            val layout = DiagramLayout.layout(sample.spec)
            for (from in listOf("sp", "fft")) {
                val bundle = layout.edges.filter { it.edge.from == from }
                assertEquals(3, bundle.map { it.start.y }.distinct().size)
                assertTrue(bundle.all { it.points.size == 2 && it.start.y == it.end.y })
            }
            val tall = layout.nodes.filter { it.node.id in setOf("sp", "fft", "ps") }
            assertEquals(1, tall.map { it.y }.distinct().size)
            assertEquals(1, tall.map { it.height }.distinct().size)
        }
    }

    @Test fun `three lane banks and two lane outputs share the same center`() {
        samples().filter { it.slug.startsWith("07-") || it.slug.startsWith("13-") }.forEach { sample ->
            val boxes = DiagramLayout.layout(sample.spec).nodes.associateBy { it.node.id }
            val input = boxes.getValue(if (sample.slug.startsWith("07-")) "in" else "sp")
            val output = boxes.getValue("out")
            assertEquals(sample.slug, input.centerY, output.centerY, .1f)
            val upper = boxes.getValue(if (sample.slug.startsWith("07-")) "mix0" else "iq0")
            val lower = boxes.getValue(if (sample.slug.startsWith("07-")) "mix1" else "iq1")
            assertEquals(sample.slug, (upper.centerY + lower.centerY) / 2f, output.centerY, .1f)
        }
    }

    @Test fun `multiport blocks span their bundle without a special role`() {
        val sample = samples().first { it.slug.startsWith("07-") }
        val explicit = DiagramLayout.layout(sample.spec)
        val inferred = DiagramLayout.layout(sample.spec.copy(nodes = sample.spec.nodes.map {
            if (it.id == "map") it.copy(role = "mapper") else it
        }))
        assertEquals(explicit.nodes.map { listOf(it.x, it.y, it.width, it.height) },
            inferred.nodes.map { listOf(it.x, it.y, it.width, it.height) })
        assertEquals(explicit.edges, inferred.edges)
    }

    @Test fun `shared sources and multiple feedbacks are independent of edge ordering`() {
        val spec = DiagramSpec("反馈", listOf(
            DiagramNode("a", "输入", DiagramNodeShape.IO),
            DiagramNode("b", "滤波器 1", DiagramNodeShape.BLOCK),
            DiagramNode("c", "滤波器 2", DiagramNodeShape.BLOCK),
            DiagramNode("d", "输出", DiagramNodeShape.IO),
            DiagramNode("carrier", "本振", DiagramNodeShape.IO, role = "carrier"),
        ), listOf(
            DiagramEdge("a", "b"), DiagramEdge("b", "c"), DiagramEdge("c", "d"),
            DiagramEdge("carrier", "b", toPort = DiagramPort.BOTTOM),
            DiagramEdge("carrier", "c", toPort = DiagramPort.BOTTOM),
            DiagramEdge("d", "a", dashed = true), DiagramEdge("c", "b", dashed = true),
        ), profile = DiagramLayoutProfile.COMMUNICATION)
        val first = DiagramLayout.layout(spec)
        val reversed = DiagramLayout.layout(spec.copy(nodes = spec.nodes.reversed(), edges = spec.edges.reversed()))
        assertEquals(first.nodes.associate { it.node.id to listOf(it.x, it.y) },
            reversed.nodes.associate { it.node.id to listOf(it.x, it.y) })
        assertEquals(first.edges.associate { it.edge to it.points }, reversed.edges.associate { it.edge to it.points })
    }

    @Test fun `sampling symbols and formulas render in light and chalk themes`() {
        val export = System.getenv("DIAGRAM_EXPORT_SAMPLES") == "1"
        val dir = File("build/diagram-samples").apply { if (export) mkdirs() }
        samples().forEach { sample ->
            (sample.spec.nodes.flatMap { listOf(it.label, it.subLabel.orEmpty()) } + sample.spec.edges.map { it.label.orEmpty() })
                .forEach { text -> Regex("\\$([^$]+)\\$").findAll(text).forEach { formula ->
                    assertTrue("${sample.slug} invalid math ${formula.value}",
                        JLatexMathDrawable.builder(formula.groupValues[1]).textSize(25f).build().intrinsicWidth > 0)
                } }
            for (dark in listOf(false, true)) {
                val bitmap = DiagramRenderer.render(sample.spec, dark = dark)
                try {
                    assertTrue(bitmap.width > 200 && bitmap.height > 100)
                    val background = bitmap.getPixel(0, 0)
                    var ink = 0
                    for (y in 0 until bitmap.height step 7) for (x in 0 until bitmap.width step 7)
                        if (bitmap.getPixel(x, y) != background) ink++
                    assertTrue("${sample.slug} empty rendered bitmap", ink > 100)
                    if (export) File(dir, "${sample.slug}${if (dark) "-chalk" else ""}.png")
                        .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
            if (export) File(dir, "${sample.slug}.json").writeText(sample.json)
        }
        if (export) File(dir, "manifest.json").writeText(resource("manifest.json"))
    }

    @Test fun `new vocabulary is optional and invalid channel is rejected`() {
        val sample = samples().first { it.slug.startsWith("11-") }
        val automatic = sample.spec.copy(profile = DiagramLayoutProfile.GENERIC)
        assertEquals(DiagramLayout.layout(sample.spec), DiagramLayout.layout(automatic))
        assertThrows(IllegalArgumentException::class.java) {
            DiagramParser.parseOne(Json.parseToJsonElement(sample.json.replace("\"channel\": 0", "\"channel\": 4")).jsonObject)
        }
    }
}
