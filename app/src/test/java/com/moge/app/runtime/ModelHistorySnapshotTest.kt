package com.moge.app.runtime

import com.moge.app.data.db.MessageEntity
import com.moge.app.data.db.RequestEntity
import com.moge.app.data.llm.PromptSegments
import com.moge.app.data.llm.RequestSnapshot
import com.moge.app.data.llm.SnapshotCodec
import com.moge.app.data.llm.SnapshotHistoryMessage
import com.moge.app.domain.ConversationMode
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ModelHistorySnapshotTest {
    private fun request(user: String, answer: String, snapshot: RequestSnapshot, attempt: String = "a") = RequestEntity(
        conversationId = "c", userMessageId = user, answerMessageId = answer, attemptId = attempt,
        status = "COMPLETED", attachmentPaths = "[\"original.png\"]", snapshotJson = SnapshotCodec.encode(snapshot),
    )

    @Test fun `transcribed and captionless turns reuse their actual prepared text and instructions`() {
        val source = listOf(MessageEntity(id = "u", conversationId = "c", role = "user", content = ""))
        val prepared = RequestSnapshot(prepared = true, modelHistoryVersion = 1,
            sourceUserText = "补图的非空提示与转写结果", photoRoute = RequestSnapshot.PHOTO_ROUTE_TRANSCRIBE,
            prompt = PromptSegments("固定规则", "原时间与模式"))
        val textOnly = ModelHistorySnapshot.capture(source, listOf(request("u", "a", prepared)), ConversationMode.SOLVE).single()
        assertEquals(prepared.sourceUserText, textOnly.text)
        assertEquals("原时间与模式", textOnly.turnContext)
        assertTrue(textOnly.imagePaths.isEmpty())
        val photo = ModelHistorySnapshot.capture(source, listOf(request("u", "a",
            prepared.copy(photoRoute = RequestSnapshot.PHOTO_ROUTE_DIRECT))), ConversationMode.SOLVE).single()
        assertEquals(listOf("original.png"), photo.imagePaths)
        assertEquals(prepared.sourceUserText, photo.text)
    }

    @Test fun `display edits retain frozen history while new attempts invalidate old answer text`() {
        val source = listOf(MessageEntity(id = "answer", conversationId = "c", role = "assistant", content = "新回答"))
        val prior = request("u", "answer", RequestSnapshot())
        val frozen = SnapshotHistoryMessage(id = "answer", role = "assistant", text = "已发送的回答", sourceAttemptId = "a")
        val later = request("u2", "a2", RequestSnapshot(modelHistoryVersion = 1, originalHistory = listOf(frozen)))
        assertEquals("已发送的回答", ModelHistorySnapshot.capture(source, listOf(prior, later), ConversationMode.SOLVE).single().text)
        assertEquals("新回答", ModelHistorySnapshot.capture(source, listOf(prior.copy(attemptId = "b"), later), ConversationMode.SOLVE).single().text)
    }

    @Test fun `individual assistant messages never duplicate previously merged continuations and remain bounded`() {
        val source = (1..30).map { index -> MessageEntity(id = "$index", conversationId = "c", role = "assistant",
            content = "回答$index", createdAt = Instant.ofEpochMilli(index.toLong())) }
        val first = ModelHistorySnapshot.capture(source, emptyList(), ConversationMode.CHAT)
        assertEquals(24, first.size)
        val snapshot = request("u", "a", RequestSnapshot(modelHistoryVersion = 1, originalHistory = first))
        val second = ModelHistorySnapshot.capture(source, listOf(snapshot), ConversationMode.CHAT)
        assertEquals(first, second)
        assertEquals(24, second.map { it.id }.distinct().size)
        val large = source.last().copy(content = "长".repeat(30_000))
        assertEquals(listOf("30"), ModelHistorySnapshot.capture(source.dropLast(1) + large, emptyList(), ConversationMode.SOLVE).map { it.id })
    }
}
