package com.moge.app.ui.markdown

import android.app.Application
import android.text.Spanned
import com.moge.app.ui.export.exportTextView
import io.noties.markwon.core.spans.EmphasisSpan
import io.noties.markwon.core.spans.StrongEmphasisSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.noties.jlatexmath.JLatexMathAndroid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class BoldItalicBoundaryRenderTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val ink = 0xFF000000.toInt()
    private val link = 0xFF0000FF.toInt()
    private val phrases = listOf("There may have been ...", "Just as ..., so ...")
    private val label = "句子结构："
    private val explanation = " 表示对句子结构的说明。"

    @Before fun initMath() = JLatexMathAndroid.init(context)

    @Test fun screenshotLabelsKeepSeparateBoldAndItalicCoverageInAnswersAndExports() {
        for (phrase in phrases) {
            val source = "**$label***$phrase*$explanation"
            for (color in listOf(ink, 0xFFFFFFFF.toInt())) {
                val view = createMarkdownTextView(context, color, link, selectable = false)
                renderMarkdown(view, source, 800, color, link)
                assertStyles(view.text, label + phrase + explanation, listOf(label), listOf(phrase))
            }
            assertStyles(exportTextView(context, source, 800).text, label + phrase + explanation, listOf(label), listOf(phrase))
        }
    }

    @Test fun streamingKeepsTheBoldLabelVisibleAndClosesItalicBeforeCompletion() {
        for (phrase in phrases) {
            val source = "**$label***$phrase*$explanation"
            val view = createMarkdownTextView(context, ink, link, selectable = false)
            val tokenizer = StreamingMarkdownTokenizer()
            val labelEnd = "**$label**".length
            val italicEnd = "**$label***$phrase*".length
            for (end in 1..source.length) {
                renderStreamingMarkdown(view, tokenizer.update(source.take(end)), 800, ink, link)
                if (end in labelEnd until italicEnd) {
                    assertStyles(view.text, label, listOf(label), emptyList())
                } else if (end >= italicEnd) {
                    assertStyles(view.text, label + phrase + source.substring(italicEnd, end), listOf(label), listOf(phrase))
                }
            }
            val bold = (view.text as Spanned).getSpans(0, view.text.length, StrongEmphasisSpan::class.java).single()
            renderStreamingMarkdown(view, tokenizer.update(source + "继续说明。"), 800, ink, link)
            assertStyles(view.text, label + phrase + explanation + "继续说明。", listOf(label), listOf(phrase))
            val appended = view.text as Spanned
            assertSame(bold, appended.getSpans(0, appended.length, StrongEmphasisSpan::class.java).single())
            // The page merges committed paragraphs into this same selectable reading surface.
            renderStreamingMarkdown(view, tokenizer.update(source, flush = true).copy(
                blocks = emptyList(), tail = source, tailStart = 0,
                renderedPrefixEnd = source.length, visibleEnd = source.length,
            ), 800, ink, link)
            assertStyles(view.text, label + phrase + explanation, listOf(label), listOf(phrase))
        }
    }

    @Test fun adjacentStrongLabelsCanAlsoEndAtTheSharedItalicBoundary() {
        val source = "**前缀****$label***There may have been ...* 后文"
        val view = createMarkdownTextView(context, ink, link, selectable = false)
        renderMarkdown(view, source, 800, ink, link)
        assertStyles(view.text, "前缀${label}There may have been ... 后文", listOf("前缀$label"), listOf(phrases.first()))
        renderStreamingMarkdown(view, StreamingMarkdownTokenizer().update(source), 800, ink, link)
        assertStyles(view.text, "前缀${label}There may have been ... 后文", listOf("前缀$label"), listOf(phrases.first()))
    }

    private fun assertStyles(rendered: CharSequence, expected: String, bold: List<String>, italic: List<String>) {
        val text = rendered as Spanned
        assertEquals(expected, text.toString())
        assertFalse(text.contains("**"))
        assertFalse(text.contains("&#"))
        assertEquals(bold, text.getSpans(0, text.length, StrongEmphasisSpan::class.java).sortedBy(text::getSpanStart).map {
            selectedMarkdownText(text, text.getSpanStart(it), text.getSpanEnd(it))
        })
        assertEquals(italic, text.getSpans(0, text.length, EmphasisSpan::class.java).sortedBy(text::getSpanStart).map {
            selectedMarkdownText(text, text.getSpanStart(it), text.getSpanEnd(it))
        })
        assertEquals(expected, selectedMarkdownText(text, 0, text.length))
    }
}
