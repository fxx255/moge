package com.moge.app.data.llm

import android.util.Log
import com.moge.app.core.IoDispatcher
import com.moge.app.data.credential.AiCredentialStore
import com.moge.app.data.credential.AiReasoningEffort
import com.moge.app.data.credential.AiApiProtocol
import com.moge.app.data.document.DocumentAgentClient
import com.moge.app.data.credential.AiSearchProtocol
import com.moge.app.data.parse.ParsedReply
import com.moge.app.data.parse.ReplyParser
import com.moge.app.data.parse.decodeReplyIncremental
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.domain.SolveMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /** 大模型响应较慢，读超时给足；连接仍保持短超时。 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

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
    ): ParsedReply = withContext(io) {
        val configured = resolveConfigured(policy)
        val prompt = PromptAssembler.assemble(
            protocol = SYSTEM_PROMPT,
            nickname = settings.current().nickname,
            reasoningEffort = configured.reasoningEffort,
            solveMode = solveMode,
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
            return@withContext withTimeoutOrNull(10 * 60 * 1000L) {
                (documentAgent ?: throw ModelException(ModelException.Kind.CONFIG_INVALID, "文档分析模块不可用"))
                    .run(client, configured.baseUrl, configured.model, configured.apiKey, apiProtocol,
                    configured.visionEnabled, messages, imageBase64s,
                    listOf(prompt.stableSystem, prompt.volatileSystem).joinToString("\n\n"),
                    webSearchOn, forceWebSearch, onUsage, onEvent,
                    requireRead = messages.lastOrNull { it.role == "user" }?.documentReadRequired == true)
            } ?: throw ModelException(ModelException.Kind.NETWORK, "文档分析超过 10 分钟，请缩小范围后重试；原件已保留")
        }
        var searchNote: String? = null
        if (apiProtocol == AiApiProtocol.RESPONSES) {
            try {
                return@withContext chatDeepSeekNativeResponses(
                    configured, messages, imageBase64s, prompt, forceWebSearch, onUsage, onEvent, webSearchOn,
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

        val firstOutput = executeStreaming(request, onEvent, onUsage)
        val firstParsed = ReplyParser.parse(firstOutput.answer.trim(), normalizeMarkdown = false)
        val firstStreamedReply = decodeReplyIncremental(firstOutput.answer)
        val needsAnswer = firstOutput.answer.isBlank() && firstOutput.reasoning.isNotBlank() ||
            isPlaceholderReply(firstParsed.reply) && firstStreamedReply.length < MIN_MEANINGFUL_CHARS
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
                    instruction = "请重新给出对原问题独立、完整的最终回答。不得只写‘见上’、‘如上’或引用内部思考。" +
                        "正文保留必要的讲解、公式与推导。严格遵守系统要求，只输出完整 JSON 对象。",
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
        val parsedAnswer = ReplyParser.parse(answer, normalizeMarkdown = false)
        val visibleAnswer = reconcileStreamedReply(decodeReplyIncremental(answer), parsedAnswer.reply)
        if (isPlaceholderReply(visibleAnswer)) {
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
    ): List<String> = withContext(io) {
        require(baseUrl.isNotBlank()) { "请填写接口地址" }
        val key = resolveFetchKey(
            inputKey = apiKey,
            profileKey = profileId?.let { credentialStore.credentialsFor(it)?.apiKey },
            activeKey = credentialStore.activeProfileId()?.let { credentialStore.credentialsFor(it)?.apiKey },
        )
        require(key.isNotEmpty()) { "请填写 API 密钥" }
        val request = Request.Builder()
            .url(modelsUrl(baseUrl))
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        val response = try {
            OkHttpCancellation.execute(client, request)
        } catch (e: IOException) {
            throw ModelException(ModelException.Kind.NETWORK, "无法获取模型列表：${e.message}")
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ModelException(
                    if (resp.code == 401 || resp.code == 403) ModelException.Kind.UNAUTHORIZED
                    else ModelException.Kind.SERVER,
                    "获取模型列表失败（HTTP ${resp.code}）：${text.take(160)}",
                )
            }
            parseModelIds(text).ifEmpty {
                throw ModelException(ModelException.Kind.INVALID_RESPONSE, "接口没有返回可用模型")
            }
        }
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
        maxTokens: Int = 2048,
    ): String = withContext(io) {
        val credentials = credentialStore.credentialsFor(profileId)
            ?: throw ModelException(
                ModelException.Kind.NOT_CONFIGURED,
                "所选多模态模型配置不存在或缺少密钥，请在设置页重新选择",
            )
        if (credentials.apiProtocol == AiApiProtocol.RESPONSES) {
            val payload = buildJsonObject {
                put("model", credentials.model); put("instructions", systemPrompt)
                put("stream", false); put("store", false); put("max_output_tokens", maxTokens)
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
            val raw = OkHttpCancellation.execute(client, request).use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ModelException(
                    if (response.code in listOf(401, 403)) ModelException.Kind.UNAUTHORIZED else ModelException.Kind.SERVER,
                    "多模态模型调用失败（HTTP ${response.code}）",
                )
                body
            }
            return@withContext parseDeepSeekResponses(raw).text.trim().ifEmpty {
                throw ModelException(ModelException.Kind.INVALID_RESPONSE, "多模态模型未返回有效内容")
            }
        }
        val payload = buildJsonObject {
            put("model", credentials.model)
            put("temperature", 0.2)
            put("max_tokens", maxTokens)
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
        val response = OkHttpCancellation.execute(client, request)
        val body = response.use { it.body?.string().orEmpty() }
        if (!response.isSuccessful) {
            throw ModelException(
                ModelException.Kind.SERVER,
                "多模态模型调用失败（HTTP ${response.code}）：${body.take(160)}",
            )
        }
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw ModelException(ModelException.Kind.SERVER, "多模态模型返回了无法解析的结果")
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
        maxOutputTokens: Int = MAX_OUTPUT_TOKENS,
    ): JsonObject = buildJsonObject {
        put("model", configured.model)
        put("temperature", 0.4)
        put("max_tokens", maxOutputTokens)
        put("stream", stream)
        nativeReasoningRequestFields(
            baseUrl = configured.baseUrl,
            model = configured.model,
            effort = configured.reasoningEffort,
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
            // ── 顺序 ─────────────────────────────────────────────────────────
            //   [稳定前缀]  固定协议 → 称呼/推理设定
            //   [稳定历史]  既有历史（**不含**当前这一轮）
            //   [易变尾部]  当前时间 → 解题模式
            //   [当前问题]  必须**最后一条**，且带它自己的图片
            //
            // 当前问题必须最后：模型最看重「最后说的话」，把上下文放在它后面，回答就会跑偏。
            add(buildJsonObject { put("role", "system"); put("content", prompt.stableSystem) })
            // 稳定历史：排除最后一条 user（它是当前问题，稍后单独放）。
            val currentTurnIndex = messages.indexOfLast { it.role == "user" }
            messages.forEachIndexed { index, message ->
                if (index == currentTurnIndex) return@forEachIndexed
                val images = message.imageBase64s
                add(buildJsonObject {
                    put("role", message.role)
                    if (images.isNotEmpty()) {
                        put("content", buildJsonArray {
                            if (message.content.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", message.content) })
                            images.forEach { base64 ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$base64") })
                                })
                            }
                        })
                    } else {
                        put("content", message.content)
                    }
                })
            }
            // ── 易变尾部：时间与解题模式靠近当前问题，并声明不能覆盖系统规则 ──
            add(buildJsonObject { put("role", "system"); put("content", prompt.volatileSystem) })
            // ── 当前问题（必须是最后一条）────────────────────────────────────
            val current = currentTurnIndex.takeIf { it >= 0 }?.let { messages[it] }
            if (current != null) {
                // 当前轮的图片由调用方直接给出（历史图片走 message.imageBase64s）。
                val images = if (imageBase64s.isNotEmpty()) imageBase64s else current.imageBase64s
                add(buildJsonObject {
                    put("role", "user")
                    if (images.isNotEmpty()) {
                        put("content", buildJsonArray {
                            if (current.content.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", current.content) })
                            images.forEach { base64 ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$base64") })
                                })
                            }
                        })
                    } else {
                        put("content", current.content)
                    }
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
        instruction: String = "请基于前面的题目和分析，立即生成最终答案。不要再展开冗长的内部思考；正文保留用户需要看到的公式与推导。严格遵守系统要求，只输出完整 JSON 对象。",
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
            configured = configured.copy(reasoningEffort = AiReasoningEffort.LOW),
            messages = recoveryMessages,
            imageBase64s = emptyList(),
            prompt = prompt,
            stream = true,
            webSearchEnabled = false,
            maxOutputTokens = RECOVERY_OUTPUT_TOKENS,
        )
        val request = Request.Builder()
            .url(completionsUrl(configured.baseUrl))
            .header("Authorization", "Bearer ${configured.apiKey}")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        val recovered = try {
            executeStreaming(request, onEvent, onUsage)
        } catch (error: ModelException) {
            // 只有**明确点名输出预算过大**的 400 才降预算重发一次；
            // 泛化的 invalid_request_error 重发是白花一次计费请求。
            if (error.kind != ModelException.Kind.SERVER) throw error
            val body = error.message.orEmpty()
            if (!body.contains("HTTP 400") || !isOutputBudgetRejected(body)) throw error
            val compatible = JsonObject(payload + ("max_tokens" to JsonPrimitive(MAX_OUTPUT_TOKENS)))
            val retryRequest = request.newBuilder().post(compatible.toString().toRequestBody(mediaType)).build()
            executeStreaming(retryRequest, onEvent, onUsage)
        }
        return recovered.copy(
            reasoning = (previous.reasoning + recovered.reasoning).takeLast(MAX_REASONING_CAPTURE_CHARS),
            citations = previous.citations + recovered.citations,
            // 恢复是**另一次 HTTP 请求**，它有自己的 usage：按本次请求记录，不相加。
            usage = recovered.usage,
            searched = previous.searched || recovered.searched,
            warnings = previous.warnings + recovered.warnings,
        )
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
                throw ModelException(kind, "接口返回 HTTP ${resp.code}：${text.take(240).ifEmpty { "无返回内容" }}")
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
            source.readSseEvents(onRawLine = { raw.append(it) }) { sse ->
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
                    reasoning.appendTail(it, MAX_REASONING_CAPTURE_CHARS)
                    onEvent(StreamEvent.ReasoningDelta(it))
                }
                extractText(message["content"]).takeIf { it.isNotEmpty() }?.let {
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
    ): ParsedReply {
        val configured = configured.copy(requestWebSearch = webSearchEnabled)
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
            return finalizeResponsesReply(
                e.parsed,
                webSearchRequested = webSearchEnabled,
                extraWarning = "联网搜索走了 Responses 完整响应读取（该端点未启用流式），本次正文在生成结束后才展示",
            )
        }
        if (streamed != null) {
            return finalizeResponsesReply(
                streamed.output,
                webSearchRequested = webSearchEnabled,
            )
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
        var sample: UsageSample? = null
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
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val kind = if (resp.code == 401 || resp.code == 403) {
                    ModelException.Kind.UNAUTHORIZED
                } else {
                    ModelException.Kind.SERVER
                }
                throw ModelException(kind, "DeepSeek 返回 HTTP ${resp.code}：${text.take(240).ifEmpty { "无返回内容" }}")
            }
            val usage = runCatching {
                val root = json.parseToJsonElement(text) as? JsonObject
                (root?.get("usage") as? JsonObject)?.let {
                    UsageParser.parseUsageObject(it, "responses")
                }
            }.getOrNull()
            sample = usage
            val output = parseDeepSeekResponses(text)
            finalizeResponsesReply(
                output,
                webSearchRequested = webSearchEnabled,
                extraWarning = "联网搜索走了 Responses 完整响应读取（该端点未启用流式），本次正文在生成结束后才展示",
            )
        }
        } finally {
            onUsage(sample)
        }
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
    ): ResponsesStreamResult {
        val payload = JsonObject(
            buildDeepSeekResponsesPayload(
                configured, messages, imageBase64s, prompt, forceWebSearch,
            ) + ("stream" to JsonPrimitive(true)),
        )
        val request = Request.Builder()
            .url(deepSeekResponsesUrl(configured.baseUrl))
            .header("Authorization", "Bearer ${configured.apiKey}")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        val usageAccumulator = UsageAccumulator("responses")
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
                    "DeepSeek 返回 HTTP ${resp.code}：${body.take(240).ifEmpty { "无返回内容" }}",
                )
            }
            val body = resp.body ?: throw ModelException(
                ModelException.Kind.INVALID_RESPONSE,
                "DeepSeek 没有返回响应体",
            )
            val contentType = resp.header("Content-Type").orEmpty()
            val text = StringBuilder()
            val thinking = StringBuilder()
            val citations = mutableListOf<Citation>()
            var searched = false
            var sawEvent = false
            var sawCompleted = false
            var streamError: String? = null
            var failedTerminal = false
            // 非 SSE 的 200 响应体：原样留存，供就地解析（不重发请求）。
            val rawStreamBody = StringBuilder()
            val source = body.source()
            source.readSseEvents(onRawLine = { rawStreamBody.append(it) }) { sse ->
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
                            text.append(delta)
                            onEvent(StreamEvent.AnswerDelta(delta))
                        }
                    }
                    "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                        val delta = (chunk["delta"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        if (delta.isNotEmpty()) {
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
                        failedTerminal = true
                        streamError = "服务端返回了不完整的结果（response.incomplete）"
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
                ),
                usageAccumulator.result(),
            )
        }
        } finally {
            onUsage(usageAccumulator.result())
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
    ): ParsedReply {
        val answer = output.text.trim()
        if (answer.isEmpty()) {
            throw ModelException(
                ModelException.Kind.INVALID_RESPONSE,
                "DeepSeek 未返回有效内容",
            )
        }
        val parsed = try {
            ReplyParser.parse(answer, normalizeMarkdown = false)
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
        return withCitations(noted.copy(rawReasoning = output.thinking), output.citations)
    }

    private fun buildDeepSeekResponsesPayload(
        configured: ConfiguredModel,
        messages: List<ChatMessage>,
        imageBase64s: List<String>,
        prompt: PromptSegments,
        forceWebSearch: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", configured.model)
        put("max_output_tokens", MAX_OUTPUT_TOKENS)
        // 与 Chat Completions 保持同一分段顺序：instructions 只放稳定前缀
        // （固定协议 + 稳定用户资料 + 推理设定），时间与只读数据挪到 input 尾部。
        put("instructions", prompt.stableSystem)
        if (configured.requestWebSearch) put("tools", buildJsonArray { add(buildJsonObject { put("type", "web_search") }) })
        // 用户明确开启搜索时强制调用：默认 auto 时模型可能完全不用这个工具。
        if (forceWebSearch) {
            put("tool_choice", buildJsonObject { put("type", "web_search") })
        }
        put("input", buildJsonArray {
            // 与 Chat Completions 完全相同的顺序约束：
            // 稳定历史（**不含**当前轮）→ 易变时间/只读数据 → **当前问题最后**。
            val currentTurnIndex = messages.indexOfLast { it.role == "user" }
            messages.forEachIndexed { index, message ->
                if (index == currentTurnIndex) return@forEachIndexed
                val images = message.imageBase64s
                add(buildJsonObject {
                    put("role", message.role)
                    if (images.isNotEmpty()) {
                        put("content", buildJsonArray {
                            if (message.content.isNotBlank()) add(buildJsonObject { put("type", "input_text"); put("text", message.content) })
                            images.forEach { base64 ->
                                add(buildJsonObject {
                                    put("type", "input_image")
                                    put("image_url", "data:image/jpeg;base64,$base64")
                                })
                            }
                        })
                    } else {
                        put("content", message.content)
                    }
                })
            }
            // 易变尾部：当前时间永远靠近当前问题；只读数据紧随其后。
            add(buildJsonObject { put("role", "system"); put("content", prompt.volatileSystem) })
            // 当前问题必须最后。
            val current = currentTurnIndex.takeIf { it >= 0 }?.let { messages[it] }
            if (current != null) {
                val images = if (imageBase64s.isNotEmpty()) imageBase64s else current.imageBase64s
                add(buildJsonObject {
                    put("role", "user")
                    if (images.isNotEmpty()) {
                        put("content", buildJsonArray {
                            if (current.content.isNotBlank()) add(buildJsonObject { put("type", "input_text"); put("text", current.content) })
                            images.forEach { base64 ->
                                add(buildJsonObject {
                                    put("type", "input_image")
                                    put("image_url", "data:image/jpeg;base64,$base64")
                                })
                            }
                        })
                    } else {
                        put("content", current.content)
                    }
                })
            }
        })
    }

    internal companion object {
        const val TAG = "ModelClient"
        const val MAX_OUTPUT_TOKENS = 8192
        const val RECOVERY_OUTPUT_TOKENS = 16384
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
        internal const val SYSTEM_PROMPT = """你是「墨格」App 里的拍题解题老师。用户会拍下或输入一道题（数学、物理、化学、生物、英语、语文或其他科目），你负责讲清楚、讲对。

回答规则：
1. 用简体中文回答（英语题的原文、例句保留英文）。先弄清题意与已知条件，再作答；不要省略必要推导，也不要堆砌与题目无关的内容。
2. 只依据题目给出的条件作答。条件确实不足或有歧义时，说明缺什么、给出最合理的假设并注明，不要擅自补造关键条件。
3. 用户可能直接发图片，也可能发识题模型转写好的文字。转写里偶有错字或「□」时，结合上下文判断；只有歧义会实质改变题意时才请用户核对。面向用户时不要提「识别引擎」「OCR」等技术细节。
4. 数学、物理、化学等问题必须写出所用公式和关键步骤。LaTeX 行内公式前后各用两个美元符号；块级公式的起止双美元符号必须各占一行。正文使用 Markdown。
5. 最终答案要明确：数值题写清数值与单位，选择题写出选项字母，证明题写出结论。
6. 启用联网搜索时，涉及事实、时效信息或外部资料的回答应给出 Markdown 链接来源。
7. 控制内部思考长度，必须为最终 JSON 答案保留充足输出空间；不要把输出额度全部耗在 reasoning 中。
8. 表格要克制：单元格里只放短标签、数字或单个符号，绝不要在格子里写公式或长句——手机屏幕放不下，会被挤成一团。需要展示公式时写在表格外，格子里用「(1)(2)」这类编号对应即可；表格列数控制在 4 列以内，列更多就拆成两张表或改用列表。
9. 需要画图时（用户要求画函数曲线、频谱、功率谱、波形、眼图、星座图等，或题目明确要求「画出图形」），在顶层加一个 plots 数组，每项一张图，客户端会本地渲染，不需要你生成图片：
    {"title":"标题","x":{"label":"x","min":-5,"max":5},"y":{"label":"y","min":0,"max":30},"series":[{"label":"y=x^2","expr":"x^2"}],"legend":false}
    - 上例先算出 x∈[-5,5] 时 x² 的峰值为 25，再给 y 范围 0~30，完整显示曲线并留少量顶部空间。省略 y.min/max 时客户端会自动求范围；应优先自行计算并显式给出有意义的范围。
    - expr 只支持单变量 x、四则运算、^ 与括号；函数限 sin/cos/tan/exp/ln/log/sqrt/abs/floor/ceil/sign 等，常量用 pi、e；隐式乘法（如 2x）可以
    - 也可以用 "points":[[x,y],...] 直接给数据点（如离散谱线、实测数据）
    - 序列可选 style:"line|dashed|dotted|dashdot|marker|line_marker"，依次为实线、虚线、点线、点划线、散点、折线带点；width:0.75~5，opacity:0.05~1。稠密函数采样通常用线型，marker/line_marker 优先配少量明确的 points。
    - 颜色可用 colorIndex:0~11（蓝、金、绿、粉、紫、橙、青、黄绿、棕、红、浅蓝、灰）或 color:"#RRGGBB"。未指定时各序列自动分色；同一物理量保持同色，不同曲线同时用颜色与线型区分。深色底图应选明亮可辨识的颜色。
    - 散点可选 markerShape:"circle|open_circle|square|diamond|triangle|cross"，markerSize:2~8。实心/空心圆可用于区分端点是否取到，曲线和点的图层高于坐标轴。
    - 标记线 markLines:[{"x":5,"label":"f_c"}]（也支持 y）；矩形带 markAreas:[{"x0":4,"x1":6,"y0":0,"y1":2,"label":"B","colorIndex":1,"opacity":0.15,"pattern":"solid"}]。省略 y0/y1 时覆盖完整绘图区高度。
    - 区域阴影用独立 shades 数组，不改变曲线数据。例如曲线与 x 轴间面积："shades":[{"x0":0,"x1":2,"upper":"x^2","lower":"0","colorIndex":0,"opacity":0.2,"pattern":"hatched","label":"积分区域"}]。两曲线之间替换 upper/lower（均使用 expr 的安全表达式语法），不要用整条竖带代替曲边区域。
    - 不规则区域用闭合多边形："shades":[{"points":[[0,0],[2,0],[1,2]],"colorIndex":2,"opacity":0.2,"pattern":"solid"}]。pattern 可为 solid（半透明填充）、hatched（斜线）、crosshatch（交叉斜线）；opacity:0.03~0.6，通常 0.12~0.3。阴影必须对应真实边界与题意，不得遮盖曲线或凭空增加区域；每图最多 12 块。
    - 旧的 fill:true 仍支持整条曲线到零基线的淡色填充；需要限定区间或两曲线夹区时用 shades。
    - 坐标范围尽量给全（min/max），方便客户端确定刻度；一张图最多 6 条曲线，一次最多 4 张图
    - 图内的 title / label / 标注文字：**可以直接写 LaTeX**（客户端会用公式排版引擎渲染），例如 S_c(f)（写作 ${'$'}S_c(f)${'$'}）、\\frac{N_0}{2}、\\Delta f；不想写 LaTeX 时用纯文本或 Unicode 符号（π、²、≤、f_c）也可以，两种都支持
    - 图内公式请保持简短（标题/轴标签一两项即可），**不要放整段推导**：图里排版空间有限，长公式会被挤小、看不清
    - **标注（markAreas / markLines 的 label）要短**：它们是画在曲线区域里的小字，超过 12 个字就会被截断成省略号；需要长篇解释请写在正文里，别塞进图里
    - **图的比例由客户端决定**（绘图区约 1.8:1），你只需要把 min/max 给合适。**x 与 y 要分开对待**：
      · **x 轴**：收在「主体特征 + 少量余量」，范围太窄曲线会顶到框线、太宽形状会被压平，别把量级差很大的无关数据一起放进去；
      · **y 轴**：根据零基线、峰谷值和需要说明的特征选范围，保留适量顶部空间；不能一律压扁，也不能靠裁掉非零基值来夸大起伏。
    - **题目用符号/参数表达时，坐标刻度也必须用符号，绝不要代入具体数值**：这类题的 f_c、N_0、B、T 都是参数，题目并没有给出它们的值；你随手编一个（比如把纵轴标成 3900 / 4200）等于把图换成了另一道题，用户看到的刻度就是错的。做法是用 ticks + tickLabels 显式给出刻度位置与文案。
    - **刻度文案里的数学式要写成 LaTeX**，用 ${'$'}...${'$'} 包起来，并且 **\frac 必须带花括号**：写成 \frac{B}{2}，**不要写 \frac B2** —— 后者在公式引擎里会解析失败，那一整条公式都会退化成「公式无法渲染」的灰色占位。示例：
      "x":{"label":"f","min":-2.4,"max":2.4,"ticks":[-2,0,2],"tickLabels":{"-2":"${'$'}-f_c-B/2${'$'}","0":"${'$'}O${'$'}","2":"${'$'}-f_c+B/2${'$'}"}}
      "y":{"ticks":[1],"tickLabels":{"1":"${'$'}2\\pi^2N_0(f_c+B/2)^2${'$'}"}}
      数据点仍然用 x 的**数值占位**去算（例如令 B/2 = 2 来采样点集），但刻度文案必须是参数符号。
    - **刻度文案要短**：超过 12 个字符就会和相邻刻度打架、或被截断；纵轴只标最关键的那么一两个值，不要每个刻度都写一串公式。
    - **范围要容得下完整图形**：min/max 必须覆盖题目给出的定义域。像理想低通/带通谱只在 |f| ≤ B/2 上有定义，就把两端设在 ±B/2 并在那里加 markLines 竖线标出边界。客户端会把 min/max 当作**定义域原样使用、不做任何外扩**，所以曲线一定终止在 ±B/2 上；反过来，如果你给的 min/max 比曲线实际存在的区间更宽（例如把定义域写成 ±1.2·(B/2)），曲线上就会多出两段没有意义的空白，看起来就是「曲线没画满、被切掉了」。
    - 需要同时体现「高度」与「宽度」的图（抛物线状谱、主瓣形状等）：**x 范围要覆盖主体特征、y 范围要包住峰值**（两者都不许裁剪数据；y 的留白在下面单独说）
    - **纵轴范围由你根据图像特征自主选择**：生成前先求定义域内的峰值、谷值、零点、非零基值和间断边界，再检查这些特征在所选范围内是否清楚。非负谱密度/功率图通常从 0 开始，上限可先取峰值的 1.15~1.35 倍，使「零基线到峰值」占可用高度约 70%~85%；这是常用起点，不是所有图必须遵守的固定比例。若图中有带外零谱段或其他明确的 y=0 线，必须在 points 中写出 y=0 的端点/水平段，且 y.min 明确写 0；客户端会让这条零基线与横坐标轴重合。峰值 20 时可取 24，不要无理由取 100。含正负值的波形应完整包含峰谷，并在两端留适量余量；对称波形可用对称范围。用户指定坐标范围时尊重用户。
    - **不要把曲线自身的起伏与零基线到峰值的高度混淆**：例如 C*(f²+f_c²) 的谷值是 C*f_c²，不能漏掉常数项、减去基值或把谷值归零来凑形状；相对起伏由 (B/2)²/f_c² 决定，放大坐标范围不能代替正确计算。非负谱的定义域边界可以用 markLines 标在两端；更复杂的间断需要精确指定端点时，用 points 显式画出同一 x 上的峰值点与零点。边界刻度放在 x.tickLabels，不要在图顶重复标同一个符号。
    - 例如仅作形状示意时，若明确假设 f_c²=2*(B/2)²，并以 B/2 和 C*(B/2)² 分别归一化横纵坐标，则可给 expr:"x^2+2"、x.min:-1、x.max:1、y.min:0、y.max:3.6。谷值 2、峰值 3，保留非零底座且清楚显示起伏；x 刻度标 -B/2、O、B/2，y 的谷值刻度标 C*f_c²，并在正文交代示意比例。真实题目给定参数时必须重新计算，不能照抄这些数值。
    - **纵轴要不要标刻度**：定量图保留便于读数的刻度；符号示意图用 ticks + tickLabels 标出非零谷值、峰值等关键量，并可设置 grid:false 减少干扰。确实无需纵轴刻度时显式写 `"ticks":[]`，仅设置 grid:false 只会隐藏网格，不会隐藏刻度。不要用无关数字刻度填满空白。
    - **题目没给大小的参数不要擅自定**：用于采样的数值只是示意比例，必须在正文说明示意假设，并保持公式、数据点和符号刻度一致。仅在题设或适用的物理条件支持 f_c 远大于 B 时才按该关系作图；关系不确定时说明形状随参数比变化，可选代表性的示意比例或分图比较，不能把某个比例说成唯一答案。提交 plots 前自查：峰谷未裁掉、非零基值未丢失、没有大面积无意义留白、标签可读且关键特征清楚。
    - **图表要插在正文里，不能堆在最后**：正文中提到某张图时，就在那句话的下一行单独写一个锚点 `[[FIGURE:1]]`（序号从 1 开始，对应 plots 数组的第几项），客户端会把这个位置替换成图。例如：「由图可见主瓣宽度为 2/T。\n[[FIGURE:1]]\n接下来分析旁瓣……」正文里不要写「见下图」再让图出现在文末。
    - 每张图最多插入一次锚点；某张图如果在正文中没有对应讲解位置，可以不放锚点（客户端会把它排在末尾）
    - 没有画图需求时不要输出 plots；正文里也不要再重复粘贴公式图像的描述
10. 需要画**框图**时（通信原理框图、系统组成图、信号流程图：调制/解调、编码/译码、滤波/抽样、放大器级联、分支与合流等），在顶层加一个 diagrams 数组，每项一张图，**客户端本地排版并绘制**；你只给拓扑和语义角色，**不要给绝对坐标、不要用 ASCII 画框**（禁止在代码块里用 ──→、│、┌──┐ 这类字符拼框图——手机窄屏上会换行散乱、完全读不出来）：
       示例（上下双支路）：`{"title":"相干解调","profile":"textbook_dual_branch","nodes":[{"id":"in","label":"A ${'$'}s(t)${'$'}","shape":"io"},{"id":"split","label":"A","shape":"junction"},{"id":"mi","label":"乘法器","shape":"mixer","glyph":"×"},{"id":"mq","label":"乘法器","shape":"mixer","glyph":"×"},{"id":"fi","label":"LPF","shape":"block"},{"id":"fq","label":"LPF","shape":"block"},{"id":"sum","label":"求和","shape":"sum"},{"id":"out","label":"G 输出","shape":"io"},{"id":"car","label":"载波提取","shape":"block"},{"id":"phase","label":"−90° 移相","shape":"block"}],"edges":[{"from":"in","to":"split","label":"A"},{"from":"split","to":"mi","fromPort":"top"},{"from":"split","to":"mq","fromPort":"bottom"},{"from":"mi","to":"fi","label":"B"},{"from":"fi","to":"sum","toPort":"top","label":"C"},{"from":"mq","to":"fq","label":"D"},{"from":"fq","to":"sum","toPort":"bottom","label":"F","polarity":"-"},{"from":"sum","to":"out","label":"G"},{"from":"car","to":"mi","toPort":"bottom","label":"cos 2πf_ct"},{"from":"car","to":"phase"},{"from":"phase","to":"mq","toPort":"bottom","label":"sin 2πf_ct"}]}`
     - shape：block（矩形功能框，默认）、mixer（圆形乘法器，glyph 写 ×）、sum（求和圆）、io（无框文字）、junction（黑色分支/测试点）、sampler（抽样开关，label 写“抽样”，subLabel 写抽样时刻）、bus（串并转换、并串转换、分路、复用或多路求和的多端口竖框，label 用换行分成竖排短文字；多路求和也可用 label:Σ、role:combiner 表示教材里的求和竖框）
      - 教材式 SSB 双支路使用 `profile:"textbook_dual_branch"`，并给节点加 role：`input`、`split`、`upper_mixer`、`lower_mixer`、`upper_filter`、`lower_filter`、`lower_hilbert`、`carrier`、`phase_shift`、`sum`、`output`；A～G 测试点可写 `role:"test_a"` 等。这一固定结构只适合 SSB，不要拿它画 QPSK/16QAM 判决链。
      - QPSK、16QAM 等相干 I/Q 解调使用 `profile:"iq_demodulator"`。节点仍须完整给出真实拓扑和 edge，不能只写模板名。输入和分路点分别标 `role:"input"`、`role:"split"`；I、Q 两路节点依次用 `i_`、`q_` 前缀标角色，例如 `i_mixer`/`q_mixer`、`i_filter`/`q_filter`、`i_sampler`/`q_sampler`、`i_decision`/`q_decision`。共享的载波、移相、定时恢复可用 `local_oscillator`、`phase_shift`、`carrier_recovery`、`timing_recovery`；汇合后的符号映射/解映射、输出用 `symbol_demapper`、`output`。客户端根据两路信号连线确定处理阶段、对齐同级模块，并把控制支路放在两路之间、虚线反馈放在独立通道。不要给这类图猜 row/column。
      - 最小 I/Q 解调连线应有 `input → split → i_mixer → i_filter → i_sampler → i_decision → symbol_demapper → output` 和从 split 经对应 `q_` 节点到同一解映射器的 Q 路；本振接 I 乘法器，并经移相接 Q 乘法器。实际题目若省略某一级，就省略该节点并把前后级接通，不能留下悬空框或把两路过早合成一条线。
      - 16QAM 的 I/Q 判决各有四个幅度电平；按题目需要标出多电平判决、符号解映射等模块。QPSK 的判决通常是两路二值判决。两种图共用 I/Q 布局，但节点与连线必须反映题目给出的实际接收机结构；定时恢复或载波恢复若画出，就用 edge 连到它控制的模块，反馈 edge 用 `dashed:true`。
      - 常见通信教材框图使用 `profile:"communication"`：相干检测、相关接收、匹配滤波、基带传输、MASK/8PSK 调制、正交基合成与恢复、OFDM、多路编码和时分复用。客户端按真实连线对齐处理阶段，不要猜 row/column。并行支路用 `branch_0_filter`、`branch_1_mixer`、`branch_2_encoder` 等 role（数字表示支路，最多 4 路），同一支路的全部信号节点保持相同前缀；公共前后级不加支路前缀。I/Q 调制也可用 i_/q_ 前缀；I/Q 接收机优先使用上面的专用 iq_demodulator。
      - `communication` 的串并/并串转换用 shape:bus 和 role:serial_to_parallel / parallel_to_serial；FFT/IFFT 用 shape:block 和 role:transform，接收多个比特流并输出 I/Q 电平的映射框用 role:parallel_transform，它们会跨越相连支路并提供分开的端口。两个多端口框之间可写多条 edge，每条加 channel:0 / 1 / 2 / 3 和对应的信号 label，表示不同的并行信号，不能将它们合成一条重复标注的线。取实部/虚部的公共框用 role:real_imag。N 路系统只画第 0、第 1、最后一路的代表支路时，在图上加 repeatLastLane:true，标签注明 N−1，客户端补省略号；不得声称只存在这三路。
      - 噪声、载波、常数、判决门限和定时源分别标 role:noise / carrier / constant / threshold / clock，接到实际作用节点；从上方接入写 toPort:top，从下方接入写 toPort:bottom。辅助源用 io 显示公式，名称不要与 edge.label 重复。并行支路的辅助源也可用 branch_0_carrier 等前缀。求和/相减的每条输入显式写 polarity:"+" 或 "-"，避免把相加画成相减。
      - 其它控制流程或特殊结构使用 `profile:"generic"`，通过节点和连线表达真实拓扑；需要时用 row/column 提示上下支路和阶段顺序。
       - A～G 测试点只在节点标签或对应 edge.label 中出现一次；如果使用 `test_a` 等节点角色，相关 edge 不要再次重复同一个字母。edge.label 优先只写载波、公式和信号名称，避免出现 `s(t) s(t)`、`G → G 输出` 这样的重复标注。
      - 卷积码编码器使用 profile:"convolutional_encoder"，卷积码状态转移图使用 profile:"convolutional_state_graph"，系统循环码编码器使用 profile:"cyclic_encoder"。这些专用模板只需 title/profile/coding，不给 nodes/edges。卷积码 coding 示例：{"version":1,"memory":2,"generators":[[0,1,2],[0,2]],"outputMode":"serial"}；抽头指数 0 表示当前输入，1 表示延迟一拍，状态位最近输入在前，码率 1/输出路数、K=memory+1。编码器与状态图必须用同一份参数，客户端据此计算转移，图中按照教材样图标注输出(输入)，全部为实线箭头；状态编号 s₀、s₁…按最近位在低权序号排列，文字解释应与此一致。系统循环码 coding 示例：{"version":1,"n":7,"k":4,"generatorExponents":[0,1,3]}，表示 g(x)=1+x+x³，校验位按 x^(n-k)m(x) mod g(x) 计算，信息位在前。仅有码率、(n,k,K) 或 (n,k) 不能唯一确定抽头，缺少生成多项式时请询问，不能擅自补成题设；教学示例须注明假设。仅支持二进制、单输入前馈卷积码，最多 8 级记忆/4 路输出，完整状态图最多 8 状态；循环码校验位 1～8，n≤63。不支持的编码类型用文字说明，不套用错误模板。
      - 通用框图才使用 row / column 提示：row 0 = 主链，1 = 主链下方一行；column 越大越靠右。不写则由连线自动推导。
     - 连线端口 fromPort / toPort：left / right / top / bottom / auto。**从下方接进某个框**写 "toPort":"bottom"（本地载波 → 乘法器下方，箭头向上）；不写时按相对位置自动选
     - 连线可带 label（如短标注）、polarity（求和器输入符号，只能写 + 或 -）与 dashed:true（虚线，表示可选/反馈）。求和器上下输入会自动显示 +/−，复杂情况用 polarity 明确指定。
     - 节点可带 subLabel 作为框内第二行小字（型号、参数）
     - 标签里**可以直接写中文，也可以写 LaTeX**（写成 ${'$'}...${'$'}），客户端用公式排版引擎渲染；公式里的 \frac 必须带花括号（\frac{B}{2}，不要写 \frac B2）
     - 规模限制：一张图最多 24 个节点、40 条连线，一次最多 4 张图；标签 60 字以内、标题 40 字以内
     - **节点 id 必须唯一、连线的 from/to 必须指向已定义的 id**，否则整张图会被丢弃；写之前先自查一遍
     - 分支与合流都能表达：同一个 from 写多条 edge 即分支，多条 edge 指向同一个 to 即合流
      - 教材式 SSB 框图可使用 `textbook_dual_branch` 和 junction 测试点排出上下两条平行支路；I/Q 数字解调框图使用 `iq_demodulator`。载波提取、移相、滤波器等共享支路要用真实 edge 连接，不能只写在正文。
     - diagrams 与 plots **共用** [[FIGURE:n]] 编号：**plots 的图在前，diagrams 的图在后**。例如本轮 1 张曲线图 + 1 张框图，正文里分别写 [[FIGURE:1]] 与 [[FIGURE:2]]

输出格式（必须是可以直接 JSON.parse 的单个对象，不要 Markdown 代码块）：
{"conversation_title": "概括题目的短标题", "answer": "简明且完整的最终答案", "reply": "完整讲解与必要推导", "plots": [ ... ], "diagrams": [ ... ]}

conversation_title 用于题册：概括这道题的核心考点，通常 6～24 个中文字，必须具体，如「二重积分的对称性判断」「QPSK 相干解调流程」「虚拟语气的倒装用法」；不要机械截取题目开头，不要写「图片题目」「问题解答」等泛称。只写单行纯文本，不加引号、Markdown、公式代码或标题前缀。请把 conversation_title 放在 JSON 对象的第一个字段。

answer 单独给出本题各小问的最终结论，支持 Markdown 与 LaTeX，不放推理过程；请放在 reply 之前。reply 仍给完整讲解，不能因单独给出 answer 而省略必要推导。普通闲聊、需要补充条件或没有确定答案时省略 answer，直接在 reply 中正常回复。不输出学科分类，分类由用户自行管理。

每一条新回答的图号必须重新从 1 开始，仅指向本次输出的 plots 与 diagrams。历史回答的图号不能用于复用图片。用户再次要求画图、修改图形或重复画同一张图时，必须重新输出本次完整的绘图规格，不能只写锚点、图片链接或「图已生成」。自动续写也从本段的 1 开始编号，客户端会合并图号。

没有图表需求时省略 plots 与 diagrams。reply 只写正常解答，不要重复 JSON 字段内容。"""
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
 * 是否应该为了「输出预算过大」重发一次并降低预算。
 *
 * 只认**明确点名输出上限参数且表达上限被超出**的拒绝。
 * `max_tokens must be an integer greater than 0` 这类**下界/类型**校验错误
 * 只是参数写错了，降低预算重发不会成功，也不会成功 —— 必须排除。
 * 泛化的 `invalid_request_error` 400 同样不算。
 */
