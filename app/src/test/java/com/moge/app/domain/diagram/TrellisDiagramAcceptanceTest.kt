package com.moge.app.domain.diagram

import android.app.Application
import android.graphics.Bitmap
import com.moge.app.core.LatexEngine
import com.moge.app.domain.coding.CodingSpec
import com.moge.app.domain.coding.TrellisSpec
import com.moge.app.ui.figure.DiagramRenderer
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrellisDiagramAcceptanceTest {
    @Before fun prepareMath() { LatexEngine.init(RuntimeEnvironment.getApplication()) }
    @Test fun referenceModesAndEightStateBoundaryRenderInBothThemes() {
        val code = CodingSpec(memory = 2, generators = listOf(listOf(0, 1, 2), listOf(0, 2)))
        val examples = listOf(
            Triple("01-grid", code, TrellisSpec(inputBits = "10111")),
            Triple("02-distances", code, TrellisSpec(mode = "distances", receivedBits = "01011001001011")),
            Triple("03-survivors", code, TrellisSpec(mode = "survivors", receivedBits = "01011001001011")),
            Triple("04-eight-states", CodingSpec(memory = 3, generators = listOf(listOf(0, 1, 3), listOf(0, 2, 3))),
                TrellisSpec(steps = 9)),
            Triple("05-open", code, TrellisSpec(steps = 5, terminated = false, initialState = 3, inputBits = "10101")),
            Triple("06-short-termination", CodingSpec(memory = 1, generators = listOf(listOf(0, 1), listOf(0))),
                TrellisSpec(steps = 1, mode = "survivors", receivedBits = "00")),
            Triple("07-eight-state-distances", CodingSpec(memory = 3, generators = List(4) { listOf(0, 3) }),
                TrellisSpec(steps = 9, mode = "distances", receivedBits = "1010".repeat(9))),
            Triple("08-eight-state-survivors", CodingSpec(memory = 3, generators = List(4) { listOf(0, 3) }),
                TrellisSpec(steps = 9, mode = "survivors", receivedBits = "1010".repeat(9))),
        )
        for ((name, coding, options) in examples) for (dark in listOf(false, true)) {
            val image = DiagramRenderer.render(DiagramSpec("", profile = DiagramLayoutProfile.CONVOLUTIONAL_TRELLIS,
                coding = coding, trellis = options), dark = dark)
            try {
                assertTrue(image.width > 300 && image.height > 200)
                assertTrue(image.width <= 4096 && image.height <= 4096)
                assertTrue(image.width.toLong() * image.height <= 8_000_000)
                val background = image.getPixel(0, 0)
                for (x in 0 until image.width) {
                    assertEquals(background, image.getPixel(x, 0))
                    assertEquals(background, image.getPixel(x, image.height - 1))
                }
                for (y in 0 until image.height) {
                    assertEquals(background, image.getPixel(0, y))
                    assertEquals(background, image.getPixel(image.width - 1, y))
                }
                if (System.getenv("TRELLIS_EXPORT_SAMPLES") == "1") {
                    val file = File("build/trellis-samples/$name${if (dark) "-chalk" else ""}.png")
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } finally { image.recycle() }
        }
    }
}
