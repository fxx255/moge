package com.moge.app.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增量 reply 解码器验收（实施文档 §7.1）。
 *
 * 核心不变量：**同一输入按任意 chunk 切分，最终正文必须完全一致**。
 */
class IncrementalReplyDecoderTest {

    private fun decodeInChunks(raw: String, chunkSize: Int): String {
        val decoder = IncrementalReplyDecoder()
        var index = 0
        while (index < raw.length) {
            val end = minOf(index + chunkSize, raw.length)
            decoder.append(raw.substring(index, end))
            index = end
        }
        return decoder.finish()
    }

    private fun assertSplitInvariant(raw: String, expected: String) {
        for (size in 1..minOf(64, raw.length.coerceAtLeast(1))) {
            assertEquals("chunkSize=$size 切分结果不一致", expected, decodeInChunks(raw, size))
        }
        assertEquals("整体解码不一致", expected, decodeReplyIncremental(raw))
    }

    @Test
    fun `plain reply decodes`() {
        val raw = """{"reply":"你好，世界"}"""
        assertSplitInvariant(raw, "你好，世界")
    }

    @Test
    fun `escaped quotes and backslashes survive`() {
        val raw = """{"reply":"他说\"行\"，路径 C:\\Users\\a"}"""
        assertSplitInvariant(raw, "他说\"行\"，路径 C:\\Users\\a")
    }

    @Test
    fun `newline and tab escapes decode`() {
        val raw = """{"reply":"第一行\n第二行\t制表"}"""
        assertSplitInvariant(raw, "第一行\n第二行\t制表")
    }

    @Test
    fun `latex single backslash is preserved literally`() {
        // 模型经常给出非法但常见的单反斜杠 LaTeX；解码器不得吃掉它。
        val raw = """{"reply":"公式 $$\\frac{a}{b}$$"}"""
        assertSplitInvariant(raw, """公式 $$\frac{a}{b}$$""")
    }

    @Test
    fun `unicode escape decodes`() {
        val raw = """{"reply":"\u4e2d\u6587 \u0041"}"""
        assertSplitInvariant(raw, "中文 A")
    }

    @Test
    fun `emoji surrogate pair split across chunks stays intact`() {
        val raw = """{"reply":"完成 🎉✅"}"""
        assertSplitInvariant(raw, "完成 🎉✅")
        // 明确按 1 字符切分（必然切开代理对）。
        assertEquals("完成 🎉✅", decodeInChunks(raw, 1))
    }

    @Test
    fun `emoji written as surrogate escapes decodes`() {
        val raw = """{"reply":"\ud83c\udf89 ok"}"""
        assertSplitInvariant(raw, "\uD83C\uDF89 ok")
    }

    @Test
    fun `reply not the first field`() {
        val raw = """{"diagrams":[{"title":"D"}],"reply":"正文在后","plots":[]}"""
        assertSplitInvariant(raw, "正文在后")
    }

    @Test
    fun `nested fake reply inside figures is ignored`() {
        val raw = """{"plots":[{"reply":"不该出现","inner":{"reply":"也不该出现"}}],"reply":"真正的正文"}"""
        assertSplitInvariant(raw, "真正的正文")
    }

    @Test
    fun `nested object before reply is skipped`() {
        val raw =
            """{"plots":[{"title":"图","series":[{"expr":"x^2"}]}],"diagrams":[{"nodes":[{"id":"a"}]}],"reply":"正文"}"""
        assertSplitInvariant(raw, "正文")
    }

    @Test
    fun `escaped quotes inside nested object do not break structure`() {
        val raw = """{"plots":[{"title":"含\"引号\"的标题"}],"reply":"安全正文"}"""
        assertSplitInvariant(raw, "安全正文")
    }

    @Test
    fun `fenced json is supported`() {
        val raw = "```json\n{\"reply\":\"围栏里的正文\",\"plan_actions\":[]}\n```"
        assertSplitInvariant(raw, "围栏里的正文")
    }

    @Test
    fun `preamble text before json is ignored`() {
        val raw = "好的，这是回答：\n{\"reply\":\"正文\",\"plan_actions\":[]}"
        assertSplitInvariant(raw, "正文")
    }

