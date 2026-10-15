package com.moge.app.domain.diagram

import android.app.Application
import android.graphics.Bitmap
import com.moge.app.core.LatexEngine
import com.moge.app.ui.figure.DiagramRenderer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IqCarrierBranchTest {
    @Before fun prepare() { LatexEngine.init(RuntimeEnvironment.getApplication()) }

    private fun sample(): DiagramSpec {
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream(
            "diagrams/communication/shared-carrier-qam.json")).bufferedReader().use { it.readText() }
        return DiagramParser.parseOne(Json.parseToJsonElement(raw).jsonObject)
    }

    private fun checkLayout(spec: DiagramSpec): DiagramLayoutResult {
        val layout = DiagramLayout.layout(spec)
        val nodes = layout.nodes.associateBy { it.node.id }
        val upper = nodes.getValue("mi")
        val lower = nodes.getValue("mq")
        val summer = nodes.getValue("sum")
        for (id in listOf("carrier", "phase")) {
            val block = nodes.getValue(id)
            assertTrue("$id must be inside the I/Q rails", block.y > upper.y + upper.height && block.y + block.height < lower.y)
            assertTrue("$id must be inside the signal envelope", block.x > nodes.getValue("sp").x && block.x + block.width < summer.x)
        }
        val shiftedUpper = spec.edges.single { it.from == "phase" }.to == "mi"
        val carrier = nodes.getValue("carrier")
        val phase = nodes.getValue("phase")
        if (shiftedUpper) assertTrue(phase.y + phase.height < carrier.y)
        else assertTrue(carrier.y + carrier.height < phase.y)
        assertEquals(upper.centerX, nodes.getValue("phase").centerX, .01f)
        assertEquals(upper.centerX, lower.centerX, .01f)
        val phaseFeed = layout.edges.single { it.edge.from == "phase" }
        assertEquals("Mixer receives its carrier from inside the rails",
            if (shiftedUpper) DiagramPort.BOTTOM else DiagramPort.TOP, phaseFeed.endPort)
        assertEquals(2, phaseFeed.points.size)
        assertEquals(spec.nodes.size, layout.nodes.size)
        assertEquals(spec.edges.size, layout.edges.size)
        layout.nodes.forEachIndexed { index, a ->
            assertTrue(a.x >= 0 && a.y >= 0 && a.x + a.width <= layout.width && a.y + a.height <= layout.height)
            layout.nodes.drop(index + 1).forEach { b ->
                assertFalse("Overlapping ${a.node.id}/${b.node.id}", a.x < b.x + b.width && a.x + a.width > b.x &&
                    a.y < b.y + b.height && a.y + a.height > b.y)
            }
        }
        layout.edges.forEach { route ->
            route.points.zipWithNext().forEach { (a, b) ->
                assertTrue("Orthogonal wiring", a.x == b.x || a.y == b.y)
                layout.nodes.filter { it.node.id !in setOf(route.edge.from, route.edge.to) }.forEach { box ->
                    assertFalse("${route.edge.from}->${route.edge.to} crosses ${box.node.id}",
                        if (a.x == b.x) a.x > box.x && a.x < box.x + box.width &&
                            maxOf(a.y, b.y) > box.y && minOf(a.y, b.y) < box.y + box.height
                        else a.y > box.y && a.y < box.y + box.height &&
                            maxOf(a.x, b.x) > box.x && minOf(a.x, b.x) < box.x + box.width)
                }
            }
        }
        // A shared carrier fork is intentional. Unrelated signal/control wires
        // must not cross or overlap even when the old spec asked for bottom ports.
        layout.edges.forEachIndexed { index, a -> layout.edges.drop(index + 1).forEach { b ->
            if (setOf(a.edge.from, a.edge.to).intersect(setOf(b.edge.from, b.edge.to)).isEmpty()) {
                a.points.zipWithNext().forEach { (p, q) -> b.points.zipWithNext().forEach { (r, s) ->
                    assertFalse("Unrelated wires cross: ${a.edge.from}->${a.edge.to}/${b.edge.from}->${b.edge.to}",
                        segmentsTouch(p, q, r, s))
                } }
            }
        } }
        return layout
    }

    private fun segmentsTouch(a: DiagramPoint, b: DiagramPoint, c: DiagramPoint, d: DiagramPoint): Boolean {
        fun inside(v: Float, x: Float, y: Float) = v >= minOf(x, y) && v <= maxOf(x, y)
        if (a.x == b.x && c.x == d.x) return a.x == c.x && maxOf(minOf(a.y,b.y), minOf(c.y,d.y)) <= minOf(maxOf(a.y,b.y), maxOf(c.y,d.y))
        if (a.y == b.y && c.y == d.y) return a.y == c.y && maxOf(minOf(a.x,b.x), minOf(c.x,d.x)) <= minOf(maxOf(a.x,b.x), maxOf(c.x,d.x))
        return if (a.x == b.x) inside(a.x, c.x, d.x) && inside(c.y, a.y, b.y)
            else inside(c.x, a.x, b.x) && inside(a.y, c.y, d.y)
    }

    @Test fun `shared carrier and phase stay inside the modulator without crossings`() { checkLayout(sample()) }

    @Test fun `carrier on the lower rail mirrors the central phase branch`() {
        checkLayout(sample().let { spec -> spec.copy(edges = spec.edges.map { edge -> when {
            edge.from == "carrier" && edge.to == "mi" -> edge.copy(to = "mq")
            edge.from == "phase" -> edge.copy(to = "mi")
            else -> edge
        } }) })
    }

    @Test fun `node and edge order plus stale coordinates cannot move carrier outside`() {
        val original = sample()
        val expected = checkLayout(original).nodes.associate { it.node.id to listOf(it.x, it.y, it.width, it.height) }
        val random = Random(128)
        repeat(4) {
            val shuffled = original.copy(nodes = original.nodes.shuffled(random).map { node ->
                if (node.id in setOf("carrier", "phase")) node.copy(row = 9, column = 12) else node
            }, edges = original.edges.shuffled(random))
            assertEquals(expected, checkLayout(shuffled).nodes.associate { it.node.id to listOf(it.x, it.y, it.width, it.height) })
        }
    }

    @Test fun `long carrier and phase captions reserve their full central area`() {
        checkLayout(sample().copy(nodes = sample().nodes.map { node -> when (node.id) {
            "carrier" -> node.copy(label = "共享正交载波发生器", subLabel = "\$\\cos(2\\pi f_ct)\$")
            "phase" -> node.copy(label = "正交移相网络 −90°", subLabel = "产生 Q 路载波")
            else -> node
        } }))
    }

    @Test fun `native light and chalk diagrams have bounded visible shared carrier blocks`() {
        val spec = sample()
        for (dark in listOf(false, true)) {
            val image = DiagramRenderer.render(spec, dark = dark)
            try {
                assertTrue(image.width.toLong() * image.height <= 8_000_000)
                val background = image.getPixel(0, 0)
                for (x in 0 until image.width) {
                    assertEquals(background, image.getPixel(x, 0))
                    assertEquals(background, image.getPixel(x, image.height - 1))
                }
                if (System.getenv("FIGURE_LAYOUT_EXPORT_SAMPLES") == "1") {
                    val folder = File("build/figure-layout-samples").apply { mkdirs() }
                    File(folder, if (dark) "shared-carrier-chalk.png" else "shared-carrier-paper.png")
                        .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } finally { image.recycle() }
        }
    }
}
