package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.moge.app.core.LatexEngine
import com.moge.app.data.parse.prepareReplyMarkdown
import com.moge.app.data.parse.splitFormula
import com.moge.app.data.parse.wrapLongFormulas
import io.noties.markwon.Markwon
import io.noties.markwon.core.spans.OrderedListItemSpan
import io.noties.markwon.image.AsyncDrawableScheduler
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathDrawable
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FormulaListRenderTest {
    private val density = "p_W(w)=4w(1-w^2)\\ (0<w<1)"
    private val answer = """
        ### 三、最终答案

        1. ${'$'}\boxed{E[X^2]=1/6}${'$'}。
        2. ${'$'}\boxed{F_W(w)=2w^2-w^4\ (0\le w\le1)}${'$'}，区间外分别为 0 和 1；
           ${'$'}\boxed{$density}${'$'}；${'$'}\boxed{E[W^2]=1/3}${'$'}。
        3. ${'$'}\boxed{P(Z=1/4)=7/16}${'$'}，${'$'}\boxed{P(Z=3/4)=9/16}${'$'}，${'$'}\boxed{E[Z^2]=11/32}${'$'}。
        4. ${'$'}\boxed{E[WZ]=79/240}${'$'}，${'$'}\boxed{E[(Z-W)^2]=3/160}${'$'}。

        ### 四、易错点
    """.trimIndent()

    @Before fun init() { LatexEngine.init(RuntimeEnvironment.getApplication()) }

    @Test fun `automatic wrapping keeps numbered answers and their continuation formulas in the same list`() {
        for ((width, size) in listOf(360 to 26f, 1000 to 26f, 460 to 36f)) {
            val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), Color.BLACK, Color.BLUE, fontSizePx = size)
            view.layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            val renderer = view.tag as Markwon
            val normalized = prepareReplyMarkdown(answer)
            val prepared = wrapLongFormulas(normalized, formulaMaxWidthPx(view, width), formulaWidthMeasurer(size))
            assertEquals(4, listItems(renderer.parse(normalized)))
            assertEquals("Wrapping must retain the four answer items at $width / $size:\n$prepared",
                4, listItems(renderer.parse(prepared)))
            renderMarkdown(view, answer, width, Color.BLACK, Color.BLUE)
            AsyncDrawableScheduler.unschedule(view)
            val text = view.text as Spanned
            assertEquals(4, text.getSpans(0, text.length, OrderedListItemSpan::class.java).size)
            assertFalse("A list marker must not become prose at the previous item's end", text.contains("3. "))
            assertFalse(text.contains("4. "))
            val formulas = text.getSpans(0, text.length, BaselineLatexSpan::class.java)
            assertEquals("Every boxed result must remain present", 9, formulas.size)
            formulas.forEach { span ->
                val drawable = JLatexMathDrawable.builder(span.drawable.destination).textSize(size).color(Color.BLACK)
                    .align(JLatexMathDrawable.ALIGN_CENTER).padding(0).build()
                assertTrue("Formula exceeds the viewport: ${span.drawable.destination}",
                    drawable.intrinsicWidth <= formulaMaxWidthPx(view, width))
                span.drawable.initWithKnownDimensions(width - view.totalPaddingLeft - view.totalPaddingRight, size)
                span.drawable.setResult(drawable)
            }
            view.text = text
            preview(view, width, "numbered-formulas-$width.png")
        }
    }

    @Test fun `wrapped math preserves nested bullets and multiline list formulas`() {
        val long = "a_1+a_2+a_3+a_4+a_5+a_6+a_7+a_8"
        val sources = listOf(
            "1. 上层\n   - ${'$'}$long${'$'}；仍在子项。\n   - 另一个子项\n2. 末项",
            "1. ${'$'}${'$'}\n   $long\n   ${'$'}${'$'}；同项尾部。\n2. 末项",
        )
        val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), Color.BLACK, Color.BLUE, fontSizePx = 26f)
        val renderer = view.tag as Markwon
        for (source in sources) {
            val normalized = prepareReplyMarkdown(source)
            val prepared = wrapLongFormulas(normalized, 180, formulaWidthMeasurer(26f))
            assertEquals(prepared, listItems(renderer.parse(normalized)), listItems(renderer.parse(prepared)))
        }
    }

    @Test fun `a boxed density moves its complete domain to a new row before splitting inequalities`() {
        for (size in listOf(26f, 36f)) {
            val measure = formulaWidthMeasurer(size)
            val width = measure("\\boxed{p_W(w)=4w(1-w^2)}") + 18
            for (space in listOf("\\quad", "\\ ", " ")) for (condition in listOf("(0<w<1)", "\\left(0<w<1\\right)")) {
                val source = "\\boxed{p_W(w)=4w(1-w^2)$space$condition}"
                val wrapped = splitFormula(source, width, measure).single()
                assertTrue("The full interval must stay together: $wrapped", wrapped.contains(condition))
                assertTrue("The formula must fit at normal text size: $wrapped", measure(wrapped) <= width)
                assertEquals(1, Regex("""\\boxed\{""").findAll(wrapped).count())
                JLatexMathDrawable.builder(wrapped).textSize(size).build()
            }
        }
    }

    private fun listItems(node: Node): Int {
        var count = if (node is ListItem) 1 else 0
        var child = node.firstChild
        while (child != null) {
            count += listItems(child)
            child = child.next
        }
        return count
    }

    private fun preview(view: TextView, width: Int, filename: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(250, 247, 234))
            view.draw(canvas)
            val file = File("build/ui-refinement-previews/$filename")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
