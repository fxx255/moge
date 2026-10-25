package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.text.Spanned
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.moge.app.data.prefs.Appearance
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
@Config(sdk = [33], application = Application::class, qualifiers = "w400dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownTableLayoutTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: View

    private val score = """
        | 小题 | 得分 | 说明 |
        |---|---:|---|
        | (1) | 0/3 | ${'$'}E_b${'$'} 结果错误 |
        | (2) | 1/3 | 算出了信号能量，但均值、方差错误，需要重新核对计算过程 |
        | (3) | 3/3 | 门限正确 |
        | (4) | 3/3 | 最终误码率正确 |
        | 总分 | **7/12** | 严格按结果评分 |
    """.trimIndent()

    private fun render(source: String, appearance: Appearance, fontScale: Float = 1f) {
        compose.setContent { MogeTheme(appearance) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                view = LocalView.current
                Surface(Modifier.width(340.dp)) {
                    Column {
                        Text("评分参考")
                        MarkdownAnswer(source)
                        Text("表格之后的正文", Modifier.testTag("after-table"))
                    }
                }
            }
        } }
    }

    @Test fun `score table fits the page with narrow labels wrapped explanations and a fully measured following paragraph`() {
        render(score, Appearance.CHALK)
        compose.onNodeWithTag("markdown-table-cell-0-2").assertIsDisplayed()
        val table = compose.onNodeWithTag("markdown-table").fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithTag("markdown-table-cell-0-0").fetchSemanticsNode().boundsInRoot
        val points = compose.onNodeWithTag("markdown-table-cell-0-1").fetchSemanticsNode().boundsInRoot
        val note = compose.onNodeWithTag("markdown-table-cell-0-2").fetchSemanticsNode().boundsInRoot
        assertTrue(label.width < table.width * 0.25f)
        assertTrue(points.width < table.width * 0.25f)
        assertTrue(note.width > label.width + points.width)
        assertTrue(note.right <= table.right + 1f)
        val end = compose.onNodeWithTag("markdown-table-row-5").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(compose.onNodeWithTag("after-table").fetchSemanticsNode().boundsInRoot.top >= end)
        compose.runOnIdle {
            val views = textViews(view.rootView)
            assertTrue(views.any { it.text.toString() == "0/3" && it.textAlignment == View.TEXT_ALIGNMENT_TEXT_END })
            assertTrue(views.any { it.text.toString() == "小题" && it.typeface.isBold })
            assertTrue(views.any { it.text.toString().contains("算出了信号能量") && it.layout.lineCount > 1 })
            preview("table-chalk")
        }
    }

    @Test fun `large font keeps every column accessible by horizontal dragging without losing later text`() {
        val source = "| A | B | C | D | E | F |\n|---|---|---|---|---|---|\n| 内容一 | 内容二 | 内容三 | 内容四 | 内容五 | 最后一列 |"
        render(source, Appearance.PAPER, fontScale = 1.5f)
        val scroll = compose.onNodeWithTag("markdown-table-scroll")
        val before = compose.onNodeWithTag("markdown-table-cell-0-5").fetchSemanticsNode().positionInRoot.x
        scroll.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("markdown-table-cell-0-5").fetchSemanticsNode().positionInRoot.x
        assertTrue("An actually wide table must remain horizontally scrollable", after < before)
        compose.onNodeWithTag("markdown-table-cell-0-5").assertIsDisplayed()
        compose.onNodeWithTag("after-table").assertIsDisplayed()
        compose.runOnIdle { preview("table-paper-large") }
    }

    @Test fun `formula headings keep their equations inline and every cell remains selectable`() {
        val snr = "\\mathrm{SNR}>2^{\\log_2 M}-1"
        val power = "P=(0.5,1,1.5)"
        render("| 调制 | 所需 ${'$'}$snr${'$'} | (c) ${'$'}$power${'$'} |\n|---|---|---|\n| PSK | ${'$'}>3${'$'} | 0.5 |",
            Appearance.PAPER, fontScale = 1.5f)
        compose.runOnIdle {
            val cells = textViews(view.rootView).filter { it.text is Spanned }
            for (formula in listOf(snr, power)) {
                val host = cells.single { cell ->
                    val text = cell.text as Spanned
                    text.getSpans(0, text.length, BaselineLatexSpan::class.java)
                        .any { it.drawable.destination == formula }
                }
                val text = host.text as Spanned
                assertEquals("The equation must remain one inline object", 1,
                    text.getSpans(0, text.length, BaselineLatexSpan::class.java).size)
                assertFalse("Body display-block gaps must not enter a table", text.contains('\n'))
                assertTrue("Table text and math must permit long-press selection", host.isTextSelectable)
            }
        }
    }

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun preview(name: String) {
        val root = view.rootView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val file = File("build/ui-refinement-previews/$name.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
