package com.moge.app.ui.export

import android.app.Application
import com.moge.app.ui.localization.AppStrings
import com.moge.app.data.db.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN")
class ConversationPdfContentTest {
    @Test fun `notebook keeps full answer question photos and figure slots without repeated title`() {
        val entry = NotebookEntryEntity(sourceConversationId = "c", sourceQuestionId = "q", sourceAnswerId = "a",
            title = "独立快照标题", questionText = "题目", questionTranscript = "识别文本", questionImagePaths = "[\"photo.jpg\"]",
            answerText = "完整解析 [[FIGURE:2]]", finalAnswer = "答案", figurePaths = "[\"\",\"figure.png\"]")
        val parts = conversationPdfParts(ConversationExportSnapshot(entry.title, notebook = entry), AppStrings(RuntimeEnvironment.getApplication())) { it }
        assertFalse(parts.filterIsInstance<ExportPart.Text>().any { it.source == entry.title })
        assertTrue(parts.filterIsInstance<ExportPart.Text>().any { it.source == "识别文本" })
        assertTrue(parts.filterIsInstance<ExportPart.Text>().any { it.source == "答案" })
        assertEquals(listOf("photo.jpg", "figure.png", ""), parts.filterIsInstance<ExportPart.Image>().map { it.path })
    }

    @Test fun `history preserves message order displayed text attachments and incomplete reply status`() {
        val q = MessageEntity(id = "q", conversationId = "c", role = "user", content = "问题", documentPaths = "[\"doc.pdf\"]")
        val a = MessageEntity(id = "a", conversationId = "c", role = "assistant", content = "RAW_JSON", displayContent = "已显示的完整答案")
        val follow = q.copy(id = "q2", content = "追问", documentPaths = "")
        val partial = a.copy(id = "a2", displayContent = "过期正文")
        val request = RequestEntity(conversationId = "c", userMessageId = "q2", answerMessageId = "a2", attemptId = "attempt",
            status = "INTERRUPTED", partialText = "已保存的部分答案")
        val parts = conversationPdfParts(ConversationExportSnapshot("c", listOf(q, a, follow, partial), requests = listOf(request)),
            AppStrings(RuntimeEnvironment.getApplication())) { "课堂讲义.pdf" }
        val texts = parts.filterIsInstance<ExportPart.Text>().map { it.source }
        assertTrue(texts.contains("附件：课堂讲义.pdf"))
        assertTrue(texts.indexOf("已显示的完整答案") < texts.indexOf("追问"))
        assertTrue(texts.contains("已保存的部分答案"))
        assertTrue(texts.any { it.contains("尚未完成") })
        assertFalse(texts.any { it.contains("RAW_JSON") || it.contains("过期正文") })
    }
}
