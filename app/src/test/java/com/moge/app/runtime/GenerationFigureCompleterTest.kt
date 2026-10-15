package com.moge.app.runtime

import android.app.Application
import com.moge.app.data.llm.ChatMessage
import com.moge.app.data.llm.ModelClient
import com.moge.app.data.llm.RequestPolicy
import com.moge.app.data.llm.StreamEvent
import com.moge.app.data.parse.ParsedReply
import com.moge.app.data.parse.ReplyFigure
import com.moge.app.data.parse.ReplyParser
import com.moge.app.data.parse.orderedFigures
import com.moge.app.domain.SolveMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GenerationFigureCompleterTest {
    private val model = mockk<ModelClient>()
    private val source = requireNotNull(javaClass.getResource("/fixtures/qam-missing-demodulator.md")).readText()
    private val valid = ReplyParser.parse("""{"diagrams":[{"title":"receiver","nodes":[{"id":"a","label":"8 电平判决"}],"edges":[]}]}""")
    private val diagram = valid.orderedFigures().single()
    private val original = ReplyParser.parse("""{"plots":[{"title":"spectrum","series":[{"expr":"x"}]}],"diagrams":[{"title":"modulator","nodes":[{"id":"a","label":"I/Q"}],"edges":[]},{}]}""").orderedFigures()
    private val policy = RequestPolicy(primaryProfileId = "original", model = "pinned-model")
    private val context = FigureCompletionContext(listOf(ChatMessage("user", "原题")),
        listOf("original-photo"), SolveMode.DETAILED, policy, {})
    private var renderCalls = 0
    private var failFirstRender = false
    private val renderer = object : FigureRenderer {
        override suspend fun render(figures: List<ReplyFigure>): List<String> {
            renderCalls++
            if (failFirstRender && renderCalls == 1) return figures.map { "" }
            return figures.map { when (it) {
                is ReplyFigure.Plot -> "/${it.spec.title}.png"
                is ReplyFigure.Diagram -> "/${it.spec.title}.png"
                ReplyFigure.Missing -> ""
            } }
        }
    }

    private fun stub(reply: ParsedReply = valid, inspect: (List<ChatMessage>) -> Unit = {}) {
        coEvery { model.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>()) } coAnswers {
            inspect(arg(0))
            assertEquals(context.imageBase64s, arg<List<String>>(1))
            assertEquals(context.solveMode, arg<SolveMode>(2))
            assertFalse(arg(3)); assertFalse(arg(4))
            assertSame(policy, arg<RequestPolicy>(6))
            reply
        }
    }

    @Test fun `failed third slot is filled without changing successful figures or their numbers`() = runBlocking {
        stub { history ->
            assertEquals("原题", history.first().content)
            val explanation = history[history.lastIndex - 1].content
            assertTrue(explanation.contains("8 电平判决"))
            assertTrue(explanation.contains("240 kbit/s"))
            assertTrue(history.last().content.contains("只补这一张图"))
            assertTrue(history.last().content.contains("[[FIGURE:3]]"))
        }
        val paths = GenerationFigureCompleter(renderer, model).complete(source, "结论", original, context)
        assertEquals(listOf("/spectrum.png", "/modulator.png", "/receiver.png"), paths)
        assertEquals(2, renderCalls)
        coVerify(exactly = 1) { model.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `an anchor with no supplied parameter slot is reserved and repaired`() = runBlocking {
        stub()
        assertEquals(listOf("/spectrum.png", "/modulator.png", "/receiver.png"),
            GenerationFigureCompleter(renderer, model).complete(source, "", original.take(2), context))
    }

    @Test fun `complete images and ordinary chat require no model request`() = runBlocking {
        val completer = GenerationFigureCompleter(renderer, model)
        assertEquals(listOf("/receiver.png"), completer.complete("[[FIGURE:1]]", "", listOf(diagram), context))
        assertEquals(emptyList<String>(), completer.complete(source, "", emptyList(), null))
        coVerify(exactly = 0) { model.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `temporary rendering failure retries its existing spec locally`() = runBlocking {
        failFirstRender = true
        assertEquals(listOf("/receiver.png"), GenerationFigureCompleter(renderer, model)
            .complete("[[FIGURE:1]]", "", listOf(diagram), context))
        assertEquals(2, renderCalls)
        coVerify(exactly = 0) { model.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `network failure leaves only the failed slot empty and is not replayed`() = runBlocking {
        coEvery { model.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>()) } throws IOException("stream failed")
        assertEquals(listOf("/spectrum.png", "/modulator.png", ""), GenerationFigureCompleter(renderer, model)
            .complete(source, "", original, context))
        coVerify(exactly = 1) { model.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `ambiguous multi image repair cannot replace a single numbered slot`() = runBlocking {
        stub(valid.copy(diagramSlots = listOf(valid.diagrams.single(), valid.diagrams.single())))
        assertEquals(listOf("/spectrum.png", "/modulator.png", ""), GenerationFigureCompleter(renderer, model)
            .complete(source, "", original, context))
    }

    @Test fun `cancellation during repair is propagated instead of saving success`() = runBlocking {
        coEvery { model.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>()) } throws CancellationException("cancelled")
        try {
            GenerationFigureCompleter(renderer, model).complete(source, "", original, context)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun `code examples and invalid numbers cannot trigger repair or huge allocations`() = runBlocking {
        val text = "`[[FIGURE:1]]`\n```text\n[[FIGURE:2]]\n```\n[[FIGURE:0]]\n[[FIGURE:999999999]]"
        assertTrue(GenerationFigureCompleter(renderer, model).complete(text, "", emptyList(), context).isEmpty())
        coVerify(exactly = 0) { model.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `target explanation is retained deep inside an unlimited answer`() {
        val text = "旧内容".repeat(10000) + "\n[[FIGURE:1]]\n\n解调参数 240 kbit/s，8 电平判决\n[[FIGURE:3]]"
        val excerpt = figureContext(text, 3)
        assertTrue(excerpt.contains("解调参数 240 kbit/s"))
        assertFalse(excerpt.contains("旧内容"))
        assertFalse(excerpt.contains("[[FIGURE:1]]"))
    }
}
