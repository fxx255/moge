package com.moge.app.ui.markdown

import android.app.Application
import android.text.Spanned
import com.moge.app.data.parse.prepareReplyMarkdown
import com.moge.app.ui.export.exportTextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.noties.jlatexmath.JLatexMathAndroid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PunctuationBoldRenderTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val boldClass = Class.forName("io.noties.markwon.core.spans.StrongEmphasisSpan")
    private val ink = 0xFF000000.toInt()
    private val link = 0xFF0000FF.toInt()

    @Before fun initMath() = JLatexMathAndroid.init(context)

    @Test fun commaCorrectionKeepsExactTextAndBoldSpanWhileStreamingAndAfterCompletion() {
        val source = "although everyone has limitations**,** we can overcome..."
        val expected = "although everyone has limitations, we can overcome..."
        val view = createMarkdownTextView(context, ink, link, selectable = false)
        val tokenizer = StreamingMarkdownTokenizer()
        val commaEnd = source.indexOf(",**") + 3
        for (end in listOf(commaEnd - 2, commaEnd - 1, commaEnd, commaEnd + 4, source.length)) {
            renderStreamingMarkdown(view, tokenizer.update(source.take(end)), 600, ink, link)
            if (end >= commaEnd) assertPunctuation(view.text, source.take(end).replace("**", ""), ",")
        }
        renderStreamingMarkdown(view, tokenizer.update(source, flush = true).copy(
            blocks = emptyList(), tail = source, tailStart = 0,
            renderedPrefixEnd = source.length, visibleEnd = source.length,
        ), 600, ink, link)
        assertPunctuation(view.text, expected, ",")
        renderMarkdown(view, source, 600, ink, link)
        assertPunctuation(view.text, expected, ",")
    }

    @Test fun punctuationOnlyBoldWorksBesideLettersDigitsAndSupplementaryCharacters() {
        for ((before, punctuation, after) in listOf(
            Triple("word", ",", " next"), Triple("word", ",", "next"),
            Triple("word", "!", "Next"), Triple("left", ";:", "right"),
            Triple("a1", ",", "2b"), Triple("word", "（，）", "next"),
            Triple("word", "...", "next"), Triple("𐐀", ",", "𐐁"),
        )) {
            val source = "$before**$punctuation**$after"
            val view = createMarkdownTextView(context, ink, link, selectable = false)
            renderMarkdown(view, source, 600, ink, link)
            assertPunctuation(view.text, before + punctuation + after, punctuation)
        }
    }

    @Test fun exportedAnswerAlsoPreservesTheBoldCommaAndCopyableSentence() {
        val source = "宜写 although everyone has limitations**,** we can overcome...；"
        val exported = exportTextView(context, source, 800)
        assertPunctuation(exported.text, source.replace("**", ""), ",")
    }

    @Test fun literalContextsUnclosedMarkersAndWordEmphasisKeepTheirOriginalSource() {
        val literal = "word**,**next"
        for (source in listOf(
            "`$literal`", "```text\n$literal\n```", "    $literal", "\t$literal",
            "\$\$\\text{$literal}\$\$", "word\\*\\*,\\*\\*next", "word**,",
            "word** , **next", "before**(literal)**after",
        )) assertEquals(source, prepareReplyMarkdown(source))
        for (source in listOf(literal, "word**!**next", "a1**;:**2b")) {
            val prepared = prepareReplyMarkdown(source)
            assertEquals(prepared, prepareReplyMarkdown(prepared))
        }
    }

    private fun assertPunctuation(rendered: CharSequence, expected: String, punctuation: String) {
        val text = rendered as Spanned
        assertEquals(expected, text.toString())
        assertTrue(text.toString(), !text.contains("**") && !text.contains("&#"))
        val bold = text.getSpans(0, text.length, boldClass).single()
        assertEquals(punctuation, selectedMarkdownText(text, text.getSpanStart(bold), text.getSpanEnd(bold)))
        assertEquals(expected, selectedMarkdownText(text, 0, text.length))
    }
}
