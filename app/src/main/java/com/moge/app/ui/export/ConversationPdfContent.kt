package com.moge.app.ui.export

import com.moge.app.R
import com.moge.app.core.TextResources
import com.moge.app.data.db.ConversationExportSnapshot
import com.moge.app.data.db.RequestRepository

/** Export the active visible branch, including follow-ups and explicitly labelled partial replies. */
internal fun conversationPdfParts(
    snapshot: ConversationExportSnapshot,
    strings: TextResources,
    documentName: (String) -> String,
): List<ExportPart> = buildList {
    fun documents(json: String) {
        RequestRepository.decodePathListStrict(json).forEach {
            add(ExportPart.Text(strings.text(R.string.pdf_attachment, documentName(it)), markdown = false))
        }
    }
    snapshot.notebook?.let { entry ->
        documents(entry.documentPaths)
        addAll(exportParts(AnswerExportContent(entry.title, entry.questionText, entry.questionTranscript,
            RequestRepository.decodePathListStrict(entry.questionImagePaths), entry.answerText, entry.finalAnswer,
            RequestRepository.decodePathListStrict(entry.figurePaths)), ExportChoice.FULL, strings).drop(1))
        return@buildList
    }
    val requests = snapshot.requests.associateBy { it.answerMessageId }
    var turn = 0
    for (message in snapshot.messages) {
        if (message.role == "user") {
            turn++
            add(ExportPart.Text(strings.text(R.string.pdf_question_number, turn), markdown = false, heading = true))
            documents(message.documentPaths)
            val question = message.displayContent ?: message.content
            if (question.isNotBlank()) add(exportQuestionText(question))
            RequestRepository.decodePathListStrict(message.imagePaths).forEachIndexed { index, path ->
                add(ExportPart.Image(path, strings.text(R.string.ui_question_photo_1_s_22570, index + 1), false))
            }
            if (message.transcript.isNotBlank() && message.transcript != message.content) {
                add(ExportPart.Text(strings.text(R.string.ui_recognized_text_e601e), markdown = false, heading = true))
                add(exportQuestionText(message.transcript))
            }
        } else if (message.role == "assistant") {
            add(ExportPart.Text(strings.text(R.string.pdf_answer_number, turn), markdown = false, heading = true))
            val request = requests[message.id]
            val partial = request != null && request.status != "COMPLETED"
            if (partial) {
                add(ExportPart.Text(strings.text(R.string.pdf_partial_answer), markdown = false))
                val text = request!!.partialText.ifBlank { message.displayContent ?: message.content }
                // A missing live figure must remain visible as source rather than disappear.
                add(ExportPart.Text(text.ifBlank { strings.text(R.string.pdf_no_answer) }))
            } else {
                documents(message.documentPaths)
                addAll(exportAnswerParts(AnswerExportContent("", "", answerText = message.displayContent ?: message.content,
                    finalAnswer = message.finalAnswer, figurePaths = RequestRepository.decodePathListStrict(message.imagePaths)),
                    ExportChoice.FULL, strings))
            }
        }
    }
}
