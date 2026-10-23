package com.moge.app.data.llm

import android.util.Log
import com.moge.app.core.IoDispatcher
import com.moge.app.data.credential.AiCredentialStore
import com.moge.app.data.credential.AiReasoningEffort
import com.moge.app.data.credential.AiApiProtocol
import com.moge.app.data.document.DocumentAgentClient
import com.moge.app.data.credential.AiSearchProtocol
import com.moge.app.data.parse.ParsedReply
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.domain.SolveMode
import com.moge.app.domain.ConversationMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 直连大模型异常。界面据此给出「未配置 / 密钥错误 / 网络 / 服务异常」等提示。 */
class ModelException(
    val kind: Kind,
    message: String,
    /**
     * 是否允许回退到「离线 Chat Completions」路径。
     *
     * **默认 false**：绝大多数错误（401/403/429、超时、断流、服务端失败）
     * 换协议重发都会重复计费，甚至把两次响应的正文混在一起。
     * 只有「联网搜索这一层明确不可用」这类兼容性信号才置 true。
     */
    val isSearchFallbackAllowed: Boolean = false,
    /** Status of a rejected HTTP request, before any generated content. */
    val rejectedHttpStatus: Int? = null,
) : Exception(message) {
    enum class Kind { NOT_CONFIGURED, CONFIG_INVALID, UNAUTHORIZED, NETWORK, SERVER, INVALID_RESPONSE }
}

/** 流式输出的增量事件。 */
sealed interface StreamEvent {
    data class ReasoningDelta(val text: String) : StreamEvent
    data class AnswerDelta(val text: String) : StreamEvent
    data object AnswerReset : StreamEvent
}

/**
 * App 直连 OpenAI 兼容 大模型接口。
 *
 * 接口地址、模型名、密钥全部由用户在设置页填写，换任意一家 OpenAI 兼容服务
 * （DeepSeek / 月之暗面 / 智谱 / OpenAI / OpenRouter 等）都不需要改代码。
 * 密钥由 [AiCredentialStore] 用 Android Keystore 加密，不进备份。
 *
 * 系统提示词内置在 [SYSTEM_PROMPT]，由 [PromptAssembler] 分段组装后随请求发出。
 */
