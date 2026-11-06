package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Spanned
import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.moge.app.data.prefs.AppFont
import com.moge.app.ui.theme.appTypeface
import io.noties.markwon.Markwon
import io.noties.markwon.core.spans.CodeBlockSpan
import io.noties.markwon.core.spans.CodeSpan
import io.noties.markwon.core.spans.StrongEmphasisSpan
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Backtick quotes use body bold without changing source text or standalone code blocks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineCodeAppearanceTest {
    @Test fun oldInlineQuotesUseBodyBoldDuringStreamingOrAfterCompletionInBothThemes() {
        val source = """
            ### 三、重点句子的修改

            #### 1. 开头描述不够准确

            原句：`two mens are in a pity about the dropped alcohol`

            建议改为：

            > two men react differently to a spilled bottle

            有三处需要注意：

            - `man` 的复数是 **men**，不能再加 `-s`，所以不能写 `mens`。
            - `in a pity` 不是自然搭配。“感到遗憾”可以说 `feel sorry about...`，但图中右边的人并不悲观，用 `react differently to...` 更准确。
            - 图中只能确定瓶子倒地、液体洒出，不必擅自认定为酒，直接用 `a spilled bottle` 更稳妥。
        """.trimIndent()
        val quotes = listOf("two mens are in a pity about the dropped alcohol", "man", "-s", "mens",
            "in a pity", "feel sorry about...", "react differently to...", "a spilled bottle")
        val context = RuntimeEnvironment.getApplication()
        for ((theme, ink, background) in listOf(Triple("paper", Color.rgb(37, 51, 65), Color.rgb(248, 246, 239)),
            Triple("chalk", Color.rgb(232, 236, 231), Color.rgb(30, 43, 38)))) {
            for (size in listOf(28f, 42f)) {
                val view = createMarkdownTextView(context, ink, ink, fontSizePx = size,
                    bodyTypeface = appTypeface(context, AppFont.WENKAI))
                renderMarkdown(view, source, 620, ink, ink)
                measure(view)
                val expected = view.text.toString()
                assertTrue(expected.contains("two mens are in a pity about the dropped alcohol"))
                assertFalse(expected.contains('`'))
                assertBoldQuotes(view, quotes)
                val themeConfig = (view.tag as Markwon).configuration().theme()
                assertTrue("Fenced code retains its separate background", Color.alpha(themeConfig.getCodeBlockBackgroundColor(view.paint)) > 0)
                val tokenizer = StreamingMarkdownTokenizer()
                for (end in listOf(source.indexOf("建议改为"), source.length)) {
                    val partial = source.take(end)
                    renderStreamingMarkdown(view, tokenizer.update(partial).copy(blocks = emptyList(), tail = partial,
                        tailStart = 0, renderedPrefixEnd = partial.length, visibleEnd = partial.length), 620, ink, ink)
                    assertBoldQuotes(view, if (end < source.length) quotes.take(1) else quotes)
                }
                measure(view)
                val heightBeforeCompletion = view.height
                renderStreamingMarkdown(view, tokenizer.update(source, flush = true).copy(blocks = emptyList(),
                    tail = source, tailStart = 0, renderedPrefixEnd = source.length, visibleEnd = source.length), 620, ink, ink)
                measure(view)
                assertEquals(heightBeforeCompletion, view.height)
                assertEquals(expected, view.text.toString())
                assertEquals(expected, selectedMarkdownText(view.text, 0, view.text.length))
                assertBoldQuotes(view, quotes)
                if (System.getenv("INLINE_CODE_EXPORT_SAMPLES") == "1") {
                    val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(image).apply { drawColor(background); view.draw(this) }
                        val file = File("build/inline-code-samples/$theme-${size.toInt()}.png")
                        file.parentFile!!.mkdirs()
                        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { image.recycle() }
                }
            }
        }
    }

    @Test fun inlineCodeKeepsLiteralSymbolsAndStandaloneCodeKeepsItsOwnStyle() {
        val literal = "value < 2 && *literal* != ${'$'}x${'$'}"
        val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), Color.BLACK, Color.BLUE)
        renderMarkdown(view, "引用：`$literal`", 620, Color.BLACK, Color.BLUE)
        assertEquals("引用：$literal", view.text.toString())
        assertBoldQuotes(view, listOf(literal))
        assertEquals("引用：$literal", selectedMarkdownText(view.text, 0, view.text.length))
        val blockLiteral = "if (value < 2 && enabled) value++ // *literal*"
        for (source in listOf("```kotlin\n$blockLiteral\n```", "    $blockLiteral")) {
            renderMarkdown(view, source, 620, Color.BLACK, Color.BLUE)
            val text = view.text as Spanned
            assertTrue(text.toString(), text.contains(blockLiteral))
            assertEquals(1, text.getSpans(0, text.length, CodeBlockSpan::class.java).size)
            assertTrue(text.getSpans(0, text.length, StrongEmphasisSpan::class.java).isEmpty())
            val theme = (view.tag as Markwon).configuration().theme()
            assertTrue(Color.alpha(theme.getCodeBlockBackgroundColor(view.paint)) > 0)
        }
    }

    private fun assertBoldQuotes(view: TextView, quotes: List<String>) {
        val text = view.text as Spanned
        assertTrue("Inline quotes no longer use code styling", text.getSpans(0, text.length, CodeSpan::class.java).isEmpty())
        val spans = text.getSpans(0, text.length, StrongEmphasisSpan::class.java)
        quotes.forEach { quote ->
            val span = spans.single { text.subSequence(text.getSpanStart(it), text.getSpanEnd(it)).toString() == quote }
            for (measureOnly in listOf(true, false)) {
                val paint = TextPaint(view.paint)
                if (measureOnly) span.updateMeasureState(paint) else span.updateDrawState(paint)
                assertEquals("Inline quotes must never draw rectangular backgrounds", 0, Color.alpha(paint.bgColor))
                assertEquals("Quotes preserve the selected body font", view.typeface, paint.typeface)
                assertTrue("Quotes have visible bold emphasis", paint.isFakeBoldText || paint.typeface?.isBold == true)
            }
        }
    }

    private fun measure(view: TextView) {
        if (view.layoutParams == null) view.layoutParams = ViewGroup.LayoutParams(620, ViewGroup.LayoutParams.WRAP_CONTENT)
        view.measure(View.MeasureSpec.makeMeasureSpec(620, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
}
