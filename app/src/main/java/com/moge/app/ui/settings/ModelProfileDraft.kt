package com.moge.app.ui.settings

import com.moge.app.R

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
    ProviderPreset("Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    ProviderPreset("Kimi", "https://api.moonshot.cn/v1"),
    ProviderPreset("OpenAI", "https://api.openai.com/v1"),
)

/** 接口地址能否用：必须是 http(s) 的合法 URL。返回给用户看的错误，合法时返回 null。 */
fun baseUrlError(baseUrl: String, strings: com.moge.app.core.TextResources? = null): String? {
    val trimmed = baseUrl.trim()
    if (trimmed.isEmpty()) return (strings?.text(R.string.ui_enter_an_api_url_459e5) ?: "请填写接口地址")
    val scheme = trimmed.substringBefore("://", "").lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return (strings?.text(R.string.ui_the_url_must_start_with_http_or_https_d7829) ?: "接口地址要以 http:// 或 https:// 开头")
    if (trimmed.toHttpUrlOrNull() == null) return (strings?.text(R.string.ui_invalid_api_url_2e196) ?: "接口地址格式不对")
    return null
}

/** http 地址的提醒：请求和密钥都是明文。 */
fun isCleartextUrl(baseUrl: String): Boolean =
    baseUrl.trim().lowercase(Locale.ROOT).startsWith("http://")

/** 配置名称留空时用模型名代替，省得多填一格。 */
fun effectiveName(draft: ModelProfileDraft): String =
    draft.name.trim().ifEmpty { draft.model.trim() }

/** 保存前的本地校验；[hasStoredKey] 为 true 时密钥框可以留空。 */
fun validateDraft(draft: ModelProfileDraft, hasStoredKey: Boolean, strings: com.moge.app.core.TextResources? = null): String? = when {
    baseUrlError(draft.baseUrl, strings) != null -> baseUrlError(draft.baseUrl, strings)
    draft.model.isBlank() -> (strings?.text(R.string.ui_enter_a_model_name_99b1f) ?: "请填写模型名")
    draft.apiKey.isBlank() && !hasStoredKey -> (strings?.text(R.string.ui_enter_an_api_key_c699f) ?: "请填写 API 密钥")
    else -> null
}

/** 配置卡片第二行：模型 · 看图能力 · 联网协议 · 思考强度。 */
fun profileSummary(profile: AiModelProfile, strings: com.moge.app.core.TextResources? = null): String = buildString {
    append(profile.model)
    append(if (profile.visionEnabled) (strings?.text(R.string.ui_vision_52cb6) ?: " · 可看图") else (strings?.text(R.string.ui_text_only_766a1) ?: " · 纯文字"))
    val api = profile.apiProtocol ?: AiApiProtocol.fromLegacy(profile.searchProtocol)
    append(" · ").append(when (api) {
        AiApiProtocol.RESPONSES -> "Responses"
        AiApiProtocol.CHAT_COMPLETIONS -> "Chat Completions"
    })
    append(if (profile.searchEnabled && profile.searchProtocol != AiSearchProtocol.OFF) (strings?.text(R.string.ui_search_allowed_3b41e) ?: " · 允许联网") else (strings?.text(R.string.ui_no_search_10ebd) ?: " · 不联网"))
    append(" · ").append(reasoningLabel(profile.reasoningEffort, strings)).append((strings?.text(R.string.ui_reasoning_a6c14) ?: "思考"))
}

fun reasoningLabel(effort: AiReasoningEffort, strings: com.moge.app.core.TextResources? = null): String = when (effort) {
    AiReasoningEffort.LOW -> (strings?.text(R.string.ui_low_b9ee2) ?: "低")
    AiReasoningEffort.MEDIUM -> (strings?.text(R.string.ui_medium_08690) ?: "中")
    AiReasoningEffort.HIGH -> (strings?.text(R.string.ui_high_b096b) ?: "高")
}

fun searchProtocolLabel(protocol: AiSearchProtocol, strings: com.moge.app.core.TextResources? = null): String = when (protocol) {
    AiSearchProtocol.RESPONSES -> "Responses"
    AiSearchProtocol.CHAT_COMPLETIONS -> "Chat Completions"
    AiSearchProtocol.OFF -> (strings?.text(R.string.ui_close_6c14b) ?: "关闭")
}

/**
 * 拍题会走哪条路线（与 GenerationPreparer 的判定一致）：
 * 主模型能看图就直送；否则交给识题模型转写；两者都没有就无法拍题。
 */
fun photoRouteHint(
    profiles: List<AiModelProfile>,
    activeId: String?,
    visionProfileId: String?,
    strings: com.moge.app.core.TextResources? = null,
): String {
    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
        ?: return (strings?.text(R.string.ui_no_models_yet_add_one_to_get_started_c1f2d) ?: "还没有模型，先添加一个。")
    if (active.visionEnabled) return (strings?.text(R.string.ui_1_s_supports_images_question_photos_are_sent_directly_to_it_0ad74, active.name) ?: "主模型「${active.name}」可看图，拍题时直接把照片发给它。")
    val vision = profiles.firstOrNull { it.id == visionProfileId && it.visionEnabled }
        ?: return (strings?.text(R.string.ui_1_s_does_not_support_images_and_no_recognition_model_is_set_bcd13, active.name) ?: "主模型「${active.name}」不看图，也没有指定识题模型：只能文字提问。")
    return (strings?.text(R.string.ui_1_s_transcribes_photos_then_2_s_answers_the_question_a7809, vision.name, active.name) ?: "先由「${vision.name}」把照片转写成文字，再交给「${active.name}」解答。")
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}
