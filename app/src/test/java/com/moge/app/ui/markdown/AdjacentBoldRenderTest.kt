package com.moge.app.ui.markdown

import android.app.Application
import android.text.Spanned
import com.moge.app.ui.export.exportTextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.noties.jlatexmath.JLatexMathAndroid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AdjacentBoldRenderTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val boldClass = Class.forName("io.noties.markwon.core.spans.StrongEmphasisSpan")
    private val ink = 0xFF000000.toInt()
    private val link = 0xFF0000FF.toInt()

    @Before fun initMath() = JLatexMathAndroid.init(context)

    @Test fun structureLabelAndAdjacentEnglishBoldKeepTheirExactCoverageInAnswersAndExports() {
        for (adjacent in listOf(false, true)) {
            val source = explanation(adjacent)
            val expectedBold = if (adjacent) listOf("结构：recommend", "to enable members to ...", "enable sb. to do sth.")
                else listOf("结构：", "to enable members to ...", "enable sb. to do sth.")
            val view = createMarkdownTextView(context, ink, link, selectable = false)
            renderMarkdown(view, source, 800, ink, link)
            assertBold(view.text, expectedExplanation, expectedBold)
            assertBold(exportTextView(context, source, 800).text, expectedExplanation, expectedBold)
        }
    }

    @Test fun streamingAdjacentBoldBecomesVisibleBeforeCompletionAndPreservesCopyText() {
        val source = explanation(adjacent = true)
        val view = createMarkdownTextView(context, ink, link, selectable = false)
        val tokenizer = StreamingMarkdownTokenizer()
        for (end in 1..source.length) {
            renderStreamingMarkdown(view, tokenizer.update(source.take(end)), 800, ink, link)
        }
        val expectedBold = listOf("结构：recommend", "to enable members to ...", "enable sb. to do sth.")
        assertBold(view.text, expectedExplanation, expectedBold)
        renderStreamingMarkdown(view, tokenizer.update(source + "继续说明。"), 800, ink, link)
        assertBold(view.text, expectedExplanation + "继续说明。", expectedBold)
        renderStreamingMarkdown(view, tokenizer.update(source, flush = true).copy(
            blocks = emptyList(), tail = source, tailStart = 0,
            renderedPrefixEnd = source.length, visibleEnd = source.length,
        ), 800, ink, link)
        assertBold(view.text, expectedExplanation, expectedBold)
    }

    @Test fun adjacentChainsAndPunctuationOnBothSidesDoNotConsumeUnboldedProse() {
        for ((source, expected, bold) in listOf(
            Triple("前**（结构：）****recommend**后", "前（结构：）recommend后", listOf("（结构：）recommend")),
            Triple("**前。****（后）**next", "前。（后）next", listOf("前。（后）")),
            Triple("**结构：****recommend****用法。**next", "结构：recommend用法。next", listOf("结构：recommend用法。")),
        )) {
            val view = createMarkdownTextView(context, ink, link, selectable = false)
            renderMarkdown(view, source, 800, ink, link)
            assertBold(view.text, expected, bold)
        }
    }

    private fun assertBold(rendered: CharSequence, expected: String, boldText: List<String>) {
        val text = rendered as Spanned
        assertEquals(expected, text.toString())
        assertFalse(text.contains("**"))
        assertFalse(text.contains("&#"))
        assertEquals(boldText, text.getSpans(0, text.length, boldClass).sortedBy(text::getSpanStart).map {
            selectedMarkdownText(text, text.getSpanStart(it), text.getSpanEnd(it))
        })
        assertEquals(expected, selectedMarkdownText(text, 0, text.length))
    }

    private fun explanation(adjacent: Boolean): String =
        "**结构：**" + (if (adjacent) "**recommend**" else "recommend") +
            " 后接建议的内容；**to enable members to ...**说明该方案的作用，其中**enable sb. to do sth.**表示使某人能够做某事。"

    private val expectedExplanation = "结构：recommend 后接建议的内容；to enable members to ...说明该方案的作用，" +
        "其中enable sb. to do sth.表示使某人能够做某事。"
}
