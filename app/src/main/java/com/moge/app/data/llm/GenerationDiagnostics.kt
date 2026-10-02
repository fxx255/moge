package com.moge.app.data.llm

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单调时钟抽象。
 *
 * 为什么不用 `System.currentTimeMillis`：它是墙钟，会因用户改时间、NTP 校时
 * 甚至时区调整而跳变，算出来的耗时会变成负数或离谱的大数。耗时统计必须用
 * **单调时钟**（[System.nanoTime] 语义）。
 */
fun interface MonotonicClock {
    /** 返回单调递增的纳秒数。 */
    fun nanoTime(): Long

    companion object {
        val SYSTEM = MonotonicClock { System.nanoTime() }
    }
}

/** 一次生成的各阶段相对耗时（毫秒），用于定位「多出来的串行等待」。 */
data class GenerationTimings(
    /** 请求开始 → 首个推理字节。 */
    val firstReasoningMs: Long? = null,
    /** 请求开始 → 首个 answer 字节。 */
    val firstAnswerByteMs: Long? = null,
    /** 请求开始 → 首个**用户可见正文**（经过 JSON 解码、确实有内容）。 */
    val firstVisibleTextMs: Long? = null,
    /** 请求开始 → 完成。 */
    val completedMs: Long? = null,
    /** 请求开始 → 中断。 */
    val interruptedMs: Long? = null,
) {
    /** 简短可读汇总；不含题目、正文或推理内容。 */
    fun summary(): String = buildString {
        firstReasoningMs?.let { append("首推理 ${it}ms；") }
        firstAnswerByteMs?.let { append("首字节 ${it}ms；") }
        firstVisibleTextMs?.let { append("首可见正文 ${it}ms；") }
        completedMs?.let { append("完成 ${it}ms；") }
        interruptedMs?.let { append("中断 ${it}ms；") }
    }.trimEnd('；', ' ')
}

/**
 * 生成过程的可控计时器。
 *
 * 用注入的 [MonotonicClock]，测试里换成可控时钟就能在不 sleep 的前提下断言
 * 「收到可展示正文后约 300ms 内更新」这类要求。
 */
class GenerationTimer(private val clock: MonotonicClock = MonotonicClock.SYSTEM) {
    private val start = clock.nanoTime()
    private var firstReasoning: Long? = null
    private var firstAnswerByte: Long? = null
    private var firstVisibleText: Long? = null
    private var completed: Long? = null
    private var interrupted: Long? = null

    private fun elapsedMs(): Long = (clock.nanoTime() - start) / 1_000_000

    fun markReasoning() {
        if (firstReasoning == null) firstReasoning = elapsedMs()
    }

    fun markAnswerByte() {
        if (firstAnswerByte == null) firstAnswerByte = elapsedMs()
    }

    fun markVisibleText() {
        if (firstVisibleText == null) firstVisibleText = elapsedMs()
    }

    fun markCompleted() {
        if (completed == null) completed = elapsedMs()
    }

    fun markInterrupted() {
        if (interrupted == null) interrupted = elapsedMs()
    }

    fun snapshot(): GenerationTimings = GenerationTimings(
        firstReasoningMs = firstReasoning,
        firstAnswerByteMs = firstAnswerByte,
        firstVisibleTextMs = firstVisibleText,
        completedMs = completed,
        interruptedMs = interrupted,
    )
}

/**
 * 诊断记录的**有界**内存存储。
 *
 * 约束：
 * - 只记录相对时间、requestId、模型/端点类型与 usage 等元数据；
 * - **绝不**记录 API 密钥、完整题目、原始聊天/推理正文、URL 查询参数或附件内容；
 * - 数量有上限，超出后丢最旧的，不会无限增长；
 * - 只在设置页的诊断入口提供简短汇总。
 */
@Singleton
class GenerationDiagnostics @Inject constructor() {

    data class Entry(
        val requestId: String,
        /** 模型/端点类型标识（模型名 + 协议），不含密钥与完整 URL。 */
        val modelKey: String,
        val protocol: String,
        /** 本轮**逻辑请求**的耗时与终态；不再承载 usage（见 [recordHttpUsage]）。 */
        val timings: GenerationTimings,
        /** 本轮实际 HTTP 调用数 − 1（首轮之外的续写/恢复/回退）。 */
        val extraRequests: Int,
        val outcome: String,
    )

    private val entries = ArrayDeque<Entry>()
    private val lock = Any()
    private val registry = CacheUsageRegistry()

    /** 一次**逻辑轮**的终态与耗时；usage 由 [recordHttpUsage] 逐次单独统计。 */
    fun record(entry: Entry) {
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    /**
     * 每个**实际 HTTP 调用**上报一次 usage（含缺失/失败时的 null）。
     *
     * 与 [record] 分离的原因：以前一个逻辑轮只记最后一次 usage，于是续写/恢复/
     * 回退各自的那次请求在诊断里完全看不见，"调用次数"也只是循环轮数而非真实 HTTP 数。
     * 现在次数与样本都来自这里的逐次上报。
     */
    fun recordHttpUsage(groupKey: String, sample: UsageSample?) {
        synchronized(lock) { registry.record(groupKey, sample) }
    }

    /** 最近若干条（新的在前）。 */
    fun recent(limit: Int = 20): List<Entry> = synchronized(lock) {
        entries.toList().takeLast(limit).asReversed()
    }

    fun cacheStats(): Map<String, CacheUsageStats> = synchronized(lock) { registry.snapshot() }

    fun totalCacheStats(): CacheUsageStats = synchronized(lock) { registry.total() }

    /** 一句话汇总，供已有诊断入口展示。 */
    fun summary(): String = synchronized(lock) {
        val recentEntries = entries.toList().takeLast(MAX_ENTRIES)
        val latest = recentEntries.lastOrNull()
        buildString {
            append("缓存：").append(registry.total().summary())
            if (latest != null) {
                append("\n最近一次：").append(latest.modelKey)
                append("（").append(latest.protocol).append("）")
                val timing = latest.timings.summary()
                if (timing.isNotEmpty()) append("\n耗时：").append(timing)
                if (latest.extraRequests > 0) append("\n额外请求：").append(latest.extraRequests).append(" 次")
            }
        }
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        registry.clear()
    }

    companion object {
        /** 有界：只保留最近 100 次生成诊断。 */
        const val MAX_ENTRIES = 100
    }
}

/**
 * 请求标识。
 *
 * 只保留「模型名 + 协议」这类可安全展示的标识，**不带** baseUrl 的查询参数、
 * 也不带密钥；避免诊断数据里混进用户隐私。
 */
internal fun modelDiagnosticKey(model: String, protocol: String): String {
    val trimmed = model.trim().ifBlank { "未标注模型" }
    return if (protocol.isBlank()) trimmed else "$trimmed / $protocol"
}
