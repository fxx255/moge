package com.moge.app.ui.figure

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.moge.app.core.LatexEngine
import com.moge.app.domain.coding.CodingSpec
import com.moge.app.domain.diagram.DiagramLayoutProfile
import com.moge.app.domain.diagram.DiagramSpec
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagramLabelTextTest {
    @Before fun prepare() { LatexEngine.init(RuntimeEnvironment.getApplication()) }

    @Test fun `coding title measures numeric tuple as math and preserves adjacent Chinese`() {
        val label = DiagramLabelText("\$(2,1,3)\$ 卷积码编码器", 28f, Color.BLACK)
        val runs = label.block.lines.flatMap { it.runs }
        assertEquals(1, runs.count { it.formula != null })
        assertEquals("\$(2,1,3)\$", runs.single { it.formula != null }.text)
        assertEquals(" 卷积码编码器", runs.filter { it.formula == null }.joinToString("") { it.text })
        assertTrue(label.width > runs.single { it.formula != null }.width)
    }

    @Test fun `formula-only labels produce themed pixels within their measured bounds`() {
        for ((paper, ink) in listOf(Color.WHITE to Color.BLACK, DiagramRenderer.DARK_BACKGROUND to Color.WHITE)) {
            val label = DiagramLabelText("\$(2,1,3)\$", 36f, ink)
            val image = Bitmap.createBitmap(420, 100, Bitmap.Config.ARGB_8888)
            try {
                image.eraseColor(paper)
                label.draw(Canvas(image), 210f, 50f, centered = true)
                val xs = mutableListOf<Int>()
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    if (image.getPixel(x, y) != paper) xs += x
                }
                assertTrue("Formula must draw on both paper and chalk backgrounds", xs.size > 100)
                assertTrue(xs.min() >= 210f - label.width / 2f - 2f)
                assertTrue(xs.max() <= 210f + label.width / 2f + 2f)
                assertTrue(xs.any { x -> (0 until image.height).any { y -> image.getPixel(x, y) == ink } })
            } finally { image.recycle() }
        }
    }

    @Test fun `convolutional encoder title consumes dollar delimiters in both themes`() {
        val spec = DiagramSpec("\$(2,1,3)\$ 卷积码编码器",
            profile = DiagramLayoutProfile.CONVOLUTIONAL_ENCODER,
            coding = CodingSpec(memory = 2, generators = listOf(listOf(0, 1, 2), listOf(0, 2))))
        // Exercise the complete coding template in both themes and retain native
        // images for visual inspection of the title and surrounding diagram.
        for (dark in listOf(false, true)) {
            val image = DiagramRenderer.render(spec, dark = dark)
            try {
                val background = image.getPixel(0, 0)
                assertTrue((0 until image.width).any { x -> (50 until 120).any { y -> image.getPixel(x, y) != background } })
                System.getenv("CODING_EXPORT_SAMPLES")?.takeIf { it == "1" }?.let {
                    val output = File("build/coding-samples").apply { mkdirs() }
                    File(output, if (dark) "numeric-title-chalk.png" else "numeric-title-paper.png")
                        .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } finally { image.recycle() }
        }
    }
}
