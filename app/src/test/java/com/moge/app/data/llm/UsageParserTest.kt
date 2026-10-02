package com.moge.app.data.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * usage / 缓存统计验收（实施文档 §5、§7.2）。
 *
 * 最核心的一条：**缺失、非法或口径冲突的字段必须算「未知」，绝不能按 0 计**。
 * 把「供应商没给缓存字段」当成 0 命中，会把加权命中率一路拉低成一个
 * 看起来很像真实、实际毫无意义的数字。
 */
class UsageParserTest {

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `deepseek chat completions cache hit field is parsed`() {
        val sample = UsageParser.parse(
            obj("""{"usage":{"prompt_tokens":1000,"completion_tokens":50,"prompt_cache_hit_tokens":800}}"""),
        )
        assertNotNull(sample)
        assertEquals(1000L, sample!!.inputTokens)
        assertEquals(800L, sample.cachedInputTokens)
        assertEquals(50L, sample.outputTokens)
        assertTrue(sample.cacheComparable)
    }

    @Test
    fun `openai prompt tokens details cached tokens is parsed`() {
        val sample = UsageParser.parse(
            obj("""{"usage":{"prompt_tokens":500,"completion_tokens":10,"prompt_tokens_details":{"cached_tokens":400}}}"""),
        )
        assertEquals(500L, sample!!.inputTokens)
        assertEquals(400L, sample.cachedInputTokens)
    }

    @Test
    fun `responses input tokens details cached tokens is parsed`() {
        val sample = UsageParser.parse(
            obj("""{"usage":{"input_tokens":700,"output_tokens":20,"input_tokens_details":{"cached_tokens":256}}}"""),
        )
        assertEquals(700L, sample!!.inputTokens)
        assertEquals(256L, sample.cachedInputTokens)
    }

    @Test
    fun `usage only final chunk without choices is still parsed`() {
        // 流式 usage-only 末块：choices 为空。
        val sample = UsageParser.parse(
            obj("""{"choices":[],"usage":{"prompt_tokens":321,"completion_tokens":7,"prompt_cache_hit_tokens":100}}"""),
        )
        assertNotNull("usage-only 块必须能解析出来", sample)
        assertEquals(321L, sample!!.inputTokens)
        assertEquals(100L, sample.cachedInputTokens)
    }

    @Test
    fun `missing cache fields stay unknown not zero`() {
        val sample = UsageParser.parse(obj("""{"usage":{"prompt_tokens":900,"completion_tokens":30}}"""))
        assertNotNull(sample)
        assertEquals(900L, sample!!.inputTokens)
        assertNull("供应商没提供缓存字段时必须为未知", sample.cachedInputTokens)
        assertTrue(sample.cacheFieldsAbsent)
        assertFalse("缺字段不能进有效样本", sample.cacheComparable)
    }

    @Test
    fun `negative and non numeric values are treated as unknown`() {
        val sample = UsageParser.parse(
            obj("""{"usage":{"prompt_tokens":-5,"completion_tokens":"abc","prompt_cache_hit_tokens":-1}}"""),
        )
        assertNotNull(sample)
        assertNull(sample!!.inputTokens)
        assertNull(sample.outputTokens)
        assertNull(sample.cachedInputTokens)
    }

    @Test
    fun `cached greater than input is not comparable`() {
        val sample = UsageParser.parse(
            obj("""{"usage":{"prompt_tokens":100,"prompt_cache_hit_tokens":500}}"""),
        )
        assertEquals(100L, sample!!.inputTokens)
        assertEquals(500L, sample.cachedInputTokens)
        assertFalse("口径冲突（cached > input）不能算有效样本", sample.cacheComparable)
    }

    @Test
    fun `string encoded numbers are accepted`() {
        val sample = UsageParser.parse(
            obj("""{"usage":{"prompt_tokens":"120","prompt_cache_hit_tokens":"60"}}"""),
        )
        assertEquals(120L, sample!!.inputTokens)
        assertEquals(60L, sample.cachedInputTokens)
    }

