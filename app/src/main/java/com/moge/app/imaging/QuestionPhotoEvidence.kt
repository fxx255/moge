package com.moge.app.imaging

import com.moge.app.domain.ConversationMode

/** Local OCR is only a gate, never a replacement for the model's transcription. */
internal data class QuestionPhotoEvidence(val text: String, val regions: List<TextRegion>) {
    val isQuestion: Boolean get() = regions.isNotEmpty() && looksLikeQuestion(text)
}

internal data class TextRegion(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal fun shouldPrepareQuestionPhoto(mode: ConversationMode, enabled: Boolean): Boolean =
    mode == ConversationMode.SOLVE && enabled

/** Prefer false negatives to changing scenery, receipts, or ordinary prose. */
internal fun looksLikeQuestion(text: String): Boolean {
    val compact = text.replace(Regex("\\s+"), "")
    if (compact.length < 5) return false
    if (questionWords.containsMatchIn(text)) return true
    val choices = Regex("(?:^|[\\s（(])([A-D])[.、．:：)）]").findAll(text)
        .map { it.groupValues[1] }.toSet()
    if (choices.size >= 3 && compact.length >= 16) return true
    return compact.any { it == '?' || it == '？' } &&
        Regex("[0-9a-zA-Z][+−*/÷×=<>≤≥]").containsMatchIn(compact)
}

private val questionWords = Regex(
    "求[出解证值]|求.{0,8}(?:值|概率|面积|体积|长度|范围)|计算|证明|已知|如图|下列|" +
        "解方程|解不等式|选择题|填空题|解答题|例题|习题|练习题|阅读.{0,8}(?:回答|完成)|" +
        "(?:^|\\b)(?:find|calculate|solve|prove|given|exercise|question)(?:\\b|$)",
    RegexOption.IGNORE_CASE,
)
