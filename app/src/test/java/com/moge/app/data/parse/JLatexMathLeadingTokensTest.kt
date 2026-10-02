package com.moge.app.data.parse

import android.app.Application
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.scilab.forge.jlatexmath.TeXFormula
import ru.noties.jlatexmath.JLatexMathAndroid

/**
 * 探索性测试：真实 JLatexMath 对「拆分产生的段首形态」的容忍度。
 *
 * 平板截图里占位框的源码以 `=` 开头（=\begin{cases}...），
 * 推导长公式在 \\ 处拆分还会产生以 \\ 开头的段。
 * 这组测试回答：到底哪种段首会触发 ParseException。
 *
 * ⚠️ **运行环境要求：必须显式指定 JDK 21**（`JAVA_HOME=D:/tools/jdk-21.0.12+8`）。
 * JDK 25 下 `TeXFormula` 静态初始化失败，本文件会报
 * `NoClassDefFoundError: Could not initialize class ...TeXFormula`，
 * 与「段首容忍度」这个被测主题无关。根因由 [JLatexMathAvailabilityTest] 给出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class JLatexMathLeadingTokensTest {

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication() as Application
        JLatexMathAndroid.init(app)
    }

    private fun parseResult(latex: String): String = try {
        // 复刻设备渲染路径：与 Markwon 插件一致，用 JLatexMathDrawable.builder 构建
        ru.noties.jlatexmath.JLatexMathDrawable.builder(latex)
            .textSize(42f)
            .align(ru.noties.jlatexmath.JLatexMathDrawable.ALIGN_CENTER)
            .build()
        "OK"
    } catch (t: Throwable) {
        "FAIL(${t.javaClass.simpleName}: ${t.message})"
    }

    @Test
    fun endToEndPipelineRendersScreenshotReply() {
        // 与截图 1 结构一致的模型原文：单行 $$…$$ 包裹的 cases
        val reply = """
            ${'$'}${'$'}
            x(n) = u(n) - u(n-N)
            ${'$'}${'$'}
            的离散卷积。这里 ${'$'}u(n)${'$'} 是单位阶跃序列，${'$'}N${'$'} 为正整数。

            ## 结论
            设 ${'$'}y(n) = h(n) * x(n)${'$'}，则分三种情况：

            当 ${'$'}a \ne 1${'$'} 时：

            ${'$'}${'$'}y(n) = \begin{cases} 0, & n<0 \\ \dfrac{1-a^{n+1}}{1-a}, & 0\le n<N \\ \dfrac{a^{n-N+1}(1-a^N)}{1-a}, & n\ge N \end{cases}${'$'}${'$'}

            当 ${'$'}a = 1${'$'} 时：

            ${'$'}${'$'}y(n) = \begin{cases} 0, & n<0 \\ n+1, & 0\\le n<N \\ N, & n\\ge N \end{cases}${'$'}${'$'}
        """.trimIndent()

        // 手机气泡宽度量级（~370dp * density2.75 - 留白）
        val maxWidthPx = 800
        val measure: (String) -> Int = { latex ->
            runCatching {
                ru.noties.jlatexmath.JLatexMathDrawable.builder(latex).textSize(45f).build().intrinsicWidth
            }.getOrDefault((latex.length * 45f * 0.62f).toInt())
        }
        val rendered = com.moge.app.data.parse.wrapLongFormulas(
            com.moge.app.data.parse.sanitizeReplyLatex(
                com.moge.app.data.parse.normalizeReplyMarkdown(reply),
            ),
            maxWidthPx,
            measure,
        )
        println("\n==== pipeline output ====\n$rendered\n==== end ====")

        // 抽出所有 $$…$$ 块/行内公式，逐个用 builder 渲染验证
        val spans = Regex("\\${'$'}\\${'$'}([\\s\\S]+?)\\${'$'}\\${'$'}").findAll(rendered).toList()
        println("found ${spans.size} formula spans")
        spans.forEachIndexed { index, match ->
            val latex = match.groupValues[1].trim()
            println("span#$index: ${parseResult(latex)}  ${latex.take(60)}")
        }
    }

    @Test
    fun reportLeadingTokenTolerance() {
        val cases = linkedMapOf(
            "leading =" to "= \\begin{cases} 0, & n<0 \\\\ n+1 \\end{cases}",
            "trailing =" to "y(n) =",
            "leading equals expr" to "= \\dfrac{1-a^{n+1}}{1-a}",
            "leading backslash-backslash" to "\\\\ x^2",
            "bare cases" to "\\begin{cases} 0, & n<0 \\\\ n+1, & 0\\le n<N \\end{cases}",
            "eq then cases" to "y(n) = \\begin{cases} 0, & n<0 \\\\ n+1 \\end{cases}",
            "screenshot piece1" to "= \\begin{cases} 0, & n<0 \\\\ \\dfrac{1-a^{n+1}}{1-a}, & 0\\le n<N \\end{cases}",
            "screenshot piece2" to "= \\begin{cases} 0, & n<0 \\\\ n+1, & 0\\le n<N \\end{cases}",
            "trailing backslash-backslash" to "f(x)=1 \\\\",
            "mid backslash-backslash" to "f(x)=1 \\\\ g(x)=2",
            "full 3-row cases" to "y(n) = \\begin{cases} 0, & n<0 \\\\ \\dfrac{1-a^{n+1}}{1-a}, & 0\\le n<N \\\\ \\dfrac{a^{n-N+1}(1-a^N)}{1-a}, & n\\ge N \\end{cases}",
            "operatorname" to "\\operatorname{Cov}(\\xi_1, \\xi_2)",
            "operatorname-star" to "\\operatorname*{Cov}(\\xi_1, \\xi_2)",
            "mathrm" to "\\mathrm{Cov}(\\xi_1, \\xi_2)",
            "dfrac" to "\\dfrac{N_0}{2}",
            "frac" to "\\frac{N_0}{2}",
            "operatorname-cov-squares" to "\\operatorname{Cov}\\{\\xi_1^2, \\xi_2^2\\}",
            "text-command" to "\\text{当} x>0",
            "rm-command" to "\\rm Cov(x)",
            // 逐字符还原截图 1 的两个失败 piece（前导 = + 完整 3 行 cases）
            "shot1 piece a-ne-1" to "= \\begin{cases} 0, & n<0 \\\\ \\dfrac{1-a^{n+1}}{1-a}, & 0\\le n<N \\\\ \\dfrac{a^{n-N+1}(1-a^N)}{1-a}, & n\\ge N \\end{cases}",
            "shot1 piece a-eq-1" to "= \\begin{cases} 0, & n<0 \\\\ n+1, & 0\\le n<N \\\\ N, & n\\ge N \\end{cases}",
        )
        val report = cases.map { (name, latex) -> "%-28s %s".format(name, parseResult(latex)) }
        println("\n==== JLatexMath leading token tolerance ====\n" + report.joinToString("\n"))
        // 这些形态在排查截图问题期间全部验证过可解析，转成硬断言防回归
        cases.forEach { (name, latex) ->
            val result = parseResult(latex)
            org.junit.Assert.assertEquals("$name 应可解析", "OK", result)
        }
    }
}
