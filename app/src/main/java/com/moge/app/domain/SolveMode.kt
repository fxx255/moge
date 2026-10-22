package com.moge.app.domain

/**
 * 解题模式。指令注入在易变 system 段（见 [com.moge.app.data.llm.PromptAssembler]），
 * 切换模式不改稳定前缀，prompt cache 仍能命中。
 */
enum class SolveMode(val label: String, val instruction: String) {
    DETAILED(
        "标准解答",
        "本题使用「标准解答」模式：按「分析 → 分步解答 → 最终答案 → 易错点」组织正文，" +
            "各部分用 Markdown 小标题分开。分析简述解题方法；分步解答保留题号并写清依据，" +
            "内容依学科、题型安排。最终答案单独成段并加粗；易错点只写与本题相关的提醒。",
    ),
    CHECK_WORK(
        "帮我查错",
        "本题使用「帮我查错」模式：按用户原有步骤逐项核对。错误用「❌ 第 n 步」说明错在哪里、" +
            "为什么错及如何改；正确步骤用「✅」简短确认。最后单独给出正确答案和相关易错点。" +
            "未提供用户作答时说明情况，并按「分析 → 分步解答 → 最终答案 → 易错点」给出标准解答。",
    ),
    ;

    companion object {
        fun fromName(raw: String?): SolveMode? = raw?.let { name -> entries.firstOrNull { it.name == name } }
    }
}
