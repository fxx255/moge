package com.moge.app.data.llm

import android.app.Application
import com.moge.app.data.credential.AiApiProtocol
import com.moge.app.data.credential.AiCredentialStore
import com.moge.app.data.credential.AiReasoningEffort
import com.moge.app.data.credential.AiResolvedIdentity
import com.moge.app.data.credential.AiSearchProtocol
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SlowModelTransportTest {
    private fun model(baseUrl: String): ModelClient {
        val settings = mockk<SettingsRepository>()
        coEvery { settings.current() } returns UserSettings()
        val credentials = mockk<AiCredentialStore>()
        every { credentials.resolveActiveIdentity() } returns AiResolvedIdentity(
            profileId = "slow-model", baseUrl = baseUrl, model = "test-model", apiKey = "test-key",
            visionEnabled = false, searchProtocol = AiSearchProtocol.OFF, reasoningEffort = AiReasoningEffort.LOW,
            apiProtocol = AiApiProtocol.CHAT_COMPLETIONS,
        )
        return ModelClient(settings, credentials, Dispatchers.IO)
    }

    private fun transport(model: ModelClient): OkHttpClient =
        ModelClient::class.java.getDeclaredField("client").apply { isAccessible = true }.get(model) as OkHttpClient

    private fun useTransport(model: ModelClient, client: OkHttpClient) {
        ModelClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(model, client)
    }

    private val stream = """data: {"choices":[{"delta":{"reasoning_content":"先检查条件"}}]}

data: {"choices":[{"delta":{"content":"{\"reply\":\"结果正确\"}"},"finish_reason":"stop"}]}

data: [DONE]

"""

    @Test fun delayedHeadersAndFirstTokenUseTheGenerationWaitBudget() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setHeadersDelay(300, TimeUnit.MILLISECONDS).setBodyDelay(300, TimeUnit.MILLISECONDS).setBody(stream))
            server.start()
            val model = model(server.url("/v1").toString())
            val client = transport(model)
            assertTrue("Slow models need more than the old 180s idle limit", client.readTimeoutMillis >= 600_000)
            assertTrue("A heartbeat-only response must have a bounded lifetime", client.callTimeoutMillis in 1..1_800_000)
            val events = mutableListOf<StreamEvent>()
            val reply = model.chatStreaming(listOf(ChatMessage("user", "合成测试问题"))) { events += it }
            assertEquals("结果正确", reply.reply)
            assertEquals(StreamEvent.ReasoningDelta("先检查条件"), events.first())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancellingWhileTheModelHasNotSentHeadersClosesTheRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val model = model(server.url("/v1").toString())
            val requestSeen = CompletableDeferred<Unit>()
            val client = transport(model).newBuilder().addNetworkInterceptor { chain ->
                requestSeen.complete(Unit)
                chain.proceed(chain.request())
            }.build()
            useTransport(model, client)
            val worker = async(Dispatchers.IO) { model.chatStreaming(listOf(ChatMessage("user", "测试"))) {} }
            try {
                withTimeout(3_000) { requestSeen.await() }
                worker.cancel()
                withTimeout(3_000) { worker.join() }
                assertTrue(worker.isCancelled)
                assertEquals(0, client.dispatcher.runningCallsCount())
            } finally { worker.cancel() }
        }
    }

    @Test fun exceedingTheCallBudgetDoesNotRetryTheModelRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val model = model(server.url("/v1").toString())
            // Scale the total request budget down for a real-socket timeout regression.
            useTransport(model, transport(model).newBuilder().callTimeout(400, TimeUnit.MILLISECONDS).build())
            val error = withTimeout(3_000) {
                runCatching { model.chatStreaming(listOf(ChatMessage("user", "测试"))) {} }.exceptionOrNull()
            }
            assertTrue(error is ModelException)
            assertEquals(ModelException.Kind.NETWORK, (error as ModelException).kind)
            assertFalse(error.isSearchFallbackAllowed)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun proxyWithNoHealthyAccountReportsServerFailureWithoutResending() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503)
                .setBody("""{"error":{"code":"no_healthy_account","message":"No healthy account"}}"""))
            server.start()
            val model = model(server.url("/v1").toString())
            val error = runCatching { model.chatStreaming(listOf(ChatMessage("user", "测试"))) {} }.exceptionOrNull()
            assertTrue(error is ModelException)
            assertEquals(ModelException.Kind.SERVER, (error as ModelException).kind)
            assertTrue(error.message!!.contains("no_healthy_account"))
            assertFalse(error.isSearchFallbackAllowed)
            assertEquals(1, server.requestCount)
        }
    }
}
