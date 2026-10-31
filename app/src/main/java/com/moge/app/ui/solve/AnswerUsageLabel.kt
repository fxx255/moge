package com.moge.app.ui.solve

import com.moge.app.R
import com.moge.app.core.TextResources
import com.moge.app.data.llm.AnswerUsage
import com.moge.app.data.llm.AnswerUsageCodec
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Formatting only: the runtime persists totals and field coverage, including missing HTTP usage. */
internal fun usageLabel(usageJson: String, strings: TextResources? = null, durationMs: Long = 0): String? {
    if (usageJson.isBlank()) return null
    val total = AnswerUsageCodec.decode(usageJson)
    fun text(id: Int, fallback: String, vararg args: Any) = strings?.text(id, *args) ?: fallback
    fun input(value: String) = text(R.string.ui_input_1_s_d5982, "输入 $value", value)
    fun cached(value: String) = text(R.string.ui_cached_1_s_bc1e4, "缓存 $value", value)
    fun output(value: String) = text(R.string.ui_output_1_s_0f7f9, "输出 $value", value)
    if (total != null) {
        if (total.requestCount == 0 && !total.priorUsageUnknown) return null
        val unknown = text(R.string.ui_usage_unknown, "未知")
        fun count(value: Long?, samples: Int): String = when {
            value == null -> unknown
            // Lower bounds round down so 1,260 known tokens never become a false bound of ≥1.3k.
            samples < total.requestCount || total.priorUsageUnknown ->
                "≥${compactCount(if (value in 1_000L..99_999L) value / 100 * 100 else value)}"
            else -> compactCount(value)
        }
        return buildList {
            if (total.inputTokens == null && total.cachedInputTokens == null && total.outputTokens == null) {
                add(text(R.string.ui_usage_unavailable, "用量未返回"))
            } else {
                add(input(count(total.inputTokens, total.inputSamples)))
                total.cachedInputTokens?.takeIf { it > 0 }?.let { add(cached(count(it, total.cachedInputSamples))) }
                add(output(count(total.outputTokens, total.outputSamples)))
                if (total.inputSamples < total.requestCount || total.outputSamples < total.requestCount) {
                    add(text(R.string.ui_usage_partial, "部分用量未返回"))
                }
                averageOutputTokensPerSecond(total, durationMs)?.let { speed ->
                    val value = if (speed > 0 && speed < 0.1) "<0.1" else String.format(Locale.ROOT, "%.1f", speed)
                    add(text(R.string.ui_usage_average_output_speed, "平均 $value tokens/s", value))
                }
            }
            if (total.priorUsageUnknown) add(text(R.string.ui_usage_prior_unknown, "前段用量缺失"))
        }.joinToString(" · ")
    }

    val obj = runCatching { Json.parseToJsonElement(usageJson) as? JsonObject }.getOrNull() ?: return null
    // An unsupported aggregate version must not be presented as a legacy single-request sample.
    if (obj.containsKey("scope")) return null
    fun field(name: String) = (obj[name] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    val parts = buildList {
        field("inputTokens")?.let { add(input(compactCount(it))) }
        field("cachedInputTokens")?.takeIf { it > 0 }?.let { add(cached(compactCount(it))) }
        field("outputTokens")?.let { add(output(compactCount(it))) }
    }
    return parts.takeIf { it.isNotEmpty() }?.let {
        (listOf(text(R.string.ui_usage_legacy_request, "旧版单次")) + it).joinToString(" · ")
    }
}

/** End-to-end throughput: all reported output (including reasoning/tools) over the whole answer time. */
internal fun averageOutputTokensPerSecond(usage: AnswerUsage, durationMs: Long): Double? {
    if (durationMs <= 0 || usage.requestCount <= 0 || usage.priorUsageUnknown ||
        usage.outputSamples != usage.requestCount) return null
    val output = usage.outputTokens?.takeIf { it >= 0 } ?: return null
    // Convert before multiplying so large token totals cannot overflow a Long.
    return output.toDouble() * 1000.0 / durationMs.toDouble()
}

internal fun compactCount(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 100_000 -> String.format(Locale.ROOT, "%.1fk", value / 1_000.0).replace(".0k", "k")
    else -> "${value / 1_000}k"
}
