package com.moge.app.data.llm

import com.moge.app.data.parse.ParsedReply
import com.moge.app.domain.ConversationMode
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NetworkAnswerRecoveryTest {
    private val network = object : NetworkAvailability {
        override val available = true
        override suspend fun awaitAvailable(timeoutMs: Long) = true
    }
    private fun session() = GenerationNetworkSession(network, clock = MonotonicClock { 0L })

    @Test fun structuredContinuationRetainsPrefixMetadataAndOriginalImageOwnership() = runBlocking(session()) {
        val response = ConversationResponse(ConversationMode.SOLVE)
        val events = mutableListOf<StreamEvent>()
        val prefix = "第一段已经写完。\n\n这个未完成段落的开头保持一致还没"
        val incoming = "这个未完成段落的开头保持一致还没有写完，现在结束。"
        val raw = "{\"conversation_title\":\"标题\",\"answer\":\"结论\",\"reply\":\"" +
            kotlinx.serialization.json.JsonPrimitive(prefix).toString().drop(1).dropLast(1)
        var calls = 0
        val result = NetworkAnswerRecovery.run(response, true, listOf(ChatMessage("user", "原题")), listOf("IMAGE"), events::add,
            turnContext = "原始时间与模式") { messages, images, recovering, emit ->
            calls++
            if (calls == 1) { emit(StreamEvent.AnswerDelta(raw)); throw IOException("reset") }
            assertEquals(emptyList<String>(), images)
            assertEquals(listOf("IMAGE"), messages.first().imageBase64s)
            assertEquals("原始时间与模式", messages.first().turnContext)
            assertTrue(recovering)
            assertTrue(messages.last().content.contains("接着写"))
            val envelope = "{\"reply\":" + kotlinx.serialization.json.JsonPrimitive(incoming) + "}"
            for (part in envelope.chunked(7)) emit(StreamEvent.AnswerDelta(part))
            ParsedReply(reply = incoming, conversationTitle = "续写标题", finalAnswer = "不应覆盖原结论")
        }
        assertEquals(2, calls)
        assertEquals("标题", result.conversationTitle)
        assertEquals("结论", result.finalAnswer)
        assertEquals("第一段已经写完。\n\n$incoming", result.reply)
        val decoder = response.streamDecoder()
        events.forEach { when (it) {
            is StreamEvent.AnswerDelta -> decoder.append(it.text)
            StreamEvent.AnswerReset -> decoder.reset()
            else -> Unit
        } }
        assertEquals(result.reply, decoder.finish())
    }

    @Test fun disabledOptionReasoningOnlyAndPreviouslyHandledToolFailureNeverContinue() = runBlocking {
        for ((enabled, raw, error) in listOf(
            Triple(false, "{\"reply\":\"已有正文", IOException("cut")),
            Triple(true, "{\"conversation_title\":\"只有标题\"", IOException("cut")),
            Triple(true, "{\"reply\":\"旧轮正文", ToolNetworkInterruption(IOException("new round had no text"))),
        )) {
            var calls = 0
            try { withContext(session()) {
                NetworkAnswerRecovery.run(ConversationResponse(ConversationMode.SOLVE), enabled, emptyList(), emptyList(), {}) { _, _, _, emit ->
                    calls++; emit(StreamEvent.AnswerDelta(raw)); throw error
                }
            }; fail("must keep interruption") } catch (actual: IOException) {
                assertEquals(error.javaClass, actual.javaClass); assertEquals(error.message, actual.message)
            }
            assertEquals(1, calls)
        }
    }

    @Test fun plainChatRecoveryIsLimitedToOneAttemptAndCancellationPropagates() = runBlocking {
        for (failure in listOf(IOException("cut"), CancellationException("stop"))) {
            var calls = 0
            try { withContext(session()) {
                NetworkAnswerRecovery.run(ConversationResponse(ConversationMode.CHAT), true, emptyList(), emptyList(), {}) { _, _, _, emit ->
                    calls++; emit(StreamEvent.AnswerDelta("已收到的正文")); throw failure
                }
            }; fail("must keep final failure") } catch (actual: Exception) {
                assertEquals(failure.javaClass, actual.javaClass); assertEquals(failure.message, actual.message)
            }
            assertEquals(if (failure is CancellationException) 1 else 2, calls)
        }
    }
}
