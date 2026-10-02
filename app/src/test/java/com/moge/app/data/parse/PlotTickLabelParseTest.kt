package com.moge.app.data.parse

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 坐标刻度支持「自定义文案」—— 这是「题目用符号表达时，坐标轴也必须标符号」的基础能力。
 *
 * 背景（v1.0.34 用户反馈）：题目里 f_c、N_0、B、T 都是参数，并没有给出数值，
 * 但模型把参数代入了具体数值（纵轴标成 3900 / 4200），等于把图换成了另一道题。
 * 正确做法是用 `ticks` + `tickLabels` 标出参数符号（`-B/2`、`O`、`B/2`、`N_0(2πf_c)^2`），
 * 数据点仍用数值占位去采样 —— 所以这条链路必须原样透传，不能被截断或丢失。
 */
class PlotTickLabelParseTest {

    @Test
    fun `explicit empty ticks hides labels while omitted ticks stays automatic`() {
        val spec = ReplyParser.parse(
            """{"reply":"示意图。","plots":[{"series":[{"expr":"x^2+2"}],"y":{"min":0,"max":3.6,"ticks":[],"grid":false}}]}""",
        ).plots.single()
        assertEquals(emptyList<Double>(), spec.y.ticks)
        assertEquals(null, spec.x.ticks)
    }

    @Test
    fun `刻度文案原样保留参数符号与 LaTeX`() {
        val raw = """
            {"reply":"频谱如下，横轴为归一化频率。","plots":[{
              "title":"同相/正交分量功率谱",
              "x":{"label":"f","min":-2.4,"max":2.4,"ticks":[-2,0,2],
                   "tickLabels":{"-2":"-B/2","0":"O","2":"B/2"}},
              "y":{"ticks":[1],"tickLabels":{"1":"${'$'}N_0(2\\pi f_c)^2${'$'}"}},
              "series":[{"expr":"x^2"}]
            }]}
        """.trimIndent()

        val spec = ReplyParser.parse(raw).plots.single()

        assertEquals(
            "x 轴刻度文案必须原样保留参数符号",
            mapOf(-2.0 to "-B/2", 0.0 to "O", 2.0 to "B/2"),
            spec.x.tickLabels,
        )
        assertEquals("显式刻度位置也要保留", listOf(-2.0, 0.0, 2.0), spec.x.ticks)
        assertEquals(
            "带 LaTeX 定界符的刻度文案不能被截断成残式",
            """${'$'}N_0(2\pi f_c)^2${'$'}""",
            spec.y.tickLabels[1.0],
        )
    }

    @Test
    fun `没有 tickLabels 时保持空映射而不是报错`() {
        val raw = """{"reply":"正文内容足够长了。","plots":[{"series":[{"expr":"x"}],"x":{"min":-1,"max":1}}]}"""
        val spec = ReplyParser.parse(raw).plots.single()
        assertEquals(emptyMap<Double, String>(), spec.x.tickLabels)
    }
}
