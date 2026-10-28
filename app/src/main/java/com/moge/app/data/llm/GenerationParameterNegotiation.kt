package com.moge.app.data.llm

import kotlinx.serialization.json.*

/** Only explicit parameter rejections before generation may change a request. Never guess a model cap. */
internal class GenerationParameterNegotiation(initial: JsonObject) {
    var payload: JsonObject = initial
        private set
    private val seen = mutableSetOf(initial)
    private val rejectedBudgetKeys = mutableSetOf<String>()
    private var retries = 0

    fun afterRejection(error: ModelException): JsonObject? {
        if (retries >= MAX_RETRIES) return null
        val candidate = compatibleGenerationParameters(payload, error, rejectedBudgetKeys) ?: return null
        if (!seen.add(candidate)) return null
        retries++
        payload = candidate
        return candidate
    }

    companion object { private const val MAX_RETRIES = 5 }
}

private val BUDGET_KEYS = listOf("max_tokens", "max_completion_tokens", "max_output_tokens")
private val UNSUPPORTED = Regex(
    "unknown|unrecognized|unrecognised|unsupported|not supported|not permitted|not allowed|unexpected|extra inputs|不支持|未知参数|不允许",
    RegexOption.IGNORE_CASE,
)
private val UPPER_LIMIT = Regex(
    "(?:less than or equal to|less_than_equal|must be\\s*<=|<=|maximum(?:\\s+(?:allowed|is|of))?|max(?:imum)?(?: (?:output|completion))?(?: tokens?| value)? is|limit(?:\\s+(?:is|of))?|must not exceed|at most|上限(?:为|是)?|最多|不能超过|不得超过|不大于)[\"':=\\s]*([0-9][0-9,]*)|[\"](?:le|maximum|maximum_value)[\"]\\s*:\\s*([0-9][0-9,]*)",
    RegexOption.IGNORE_CASE,
)

