package com.moge.app.data.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

class OkHttpCancellationTest {
    @Test(timeout = 15000)
    fun cancellationInterruptsWaitingForHeaders() = verifyCancellation(sendHeaders = false, wholeBody = false)

    @Test(timeout = 15000)
    fun cancellationInterruptsStreamingReadAfterHeaders() = verifyCancellation(sendHeaders = true, wholeBody = false)

    @Test(timeout = 15000)
    fun cancellationInterruptsNonStreamingBodyAfterHeaders() = verifyCancellation(sendHeaders = true, wholeBody = true)

    private fun verifyCancellation(sendHeaders: Boolean, wholeBody: Boolean) = runBlocking {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val requestReceived = CompletableDeferred<Unit>()
        val readStarted = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var accepted: Socket? = null
        var call: Call? = null
        val serverThread = Thread {
            try {
                server.accept().use { socket ->
                    accepted = socket
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* request headers */ }
                    if (sendHeaders) {
                        socket.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".toByteArray(),
                        )
                        socket.getOutputStream().flush()
                    }
                    requestReceived.complete(Unit)
                    // No response body. The client must cancel the socket, not await its timeout.
                    reader.read()
                }
            } catch (_: Exception) { /* fixture cleanup */ }
        }.apply { isDaemon = true; start() }
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()
        val worker = launch(Dispatchers.IO) {
            try {
                OkHttpCancellation.execute(client, request) { call = it }.use { response ->
                    readStarted.complete(Unit)
                    if (wholeBody) response.body!!.string() else response.body!!.source().readUtf8Line()
                }
            } catch (error: CancellationException) {
                cancelled.complete(Unit)
                throw error
            }
        }
        try {
            withTimeout(5000) {
                requestReceived.await()
                if (sendHeaders) readStarted.await()
            }
            worker.cancel()
            withTimeout(3000) {
                cancelled.await()
                worker.join()
            }
            assertTrue("Cancellation must reach the actual OkHttp call", call!!.isCanceled())
        } finally {
            worker.cancel()
            accepted?.close()
            server.close()
            serverThread.join(1000)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
