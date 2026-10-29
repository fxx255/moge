package com.moge.app.data.llm

import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.BufferedSink
import okio.Buffer
import okio.buffer
import okio.sink
import okio.source
import org.junit.Assert.*
import org.junit.Test

class ModelTransportTest {
    private val online = object : NetworkAvailability {
        override val available = true
        override suspend fun awaitAvailable(timeoutMs: Long) = true
    }

    @Test fun imageUploadWaitIsLongerThanTheOldDefaultWithoutChangingThinkingOrCancellationLimits() {
        val client = ModelTransport.create()
        assertEquals(120_000, client.writeTimeoutMillis)
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(600_000, client.readTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
    }

    @Test(timeout = 15000) fun headerFailureRecordsItsStageAndDoesNotResendOrExposePrivateData() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)); server.start()
            val client = ModelTransport.create()
            try {
                val error = try {
                    GenerationNetworkSession(online).use { session -> withContext(Dispatchers.IO + session) {
                        OkHttpCancellation.execute(client, Request.Builder().url(server.url("/private-path?key=secret"))
                            .header("Authorization", "Bearer private-token").post("private-image-data".toRequestBody()).build())
                    } }
                    error("must fail")
                } catch (failure: ModelTransportFailure) { failure }
                assertEquals(ModelTransportTrace.Phase.WAIT_RESPONSE, error.phase)
                assertEquals(18L, error.bodyBytes)
                assertEquals(18L, error.expectedBytes)
                assertFalse(error.reused)
                assertEquals(1, server.requestCount)
                assertFalse(error.summary().contains("private"))
                assertFalse(error.summary().contains("secret"))
                assertFalse(error.message!!.contains(server.hostName))
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
    }

    @Test(timeout = 15000) fun bodyReadFailureKeepsTheReadStageAndOriginalCause() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("partial response".repeat(100))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)); server.start()
            val client = ModelTransport.create()
            try {
                val error = try {
                    GenerationNetworkSession(online).use { session -> withContext(Dispatchers.IO + session) {
                        OkHttpCancellation.execute(client, Request.Builder().url(server.url("/"))
                            .post("question".toRequestBody()).build()).use { it.body!!.string() }
                    } }
                    error("must fail")
                } catch (failure: ModelTransportFailure) { failure }
                assertEquals(ModelTransportTrace.Phase.READ_RESPONSE, error.phase)
                assertTrue(error.cause is IOException)
                assertEquals(1, server.requestCount)
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
    }

    @Test(timeout = 15000) fun cancellationDuringAStalledPhotoUploadClosesTheSocketWithoutReplaying() = runBlocking {
        ServerSocket(0).use { server ->
            val accepted = CompletableDeferred<java.net.Socket>()
            val socketRef = AtomicReference<java.net.Socket>()
            val reader = launch(Dispatchers.IO) {
                try { server.accept().also { socketRef.set(it); accepted.complete(it) } }
                catch (error: IOException) { accepted.completeExceptionally(error) }
            }
            val writing = CompletableDeferred<Unit>()
            val body = object : RequestBody() {
                override fun contentType(): okhttp3.MediaType? = null
                override fun contentLength() = 64L * 1024 * 1024
                override fun writeTo(sink: BufferedSink) {
                    writing.complete(Unit)
                    val chunk = ByteArray(64 * 1024)
                    repeat(1024) { sink.write(chunk); sink.flush() }
                }
            }
            val client = ModelTransport.create()
            val session = GenerationNetworkSession(online)
            var calls = 0
            val worker = async(Dispatchers.IO + session) {
                OkHttpCancellation.execute(client, Request.Builder().url("http://127.0.0.1:${server.localPort}/")
                    .post(body).build()) { calls++ }.use { it.body!!.string() }
            }
            try {
                val socket = withTimeout(3000) { accepted.await() }
                withTimeout(3000) { writing.await() }
                delay(100)
                worker.cancel()
                withTimeout(3000) { worker.join() }
                assertTrue(worker.isCancelled)
                assertEquals(1, calls)
                assertEquals(0, client.dispatcher.runningCallsCount())
                socket.close()
            } finally {
                worker.cancel()
                server.close()
                worker.join(); reader.join(); session.close()
                socketRef.get()?.close()
                client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    @Test(timeout = 40000) fun photoUploadSurvivesAWriteStallLongerThanTheOldTenSecondDefault() = runBlocking {
        ServerSocket(0).use { server ->
            val socketRef = AtomicReference<java.net.Socket>()
            val size = 64L * 1024 * 1024
            val receiver = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socketRef.set(socket)
                    socket.soTimeout = 5000
                    val source = socket.source().buffer()
                    while (source.readUtf8LineStrict().isNotEmpty()) { /* request headers */ }
                    // Do not drain the upload: exhaust the socket buffer beyond the previous write timeout.
                    delay(12_000)
                    var received = 0L
                    val buffer = Buffer()
                    while (received < size) {
                        val count = source.read(buffer, minOf(65536, size - received))
                        check(count > 0) { "upload was truncated" }
                        received += count
                        buffer.clear()
                    }
                    socket.sink().buffer().apply {
                        writeUtf8("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
                        flush()
                    }
                    received
                }
            }
            val client = ModelTransport.create()
            val session = GenerationNetworkSession(online)
            val body = object : RequestBody() {
                override fun contentType(): okhttp3.MediaType? = null
                override fun contentLength() = size
                override fun writeTo(sink: BufferedSink) {
                    val chunk = ByteArray(65536)
                    repeat(1024) { sink.write(chunk); sink.flush() }
                }
            }
            var calls = 0
            try {
                withContext(Dispatchers.IO + session) {
                    OkHttpCancellation.execute(client, Request.Builder().url("http://127.0.0.1:${server.localPort}/")
                        .post(body).build()) { calls++ }.use { assertEquals("ok", it.body!!.string()) }
                }
                assertEquals(size, receiver.await())
                assertEquals(1, calls)
            } finally {
                server.close(); socketRef.get()?.close(); receiver.cancelAndJoin(); session.close()
                client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
            }
        }
    }
}
