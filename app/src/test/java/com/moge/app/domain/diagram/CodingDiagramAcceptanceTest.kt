package com.moge.app.domain.diagram

import android.app.Application
import android.graphics.Bitmap
import com.moge.app.domain.coding.CodingSpec
import com.moge.app.ui.figure.DiagramRenderer
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodingDiagramAcceptanceTest {
    @Test fun parameterTemplatesRenderBothThemesAtBoundedResolution() {
        val k3 = CodingSpec(memory = 2, generators = listOf(listOf(0, 1, 2), listOf(0, 2)))
        val k4 = CodingSpec(memory = 3, generators = listOf(listOf(0, 2, 3), listOf(0, 1, 2, 3)))
        val samples = listOf(
            "01-convolutional-K3" to DiagramSpec("K=3 卷积码编码器", profile = DiagramLayoutProfile.CONVOLUTIONAL_ENCODER, coding = k3),
            "02-convolutional-K4" to DiagramSpec("K=4 卷积码编码器", profile = DiagramLayoutProfile.CONVOLUTIONAL_ENCODER, coding = k4),
            "03-cyclic-7-4" to DiagramSpec("(7,4) 系统循环码编码器", profile = DiagramLayoutProfile.CYCLIC_ENCODER,
                coding = CodingSpec(n = 7, k = 4, generatorExponents = listOf(0, 1, 3))),
            "04-four-state" to DiagramSpec("四状态转移图", profile = DiagramLayoutProfile.CONVOLUTIONAL_STATE_GRAPH, coding = k3),
            "05-eight-state" to DiagramSpec("八状态转移图", profile = DiagramLayoutProfile.CONVOLUTIONAL_STATE_GRAPH, coding = k4),
            "06-parallel" to DiagramSpec("四路并行输出", profile = DiagramLayoutProfile.CONVOLUTIONAL_ENCODER,
                coding = CodingSpec(memory = 8, generators = listOf((0..8).toList(), listOf(0, 8), listOf(1, 4, 8), listOf(2, 5, 7)), outputMode = "parallel")),
            "07-two-state" to DiagramSpec("二状态转移图", profile = DiagramLayoutProfile.CONVOLUTIONAL_STATE_GRAPH,
                coding = CodingSpec(memory = 1, generators = listOf(listOf(0, 1), listOf(1)))),
            "08-cyclic-eight-registers" to DiagramSpec("八级系统循环码编码器", profile = DiagramLayoutProfile.CYCLIC_ENCODER,
                coding = CodingSpec(n = 15, k = 7, generatorExponents = listOf(0, 1, 3, 4, 5, 7, 8))),
            "09-cyclic-one-register" to DiagramSpec("一级系统循环码编码器", profile = DiagramLayoutProfile.CYCLIC_ENCODER,
                coding = CodingSpec(n = 3, k = 2, generatorExponents = listOf(0, 1))),
            "10-single-tap" to DiagramSpec("单抽头卷积码编码器", profile = DiagramLayoutProfile.CONVOLUTIONAL_ENCODER,
                coding = CodingSpec(memory = 1, generators = listOf(listOf(1)))),
            "11-eight-state-four-outputs" to DiagramSpec("八状态四路输出", profile = DiagramLayoutProfile.CONVOLUTIONAL_STATE_GRAPH,
                coding = CodingSpec(memory = 3, generators = listOf(listOf(0, 2, 3), listOf(0, 1, 2, 3), listOf(0, 3), listOf(1, 2)))),
            "12-three-output-serial" to DiagramSpec("三路串行输出", profile = DiagramLayoutProfile.CONVOLUTIONAL_ENCODER,
                coding = CodingSpec(memory = 3, generators = listOf(listOf(0, 2, 3), listOf(0, 1, 2, 3), listOf(0, 3)))),
        )
        val export = System.getenv("CODING_EXPORT_SAMPLES") == "1"
        val dir = File("build/coding-samples").apply { if (export) mkdirs() }
        samples.forEach { (name, spec) ->
            val json = Json.encodeToString(DiagramSpec.serializer(), spec)
            val parsed = DiagramParser.parseOne(Json.parseToJsonElement(json).jsonObject)
            for (dark in listOf(false, true)) {
                val bitmap = DiagramRenderer.render(parsed, dark = dark)
                try {
                    assertTrue(bitmap.width > 300 && bitmap.height > 200)
                    assertTrue(name, bitmap.width.toLong() * bitmap.height <= 8_000_000)
                    val background = bitmap.getPixel(0, 0)
                    // Catch clipped loops, long equations, and maximum-register bypass wires.
                    for (x in 0 until bitmap.width) {
                        assertEquals("$name top", background, bitmap.getPixel(x, 0))
                        assertEquals("$name bottom", background, bitmap.getPixel(x, bitmap.height - 1))
                    }
                    for (y in 0 until bitmap.height) {
                        assertEquals("$name left", background, bitmap.getPixel(0, y))
                        assertEquals("$name right", background, bitmap.getPixel(bitmap.width - 1, y))
                    }
                    var ink = 0
                    for (y in 0 until bitmap.height step 5) for (x in 0 until bitmap.width step 5)
                        if (bitmap.getPixel(x, y) != background) ink++
                    assertTrue(name, ink > 100)
                    if (export) File(dir, name + if (dark) "-chalk.png" else ".png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { bitmap.recycle() }
            }
            if (export) File(dir, "$name.json").writeText(json)
        }
    }
}
