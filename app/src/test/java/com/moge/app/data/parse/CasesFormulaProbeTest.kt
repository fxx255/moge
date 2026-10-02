package com.moge.app.data.parse

import android.app.Application
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.scilab.forge.jlatexmath.TeXFormula
import ru.noties.jlatexmath.JLatexMathAndroid
import java.io.File

/**
 * 公式渲染能力探测（`cases` 分段函数专项）：把各种 `cases` 写法逐个喂给真实的
 * JLatexMath，把「通过 / 失败」写进 `build/cases-probe.txt`。
 *
 * 与 [LatexCapabilityProbeTest] 同一套思路——**目的不是断言，而是拿到一份能力边界清单**，
 * 用来区分两种截然不同的故障：
 * - 结构性缺陷：JLatexMath 本身不支持这种写法（清洗再怎么补也补不出来，必须改写环境）；
 * - 兜底覆盖不全：库里其实支持，只是我们的清洗/包裹没把模型输出的畸形写法修好。
 *
 * 本文件诞生的那轮问题（用户反馈「第 4 期最终结果里的公式没渲染，`\begin{cases}`
 * 及其后内容以源码原样露出」）实测结论是 **15/15 全部通过** —— 即 `cases` 本身
 * 完全受支持，包括截图里那种「首行以孤立 `\` 结尾（漏写行分隔符 `\\`）」的畸形形态。
 * 于是问题被排除在渲染能力之外，最终定位到「`$$` 定界符被多补一对、显示块被劈成两半」
 * （见 [CasesDelimiterTest] 的回归测试）。
 *
 * **保留此文件的价值**：将来若又有 `cases` 相关的渲染故障，先看这份清单就能立刻
 * 分清「是库不行」还是「是清洗没兜住」，不必再从零猜一遍。若某天升级 JLatexMath 后
 * 这里出现 FAIL，恰恰说明库的行为变了、清洗策略需要重新评估。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CasesFormulaProbeTest {

    @Before
    fun setUp() {
        JLatexMathAndroid.init(RuntimeEnvironment.getApplication() as Application)
    }

    @Test
    fun probeCases() {
        val oneLineCases = "S_{Y_c}(f) = S_{Y_s}(f) = \\begin{cases}" +
            " 4\\pi^2N_0\\big(f^2+f_c^2\\big), & |f|\\le \\dfrac B2\\ " +
            " 0, & |f|>\\dfrac B2 \\end{cases}"

        val cases = listOf(
            // 最小可用的 cases
            "\\begin{cases} a & b \\\\ c & d \\end{cases}" to "minimal-cases",
            "\\begin{cases} x, & x>0 \\\\ -x, & x\\le 0 \\end{cases}" to "realistic-cases",
            // 截图里的形状，逐项加变量定位元凶
            oneLineCases to "screenshot-full-raw",
            oneLineCases.replace("\\ ", " ") to "minus-trailing-backslash-space",
            "S_{Y_c}(f) = \\begin{cases} 4\\pi^2N_0(f^2+f_c^2), & |f|\\le \\dfrac B2 \\\\ 0, & |f|>\\dfrac B2 \\end{cases}"
                to "with-proper-rowbreak",
            "\\begin{cases} 4\\pi^2N_0\\big(f^2+f_c^2\\big), & |f|\\le \\dfrac B2 \\\\ 0, & |f|>\\dfrac B2 \\end{cases}"
                to "cases-only-proper",
            "\\begin{cases} 4\\pi^2N_0\\big(f^2+f_c^2\\big), & |f|\\le \\frac B2 \\\\ 0, & |f|>\\frac B2 \\end{cases}"
                to "bracless-frac-B2",
            "\\begin{cases} 4\\pi^2N_0\\big(f^2+f_c^2\\big), & |f|\\le \\frac{B}{2} \\\\ 0, & |f|>\\frac{B}{2} \\end{cases}"
                to "braced-frac",
            // \big 单独试
            "\\big(f^2+f_c^2\\big)" to "big-parens",
            "\\dfrac B2" to "dfrac-bracless",
            "\\dfrac{B}{2}" to "dfrac-braced",
            // cases + array 对照（array 是已知可用的替代写法）
            "\\left\\{\\begin{array}{ll} 4\\pi^2N_0(f^2+f_c^2), & |f|\\le \\frac{B}{2} \\\\ 0, & |f|>\\frac{B}{2} \\end{array}\\right."
                to "array-替代写法",
            // ★ 关键：截图里模型**漏掉了行分隔符 \\** 的形态（第一行以孤立 \ 结尾）
            "S_{Y_c}(f) = S_{Y_s}(f) = \\begin{cases} 4\\pi^2N_0\\big(f^2+f_c^2\\big), & |f|\\le \\dfrac B2\\ 0, & |f|>\\dfrac B2 \\end{cases}"
                to "MISSING-rowbreak-raw",
            // ★ 漏掉 \\ 且换成 \frac
            "\\begin{cases} 4\\pi^2N_0(f^2+f_c^2), & |f|\\le \\frac{B}{2} 0, & |f|>\\frac{B}{2} \\end{cases}"
                to "MISSING-rowbreak-braced",
            // ★ 截图里那种「&= 换行后 \begin{cases}」的真实换行形态
            "S_{Y_c}(f) = S_{Y_s}(f) =\n\\begin{cases}\n4\\pi^2N_0(f^2+f_c^2), & |f|\\le \\frac{B}{2} \\\\\n0, & |f|>\\frac{B}{2}\n\\end{cases}"
                to "newline-after-equals",
        )

        val report = StringBuilder()
        var ok = 0
        var bad = 0
        for ((latex, tag) in cases) {
            val result = runCatching { TeXFormula(latex) }
            val error = result.exceptionOrNull()
            if (error == null) {
                ok++
                report.append("OK   | [").append(tag).append("] ").append(latex).append('\n')
            } else {
                bad++
                val msg = error.message?.replace('\n', ' ')?.take(110).orEmpty()
                report.append("FAIL | [").append(tag).append("] ").append(latex)
                    .append("\n        <<< ").append(error.javaClass.simpleName).append(": ").append(msg)
                    .append('\n')
            }
        }
        val header = "cases 定向探测：总计 ${cases.size}，通过 $ok，失败 $bad\n\n"
        // 与 LatexCapabilityProbeTest 一致：输出到 build/ 下，路径由 Gradle 工作目录决定，
        // 不写死绝对路径（否则换机器/换 clone 位置就写不出去）。
        val out = File("build/cases-probe.txt")
        out.parentFile?.mkdirs()
        out.writeText(header + report)
    }
}