    @Test
    fun `no usage object returns null`() {
        assertNull(UsageParser.parse(obj("""{"choices":[{"delta":{"content":"x"}}]}""")))
    }

    // ── 口径冲突：必须按「未知」处理，不能擅自挑一条当事实 ──

    /**
     * `prompt_tokens` 与 `input_tokens` 同时出现且**互不相等**：两条互相矛盾的
     * 上报，挑选任何一条都会让汇总比例无法解释，所以必须是未知（null）。
     */
    @Test
    fun `conflicting input token fields are unknown`() {
        val sample = UsageParser.parse(
            obj("""{"usage":{"prompt_tokens":100,"input_tokens":200,"completion_tokens":10}}"""),
        )
        assertNull("冲突的输入 token 必须未知", sample!!.inputTokens)
    }

    /** 输出 token 的冲突同理。 */
    @Test
    fun `conflicting output token fields are unknown`() {
        val sample = UsageParser.parse(
            obj("""{"usage":{"prompt_tokens":100,"completion_tokens":10,"output_tokens":99}}"""),
        )
        assertNull("冲突的输出 token 必须未知", sample!!.outputTokens)
        assertEquals("未冲突的字段仍可用", 100L, sample.inputTokens)
    }

    /** 缓存字段冲突（显式 cache_hit 与 details.cached 不一致）同样未知。 */
    @Test
    fun `conflicting cache fields are unknown`() {
        val sample = UsageParser.parse(
            obj(
                """{"usage":{"prompt_tokens":1000,"prompt_cache_hit_tokens":800,"prompt_tokens_details":{"cached_tokens":400}}}""",
            ),
        )
        assertNull("冲突的缓存字段必须未知", sample!!.cachedInputTokens)
        assertFalse("未知样本不得进入命中率分子/分母", sample.cacheComparable)
    }

    /** 同义字段**取值相同**时不算冲突：那是同一事实的两种写法。 */
    @Test
    fun `identical synonymous fields are not a conflict`() {
        val sample = UsageParser.parse(
            obj("""{"usage":{"prompt_tokens":100,"input_tokens":100,"completion_tokens":5}}"""),
        )
        assertEquals(100L, sample!!.inputTokens)
    }

    @Test
    fun `invalid synonym next to valid value is unknown`() {
        val sample = UsageParser.parse(
            obj("""{"usage":{"prompt_tokens":100,"input_tokens":-1,"completion_tokens":5,"prompt_cache_hit_tokens":20,"prompt_tokens_details":{"cached_tokens":"bad"}}}"""),
        )
        assertNull(sample!!.inputTokens)
        assertNull(sample.cachedInputTokens)
    }

    @Test
    fun `cumulative usage within one request is counted once not summed`() {
        val accumulator = UsageAccumulator("chat-completions")
        // 同一请求里多次上报累计 usage（增量块 + 末块）：只能取最后一次。
        accumulator.accept(UsageParser.parse(obj("""{"usage":{"prompt_tokens":100,"prompt_cache_hit_tokens":50}}""")))
        accumulator.accept(UsageParser.parse(obj("""{"usage":{"prompt_tokens":300,"prompt_cache_hit_tokens":150}}""")))
        val result = accumulator.result()
        assertEquals("累计块绝不能相加", 300L, result!!.inputTokens)
        assertEquals(150L, result.cachedInputTokens)
    }

    @Test
    fun `weighted ratio excludes provider absent samples from denominator`() {
        var stats = CacheUsageStats()
        // 两个有效样本：1000 中命中 800；500 中命中 0。
        stats = stats.plus(UsageSample(inputTokens = 1000, cachedInputTokens = 800, outputTokens = 1))
        stats = stats.plus(UsageSample(inputTokens = 500, cachedInputTokens = 0, outputTokens = 1))
        // 三个供应商未提供缓存字段的调用：不得进入分母。
        repeat(3) { stats = stats.plus(UsageSample(inputTokens = 2000, cachedInputTokens = null, outputTokens = 1)) }
        assertEquals(2, stats.validSamples)
        assertEquals(3, stats.providerAbsentSamples)
        assertEquals(1500L, stats.totalInputTokens)
        assertEquals(800L, stats.totalCachedInputTokens)
        assertEquals(800.0 / 1500.0, stats.cachedRatio!!, 1e-9)
        assertEquals(2.0 / 5.0, stats.coverage!!, 1e-9)
    }

