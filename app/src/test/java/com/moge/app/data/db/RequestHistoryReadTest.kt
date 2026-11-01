package com.moge.app.data.db

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RequestHistoryReadTest {
    @Test fun `long histories stay within sqlite bindings and return each request once in time order`() = runBlocking {
        val ids = (1..1_201).map { "m$it" }
        val records = listOf(
            request("across-batches", "m1", "m1201", 3),
            request("earliest", "m801", "m802", 1),
            request("middle", "m401", "m402", 2),
        )
        val batches = mutableListOf<List<String>>()
        val dao = mockk<RequestDao> {
            coEvery { getRequestsForMessages("c", any()) } answers {
                val batch = secondArg<List<String>>()
                batches += batch
                records.filter { it.userMessageId in batch || it.answerMessageId in batch }
            }
        }
        val found = RequestRepository(dao, Dispatchers.Unconfined).forMessages("c", ids + ids.first())
        assertEquals(records.sortedBy { it.createdAt }, found)
        assertTrue(batches.all { it.size * 2 + 1 <= 999 })
        assertEquals(ids, batches.flatten())
    }

    @Test fun `empty history avoids querying requests`() = runBlocking {
        val dao = mockk<RequestDao>()
        assertTrue(RequestRepository(dao, Dispatchers.Unconfined).forMessages("c", emptyList()).isEmpty())
        coVerify(exactly = 0) { dao.getRequestsForMessages(any(), any()) }
    }

    private fun request(id: String, user: String, answer: String, time: Long) = RequestEntity(
        requestId = id, conversationId = "c", userMessageId = user, answerMessageId = answer,
        attemptId = "attempt", status = "COMPLETED", createdAt = Instant.ofEpochMilli(time),
    )
}
