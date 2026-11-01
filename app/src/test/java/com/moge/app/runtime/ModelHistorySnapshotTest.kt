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

    @Test fun `individual assistant messages retain full history without duplicating merged continuations`() {
        val source = (1..30).map { index -> MessageEntity(id = "$index", conversationId = "c", role = "assistant",
            content = "回答$index", createdAt = Instant.ofEpochMilli(index.toLong())) }
        val first = ModelHistorySnapshot.capture(source, emptyList(), ConversationMode.CHAT)
        assertEquals(30, first.size)
        val snapshot = request("u", "a", RequestSnapshot(modelHistoryVersion = 1, originalHistory = first))
        val second = ModelHistorySnapshot.capture(source, listOf(snapshot), ConversationMode.CHAT)
        assertEquals(first, second)
        assertEquals(30, second.map { it.id }.distinct().size)
        val large = source.last().copy(content = "长".repeat(30_000))
        val longHistory = ModelHistorySnapshot.capture(source.dropLast(1) + large, emptyList(), ConversationMode.SOLVE)
        assertEquals(source.map { it.id }, longHistory.map { it.id })
        assertEquals(large.content, longHistory.last().text)
    }

    @Test fun `appending beyond former history limits preserves frozen text images and turn context`() {
        val source = (1..15).flatMap { index -> listOf(
            MessageEntity(id = "u$index", conversationId = "c", role = "user", content = "问题$index"),
            MessageEntity(id = "a$index", conversationId = "c", role = "assistant",
                content = if (index == 1) "长".repeat(30_000) else "回答$index"),
        ) }
        val original = request("u1", "a1", RequestSnapshot(prepared = true, modelHistoryVersion = 1,
            sourceUserText = "实际发送的首轮问题", photoRoute = RequestSnapshot.PHOTO_ROUTE_DIRECT,
            prompt = PromptSegments("固定规则", "首轮时间与模式")))
            .copy(attachmentPaths = "[\"first.png\",\"second.png\",\"third.png\"]")
        val prefix = ModelHistorySnapshot.capture(source, listOf(original), ConversationMode.SOLVE)
        assertEquals(30, prefix.size)
        assertEquals(listOf("first.png", "second.png", "third.png"), prefix.first().imagePaths)
        val later = request("next-user", "next-answer",
            RequestSnapshot(modelHistoryVersion = 1, originalHistory = prefix))
            .copy(createdAt = original.createdAt.plusMillis(1))
        val appended = ModelHistorySnapshot.capture(
            source.map { it.copy(content = "显示内容已更新") } +
                MessageEntity(id = "next-user", conversationId = "c", role = "user", content = "追加问题"),
            listOf(original, later), ConversationMode.SOLVE)
        assertEquals(prefix, appended.dropLast(1))
        assertEquals("追加问题", appended.last().text)
    }
}
