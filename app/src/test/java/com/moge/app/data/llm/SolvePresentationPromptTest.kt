package com.moge.app.data.llm

import com.moge.app.data.credential.AiReasoningEffort
import com.moge.app.domain.ConversationMode
import com.moge.app.domain.SolveMode
import org.junit.Assert.*
import org.junit.Test

class SolvePresentationPromptTest {
    @Test fun `both solving contracts keep conclusions and explanation free of duplicate greetings`() {
        for (contract in listOf(SOLVE_SYSTEM_PROMPT, LEGACY_SOLVE_SYSTEM_PROMPT)) {
            assertTrue(contract.contains("不放称呼、问候"))
            assertTrue(contract.contains("reply 直接从依据或第一步承接讲解"))
            assertTrue(contract.contains("不要按假定的手机宽度预先拆行"))
            assertTrue(contract.contains("矩阵、分段函数和方程组保留必要的行结构"))
        }
        val solve = prompt(ConversationMode.SOLVE)
        assertTrue(solve.contains("用户的称呼是「小墨」"))
        assertTrue(solve.contains("answer 不使用称呼或问候"))
        assertTrue(solve.contains("同一轮最多一次"))
        val chat = prompt(ConversationMode.CHAT)
        assertTrue(chat.contains("同一轮最多称呼一次"))
        assertFalse(chat.contains("answer 不使用"))
    }

    private fun prompt(mode: ConversationMode) = PromptAssembler.assemble(SOLVE_SYSTEM_PROMPT, "小墨",
        AiReasoningEffort.LOW, SolveMode.DETAILED, conversationMode = mode).stableSystem
}
