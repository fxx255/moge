package com.moge.app.data.llm

import com.moge.app.data.credential.AiReasoningEffort
import com.moge.app.data.prefs.AppLanguage
import com.moge.app.domain.ConversationMode
import com.moge.app.domain.SolveMode
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AnswerLanguageTest {
    @Test fun eachModeUsesExactlyTheSelectedAnswerLanguage() {
        for (mode in ConversationMode.entries) for (language in AppLanguage.entries) {
            val prompt = PromptAssembler.assemble(SOLVE_SYSTEM_PROMPT, "", AiReasoningEffort.LOW,
                SolveMode.DETAILED, conversationMode = mode, answerLanguage = language)
            assertEquals(language, prompt.answerLanguage)
            assertTrue(prompt.stableSystem.contains(language.answerInstruction))
            assertFalse(prompt.stableSystem.contains("用简体中文回答"))
            assertFalse(prompt.stableSystem.contains("默认使用用户的语言"))
            assertSame(prompt, PromptAssembler.reusablePrompt(prompt, SolveMode.DETAILED, mode))
            for (other in AppLanguage.entries.filter { it != language }) {
                assertFalse(prompt.stableSystem.contains(other.answerInstruction))
            }
        }
    }

    @Test fun savedPromptRetainsLanguageAndLegacySnapshotsDefaultToChinese() {
        val captured = PromptAssembler.assemble(SOLVE_SYSTEM_PROMPT, "", AiReasoningEffort.HIGH,
            SolveMode.CHECK_WORK, answerLanguage = AppLanguage.JAPANESE)
        val decoded = Json.decodeFromString<PromptSegments>(Json.encodeToString(captured))
        assertEquals(captured, decoded)
        assertEquals(AppLanguage.JAPANESE, decoded.answerLanguage)
        assertSame(decoded, PromptAssembler.reusablePrompt(decoded, SolveMode.CHECK_WORK, ConversationMode.SOLVE))
        val legacy = Json.decodeFromString<PromptSegments>("""{"stableSystem":"old","volatileSystem":"time"}""")
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, legacy.answerLanguage)
    }
}