    @Test
    fun `ratio is null when no valid sample exists`() {
        var stats = CacheUsageStats()
        stats = stats.plus(UsageSample(inputTokens = 100, cachedInputTokens = null, outputTokens = 1))
        assertNull("没有有效样本时必须报告「不可用」而不是 0%", stats.cachedRatio)
        assertTrue(stats.summary().contains("不可用"))
    }

    @Test
    fun `zero cached is a real zero measurement`() {
        // 与「缺字段」不同：供应商明确给了 0，这是一个有效的零命中观测。
        val stats = CacheUsageStats().plus(UsageSample(inputTokens = 1000, cachedInputTokens = 0, outputTokens = 1))
        assertEquals(1, stats.validSamples)
        assertEquals(0.0, stats.cachedRatio!!, 1e-9)
    }

    @Test
    fun `registry groups by model and merges totals`() {
        val registry = CacheUsageRegistry()
        registry.record("model-a", UsageSample(inputTokens = 100, cachedInputTokens = 50, outputTokens = 1))
        registry.record("model-b", UsageSample(inputTokens = 300, cachedInputTokens = 90, outputTokens = 1))
        registry.record("model-a", UsageSample(inputTokens = 100, cachedInputTokens = 10, outputTokens = 1))
        assertEquals(2, registry.statsFor("model-a").validSamples)
        assertEquals(60.0 / 200.0, registry.statsFor("model-a").cachedRatio!!, 1e-9)
        // model-a 两次 + model-b 一次 = 3 个有效样本。
        assertEquals(3, registry.total().validSamples)
        assertEquals(500L, registry.total().totalInputTokens)
        assertEquals(150L, registry.total().totalCachedInputTokens)
        assertEquals(150.0 / 500.0, registry.total().cachedRatio!!, 1e-9)
    }

    @Test
    fun `summary never leaks content and reports coverage`() {
        val stats = CacheUsageStats()
            .plus(UsageSample(inputTokens = 100, cachedInputTokens = 20, outputTokens = 1))
            .plus(UsageSample(inputTokens = 50, cachedInputTokens = null, outputTokens = 1))
        val text = stats.summary()
        assertTrue(text.contains("有效样本 1/2"))
        assertTrue(text.contains("供应商未提供缓存字段 1 次"))
    }

    /** 缺字段的调用必须计入 unknown，而不是从统计里消失。 */
    @Test
    fun `missing usage sample counts as unknown`() {
        val registry = CacheUsageRegistry()
        registry.record("m", UsageSample(inputTokens = 100, cachedInputTokens = 50, outputTokens = 1))
        registry.record("m", null)
        val stats = registry.statsFor("m")
        assertEquals(1, stats.validSamples)
        assertEquals("缺 usage 的调用必须记为未知", 1, stats.unknownSamples)
        assertEquals(2, stats.totalRequests)
    }

    /**
     * **键数量有上界**：不断出现新的「端点/模型/协议」组合时，
     * 注册表不能无限增长（长时间使用 + 频繁改设置会踩到）。
     */
    @Test
    fun `registry key count is bounded`() {
        val registry = CacheUsageRegistry(maxKeys = 8)
        repeat(50) { index ->
            registry.record(
                "endpoint$index | model$index | chat_completions",
                UsageSample(inputTokens = 10, cachedInputTokens = 5, outputTokens = 1),
            )
        }
        assertEquals("键数量必须有上界", 8, registry.trackedKeys())
        // 最近使用的键仍在（最久未用的被淘汰）。
        assertTrue(registry.statsFor("endpoint49 | model49 | chat_completions").validSamples == 1)
        assertTrue(registry.statsFor("endpoint0 | model0 | chat_completions").validSamples == 0)
    }

