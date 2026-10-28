package com.moge.app.data.llm.tools

import android.content.Context
import com.moge.app.data.credential.AiApiProtocol
import com.moge.app.data.document.DocumentStore
import com.moge.app.data.document.DocumentTools
import com.moge.app.data.llm.*
import com.moge.app.data.parse.ParsedReply
import com.moge.app.data.parse.orderedFigures
import com.moge.app.domain.ConversationMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal data class ToolSolveResult(val reply: ParsedReply, val citations: List<ModelClient.Citation>)

/** Bounded solve harness, shared by ordinary questions and document questions. */
@Singleton
class SolveToolClient @Inject constructor(
    private val renderer: FigureToolRenderer,
    @param:ApplicationContext private val context: Context,
    private val documentStore: DocumentStore,
) {
    private val rejectedEndpoints = linkedMapOf<String, Long>()

    /** Null means an explicit tool-capability rejection before generation; the caller may use the inline contract. */
    internal suspend fun run(
        client: OkHttpClient, request: Request, initialPayload: JsonObject, protocol: AiApiProtocol,
        messages: List<ChatMessage>, vision: Boolean, recoveryFields: JsonObject,
        onUsage: (UsageSample?) -> Unit, onEvent: suspend (StreamEvent) -> Unit,
        recoverySystem: String? = null,
        autoResumeOnNetworkLoss: Boolean = false,
    ): ToolSolveResult? = coroutineScope {
        val key = compatibilityKey(request, initialPayload)
        if (synchronized(rejectedEndpoints) { rejectedEndpoints[key]?.let { System.nanoTime() - it < TimeUnit.MINUTES.toNanos(10) } == true }) return@coroutineScope null
        val documents = documents(messages)
        val figures = FigureTools(this, renderer)
        val responses = protocol == AiApiProtocol.RESPONSES
        val sequenceKey = if (responses) "input" else "messages"
        val transcript = (initialPayload[sequenceKey] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().toMutableList()
        var base = initialPayload
        if (documents != null) {
            val instructions = "\n" + DocumentTools.INSTRUCTIONS + "\n本轮可访问的附件：" + documents.manifest()
            if (responses) base = JsonObject(base + ("instructions" to JsonPrimitive(base["instructions"]!!.jsonPrimitive.content + instructions)))
            else transcript[0] = JsonObject(transcript[0] + ("content" to JsonPrimitive(transcript[0]["content"]!!.jsonPrimitive.content + instructions)))
        }
        val definitions = FigureToolCatalog.definitions + if (documents != null) DocumentTools.definitions.map { JsonObject(it + ("strict" to JsonPrimitive(false))) } else emptyList()
        val declaredTools = JsonArray(definitions.map { definition ->
            if (responses) JsonObject(mapOf("type" to JsonPrimitive("function")) + definition)
            else buildJsonObject { put("type", "function"); put("function", definition) }
        } + (initialPayload["tools"] as? JsonArray).orEmpty())
        val transport = ToolStreamingTransport(client, protocol)
        val reasoning = StringBuilder()
        val citations = mutableListOf<ModelClient.Citation>()
        var searched = false
        var pendingReset = false
        var recovering = false
        var limitReached = false
        var retainedReply = ""
        var interruptedRaw = ""
        val response = ConversationResponse(ConversationMode.SOLVE)
        val maxToolRounds = if (documents == null) MAX_TOOL_ROUNDS else MAX_DOCUMENT_ROUNDS
        for (round in 0..maxToolRounds + 2) {
            currentCoroutineContext().ensureActive()
            val allowTools = round < maxToolRounds && !recovering
            val payload = buildJsonObject {
                base.filterKeys { it !in setOf(sequenceKey, "tools", "tool_choice", "response_format", "text") &&
                    !(recovering && it == "web_search") }.forEach { (k, v) -> put(k, v) }
                put("stream", true)
                if (responses) put("store", false)
                else put("stream_options", buildJsonObject { put("include_usage", true) })
                if (allowTools) {
                    put("tools", if (round == 0) declaredTools else JsonArray(declaredTools.map { tool ->
                        if ((tool as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "web_search") JsonObject(tool - "force_search") else tool
                    }))
                    if (round == 0) base["tool_choice"]?.let { put("tool_choice", it) }
                } else {
                    for (field in listOf("response_format", "text")) base[field]?.let { put(field, it) }
                }
                if (recovering) recoveryFields.forEach { (k, v) -> put(k, v) }
                put(sequenceKey, JsonArray(transcript))
                if (responses) put("include", JsonArray(listOf(JsonPrimitive("reasoning.encrypted_content"))))
            }
            val rawRound = StringBuilder()
            val bridge = retainedReply.takeIf { it.isNotEmpty() }?.let { ContinuationStreamBridge(it, response, onEvent) }
            val output = try {
                transport.execute(request, payload, onUsage) { event ->
                    if (event is StreamEvent.AnswerDelta) rawRound.append(event.text)
                    if (event is StreamEvent.AnswerDelta && pendingReset) {
                        if (bridge == null) onEvent(StreamEvent.AnswerReset)
                        pendingReset = false
                    }
                    if (bridge == null) onEvent(event) else bridge.accept(event)
                }
            } catch (error: Exception) {
                val visible = response.visible(rawRound.toString())
                val session = currentCoroutineContext()[GenerationNetworkSession]
                if (isRecoverableNetworkFailure(error) && visible.isNotBlank() && session?.claimContinuation(autoResumeOnNetworkLoss) == true) {
                    interruptedRaw = rawRound.toString()
                    retainedReply = mergeContinuation(retainedReply, visible)
                    session.awaitContinuation()
                    // Keep every completed call/result, encrypted reasoning, document source and successful figure.
                    transcript += buildJsonObject { put("role", "assistant"); put("content", buildJsonObject { put("reply", retainedReply.takeLast(CONTINUATION_ECHO_CHARS)) }.toString()) }
                    transcript += userMessage(response.continuationInstruction + "\n沿用前文工具结果及已绘图片的原图号，不重复读取或重画成功的图。")
                    continue
                }
                if (isRecoverableNetworkFailure(error)) throw ToolNetworkInterruption(error)
                if (error !is ModelException || round != 0 || !isFunctionToolsRejected(error)) throw error
                synchronized(rejectedEndpoints) {
                    rejectedEndpoints.remove(key); rejectedEndpoints[key] = System.nanoTime()
                    while (rejectedEndpoints.size > 16) rejectedEndpoints.remove(rejectedEndpoints.keys.first())
                }
                onEvent(StreamEvent.ReasoningDelta("\n当前接口明确不支持绘图工具，使用兼容绘图协议\n"))
                return@coroutineScope null
            }
            reasoning.append(output.reasoning)
            if (reasoning.length > ModelClient.MAX_REASONING_CAPTURE_CHARS) reasoning.delete(0, reasoning.length - ModelClient.MAX_REASONING_CAPTURE_CHARS)
            citations += output.citations; searched = searched || output.searched
            if (output.calls.isNotEmpty()) {
                if (!allowTools) throw ModelException(ModelException.Kind.INVALID_RESPONSE, "工具调用已到上限，模型仍未完成回答；请缩小范围后重试")
                if (output.calls.size > MAX_CALLS_PER_ROUND) throw ModelException(ModelException.Kind.INVALID_RESPONSE, "单轮工具调用过多，请缩小范围")
                transcript += output.replay
                val images = mutableListOf<JsonObject>()
                for (call in output.calls) {
                    currentCoroutineContext().ensureActive()
                    onEvent(StreamEvent.ReasoningDelta("\n${toolStatus(call.name)}\n"))
                    val arguments = runCatching { Json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()
                    val documentTool = call.name in DocumentTools.definitions.map { it["name"]!!.jsonPrimitive.content }
                    if (arguments != null && documentTool && documents != null) {
                        val result = documents.execute(call.name, arguments, vision)
                        transcript += toolOutput(call.id, result.text, responses)
                        result.image?.let { image -> images += imageMessage(result.text, image, responses) }
                        continue
                    }
                    val result = if (arguments == null) """{"status":"error","error":"工具参数必须是合法JSON对象，请修正后重试"}"""
                    else figures.execute(call.name, arguments)
                    transcript += toolOutput(call.id, result, responses)
                }
                transcript += images
                pendingReset = pendingReset || output.text.isNotEmpty()
                if (round + 1 >= maxToolRounds) {
                    limitReached = true
                    transcript += userMessage("本轮工具调用已到上限。请依据已成功获得的结果完成最终答案JSON，无法完成的图片如实说明；不要继续调用工具。")
                }
                continue
            }
            val parsed = response.parse(output.text)
            val needsRecovery = output.text.isBlank() || isPlaceholderReply(parsed.reply) && response.visible(output.text).length < MIN_MEANINGFUL_CHARS
            if (needsRecovery && !recovering) {
                recovering = true
                recoverySystem?.let { system ->
                    val documentRules = if (documents != null) "\n" + DocumentTools.INSTRUCTIONS + "\n本轮可访问的附件：" + documents.manifest() else ""
                    if (responses) base = JsonObject(base + ("instructions" to JsonPrimitive(system + documentRules)))
                    else transcript[0] = JsonObject(transcript[0] + ("content" to JsonPrimitive(system + documentRules)))
                }
                transcript += output.replay
                transcript += userMessage(response.recoveryInstruction)
                pendingReset = pendingReset || output.text.isNotEmpty()
                onEvent(StreamEvent.ReasoningDelta("\n正在补全最终回答\n"))
                continue
            }
            if (needsRecovery) throw ModelException(ModelException.Kind.INVALID_RESPONSE, "模型未返回完整最终答案，请重试或降低思考强度")
            if (messages.lastOrNull { it.role == "user" }?.documentReadRequired == true && documents != null && documents.sources.isEmpty())
                throw ModelException(ModelException.Kind.INVALID_RESPONSE, "模型没有读取附件内容，请使用支持工具调用的模型")
            val references = documents?.sources.orEmpty().map { (id, locator) ->
                val document = documents!!.attachments.getValue(id)
                "[${document.name.replace("[", "").replace("]", "")} · $locator](moge-document://$id?locator=${android.net.Uri.encode(locator)})"
            }
            val reconciled = if (bridge == null) parsed.copy(reply = response.reconcile(response.visible(output.text), parsed.reply)) else bridge.finish(parsed)
            val completed = recoveredMetadata(reconciled, interruptedRaw)
            val reply = completed.reply +
                if (references.isNotEmpty()) "\n\n来源：" + references.distinct().joinToString("；") else ""
            figures.awaitRenders()
            val toolFigures = figures.figures.takeIf { it.isNotEmpty() }
            return@coroutineScope ToolSolveResult(completed.copy(
                reply = reply, rawReasoning = reasoning.toString(), truncated = output.truncated, toolFigures = toolFigures,
                warnings = parsed.warnings + listOfNotNull(
                    "已请求联网，但接口未返回搜索记录或来源".takeIf { (initialPayload["tools"].toString().contains("web_search") || initialPayload["web_search"] != null) && !searched },
                    "本轮工具调用达到上限，已根据现有结果完成回答".takeIf { limitReached },
                    "工具图已保留，最终答案中重复的内联图参数已忽略".takeIf { toolFigures != null && parsed.orderedFigures().isNotEmpty() },
                ),
            ), citations.distinctBy { it.url })
        }
        throw ModelException(ModelException.Kind.INVALID_RESPONSE, "模型未能在工具调用上限内完成回答")
    }

    private suspend fun documents(messages: List<ChatMessage>): DocumentTools? {
        val paths = messages.flatMap { it.documentPaths }.distinct()
        if (paths.isEmpty()) return null
        val attachments = paths.map { documentStore.attachment(it, verifyHash = true) }
        if (messages.lastOrNull { it.role == "user" }?.documentReadRequired == true &&
            attachments.all { File(it.path).extension.lowercase() in setOf("doc", "ppt", "xls") })
            throw ModelException(ModelException.Kind.CONFIG_INVALID, "原件已保留。旧版 DOC/PPT/XLS 请先转换为 DOCX/PPTX/XLSX 或 PDF 后分析")
        return DocumentTools(context, attachments)
    }

    private fun compatibilityKey(request: Request, payload: JsonObject): String {
        val identity = request.url.toString() + payload["model"].toString() + request.header("Authorization").orEmpty()
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun toolStatus(name: String) = when (name) {
        "get_figure_guide" -> "正在读取所需绘图说明"
        "render_figure" -> "正在校验并绘制图片"
        else -> "正在读取文档"
    }

    private fun toolOutput(id: String, text: String, responses: Boolean) = buildJsonObject {
        if (responses) { put("type", "function_call_output"); put("call_id", id); put("output", text) }
        else { put("role", "tool"); put("tool_call_id", id); put("content", text) }
    }

    private fun userMessage(text: String) = buildJsonObject { put("role", "user"); put("content", text) }

    private fun imageMessage(text: String, image: String, responses: Boolean) = buildJsonObject {
        put("role", "user"); put("content", buildJsonArray {
            add(buildJsonObject { put("type", if (responses) "input_text" else "text"); put("text", text) })
            add(buildJsonObject {
                put("type", if (responses) "input_image" else "image_url")
                if (responses) put("image_url", image)
                else put("image_url", buildJsonObject { put("url", image) })
            })
        })
    }

    companion object {
        internal const val MAX_TOOL_ROUNDS = 6
        private const val MAX_DOCUMENT_ROUNDS = 24
        private const val MAX_CALLS_PER_ROUND = 8
    }
}

/** No fallback on auth, rate limits, schemas, timeouts, body failures or generic 400 errors. */
internal fun isFunctionToolsRejected(error: ModelException): Boolean {
    if (error.kind != ModelException.Kind.SERVER || error.rejectedHttpStatus !in listOf(400, 422)) return false
    val body = error.message.orEmpty()
    if (body.contains("web_search", true)) return false
    if (Regex("schema|properties|additionalProperties|tools.{0,80}parameters", RegexOption.IGNORE_CASE).containsMatchIn(body)) return false
    return Regex("(?:unknown|unrecognized|unsupported|not supported|does not support|not allowed|不支持|未知)[^\\n]{0,65}(?:function(?:_call| calling| tools)?|tools|tool_choice)|(?:function(?:_call| calling| tools)?|tools|tool_choice)[^\\n]{0,65}(?:not supported|unsupported|not allowed|不支持)", RegexOption.IGNORE_CASE)
        .containsMatchIn(body)
}
