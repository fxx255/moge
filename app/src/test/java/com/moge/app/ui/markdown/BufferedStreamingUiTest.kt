package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.solve.AnswerSheet
import com.moge.app.ui.solve.AnswerState
import com.moge.app.ui.solve.SolveItem
import com.moge.app.ui.theme.LocalPaperMotionEnabled
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h820dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BufferedStreamingUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View
    private var text by mutableStateOf("")
    private var status by mutableStateOf(AnswerState.STREAMING)
    private var appearance by mutableStateOf(Appearance.PAPER)
    private var fontScale by mutableFloatStateOf(1f)

    private fun native(view: View = root): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { native(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun render() {
        compose.setContent {
            root = LocalView.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MogeTheme(appearance) {
                    CompositionLocalProvider(LocalPaperMotionEnabled provides false) {
                        LazyColumn(Modifier.width(393.dp).height(820.dp)) { item {
                            AnswerSheet(SolveItem.Answer("answer", status, text), {}, {}, true, { _, _ -> })
                        } }
                    }
                }
            }
        }
        tick()
    }

    private fun tick() {
        compose.mainClock.advanceTimeBy(160)
        compose.waitForIdle()
    }

    @Test fun `unfinished math is hidden and paragraph commit and completion retain the native reading view`() {
        text = "由条件可得 ${'$'}\\frac{A^{10}}{2}"
        render()
        compose.runOnIdle { assertEquals("由条件可得 ", native().single().text.toString()) }
        compose.runOnIdle { text += "${'$'}，继续求解。" }
        tick()
        lateinit var paragraph: TextView
        var height = 0
        lateinit var formula: BaselineLatexSpan
        compose.runOnIdle {
            paragraph = native().single()
            val span = (paragraph.text as Spanned).getSpans(0, paragraph.text.length, BaselineLatexSpan::class.java).single()
            formula = span
            assertTrue("The first visible formula already owns its final drawable", span.drawable.hasResult())
            height = paragraph.height
        }
        compose.runOnIdle { text += "\n\n下一段内容" }
        tick()
        compose.runOnIdle {
            assertSame("Committing a paragraph must retain its native view", paragraph, native().first())
            assertEquals("Existing lines must keep their measured height", height - paragraph.totalPaddingTop - paragraph.totalPaddingBottom,
                paragraph.layout.getLineBottom(0))
            val span = (paragraph.text as Spanned).getSpans(0, paragraph.text.length, BaselineLatexSpan::class.java).single()
            assertSame("Appending another paragraph must reuse ready formula ink", formula.drawable, span.drawable)
            assertTrue("Paragraph boundaries stay selectable in one native view", paragraph.text.contains("\n\n下一段内容"))
            height = paragraph.height
            status = AnswerState.COMPLETED
        }
        tick()
        compose.runOnIdle {
            assertSame("Completion must keep the same reading tree", paragraph, native().first())
            assertEquals(height, paragraph.height)
            text += "，补充完成后的信息。"
        }
        tick()
        compose.runOnIdle { assertTrue(paragraph.text.endsWith("，补充完成后的信息。")) }
        snapshot("formula-completed")
    }

    @Test fun `table is disclosed only after all rows arrive and its columns and native cells survive completion`() {
        text = "测量结果：\n\n| 类型 | 数值 |\n| --- | --- |\n| SNR | ${'$'}\\frac{P}{N_0B}${'$'} |"
        render()
        compose.onNodeWithTag("markdown-table").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("测量结果："), native().map { it.text.toString() }) }
        compose.runOnIdle { text += "\n| P | 宽一些的测试数据 |\n\n结论" }
        tick()
        val firstWidth = compose.onNodeWithTag("markdown-table-cell-0-0").fetchSemanticsNode().boundsInRoot.width
        lateinit var snr: TextView
        compose.runOnIdle { snr = native().single { it.text.toString() == "SNR" }; status = AnswerState.COMPLETED }
        tick()
        assertEquals(firstWidth, compose.onNodeWithTag("markdown-table-cell-0-0").fetchSemanticsNode().boundsInRoot.width, .1f)
        compose.runOnIdle { assertSame(snr, native().single { it.text.toString() == "SNR" }) }
        snapshot("table-completed")
    }

    @Test fun `figure anchors reserve one pending slot across paragraphs and stop loading on interruption`() {
        text = "图示如下：\n\n[[FIGURE:1]]\n\n再次参考 [[FIGURE:1]]。"
        render()
        compose.onAllNodesWithTag("figure-pending-0").assertCountEquals(1)
        compose.runOnIdle { assertTrue(native().none { "FIGURE" in it.text.toString() }); status = AnswerState.FAILED }
        tick()
        compose.onNodeWithTag("figure-pending-0").assertDoesNotExist()
        compose.onNodeWithTag("figure-slot-0").assertExists()
    }

    @Test fun `stopped replies release incomplete source with large fonts and disabled animations in either theme`() {
        text = "已收到的内容 ${'$'}\\frac{x+1"
        render()
        for (theme in listOf(Appearance.PAPER, Appearance.CHALK)) {
            compose.runOnIdle { appearance = theme; fontScale = 1.5f; status = AnswerState.STOPPED }
            tick()
            compose.runOnIdle { assertTrue(native().single().text.toString().endsWith("${'$'}\\frac{x+1")) }
        }
    }

    @Test fun `headings and list styles appear with their first safe text instead of changing after a blank line`() {
        text = "## 推导步骤"
        render()
        lateinit var paragraph: TextView
        var lineBottom = 0
        compose.runOnIdle {
            paragraph = native().single()
            assertEquals("推导步骤", paragraph.text.toString())
            lineBottom = paragraph.layout.getLineBottom(0)
            text += "\n\n1. 根据条件"
        }
        tick()
        compose.runOnIdle { assertSame(paragraph, native().first()); assertEquals(lineBottom, paragraph.layout.getLineBottom(0)) }
    }

    private fun snapshot(name: String) {
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val file = File("build/buffered-stream/$name.png").apply { requireNotNull(parentFile).mkdirs() }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }
}
