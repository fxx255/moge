package com.moge.app.data.llm.tools

import com.moge.app.data.credential.AiApiProtocol
import com.moge.app.data.llm.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One session's streaming transport. Only explicit pre-generation parameter rejections are retried. */
internal class ToolStreamingTransport(private val client: OkHttpClient, private val protocol: AiApiProtocol) {
    private data class ParameterChange(val original: JsonElement?, val replacement: JsonElement?)
    private val changes = mutableMapOf<String, ParameterChange>()

    suspend fun execute(request: Request, payload: JsonObject, onUsage: (UsageSample?) -> Unit,
        onEvent: suspend (StreamEvent) -> Unit): ToolRoundOutput {
        val adapted = payload.toMutableMap().apply { changes.forEach { (key, change) ->
            // A deliberate change (e.g. high -> low for recovery) gets its own compatibility check.
            if (get(key) == change.original) {
                if (change.replacement == null) remove(key) else put(key, change.replacement)
            }
        } }
        var parameters = GenerationParameterNegotiation(JsonObject(adapted))
        while (true) {
            currentCoroutineContext().ensureActive()
            val current = parameters.payload
            try {
                return read(request.newBuilder().post(current.toString().toRequestBody("application/json".toMediaType())).build(), onUsage, onEvent)
            } catch (error: ModelException) {
                val next = parameters.afterRejection(error) ?: if (current["stream"]?.jsonPrimitive?.booleanOrNull == true &&
                    isStreamParameterRejected(error.rejectedHttpStatus ?: 0, error.message.orEmpty())) {
                    JsonObject(current - "stream_options" + ("stream" to JsonPrimitive(false))).also { parameters = GenerationParameterNegotiation(it) }
                } else throw error
                (current.keys + next.keys).filter { current[it] != next[it] }.forEach { key ->
                    changes[key] = ParameterChange(if (key in changes) changes.getValue(key).original else current[key], next[key])
                }
            }
        }
    }

    private suspend fun read(request: Request, onUsage: (UsageSample?) -> Unit, onEvent: suspend (StreamEvent) -> Unit): ToolRoundOutput {
        val usage = UsageAccumulator(if (protocol == AiApiProtocol.RESPONSES) "responses" else "chat-completions")
        try {
            return OkHttpCancellation.execute(client, request).use { response ->
                if (!response.isSuccessful) throw ModelException(
                    if (response.code in listOf(401, 403)) ModelException.Kind.UNAUTHORIZED else ModelException.Kind.SERVER,
                    "模型接口返回 HTTP ${response.code}：${response.body?.string().orEmpty().take(2048)}", rejectedHttpStatus = response.code,
                )
                val body = response.body ?: throw ModelException(ModelException.Kind.INVALID_RESPONSE, "模型接口没有返回响应体")
                val decoder = ToolRoundDecoder(protocol, onEvent, usage::accept)
                val progress = GenerationProgressWatchdog()
                var sawEvent = false
                var done = false
                val raw = StringBuilder()
                body.source().readSseEvents(onRawLine = { raw.append(it).append('\n'); progress.progress() }, onLine = progress::check) { event ->
                    if (event.data == "[DONE]") {
                        done = true
                        return@readSseEvents false
                    }
                    if (event.data.isBlank()) return@readSseEvents true
                    sawEvent = true
                    val chunk = runCatching { Json.parseToJsonElement(event.data) as? JsonObject }.getOrNull()
                        ?: throw ModelException(ModelException.Kind.INVALID_RESPONSE, "模型返回了无法解析的流式事件，已保留已有正文")
                    decoder.accept(chunk, event.name)
                    if (decoder.progress) progress.progress()
                    !(protocol == AiApiProtocol.RESPONSES && decoder.terminal)
                }
                if (!sawEvent && !done) {
                    val root = runCatching { Json.parseToJsonElement(raw.toString()) as? JsonObject }.getOrNull()
                        ?: throw ModelException(ModelException.Kind.INVALID_RESPONSE, "模型没有返回合法结果")
                    decoder.accept(root)
                } else if (!decoder.terminal && !(done && protocol == AiApiProtocol.CHAT_COMPLETIONS)) {
                    throw ModelException(ModelException.Kind.NETWORK, "流式响应没有正常结束，已保留已有正文")
                }
                decoder.result()
            }
        } finally {
            onUsage(usage.result())
        }
    }
}
