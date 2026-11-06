package com.moge.app.domain.diagram

import android.app.Application
import android.graphics.Bitmap
import com.moge.app.core.LatexEngine
import com.moge.app.ui.figure.DiagramRenderer
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LfsrDiagramAcceptanceTest {
    @Before fun prepareMath() { LatexEngine.init(RuntimeEnvironment.getApplication()) }

    @Test fun `textbook examples and dense taps render legibly in both themes without clipping`() {
        val formula = DiagramText.layout("\$a_0\$", DiagramTextRole.LABEL, 160f)
        assertTrue("Output subscripts must be drawn as math", formula.lines.flatMap { it.runs }.single().formula != null)
        val examples = listOf(
            "01-taps-2-3" to DiagramSpec("三阶 LFSR · 第 2、3 级输出反馈", profile = DiagramLayoutProfile.LFSR,
                lfsr = LfsrSpec(stages = 3, taps = listOf(2, 3), clockLabel = "")),
            "02-taps-1-3" to DiagramSpec("三阶 LFSR · 第 1、3 级输出反馈", profile = DiagramLayoutProfile.LFSR,
                lfsr = LfsrSpec(stages = 3, taps = listOf(1, 3), clockLabel = "")),
            "03-clock" to DiagramSpec("三阶 m 序列发生器", profile = DiagramLayoutProfile.LFSR,
                lfsr = LfsrSpec(stages = 3, taps = listOf(1, 3), stageOutputLabels = listOf("\$a_{n-1}\$", "\$a_{n-2}\$", "\$a_{n-3}\$"))),
            "04-eight-taps" to DiagramSpec("八级抽头布局示例", profile = DiagramLayoutProfile.LFSR,
                lfsr = LfsrSpec(stages = 8, taps = (1..8).toList())),
            "05-sixteen" to DiagramSpec("十六级线性反馈移位寄存器", profile = DiagramLayoutProfile.LFSR,
                lfsr = LfsrSpec(stages = 16, taps = listOf(1, 3, 12, 16))),
            "06-legacy" to LfsrLayoutTest.legacy(),
            "07-long-labels" to DiagramSpec("较长标签", profile = DiagramLayoutProfile.LFSR,
                lfsr = LfsrSpec(stages = 3, taps = listOf(3), clockLabel = "统一时钟输入", outputLabel = "伪随机序列输出")),
            "08-long-stage-outputs" to DiagramSpec("每级输出单独标注", profile = DiagramLayoutProfile.LFSR,
                lfsr = LfsrSpec(stages = 3, taps = listOf(1, 3),
                    stageOutputLabels = listOf("第一级寄存器输出", "第二级寄存器输出", "第三级寄存器输出"))),
        )
        val export = System.getenv("LFSR_EXPORT_SAMPLES") == "1"
        val dir = File("build/lfsr-samples").apply { if (export) mkdirs() }
        for ((name, spec) in examples) for (dark in listOf(false, true)) {
            val image = DiagramRenderer.render(spec, dark = dark)
            try {
                assertTrue(image.width > 300 && image.height > 200)
                assertTrue(image.width.toLong() * image.height <= 8_000_000)
                val background = image.getPixel(0, 0)
                for (x in 0 until image.width) {
                    assertEquals("$name top", background, image.getPixel(x, 0))
                    assertEquals("$name bottom", background, image.getPixel(x, image.height - 1))
                }
                for (y in 0 until image.height) {
                    assertEquals("$name left", background, image.getPixel(0, y))
                    assertEquals("$name right", background, image.getPixel(image.width - 1, y))
                }
                if (export) File(dir, name + if (dark) "-chalk.png" else ".png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { image.recycle() }
        }
    }
}
