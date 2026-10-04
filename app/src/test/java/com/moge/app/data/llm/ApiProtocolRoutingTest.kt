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
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ApiProtocolRoutingTest {
    private val requests = mutableListOf<Request>()
    private val payloads = mutableListOf<JsonObject>()
    private fun client(responseCode: Int = 200, body: String = """{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"{\"reply\":\"已读取\"}"}]}]}"""): ModelClient {
        val store = mockk<AiCredentialStore>(relaxed = true)
        val identity = AiResolvedIdentity("r", "https://gateway.example/v1", "model", "test-key", true,
            AiSearchProtocol.RESPONSES, AiReasoningEffort.LOW, apiProtocol = AiApiProtocol.RESPONSES)
        every { store.resolveActiveIdentity() } returns identity
        every { store.resolveIdentityFor("r") } returns identity
        every { store.credentialsFor("r") } returns AiProfileCredentials(identity.baseUrl, identity.model, identity.apiKey,
            identity.searchProtocol, AiApiProtocol.RESPONSES)
        val settings = mockk<SettingsRepository>()
        coEvery { settings.current() } returns UserSettings(webSearchEnabled = false)
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
            payloads += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(responseCode).message("test")
                .header("Content-Type", "application/json")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return ModelClient(settings, store, Dispatchers.Unconfined).also {
            ModelClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(it, transport)
        }
    }

    @Test fun explicitResponsesKeepsEndpointAndOmitsSearchWhenOff() = runBlocking {
        val result = client().chatStreaming(listOf(ChatMessage("user", "问题")), webSearchEnabled = false) { }
        assertEquals("已读取", result.reply)
        assertEquals(1, requests.size)
        assertEquals("/v1/responses", requests.single().url.encodedPath)
        assertFalse(payloads.single().containsKey("tools"))
    }

    @Test fun connectionAndVisionRecognizerUseConfiguredResponsesApi() = runBlocking {
        val model = client()
        model.testConnection("r")
        val result = model.completeWithProfile("r", "识题", "读图", listOf("picture"))
        assertTrue(result.contains("已读取"))
        assertTrue(requests.all { it.url.encodedPath == "/v1/responses" })
        val blocks = payloads.last()["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals("input_image", blocks.last().jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(payloads.last().containsKey("tools"))
    }

    @Test fun responsesErrorDoesNotSilentlyResendAsChat() = runBlocking {
        val result = runCatching { client(400, """{"error":{"message":"tool type web_search is not supported"}}""")
            .chatStreaming(listOf(ChatMessage("user", "问题")), webSearchEnabled = false) { } }
        assertTrue(result.isFailure)
        assertEquals(1, requests.size)
        assertEquals("/v1/responses", requests.single().url.encodedPath)
    }
}
