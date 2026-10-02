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
 * 公式渲染能力探测：把常见 LaTeX 构造逐个喂给真实的 JLatexMath，
 * 把「通过 / 失败」写进 build/latex-probe.txt。
 *
 * 目的不是断言，而是拿到一份**能力边界清单**，用来区分：
 * - 结构性缺陷：JLatexMath 本身不支持（清洗再怎么补也补不出来）；
 * - 兜底覆盖不全：库里其实支持，只是我们的清洗/包裹没把模型输出的畸形写法修好。
 *
 * ⚠️ **运行环境要求：必须显式指定 JDK 21**（`JAVA_HOME=D:/tools/jdk-21.0.12+8`）。
 * 用 JDK 25 跑时，`TeXFormula` 的静态初始化会失败，本文件与
 * [JLatexMathArraySupportTest]、[JLatexMathLeadingTokensTest] 会**集体**报
 * `NoClassDefFoundError: Could not initialize class ...TeXFormula`（109/109 全 FAIL），
 * 看上去像渲染全线崩了，实际与生产代码毫无关系。
 * 此时 [JLatexMathAvailabilityTest] 会给出真正的根因；单独跑这几个类也会恢复正常。
 * 详见 build.gradle.kts 里的 JDK 约束说明。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LatexCapabilityProbeTest {

    @Before
    fun setUp() {
        JLatexMathAndroid.init(RuntimeEnvironment.getApplication() as Application)
    }

    @Test
    fun probeCapabilities() {
        val cases = listOf(
            // 基础运算
            "\\frac{a}{b}", "\\dfrac{a}{b}", "\\tfrac{a}{b}", "\\sqrt{x}", "\\sqrt[3]{x}",
            "\\sum_{i=1}^{n} i", "\\prod_{i=1}^{n} i", "\\int_0^1 x\\,dx",
            "\\lim_{x \\to 0} f(x)", "\\binom{n}{k}",
            // 字母表与字体
            "\\alpha + \\beta", "\\Gamma(z)", "\\mathbb{R}", "\\mathcal{L}",
            "\\mathrm{d}x", "\\mathbf{v}", "\\mathit{abc}", "\\mathfrak{g}", "\\hbar", "\\ell",
            "\\partial", "\\nabla", "\\infty",
            // 环境
            "\\begin{array}{cc} a & b \\\\ c & d \\end{array}",
            "\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}",
            "\\begin{align} a &= b \\\\ c &= d \\end{align}",
            "\\begin{equation} a = b \\end{equation}",
            "\\begin{gather} a = b \\\\ c = d \\end{gather}",
            "\\begin{split} a &= b \\end{split}",
            "\\begin{multline} a = b \\end{multline}",
            "\\begin{eqnarray} a &=& b \\end{eqnarray}",
            "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}",
            "\\begin{cases} a & b \\\\ c & d \\end{cases}",
            "\\begin{smallmatrix} a & b \\end{smallmatrix}",
            "\\begin{matrix} a & b \\end{matrix}",
            "\\begin{cases} x, & x > 0 \\\\ -x, & x \\le 0 \\end{cases}",
            // 定界符与装饰
            "\\left\\{ x \\right\\}", "\\left( \\frac{a}{b} \\right)",
            "\\overbrace{a+b}", "\\underbrace{a+b}",
            "\\overrightarrow{AB}", "\\bar{x}", "\\hat{x}", "\\vec{v}", "\\dot{x}", "\\ddot{x}",
            "\\overline{AB}", "\\underline{x}", "\\widehat{abc}", "\\widetilde{abc}",
            // 符号与间距
            "\\forall x \\exists y", "\\because a \\therefore b", "\\implies", "\\iff",
            "\\pmod{n}", "\\bmod", "\\displaystyle \\sum_i", "\\limits",
            "\\quad a \\qquad b", "\\,a\\;b\\!c",
            "\\cdots", "\\ldots", "\\ddots", "\\vdots",
            "\\gcd(a,b)", "\\max\\{a,b\\}", "\\deg p", "\\Re z", "\\Im z",
            // 中文与文本
            "\\text{abc}", "\\text{当 } x > 0", "\\text{持续}", "\\mbox{中文}", "\\text{匀速直线运动}",
            // 需要额外宏包 / amsmath 专有
            "\\tag{1}", "\\tag*{(1)}", "\\boxed{E=mc^2}", "\\cancel{x}",
            "\\textcolor{red}{x}", "\\color{red} x",
            "\\operatorname{sgn}", "\\substack{a \\\\ b}", "\\xrightarrow{f}",
            "\\intertext{说明}", "\\sideset{}{'} \\sum", "\\dcases{a & b}",
            "\\phantom{x}", "\\vphantom{x}", "\\smash{x}",
            "\\rule{1cm}{1pt}", "\\raisebox{1pt}{x}", "\\hspace{1cm}", "\\vspace{1cm}",
            // 直接输入的 Unicode 数学符号（输入法/OCR 常见）
            "a ≤ b", "α + β", "x → 0", "∑_{i}", "a × b", "a ≠ b", "√x", "∫_0^1",
            // 公式内容里带换行（JSON 的 \n 被正确还原后就是这个形态，
            // 用来确认「换行不再被误判成 LaTeX 之后」渲染器依然吃得下）
            "\nf(x)",
            "f(x)\n",
            "f(x) \\le \\frac{1}{2}\n= \\frac{1}{2}Muv",
            "\n\nf(x)\n\n",
            // 结构性畸形
            "\\begin{array}{cc} a & b",
            "a & b",
            "x = \\frac{1}{2} \\\\ y = \\frac{1}{3}",
            "\\begin{aligned} a &= b \\end{aligned} + c",
        )

        val report = StringBuilder()
        var ok = 0
        var bad = 0
        for (latex in cases) {
            val result = runCatching { TeXFormula(latex) }
            val error = result.exceptionOrNull()
            if (error == null) {
                ok++
                report.append("OK   | ").append(latex).append('\n')
            } else {
                bad++
                val msg = error.message?.replace('\n', ' ')?.take(90).orEmpty()
                report.append("FAIL | ").append(latex)
                    .append("  <<< ").append(error.javaClass.simpleName).append(": ").append(msg)
                    .append('\n')
            }
        }
        val header = "总计 ${cases.size}，通过 $ok，失败 $bad\n\n"
        File("build/reports/latex-probe.txt").apply {
            parentFile.mkdirs()
            writeText(header + report)
        }
    }
}
