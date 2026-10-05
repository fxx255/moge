package com.moge.app.data.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.moge.app.data.credential.migrateRetiredProtocols
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 一次提问的本机快照：模型/端点身份、推理与联网设置、解题模式、原问题、原历史和照片路线。
 *
 * ## 身份何时固定
 *
 * 新提问时捕获当前配置；用户点「重新发送」时重新捕获当前配置并与新 attempt 原子保存。
 * 一次 attempt 开始后以快照为准，生成中切换模型不会影响在途请求。
 *
 * ## 不含密钥和图片内容
 *
 * 只存非敏感身份（模型名、去掉查询串和凭证的端点身份）与附件的**私有目录路径**。
 * API Key 运行时从 [com.moge.app.data.credential.AiCredentialStore] 取，从不落库。
 */
@Serializable
data class RequestSnapshot(
    val model: String = "",
    /** 主机 + 路径，不含查询串、用户名和端口凭证。 */
    val endpointIdentity: String = "",
    /** [com.moge.app.data.credential.AiSearchProtocol.name]。 */
    val protocol: String = "",
    val reasoningEffort: String = "",
    /** [com.moge.app.domain.SolveMode.name]。 */
    val solveMode: String = "",
    val historyMessages: Int = 0,
    val hasImages: Boolean = false,
    /** 提交时的用户原文（转写路线下是转写结果拼上用户补充说明）。 */
    val sourceUserText: String = "",
    /** 转写/准备结果是否已补写进快照。 */
    val prepared: Boolean = false,
    val forceWebSearch: Boolean = false,
    /** 提交时联网开关的生效值；重试沿用，不读当前开关。 */
    val effectiveWebSearchEnabled: Boolean = false,
    val maxContinuations: Int = 0,
    val primaryProfileId: String = "",
    /** 提交时主模型能否看图；追问时据此决定是否回带历史图片。 */
    val primaryVisionEnabled: Boolean = false,
    /** 识题模型档案 id，只有转写路线非空。 */
    val visionProfileId: String = "",
    val visionModel: String = "",
    val visionEndpointIdentity: String = "",
    /** [PHOTO_ROUTE_NONE] / [PHOTO_ROUTE_DIRECT] / [PHOTO_ROUTE_TRANSCRIBE]。 */
    val photoRoute: String = PHOTO_ROUTE_NONE,
    /** 提交时钉下的有界历史（不含本轮问题与回答位）。 */
    val originalHistory: List<SnapshotHistoryMessage> = emptyList(),
    /** Visible saved prefix for an explicit resume; independent of the bounded model echo. */
    val continuationText: String = "",
    val documentPaths: List<String> = emptyList(),
    val documentReadRequired: Boolean = false,
    val apiProtocol: String = "",
) {
    /** 身份齐备且路线可识别才可重试；纯照片题的 [sourceUserText] 可以为空。 */
    val isComplete: Boolean
        get() = model.isNotBlank() &&
            endpointIdentity.isNotBlank() &&
            photoRoute in setOf(PHOTO_ROUTE_NONE, PHOTO_ROUTE_DIRECT, PHOTO_ROUTE_TRANSCRIBE)

    /** 诊断与 usage 归集键：端点身份 + 模型 + 协议。 */
    val usageGroupKey: String
        get() = listOf(
            endpointIdentity.ifBlank { "未知端点" },
            model.ifBlank { "未知模型" },
            protocol.ifBlank { "未知协议" },
        ).joinToString(" | ")

    fun toPolicy(): RequestPolicy = RequestPolicy(
        primaryProfileId = primaryProfileId,
        visionProfileId = visionProfileId,
        visionModel = visionModel,
        visionEndpointIdentity = visionEndpointIdentity,
        model = model,
        endpointIdentity = endpointIdentity,
        searchProtocol = protocol,
        reasoningEffort = reasoningEffort,
        effectiveWebSearchEnabled = effectiveWebSearchEnabled,
        apiProtocol = apiProtocol,
        visionEnabled = primaryVisionEnabled,
    )

    companion object {
        const val PHOTO_ROUTE_NONE = "none"
        const val PHOTO_ROUTE_DIRECT = "direct"
        const val PHOTO_ROUTE_TRANSCRIBE = "transcribe"
    }
}

/** 历史只保存本机图片路径；发送时才重新压缩，不持久化 base64。 */
@Serializable
data class SnapshotHistoryMessage(
    val id: String = "",
    val role: String = "",
    val text: String = "",
    val imagePaths: List<String> = emptyList(),
    val documentPaths: List<String> = emptyList(),
)

/**
 * 一次生成使用的**不可变运行时策略**（只在内存里，不含密钥）。
 *
 * 整轮 HTTP（主请求、转写、恢复、续写）都用同一份身份与设置，中途不重读当前活动档案。
 */
data class RequestPolicy(
    val primaryProfileId: String = "",
    val visionProfileId: String = "",
    val visionModel: String = "",
    val visionEndpointIdentity: String = "",
    val model: String = "",
    val endpointIdentity: String = "",
    val searchProtocol: String = "",
    val reasoningEffort: String = "",
    val effectiveWebSearchEnabled: Boolean = false,
    val apiProtocol: String = "",
    val visionEnabled: Boolean? = null,
)

/** 快照的序列化；坏快照解码成 null，调用方按「不可重试」处理。 */
object SnapshotCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: RequestSnapshot): String =
        runCatching { json.encodeToString(RequestSnapshot.serializer(), snapshot) }.getOrDefault("")

    fun decode(raw: String): RequestSnapshot? =
        if (raw.isBlank()) null
        else runCatching { json.decodeFromJsonElement(RequestSnapshot.serializer(), migrateRetiredProtocols(Json.parseToJsonElement(raw))) }.getOrNull()
}

/**
 * 由 baseUrl 派生**安全端点身份**：scheme://host[:port]/path，
 * 不含查询串、用户信息、片段与任何凭证。
 *
 * baseUrl 可能带 `?key=...` 这类查询串，直接落库等于把凭证写进数据库。
 * 手写解析而不走 OkHttp 的 HttpUrl，是为了能在纯 JVM 单测里跑。
 */
fun endpointIdentityOf(baseUrl: String): String {
    val trimmed = baseUrl.trim()
    if (trimmed.isEmpty()) return ""
    val withoutQuery = trimmed.substringBefore('?').substringBefore('#')
    val schemeEnd = withoutQuery.indexOf("://")
    val scheme = if (schemeEnd > 0) withoutQuery.substring(0, schemeEnd + 3) else ""
    val afterScheme = withoutQuery.substring(scheme.length)
    val slash = afterScheme.indexOf('/')
    val authority = if (slash >= 0) afterScheme.substring(0, slash) else afterScheme
    val path = if (slash >= 0) afterScheme.substring(slash) else ""
    val hostAndPort = authority.substringAfterLast('@')
    return scheme + hostAndPort + path.trimEnd('/')
}