    /** 命中已有键时刷新其「最近使用」位置，避免被误淘汰。 */
    @Test
    fun `recently used key is not evicted first`() {
        val registry = CacheUsageRegistry(maxKeys = 3)
        registry.record("a", UsageSample(inputTokens = 1, cachedInputTokens = 0, outputTokens = 1))
        registry.record("b", UsageSample(inputTokens = 1, cachedInputTokens = 0, outputTokens = 1))
        registry.record("c", UsageSample(inputTokens = 1, cachedInputTokens = 0, outputTokens = 1))
        // 再次使用 a：a 变成最近使用。
        registry.record("a", UsageSample(inputTokens = 1, cachedInputTokens = 0, outputTokens = 1))
        // 新增 d 会淘汰最久未用的 b（而不是 a）。
        registry.record("d", UsageSample(inputTokens = 1, cachedInputTokens = 0, outputTokens = 1))
        assertEquals(2, registry.statsFor("a").validSamples)
        assertEquals(0, registry.statsFor("b").validSamples)
    }

    /**
     * 合法 alias 与**非法** alias 并存时，不得挑那条合法的当真相。
     *
     * `prompt_cache_hit_tokens: 800` 合法、`cache_hit_tokens: -5` 非法 ——
     * 结果是"这份 usage 不可信"（null），而不是 800。
     */
    @Test
    fun `valid alias adjacent to an invalid alias is unknown`() {
        val sample = UsageParser.parseUsageObject(
            obj("""{"prompt_tokens":1000,"prompt_cache_hit_tokens":800,"cache_hit_tokens":-5}"""),
        )
        assertNull("非法 alias 与合法 alias 并存时必须判为未知", sample.cachedInputTokens)
        assertTrue("提供了缓存字段，但字段不可信 ⇒ 记 invalid", sample.cacheFieldsPresent)
        val stats = CacheUsageStats().plus(sample)
        assertEquals(1, stats.invalidSamples)
        assertEquals("冲突/非法不得冒充 provider absent", 0, stats.providerAbsentSamples)
    }

    /** 两个 alias 都合法但互不相等 ⇒ 未知，且**不能**算成「供应商未提供」。 */
    @Test
    fun `conflicting cache fields are invalid not provider absent`() {
        val sample = UsageParser.parseUsageObject(
            obj("""{"prompt_tokens":1000,"prompt_cache_hit_tokens":800,"cache_hit_tokens":300}"""),
        )
        assertNull(sample.cachedInputTokens)
        assertTrue(sample.cacheFieldsPresent)
        val stats = CacheUsageStats().plus(sample)
        assertEquals(1, stats.invalidSamples)
        assertEquals(0, stats.providerAbsentSamples)
        assertEquals("有效样本不得被非法者污染", 0, stats.validSamples)
    }

    /** input 缺失 ⇒ unknown（不是 0），不能进命中率分母。 */
    @Test
    fun `missing input token counts as unknown not zero`() {
        val stats = CacheUsageStats().plus(
            UsageParser.parseUsageObject(obj("""{"prompt_cache_hit_tokens":10}""")),
        )
        assertEquals(1, stats.unknownSamples)
        assertEquals(0, stats.validSamples)
        assertNull(stats.cachedRatio)
    }

    /** 加权比例按 token 加权：不同输入规模的样本不能等权平均。 */
    @Test
    fun `weighted ratio follows token totals across different input sizes`() {
        var stats = CacheUsageStats()
        stats = stats.plus(UsageSample(inputTokens = 100, cachedInputTokens = 100, outputTokens = 1))
        stats = stats.plus(UsageSample(inputTokens = 900, cachedInputTokens = 0, outputTokens = 1))
        // 等权平均会得到 50%，按 token 加权应是 100/1000 = 10%。
        assertEquals(0.10, stats.cachedRatio!!, 1e-9)
        assertEquals(1000L, stats.totalInputTokens)
        assertEquals(2.0 / 2.0, stats.coverage!!, 1e-9)
    }
}