private fun compatibleGenerationParameters(
    payload: JsonObject, error: ModelException, rejectedBudgetKeys: MutableSet<String>,
): JsonObject? {
    val body = error.message.orEmpty()
    if (error.kind != ModelException.Kind.SERVER || error.rejectedHttpStatus !in listOf(400, 422)) return null
    // Relax format constraints only on an explicit rejection, before any answer is read.
    val chatFormat = payload["response_format"] as? JsonObject
    val textOptions = payload["text"] as? JsonObject
    val format = chatFormat ?: textOptions?.get("format") as? JsonObject
    if (format != null && UNSUPPORTED.containsMatchIn(body) &&
        listOf("response_format", "json_schema", "json_object", "text.format").any { body.contains(it, true) }) {
        val schemaRejected = format["type"]?.jsonPrimitive?.contentOrNull == "json_schema" &&
            body.contains("json_schema", true)
        val fallback = if (schemaRejected) buildJsonObject { put("type", "json_object") } else null
        return if (chatFormat != null) {
            JsonObject(payload - "response_format" + if (fallback != null) mapOf("response_format" to fallback) else emptyMap())
        } else {
            val remaining = JsonObject(textOptions.orEmpty() - "format" + if (fallback != null) mapOf("format" to fallback) else emptyMap())
            JsonObject(payload - "text" + if (remaining.isNotEmpty()) mapOf("text" to remaining) else emptyMap())
        }
    }
    val outputKey = BUDGET_KEYS.firstOrNull(payload::containsKey)
    // Some services enforce input+output together. Keep the full input and request all remaining room.
    val remaining = remainingContextAllowance(body)
    val oldOutput = outputKey?.let { (payload[it] as? JsonPrimitive)?.intOrNull }
    if (outputKey != null && oldOutput != null && remaining != null && remaining in 1 until oldOutput) {
        return JsonObject(payload + (outputKey to JsonPrimitive(remaining)))
    }
    val budget = BUDGET_KEYS.firstOrNull { payload.containsKey(it) && body.contains(it, ignoreCase = true) }
    if (budget != null) {
        val old = (payload[budget] as? JsonPrimitive)?.intOrNull ?: return null
        // Read only upper bounds attached to this parameter; unrelated limits must not lower output.
        val paramAt = body.indexOf(budget, ignoreCase = true)
        val detail = body.substring(paramAt, (paramAt + 400).coerceAtMost(body.length))
        val range = OutputTokenRange(budget)
        if (range.isDescribedBy(detail)) {
            // A range is a value constraint, not a reason to swap/remove the parameter name.
            val maximum = range.reducedMaximum(detail, old) ?: return null
            return JsonObject(payload + (budget to JsonPrimitive(maximum)))
        }
        val limit = UPPER_LIMIT.findAll(detail).mapNotNull { match ->
            match.groupValues.drop(1).firstOrNull(String::isNotBlank)?.replace(",", "")?.toIntOrNull()
        }.filter { it in 1 until old }.minOrNull()
        if (limit != null) return JsonObject(payload + (budget to JsonPrimitive(limit)))
        if (UNSUPPORTED.containsMatchIn(detail) || UNSUPPORTED.containsMatchIn(body.substring((paramAt - 60).coerceAtLeast(0), paramAt))) {
            rejectedBudgetKeys += budget
            if (budget != "max_output_tokens") {
                val alternate = if (budget == "max_tokens") "max_completion_tokens" else "max_tokens"
                if (alternate !in rejectedBudgetKeys) return JsonObject(payload - budget + (alternate to JsonPrimitive(old)))
            }
            return JsonObject(payload - budget)
        }
        // The endpoint rejects an oversized value but supplies no usable bound. Let it choose.
        if (isOutputBudgetRejected(detail)) return JsonObject(payload - budget)
    }
    // Reasoning models may accept only their default temperature; optional tuning cannot block output.
    for (key in listOf("temperature", "stream_options", "include")) {
        val at = body.indexOf(key, ignoreCase = true)
        if (payload.containsKey(key) && at >= 0 && UNSUPPORTED.containsMatchIn(
                body.substring((at - 60).coerceAtLeast(0), (at + 180).coerceAtMost(body.length)))) {
            return JsonObject(payload - key)
        }
    }
    val nested = payload["reasoning"] as? JsonObject
    val effortKey = when {
        payload.containsKey("reasoning_effort") && body.contains("reasoning_effort", true) -> "reasoning_effort"
        nested?.containsKey("effort") == true && (body.contains("reasoning", true) || body.contains("effort", true)) -> "reasoning"
        else -> return null
    }
    val current = (if (effortKey == "reasoning") nested?.get("effort") else payload[effortKey])?.jsonPrimitive?.contentOrNull ?: return null
    val enumDetail = Regex("(?:supported[ _]values|allowed[ _]values|valid[ _]values|one of|should be|must be|permitted|enum|支持的值|可选值)[^\\n]{0,240}", RegexOption.IGNORE_CASE)
        .find(body)?.value.orEmpty()
    val allowed = NATIVE_REASONING_EFFORTS.filter { Regex("[\"']$it[\"']").containsMatchIn(enumDetail) }
    val replacement = allowed.filter { it != current }.minWithOrNull(
        compareBy<String> { kotlin.math.abs(NATIVE_REASONING_EFFORTS.indexOf(it) - NATIVE_REASONING_EFFORTS.indexOf(current)) }
            .thenByDescending { NATIVE_REASONING_EFFORTS.indexOf(it) },
    )
    if (replacement != null) return if (effortKey == "reasoning") {
        JsonObject(payload + ("reasoning" to JsonObject(nested.orEmpty() + ("effort" to JsonPrimitive(replacement)))))
    } else JsonObject(payload + (effortKey to JsonPrimitive(replacement)))
    if (!UNSUPPORTED.containsMatchIn(body)) return null
    return if (effortKey == "reasoning") {
        val remaining = JsonObject(nested.orEmpty() - "effort")
        if (remaining.isEmpty()) JsonObject(payload - effortKey) else JsonObject(payload + (effortKey to remaining))
    } else JsonObject(payload - effortKey)
}

private fun remainingContextAllowance(body: String): Int? {
    fun number(regex: String) = Regex(regex, RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
    val context = number("(?:maximum context length|context window(?: size)?|context limit)\\s*(?:is|of|:|=)?\\s*([0-9][0-9,]*)") ?: return null
    val input = number("([0-9][0-9,]*)\\s+(?:tokens?\\s+)?in (?:the )?(?:messages|input|prompt)")
        ?: number("(?:input|prompt|messages)(?: tokens?| length)?\\s*[:=]\\s*([0-9][0-9,]*)") ?: return null
    return (context - input).takeIf { it > 0 }
}

/**
 * 是否应该为了「输出预算过大」重发一次并降低预算。
 *
 * 只认**明确点名输出上限参数且表达上限被超出**的拒绝。
 * `max_tokens must be an integer greater than 0` 这类**下界/类型**校验错误
 * 降低预算不能解决下界或类型错误，必须排除。
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
