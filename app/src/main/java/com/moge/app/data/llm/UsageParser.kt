package com.moge.app.data.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 一次 HTTP 请求的供应商 usage 快照。
 *
 * **关键是「未知」的表达**：字段缺失、非法或口径冲突时一律为 `null`，
 * 绝不能当成 0 —— 把「供应商没给缓存字段」算成 0 命中，会把汇总比例
 * 一路拉低成一个看起来很像真实、实际毫无意义的数字。
 */
data class UsageSample(
    /** 本次请求的输入 token；缺失为 null。 */
    val inputTokens: Long?,
    /** 本次请求命中的缓存输入 token；缺失为 null。 */
    val cachedInputTokens: Long?,
    /** 本次请求的输出 token；缺失为 null。 */
    val outputTokens: Long?,
    /** 累计口径的原始块（用于去重）：同一请求里如果出现多次累计 usage，只取最后一次。 */
    val isCumulative: Boolean = true,
    /** 数据来源，便于夹具比对与诊断。 */
    val source: String = "",
    val cacheFieldsPresent: Boolean = cachedInputTokens != null,
) {
    /** 这份样本能否进入「缓存命中率」的分子/分母。 */
    val cacheComparable: Boolean
        get() = inputTokens != null && cachedInputTokens != null &&
            inputTokens > 0 && cachedInputTokens >= 0 && cachedInputTokens <= inputTokens

    /** 供应商是否完全没提供缓存字段。 */
    val cacheFieldsAbsent: Boolean
        get() = !cacheFieldsPresent
}

/**
 * 供应商 usage 解析。
 *
 * 覆盖两条路径的不同字段名（实施文档第五节）：
 * - Chat Completions：`prompt_tokens` / `completion_tokens`，
 *   缓存命中在 `prompt_cache_hit_tokens`（DeepSeek）
 *   或 `prompt_tokens_details.cached_tokens`（OpenAI 兼容）；
 * - Responses：`input_tokens` / `output_tokens`，
 *   缓存命中在 `input_tokens_details.cached_tokens`。
 *
 * 流式场景下，usage 可能出现在**最后一个只带 usage 的块**里
 * （choices 为空），本解析器只看 usage 子树，因此天然兼容。
 */
object UsageParser {

    /**
     * 从任意响应块的 JSON 对象里解析 usage。
     *
     * 返回 null 表示这个块根本没有 usage 字段（例如普通增量块）。
     */
    fun parse(chunk: JsonObject, source: String = ""): UsageSample? {
        val usage = (chunk["usage"] as? JsonObject) ?: return null
        return parseUsageObject(usage, source)
    }

    /** 直接解析一个 usage 对象（Responses 的 `response.usage` 也走这里）。 */
    fun parseUsageObject(usage: JsonObject, source: String = ""): UsageSample {
        val input = resolveField(
            usage,
            "输入 token",
            "prompt_tokens",
            "input_tokens",
        )
        val output = resolveField(
            usage,
            "输出 token",
            "completion_tokens",
            "output_tokens",
        )
        // 缓存字段：显式 cache_hit → prompt_tokens_details.cached → input_tokens_details.cached。
        // 三者都不存在时是 null（未知），不是 0。
        val cached = resolveField(
            usage,
            "缓存命中 token",
            "prompt_cache_hit_tokens",
            "cache_hit_tokens",
            detailsKey = listOf("prompt_tokens_details" to "cached_tokens"),
            detailsKey2 = listOf("input_tokens_details" to "cached_tokens"),
        )
        return UsageSample(
            inputTokens = input,
            cachedInputTokens = cached,
            outputTokens = output,
            source = source,
            cacheFieldsPresent = usage.containsKey("prompt_cache_hit_tokens") ||
                usage.containsKey("cache_hit_tokens") ||
                (usage["prompt_tokens_details"] as? JsonObject)?.containsKey("cached_tokens") == true ||
                (usage["input_tokens_details"] as? JsonObject)?.containsKey("cached_tokens") == true,
        )
    }

