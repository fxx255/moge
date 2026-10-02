package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Paint
import android.text.TextPaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 守卫：表格宽度**按需**撑宽，而不是无条件乘 1.8。
 *
 * 背景（用户反馈）：「表格有些时候宽度富裕很多，但是依然控了很大」——
 * 旧实现是 `containerWidth * TABLE_WIDTH_FACTOR`，**没有任何「够用就停」的分支**，
 * 于是只有两三列短文字的窄表格也被拉成 1.8 倍，右侧一大半被顶到屏幕外。
 *
 * 覆盖两件事：
 * 1. [resolveTableWidthPx] 的决策（不撑 / 撑到刚好够 / 不超过上限）
 * 2. [estimateTableNaturalWidthPx] 的估算（按**最宽列 × 列数**，不是各列之和）
 *
 * ⚠️ Robolectric 不栅格化字体，`measureText` 返回的是近似值，所以本类
 * **不依赖绝对像素值**，只用相对比较（宽表格 > 窄表格、两个判据的差异等）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TableWidthEstimateTest {

    /** 模拟 density 3 的真机：17sp 正文 ⇒ 51px 字号。 */
    private val textSizePx = MARKDOWN_TEXT_SIZE_SP * 3f
    private val cellPaddingPx = 6

    private fun paint(): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = textSizePx }

    /**
     * 确定性的近似测宽器。
     *
     * ⚠️ Robolectric 的 `measureText` **直接返回字符数**（实测「名称」= 2.0）——
     * 它压根不栅格化字体。拿它做宽度断言全是假绿/假红，所以注入自己的实现：
     * 汉字按 1 个字宽、ASCII 按 0.55 个字宽。够用来验证相对关系与决策行为。
     */
    private fun approxMeasure(): (String) -> Float = { s ->
        (s.sumOf { c -> if (c.code > 0x2E80) 1.0 else 0.55 } * textSizePx).toFloat()
    }

    private fun estimate(table: String): Int =
        estimateTableNaturalWidthPx(table, paint(), cellPaddingPx, approxMeasure())

    /** 探测：把 Robolectric 的假值与注入的近似值都打出来，留作假绿陷阱的证据。 */
    @Test
    fun probeMeasureTextScale() {
        val p = paint()
        println("== 测宽探测（textSize=$textSizePx）==")
        println("  Robolectric 原生 measureText(\"名称\") = ${p.measureText("名称")}  ← 假值，等于字符数")
        println("  Robolectric 原生 measureText(\"xxxx\") = ${p.measureText("xxxx")}  ← 同上")
        println("  注入的近似测宽器(\"名称\")           = ${approxMeasure()("名称")}")
        println("  注入的近似测宽器(\"xxxx\")           = ${approxMeasure()("xxxx")}")
        println("  窄表格估算 = ${estimate(NARROW_TABLE)}")
        println("  宽表格估算 = ${estimate(WIDE_TABLE)}")
        println("  气泡宽度参照 = $CONTAINER_PX")
    }

    // ---------- 决策：resolveTableWidthPx ----------

    /** 放得下就不撑。这是本次要修的核心行为。 */
    @Test
    fun `放得下时不撑宽，直接用容器宽度`() {
        val container = 900
        assertEquals(container, resolveTableWidthPx(container, 300))
        // 恰好相等也算放得下
        assertEquals(container, resolveTableWidthPx(container, container))
    }

    /** 放不下时撑到「刚好够」，而不是一上来就顶到 1.8 倍。 */
    @Test
    fun `放不下时撑到刚好够而不是直接顶满`() {
        val container = 900
        val natural = 1200
        val resolved = resolveTableWidthPx(container, natural)
        assertEquals("应当正好撑到自然宽度", natural, resolved)
        assertTrue("不该被顶到上限", resolved < (container * TABLE_MAX_WIDTH_FACTOR).toInt())
    }

    /** 上限仍然生效，避免超宽表格失控。 */
    @Test
    fun `自然宽度超过上限时截断到上限`() {
        val container = 900
        assertEquals(1620, resolveTableWidthPx(container, 100_000))
    }

    @Test
    fun `容器宽度未测到时不返回负数或垃圾值`() {
        assertEquals(0, resolveTableWidthPx(0, 500))
        assertEquals(0, resolveTableWidthPx(-1, 500))
    }

    // ---------- 估算：estimateTableNaturalWidthPx ----------

    /** 回归用户反馈：两三列短文字的窄表格，**不该**被判定为需要撑宽。 */
    @Test
    fun `窄表格在常见气泡宽度下不需要撑宽`() {
        val natural = estimate(NARROW_TABLE)
        assertTrue(
            "窄表格不该被撑宽：natural=$natural > container=$CONTAINER_PX",
            natural <= CONTAINER_PX,
        )
    }

    /** 很宽的表格确实需要撑宽。 */
    @Test
    fun `宽表格确实需要撑宽`() {
        val natural = estimate(WIDE_TABLE)
        assertTrue(
            "宽表格应判定为放不下：natural=$natural <= container=$CONTAINER_PX",
            natural > CONTAINER_PX,
        )
    }

    /** 宽表格的估算值必须大于窄表格（保证估算有区分度，不是常数）。 */
    @Test
    fun `估算值随内容变宽而变大`() {
        assertTrue(estimate(WIDE_TABLE) > estimate(NARROW_TABLE))
    }

    /**
     * 判据必须是「**最宽列 × 列数**」，不能是「各列之和」。
     *
     * Markwon 把可用宽度**均分**给每列，而不是按内容分配。所以三列 200/50/50 的表格，
     * 按「和」是 300 看着正好，实际均分后每列只有 100，第一列照样被挤换行。
     * 若用「最宽列 × 列数」，则 200/50/50 与 200/200/200 需要的宽度**相同** —— 用它来验证。
     */
    @Test
    fun `列宽不均时按最宽列乘列数而不是各列之和`() {
        val uneven = estimate(
            "| ${"x".repeat(200)} | ${"y".repeat(50)} | z |\n|---|---|---|\n| a | b | c |",
        )
        val even = estimate(
            "| ${"x".repeat(200)} | ${"x".repeat(200)} | ${"x".repeat(200)} |\n" +
                "|---|---|---|\n| a | b | c |",
        )
        assertEquals(
            "200/50/50 与 200/200/200 需要的宽度应相同（均分列宽），实际 $uneven vs $even",
            even, uneven,
        )

        // 反过来：若改用「各列之和」，200/50/50 会明显小于 200/200/200。
        // 这里用一个反例钉死：三列 200/1/1 也必须按 200×3 算，远大于「和」的量级。
        val oneWide = estimate(
            "| ${"x".repeat(200)} | a | b |\n|---|---|---|\n| a | b | c |",
        )
        assertTrue(
            "单列很宽时应按 200×3 估（≈$even），实际 $oneWide",
            oneWide > even * 0.9,
        )
    }

    /** 分隔行 `|---|---|` 不能被当成数据行参与列宽计算。 */
    @Test
    fun `分隔行不参与列宽计算`() {
        val withSeparator = "| a | b |\n|---|---|\n| 1 | 2 |"
        val withoutSeparator = "| a | b |\n| 1 | 2 |"
        assertEquals(estimate(withoutSeparator), estimate(withSeparator))
    }

    /** 行内公式不能按源码字符宽度算（渲染出来通常比源码宽），必须给保守下限。 */
    @Test
    fun `含行内公式的单元格不会被估窄`() {
        val withFormula = estimate("| \$E = mc^2\$ | b |\n|---|---|\n| 1 | 2 |")
        val plainCell = estimate("| x | b |\n|---|---|\n| 1 | 2 |")
        assertTrue("含公式的单元格应有可观宽度，实际 $withFormula", withFormula > 0)
        assertTrue(
            "含公式不该比单字符还窄：$withFormula vs $plainCell",
            withFormula > plainCell,
        )
    }

    /** 强调标记 `**` 等不占显示宽度，应先剥掉。 */
    @Test
    fun `强调标记不占显示宽度`() {
        val bold = estimate("| **名称** | b |\n|---|---|\n| 1 | 2 |")
        val plain = estimate("| 名称 | b |\n|---|---|\n| 1 | 2 |")
        assertEquals("** 应被剥掉，两者宽度应一致", plain, bold)
    }

    /** 异常/边界输入不崩，且返回非负。 */
    @Test
    fun `异常输入不崩溃且返回非负`() {
        assertEquals(0, estimate(""))
        assertTrue(estimate("|---|---|") >= 0)
        assertTrue(estimate("| a |") >= 0)
        assertTrue(estimate("只有一行文字，没有竖线") >= 0)
        assertTrue(estimate("|||\n|-|-|\n|||") >= 0)
    }

    private companion object {
        /** 1080p 手机（density 3）上气泡正文的可用宽度，约 300dp。 */
        const val CONTAINER_PX = 900

        val NARROW_TABLE = """
            | 名称 | 数值 |
            |---|---|
            | 速度 | 5 |
            | 时间 | 9 |
        """.trimIndent()

        val WIDE_TABLE = """
            | 项目 | 说明 |
            |---|---|
            | ${"很长的说明内容".repeat(12)} | 备注 |
            | 速度 | 5 |
        """.trimIndent()
    }
}
