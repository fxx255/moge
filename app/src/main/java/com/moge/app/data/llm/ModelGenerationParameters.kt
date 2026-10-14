package com.moge.app.data.llm

import com.moge.app.data.credential.AiApiProtocol
import com.moge.app.data.credential.AiReasoningEffort
import kotlinx.serialization.json.*
import java.util.Locale

/**
 * Protocol parameters, independent of provider hostnames and model names.
 * Missing capability declarations use an integer probe ceiling, then explicit protocol rejections.
 * The ceiling is not an advertised model capability or a promise of an unlimited context window.
 */
object ModelGenerationParameters {
    /** Prefer declarations; otherwise negotiate instead of accepting a potentially small default. */
    @Suppress("UNUSED_PARAMETER")
    fun outputLimit(baseUrl: String, model: String, capability: ModelCapability? = null): Int =
        capability?.maxOutputTokens ?: Int.MAX_VALUE

    fun fields(baseUrl: String, model: String, effort: AiReasoningEffort,
        protocol: AiApiProtocol, finalAnswer: Boolean = false, capability: ModelCapability? = null): JsonObject = buildJsonObject {
        val responses = protocol == AiApiProtocol.RESPONSES
        val budgetKey = if (responses) "max_output_tokens" else capability?.chatOutputParameter ?: "max_tokens"
        put(budgetKey, outputLimit(baseUrl, model, capability))
        val value = if (finalAnswer) "low" else effort.name.lowercase(Locale.ROOT)
        if (capability?.supportsReasoning != false) {
            val native = declaredEffort(capability, effort, finalAnswer) ?: value
            if (responses) put("reasoning", buildJsonObject { put("effort", native) })
            else put("reasoning_effort", native)
        }
    }

    /** Describes the contract/request, never treats HTTP 200 or reasoning length as proof of execution. */
    fun reasoningStatus(baseUrl: String, model: String, effort: AiReasoningEffort, protocol: AiApiProtocol,
        capability: ModelCapability? = null): String {
        if (capability?.supportsReasoning == false) return "接口声明此模型不支持思考强度"
        val fields = fields(baseUrl, model, effort, protocol, capability = capability)
        val value = fields["reasoning_effort"]?.jsonPrimitive?.contentOrNull
            ?: (fields["reasoning"] as? JsonObject)?.get("effort")?.jsonPrimitive?.contentOrNull
            ?: return "接口思考参数未确认；尚无原生档位依据"
        val fixed = capability?.let { it.fixedEffort ?: it.supportedEfforts.singleOrNull() }
        if (fixed != null) return "接口声明固定为 $fixed；三档选择不会改变原生档位"
        if (capability != null && declaredEffort(capability, effort, false) != null) {
            val mapping = AiReasoningEffort.entries.joinToString("，") {
                "${when (it) { AiReasoningEffort.LOW -> "低"; AiReasoningEffort.MEDIUM -> "中"; AiReasoningEffort.HIGH -> "高" }}→${declaredEffort(capability, it, false)}"
            }
            return "$mapping · 接口声明支持，实际执行需核验"
        }
        return "${if (protocol == AiApiProtocol.RESPONSES) "reasoning.effort" else "reasoning_effort"}=$value · 使用通用协议参数"
    }

    private fun declaredEffort(capability: ModelCapability?, effort: AiReasoningEffort, finalAnswer: Boolean): String? {
        if (capability == null || capability.supportsReasoning == false) return null
        if (finalAnswer && (capability.canDisableThinking == true || "none" in capability.supportedEfforts)) return "none"
        capability.fixedEffort?.let { return it }
        val levels = capability.supportedEfforts.filter { it != "none" }
            .sortedBy(NATIVE_REASONING_EFFORTS::indexOf)
        if (levels.isEmpty()) return null
        if (finalAnswer) return levels.first()
        return when (effort) {
            AiReasoningEffort.LOW -> levels.first()
            AiReasoningEffort.MEDIUM -> "medium".takeIf(levels::contains) ?: "high".takeIf(levels::contains) ?: levels[levels.size / 2]
            AiReasoningEffort.HIGH -> levels.last()
        }
    }
}

internal fun finalAnswerPrompt(prompt: PromptSegments, originalEffort: AiReasoningEffort): PromptSegments =
    prompt.copy(stableSystem = prompt.stableSystem.replace(reasoningInstruction(originalEffort),
        "已有分析仅供核对。现在直接完成最终正文，保留必要公式和步骤，不再展开冗长内部思考。"))
