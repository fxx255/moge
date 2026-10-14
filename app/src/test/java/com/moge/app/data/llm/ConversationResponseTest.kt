package com.moge.app.data.llm

import com.moge.app.data.credential.AiReasoningEffort
import com.moge.app.domain.ConversationMode
import com.moge.app.domain.SolveMode
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ConversationResponseTest {
    @Test fun chatPreservesJsonExamplesAndShortAnswersWhileSolvingStillDecodesItsEnvelope() {
        val raw = """{"reply":"这是 JSON 示例","answer":"字段值"}"""
        val chat = ConversationResponse(ConversationMode.CHAT)
        val decoder = chat.streamDecoder()
        raw.chunked(3).forEach { decoder.append(it) }
        assertEquals(raw, decoder.finish())
        assertEquals(raw, chat.parse(raw).reply)
        assertEquals("", chat.parse(raw).finalAnswer)
        assertEquals("见上", chat.reconcile("见上", "见上"))
        decoder.reset()
        assertEquals("你好", decoder.append("你好"))
        assertEquals("你好", decoder.finish())
        val solve = ConversationResponse(ConversationMode.SOLVE)
        assertEquals("这是 JSON 示例", solve.parse(raw).reply)
        assertEquals("字段值", solve.parse(raw).finalAnswer)
    }

    @Test fun chatPromptAndRecoveryNeverInjectSolvingOrJsonInstructions() {
        val clock = Clock.fixed(Instant.parse("2026-10-14T08:30:00Z"), ZoneOffset.UTC)
        val prompt = PromptAssembler.assemble("解题协议只输出 JSON", "小墨", AiReasoningEffort.HIGH,
            SolveMode.CHECK_WORK, clock, ConversationMode.CHAT)
        assertTrue(prompt.stableSystem.contains("小墨"))
        assertTrue(prompt.volatileSystem.contains("2026-10-14 08:30"))
        val all = prompt.stableSystem + prompt.volatileSystem
        listOf("解题", "JSON", "推理设定", "完整推导").forEach { assertFalse(all.contains(it)) }
        val chat = ConversationResponse(ConversationMode.CHAT)
        assertFalse(chat.recoveryInstruction.contains("JSON"))
        assertFalse(chat.continuationInstruction.contains("JSON"))
        assertTrue(ConversationResponse(ConversationMode.SOLVE).continuationInstruction.contains("JSON"))
    }

    @Test fun oldSnapshotsDefaultToSolvingAndChatSnapshotsRoundTrip() {
        val old = SnapshotCodec.decode("""{"model":"m","endpointIdentity":"https://example.com/v1"}""")!!
        assertEquals(ConversationMode.SOLVE, old.toPolicy().conversationMode)
        val chat = old.copy(conversationMode = ConversationMode.CHAT.name)
        assertEquals(ConversationMode.CHAT, SnapshotCodec.decode(SnapshotCodec.encode(chat))!!.toPolicy().conversationMode)
    }
}
