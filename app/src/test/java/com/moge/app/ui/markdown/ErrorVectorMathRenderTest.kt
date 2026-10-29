package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Color
import android.text.Spanned
import com.moge.app.core.LatexEngine
import com.moge.app.data.parse.IncrementalReplyDecoder
import com.moge.app.data.parse.ReplyParser
import io.noties.markwon.image.AsyncDrawableScheduler
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ErrorVectorMathRenderTest {
    @Test fun errorVectorRendersAndCopiesAsEInCompletedStreamedAndSavedAnswers() {
        LatexEngine.init(RuntimeEnvironment.getApplication())
        val body = """所以最可能错误图样为

$$
e=0000010,
$$

即第6位出错。对应译码码字为

$$
c=y+e=0110010.
$$"""
        val raw = buildJsonObject { put("reply", body) }.toString()
        val decoder = IncrementalReplyDecoder()
        for (char in raw) decoder.append(char.toString())
        val old = body.replace("\ne=", "\\ne=")
        for (ink in listOf(Color.BLACK, Color.WHITE)) {
            val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), ink, Color.YELLOW, fontSizePx = 26f)
            try {
                for (source in listOf(ReplyParser.parse(raw).reply, decoder.finish(), old)) {
                    renderMarkdown(view, source, 600, ink, Color.YELLOW)
                    assertErrorVector(view.text as Spanned)
                    for (completed in listOf(false, true)) {
                        val snapshot = StreamingMarkdownTokenizer().update(source, flush = completed)
                        // StreamingMarkdownBody merges paragraphs into one native selection surface.
                        val merged = snapshot.copy(blocks = emptyList(), tail = source, tailStart = 0,
                            visibleEnd = source.length,
                            renderedPrefixEnd = if (completed) source.length else snapshot.renderedPrefixEnd)
                        renderStreamingMarkdown(view, merged, 600, ink, Color.YELLOW)
                        assertErrorVector(view.text as Spanned)
                    }
                }
            } finally { AsyncDrawableScheduler.unschedule(view) }
        }
    }

    private fun assertErrorVector(text: Spanned) {
        val spans = text.getSpans(0, text.length, BaselineLatexSpan::class.java).sortedBy(text::getSpanStart)
        assertEquals(listOf("e=0000010,", "c=y+e=0110010."), spans.map { it.drawable.destination.trim() })
        assertEquals("e=0000010,", selectedMarkdownText(text, text.getSpanStart(spans[0]), text.getSpanEnd(spans[0])))
    }
}