@Singleton
class ModelClient @Inject constructor(
    private val settings: SettingsRepository,
    private val credentialStore: AiCredentialStore,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val documentAgent: DocumentAgentClient? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val capabilityCatalog = ModelCapabilityCatalog()

    /** Allow slow first tokens and pauses in reasoning; cancellation still closes the socket immediately. */
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Model operations share the IO dispatcher and classify failures throughout body consumption. */
    private suspend fun <T> modelRequest(block: suspend () -> T): T = withContext(io) {
        withModelNetworkErrors { block() }
    }

    suspend fun isConfigured(): Boolean = withContext(io) { credentialStore.resolveActiveIdentity() != null }

    suspend fun isVisionEnabled(): Boolean = withContext(io) {
        credentialStore.activeProfile()?.visionEnabled ?: false
    }

    /**
     * 流式发送一轮对话，结束后解析结构化回复。
     *
     * [imageBase64s] 非空时，以 OpenAI 兼容的 vision 内容块附在当前问题上；
     * 只有多模态模型才应传图（由准备阶段按照片路线决定）。
     *
     * [webSearchEnabled] 为 true 且协议未关闭时：
     * - Responses 协议 → 走服务端内置联网搜索；
     * - Chat Completions 协议 → 注入 web_search 工具（如小米 MiMo）。
     *
     * 联网降级与「服务端没真的搜索」都会在 [ParsedReply.warnings] 里留下说明，
     * 最终由界面展示给用户——静默降级会让联网问题完全无法定位。
     */
    suspend fun chatStreaming(
        messages: List<ChatMessage>,
        imageBase64s: List<String> = emptyList(),
        solveMode: SolveMode = SolveMode.DETAILED,
        webSearchEnabled: Boolean = false,
        forceWebSearch: Boolean = false,
        /** 每次**真实 HTTP 调用**上报一次本次 usage（缺失上报 null）。 */
        onUsage: (UsageSample?) -> Unit = {},
        /**
         * 重试/续写用的**不可变请求策略**：钉下档案 id、安全端点身份与推理档位。
         * null 表示走当前活动档案（新提交）。整轮 HTTP（含恢复/回退）只用
         * 这里解析出的那一份配置，中途绝不重读活动档案。
         */
        policy: RequestPolicy? = null,
        onEvent: suspend (StreamEvent) -> Unit,
    ): ParsedReply = modelRequest {
        val response = ConversationResponse(policy?.conversationMode ?: ConversationMode.SOLVE)
        val configured = resolveConfigured(policy).copy(conversationMode = response.mode)
        val prompt = PromptAssembler.reusablePrompt(policy?.prompt, solveMode, response.mode) ?: PromptAssembler.assemble(
            protocol = SYSTEM_PROMPT,
            nickname = settings.current().nickname,
            reasoningEffort = configured.reasoningEffort,
            solveMode = solveMode,
            conversationMode = response.mode,
        )
        val searchProtocol = effectiveSearchProtocol(configured.baseUrl, configured.searchProtocol)
        // 联网开关用**本轮生效值**（重试时来自快照，不是当前设置）。
        val webSearchOn = webSearchEnabled &&
            configured.effectiveWebSearchEnabled &&
            searchProtocol != AiSearchProtocol.OFF
        val apiProtocol = configured.apiProtocol ?: when {
            webSearchOn && searchProtocol == AiSearchProtocol.RESPONSES -> AiApiProtocol.RESPONSES
            else -> AiApiProtocol.CHAT_COMPLETIONS
        }
        if (messages.any { it.documentPaths.isNotEmpty() }) {
            return@modelRequest run {
                (documentAgent ?: throw ModelException(ModelException.Kind.CONFIG_INVALID, "文档分析模块不可用"))
                    .run(client, configured.baseUrl, configured.model, configured.apiKey, apiProtocol,
                    configured.visionEnabled, promptMessageSequence(messages, imageBase64s, prompt), emptyList(),
                    prompt.stableSystem,
                    webSearchOn, forceWebSearch, onUsage, onEvent,
                    requireRead = messages.lastOrNull { it.role == "user" }?.documentReadRequired == true,
                    conversationMode = response.mode, reasoningEffort = configured.reasoningEffort,
                    capability = configured.capability)
            }
        }
        var searchNote: String? = null
        if (apiProtocol == AiApiProtocol.RESPONSES) {
            try {
                return@modelRequest chatDeepSeekNativeResponses(
                    configured, messages, imageBase64s, prompt, forceWebSearch, onUsage, onEvent, webSearchOn, response,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelException) {
                // ⚠️ **真实的服务端/鉴权/网络错误不降级**：换协议重发会
                // ①重复计费 ②把两次响应的正文混在一起。这里只允许
                // 「联网搜索这一层不可用」的明确信号回退。
                if (!e.isSearchFallbackAllowed || configured.apiProtocol == AiApiProtocol.RESPONSES) throw e
                Log.w(TAG, "联网搜索不可用，改用离线回答", e)
                searchNote = "联网搜索未生效，已改用离线回答（${e.message ?: "未知原因"}）"
            }
        }
        val chatCompletionsSearch = webSearchOn && searchProtocol == AiSearchProtocol.CHAT_COMPLETIONS
        val payload = buildChatPayload(
            configured,
            messages,
            imageBase64s,
            prompt,
            stream = true,
            webSearchEnabled = chatCompletionsSearch,
        )
        val request = Request.Builder()
            .url(completionsUrl(configured.baseUrl))
            .header("Authorization", "Bearer ${configured.apiKey}")
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        val firstOutput = executeGenerationStreaming(request, payload, onEvent, onUsage)
        val firstParsed = response.parse(firstOutput.answer)
        val firstStreamedReply = response.visible(firstOutput.answer)
        val needsAnswer = firstOutput.answer.isBlank() && (firstOutput.reasoning.isNotBlank() || firstOutput.finishReason == "length") ||
            response.mode == ConversationMode.SOLVE && isPlaceholderReply(firstParsed.reply) && firstStreamedReply.length < MIN_MEANINGFUL_CHARS
        val output = if (needsAnswer) {
            onEvent(StreamEvent.ReasoningDelta("\n\n…正在补全最终回答…\n"))
            onEvent(StreamEvent.AnswerReset)
            try {
                recoverFinalAnswer(
                    configured = configured,
                    messages = messages,
                    imageBase64s = imageBase64s,
                    prompt = prompt,
                    previous = firstOutput,
                    onUsage = onUsage,
                    onEvent = onEvent,
                    instruction = response.recoveryInstruction,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "final answer recovery failed", error)
                firstOutput
            }
        } else {
            firstOutput
        }
        val answer = output.answer.trim()
        if (answer.isEmpty() && output.reasoning.isNotBlank()) {
            throw ModelException(
                ModelException.Kind.INVALID_RESPONSE,
                "模型在补充生成后仍未返回最终答案，请重试或降低思考强度；思考过程未作为回答保存",
            )
        }
        if (answer.isEmpty()) {
            throw ModelException(
                ModelException.Kind.INVALID_RESPONSE,
                if (output.finishReason == "length") "模型输出达到长度上限，未生成最终答案" else "模型未返回有效内容",
            )
        }
        val parsedAnswer = response.parse(answer)
        val visibleAnswer = response.reconcile(response.visible(answer), parsedAnswer.reply)
        if (response.mode == ConversationMode.SOLVE && isPlaceholderReply(visibleAnswer)) {
            throw ModelException(
                ModelException.Kind.INVALID_RESPONSE,
                "模型未返回完整正文，请重试；简短的引用说明未作为回答保存",
            )
        }
        val base = parsedAnswer.copy(
            reply = visibleAnswer,
            truncated = output.finishReason == "length",
            rawReasoning = output.reasoning,
            warnings = parsedAnswer.warnings + output.warnings,
        )
        val parsed = if (searchNote == null) base else base.copy(warnings = base.warnings + searchNote)
        withCitations(parsed, output.citations)
    }

    /** 把去重后的联网来源以 Markdown 链接列表追加到正文末尾。 */
    private fun withCitations(parsed: ParsedReply, citations: List<Citation>): ParsedReply {
        val distinct = citations.distinctBy(Citation::url)
        if (distinct.isEmpty()) return parsed
        return parsed.copy(
            reply = buildString {
                append(parsed.reply)
                append("\n\n### 来源\n")
                distinct.forEach { citation ->
                    append("- [")
                    append(citation.title.replace("[", "").replace("]", "").ifBlank { citation.url })
                    append("](").append(citation.url).append(")\n")
                }
            }.trim(),
        )
    }

    /**
     * Fetches model ids with the selected provider's native headers and endpoint.
     *
     * [apiKey] 可以为空：此时依次回退到 [profileId] 对应配置已保存的密钥、
     * 以及当前生效配置的密钥。这样编辑已有配置时（密钥框出于安全不回显，
     * 始终为空）也能随时获取模型列表，且可重复点击。
     */
    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String = "",
        profileId: String? = null,
        protocol: AiSearchProtocol? = null,
    ): List<String> = fetchModelCatalog(baseUrl, apiKey, profileId, protocol).ids

    suspend fun fetchModelCatalog(
        baseUrl: String,
        apiKey: String = "",
        profileId: String? = null,
        protocol: AiSearchProtocol? = null,
    ): ModelCatalog = modelRequest {
        require(baseUrl.isNotBlank()) { "请填写接口地址" }
        val key = resolveFetchKey(
            inputKey = apiKey,
            profileKey = profileId?.let { credentialStore.credentialsFor(it)?.apiKey },
            activeKey = credentialStore.activeProfileId()?.let { credentialStore.credentialsFor(it)?.apiKey },
        )
        require(key.isNotEmpty()) { "请填写 API 密钥" }
        capabilityCatalog.fetch(client, baseUrl, key)
    }

    /**
     * 用一次最小请求测试连通性与密钥，返回给用户看的一句话结果。
     *
     * [profileId] 为空时测当前生效配置；设置页编辑某个配置时传它的 id。
     */
    suspend fun testConnection(profileId: String? = null): String = withContext(io) {
        val identity = (profileId?.let(credentialStore::resolveIdentityFor) ?: credentialStore.resolveActiveIdentity())
            ?: throw ModelException(
                ModelException.Kind.NOT_CONFIGURED,
                "请先保存接口地址、模型名和 API 密钥",
            )
        val model = identity.model
        val baseUrl = identity.baseUrl
        val apiKey = identity.apiKey
        val responses = identity.apiProtocol == AiApiProtocol.RESPONSES
        val payload = if (responses) buildJsonObject {
            put("model", model); put("input", "ping"); put("max_output_tokens", 16); put("store", false)
        } else buildJsonObject {
            put("model", model)
            put("max_tokens", 1)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", "ping") })
            })
        }
        val request = Request.Builder()
            .url(if (responses) deepSeekResponsesUrl(baseUrl) else completionsUrl(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        OkHttpCancellation.execute(client, request).use { response ->
            when {
                response.isSuccessful -> "连接成功，模型「$model」工作正常"
                response.code == 401 || response.code == 403 ->
                    throw ModelException(
                        ModelException.Kind.UNAUTHORIZED,
                        "API 密钥被拒绝（HTTP ${response.code}），请核对后重新保存",
                    )
                else -> {
                    val hint = response.body?.string().orEmpty().take(160)
                    throw ModelException(
                        ModelException.Kind.SERVER,
                        "接口返回 HTTP ${response.code}：$hint",
                    )
                }
            }
        }
    }

    /**
     * 联网搜索自检：用一条必须依赖实时信息的问题打一次请求，报告
     * HTTP 状态、服务端是否真的发起搜索、拿到几条来源。
     *
     * 正式对话里联网失败是静默降级的（自动退回普通对话、界面无提示），
     * 没有这个入口就无法区分到底是开关、协议、模型还是服务端的问题。
     */
    suspend fun testWebSearch(
        baseUrl: String? = null,
        apiKey: String? = null,
        model: String? = null,
        protocol: AiSearchProtocol? = null,
        profileId: String? = null,
    ): String = withContext(io) {
        val configured = resolveSearchTarget(baseUrl, apiKey, model, protocol, profileId)
        val chosen = effectiveSearchProtocol(configured.baseUrl, configured.searchProtocol)
        if (chosen == AiSearchProtocol.OFF) {
            return@withContext "这套配置的联网协议是「关闭」，不会发起搜索。请选择服务支持的联网协议再测。"
        }
        val primary = probeWebSearch(configured, chosen)
        val other = if (chosen == AiSearchProtocol.RESPONSES) {
            AiSearchProtocol.CHAT_COMPLETIONS
        } else {
            AiSearchProtocol.RESPONSES
        }
        val text = StringBuilder("模型：").append(configured.model).append('\n')
        if (chosen != configured.searchProtocol) {
            text.append("（该端点的 Responses 网关不支持 web_search 工具，已自动改用 Chat Completions 检测）\n")
        }
        text.append('\n').append(primary.render("当前"))
        if (!primary.searched && chosen == AiSearchProtocol.RESPONSES) {
            // 再打一次「官方最小示例」形态的请求：只有 model / input / tools，
            // 用来排除「是不是我们自己附加的字段把搜索挤掉了」这一可能。
            val minimal = probeWebSearch(configured, chosen, minimal = true)
            text.append("\n\n").append(minimal.render("最小请求"))
            text.append(
                if (minimal.searched) {
                    "\n\n→ 精简到官方示例的字段就能搜到，说明是本地请求里的额外字段影响了搜索。"
                } else {
                    "\n\n→ 连官方最小示例都搜不到，说明该模型的服务端搜索当前未生效，请求格式没有问题。"
                },
            )
        } else if (!primary.searched) {
            text.append("\n\n").append(probeWebSearch(configured, other).render("备选"))
        }
        text.toString()
    }

    /**
     * 解析自检目标：优先用「编辑器里正在编辑的这套配置」，字段缺省时回退到当前生效配置。
     * 这样模型还没保存也能先测联网，密钥框留空则沿用该配置已存的密钥。
     */
    private suspend fun resolveSearchTarget(
        baseUrl: String?,
        apiKey: String?,
        model: String?,
        protocol: AiSearchProtocol?,
        profileId: String?,
    ): ConfiguredModel {
        val active = credentialStore.activeProfile()
        val targetBaseUrl = baseUrl?.trim().orEmpty().ifEmpty { active?.baseUrl.orEmpty().trim() }
        val targetModel = model?.trim().orEmpty().ifEmpty { active?.model.orEmpty().trim() }
        val targetKey = resolveFetchKey(
            inputKey = apiKey.orEmpty(),
            profileKey = profileId?.let { credentialStore.credentialsFor(it)?.apiKey },
            activeKey = credentialStore.activeProfileId()?.let { credentialStore.credentialsFor(it)?.apiKey },
        )
        require(targetBaseUrl.isNotEmpty()) { "请填写接口地址" }
        require(targetModel.isNotEmpty()) { "请填写模型名" }
        require(targetKey.isNotEmpty()) { "请先填写并保存 API 密钥" }
        return ConfiguredModel(
            baseUrl = targetBaseUrl,
            model = targetModel,
            apiKey = targetKey,
            searchProtocol = protocol ?: active?.searchProtocol ?: AiSearchProtocol.RESPONSES,
            reasoningEffort = active?.reasoningEffort ?: AiReasoningEffort.LOW,
            // 自检本身就是在测联网，不看全局开关。
            effectiveWebSearchEnabled = true,
            capability = capabilityCatalog.resolve(client, targetBaseUrl, targetKey, targetModel),
        )
    }

    /**
     * 对齐官方最小示例的请求：只有 model / input / tools。
     *
     * 官方文档给的就是这么一条，用来判断「搜不到」是发生在我们的请求构造上，
     * 还是发生在服务端本身。
     */
    private fun buildMinimalWebSearchPayload(
        configured: ConfiguredModel,
        prompt: String,
    ): JsonObject = buildJsonObject {
        put("model", configured.model)
        put("input", prompt)
        if (configured.requestWebSearch) put("tools", buildJsonArray { add(buildJsonObject { put("type", "web_search") }) })
        put("stream", false)
    }

    /** 用一种协议探测一次联网搜索，结果与是否真的搜过一起返回。 */
    private suspend fun probeWebSearch(
        configured: ConfiguredModel,
        protocol: AiSearchProtocol,
        minimal: Boolean = false,
    ): WebSearchProbe {
        val responses = protocol == AiSearchProtocol.RESPONSES
        val url = when {
            responses -> deepSeekResponsesUrl(configured.baseUrl)
            else -> completionsUrl(configured.baseUrl)
        }
        val prompt = "请联网检索后回答：今天有什么重要的科技新闻？只说一条标题即可。"
        val messages = listOf(ChatMessage("user", prompt))
        val payload = when {
            minimal && responses -> buildMinimalWebSearchPayload(configured, prompt)
            responses -> buildDeepSeekResponsesPayload(
                configured, messages, emptyList(), probePrompt(configured), forceWebSearch = true,
            )
            else -> buildChatPayload(
                configured, messages, emptyList(), probePrompt(configured), stream = false, webSearchEnabled = true,
            )
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${configured.apiKey}")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        val started = System.currentTimeMillis()
        val code: Int
        val body: String
        try {
            OkHttpCancellation.execute(client, request).use { resp ->
                code = resp.code
                body = resp.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            return WebSearchProbe(protocol, url, error = "网络错误：${e.message ?: "无法连接"}")
        }
        val elapsed = System.currentTimeMillis() - started
        if (code !in 200..299) {
            return WebSearchProbe(protocol, url, code, elapsed, error = "HTTP $code：${body.take(160)}")
        }
        if (responses) {
            val output = parseDeepSeekResponses(body)
            return WebSearchProbe(
                protocol, url, code, elapsed, output.searched,
                output.citations.distinctBy { it.url }.size, output.text.trim().take(100),
            )
        }
        val message = runCatching {
            val root = Json.parseToJsonElement(body) as? JsonObject
            ((root?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
        }.getOrNull()
        val found = mutableListOf<Citation>()
        extractDeepSeekCitations(message?.get("citations"), found)
        extractDeepSeekCitations(message?.get("annotations"), found)
        val distinct = found.distinctBy { it.url }
        return WebSearchProbe(
            protocol, url, code, elapsed,
            searched = distinct.isNotEmpty() || "web_search" in body || "search_result" in body,
            citations = distinct.size,
            preview = extractText(message?.get("content")).trim().take(100),
        )
    }

    private fun probePrompt(configured: ConfiguredModel): PromptSegments =
        PromptAssembler.assemble(SYSTEM_PROMPT, "", configured.reasoningEffort, SolveMode.DETAILED)

    private data class WebSearchProbe(
        val protocol: AiSearchProtocol,
        val url: String,
        val code: Int = 0,
        val elapsedMs: Long = 0,
        val searched: Boolean = false,
        val citations: Int = 0,
        val preview: String = "",
        val error: String? = null,
    ) {
        fun render(tag: String): String = buildString {
            val name = when (protocol) {
                AiSearchProtocol.RESPONSES -> "Responses"
                AiSearchProtocol.CHAT_COMPLETIONS -> "Chat Completions"
                AiSearchProtocol.OFF -> "关闭"
            }
            append("【$tag · $name】")
            if (error != null) {
                append(error)
                return@buildString
            }
            append("HTTP $code · ${elapsedMs}ms · ")
            append(if (searched) "已发起搜索" else "未发起搜索")
            append(" · 来源 $citations 条")
            if (preview.isNotBlank()) append("\n回答摘要：$preview")
            append("\n端点：$url")
        }
    }

    /**
     * 以指定模型配置做一次非流式调用（不占用当前激活配置）。
     * 用于识题模型的题目转写。
     */
    suspend fun completeWithProfile(
        profileId: String,
        systemPrompt: String,
        userText: String,
        imageBase64s: List<String> = emptyList(),
        maxTokens: Int? = null,
    ): String = modelRequest {
        val credentials = credentialStore.credentialsFor(profileId)
            ?: throw ModelException(
                ModelException.Kind.NOT_CONFIGURED,
                "所选多模态模型配置不存在或缺少密钥，请在设置页重新选择",
            )
        val capability = capabilityCatalog.resolve(client, credentials.baseUrl, credentials.apiKey, credentials.model)
        if (credentials.apiProtocol == AiApiProtocol.RESPONSES) {
            val payload = buildJsonObject {
                put("model", credentials.model); put("instructions", systemPrompt)
                put("stream", false); put("store", false)
                ModelGenerationParameters.fields(credentials.baseUrl, credentials.model, credentials.reasoningEffort,
                    AiApiProtocol.RESPONSES, capability = capability).forEach { (key, value) -> put(key, value) }
                maxTokens?.let { put("max_output_tokens", it) }
                put("input", buildJsonArray { add(buildJsonObject {
                    put("role", "user"); put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "input_text"); put("text", userText) })
                        imageBase64s.forEach { encoded -> add(buildJsonObject {
                            put("type", "input_image"); put("image_url", "data:image/jpeg;base64,$encoded")
                        }) }
                    })
                }) })
            }
            val request = Request.Builder().url(deepSeekResponsesUrl(credentials.baseUrl))
                .header("Authorization", "Bearer ${credentials.apiKey}")
                .post(payload.toString().toRequestBody(mediaType)).build()
            val raw = executeBudgetedJsonRequest(client, request, payload, "responses")
            return@modelRequest parseDeepSeekResponses(raw.toString()).text.trim().ifEmpty {
                throw ModelException(ModelException.Kind.INVALID_RESPONSE, "多模态模型未返回有效内容")
            }
        }
        val payload = buildJsonObject {
            put("model", credentials.model)
            put("temperature", 0.2)
            ModelGenerationParameters.fields(credentials.baseUrl, credentials.model, credentials.reasoningEffort,
                AiApiProtocol.CHAT_COMPLETIONS, capability = capability).forEach { (key, value) -> put(key, value) }
            maxTokens?.let { put(capability?.chatOutputParameter ?: "max_tokens", it) }
            put("stream", false)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", systemPrompt) })
                add(buildJsonObject {
                    put("role", "user")
                    if (imageBase64s.isEmpty()) {
                        put("content", userText)
                    } else {
                        put("content", buildJsonArray {
                            if (userText.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", userText) })
                            imageBase64s.forEach { base64 ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$base64") })
                                })
                            }
                        })
                    }
                })
            })
        }
        val request = Request.Builder()
            .url(completionsUrl(credentials.baseUrl))
            .header("Authorization", "Bearer ${credentials.apiKey}")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        val root = executeBudgetedJsonRequest(client, request, payload, "chat-completions")
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw ModelException(ModelException.Kind.SERVER, "多模态模型未返回内容")
        val content = when (val rawContent = message["content"]) {
            is JsonPrimitive -> rawContent.contentOrNull.orEmpty()
            is JsonArray -> rawContent.mapNotNull { part ->
                (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
            }.joinToString("")
            else -> ""
        }.trim()
        if (content.isEmpty()) {
            throw ModelException(ModelException.Kind.SERVER, "多模态模型未返回有效内容")
        }
        content
    }

    /**
     * **一次解析出整轮 HTTP 使用的不可变配置**：身份与凭证取自存储层的同一把锁
     * （不会混钥），并在进入网络前完成校验 —— 配置缺失/档案被删时直接
     * CONFIG_INVALID，不会发出一个发往意外端点的请求。
     *
     * 重试路径必须用 [policy]（快照钉下的档案/身份/设置），
     * 新提交路径传 null 走当前活动档案。
     */
    private suspend fun resolveConfigured(policy: RequestPolicy?): ConfiguredModel {
        val identity = if (policy == null || policy.primaryProfileId.isBlank()) {
            credentialStore.resolveActiveIdentity()
        } else {
            credentialStore.resolveIdentityFor(policy.primaryProfileId)
        }
        if (identity == null) {
            throw ModelException(
                ModelException.Kind.NOT_CONFIGURED,
                if (policy?.primaryProfileId?.isNotBlank() == true) {
                    "提交时使用的模型配置已被删除或缺少密钥，请重新选择后再试"
                } else {
                    "还没有可用的模型：请在设置页添加接口地址、模型名与 API 密钥"
                },
            )
        }
        if (policy != null) {
            // 端点与模型都要校验：同一个档案 id 换了模型/地址都不允许静默通过。
            val currentEndpoint = endpointIdentityOf(identity.baseUrl)
            if (policy.endpointIdentity.isNotBlank() && currentEndpoint != policy.endpointIdentity) {
                throw ModelException(
                    ModelException.Kind.NOT_CONFIGURED,
                    "模型端点已改变（要求 ${policy.endpointIdentity}，当前 $currentEndpoint），请重新发送而不是重试",
                )
            }
            if (policy.model.isNotBlank() && identity.model != policy.model) {
                throw ModelException(
                    ModelException.Kind.NOT_CONFIGURED,
                    "模型已改变（要求 ${policy.model}，当前 ${identity.model}），请重新发送而不是重试",
                )
            }
        }
        // 推理档位与联网协议以**策略**（快照）为权威。
        val effort = policy?.reasoningEffort?.takeIf { it.isNotBlank() }?.let { named ->
            runCatching { AiReasoningEffort.valueOf(named) }.getOrNull()
        } ?: identity.reasoningEffort
        val protocol = policy?.searchProtocol?.takeIf { it.isNotBlank() }?.let { named ->
            runCatching { AiSearchProtocol.valueOf(named.uppercase()) }.getOrNull()
        } ?: identity.searchProtocol
        return ConfiguredModel(
            baseUrl = identity.baseUrl,
            model = identity.model,
            apiKey = identity.apiKey,
            searchProtocol = protocol,
            reasoningEffort = effort,
            effectiveWebSearchEnabled = policy?.effectiveWebSearchEnabled ?: (settings.current().webSearchEnabled && identity.searchEnabled),
            apiProtocol = policy?.apiProtocol?.takeIf { it.isNotBlank() }?.let { AiApiProtocol.valueOf(it) } ?: identity.apiProtocol.takeIf { policy == null },
            visionEnabled = policy?.visionEnabled ?: identity.visionEnabled,
            capability = capabilityCatalog.resolve(client, identity.baseUrl, identity.apiKey, identity.model),
        )
    }

    private data class ConfiguredModel(
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val searchProtocol: AiSearchProtocol,
        val reasoningEffort: AiReasoningEffort,
        /** 全局联网开关的**本轮生效值**（重试时来自快照，不读当前开关）。 */
        val effectiveWebSearchEnabled: Boolean,
        val apiProtocol: AiApiProtocol? = null,
        val visionEnabled: Boolean = false,
        val requestWebSearch: Boolean = true,
        val finalAnswer: Boolean = false,
        val capability: ModelCapability? = null,
        /** Null for configuration probes, whose prompts do not request solving JSON. */
        val conversationMode: ConversationMode? = null,
    ) {
        /** 安全端点身份（主机+路径），用于诊断归集与重试校验。 */
        val endpointIdentity: String get() = endpointIdentityOf(baseUrl)
    }

    private fun buildChatPayload(
        configured: ConfiguredModel,
        messages: List<ChatMessage>,
        imageBase64s: List<String>,
        prompt: PromptSegments,
        stream: Boolean,
        webSearchEnabled: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("model", configured.model)
        put("temperature", 0.4)
        put("stream", stream)
        configured.conversationMode?.let { mode ->
            solveOutputFormatFields(mode, configured.capability, AiApiProtocol.CHAT_COMPLETIONS)
                .forEach { (key, value) -> put(key, value) }
        }
        ModelGenerationParameters.fields(
            baseUrl = configured.baseUrl,
            model = configured.model,
            effort = configured.reasoningEffort,
            protocol = AiApiProtocol.CHAT_COMPLETIONS,
            finalAnswer = configured.finalAnswer,
            capability = configured.capability,
        ).forEach { (key, value) -> put(key, value) }
        // Chat Completions 的联网方式各家不同，必须分开写：
        // - 小米 MiMo：在 tools 里声明 web_search（配 force_search 强制检索），已实测可用；
        // - 其它（如 DeepSeek）：tools 只接受 type=function，塞 web_search 会直接 400
        //   （unknown variant `web_search`），改用顶层 web_search 开关字段。
        if (webSearchEnabled) {
            if (isMiMoEndpoint(configured.baseUrl)) {
                put("tools", buildJsonArray {
                    add(buildJsonObject { put("type", "web_search"); put("force_search", true) })
                })
            } else {
                put("web_search", buildJsonObject { put("enabled", true) })
            }
        }
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", prompt.stableSystem) })
            promptMessageSequence(messages, imageBase64s, prompt).forEach { message ->
                add(buildJsonObject {
                    put("role", message.role)
                    if (message.imageBase64s.isNotEmpty()) {
                        put("content", buildJsonArray {
                            if (message.content.isNotBlank()) add(buildJsonObject {
                                put("type", "text"); put("text", message.content)
                            })
                            message.imageBase64s.forEach { base64 -> add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$base64") })
                            }) }
                        })
                    } else put("content", message.content)
                })
            }
        })
    }

    private data class StreamOutput(
        val answer: String,
        val reasoning: String,
        val finishReason: String?,
        val citations: List<Citation>,
        /** 本次 HTTP 请求归一化后的 usage；null = 供应商未提供。 */
        val usage: UsageSample? = null,
        val searched: Boolean = false,
        val warnings: List<String> = emptyList(),
    )

    internal data class Citation(val title: String, val url: String)

    /** Continues a reasoning-only response in a fresh request so the model has a new output budget. */
    private suspend fun recoverFinalAnswer(
        configured: ConfiguredModel,
        messages: List<ChatMessage>,
        imageBase64s: List<String>,
        prompt: PromptSegments,
        previous: StreamOutput,
        onUsage: (UsageSample?) -> Unit,
        onEvent: suspend (StreamEvent) -> Unit,
        instruction: String,
    ): StreamOutput {
        currentCoroutineContext().ensureActive()
        // Keep the original picture with the original question, not the recovery instruction.
        val recoveryMessages = messages.mapIndexed { index, message ->
            if (index == messages.lastIndex && message.role == "user" && imageBase64s.isNotEmpty()) {
                message.copy(imageBase64s = imageBase64s)
            } else message
        } + listOf(
            ChatMessage(
                role = "assistant",
                content = "上一轮回答与内部分析仅用于补全，不要逐字复述：\n\n" +
                    previous.answer.takeLast(2000) + "\n\n" + previous.reasoning.takeLast(RECOVERY_REASONING_CHARS),
            ),
            ChatMessage(
                role = "user",
                content = instruction,
            ),
        )
        val payload = buildChatPayload(
            configured = configured.copy(reasoningEffort = AiReasoningEffort.LOW, finalAnswer = true),
            messages = recoveryMessages,
            imageBase64s = emptyList(),
            prompt = finalAnswerPrompt(prompt, configured.reasoningEffort),
            stream = true,
            webSearchEnabled = false,
        )
        val request = Request.Builder()
            .url(completionsUrl(configured.baseUrl))
            .header("Authorization", "Bearer ${configured.apiKey}")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        val recovered = executeGenerationStreaming(request, payload, onEvent, onUsage)
        return recovered.copy(
            reasoning = (previous.reasoning + recovered.reasoning).takeLast(MAX_REASONING_CAPTURE_CHARS),
            citations = previous.citations + recovered.citations,
            // 恢复是**另一次 HTTP 请求**，它有自己的 usage：按本次请求记录，不相加。
            usage = recovered.usage,
            searched = previous.searched || recovered.searched,
            warnings = previous.warnings + recovered.warnings,
        )
    }

    private suspend fun executeGenerationStreaming(request: Request, payload: JsonObject,
        onEvent: suspend (StreamEvent) -> Unit, onUsage: (UsageSample?) -> Unit): StreamOutput {
        val negotiation = GenerationParameterNegotiation(payload)
        while (true) {
            try {
                val current = request.newBuilder().post(negotiation.payload.toString().toRequestBody(mediaType)).build()
                return executeStreaming(current, onEvent, onUsage)
            } catch (error: ModelException) {
                negotiation.afterRejection(error) ?: throw error
            }
        }
    }

    private suspend fun executeStreaming(
        request: Request,
        onEvent: suspend (StreamEvent) -> Unit,
        /**
         * 每次**真实 HTTP 调用**上报一次：拿到 usage 就上报样本，
         * 供应商没给该字段就上报 `null`（计为「未知」，不是「0 命中」）。
         * 绝不能因为 null 就跳过 —— 那样「缺字段的调用」根本不计入统计。
         */
        onUsage: (UsageSample?) -> Unit = {},
    ): StreamOutput {
        val usageAccumulator = UsageAccumulator("chat-completions")
        try {
        // 取消必须能立刻打断阻塞读：注册在 execute 之前，覆盖整个 response.use。
        val response = try {
            OkHttpCancellation.execute(client, request)
        } catch (e: IOException) {
            throw ModelException(
                ModelException.Kind.NETWORK,
                "网络错误：${e.message ?: "无法连接模型接口"}",
            )
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty()
                val kind = if (resp.code == 401 || resp.code == 403) {
                    ModelException.Kind.UNAUTHORIZED
                } else {
                    ModelException.Kind.SERVER
                }
                throw ModelException(kind, "接口返回 HTTP ${resp.code}：${text.take(2048).ifEmpty { "无返回内容" }}", rejectedHttpStatus = resp.code)
            }
            val body = resp.body ?: throw ModelException(
                ModelException.Kind.INVALID_RESPONSE,
                "模型接口没有返回响应体",
            )
            val answer = StringBuilder()
            val reasoning = StringBuilder()
            val citations = mutableListOf<Citation>()
            var finishReason: String? = null
            var sawSse = false
            var sawTerminal = false
            val raw = StringBuilder()
            // 同一请求的累计 usage 只能统计一次：这里保留最后一次有效上报，绝不累加。
            val source = body.source()
            val progress = GenerationProgressWatchdog()
            source.readSseEvents(onRawLine = { raw.append(it); progress.progress() }, onLine = progress::check) { sse ->
                val data = sse.data
                if (!sawSse && data.isNotBlank()) sawSse = true
                if (data == "[DONE]") {
                    sawTerminal = true
                    // 明确终止：不再等 EOF（阻塞读会一直挂着）。
                    return@readSseEvents false
                }
                if (data.isBlank()) return@readSseEvents true
                val chunk = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull()
                    ?: return@readSseEvents true
                sawSse = true
                // usage-only 末块（choices 为空）也要处理：先于 choices 解析。
                usageAccumulator.accept(UsageParser.parse(chunk, "chat-completions"))
                citations += extractCitations(chunk["citations"])
                val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                    ?: return@readSseEvents true
                val reason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
                if (reason != null) {
                    progress.progress()
                    finishReason = reason
                    // 有 finish_reason 就是一次合法的收尾（长度上限或被内容过滤都算）。
                    sawTerminal = true
                    // 保留后续 usage-only 末块（choices 为空），直到 [DONE] 或 EOF。
                }
                val message = (choice["delta"] as? JsonObject) ?: (choice["message"] as? JsonObject)
                    ?: return@readSseEvents true
                citations += extractCitations(message["annotations"])
                citations += extractCitations(message["citations"])
                // 有的网关把 usage 挂在 choice 上。
                usageAccumulator.accept(UsageParser.parse(choice, "chat-completions"))
                extractText(
                    message["reasoning_content"] ?: message["reasoning"] ?: message["reasoning_details"],
                ).takeIf { it.isNotEmpty() }?.let {
                    progress.progress()
                    reasoning.appendTail(it, MAX_REASONING_CAPTURE_CHARS)
                    onEvent(StreamEvent.ReasoningDelta(it))
                }
                extractText(message["content"]).takeIf { it.isNotEmpty() }?.let {
                    progress.progress()
                    answer.append(it)
                    onEvent(StreamEvent.AnswerDelta(it))
                }
                true
            }
            if (!sawSse) {
                val root = runCatching { json.parseToJsonElement(raw.toString()) as? JsonObject }.getOrNull()
                    ?: throw ModelException(ModelException.Kind.INVALID_RESPONSE, "模型返回不是合法 JSON")
                val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                val message = choice?.get("message") as? JsonObject
                citations += extractCitations(root["citations"])
                citations += extractCitations(message?.get("annotations"))
                citations += extractCitations(message?.get("citations"))
                finishReason = (choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull
                usageAccumulator.accept(UsageParser.parse(root, "chat-completions"))
                val reasoningText = extractText(
                    message?.get("reasoning_content") ?: message?.get("reasoning") ?: message?.get("reasoning_details"),
                )
                val answerText = extractText(message?.get("content"))
                if (reasoningText.isNotEmpty()) {
                    reasoning.appendTail(reasoningText, MAX_REASONING_CAPTURE_CHARS)
                    onEvent(StreamEvent.ReasoningDelta(reasoningText))
                }
                if (answerText.isNotEmpty()) {
                    answer.append(answerText)
                    onEvent(StreamEvent.AnswerDelta(answerText))
                }
                sawTerminal = true
            } else if (!sawTerminal) {
                // 即使已有部分正文，没有终止事件的 EOF 仍是断流，不能保存为完整回答。
                throw ModelException(
                    ModelException.Kind.NETWORK,
                    "流式响应没有正常结束（未收到终止标记），已按中断处理",
                )
            }
            // usage 上报：**每次真实 HTTP 调用都要上报一次**。
            // 供应商没给 usage 字段时上报 null（「未知」），不能跳过 ——
            // 跳过会让「缺字段的调用」从统计里凭空消失，覆盖率随之虚高。
            val usage = usageAccumulator.result()
            return StreamOutput(
                answer.toString(),
                reasoning.toString(),
                finishReason,
                citations,
                usage,
            )
        }
        } finally {
            onUsage(usageAccumulator.result())
        }
    }

    private fun extractCitations(element: JsonElement?): List<Citation> = (element as? JsonArray).orEmpty()
        .mapNotNull { annotation ->
            if (annotation is JsonPrimitive) {
                val url = annotation.contentOrNull?.trim().orEmpty()
                return@mapNotNull url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    ?.let { Citation("", it) }
            }
            val obj = annotation as? JsonObject ?: return@mapNotNull null
            val citation = (obj["url_citation"] as? JsonObject) ?: obj
            val url = (citation["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
            val title = (citation["title"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            Citation(title, url)
        }

    /** 从 OpenAI 兼容响应里取出 choices[0].message.content。 */
    private suspend fun executeForContent(request: Request): String {
        val response = try {
            OkHttpCancellation.execute(client, request)
        } catch (e: IOException) {
            throw ModelException(
                ModelException.Kind.NETWORK,
                "网络错误：${e.message ?: "无法连接模型接口"}",
            )
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (resp.code == 401 || resp.code == 403) {
                    throw ModelException(
                        ModelException.Kind.UNAUTHORIZED,
                        "API 密钥被拒绝（HTTP ${resp.code}）",
                    )
                }
                throw ModelException(
                    ModelException.Kind.SERVER,
                    "接口返回 HTTP ${resp.code}：${text.take(200).ifEmpty { "无返回内容" }}",
                )
            }
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: throw ModelException(
                    ModelException.Kind.INVALID_RESPONSE,
                    "模型返回不是合法 JSON",
                )
            val content = root["choices"]?.jsonArray
                ?.firstOrNull()?.let { it as? JsonObject }
                ?.get("message")?.let { it as? JsonObject }
                ?.get("content")?.let(::extractText)
            if (content.isNullOrBlank()) {
                throw ModelException(
                    ModelException.Kind.INVALID_RESPONSE,
                    "模型未返回有效内容",
                )
            }
            return content
        }
    }

    // ---------------- DeepSeek 服务端内置联网搜索（OpenAI Responses API） ----------------

    private suspend fun chatDeepSeekNativeResponses(
        configured: ConfiguredModel,
        messages: List<ChatMessage>,
        imageBase64s: List<String>,
        prompt: PromptSegments,
        forceWebSearch: Boolean,
        onUsage: (UsageSample?) -> Unit,
        onEvent: suspend (StreamEvent) -> Unit,
        webSearchEnabled: Boolean = true,
        responseFormat: ConversationResponse = ConversationResponse(ConversationMode.SOLVE),
        allowRecovery: Boolean = true,
    ): ParsedReply {
        val configured = configured.copy(requestWebSearch = webSearchEnabled)
        suspend fun finish(output: DeepSeekResponsesOutput, warning: String? = null): ParsedReply {
            if (allowRecovery && output.text.isBlank() && (output.thinking.isNotBlank() || output.truncated)) {
                onEvent(StreamEvent.ReasoningDelta("\n\n…正在补全最终回答…\n"))
                onEvent(StreamEvent.AnswerReset)
                val recoveryMessages = messages.mapIndexed { index, message ->
                    if (index == messages.indexOfLast { it.role == "user" } && imageBase64s.isNotEmpty()) message.copy(imageBase64s = imageBase64s) else message
                } + listOf(ChatMessage("assistant", "上一轮分析仅供核对：\n" + output.thinking.takeLast(RECOVERY_REASONING_CHARS)),
                    ChatMessage("user", responseFormat.recoveryInstruction))
                val recovered = chatDeepSeekNativeResponses(configured.copy(reasoningEffort = AiReasoningEffort.LOW, finalAnswer = true),
                    recoveryMessages, emptyList(), finalAnswerPrompt(prompt, configured.reasoningEffort), false,
                    onUsage, onEvent, false, responseFormat, allowRecovery = false)
                return withCitations(recovered.copy(rawReasoning = (output.thinking + recovered.rawReasoning).takeLast(MAX_REASONING_CAPTURE_CHARS)), output.citations)
            }
            return finalizeResponsesReply(output, warning, webSearchEnabled, responseFormat)
        }
        // 先尝试**真正的逐片流式**：Responses 端点支持 stream 时，
        // response.output_text.delta 会随生成逐片到达，用户能在结束前看到正文。
        //
        // 回退条件被刻意收得很窄（见 [StreamUnsupportedSignal] 的抛出点）：
        // 只有「端点明确拒绝 stream 参数」或「端点根本没按 SSE 回、且尚未产出任何正文」
        // 才回退。401/403/429、超时、response.failed、已产生正文后的断流
        // 都是**真实错误**，直接中断，绝不换协议重发（那会重复计费并混合两部分正文）。
        val streamed = try {
            executeResponsesStreaming(
                configured, messages, imageBase64s, prompt, forceWebSearch, onUsage, onEvent,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: StreamUnsupportedSignal) {
            null
        } catch (e: StreamNonSseSignal) {
            // 端点回了 HTTP 200 + JSON（忽略了 stream 参数）：**就地解析**，
            // 不再发第二次请求。早先这里把已经拿到的 200 JSON 丢掉重发一次，
            // 等于白花一次计费请求。
            return finish(
                e.parsed,
                warning = "Responses 接口未启用流式，本次正文在生成结束后展示",
            )
        }
        if (streamed != null) {
            return finish(streamed.output)
        }
        // 走到这里说明流式请求被端点**明确拒绝**（StreamUnsupportedSignal）：
        // 才允许再发一次非流式请求。
        val payload = buildDeepSeekResponsesPayload(
            configured, messages, imageBase64s, prompt, forceWebSearch,
        )
        val request = Request.Builder()
            .url(deepSeekResponsesUrl(configured.baseUrl))
            .header("Authorization", "Bearer ${configured.apiKey}")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        val body = executeBudgetedJsonRequest(client, request, payload, "responses", onUsage)
        return finish(parseDeepSeekResponses(body.toString()),
            warning = "Responses 接口未启用流式，本次正文在生成结束后展示")
    }

    /** Responses 流式路径中「端点不支持」的信号，用于静默回退完整读取。 */
    private class StreamUnsupportedSignal(message: String) : Exception(message)

    /**
     * 端点返回了 HTTP 200 但**不是 SSE**（忽略了 `stream` 参数）。
     *
     * 携带**已经解析好的**输出，调用方就地使用 —— 不再发第二次请求。
     */
    private class StreamNonSseSignal(
        val parsed: DeepSeekResponsesOutput,
        val usage: UsageSample?,
        message: String,
    ) : Exception(message)

    private data class ResponsesStreamResult(
        val output: DeepSeekResponsesOutput,
        val usage: UsageSample?,
    )

    /**
     * Responses API 的真实流式读取。
     *
     * 只解析**实际事件**（SSE `event:`/`data:` 对），把
     * `response.output_text.delta` 作为增量正文、`response.reasoning_summary_text.delta`
     * 作为增量推理分别上报；`response.output_text.annotation.added` 收集来源；
     * `response.completed` 里取最终 usage。
     *
     * 抛 [StreamUnsupportedSignal] 表示「这个端点不支持流式」，由调用方回退。
     */
    private suspend fun executeResponsesStreaming(
        configured: ConfiguredModel,
        messages: List<ChatMessage>,
        imageBase64s: List<String>,
        prompt: PromptSegments,
        forceWebSearch: Boolean,
        onUsage: (UsageSample?) -> Unit,
        onEvent: suspend (StreamEvent) -> Unit,
        negotiation: GenerationParameterNegotiation? = null,
    ): ResponsesStreamResult {
        val parameters = negotiation ?: GenerationParameterNegotiation(JsonObject(
            buildDeepSeekResponsesPayload(
                configured, messages, imageBase64s, prompt, forceWebSearch,
            ) + ("stream" to JsonPrimitive(true)),
        ))
        val payload = parameters.payload
        val request = Request.Builder()
            .url(deepSeekResponsesUrl(configured.baseUrl))
            .header("Authorization", "Bearer ${configured.apiKey}")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        val usageAccumulator = UsageAccumulator("responses")
        var usageReported = false
        currentCoroutineContext().ensureActive()
        try {
        val response = try {
            OkHttpCancellation.execute(client, request)
        } catch (e: IOException) {
            throw ModelException(
                ModelException.Kind.NETWORK,
                "网络错误：${e.message ?: "无法连接 DeepSeek 接口"}",
            )
        }
        return response.use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                if (resp.code == 404) throw ModelException(
                    ModelException.Kind.SERVER,
                    "Responses 请求返回 HTTP 404；请核对接口地址和模型名。若此网关仅提供 Chat Completions，请在模型设置切换接口协议",
                )
                // 401/403/429 是**鉴权或限流**问题，换一种协议再发一次毫无意义，
                // 而且会重复计费。只有明确的「不支持该参数/工具」才允许回退。
                if (resp.code == 401 || resp.code == 403) {
                    throw ModelException(
                        ModelException.Kind.UNAUTHORIZED,
                        "API 密钥被拒绝（HTTP ${resp.code}）",
                    )
                }
                if (resp.code == 429) {
                    throw ModelException(
                        ModelException.Kind.SERVER,
                        "请求被限流（HTTP 429）：${body.take(200).ifEmpty { "请稍后重试" }}",
                    )
                }
                if (isStreamUnsupportedResponse(resp.code, body)) {
                    throw StreamUnsupportedSignal("HTTP ${resp.code}: ${body.take(160)}")
                }
                // 其余 4xx/5xx 都是**真实错误**，直接中断，不再换协议重发。
                throw ModelException(
                    ModelException.Kind.SERVER,
                    "模型接口返回 HTTP ${resp.code}：${body.take(2048).ifEmpty { "无返回内容" }}",
                    rejectedHttpStatus = resp.code,
                )
            }
            val body = resp.body ?: throw ModelException(
                ModelException.Kind.INVALID_RESPONSE,
                "DeepSeek 没有返回响应体",
            )
            val contentType = resp.header("Content-Type").orEmpty()
            val text = StringBuilder()
            val thinking = StringBuilder()
            var truncated = false
            val citations = mutableListOf<Citation>()
            var searched = false
            var sawEvent = false
            var sawCompleted = false
            var streamError: String? = null
            var failedTerminal = false
            // 非 SSE 的 200 响应体：原样留存，供就地解析（不重发请求）。
            val rawStreamBody = StringBuilder()
            val source = body.source()
            val progress = GenerationProgressWatchdog()
            source.readSseEvents(onRawLine = { rawStreamBody.append(it); progress.progress() }, onLine = progress::check) { sse ->
                val data = sse.data
                if (data.isEmpty() || data == "[DONE]") return@readSseEvents true
                val chunk = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull()
                    ?: return@readSseEvents true
                sawEvent = true
                // JSON 里没有 type 时，用 SSE 的 event 名当类型。
                val type = (chunk["type"] as? JsonPrimitive)?.contentOrNull ?: sse.name.orEmpty()
                when (type) {
                    "response.output_text.delta" -> {
                        val delta = (chunk["delta"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        if (delta.isNotEmpty()) {
                            progress.progress()
                            text.append(delta)
                            onEvent(StreamEvent.AnswerDelta(delta))
                        }
                    }
                    "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                        val delta = (chunk["delta"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        if (delta.isNotEmpty()) {
                            progress.progress()
                            thinking.appendTail(delta, MAX_REASONING_CAPTURE_CHARS)
                            onEvent(StreamEvent.ReasoningDelta(delta))
                        }
                    }
                    "response.output_text.annotation.added" -> {
                        extractDeepSeekCitations(chunk["annotation"], citations)
                        searched = true
                    }
                    "response.web_search_call.searching", "response.web_search_call.completed" -> searched = true
                    "response.completed" -> {
                        sawCompleted = true
                        val respObj = chunk["response"] as? JsonObject
                        usageAccumulator.accept(
                            (respObj?.get("usage") as? JsonObject)?.let {
                                UsageParser.parseUsageObject(it, "responses")
                            },
                        )
                        // completed 里带有完整的 output：用它补齐 citations 与 searched 标记，
                        // 但**正文以增量累积为准**（两者应一致，增量已实时展示过）。
                        respObj?.let { final ->
                            val parsed = parseDeepSeekResponses(final.toString())
                            citations += parsed.citations
                            if (parsed.searched) searched = true
                            if (text.isEmpty() && parsed.text.isNotEmpty()) {
                                text.append(parsed.text)
                                onEvent(StreamEvent.AnswerDelta(parsed.text))
                            }
                            if (thinking.isEmpty() && parsed.thinking.isNotEmpty()) {
                                thinking.appendTail(parsed.thinking, MAX_REASONING_CAPTURE_CHARS)
                                onEvent(StreamEvent.ReasoningDelta(parsed.thinking))
                            }
                        }
                        // 成功终态已收齐（usage 也在上面取过）：不再等 EOF。
                        return@readSseEvents false
                    }
                    "response.failed", "error" -> {
                        failedTerminal = true
                        streamError = (chunk["message"] as? JsonPrimitive)?.contentOrNull
                            ?: ((chunk["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
                            ?: "服务端报告失败"
                    }
                    // 明确的不完整终态：同样不得重发，按失败处理。
                    "response.incomplete" -> {
                        val terminal = chunk["response"] as? JsonObject
                        if ((terminal?.get("incomplete_details") as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull == "max_output_tokens") {
                            truncated = true
                            sawCompleted = true
                            usageAccumulator.accept(terminal["usage"]?.let { it as? JsonObject }?.let { UsageParser.parseUsageObject(it, "responses") })
                            val parsed = parseDeepSeekResponses(terminal.toString())
                            citations += parsed.citations
                            searched = searched || parsed.searched
                            if (text.isEmpty() && parsed.text.isNotEmpty()) { text.append(parsed.text); onEvent(StreamEvent.AnswerDelta(parsed.text)) }
                            if (thinking.isEmpty()) thinking.appendTail(parsed.thinking, MAX_REASONING_CAPTURE_CHARS)
                            return@readSseEvents false
                        } else {
                            failedTerminal = true
                            streamError = "服务端返回了不完整的结果（response.incomplete）"
                        }
                    }
                    else -> Unit
                }
                // 失败/不完整终态已是明确结论：停止读取（keep-open 连接不能等 EOF），
                // 错误由下面的既有失败路径抛出，绝不重发。
                !failedTerminal
            }
            // 兼容路径判据：Content-Type 明确是 JSON 且不是 event-stream。
            // 只有在这种「端点根本没按 SSE 回」的情况下才允许就地解析，
            // 且必须**尚未产生任何正文**（否则会丢掉已经展示给用户的内容）。
            val looksJson = contentType.contains("application/json", ignoreCase = true) &&
                !contentType.contains("event-stream", ignoreCase = true)
            // response.failed / error：这是服务端明确的失败终态，**不得**协议回退重发。
            streamError?.let {
                throw ModelException(ModelException.Kind.SERVER, "联网搜索请求失败：$it")
            }
            // 端点忽略 stream 参数、直接回完整 JSON：**就地解析**这份 200 响应体，
            // 绝不丢弃后重发（那会白花一次计费请求）。
            // 只有在**没有任何正文**时才允许这样做。
            if (looksJson && !sawEvent && text.isEmpty() && thinking.isEmpty()) {
                val jsonText = rawStreamBody.toString()
                val usage = runCatching {
                    val root = json.parseToJsonElement(jsonText) as? JsonObject
                    (root?.get("usage") as? JsonObject)?.let {
                        UsageParser.parseUsageObject(it, "responses")
                    }
                }.getOrNull()
                usageAccumulator.accept(usage)
                val parsed = parseDeepSeekResponses(jsonText)
                throw StreamNonSseSignal(
                    parsed = parsed,
                    usage = usage,
                    message = "端点未按 SSE 返回（Content-Type=$contentType）",
                )
            }
            // 有 event 但从未出现 completed：EOF 属于**断流**，不能当成功保存。
            if (sawEvent && !sawCompleted && !failedTerminal) {
                throw ModelException(
                    ModelException.Kind.NETWORK,
                    "流式响应没有正常结束（未收到 response.completed），已按中断处理",
                )
            }
            if (!sawEvent && text.isEmpty() && thinking.isEmpty()) {
                // 既没有事件、也没有正文、也不是明确 JSON：空响应。
                throw ModelException(
                    ModelException.Kind.INVALID_RESPONSE,
                    "DeepSeek 返回了空响应体",
                )
            }
            ResponsesStreamResult(
                DeepSeekResponsesOutput(
                    text = text.toString(),
                    thinking = thinking.toString(),
                    citations = citations,
                    searched = searched || citations.isNotEmpty(),
                    truncated = truncated,
                ),
                usageAccumulator.result(),
            )
        }
        } catch (error: ModelException) {
            val compatible = parameters.afterRejection(error)
            if (compatible == null) throw error
            // Record the rejected HTTP before the retry, in request order.
            usageReported = true
            onUsage(usageAccumulator.result())
            return executeResponsesStreaming(configured, messages, imageBase64s, prompt, forceWebSearch,
                onUsage, onEvent, parameters)
        } finally {
            if (!usageReported) onUsage(usageAccumulator.result())
        }
    }

    /**
     * 这个 4xx 是否**明确表示端点不接受 stream**。
     *
     * 判据必须窄：只有响应体里出现「不支持/未知参数 `stream`」这类**明确措辞**
     * 才算。早先的实现把所有非 401/403 的 4xx 都当成「不支持流式」并重发一次，
     * 于是限流(429)、参数错误、内容策略拒绝都会**多花一次计费请求**。
     */
    /**
     * 这个 4xx 是否**明确表示端点不接受 `stream` 参数**。
     *
     * 回退（换协议重发一次）的代价是**多一次计费请求**，而且如果第一次其实
     * 已经开始输出，两次响应的正文还会被混在一起。所以判据必须很窄 ——
     * 只有响应体**明确点名 `stream` 参数**才算：
     *
     * - ✅ `Unknown parameter: 'stream'` / `unsupported parameter: stream`
     *   / `stream is not supported` / API 文档式的「不支持 stream」；
     * - ❌ 泛化的 `invalid_request_error`、`unsupported`、`unknown parameter`
     *   —— 这些是 OpenAI 兼容端点的**通用错误外壳**，出现在几乎所有 400 上
     *   （提示词非法、参数类型错、内容策略拒绝……）。早先只要正文里同时出现
     *   "stream" 和 "invalid_request_error" 就回退，于是**任意 400 都会重发一次**。
     */
    private fun isStreamUnsupportedResponse(code: Int, body: String): Boolean =
        isStreamParameterRejected(code, body)


    /** Responses 路径共用的收尾：正文、来源与联网说明。 */
    private fun finalizeResponsesReply(
        output: DeepSeekResponsesOutput,
        extraWarning: String? = null,
        webSearchRequested: Boolean = true,
        response: ConversationResponse = ConversationResponse(ConversationMode.SOLVE),
    ): ParsedReply {
        val answer = output.text.trim()
        if (answer.isEmpty()) {
            throw ModelException(
                ModelException.Kind.INVALID_RESPONSE,
                "DeepSeek 未返回有效内容",
            )
        }
        val parsed = try {
            response.parse(answer)
        } catch (e: Exception) {
            throw ModelException(
                ModelException.Kind.INVALID_RESPONSE,
                "无法解析模型返回：${e.message ?: "未知错误"}",
            )
        }
        val warnings = buildList {
            if (webSearchRequested && !output.searched) {
                add(
                    "已按联网模式请求，但服务端没有返回任何搜索记录或来源" +
                        "（接口可能忽略了 web_search 工具），本次实际是离线回答",
                )
            }
            if (extraWarning != null) add(extraWarning)
        }
        val noted = if (warnings.isEmpty()) parsed else parsed.copy(warnings = parsed.warnings + warnings)
        return withCitations(noted.copy(rawReasoning = output.thinking, truncated = output.truncated), output.citations)
    }

    private fun buildDeepSeekResponsesPayload(
        configured: ConfiguredModel,
        messages: List<ChatMessage>,
        imageBase64s: List<String>,
        prompt: PromptSegments,
        forceWebSearch: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", configured.model)
        ModelGenerationParameters.fields(configured.baseUrl, configured.model, configured.reasoningEffort,
            AiApiProtocol.RESPONSES, configured.finalAnswer, configured.capability).forEach { (key, value) -> put(key, value) }
        configured.conversationMode?.let { mode ->
            solveOutputFormatFields(mode, configured.capability, AiApiProtocol.RESPONSES)
                .forEach { (key, value) -> put(key, value) }
        }
        // Fixed rules stay in instructions; each historical turn keeps its original context in input.
        put("instructions", prompt.stableSystem)
        if (configured.requestWebSearch) put("tools", buildJsonArray { add(buildJsonObject { put("type", "web_search") }) })
        // 用户明确开启搜索时强制调用：默认 auto 时模型可能完全不用这个工具。
        if (forceWebSearch) {
            put("tool_choice", buildJsonObject { put("type", "web_search") })
        }
        put("input", buildJsonArray {
            promptMessageSequence(messages, imageBase64s, prompt).forEach { message ->
                add(buildJsonObject {
                    put("role", message.role)
                    if (message.imageBase64s.isNotEmpty()) {
                        put("content", buildJsonArray {
                            if (message.content.isNotBlank()) add(buildJsonObject {
                                put("type", "input_text"); put("text", message.content)
                            })
                            message.imageBase64s.forEach { base64 -> add(buildJsonObject {
                                put("type", "input_image"); put("image_url", "data:image/jpeg;base64,$base64")
                            }) }
                        })
                    } else put("content", message.content)
                })
            }
        })
    }

    internal companion object {
        const val TAG = "ModelClient"
        const val RECOVERY_REASONING_CHARS = 12_000
        const val MAX_REASONING_CAPTURE_CHARS = 64_000

        /** 识题模型的转写提示词：只转写题面，不解题。 */
        const val TRANSCRIBE_PROMPT = """你是题目转写员。把图片里的题目逐字转写成 Markdown，不要解题、不要评论。
- 数学式一律写成 LaTeX：行内用两个美元符号包住，独立公式的起止双美元符号各占一行。
- 保留题号、小问编号、选项字母和单位；表格用 Markdown 表格。
- 图形、电路、坐标图等无法转写的部分，用一句话描述关键信息（已知量、标注、连接关系），写在「【图】」后面。
- 手写的作答过程也要转写，放在最后的「【作答】」小节，原样保留错误，不要改正。
- 看不清的字写成「□」，不要猜。
只输出转写结果本身。"""

        /** 解题协议与输出格式。解题模式、称呼、时间由 [PromptAssembler] 另行注入。 */
        internal const val SYSTEM_PROMPT = SOLVE_SYSTEM_PROMPT
    }
}

/** 把用户填的接口地址规范化成 chat completions 端点。 */
internal fun completionsUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
}

/** Normalizes a provider URL to its OpenAI-compatible model listing endpoint. */
internal fun modelsUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
        .removeSuffix("/chat/completions")
        .removeSuffix("/responses")
    return if (trimmed.endsWith("/models")) trimmed else "$trimmed/models"
}

/** 该端点是不是小米 MiMo：目前只有它的 Chat Completions 支持在 tools 里声明 web_search。 */
internal fun isMiMoEndpoint(baseUrl: String): Boolean = "mimo" in baseUrl.lowercase()

/**
 * 这个 4xx 是否**明确表示端点不接受 `stream` 参数**（是否可以安全回退）。
 *
 * 抽成纯函数是为了能直接单测：回退一次 = 多一次计费请求，
 * 判宽了会重复计费，判窄了会让兼容端点无法工作。
 *
 * 判据（见 [AssistantModelClient.isStreamUnsupportedResponse] 的说明）：
 * 必须**同时**满足「点名了 stream 参数」与「明确说不支持/未知」。
 * 泛化的 `invalid_request_error` / `unsupported` 单独出现**不算** ——
 * 它们是 OpenAI 兼容端点的通用错误外壳，几乎出现在所有 400 上。
 */
internal fun isStreamParameterRejected(code: Int, body: String): Boolean {
    // 401/403 鉴权、429 限流都**不是**「不支持 stream」：换协议重发毫无意义，
    // 而且会多花一次请求（限流下还会加重限流）。
    if (code !in 400..499) return false
    if (code == 401 || code == 403 || code == 429) return false
    val lower = body.lowercase()
    // ① 必须点名 `stream` 参数本身。
    val namesStreamParameter = listOf(
        "stream'", "stream\"", "stream`",
        "parameter stream", "parameter: stream",
        "stream parameter", "stream 参数",
    ).any { it in lower } || lower.trim().endsWith("stream")
    if (!namesStreamParameter) return false
    // ② 必须明确表达「不支持 / 未知 / 不接受」。
    return listOf(
        "unsupported", "not supported", "does not support", "不支持",
        "未知参数", "unknown parameter", "unrecognized parameter",
        "unrecognised parameter", "invalid parameter", "不接受的参数",
    ).any { it in lower }
}

/**
 * 服务商实际可用的联网协议。
 *
 * MiMo 的 Responses 网关明确拒绝 web_search 工具
 * （400 `responses_feature_not_supported`：「tool type 'web_search' is not supported by this
 * gateway phase」），选了 Responses 只会白跑一次请求，这里直接改走实测可用的 Chat Completions。
 */
internal fun effectiveSearchProtocol(baseUrl: String, chosen: AiSearchProtocol): AiSearchProtocol =
    if (chosen == AiSearchProtocol.RESPONSES && isMiMoEndpoint(baseUrl)) {
        AiSearchProtocol.CHAT_COMPLETIONS
    } else {
        chosen
    }

/** 归一化到 DeepSeek OpenAI Responses API 端点。 */
internal fun deepSeekResponsesUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
        .removeSuffix("/chat/completions")
        .removeSuffix("/v1")
    return "${trimmed.trimEnd('/')}/v1/responses"
}

/** DeepSeek Responses API 解析结果。 */
internal data class DeepSeekResponsesOutput(
    val text: String,
    val thinking: String,
    val citations: List<ModelClient.Citation>,
    /**
     * 响应里是否真的出现过搜索动作或来源引用。
     *
     * 服务端可能"接受但忽略" web_search：请求返回 200、回答正常，却一次都没搜过。
     * 只有这个标记能区分「搜了但没找到引用」和「压根没搜」。
     */
    val searched: Boolean = false,
    val truncated: Boolean = false,
)

/** 解析 DeepSeek Responses API 的非流式响应：收集 output_text / 思考与 citations，并标记是否真的发起过搜索。 */
internal fun parseDeepSeekResponses(raw: String): DeepSeekResponsesOutput {
    val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?: return DeepSeekResponsesOutput("", "", emptyList(), false)
    val output = root["output"] as? JsonArray
        ?: return DeepSeekResponsesOutput("", "", emptyList(), false)

    val text = StringBuilder()
    val thinking = StringBuilder()
    val citations = mutableListOf<ModelClient.Citation>()
    var searched = false
    for (item in output) {
        val obj = item as? JsonObject ?: continue
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        when (type) {
            "message" -> {
                val content = obj["content"] as? JsonArray ?: continue
                for (part in content) {
                    val partObj = part as? JsonObject ?: continue
                    val partType = (partObj["type"] as? JsonPrimitive)?.contentOrNull
                    val partText = (partObj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    when (partType) {
                        "output_text" -> text.append(partText)
                        "output_thought" -> thinking.append(partText)
                    }
                    extractDeepSeekCitations(partObj["citations"], citations)
                    // OpenAI 兼容实现常把引用放在 annotations（url_citation）里
                    extractDeepSeekCitations(partObj["annotations"], citations)
                }
            }
            // 服务端确实发起过搜索才会出现这个 item
            "web_search_call" -> searched = true
            "reasoning" -> {
                val summary = (obj["summary"] as? JsonArray).orEmpty() + (obj["content"] as? JsonArray).orEmpty()
                for (part in summary) {
                    val partObj = part as? JsonObject ?: continue
                    if ((partObj["type"] as? JsonPrimitive)?.contentOrNull in setOf("summary_text", "reasoning_text")) {
                        thinking.append((partObj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                    }
                }
            }
            // 其余 item（function_call / custom_tool_call 等）本客户端暂不使用，忽略。
        }
    }
    return DeepSeekResponsesOutput(
        text.toString(),
        thinking.toString(),
        citations,
        searched || citations.isNotEmpty(),
        (root["incomplete_details"] as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull == "max_output_tokens",
    )
}

private fun extractDeepSeekCitations(element: JsonElement?, out: MutableList<ModelClient.Citation>) {
    val arr = element as? JsonArray ?: return
    for (c in arr) {
        val obj = c as? JsonObject ?: continue
        val url = (obj["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) continue
        val title = (obj["document_title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            .ifBlank { (obj["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty() }
        out.add(ModelClient.Citation(title, url))
    }
}

internal fun reasoningInstruction(effort: AiReasoningEffort): String = when (effort) {
    AiReasoningEffort.LOW ->
        "本轮使用低思考强度。优先快速、直接作答；普通问题不要展开冗长内部分析或反复验证。复杂题仍须在最终答案中保留必要公式和关键推导。"
    AiReasoningEffort.MEDIUM ->
        "本轮使用中等思考强度。对关键条件做充分检查，但控制内部分析长度，并及时输出完整最终答案。"
    AiReasoningEffort.HIGH ->
        "本轮使用高思考强度。适合复杂证明与多步规划；可以深入检查，但仍必须为完整最终答案保留输出空间。"
}

/** Whether this turn needs a provider-backed web search. */
internal fun shouldUseWebSearch(userPrompt: String, forced: Boolean): Boolean {
    if (forced) return true
    val normalized = userPrompt.trim().lowercase()
    return WEB_SEARCH_INTENT_MARKERS.any(normalized::contains)
}

internal fun parseModelIds(raw: String): List<String> = parseModelCatalog(raw).ids

/**
 * 解析「获取模型列表」实际使用的密钥。
 *
 * 优先级：输入框现填值 → 该配置已保存的密钥 → 当前生效配置的密钥。
 * 编辑已有配置时输入框为空（密钥不回显），前两级回退保证按钮始终可用。
 */
internal fun resolveFetchKey(inputKey: String, profileKey: String?, activeKey: String?): String =
    inputKey.trim()
        .ifEmpty { profileKey.orEmpty().trim() }
        .ifEmpty { activeKey.orEmpty().trim() }

private val WEB_SEARCH_INTENT_MARKERS = listOf(
    "搜索",
    "搜一下",
    "联网",
    "查一下",
    "查询一下",
    "最新",
    "新闻",
    "官网",
    "今年",
    "近期",
    "实时",
    "当前版本",
    "截至",
)

/** Supports both the common string content and providers returning typed content arrays. */
internal fun extractText(element: JsonElement?): String = when (element) {
    is JsonPrimitive -> element.contentOrNull.orEmpty()
    is JsonArray -> element.joinToString("") { item ->
        val obj = item as? JsonObject
        extractText(obj?.get("text") ?: obj?.get("content") ?: obj?.get("output_text") ?: obj?.get("data"))
    }
    else -> ""
}

private fun StringBuilder.appendTail(text: String, maxChars: Int) {
    append(text)
    if (length > maxChars) delete(0, length - maxChars)
}
