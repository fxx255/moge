package com.moge.app.data.llm

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class GenerationConnectionReuseTest {
    private val online = object : NetworkAvailability {
        override val available = true
        override suspend fun awaitAvailable(timeoutMs: Long) = true
    }

    @Test(timeout = 15000) fun followUpDoesNotInheritAnUnusableConnectionFromThePreviousAnswer() = runBlocking {
        val server = MockWebServer()
        // A gateway that no longer accepts another request on its old connection.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.sequenceNumber > 0)
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            else MockResponse().setBody("ok")
        }
        server.start()
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        try {
            client.newCall(Request.Builder().url(server.url("/previous-answer")).build()).execute().use { it.body!!.string() }
            assertEquals(1, client.connectionPool.idleConnectionCount())
            GenerationNetworkSession(online).use { session -> withContext(Dispatchers.IO + session) {
                OkHttpCancellation.execute(client, Request.Builder().url(server.url("/photo-follow-up"))
                    .post("photo bytes".toRequestBody()).build()).use { assertEquals("ok", it.body!!.string()) }
            } }
            assertEquals("No failed paid request or settings probe should be needed", 2, server.requestCount)
            assertEquals(0, server.takeRequest().sequenceNumber)
            assertEquals(0, server.takeRequest().sequenceNumber)
        } finally { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test(timeout = 15000) fun toolsReuseTheCurrentAttemptsConnectionAndItsPoolIsReleased() = runBlocking {
        val server = MockWebServer()
        repeat(3) { server.enqueue(MockResponse().setBody("ok")) }; server.start()
        val client = OkHttpClient()
        val sockets = mutableSetOf<java.net.Socket>()
        val observed = client.newBuilder().eventListener(object : okhttp3.EventListener() {
            override fun connectionAcquired(call: okhttp3.Call, connection: okhttp3.Connection) { sockets += connection.socket() }
        }).build()
        val session = GenerationNetworkSession(online)
        try {
            session.use { withContext(Dispatchers.IO + it) {
                repeat(3) {
                    OkHttpCancellation.execute(observed, Request.Builder().url(server.url("/tool-$it"))
                        .post("tool request".toRequestBody()).build()).use { it.body!!.string() }
                }
            } }
            assertEquals(listOf(0, 1, 2), List(3) { server.takeRequest().sequenceNumber })
            assertEquals(0, client.connectionPool.connectionCount())
            assertEquals(1, sockets.size)
            assertTrue(sockets.single().isClosed)
        } finally { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test(timeout = 15000) fun closingAnOlderAttemptCannotCloseNewerOrSettingsConnections() = runBlocking {
        MockWebServer().use { server ->
            repeat(5) { server.enqueue(MockResponse().setBody("ok")) }; server.start()
            val sockets = mutableListOf<java.net.Socket>()
            val client = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
                override fun connectionAcquired(call: okhttp3.Call, connection: okhttp3.Connection) { sockets += connection.socket() }
            }).build()
            val metadata = client.newBuilder().readTimeout(5, TimeUnit.SECONDS).build()
            val older = GenerationNetworkSession(online)
            val newer = GenerationNetworkSession(online)
            suspend fun request(session: GenerationNetworkSession, transport: OkHttpClient) {
                withContext(Dispatchers.IO + session) {
                    OkHttpCancellation.execute(transport, Request.Builder().url(server.url("/model"))
                        .post("question".toRequestBody()).build()).use { it.body!!.string() }
                }
            }
            try {
                client.newCall(Request.Builder().url(server.url("/settings-ping")).build()).execute().use { it.body!!.string() }
                request(older, client)
                request(newer, metadata)
                older.close()
                assertFalse(sockets[0].isClosed)
                assertTrue(sockets[1].isClosed)
                assertFalse(sockets[2].isClosed)
                request(newer, client)
                assertSame("Derived metadata and tool clients share the current pool", sockets[2], sockets[3])
                try { request(older, client); fail("closed attempt must not send") } catch (_: IllegalStateException) { }
                newer.close()
                assertTrue(sockets[2].isClosed)
                client.newCall(Request.Builder().url(server.url("/settings-ping")).build()).execute().use { it.body!!.string() }
                assertSame(sockets[0], sockets[4])
                assertEquals(listOf(0, 0, 0, 1, 1), List(5) { server.takeRequest().sequenceNumber })
            } finally {
                older.close(); newer.close()
                client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
            }
        }
    }
}
