package com.moge.app.domain

/**
 * 解题模式。指令注入在易变 system 段（见 [com.moge.app.data.llm.PromptAssembler]），
 * 切换模式不改稳定前缀，prompt cache 仍能命中。
 */
enum class SolveMode(val label: String, val instruction: String) {
    DETAILED(
        "标准解答",
        "本题使用「详细讲解」模式：按「分析 → 分步解答 → 最终答案 → 易错点」组织正文。" +
            "每一步写清依据与必要公式，最终答案单独成段并加粗。",
    ),
    CHECK_WORK(
        "帮我查错",
        "本题使用「帮我查错」模式：用户给出的是自己的解答。逐步核对，对每一处错误" +
            "用「❌ 第 n 步」标出错在哪里、为什么错、应如何改；正确的步骤用「✅」简短确认。" +
            "最后给出正确答案。看不到用户解答时，先说明并按「详细讲解」解题。",
    ),
    ;

    companion object {
        fun fromName(raw: String?): SolveMode? = raw?.let { name -> entries.firstOrNull { it.name == name } }
    }
}