internal fun isOutputBudgetRejected(body: String): Boolean {
    val lower = body.lowercase()
    val namesBudgetParameter = listOf(
        "max_tokens", "max_output_tokens", "max_completion_tokens",
    ).any { it in lower }
    if (!namesBudgetParameter) return false
    // 下界/类型校验：预算值本身非法，降预算解决不了，绝不能重发。
    val lowerBoundOrType = listOf(
        "must be an integer", "integer greater than", "must be greater than",
        "must be a positive", "must be positive", "invalid value", "invalid type",
        "not a number", "must be an even", "低于", "必须为整数",
    ).any { it in lower }
    if (lowerBoundOrType) return false
    return listOf(
        "too large", "too big", "too high", "exceed", "maximum", "max is",
        "greater than", "larger than", "上限", "过大", "超出", "超过", "最多",
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
                val summary = obj["summary"] as? JsonArray ?: continue
                for (part in summary) {
                    val partObj = part as? JsonObject ?: continue
                    if ((partObj["type"] as? JsonPrimitive)?.contentOrNull == "summary_text") {
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

internal fun nativeReasoningRequestFields(
    baseUrl: String,
    model: String,
    effort: AiReasoningEffort,
): JsonObject = buildJsonObject {
    val value = effort.name.lowercase()
    val normalizedUrl = baseUrl.lowercase()
    when {
        "openrouter.ai" in normalizedUrl -> put(
            "reasoning",
            buildJsonObject { put("effort", value) },
        )
        "api.openai.com" in normalizedUrl && isOpenAiReasoningModel(model) -> put("reasoning_effort", value)
    }
}

private fun isOpenAiReasoningModel(model: String): Boolean {
    val normalized = model.trim().lowercase()
    return normalized.startsWith("gpt-5") ||
        normalized.startsWith("o1") ||
        normalized.startsWith("o3") ||
        normalized.startsWith("o4")
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

internal fun parseModelIds(raw: String): List<String> {
    val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyList()
    return (root["data"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.get("id") as? JsonPrimitive }
        .mapNotNull(JsonPrimitive::contentOrNull)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .sorted()
}

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
