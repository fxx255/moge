package com.moge.app.ui.solve

import com.moge.app.data.db.MessageEntity
import com.moge.app.data.db.RequestEntity
import com.moge.app.domain.FailureKind
import com.moge.app.domain.RequestStatus
import com.moge.app.runtime.GenerationManager.ActiveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [buildSolveItems] 的合成规则：活动状态围栏、部分正文来源、重发入口只给最后一问。 */
class SolveItemsTest {

    private fun user(id: String, text: String = "问") = MessageEntity(id = id, conversationId = "c", role = "user", content = text)
    private fun answer(id: String, text: String = "") = MessageEntity(id = id, conversationId = "c", role = "assistant", content = text)
    private fun request(answerId: String, status: RequestStatus, partial: String = "", kind: FailureKind? = null) = RequestEntity(
        requestId = "r-$answerId", conversationId = "c", userMessageId = "u", answerMessageId = answerId,
        attemptId = "att", status = status.name, partialText = partial,
        failureKind = kind?.name.orEmpty(), failureMessage = if (kind != null) "断了" else "",
    )

    @Test
    fun `new question has no items`() {
        assertTrue(buildSolveItems(null, listOf(user("u")), emptyList(), ActiveState()).isEmpty())
    }

    @Test
    fun `first question is the card and later ones are follow ups`() {
        val items = buildSolveItems("c", listOf(user("u1"), answer("a1", "答"), user("u2")), emptyList(), ActiveState())
        assertTrue((items[0] as SolveItem.Question).isFirst)
        assertFalse((items[2] as SolveItem.Question).isFirst)
        assertEquals(AnswerState.COMPLETED, (items[1] as SolveItem.Answer).state)
    }

    @Test
    fun `display content wins over model text for user messages`() {
        val message = user("u1", "转写后的长题目").copy(displayContent = "原问题")
        val item = buildSolveItems("c", listOf(message), emptyList(), ActiveState())[0] as SolveItem.Question
        assertEquals("原问题", item.text)
    }

    @Test
    fun `completed answer uses repaired display while original provider text stays in message`() {
        val message = answer("a1", "原始绘图协议").copy(displayContent = "恢复后的讲解", imagePaths = "[\"/figure.png\"]")
        val item = buildSolveItems("c", listOf(message), emptyList(), ActiveState())[0] as SolveItem.Answer
        assertEquals("恢复后的讲解", item.text)
        assertEquals(listOf("/figure.png"), item.figurePaths)
        assertEquals("原始绘图协议", message.content)
    }

    @Test
    fun `preparing phase without text shows preparing`() {
        val active = ActiveState("r", "att", "c", RequestStatus.PREPARING, answerMessageId = "a1")
        val item = buildSolveItems("c", listOf(user("u1"), answer("a1")), emptyList(), active)[1] as SolveItem.Answer
        assertEquals(AnswerState.PREPARING, item.state)
    }

    @Test
    fun `interrupted only the last answer is retryable`() {
        val messages = listOf(user("u1"), answer("a1"), user("u2"), answer("a2"))
        val reqs = listOf(
            request("a1", RequestStatus.INTERRUPTED, "旧半截", FailureKind.NETWORK),
            request("a2", RequestStatus.INTERRUPTED, "新半截", FailureKind.SERVER),
        )
        val answers = buildSolveItems("c", messages, reqs, ActiveState()).filterIsInstance<SolveItem.Answer>()
        assertNull(answers[0].retryRequestId)
        assertEquals("r-a2", answers[1].retryRequestId)
        assertEquals("新半截", answers[1].text)
        assertEquals(AnswerState.FAILED, answers[1].state)
    }

    @Test
    fun `non retryable failure kind gets no retry entry`() {
        val reqs = listOf(request("a1", RequestStatus.INTERRUPTED, kind = FailureKind.CANCELLED))
        val item = buildSolveItems("c", listOf(user("u1"), answer("a1")), reqs, ActiveState())[1] as SolveItem.Answer
        assertNull(item.retryRequestId)
    }

    @Test
    fun `active state of another answer does not override`() {
        val active = ActiveState("r", "att", "c", RequestStatus.RUNNING, answerMessageId = "a2", partialText = "别的")
        val item = buildSolveItems("c", listOf(user("u1"), answer("a1", "完成")), emptyList(), active)[1] as SolveItem.Answer
        assertEquals("完成", item.text)
        assertEquals(AnswerState.COMPLETED, item.state)
    }

    @Test
    fun `only the last completed or stopped answer can be regenerated`() {
        val messages = listOf(user("u1"), answer("a1", "一"), user("u2"), answer("a2", "二"))
        val reqs = listOf(request("a1", RequestStatus.COMPLETED), request("a2", RequestStatus.CANCELLED, "停"))
        val answers = buildSolveItems("c", messages, reqs, ActiveState()).filterIsInstance<SolveItem.Answer>()
        assertNull(answers[0].regenerateRequestId)
        assertEquals("r-a2", answers[1].regenerateRequestId)
        assertEquals(AnswerState.STOPPED, answers[1].state)
    }

    @Test
    fun `answer without request record cannot be regenerated`() {
        val item = buildSolveItems("c", listOf(user("u1"), answer("a1", "完成")), emptyList(), ActiveState())[1] as SolveItem.Answer
        assertNull(item.regenerateRequestId)
    }
}