    /**
     * 从若干**同义字段**里解析一个值，并处理「口径冲突」。
     *
     * 规则（实施文档第五节）：
     * - 只有一个字段提供了合法值 ⇒ 用它；
     * - 多个字段都提供了合法值且**彼此相等** ⇒ 用它（同一事实的两种写法，无歧义）；
     * - 多个字段都提供了合法值但**互不相等** ⇒ **未知（null）**。
     *
     * 早先的实现（`firstValidLong`）在冲突时按固定优先级取第一个 —— 那等于
     * 在两条互相矛盾的上报里**擅自挑一条当事实**，算出来的命中率无法解释。
     */
    private fun resolveField(
        usage: JsonObject,
        label: String,
        vararg keys: String,
        detailsKey: List<Pair<String, String>> = emptyList(),
        detailsKey2: List<Pair<String, String>> = emptyList(),
    ): Long? {
        val candidates = mutableListOf<Long>()
        var invalid = false
        keys.forEach { key ->
            usage[key]?.let { element ->
                validLong(element)?.let { candidates += it } ?: run { invalid = true }
            }
        }
        (detailsKey + detailsKey2).forEach { (parent, child) ->
            val details = usage[parent] as? JsonObject
            details?.get(child)?.let { element ->
                validLong(element)?.let { candidates += it } ?: run { invalid = true }
            }
        }
        if (invalid) return null
        if (candidates.isEmpty()) return null
        val distinct = candidates.distinct()
        if (distinct.size > 1) {
            // 冲突：记录一次便于诊断，但对外表现为「未知」。
            android.util.Log.w(
                "AssistantUsage",
                "usage 字段口径冲突（$label）：$candidates，按未知处理",
            )
            return null
        }
        return distinct.single()
    }

    private fun detailsElement(
        details: kotlinx.serialization.json.JsonElement?,
        key: String,
    ): kotlinx.serialization.json.JsonElement? {
        val obj = details as? JsonObject ?: return null
        return obj[key]
    }

    /**
     * 只接受**非负整数**。
     *
     * 负数、小数、非数字字符串一律当作「不提供」（null）：把它们当成 0
     * 会污染统计口径，当成原值又会算出负的命中率。
     */
    private fun validLong(element: kotlinx.serialization.json.JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is kotlinx.serialization.json.JsonNull) return null
        primitive.longOrNull?.let { return if (it >= 0) it else null }
        // 有些网关把数字写成字符串。
        val text = primitive.contentOrNull?.trim() ?: return null
        return text.toLongOrNull()?.takeIf { it >= 0 }
    }
}

/**
 * 单次请求内的 usage 归并。
 *
 * 同一请求里可能多次收到 usage（增量块 + 末块，或累计口径重复上报）。
 * 规则：**同一请求的累计 usage 只能统计一次** —— 取最后一次有效上报，
 * 绝不把多个块相加（相加会把同一个请求的 token 数翻好几倍）。
 */
class UsageAccumulator(private val source: String) {
    private var latest: UsageSample? = null

    fun accept(sample: UsageSample?) {
        if (sample == null) return
        latest = sample
    }

    /** 归一化后的本次请求样本；没有任何 usage 时为 null。 */
    fun result(): UsageSample? = latest?.copy(source = source)
}

/**
 * 跨请求的缓存统计汇总。
 *
 * 口径（实施文档第五节）：
 * - 命中率 = **有效 cached 输入 token 总数 ÷ 同批有效 input token 总数**；
 * - 只有同时拿到 input 与 cached 的样本才进入这个分母；
 * - 供应商完全没提供缓存字段的调用**单列**为「供应商未提供」，
 *   不放进零命中分母；
 * - 覆盖率 = 有效样本数 ÷ 总请求数，如实反映可统计的范围。
 */