    @Test
    fun `table markdown with pipes survives`() {
        val raw = """{"reply":"| 列1 | 列2 |\n| --- | --- |\n| a | b |"}"""
        assertSplitInvariant(raw, "| 列1 | 列2 |\n| --- | --- |\n| a | b |")
    }

    @Test
    fun `truncated eof keeps partial reply`() {
        val raw = """{"reply":"被截断的正文还没有结束"""
        val decoder = IncrementalReplyDecoder()
        decoder.append(raw)
        assertEquals("被截断的正文还没有结束", decoder.finish())
        assertTrue(decoder.foundReply)
    }

    @Test
    fun `truncated mid escape does not emit broken sequence`() {
        val decoder = IncrementalReplyDecoder()
        decoder.append("""{"reply":"正文结尾""")
        val mid = decoder.text
        decoder.append("\\")
        assertEquals("半个转义不得产生新字符", mid, decoder.text)
        decoder.append("n")
        assertEquals("正文结尾\n", decoder.finish())
    }

    @Test
    fun `truncated unicode escape does not leak raw digits`() {
        val decoder = IncrementalReplyDecoder()
        decoder.append("""{"reply":"A\u00""")
        val text = decoder.finish()
        assertFalse("不得把半截 \\u 原样露出", text.contains("u00"))
        assertTrue(text.startsWith("A"))
    }

    @Test
    fun `reasoning only response yields no visible text`() {
        // 只有推理、没有 reply：解码器不能凭空产出正文。
        val raw = """{"plan_actions":[],"plots":[]}"""
        val decoder = IncrementalReplyDecoder()
        decoder.append(raw)
        assertEquals("", decoder.finish())
        assertFalse(decoder.foundReply)
        assertFalse(decoder.hasVisibleText())
    }

    @Test
    fun `plain non json text is not decoded as reply`() {
        val raw = "这不是 JSON，只是普通一段话。"
        val decoder = IncrementalReplyDecoder()
        decoder.append(raw)
        assertEquals("", decoder.finish())
        assertEquals("", decodeReplyIncremental(raw))
    }

    @Test
    fun `unicode escaped braces inside reply do not close object`() {
        val raw = """{"reply":"\u007b 转义大括号 \u007d 仍在字符串里"}"""
        assertSplitInvariant(raw, "{ 转义大括号 } 仍在字符串里")
    }

    @Test
    fun `literal brace character inside string does not break nesting`() {
        val raw = """{"reply":"函数写法 f{ x } 与 { 括号 }"}"""
        assertSplitInvariant(raw, "函数写法 f{ x } 与 { 括号 }")
    }

    @Test
    fun `empty reply is detected as found`() {
        val decoder = IncrementalReplyDecoder()
        decoder.append("""{"reply":""}""")
        assertEquals("", decoder.finish())
        assertTrue(decoder.foundReply)
        assertFalse(decoder.hasVisibleText())
    }

    // ── 回归：普通字符串**值**不得被当成键名（协调者审查第 5 条反例） ──

    /**
     * `{"note":"reply","foo":"secret","reply":"answer"}`：
     * 早先实现把 note 的**值** "reply" 当成键名，于是 "secret" 被当成正文、
     * 真正的 reply 反而被跳过。这里的正确结果只有一个："answer"。
     */
    @Test
    fun `ordinary string value named reply is not a key`() {
        val raw = """{"note":"reply","foo":"secret","reply":"answer"}"""
        assertSplitInvariant(raw, "answer")
    }

    /** 值在先、reply 在后的更复杂反例：值里甚至嵌套对象。 */
    @Test
    fun `string value containing nested object before reply is ignored`() {
        val raw = """{"note":{"reply":"假正文","depth":2},"reply":"真正文"}"""
        assertSplitInvariant(raw, "真正文")
    }

    /** 数组里恰好有个字符串 "reply"：数组元素永远是值，绝不能被当成键名。 */
    @Test
    fun `array element named reply is a value not a key`() {
        val raw = """{"tags":["reply","x"],"reply":"正文"}"""
        assertSplitInvariant(raw, "正文")
    }

    /** reply 键前出现的键值对不止一个。 */
    @Test
    fun `multiple key value pairs before reply`() {
        val raw = """{"a":"1","b":{"c":"d"},"e":[1,2,"reply"],"reply":"OK"}"""
        assertSplitInvariant(raw, "OK")
    }

