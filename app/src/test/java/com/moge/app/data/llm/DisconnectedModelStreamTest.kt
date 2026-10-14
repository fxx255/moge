package com.moge.app.data.llm

import android.app.Application
import com.moge.app.data.credential.*
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real sockets break after an answer delta: both APIs must preserve it without a second request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DisconnectedModelStreamTest {
    @Test fun `chat completions body disconnection is a network failure`() = verify(AiApiProtocol.CHAT_COMPLETIONS)
    @Test fun `responses body disconnection is a network failure without protocol fallback`() = verify(AiApiProtocol.RESPONSES)

    private fun verify(protocol: AiApiProtocol) = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val first = if (protocol == AiApiProtocol.RESPONSES)
                """data: {"type":"response.output_text.delta","delta":"partial answer"}"""
            else """data: {"choices":[{"delta":{"content":"partial answer"}}]}"""
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(first + "\n\n" + ": keepalive\n\n".repeat(2000))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val settings = mockk<SettingsRepository>()
            coEvery { settings.current() } returns UserSettings()
            val store = mockk<AiCredentialStore>()
            every { store.resolveActiveIdentity() } returns AiResolvedIdentity(
                "profile", server.url("/v1").toString(), "test-model", "test-key", true,
                AiSearchProtocol.OFF, AiReasoningEffort.MEDIUM, apiProtocol = protocol,
            )
            val model = ModelClient(settings, store, Dispatchers.IO)
            val visible = StringBuilder()
            val usages = mutableListOf<UsageSample?>()
            val error = runCatching {
                model.chatStreaming(listOf(ChatMessage("user", "question")), onUsage = { usages += it }) {
                    if (it is StreamEvent.AnswerDelta) visible.append(it.text)
                }
            }.exceptionOrNull()
            assertEquals("partial answer", visible.toString())
            assertTrue(error.toString(), error is ModelException)
            assertEquals(ModelException.Kind.NETWORK, (error as ModelException).kind)
            assertNotNull(error.cause)
            assertEquals(1, server.requestCount)
            assertEquals(1, usages.size)
        } finally { server.shutdown() }
    }
}
