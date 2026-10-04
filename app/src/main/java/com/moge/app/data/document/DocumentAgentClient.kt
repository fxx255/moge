package com.moge.app.data.document

import android.content.Context
import android.util.Base64
import com.moge.app.data.credential.AiApiProtocol
import com.moge.app.data.llm.*
import com.moge.app.data.parse.ParsedReply
import com.moge.app.data.parse.ReplyParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject

/** Bounded native-document / read-tool loop; original questions are never rewritten. */
class DocumentAgentClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: DocumentStore,
) {
    suspend fun run(
        client: OkHttpClient, baseUrl: String, model: String, apiKey: String,
        protocol: AiApiProtocol, nativePdf: Boolean, vision: Boolean,
        messages: List<ChatMessage>, currentImages: List<String>, system: String,
        webSearch: Boolean, forceWebSearch: Boolean,
        onUsage: (UsageSample?) -> Unit, onEvent: suspend (StreamEvent) -> Unit,
        requireRead: Boolean = true,
    ): ParsedReply {
        val attachments = messages.flatMap { it.documentPaths }.distinct().map { store.attachment(it, verifyHash = true) }
        if (requireRead && attachments.all { File(it.path).extension.lowercase() in setOf("doc", "ppt", "xls") })
            throw ModelException(ModelException.Kind.CONFIG_INVALID, "原件已保留。旧版 DOC/PPT/XLS 请先转换为 DOCX/PPTX/XLSX 或 PDF 后分析")
        val session = DocumentTools(context, attachments)
        val anthropic = protocol == AiApiProtocol.ANTHROPIC_MESSAGES
        val responses = protocol == AiApiProtocol.RESPONSES
        val transcript = mutableListOf<JsonObject>()
        val native = if (nativePdf && vision) attachments.filter { File(it.path).extension.equals("pdf", true) } else emptyList()
        val inlineNative = native.takeIf { docs -> docs.sumOf { it.bytes } <= 18L * 1024 * 1024 }.orEmpty()
        val prompt = system + "\n" + DocumentTools.INSTRUCTIONS +
            (if (forceWebSearch) "\n本轮必须先执行联网搜索，并在回答中提供可核验来源。" else "") +
            "\n本轮可访问的附件：" + session.manifest()
        if (!anthropic && !responses) transcript += buildJsonObject { put("role", "system"); put("content", prompt) }
        messages.forEachIndexed { index, message ->
            val content = buildJsonArray {
                val text = message.content.ifBlank { if (message.role == "user") "请分析附件文档。" else "" }
                if (text.isNotBlank()) add(buildJsonObject { put("type", if (responses) "input_text" else "text"); put("text", text) })
                val images = if (index == messages.indexOfLast { it.role == "user" } && currentImages.isNotEmpty()) currentImages else message.imageBase64s
                images.forEach { image -> add(imageBlock("data:image/jpeg;base64,$image", protocol)) }
                if (index == messages.indexOfLast { it.role == "user" }) inlineNative.forEach { document ->
                    val encoded = Base64.encodeToString(File(document.path).readBytes(), Base64.NO_WRAP)
                    add(buildJsonObject {
                        if (anthropic) {
                            put("type", "document"); put("title", document.name)
                            put("source", buildJsonObject { put("type", "base64"); put("media_type", "application/pdf"); put("data", encoded) })
                            put("citations", buildJsonObject { put("enabled", true) })
                        } else if (responses) {
                            put("type", "input_file"); put("filename", document.name); put("file_data", "data:application/pdf;base64,$encoded")
                        } else {
                            put("type", "file"); put("file", buildJsonObject {
                                put("filename", document.name); put("file_data", "data:application/pdf;base64,$encoded")
                            })
                        }
                    })
                }
            }
            transcript += buildJsonObject { put("role", message.role); put("content", if (responses && message.role == "assistant") JsonPrimitive(message.content) else content) }
        }
        val tools = JsonArray(DocumentTools.definitions.map { definition ->
            when {
                anthropic -> buildJsonObject {
                    put("name", definition.getValue("name")); put("description", definition.getValue("description"))
                    put("input_schema", definition.getValue("parameters"))
                }
                responses -> JsonObject(mapOf("type" to JsonPrimitive("function")) + definition)
                else -> buildJsonObject { put("type", "function"); put("function", definition) }
            }
        } + if (webSearch && (anthropic || responses)) listOf(buildJsonObject {
            put("type", if (anthropic) "web_search_20250305" else "web_search")
            if (anthropic) { put("name", "web_search"); put("max_uses", 5) }
        }) else emptyList())
        var searched = false
        val webSources = linkedSetOf<String>()
        val nativeSources = linkedSetOf<Pair<String, String>>()
        val visible = StringBuilder()
        repeat(24) {
            currentCoroutineContext().ensureActive()
            val payload = buildJsonObject {
                put("model", model); put("stream", false); put("tools", tools)
                if (anthropic) { put("system", prompt); put("max_tokens", 8192); put("messages", JsonArray(transcript)) }
                else if (responses) { put("instructions", prompt); put("max_output_tokens", 8192); put("input", JsonArray(transcript)); put("store", false) }
                else { put("messages", JsonArray(transcript)); put("max_tokens", 8192) }
            }
            val url = when (protocol) {
                AiApiProtocol.ANTHROPIC_MESSAGES -> anthropicMessagesUrl(baseUrl)
                AiApiProtocol.RESPONSES -> baseUrl.trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/responses").let { if (it.endsWith("/v1")) "$it/responses" else "$it/v1/responses" }
                else -> completionsUrl(baseUrl)
            }
            val request = Request.Builder().url(url).apply {
                if (anthropic) { header("x-api-key", apiKey); header("anthropic-version", "2023-06-01") }
                else header("Authorization", "Bearer $apiKey")
            }.post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            val body = OkHttpCancellation.execute(client, request).use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errorType = runCatching { Json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("type")?.jsonPrimitive?.content }.getOrNull()
                    throw ModelException(if (response.code in listOf(401, 403)) ModelException.Kind.UNAUTHORIZED else ModelException.Kind.SERVER,
                        "文档分析失败：HTTP ${response.code}" + (errorType?.let { "（$it）" } ?: "") +
                            if (response.code == 400) "，请检查接口是否支持当前协议、文件及工具调用" else "")
                }
                Json.parseToJsonElement(text).jsonObject
            }
            onUsage(if (anthropic) (body["usage"] as? JsonObject)?.let { usage ->
                val sample = UsageParser.parseUsageObject(usage, "anthropic")
                sample.copy(inputTokens = sample.inputTokens?.let { it + (usage["cache_creation_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0) + (usage["cache_read_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0) }, cachedInputTokens = usage["cache_read_input_tokens"]?.jsonPrimitive?.longOrNull, cacheFieldsPresent = "cache_read_input_tokens" in usage)
            } else UsageParser.parse(body, if (responses) "responses" else "chat"))
            fun recordWeb(value: JsonElement) {
                when (value) {
                    is JsonObject -> {
                        val type = value["type"]?.jsonPrimitive?.contentOrNull
                        if (type == "web_search_call" || type == "server_tool_use" && value["name"]?.jsonPrimitive?.contentOrNull == "web_search") searched = true
                        if (type == "web_search_result_location" || type == "url_citation") value["url"]?.jsonPrimitive?.contentOrNull?.let { webSources += it }
                        if (type == "page_location") {
                            val document = inlineNative.getOrNull(value["document_index"]?.jsonPrimitive?.intOrNull ?: -1)
                            val page = value["start_page_number"]?.jsonPrimitive?.intOrNull
                            if (document != null && page != null && page > 0) nativeSources += document.id to "page:$page"
                        }
                        value.values.forEach(::recordWeb)
                    }
                    is JsonArray -> value.forEach(::recordWeb)
                    else -> Unit
                }
            }
            recordWeb(body)
            val calls: List<JsonObject>
            val text: String
            var paused = false
            when {
                anthropic -> {
                    val content = body["content"] as? JsonArray ?: JsonArray(emptyList())
                    transcript += buildJsonObject { put("role", "assistant"); put("content", content) }
                    calls = content.filterIsInstance<JsonObject>().filter { it["type"]?.jsonPrimitive?.content == "tool_use" }
                    text = content.filterIsInstance<JsonObject>().filter { it["type"]?.jsonPrimitive?.content == "text" }.joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }
                    paused = body["stop_reason"]?.jsonPrimitive?.content == "pause_turn"
                }
                responses -> {
                    val output = body["output"] as? JsonArray ?: JsonArray(emptyList())
                    transcript += output.filterIsInstance<JsonObject>()
                    calls = output.filterIsInstance<JsonObject>().filter { it["type"]?.jsonPrimitive?.content == "function_call" }
                    text = output.filterIsInstance<JsonObject>().flatMap { (it["content"] as? JsonArray).orEmpty() }
                        .filterIsInstance<JsonObject>().filter { it["type"]?.jsonPrimitive?.content == "output_text" }.joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }
                }
                else -> {
                    val message = body["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                        ?: throw ModelException(ModelException.Kind.INVALID_RESPONSE, "文档接口没有返回消息")
                    transcript += message
                    calls = (message["tool_calls"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                    text = (message["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                }
            }
            if (paused) { return@repeat }
            if (calls.isEmpty()) {
                visible.append(text)
                if (forceWebSearch && !searched) throw ModelException(ModelException.Kind.CONFIG_INVALID, "本轮要求联网，但接口没有执行搜索；请测试联网设置")
                if (visible.isBlank()) throw ModelException(ModelException.Kind.INVALID_RESPONSE, "文档模型没有返回有效答案")
                if (requireRead && inlineNative.isEmpty() && session.sources.isEmpty())
                    throw ModelException(ModelException.Kind.INVALID_RESPONSE, "模型没有读取附件内容，请使用支持工具调用的模型")
                val parsed = ReplyParser.parse(visible.toString())
                val references = (session.sources + nativeSources).map { (id, locator) ->
                    val document = session.attachments.getValue(id)
                    "[${document.name.replace("[", "").replace("]", "")} · $locator](moge-document://$id?locator=${android.net.Uri.encode(locator)})"
                } + inlineNative.filter { document -> nativeSources.none { it.first == document.id } }.map { document -> "[${document.name.replace("[", "").replace("]", "")}](moge-document://${document.id})" } +
                    webSources.map { "[$it]($it)" }
                val reply = if (references.isEmpty()) parsed.reply else parsed.reply + "\n\n来源：" + references.distinct().joinToString("；")
                onEvent(StreamEvent.AnswerDelta(visible.toString()))
                val truncated = when {
                    anthropic -> body["stop_reason"]?.jsonPrimitive?.contentOrNull == "max_tokens"
                    responses -> (body["incomplete_details"] as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull == "max_output_tokens"
                    else -> body["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("finish_reason")?.jsonPrimitive?.contentOrNull == "length"
                }
                return parsed.copy(reply = reply, truncated = truncated, warnings = parsed.warnings + listOfNotNull(
                    "本次回答未联网".takeIf { webSearch && !searched },
                    "PDF 超过本轮原生请求预算，已改用按页工具读取".takeIf { native.isNotEmpty() && inlineNative.isEmpty() },
                ))
            }
            val results = calls.map { call ->
                val function = if (!anthropic && !responses) call["function"]!!.jsonObject else call
                val name = function["name"]!!.jsonPrimitive.content
                val arguments = if (anthropic) call["input"] as? JsonObject ?: JsonObject(emptyMap()) else
                    runCatching { Json.parseToJsonElement(function["arguments"]!!.jsonPrimitive.content).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                onEvent(StreamEvent.ReasoningDelta("\n正在读取文档：$name ${arguments["locator"]?.jsonPrimitive?.content.orEmpty()}\n"))
                val result = session.execute(name, arguments, vision)
                val id = call[if (responses) "call_id" else "id"]!!.jsonPrimitive.content
                Triple(id, name, result)
            }
            if (anthropic) transcript += buildJsonObject {
                put("role", "user"); put("content", buildJsonArray { results.forEach { (id, _, result) ->
                    add(buildJsonObject {
                        put("type", "tool_result"); put("tool_use_id", id); put("is_error", result.isError)
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", result.text) })
                            result.image?.let { add(imageBlock(it, protocol)) }
                        })
                    })
                } })
            } else results.forEach { (id, _, result) ->
                transcript += if (responses) buildJsonObject { put("type", "function_call_output"); put("call_id", id); put("output", result.text) }
                    else buildJsonObject { put("role", "tool"); put("tool_call_id", id); put("content", result.text) }
                result.image?.let { image ->
                    transcript += buildJsonObject { put("role", "user"); put("content", JsonArray(listOf(
                        buildJsonObject { put("type", if (responses) "input_text" else "text"); put("text", result.text) },
                        imageBlock(image, protocol),
                    ))) }
                }
            }
        }
        throw ModelException(ModelException.Kind.CONFIG_INVALID, "本轮文档读取达到 24 轮上限，请缩小范围或继续分析剩余部分")
    }

    private fun imageBlock(url: String, protocol: AiApiProtocol) = buildJsonObject {
        when (protocol) {
            AiApiProtocol.ANTHROPIC_MESSAGES -> {
                put("type", "image"); put("source", buildJsonObject {
                    put("type", "base64"); put("media_type", url.substringAfter("data:").substringBefore(';')); put("data", url.substringAfter(','))
                })
            }
            AiApiProtocol.RESPONSES -> { put("type", "input_image"); put("image_url", url) }
            else -> { put("type", "image_url"); put("image_url", buildJsonObject { put("url", url) }) }
        }
    }
}
