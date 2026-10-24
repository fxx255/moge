package com.moge.app.ui.export

import com.moge.app.R

import com.moge.app.ui.markdown.splitFigureSegments

/** Only the selected, completed question/answer snapshot belongs here. Never pass reasoning or logs. */
data class AnswerExportContent(
    val title: String,
    val questionText: String,
    val questionTranscript: String = "",
    val questionPhotos: List<String> = emptyList(),
    val answerText: String,
    val finalAnswer: String = "",
    val figurePaths: List<String> = emptyList(),
)

internal enum class ExportChoice { FULL, ANSWER_ONLY }

/** Twice the original pixel density, with the same layout proportions and length per page. */
internal object ExportLimits {
    const val SCALE = 2
    const val WIDTH = 1080 * SCALE
    // Prefer a single long image for sharing. Extremely long answers still
    // fall back to sequential pages in the renderer to keep allocations bounded.
    const val HEIGHT = 12000 * SCALE
    const val MARGIN = 72 * SCALE
    const val FOOTER = 64 * SCALE
    const val GAP = 24 * SCALE
    const val TEXT_SIZE = 34f * SCALE
    const val CONTENT_WIDTH = WIDTH - MARGIN * 2
    const val CONTENT_HEIGHT = HEIGHT - MARGIN * 2 - FOOTER
    const val SOURCE_CHUNK = 6000
    const val FORMULA_CHARS = 2048
    const val RENDER_BAND_HEIGHT = 512
    const val CACHE_DIRECTORY = "answer_exports"
}

internal data class VerticalSlice(val top: Int, val bottom: Int, val scale: Float = 1f) {
    val height: Int get() = kotlin.math.round((bottom - top) * scale).toInt().coerceAtLeast(1)
}

/** Break at line/row boundaries. A single oversized line is scaled intact, never cropped or skipped. */
internal fun paginateBoundaries(boundaries: List<Int>, capacity: Int): List<VerticalSlice> {
    require(capacity > 0)
    require(boundaries.isNotEmpty() && boundaries.first() == 0)
    require(boundaries.zipWithNext().all { (a, b) -> b > a })
    val result = mutableListOf<VerticalSlice>()
    var start = 0
    while (start < boundaries.lastIndex) {
        var end = start + 1
        while (end < boundaries.lastIndex && boundaries[end + 1] - boundaries[start] <= capacity) end++
        val height = boundaries[end] - boundaries[start]
        result += VerticalSlice(boundaries[start], boundaries[end], minOf(1f, capacity.toFloat() / height))
        start = end
    }
    return result
}

internal data class SourceChunk(val source: String, val plain: Boolean = false)

/** Bound native Layout/TeX work as well as bitmaps. Oversized blocks retain their entire source. */
internal fun boundedSourceChunks(source: String, limit: Int = ExportLimits.SOURCE_CHUNK): List<SourceChunk> {
    require(limit >= 2)
    if (source.length <= limit) return listOf(SourceChunk(source))
    // Paragraph boundaries inside math or code are not safe Markdown boundaries.
    val protected = Regex(
        "(?ms)^[ \\t]*(`{3,}|~{3,})[^\\n]*\\n.*?^[ \\t]*\\1[ \\t]*(?:\\n|$)" +
            "|(?s)(?<!\\\\)\\$\\$.*?(?<!\\\\)\\$\\$" +
            "|(?s)\\\\\\[.*?\\\\\\]" +
            "|(?s)\\\\\\(.*?\\\\\\)",
    ).findAll(source).map { it.range }.toList()
    val blocks = mutableListOf<String>()
    var cursor = 0
    Regex("\n[ \\t]*\n").findAll(source).forEach { match ->
        if (protected.none { match.range.first in it }) {
            val end = match.range.last + 1
            blocks += source.substring(cursor, end)
            cursor = end
        }
    }
    if (cursor < source.length) blocks += source.substring(cursor)
    val result = mutableListOf<SourceChunk>()
    var pending = ""
    for (block in blocks) {
        if (block.length > limit) {
            if (pending.isNotEmpty()) { result += SourceChunk(pending); pending = "" }
            // Arbitrary cuts cannot safely retain a fence, link or math delimiter. Display the
            // original text explicitly instead of reparsing halves and silently losing syntax.
            var offset = 0
            while (offset < block.length) {
                var end = minOf(block.length, offset + limit)
                if (end < block.length && block[end - 1].isHighSurrogate() && block[end].isLowSurrogate()) end--
                result += SourceChunk(block.substring(offset, end), plain = true)
                offset = end
            }
        } else if (pending.isEmpty()) pending = block
        else if (pending.length + block.length <= limit) pending += block
        else { result += SourceChunk(pending); pending = block }
    }
    if (pending.isNotEmpty()) result += SourceChunk(pending)
    return result
}

