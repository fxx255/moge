package com.moge.app.domain

/**
 * 解题模式。指令随本轮用户消息注入（见 [com.moge.app.data.llm.PromptAssembler]），
 * 切换模式不改稳定前缀，prompt cache 仍能命中。
 */
enum class SolveMode(val label: String, val instruction: String) {
    DETAILED(
        "标准解答",
        "本轮使用「标准解答」模式：根据用户输入、题型与复杂程度安排结构和篇幅，不固定为四段。" +
            "解题时写清必要依据、公式与关键步骤，并明确最终结论；简短问答直接回答。" +
            "只有确有帮助时才补充易错点，用户指定格式时优先遵从。",
    ),
    CHECK_WORK(
        "帮我查错",
        "本题使用「帮我查错」模式：用户给出的是自己的解答。逐步核对，对每一处错误" +
            "用「❌ 第 n 步」标出错在哪里、为什么错、应如何改；正确的步骤用「✅」简短确认。" +
            "最后给出正确答案。看不到用户解答时，先说明并按「标准解答」模式回应。",
    ),
    ;

    companion object {
        fun fromName(raw: String?): SolveMode? = raw?.let { name -> entries.firstOrNull { it.name == name } }
    }
}
