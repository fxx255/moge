package com.moge.app.data.llm

import com.moge.app.data.parse.findFigureAnchors
import com.moge.app.data.parse.replaceFigureAnchors

/**
 * 生成流程的共享纯逻辑：界面与 GenerationManager 共用**同一份实现**，
 * 避免「界面按一套规则合并、管理器按另一套规则合并」这种会悄悄撕坏长回答的分裂。
 */

/** 发给模型的历史上限：条数与总字符双限，超长时从最早的消息开始丢。 */
const val HISTORY_MAX_MESSAGES = 24
const val HISTORY_MAX_CHARS = 24_000

/** 自动续写时回传给模型的「已生成内容」尾部长度。 */
const val CONTINUATION_ECHO_CHARS = 12_000

/** 续写重试的内部指令（不写入会话）。 */
const val CONTINUE_INSTRUCTION =
    "上面是这个长回答已经写出的部分（可能只截取了尾部）。请直接接着写未完成的内容，" +
        "若末尾段落或公式被截断，请从该段落开头重新写完整段落（保持开头一致），再继续后文；" +
        "不要只猜测补一个公式后缀。保留成对的公式定界符和加粗标记，不写‘接上文’。" +
        "不要重复更早已完成的段落，也不要提前收尾——除非确实已经全部写完。仍只输出独立完整的 JSON 对象。"

/** 单轮「有效产出」阈值：低于它视为模型在收尾或打转。 */
const val MIN_MEANINGFUL_CHARS = 200

/**
 * Models occasionally finish a long reasoning turn with a reference such as
 * "见上" instead of a self-contained answer.  Treat these exact short replies
 * as placeholders so they cannot overwrite a useful streamed answer.
 */
fun isPlaceholderReply(text: String): Boolean {
    val normalized = text.trim()
        .replace(Regex("[。！？!?：:，,、\\s]+$"), "")
    return normalized in setOf(
        "见上", "如上", "同上", "见前文", "见上文", "如上所述", "见图", "见下图",
    )
}

/** Prefer a meaningful streamed answer over a short placeholder from final JSON. */
fun reconcileStreamedReply(streamed: String, parsed: String): String {
    val streamedText = streamed.trim()
    val parsedText = parsed.trim()
    return when {
        // A provider can emit a useful short reply in the stream and then put
        // a stale "见上"/"如上" value in the final JSON field.  The old
        // length gate (200 chars) let that placeholder overwrite short but
        // complete answers.  Only reject an equally short placeholder stream;
        // any other visible stream text is the answer the user saw arriving.
        isPlaceholderReply(parsedText) && streamedText.isNotBlank() &&
            !isPlaceholderReply(streamedText) -> streamedText
        // Neither the final JSON nor the streamed reply contains an answer.
        // Returning the placeholder here would let the generation finish as a
        // successful two-character response and remove the retry entry.
        isPlaceholderReply(parsedText) || (parsedText.isBlank() && isPlaceholderReply(streamedText)) -> ""
        parsedText.isBlank() && streamedText.isNotBlank() -> streamedText
        else -> parsedText
    }
}

/** 累计输出绝对熔断长度。 */
const val TOTAL_CHAR_FUSE = 240_000


/**
 * 合并原始 reply 片段，含「续写重放了未完成段落」的情况。
 *
 * 必须在 Markdown 规范化**之前**做，否则重放段落会被当成新内容重复追加。
 */
fun mergeContinuation(previous: String, next: String): String {
    if (previous.isEmpty()) return next
    if (next.isEmpty()) return previous
    val incoming = next.trimStart()
    val paragraphStart = previous.lastIndexOf("\n\n").let { if (it < 0) 0 else it + 2 }
    val tail = previous.substring(paragraphStart)
    // 续写指令要求重写未完成段落，因此需要足够长的公共前缀才算「同一段」；
    // 阈值定小了会让 `(1)`、`解：` 这类常见开头被误判成重放。
    val common = tail.commonPrefixWith(incoming).length
    if (common >= 12) return previous.substring(0, paragraphStart) + incoming
    // 有些端点会原样重放上一段结尾。
    for (length in minOf(previous.length, incoming.length) downTo 16) {
        if (previous.regionMatches(previous.length - length, incoming, 0, length)) {
            return previous + incoming.substring(length)
        }
    }
    return previous + next
}

/** 兼容模型把新回答的完整图序列接在历史编号之后的情况。仅连续完整序列可可靠重排。 */
fun localizeFigureAnchors(text: String, figureCount: Int): String {
    if (figureCount <= 0) return text
    val numbers = findFigureAnchors(text).mapNotNull { it.number }
        .distinct().toList()
    if (numbers.size != figureCount || numbers.firstOrNull() == 1 ||
        numbers.zipWithNext().any { (a, b) -> b.toLong() != a.toLong() + 1 }) return text
    val first = numbers.firstOrNull() ?: return text
    if (first < 1 || numbers.last() <= figureCount) return text
    return replaceFigureAnchors(text) { anchor ->
        val n = anchor.number ?: return@replaceFigureAnchors text.substring(anchor.range)
        if (n <= 0) return@replaceFigureAnchors text.substring(anchor.range)
        "[[FIGURE:${n - first + 1}]]"
    }
}

/**
 * 把续写轮里的 `[[FIGURE:n]]` 锚点按已累积的图表数平移。
 *
 * 续写轮里模型不知道前面已画过几张图，锚点通常从 1 重新编号；
 * 不平移的话第 2 轮的 `[[FIGURE:1]]` 会错误地指到第 1 轮的第一张图上。
 *
 * 这是**唯一实现**：界面与管理器共用（`FigureAnchorOffsetTest`）。
 */
fun offsetFigureAnchors(text: String, base: Int): String {
    if (base <= 0 || !text.contains("[[")) return text
    return replaceFigureAnchors(text) { anchor ->
        val number = anchor.number ?: return@replaceFigureAnchors text.substring(anchor.range)
        if (number <= 0) return@replaceFigureAnchors text.substring(anchor.range)
        val shifted = number.toLong() + base
        if (shifted > Int.MAX_VALUE) return@replaceFigureAnchors text.substring(anchor.range)
        "[[FIGURE:$shifted]]"
    }
}


/**
 * 截断收尾：去掉悬空的加粗标记、补齐未闭合的公式块，并附加截断说明。
 *
 * 与锚点平移一样，这是界面与管理器共用的**唯一实现**。
 */
fun polishTruncatedTail(text: String): String {
    var t = text.trimEnd()
    if (t.endsWith("**")) t = t.removeSuffix("**").trimEnd()
    // $$ 出现奇数次 → 有未闭合的公式块，补一个闭合
    if (t.split("$$").size % 2 == 0) t += "\n$$"
    return "$t\n\n——（回答达到单次输出上限被截断）"
}

/**
 * 是否应当继续自动续写。
 *
 * 三道刹车：① 模型确实被输出上限截断；② 没有连续两轮几乎没内容；
 * ③ 未超过用户的续写轮数上限与总熔断长度。[barrenRounds] 为已连续空转轮数。
 */
fun shouldContinueGeneration(
    truncated: Boolean,
    barrenRounds: Int,
    continuation: Int,
    maxContinuations: Int,
    accumulatedChars: Int,
): Boolean = truncated &&
    barrenRounds < 2 &&
    continuation < maxContinuations &&
    accumulatedChars < TOTAL_CHAR_FUSE

/** 单轮产出是否算「有效内容」（低于阈值视为收尾或打转）。 */
fun isMeaningfulRound(replyText: String): Boolean = replyText.length >= MIN_MEANINGFUL_CHARS
