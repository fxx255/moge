package com.moge.app.domain

/**
 * 解题模式。指令注入在易变 system 段（见 [com.moge.app.data.llm.PromptAssembler]），
 * 切换模式不改稳定前缀，prompt cache 仍能命中。
 */
enum class SolveMode(val label: String, val instruction: String) {
    DETAILED(
        "标准解答",
        "本题使用「标准解答」模式：按题目需要说明依据和关键步骤，给出明确答案；结构随题型安排。",
    ),
    CHECK_WORK(
        "帮我查错",
        "本题使用「帮我查错」模式：核对用户作答，指出错误、依据和改法，给出正确答案；未提供作答时按标准解答处理。",
    ),
    ;

    companion object {
        fun fromName(raw: String?): SolveMode? = raw?.let { name -> entries.firstOrNull { it.name == name } }
    }
}
