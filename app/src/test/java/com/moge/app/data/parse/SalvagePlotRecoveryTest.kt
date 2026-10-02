package com.moge.app.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长回答被截断时，`plots` 与正文一起恢复。
 *
 * 之前的 `salvageTruncated` 只抢救 reply 与动作数组，**丢掉了 plots**：
 * 模型明明已经把图写出来了，客户端却拿不到，`imagePaths` 为空，
 * 而正文里的 `[[FIGURE:n]]` 锚点还在 —— 渲染层就把锚点当普通文字露出来
 * （用户截图里裸着 `[[FIGURE:1]]`、`[[FIGURE:2]]`）。
 */
class SalvagePlotRecoveryTest {

    private val truncated = """
        {"reply": "先看频谱。$$\nS_c(f) = 4\pi^2 N_0(f_c^2+f^2)\n$$\n\n[[FIGURE:1]]\n\n## 五、同相分量",
        "plots": [{"title": "$\${'$'}S_c(f)${'$'}$", "series": [{"expr": "4*pi^2*(25+x^2)", "label": "S_c"}], "x": {"min": -1, "max": 1}}],
        "plan_actions": []
    """.trimIndent()

    @Test
    fun `plots are recovered from truncated json`() {
        val parsed = ReplyParser.parse(truncated)

        assertTrue("正文应被恢复", parsed.reply.contains("先看频谱"))
        assertEquals("plots 必须一起恢复，否则锚点会裸露", 1, parsed.plots.size)
        assertEquals("S_c", parsed.plots[0].series.first().label)
    }

    @Test
    fun `truncated plot entry is dropped without killing the rest`() {
        val raw = """
            {"reply": "正文内容在这里，长度足够。",
            "plots": [
              {"series": [{"expr": "x^2"}]},
              {"series": [{"expr": "x^3"}]},
              {"series": [{"expr": "sin(x)
        """.trimIndent()

        val parsed = ReplyParser.parse(raw)

        assertTrue("已闭合的两张图应保留", parsed.plots.size >= 2)
    }

    @Test
    fun `normal fully closed json still parses`() {
        val raw = """
            {"reply": "正文", "plots": [{"series": [{"expr": "x"}]}]}
        """.trimIndent()

        val parsed = ReplyParser.parse(raw)

        assertEquals(1, parsed.plots.size)
        assertEquals("正文", parsed.reply)
    }
}
