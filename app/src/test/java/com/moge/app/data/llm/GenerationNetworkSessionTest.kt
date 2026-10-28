package com.moge.app.data.llm

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class GenerationNetworkSessionTest {
    private class Network(var online: Boolean = true) : NetworkAvailability {
        val ready = CompletableDeferred<Boolean>()
        override val available get() = online
        override suspend fun awaitAvailable(timeoutMs: Long) = online || ready.await().also { online = it }
    }
    private val nanos = AtomicLong(0)
    private val clock = MonotonicClock { nanos.get() }

    @Test(timeout = 15000) fun offlineWaitCanBeCancelledWithoutAnyRequest() = runBlocking {
        val network = Network(false)
        val statuses = mutableListOf<NetworkRecoveryStatus?>()
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { calls++; throw IOException("must not connect") }.build()
        val worker = launch(GenerationNetworkSession(network, statuses::add, clock)) {
            OkHttpCancellation.execute(client, Request.Builder().url("https://model.invalid").build())
        }
        yield()
        assertEquals(listOf(NetworkRecoveryStatus.WAITING), statuses)
        worker.cancelAndJoin()
        assertEquals(0, calls)
        assertNull(statuses.last())
    }

    @Test(timeout = 15000) fun offlineRequestIsSentOnlyAfterConnectivityReturns() = runBlocking {
        val network = Network(false)
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok")); server.start()
        val client = OkHttpClient()
        try {
            val result = async(Dispatchers.IO + GenerationNetworkSession(network, clock = clock)) {
                OkHttpCancellation.execute(client, Request.Builder().url(server.url("/")).build()).use { it.body!!.string() }
            }
            assertEquals(0, server.requestCount)
            network.ready.complete(true)
            assertEquals("ok", result.await())
            assertEquals(1, server.requestCount)
        } finally { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test(timeout = 15000) fun dnsFailuresBeforeSendingAreRetriedWithBoundedBackoff() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok")); server.start()
        var lookups = 0
        val pauses = mutableListOf<Long>()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (++lookups < 3) throw UnknownHostException("temporary DNS failure")
                return listOf(InetAddress.getByName("127.0.0.1"))
            }
        }).build()
        val session = GenerationNetworkSession(Network(), clock = clock, pause = { pauses += it; nanos.addAndGet(it * 1_000_000) })
        try {
            withContext(Dispatchers.IO + session) {
                OkHttpCancellation.execute(client, Request.Builder().url(server.url("/").newBuilder().host("model.invalid").build())
                    .post("question".toRequestBody()).build()).use { assertEquals("ok", it.body!!.string()) }
            }
            assertEquals(3, lookups)
            assertEquals(listOf(1000L, 2000L), pauses)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test(timeout = 15000) fun submittedPostIsNeverReplayedWhenHeadersAreLost() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)); server.start()
        val client = OkHttpClient()
        var calls = 0
        try {
            try {
                withContext(Dispatchers.IO + GenerationNetworkSession(Network(), clock = clock, pause = { fail("unsafe replay") })) {
                    OkHttpCancellation.execute(client, Request.Builder().url(server.url("/")).post("paid generation".toRequestBody()).build()) { calls++ }
                }
                fail("must fail")
            } catch (_: IOException) { }
            assertEquals(1, server.requestCount)
            assertEquals(1, calls)
        } finally { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test(timeout = 15000) fun losingConnectivityDoesNotCancelAnExistingResponseBody() = runBlocking {
        val network = Network()
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("still readable")); server.start()
        val client = OkHttpClient()
        try {
            withContext(Dispatchers.IO + GenerationNetworkSession(network, clock = clock)) {
                OkHttpCancellation.execute(client, Request.Builder().url(server.url("/")).build()).use {
                    network.online = false
                    assertEquals("still readable", it.body!!.string())
                }
            }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test(timeout = 15000) fun submittedPostsCannotBeReplayedByHttpFollowUps() = runBlocking {
        for (code in listOf(408, 503, 307, 308)) {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After", "0").setHeader("Location", "/redirected"))
            server.enqueue(MockResponse().setBody("must not generate again"))
            server.start()
            val client = OkHttpClient()
            try {
                withContext(Dispatchers.IO + GenerationNetworkSession(Network())) {
                    OkHttpCancellation.execute(client, Request.Builder().url(server.url("/"))
                        .post("paid generation".toRequestBody()).build()).use { assertEquals(code, it.code) }
                }
                assertEquals("HTTP $code must not replay a POST", 1, server.requestCount)
            } finally { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
    }

    @Test(timeout = 15000) fun ordinaryGetRedirectsStillWork() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
        server.enqueue(MockResponse().setBody("redirected document"))
        server.start()
        val client = OkHttpClient()
        try {
            withContext(Dispatchers.IO + GenerationNetworkSession(Network())) {
                OkHttpCancellation.execute(client, Request.Builder().url(server.url("/")).build()).use {
                    assertEquals("redirected document", it.body!!.string())
                }
            }
            assertEquals(2, server.requestCount)
        } finally { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test(timeout = 15000) fun certificateFailureAndOfflineDeadlineAreNotRetried() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { calls++; throw SSLHandshakeException("bad certificate") }.build()
        try {
            withContext(GenerationNetworkSession(Network(), clock = clock)) {
                OkHttpCancellation.execute(client, Request.Builder().url("https://model.invalid/").build())
            }
            fail("must fail")
        } catch (_: SSLHandshakeException) { }
        assertEquals(1, calls)
        val offline = Network(false).apply { ready.complete(false) }
        try {
            withContext(GenerationNetworkSession(offline, clock = clock)) {
                OkHttpCancellation.execute(client, Request.Builder().url("https://model.invalid/").build())
            }
            fail("must time out before connecting")
        } catch (_: IOException) { }
        assertEquals(1, calls)
    }

    @Test(timeout = 15000) fun connectionBudgetDoesNotShortenTheModelsHeaderOrBodyTimeout() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeadersDelay(700, java.util.concurrent.TimeUnit.MILLISECONDS).setBody("slow first token"))
        server.start()
        val client = OkHttpClient.Builder().readTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
        try {
            withContext(Dispatchers.IO + GenerationNetworkSession(Network(), clock = clock, waitBudgetMs = 300)) {
                OkHttpCancellation.execute(client, Request.Builder().url(server.url("/")).build()).use {
                    assertEquals("slow first token", it.body!!.string())
                }
            }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test(timeout = 15000) fun connectionDeadlineCannotSendAfterSlowDnsReturns() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("must not submit")); server.start()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                Thread.sleep(750)
                return listOf(InetAddress.getByName("127.0.0.1"))
            }
        }).build()
        try {
            try {
                withContext(Dispatchers.IO + GenerationNetworkSession(Network(), waitBudgetMs = 150)) {
                    OkHttpCancellation.execute(client, Request.Builder()
                        .url(server.url("/").newBuilder().host("model.invalid").build())
                        .post("question".toRequestBody()).build())
                }
                fail("connection deadline must expire")
            } catch (_: java.net.SocketTimeoutException) { }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
}
