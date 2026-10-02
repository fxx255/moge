package com.moge.app.data.llm

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.BufferedSource

/**
 * 一个 SSE 事件：`data:` 行按 `\n` 连接，`event:` 名单独保留。
 *
 * [data] 为空表示这个事件只有 event 名（或全是注释/id/retry），调用方应跳过。
 */
data class SseEvent(val name: String?, val data: String)

/**
 * 按 **SSE 空行边界**读取事件；回调返回 `false` 即停止读取。
 *
 * 为什么不能逐行 parse：一个事件可以包含多个 `data:` 行
 * （长 JSON 被拆成若干行，或 vendor 故意分片），逐行 parse 会把每行当成
 * 一个独立事件 —— 每行都不是合法 JSON，于是整段内容被静默丢弃。
 *
 * 规则：
 * - 空行 ⇒ 派发当前积累的事件；
 * - 多个 `data:` 行用 `\n` 连接；
 * - 字段值只去掉冒号后**至多一个** ASCII 空格（SSE 规范，不做 trim）；
 * - `:` 开头的注释行与已识别的 `id:`/`retry:` 字段忽略（不算原始响应行）；
 * - `event:` 只记名字；
 * - 回调返回 false ⇒ 立即停止（例如 Chat 的 `[DONE]`、Responses 的 completed）；
 * - 流结束（EOF）时派发最后一个**没有空行结尾**的 pending 事件。
 *
 * 循环写成 `while (true) { ensureActive(); readUtf8Line() ?: break }`：
 * `exhausted()` 会阻塞等待更多数据，取消检查点必须放在阻塞读**之前**。
 *
 * `readUtf8Line` 已经处理 CRLF。
 */
internal suspend fun BufferedSource.readSseEvents(
    onRawLine: ((String) -> Unit)? = null,
    onEvent: suspend (SseEvent) -> Boolean,
) {
    val dataLines = mutableListOf<String>()
    var eventName: String? = null

    suspend fun dispatch(): Boolean {
        if (dataLines.isEmpty()) {
            eventName = null
            return true
        }
        val data = dataLines.joinToString("\n")
        dataLines.clear()
        val name = eventName
        eventName = null
        return onEvent(SseEvent(name, data))
    }

    while (true) {
        currentCoroutineContext().ensureActive()
        val line = readUtf8Line() ?: break
        when {
            line.isEmpty() -> if (!dispatch()) return
            line.startsWith(":") -> Unit
            line.startsWith("event:") -> eventName = value(line)
            line.startsWith("data:") -> dataLines += value(line)
            // 已识别的 SSE 字段不属于「原始响应 JSON」。
            line.startsWith("id:") || line.startsWith("retry:") -> Unit
            else -> if (line.isNotBlank()) onRawLine?.invoke(line)
        }
    }
    // EOF：提交最后一组没有以空行结尾的数据行。
    dispatch()
}

/** SSE 字段值：去掉冒号后**至多一个** ASCII 空格，其余空白保留。 */
private fun value(line: String): String {
    val rest = line.substringAfter(':')
    return if (rest.startsWith(" ")) rest.removePrefix(" ") else rest
}