internal fun tableColumnGroups(columns: Int, maxColumns: Int = 3): List<IntRange> {
    require(columns > 0 && maxColumns > 0)
    return (0 until columns step maxColumns).map { it..minOf(columns - 1, it + maxColumns - 1) }
}

internal sealed interface ExportPart {
    data class Text(val source: String, val markdown: Boolean = true, val heading: Boolean = false) : ExportPart
    data class Image(val path: String, val label: String, val generated: Boolean) : ExportPart
}

internal fun exportParts(content: AnswerExportContent, choice: ExportChoice, strings: com.moge.app.core.TextResources? = null): List<ExportPart> = buildList {
    add(ExportPart.Text(content.title.ifBlank { (strings?.text(R.string.ui_question_solution_94121) ?: "题目与解答") }, markdown = false, heading = true))
    add(ExportPart.Text((strings?.text(R.string.ui_question_bfd7c) ?: "题目"), markdown = false, heading = true))
    if (content.questionText.isNotBlank()) add(ExportPart.Text(content.questionText, markdown = false))
    content.questionPhotos.forEachIndexed { index, path -> add(ExportPart.Image(path, (strings?.text(R.string.ui_question_photo_1_s_22570, index + 1) ?: "题目照片 ${index + 1}"), false)) }
    if (content.questionTranscript.isNotBlank()) {
        add(ExportPart.Text((strings?.text(R.string.ui_recognized_text_available_145e8) ?: "已有识别文本"), markdown = false, heading = true))
        add(ExportPart.Text(content.questionTranscript, markdown = false))
    }
    add(ExportPart.Text(if (choice == ExportChoice.FULL) (strings?.text(R.string.ui_full_solution_b8fde) ?: "完整解答") else (strings?.text(R.string.ui_final_answer_e1c1c) ?: "最终答案"), markdown = false, heading = true))
    val used = mutableSetOf<Int>()
    fun answer(source: String) {
        splitFigureSegments(source, content.figurePaths.size).forEach { segment ->
            val index = segment.figureIndex
            if (index != null) {
                if (used.add(index)) add(ExportPart.Image(content.figurePaths[index], (strings?.text(R.string.ui_generated_figure_1_s_3af49, index + 1) ?: "生成图 ${index + 1}"), true))
            } else if (segment.text.isNotBlank()) {
                add(ExportPart.Text(segment.text))
            }
        }
    }
    if (choice == ExportChoice.ANSWER_ONLY) {
        require(content.finalAnswer.isNotBlank()) { (strings?.text(R.string.ui_no_separate_final_answer_export_the_full_solution_ec9a3) ?: "这条回答没有独立的最终答案，请导出完整解答") }
        answer(content.finalAnswer)
    } else {
        if (content.finalAnswer.isNotBlank() && content.finalAnswer.trim() != content.answerText.trim()) {
            answer(content.finalAnswer)
            add(ExportPart.Text((strings?.text(R.string.ui_solution_steps_4feb8) ?: "解答过程"), markdown = false, heading = true))
        }
        answer(content.answerText)
        content.figurePaths.forEachIndexed { index, path ->
            if (index !in used) add(ExportPart.Image(path, (strings?.text(R.string.ui_generated_figure_1_s_3af49, index + 1) ?: "生成图 ${index + 1}"), true))
        }
    }
}
