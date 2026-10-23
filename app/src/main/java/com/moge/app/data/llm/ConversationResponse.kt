package com.moge.app.data.llm

import com.moge.app.data.parse.IncrementalReplyDecoder
import com.moge.app.data.parse.ParsedReply
import com.moge.app.data.parse.ReplyParser
import com.moge.app.domain.ConversationMode

/** Output rules shared by network, document and runtime paths. Chat text stays literal Markdown. */
class ConversationResponse(val mode: ConversationMode) {
    fun parse(raw: String, normalizeMarkdown: Boolean = false): ParsedReply = when (mode) {
        ConversationMode.SOLVE -> ReplyParser.parse(raw, normalizeMarkdown = normalizeMarkdown)
        ConversationMode.CHAT -> ParsedReply(reply = raw.trim())
    }

    fun visible(raw: String): String = when (mode) {
        ConversationMode.SOLVE -> com.moge.app.data.parse.decodeReplyIncremental(raw)
        ConversationMode.CHAT -> raw
    }

    fun reconcile(streamed: String, parsed: String): String = when (mode) {
        ConversationMode.SOLVE -> reconcileStreamedReply(streamed, parsed)
        ConversationMode.CHAT -> parsed.trim().ifBlank { streamed.trim() }
    }

    val continuationInstruction: String get() = when (mode) {
        ConversationMode.SOLVE -> CONTINUE_INSTRUCTION
        ConversationMode.CHAT -> "上面是回答已经写出的部分，可能仅包含尾部。请接着完成未写完的内容；" +
            "如果末尾的段落、代码或公式被截断，从该段开头完整重写再继续。避免重复已经完成的段落，直接输出正文。"
    }

    val recoveryInstruction: String get() = when (mode) {
        ConversationMode.SOLVE -> "请重新给出对原问题独立、完整的最终回答。不得只写‘见上’、‘如上’或引用内部思考。" +
            "正文保留必要的讲解、公式与推导。严格遵守系统要求，只输出完整 JSON 对象。"
        ConversationMode.CHAT -> "请根据用户的原始请求给出完整回答，直接输出正文。"
    }

    fun streamDecoder() = ConversationStreamDecoder(mode)
}

/** Never interpret JSON examples or braces in ordinary conversation as a solving envelope. */
class ConversationStreamDecoder(mode: ConversationMode) {
    private val structured = if (mode == ConversationMode.SOLVE) IncrementalReplyDecoder() else null
    private val plain = StringBuilder()

    fun append(delta: String): String = structured?.append(delta) ?: delta.also { plain.append(it) }
    fun finish(): String = structured?.finish() ?: plain.toString()
    fun reset() { structured?.reset(); plain.setLength(0) }
}