data class CacheUsageStats(
    /** 有效样本：同时有 input 与 cached。 */
    val validSamples: Int = 0,
    val totalInputTokens: Long = 0,
    val totalCachedInputTokens: Long = 0,
    /** 拿到了 usage 但完全没有缓存字段的请求数。 */
    val providerAbsentSamples: Int = 0,
    /** 连 input token 都没拿到的请求数。 */
    val unknownSamples: Int = 0,
    /** 已提供字段但非法、冲突或不能比较。 */
    val invalidSamples: Int = 0,
) {
    val totalRequests: Int get() = validSamples + providerAbsentSamples + unknownSamples + invalidSamples

    /**
     * 加权缓存命中比例。
     *
     * 没有有效样本时返回 **null**（不可用），而不是 0% ——
     * 「没有数据」和「命中率为零」是完全不同的结论。
     */
    val cachedRatio: Double?
        get() = if (validSamples > 0 && totalInputTokens > 0) {
            totalCachedInputTokens.toDouble() / totalInputTokens.toDouble()
        } else {
            null
        }

    /** 有效样本覆盖率，同样在无请求时为 null。 */
    val coverage: Double?
        get() = if (totalRequests > 0) validSamples.toDouble() / totalRequests.toDouble() else null

    /** 简短可读汇总；不含密钥、题目或原始内容。 */
    fun summary(): String {
        if (totalRequests == 0) return "暂无请求"
        val ratio = cachedRatio
        val ratioText = ratio?.let { "%.1f%%".format(it * 100) } ?: "不可用"
        val coverageText = coverage?.let { "%.0f%%".format(it * 100) } ?: "不可用"
        return buildString {
            append("有效样本 $validSamples/$totalRequests（覆盖 $coverageText）")
            append("，加权缓存命中 $ratioText")
            append("（cached $totalCachedInputTokens / input $totalInputTokens）")
            if (providerAbsentSamples > 0) append("，供应商未提供缓存字段 $providerAbsentSamples 次")
            if (unknownSamples > 0) append("，无 input 数据 $unknownSamples 次")
            if (invalidSamples > 0) append("，字段无效或口径冲突 $invalidSamples 次")
        }
    }

    fun plus(sample: UsageSample?): CacheUsageStats {
        if (sample == null) return copy(unknownSamples = unknownSamples + 1)
        val input = sample.inputTokens
        val cached = sample.cachedInputTokens
        return when {
            input == null -> copy(unknownSamples = unknownSamples + 1)
            sample.cacheFieldsAbsent -> copy(providerAbsentSamples = providerAbsentSamples + 1)
            cached == null || input <= 0 || cached < 0 || cached > input -> copy(invalidSamples = invalidSamples + 1)
            else -> copy(
                validSamples = validSamples + 1,
                totalInputTokens = totalInputTokens + input,
                totalCachedInputTokens = totalCachedInputTokens + cached,
            )
        }
    }
}

/**
 * 按「端点身份/模型/协议」维度归集统计。
 *
 * **键数量有上限**：usage 归集键由 `endpointIdentity | model | protocol` 组成，
 * 用户改设置、换供应商都会产生新键。不加限制的话，长期使用会让这个
 * 进程内存里的 map 无限增长（每日诊断入口也会越来越慢）。
 *
 * 上限用「最近使用」淘汰：`LinkedHashMap` 的插入序 + 命中时重新插入，
 * 于是丢掉的永远是最久没被用到的键 —— 汇总值本来就是累计量，
 * 淘汰几个冷键不影响近期结论，但内存有确定上界。
 */
class CacheUsageRegistry(private val maxKeys: Int = MAX_TRACKED_KEYS) {
    private val byModel = linkedMapOf<String, CacheUsageStats>()

    fun record(modelKey: String, sample: UsageSample?) {
        val key = modelKey.ifBlank { "未标注" }
        // 命中时先移除再插入：把它顶到「最近使用」的一端。
        val existing = byModel.remove(key)
        byModel[key] = (existing ?: CacheUsageStats()).plus(sample)
        // 超限时淘汰最久未使用的键（迭代顺序即插入顺序）。
        while (byModel.size > maxKeys) {
            val oldest = byModel.keys.firstOrNull() ?: break
            byModel.remove(oldest)
        }
    }

    fun statsFor(modelKey: String): CacheUsageStats = byModel[modelKey] ?: CacheUsageStats()

    fun snapshot(): Map<String, CacheUsageStats> = byModel.toMap()

    /** 当前已跟踪的键数量，供测试断言上界。 */
    fun trackedKeys(): Int = byModel.size

    /** 全部模型合并后的汇总。 */
    fun total(): CacheUsageStats = byModel.values.fold(CacheUsageStats()) { acc, item ->
        CacheUsageStats(
            validSamples = acc.validSamples + item.validSamples,
            totalInputTokens = acc.totalInputTokens + item.totalInputTokens,
            totalCachedInputTokens = acc.totalCachedInputTokens + item.totalCachedInputTokens,
            providerAbsentSamples = acc.providerAbsentSamples + item.providerAbsentSamples,
            unknownSamples = acc.unknownSamples + item.unknownSamples,
            invalidSamples = acc.invalidSamples + item.invalidSamples,
        )
    }

    fun clear() = byModel.clear()

    companion object {
        /**
         * 最多跟踪多少个「端点/模型/协议」组合。
         *
         * 取值远大于正常用户可能配置的数量（改几次设置也只有个位数），
         * 所以实际使用中几乎不会触发淘汰；它只是内存上界的保险丝。
         */
        const val MAX_TRACKED_KEYS = 64
    }
}
