package com.moge.app.data.parse

import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * 真实回答里的公式回归：公式取自 2026-09-20 10:57 那条会话的助手原回答
 * （完整正文仅导出到 build/assistant-review/，不入库、不提交私人对话）。
 *
 * 覆盖两点：
 * - 该回答的关键公式在常见宽度下，走完 normalize → sanitize → 分行之后，
 *   **每个公式块都能被真实 JLatexMath 解析**；
 * - 中文混排（加粗正文 + 行内公式 + 块级公式）不会制造坏片段。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RealAnswerFormulaRegressionTest {

    @Before fun init() { JLatexMathAndroid.init(RuntimeEnvironment.getApplication()) }

    private companion object { const val DD = "\$\$" }

    /** 与生产 formulaWidthMeasurer 一致：测量失败按字符估算回退，不能让测量异常中断管线。 */
    private fun measure(latex: String): Int = runCatching {
        JLatexMathDrawable.builder(latex).textSize(36f).build().intrinsicWidth
    }.getOrDefault((latex.length * 36f * 0.62f).toInt())

    private fun assertRenderable(markdown: String, label: String, widths: List<Int>) {
        for (width in widths) {
            val prepared = wrapLongFormulas(
                sanitizeReplyLatex(normalizeReplyMarkdown(markdown)),
                width,
                ::measure,
            )
            val pieces = Regex("""\$\$([\s\S]*?)\$\$""").findAll(prepared).toList()
            assertTrue("「$label」@${width}px 没产生公式块:\n$prepared", pieces.isNotEmpty())
            pieces.forEach { piece ->
                val latex = piece.groupValues[1].trim()
                val error = runCatching {
                    JLatexMathDrawable.builder(latex).textSize(36f).build()
                }.exceptionOrNull()
                if (error != null) {
                    throw AssertionError(
                        "「$label」@${width}px 失败:\n$latex\n" +
                            "错误: ${error.javaClass.simpleName}: ${error.message}\n" +
                            "全部片段: ${pieces.joinToString(" ‖ ") { it.groupValues[1].trim() }}",
                    )
                }
            }
        }
    }

    /** 该回答里的关键公式（含截图对应的那条长 \Rightarrow 链）。 */
    @Test fun formulasFromReportedAnswerStayRenderable() {
        val formulas = listOf(
            """e^{\frac{4}{n}} = \frac{1}{4}\left(\frac{1}{n} + \frac{1}{4}\right)e^{-\frac{4}{n}},\quad n=?""",
            """e^{\frac{8}{n}} = \frac{1}{4}\left(\frac{1}{n}+\frac{1}{4}\right) = \frac{n+4}{16n}\quad(n\neq 0)""",
            """f(t)=16e^{8t}-4t-1=0""",
            """e^{8t}=\frac{1}{32}\ \Rightarrow\ t^*=-\frac{\ln 32}{8}\approx-0.433""",
            """1=\frac{1}{4}\left(\frac{1}{n}+\frac{1}{4}\right)\ \Rightarrow\ \frac{1}{n}+\frac{1}{4}=4\ """ +
                """\Rightarrow\ \frac{1}{n}=\frac{15}{4}\ \Rightarrow\ n=\frac{4}{15}\approx0.267""",
        )
        formulas.forEachIndexed { index, formula ->
            assertRenderable("$DD\n$formula\n$DD", "公式#${index + 1}", listOf(320, 480, 720, 1080))
        }
    }

    /** 窄屏下长链会被拆开：拆开后每段仍要各自成立。 */
    @Test fun longImplicationChainSurvivesNarrowWidth() {
        val chain = """1=\frac{1}{4}\left(\frac{1}{n}+\frac{1}{4}\right)\ \Rightarrow\ """ +
            """\frac{1}{n}+\frac{1}{4}=4\ \Rightarrow\ \frac{1}{n}=\frac{15}{4}\ \Rightarrow\ n=\frac{4}{15}\approx0.267"""
        assertRenderable("$DD\n$chain\n$DD", """长 \Rightarrow 链""", listOf(160, 240, 320, 420))
    }

    /** 中文混排：加粗正文 + 行内公式 + 块级公式。 */
    @Test fun mixedProseAndMathStaysRenderable() {
        val inline = "分析 \$f(t)\$ 的最小值，求导 \$f'(t)=128e^{8t}-4\$："
        val subscriptLine = listOf(
            "代入 ",
            DD,
            """f(t^*)=16\cdot\frac{1}{32}-4t^*-1=\frac{1}{2}+\frac{\ln 32}{2}-1=""",
            """\frac{\ln 32}{2}-\frac{1}{2}\approx 1.233>0""",
            DD,
            " 成立。",
        ).joinToString("")
        val paragraph = buildString {
            appendLine(inline)
            appendLine()
            append(DD).append('\n')
            append("""e^{8t}=\frac{1}{32}\ \Rightarrow\ t^*=-\frac{\ln 32}{8}\approx-0.433""")
            append('\n').append(DD)
            appendLine()
            appendLine()
            appendLine(subscriptLine)
        }
        assertRenderable(paragraph, "中文混排段落", listOf(240, 360, 720))
    }
}
