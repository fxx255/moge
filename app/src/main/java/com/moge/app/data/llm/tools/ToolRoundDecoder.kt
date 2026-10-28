package com.moge.app.data.llm.tools

import com.moge.app.data.credential.AiApiProtocol
import com.moge.app.data.llm.*
import kotlinx.serialization.json.*

internal data class ModelToolCall(val id: String, val name: String, val arguments: String)
internal data class ToolRoundOutput(
    val text: String,
    val reasoning: String,
    val replay: List<JsonObject>,
    val calls: List<ModelToolCall>,
    val truncated: Boolean,
    val citations: List<ModelClient.Citation>,
    val searched: Boolean,
)

/** Pure protocol state. Tool argument fragments never enter the visible answer channel. */
internal class ToolRoundDecoder(
    private val protocol: AiApiProtocol,
    private val onEvent: suspend (StreamEvent) -> Unit,
    private val onUsage: (UsageSample?) -> Unit,
) {
    private val responses = protocol == AiApiProtocol.RESPONSES
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private val chatReasoning = StringBuilder()
    private var chatReasoningField: String? = null
    private val items = sortedMapOf<Int, JsonObject>()
    private val chatCalls = sortedMapOf<Int, JsonObject>()
    private val reasoningDetails = sortedMapOf<Int, JsonObject>()
    private val citations = mutableListOf<ModelClient.Citation>()
    private var searched = false
    private var truncated = false
    var terminal = false
        private set
    var progress = false
        private set

    suspend fun accept(chunk: JsonObject, eventName: String? = null) {
        progress = false
        onUsage(UsageParser.parse(chunk, if (responses) "responses" else "chat-completions"))
        if (responses) acceptResponses(chunk, eventName) else acceptChat(chunk)
    }

    private suspend fun appendText(value: String) {
        if (value.isEmpty()) return
        progress = true; text.append(value); onEvent(StreamEvent.AnswerDelta(value))
    }

    private suspend fun appendReasoning(value: String) {
        if (value.isEmpty()) return
        progress = true; reasoning.append(value); onEvent(StreamEvent.ReasoningDelta(value))
    }

    private suspend fun acceptChat(root: JsonObject) {
        if (root["error"] != null && root["error"] != JsonNull) fail(root)
        recordSources(root["citations"])
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        onUsage(UsageParser.parse(choice, "chat-completions"))
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let {
            terminal = true; progress = true; truncated = it == "length"
        }
        val delta = choice["delta"] as? JsonObject
        val message = delta ?: choice["message"] as? JsonObject ?: return
        recordSources(message["citations"]); recordSources(message["annotations"])
        val content = extractText(message["content"])
        appendText(if (delta == null && content.startsWith(text.toString())) content.drop(text.length) else content)
        val reasoningField = when {
            message["reasoning_content"] is JsonPrimitive -> "reasoning_content"
            message["reasoning"] is JsonPrimitive -> "reasoning"
            else -> null
        }
        if (reasoningField != null) {
            chatReasoningField = reasoningField
            val content = extractText(message[reasoningField])
            chatReasoning.append(content); appendReasoning(content)
        }
        (message["reasoning_details"] as? JsonArray)?.filterIsInstance<JsonObject>()?.forEachIndexed { index, detail ->
            val key = detail["index"]?.jsonPrimitive?.intOrNull ?: index
            reasoningDetails[key] = if (delta == null) detail else mergeFragments(reasoningDetails[key], detail, setOf("text", "data", "summary", "signature"))
            if (message["reasoning_content"] == null && message["reasoning"] == null)
                appendReasoning(extractText(detail["text"] ?: detail["summary"]))
        }
        (message["tool_calls"] as? JsonArray)?.filterIsInstance<JsonObject>()?.forEachIndexed { index, call ->
            progress = true
            val key = call["index"]?.jsonPrimitive?.intOrNull ?: index
            val old = chatCalls[key]
            val function = call["function"] as? JsonObject ?: JsonObject(emptyMap())
            chatCalls[key] = JsonObject(old.orEmpty() + call + ("function" to
                if (delta == null) function else mergeFragments(old?.get("function") as? JsonObject, function, setOf("arguments", "name"))))
        }
    }

    private suspend fun acceptResponses(root: JsonObject, eventName: String?) {
        val type = root["type"]?.jsonPrimitive?.contentOrNull ?: eventName.orEmpty()
        when (type) {
            "response.output_text.delta" -> appendText(root["delta"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                appendReasoning(root["delta"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "response.output_item.added", "response.output_item.done" -> {
                progress = true
                val item = root["item"] as? JsonObject ?: return
                val index = root["output_index"]?.jsonPrimitive?.intOrNull ?: items.size
                items[index] = item
                recordSources(item)
            }
            "response.function_call_arguments.delta" -> {
                progress = true
                val index = root["output_index"]?.jsonPrimitive?.intOrNull ?: error("工具调用缺少output_index")
                items[index] = mergeFragments(items[index], buildJsonObject {
                    put("arguments", root["delta"]?.jsonPrimitive?.contentOrNull.orEmpty())
                }, setOf("arguments"))
            }
            "response.function_call_arguments.done" -> {
                progress = true
                val index = root["output_index"]?.jsonPrimitive?.intOrNull ?: return
                items[index] = JsonObject(items[index].orEmpty() + root.filterKeys { it in setOf("arguments", "name") })
            }
            "response.output_text.annotation.added" -> recordSources(root["annotation"])
            "response.web_search_call.searching", "response.web_search_call.completed" -> { searched = true; progress = true }
            "response.completed", "response.incomplete" -> {
                val response = root["response"] as? JsonObject ?: root
                acceptResponseObject(response)
                if (type == "response.incomplete" && !truncated) fail(response)
                terminal = true; progress = true
            }
            "response.failed", "error" -> {
                val response = root["response"] as? JsonObject ?: root
                onUsage(UsageParser.parse(response, "responses")); fail(response)
            }
            // A 200 JSON body from a gateway that ignored stream=true is consumed in place.
            else -> if (root["output"] is JsonArray) { acceptResponseObject(root); terminal = true }
        }
    }

    private suspend fun acceptResponseObject(root: JsonObject) {
        onUsage(UsageParser.parse(root, "responses"))
        if (root["error"] != null && root["error"] != JsonNull || root["status"]?.jsonPrimitive?.contentOrNull == "failed") fail(root)
        truncated = (root["incomplete_details"] as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull == "max_output_tokens"
        if (root["status"]?.jsonPrimitive?.contentOrNull == "incomplete" && !truncated) fail(root)
        val output = root["output"] as? JsonArray
        if (output != null && output.isNotEmpty()) {
            items.clear()
            output.filterIsInstance<JsonObject>().forEachIndexed { index, item -> items[index] = item; recordSources(item) }
        }
        // Some gateways send only the terminal snapshot. Do not duplicate already-streamed text.
        val snapshotText = items.values.filter { it["type"]?.jsonPrimitive?.contentOrNull == "message" }
            .flatMap { (it["content"] as? JsonArray).orEmpty() }.filterIsInstance<JsonObject>()
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "output_text" }
            .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        if (snapshotText.startsWith(text.toString())) appendText(snapshotText.drop(text.length))
        if (reasoning.isEmpty()) appendReasoning(items.values.filter { it["type"]?.jsonPrimitive?.contentOrNull == "reasoning" }
            .flatMap { (it["summary"] as? JsonArray).orEmpty() }.filterIsInstance<JsonObject>()
            .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() })
    }

    fun result(): ToolRoundOutput {
        val replay = if (responses) items.values.toList() else listOf(buildJsonObject {
            put("role", "assistant"); put("content", text.toString())
            chatReasoningField?.let { put(it, chatReasoning.toString()) }
            if (reasoningDetails.isNotEmpty()) put("reasoning_details", JsonArray(reasoningDetails.values.toList()))
            if (chatCalls.isNotEmpty()) put("tool_calls", JsonArray(chatCalls.values.map { JsonObject(it - "index") }))
        })
        val calls = if (responses) items.values.filter { it["type"]?.jsonPrimitive?.contentOrNull == "function_call" }.map { call ->
            ModelToolCall(call["call_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                call["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), call["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty())
        } else chatCalls.values.map { call ->
            val function = call["function"] as? JsonObject
            ModelToolCall(call["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                function?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(), function?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty())
        }
        if (calls.any { it.id.isBlank() || it.name.isBlank() } || calls.map { it.id }.distinct().size != calls.size)
            throw ModelException(ModelException.Kind.INVALID_RESPONSE, "模型返回了无效的工具调用标识")
        return ToolRoundOutput(text.toString(), reasoning.toString(), replay, calls, truncated, citations.distinctBy { it.url }, searched)
    }

    private fun recordSources(value: JsonElement?) {
        when (value) {
            is JsonObject -> {
                if (value["type"]?.jsonPrimitive?.contentOrNull == "web_search_call") searched = true
                val url = value["url"]?.jsonPrimitive?.contentOrNull
                if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                    citations += ModelClient.Citation(value["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), url)
                    searched = true
                }
                value.values.forEach(::recordSources)
            }
            is JsonArray -> value.forEach(::recordSources)
            is JsonPrimitive -> value.contentOrNull?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let {
                citations += ModelClient.Citation(it, it); searched = true
            }
            else -> Unit
        }
    }

    private fun fail(root: JsonObject): Nothing {
        val detail = (root["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            ?: root["message"]?.jsonPrimitive?.contentOrNull ?: "服务端返回失败或不完整结果"
        throw ModelException(ModelException.Kind.SERVER, detail.take(1200))
    }

    private fun mergeFragments(old: JsonObject?, next: JsonObject, appended: Set<String>): JsonObject =
        JsonObject(old.orEmpty() + next.mapValues { (key, value) ->
            if (key in appended && !(key == "name" && old?.get(key) == value))
                JsonPrimitive(old?.get(key)?.jsonPrimitive?.contentOrNull.orEmpty() + value.jsonPrimitive.contentOrNull.orEmpty()) else value
        })
}
