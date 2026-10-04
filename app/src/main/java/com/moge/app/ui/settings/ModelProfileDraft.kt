package com.moge.app.ui.settings

import com.moge.app.data.credential.AiModelProfile
import com.moge.app.data.credential.AiReasoningEffort
import com.moge.app.data.credential.AiApiProtocol
import com.moge.app.data.credential.AiSearchProtocol
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** 模型编辑页里正在编辑、尚未保存的一套配置。密钥框为空表示沿用已保存的密钥。 */
data class ModelProfileDraft(
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val visionEnabled: Boolean,
    val searchProtocol: AiSearchProtocol,
    val reasoningEffort: AiReasoningEffort,
    val apiProtocol: AiApiProtocol? = null,
    val nativePdfEnabled: Boolean = false,
    val searchEnabled: Boolean = true,
)

/** 常见服务的快捷填入，模型名交给「获取模型列表」。 */
data class ProviderPreset(
    val name: String,
    val baseUrl: String,
    val searchProtocol: AiSearchProtocol = AiSearchProtocol.RESPONSES,
)

val PROVIDER_PRESETS = listOf(
    ProviderPreset("DeepSeek", "https://api.deepseek.com"),
    ProviderPreset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    ProviderPreset("Kimi", "https://api.moonshot.cn/v1"),
    ProviderPreset("OpenAI", "https://api.openai.com/v1"),
    ProviderPreset("Anthropic", "https://api.anthropic.com", AiSearchProtocol.ANTHROPIC),
)

/** 接口地址能否用：必须是 http(s) 的合法 URL。返回给用户看的错误，合法时返回 null。 */
fun baseUrlError(baseUrl: String): String? {
    val trimmed = baseUrl.trim()
    if (trimmed.isEmpty()) return "请填写接口地址"
    val scheme = trimmed.substringBefore("://", "").lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return "接口地址要以 http:// 或 https:// 开头"
    if (trimmed.toHttpUrlOrNull() == null) return "接口地址格式不对"
    return null
}

/** http 地址的提醒：请求和密钥都是明文。 */
fun isCleartextUrl(baseUrl: String): Boolean =
    baseUrl.trim().lowercase(Locale.ROOT).startsWith("http://")

/** 配置名称留空时用模型名代替，省得多填一格。 */
fun effectiveName(draft: ModelProfileDraft): String =
    draft.name.trim().ifEmpty { draft.model.trim() }

/** 保存前的本地校验；[hasStoredKey] 为 true 时密钥框可以留空。 */
fun validateDraft(draft: ModelProfileDraft, hasStoredKey: Boolean): String? = when {
    baseUrlError(draft.baseUrl) != null -> baseUrlError(draft.baseUrl)
    draft.model.isBlank() -> "请填写模型名"
    draft.apiKey.isBlank() && !hasStoredKey -> "请填写 API 密钥"
    else -> null
}

/** 配置卡片第二行：模型 · 看图能力 · 联网协议 · 思考强度。 */
fun profileSummary(profile: AiModelProfile): String = buildString {
    append(profile.model)
    append(if (profile.visionEnabled) " · 可看图" else " · 纯文字")
    val api = profile.apiProtocol ?: AiApiProtocol.fromLegacy(profile.searchProtocol)
    append(" · ").append(when (api) {
        AiApiProtocol.RESPONSES -> "Responses"
        AiApiProtocol.CHAT_COMPLETIONS -> "Chat Completions"
        AiApiProtocol.ANTHROPIC_MESSAGES -> "Anthropic Messages"
    })
    append(if (profile.searchEnabled && profile.searchProtocol != AiSearchProtocol.OFF) " · 允许联网" else " · 不联网")
    append(" · ").append(reasoningLabel(profile.reasoningEffort)).append("思考")
}

fun reasoningLabel(effort: AiReasoningEffort): String = when (effort) {
    AiReasoningEffort.LOW -> "低"
    AiReasoningEffort.MEDIUM -> "中"
    AiReasoningEffort.HIGH -> "高"
}

fun searchProtocolLabel(protocol: AiSearchProtocol): String = when (protocol) {
    AiSearchProtocol.RESPONSES -> "Responses"
    AiSearchProtocol.CHAT_COMPLETIONS -> "Chat Completions"
    AiSearchProtocol.ANTHROPIC -> "Anthropic Messages"
    AiSearchProtocol.OFF -> "关闭"
}

/**
 * 拍题会走哪条路线（与 GenerationPreparer 的判定一致）：
 * 主模型能看图就直送；否则交给识题模型转写；两者都没有就无法拍题。
 */
fun photoRouteHint(
    profiles: List<AiModelProfile>,
    activeId: String?,
    visionProfileId: String?,
): String {
    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
        ?: return "还没有模型，先添加一个。"
    if (active.visionEnabled) return "主模型「${active.name}」可看图，拍题时直接把照片发给它。"
    val vision = profiles.firstOrNull { it.id == visionProfileId && it.visionEnabled }
        ?: return "主模型「${active.name}」不看图，也没有指定识题模型：只能文字提问。"
    return "先由「${vision.name}」把照片转写成文字，再交给「${active.name}」解答。"
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}
