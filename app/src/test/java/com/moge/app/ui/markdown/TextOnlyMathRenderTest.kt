package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.moge.app.data.prefs.AppFont
import com.moge.app.ui.export.exportTextView
import com.moge.app.ui.theme.appTypeface
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathAndroid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextOnlyMathRenderTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val sentence = "I would appreciate it if these practical, low-cost measures could be piloted next semester."
    private val source = "可改成更冲 9 的收束句：\n\n\$\$\\text{$sentence}\$\$\n\n总体判断：内容 3/3、格式 2/2。"

    @Before fun initMath() = JLatexMathAndroid.init(context)

    @Test fun screenshotSentenceWrapsAtBodySizeAndSupportsPartialSelectionDuringAndAfterStreaming() {
        for ((theme, ink, background) in listOf(
            Triple("paper", Color.rgb(37, 51, 65), Color.rgb(248, 246, 239)),
            Triple("chalk", Color.rgb(232, 236, 231), Color.rgb(30, 43, 38)),
        )) for (size in listOf(28f, 42f)) for (width in listOf(360, 740)) {
            val view = createMarkdownTextView(context, ink, ink, fontSizePx = size,
                bodyTypeface = appTypeface(context, AppFont.WENKAI))
            renderMarkdown(view, source, width, ink, ink, stableLayout = true)
            measure(view, width)
            assertSentence(view)
            val completedText = view.text.toString()
            val completedHeight = view.height
            val tokenizer = StreamingMarkdownTokenizer()
            val closing = source.indexOf("}\$\$") + 3
            for (end in listOf(closing - 2, closing - 1, closing, source.length)) {
                renderStreamingMarkdown(view, tokenizer.update(source.take(end)).let { snapshot ->
                    snapshot.copy(blocks = emptyList(), tail = source.take(end), tailStart = 0)
                }, width, ink, ink)
                if (end >= closing) {
                    measure(view, width)
                    assertSentence(view)
                }
            }
            assertEquals(completedText, view.text.toString())
            assertEquals(completedHeight, view.height)
            renderStreamingMarkdown(view, tokenizer.update(source, flush = true).copy(
                blocks = emptyList(), tail = source, tailStart = 0,
                renderedPrefixEnd = source.length, visibleEnd = source.length,
            ), width, ink, ink)
            measure(view, width)
            assertSentence(view)
            assertEquals(completedText, view.text.toString())
            assertEquals(completedHeight, view.height)
            if (System.getenv("TEXT_ONLY_MATH_SAMPLES") == "1" && width == 740 && size == 42f) {
                val bitmap = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(bitmap).apply { drawColor(background); view.draw(this) }
                    val file = File("build/text-only-math-samples/$theme.png")
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun exportsUseWrappingSelectableTextWhileMixedTextAndMathKeepTheirFormula() {
        val exported = exportTextView(context, source, 740)
        assertSentence(exported)
        val view = createMarkdownTextView(context, Color.BLACK, Color.BLUE)
        renderMarkdown(view, "\$\$P(\\text{the sentence remains a math label})=1\$\$", 740, Color.BLACK, Color.BLUE, stableLayout = true)
        val text = view.text as Spanned
        assertEquals(1, text.getSpans(0, text.length, BaselineLatexSpan::class.java).size)
    }

    private fun assertSentence(view: TextView) {
        val text = view.text as Spanned
        assertTrue("The whole sentence must use body text rather than a formula image", text.getSpans(0, text.length, BaselineLatexSpan::class.java).isEmpty())
        assertTrue(text.toString(), text.contains(sentence))
        assertFalse(text.contains("\\text"))
        val start = text.indexOf(sentence)
        val end = start + sentence.length
        assertTrue("The sentence must wrap at body size", view.layout.getLineForOffset(end) > view.layout.getLineForOffset(start))
        val expected = "practical, low-cost measures"
        val from = text.indexOf(expected)
        val to = from + expected.length
        assertEquals(MarkdownSelection(from, to), snapFormulaSelection(text, from, to))
        assertEquals(expected, selectedMarkdownText(text, from, to, delimitFormulas = true))
        val word = text.indexOf("piloted")
        val line = view.layout.getLineForOffset(word)
        val x = view.totalPaddingLeft + view.layout.getPrimaryHorizontal(word)
        val y = view.totalPaddingTop + (view.layout.getLineTop(line) + view.layout.getLineBottom(line)) / 2f
        assertEquals("Touch coordinates must address individual text characters", word, view.getOffsetForPosition(x, y))
    }

    private fun measure(view: TextView, width: Int) {
        view.layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
    }
}
