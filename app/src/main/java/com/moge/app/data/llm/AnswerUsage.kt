package com.moge.app.data.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/** One answer's HTTP usage totals. Cache and reasoning are subsets, never added to input/output. */
@Serializable
data class AnswerUsage(
    val scope: String = "answer",
    val version: Int = 1,
    val requestCount: Int = 0,
    val requestsWithUsage: Int = 0,
    val inputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val inputSamples: Int = 0,
    val cachedInputSamples: Int = 0,
    val outputSamples: Int = 0,
    val reasoningSamples: Int = 0,
    /** A saved prefix may predate usage checkpoints; never present its new subtotal as a full total. */
    val priorUsageUnknown: Boolean = false,
) {
    /** Accept exactly one final sample per HTTP call, including null when the provider omitted usage. */
    fun plus(sample: UsageSample?): AnswerUsage = copy(
        requestCount = requestCount + 1,
        requestsWithUsage = requestsWithUsage + if (sample != null) 1 else 0,
        inputTokens = sumKnown(inputTokens, sample?.inputTokens),
        cachedInputTokens = sumKnown(cachedInputTokens, sample?.cachedInputTokens),
        outputTokens = sumKnown(outputTokens, sample?.outputTokens),
        reasoningTokens = sumKnown(reasoningTokens, sample?.reasoningTokens),
        inputSamples = inputSamples + if (sample?.inputTokens != null) 1 else 0,
        cachedInputSamples = cachedInputSamples + if (sample?.cachedInputTokens != null) 1 else 0,
        outputSamples = outputSamples + if (sample?.outputTokens != null) 1 else 0,
        reasoningSamples = reasoningSamples + if (sample?.reasoningTokens != null) 1 else 0,
    )
}

private fun sumKnown(total: Long?, value: Long?): Long? =
    if (value == null) total else (total ?: 0L) + value

object AnswerUsageCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun encode(usage: AnswerUsage): String = json.encodeToString(AnswerUsage.serializer(), usage)

    /** Legacy JSON describes only the last request and must not be decoded as an answer total. */
    fun decode(raw: String): AnswerUsage? = runCatching {
        val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        if (obj["scope"]?.jsonPrimitive?.content != "answer" || obj["version"]?.jsonPrimitive?.intOrNull != 1) return null
        json.decodeFromString(AnswerUsage.serializer(), raw)
    }.getOrNull()
}