    @Test
    fun `pending escape never consumes a closing quote or following escape`() {
        for (escape in listOf("n", "t", "f", "b", "r")) {
            val control = when (escape) {
                "n" -> '\n'
                "t" -> '\t'
                "f" -> '\u000C'
                "b" -> '\b'
                else -> '\r'
            }
            assertSplitInvariant("""{"reply":"A\$escape","foo":"secret"}""", "A$control")
            assertSplitInvariant("""{"note":"A\$escape","reply":"answer"}""", "answer")
            assertSplitInvariant("""{"reply":"A\$escape\u4e2d","foo":"secret"}""", "A${control}中")
        }
    }

    @Test
    fun `n command requires the complete word and keeps structural state`() {
        assertSplitInvariant("""{"reply":"A\nempty","foo":"secret"}""", "A\nempty")
        assertSplitInvariant("""{"reply":"A\narrow","foo":"secret"}""", "A\narrow")
        assertSplitInvariant("""{"note":"\nabla","reply":"answer"}""", "answer")
        assertSplitInvariant("""{"reply":"\neq"}""", "\\neq")
        assertSplitInvariant("""{"reply":"\ne"}""", "\\ne")
        assertEquals("\\nabla", decodeReplyIncremental("""{"reply":"\nabla"""))
    }

    @Test
    fun `invalid unicode does not swallow closing delimiter`() {
        assertSplitInvariant("""{"reply":"A\u12","foo":"secret"}""", "A\\u12")
        assertSplitInvariant("""{"note":"A\u12","reply":"answer"}""", "answer")
    }

    // ── 回归：未知转义不得吞掉反斜杠（LaTeX） ──

    /**
     * **模型单反斜杠写 LaTeX**：`\frac` 必须原样出现在流式正文里。
     *
     * 这是本层的核心回归：`\f` 在 JSON 里是合法转义（换页），但模型写公式时
     * 几乎总是只给一个反斜杠。流式层用**延迟判别**处理这种歧义：
     * 转义字母后面跟字母就按 LaTeX 保留，否则才当控制字符。
     */
    @Test
    fun `single backslash latex frac survives streaming`() {
        val raw = """{"reply":"$$\frac{a}{b}$$"}"""
        assertSplitInvariant(raw, """$$\frac{a}{b}$$""")
    }

    /** 其余高频单反斜杠命令同样必须完整保留。 */
    @Test
    fun `single backslash latex text and beta and rho survive streaming`() {
        val raw = """{"reply":"\text{甲} 与 \beta 和 \rho"}"""
        assertSplitInvariant(raw, """\text{甲} 与 \beta 和 \rho""")
    }

    /** `\nabla` 是命令，必须保留；而 `\n` 后面不是命令时仍是换行。 */
    @Test
    fun `nabla command is preserved while plain newline stays a newline`() {
        assertSplitInvariant(
            """{"reply":"$$\nabla f$$"}""",
            """$$\nabla f$$""",
        )
        assertSplitInvariant(
            """{"reply":"第一行\n第二行"}""",
            "第一行\n第二行",
        )
    }

    /** 换行后紧跟英文单词：`\n` 是换行，不是 `\n...` 命令。 */
    @Test
    fun `newline followed by english word stays newline`() {
        val raw = """{"reply":"见下\nnetwork 层"}"""
        assertSplitInvariant(raw, "见下\nnetwork 层")
    }

    /** 真正的制表/换页（后面**不跟 ASCII 字母**）仍按 JSON 控制字符解码。 */
    @Test
    fun `real control characters decode when not followed by a letter`() {
        // 与既有 `newline and tab escapes decode` 同一判据：转义字母后跟非字母
        // （这里是中文）才是真正的控制字符；后跟 ASCII 字母时按 LaTeX 保留。
        val raw = """{"reply":"A\t制表"}"""
        assertSplitInvariant(raw, "A\t制表")
    }

    /** 真正未知的转义（`\g`、`\q` 等）必须保留反斜杠，而不是吞掉。 */
    @Test
    fun `truly unknown escape keeps its backslash`() {
        val raw = """{"reply":"\gxi \qmat"}"""
        assertSplitInvariant(raw, """\gxi \qmat""")
    }

