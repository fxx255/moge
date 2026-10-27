package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Color
import android.text.Spanned
import io.noties.markwon.core.spans.BlockQuoteSpan
import io.noties.markwon.image.AsyncDrawableScheduler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathDrawable

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BlockQuoteMathRenderTest {
    @Test fun `quoted display math renders screenshot formulas during streaming and after completion`() {
        val formulas = listOf(
            """P_{NA}=2BN_0=14\times 10^{-3}\,\mathrm{W},""",
            "P_N=N_0B_T,",
            """\boxed{\left(\frac{S}{N}\right)_A=3000}""",
            """\boxed{P_s=21\,\mathrm{W},\quad B_s=5\,\mathrm{kHz}}""",
        )
        val source = formulas.joinToString("\n>\n") { "> 小结：\n> \\[\n> $it\n> \\]\n> 继续说明。" }
        for (ink in listOf(Color.BLACK, Color.WHITE)) {
            val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), ink, Color.YELLOW, fontSizePx = 26f)
            try {
                renderMarkdown(view, source, 600, ink, Color.YELLOW)
                assertQuotedFormulas(view.text as Spanned, formulas)
                val tokenizer = StreamingMarkdownTokenizer()
                renderStreamingMarkdown(view, tokenizer.update(source), 600, ink, Color.YELLOW)
                assertQuotedFormulas(view.text as Spanned, formulas)
                // StreamingMarkdownBody merges committed blocks into one selection surface.
                val completed = tokenizer.update(source, flush = true).copy(
                    blocks = emptyList(), tail = source, tailStart = 0,
                    renderedPrefixEnd = source.length, visibleEnd = source.length,
                )
                renderStreamingMarkdown(view, completed, 600, ink, Color.YELLOW)
                assertQuotedFormulas(view.text as Spanned, formulas)
            } finally { AsyncDrawableScheduler.unschedule(view) }
        }
    }

    @Test fun `long inline math stays inside its quote after wrapping`() {
        val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), Color.BLACK, Color.BLUE, fontSizePx = 26f)
        try {
            renderMarkdown(view, "> 结论：\\(a+b+c+d=e+f+g+h\\)，继续。", 180, Color.BLACK, Color.BLUE)
            val text = view.text as Spanned
            val formulas = text.getSpans(0, text.length, BaselineLatexSpan::class.java)
            assertTrue(text.toString(), formulas.size > 1)
            for (span in formulas) {
                assertFalse(span.drawable.destination.contains('>'))
                assertTrue("Wrapped formulas must retain quote styling", text.getSpans(
                    text.getSpanStart(span), text.getSpanEnd(span), BlockQuoteSpan::class.java).isNotEmpty())
            }
            assertTrue(text.toString(), text.toString().endsWith("继续。"))
        } finally { AsyncDrawableScheduler.unschedule(view) }
    }

    @Test fun `nested quotes retain multiline math without quote markers in latex`() {
        val source = "> > \\[\n> > a+b\n> > =c\n> > \\]\n> > 后续含 \\(N_0\\)。"
        val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), Color.BLACK, Color.BLUE)
        try {
            renderMarkdown(view, source, 600, Color.BLACK, Color.BLUE)
            assertQuotedFormulas(view.text as Spanned, listOf("a+b\n=c", "N_0"))
        } finally { AsyncDrawableScheduler.unschedule(view) }
    }

    private fun assertQuotedFormulas(text: Spanned, expected: List<String>) {
        val spans = text.getSpans(0, text.length, BaselineLatexSpan::class.java).sortedBy(text::getSpanStart)
        assertEquals(text.toString(), expected, spans.map { it.drawable.destination.trim() })
        assertTrue("Quote styling must survive", text.getSpans(0, text.length, BlockQuoteSpan::class.java).isNotEmpty())
        for (span in spans) {
            val latex = span.drawable.destination
            assertFalse(latex, latex.contains('>'))
            assertEquals(latex.trim(), selectedMarkdownText(text, text.getSpanStart(span), text.getSpanEnd(span)))
            val drawable = JLatexMathDrawable.builder(latex).textSize(26f).padding(0).build()
            assertTrue(latex, drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
        }
        assertFalse("Raw delimiters must not leak", text.contains("\\[") || text.contains("\\]") || text.contains('$'))
    }
}
