package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Color
import android.text.Spanned
import com.moge.app.core.LatexEngine
import io.noties.markwon.image.AsyncDrawableScheduler
import org.junit.Assert.*
import org.junit.Before
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
class InlineMathListRenderTest {
    @Before fun prepare() { LatexEngine.init(RuntimeEnvironment.getApplication()) }

    @Test fun `reported matrix answer renders both symbol lists and both equations in either theme`() {
        val source = javaClass.getResource("/fixtures/matrix-equivalence.md")!!.readText().trimEnd()
        val expected = listOf("P,Q", "PAQ=B", "\\operatorname{rank}(A)=\\operatorname{rank}(B)", "A,B")
        for (ink in listOf(Color.BLACK, Color.WHITE)) {
            val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), ink, Color.YELLOW, fontSizePx = 26f)
            try {
                renderMarkdown(view, source, 600, ink, Color.YELLOW)
                val text = view.text as Spanned
                val spans = text.getSpans(0, text.length, BaselineLatexSpan::class.java).sortedBy(text::getSpanStart)
                assertEquals(expected, spans.map { it.drawable.destination.trim() })
                assertFalse("No raw math delimiters remain", text.contains('$'))
                for (span in spans) {
                    val latex = span.drawable.destination
                    val drawable = JLatexMathDrawable.builder(latex).textSize(26f).padding(0).build()
                    assertTrue(latex, drawable.intrinsicWidth > 0 && drawable.intrinsicWidth <= formulaMaxWidthPx(view, 600))
                    assertEquals(latex, selectedMarkdownText(text, text.getSpanStart(span), text.getSpanEnd(span)))
                }
                assertTrue(text.toString(), text.toString().endsWith("的秩。"))
            } finally { AsyncDrawableScheduler.unschedule(view) }
        }
    }

    @Test fun `streaming completes letter lists without disturbing the unfinished formula`() {
        val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), Color.WHITE, Color.YELLOW, fontSizePx = 26f)
        try {
            val tokenizer = StreamingMarkdownTokenizer()
            val prefix = "存在可逆矩阵 \$P,Q"
            renderStreamingMarkdown(view, tokenizer.update(prefix), 600, Color.WHITE, Color.YELLOW)
            assertTrue("An unfinished formula must stay literal", view.text.endsWith("\$P,Q"))
            val source = "$prefix\$，只需比较 \$A,B\$ 的秩。"
            renderStreamingMarkdown(view, tokenizer.update(source), 600, Color.WHITE, Color.YELLOW)
            val text = view.text as Spanned
            assertEquals(listOf("P,Q", "A,B"), text.getSpans(0, text.length, BaselineLatexSpan::class.java)
                .sortedBy(text::getSpanStart).map { it.drawable.destination })
            assertFalse(text.contains('$'))
            assertTrue(text.toString().endsWith("的秩。"))
        } finally { AsyncDrawableScheduler.unschedule(view) }
    }
}
