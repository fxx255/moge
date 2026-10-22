package com.moge.app.data.llm

import com.moge.app.data.credential.AiReasoningEffort
import com.moge.app.domain.SolveMode
import com.moge.app.domain.ConversationMode
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 发给模型的一条消息。role 只允许 user / assistant。
 *
 * [imageBase64s] 只在本次请求里使用：接口是无状态的，追问时要把历史图片重新附上；
 * base64 不落库。
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val imageBase64s: List<String> = emptyList(),
    val documentPaths: List<String> = emptyList(),
    val documentReadRequired: Boolean = documentPaths.isNotEmpty(),
)

/**
 * 请求前缀的**稳定 / 易变**分段。
 *
 * ```
 * [稳定前缀]  固定协议 → 称呼 → 推理设定
 * [稳定历史]  既有对话历史
 * [易变尾部]  当前时间 → 解题模式 → 当前问题
 * ```
 *
 * 分钟级时间和每题可变的解题模式都放在尾部，稳定前缀逐字不变，供应商的 prompt cache 才能命中。
 * 这里只保证给定输入下输出确定（纯函数 + 注入 [Clock]），不承诺具体命中率。
 */
data class PromptSegments(
    val stableSystem: String,
    val volatileSystem: String,
)

object PromptAssembler {

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    fun assemble(
        protocol: String,
        nickname: String,
        reasoningEffort: AiReasoningEffort,
        solveMode: SolveMode,
        clock: Clock = Clock.systemDefaultZone(),
        conversationMode: ConversationMode = ConversationMode.SOLVE,
    ): PromptSegments {
        val stableSystem = buildString {
            append(if (conversationMode == ConversationMode.CHAT) CHAT_PROMPT else protocol)
            val name = nickname.trim()
            if (name.isNotEmpty()) {
                append("\n\n## 称呼\n")
                append("用「").append(name).append("」称呼用户，自然使用即可，不要每段都叫。")
            }
            if (conversationMode == ConversationMode.SOLVE) {
                append("\n\n## 推理设定\n").append(reasoningInstruction(reasoningEffort))
            }
        }
        val volatileSystem = buildString {
            append("## 当前时间\n")
            val now = LocalDateTime.now(clock)
            val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE)
            append("现在是 ").append(now.format(TIME_FORMAT)).append("（").append(weekday).append("）。")
            if (conversationMode == ConversationMode.SOLVE) {
                append("\n\n## 解题模式\n").append(solveMode.instruction)
                append("\n追问、补图或补充条件时，结合前文，围绕本轮问题作答。")
                append("\n用户明确指定正文格式或只要答案时按其要求安排内容，外层 JSON 格式保持不变。")
                append("\n时间与解题模式不改变系统规定的 JSON 协议和正文边界。")
            }
        }
        return PromptSegments(stableSystem, volatileSystem)
    }

    private const val CHAT_PROMPT = "你是墨格中的对话助手。根据用户的意图自然、准确地回答；" +
        "不确定时坦诚说明，信息不足时适当澄清。默认使用用户的语言，直接输出正文，按需使用 Markdown。"
}