    /** 非法 `\u` 序列（非十六进制）：`\u` 与已吃掉的字符都必须按原样回吐。 */
    @Test
    fun `invalid unicode escape keeps backslash and digits`() {
        val raw = """{"reply":"x \uZZ9 y"}"""
        assertSplitInvariant(raw, """x \uZZ9 y""")
    }

    /**
     * 被截断在 `\u` 中间（EOF）：**尚未证明非法**，绝不能把半截转义泄漏到界面。
     *
     * 这与 [invalid unicode escape keeps backslash and digits] 并不矛盾：
     * 只有当后续字符**已经证明**这个转义非法时才回吐；输入就此结束时
     * （模型被截断／网络断开），半截 `\uAB` 既不是正文也不该闪现。
     */
    @Test
    fun `truncated unicode escape at eof emits nothing for the partial escape`() {
        val decoder = IncrementalReplyDecoder()
        decoder.append("""{"reply":"x \uAB""")
        val result = decoder.finish()
        assertEquals("已在正文保留", true, result.contains("x "))
        assertFalse("半截 \\uAB 不得泄漏到界面", result.contains("\\uAB"))
        assertFalse("不得泄漏原始十六进制片段", result.contains("uAB"))
    }

    /** 合法与非法混在同一正文里，且被切成极小片段。 */
    @Test
    fun `mixed valid and invalid escapes across tiny chunks`() {
        // "\\uZZ" 用拼接构造：Kotlin 普通字符串不允许 \u 后跟非十六进制字符。
        val expected = "中 " + "\\uZZ" + " \\q ok\n完"
        val raw = """{"reply":"\u4e2d \uZZ \q ok\n完"}"""
        assertSplitInvariant(raw, expected)
    }
    /**
     * **完整管线**：模型单反斜杠输出 → 流式解码 → 完整响应正式解析，
     * 最终正文必须与模型写的原文**逐字一致**，公式不被破坏。
     *
     * 用真实 [ReplyParser] 断言精确正文，而不是 `contains("frac")`
     * 这种几乎恒真的弱断言。
     */
    @Test
    fun `streaming then final parse preserves latex exactly`() {
        val reply = """结论：$$\frac{a}{b}$$ 与 \alpha 以及 \nabla f 都要保留。"""
        // 模型实际发出的 JSON（单反斜杠，JSON 层面非法但极常见）。
        val raw = """{"reply":"$reply"}"""

        // 1) 流式层：公式原样可见（不得被吃成换页/制表）。
        val streamed = decodeReplyIncremental(raw)
        assertEquals("流式正文必须与原文逐字一致", reply, streamed)

        // 2) 完整响应层：正式解析器给出同一正文。
        val parsed = ReplyParser.parse(raw, normalizeMarkdown = false)
        assertEquals("正式解析正文必须与原文逐字一致", reply, parsed.reply)
        assertEquals("流式与最终解析结果必须一致", parsed.reply, streamed)
    }

    /**
     * **UI 安全可见边界**：流式过程中**任何前缀**都不得泄漏半截转义或外层结构。
     *
     * 逐字符喂入时，每一步的可见正文都必须是最终正文的前缀 —— 这正是
     * 「`\u` 片段一闪而过」「公式被吃成控制字符」这类问题的判据。
     */
    @Test
    fun `every streaming prefix is safe to display`() {
        val reply = """结论：$$\nabla f = \frac{1}{2}$$ 完。"""
        val raw = """{"reply":"$reply"}"""
        val decoder = IncrementalReplyDecoder()
        val visible = StringBuilder()
        for (ch in raw) {
            visible.append(decoder.append(ch.toString()))
            val snapshot = visible.toString()
            assertTrue(
                "第 ${visible.length} 个字符处泄漏了非正文内容：$snapshot",
                reply.startsWith(snapshot),
            )
            assertFalse("不得出现外层 JSON 片段", snapshot.contains("plan_actions"))
            assertFalse("不得出现半截转义", snapshot.endsWith("\\"))
            assertFalse("不得出现半截 unicode 转义", snapshot.endsWith("\\u"))
        }
        assertEquals("逐字符喂完后必须等于最终正文", reply, decoder.finish())
    }
}
