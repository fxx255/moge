package com.moge.app.data.parse

import android.app.Application
import com.moge.app.data.parse.sanitizeReplyLatex
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.scilab.forge.jlatexmath.ParseException
import org.scilab.forge.jlatexmath.TeXFormula
import ru.noties.jlatexmath.JLatexMathAndroid

/**
 * 用真实的 JLatexMath（Markwon ext-latex 传递依赖的 ru.noties Android 版）验证
 * LaTeX 清洗/拆分管线产出的公式确实能被解析。
 *
 * 背景：Android 版 JLatexMath 把设置/字体资源放在 assets（org/scilab/forge/jlatexmath/），
 * 静态初始化硬性要求先调 JLatexMathAndroid.init(context)，否则 TeXFormula 类初始化失败，
 * 报 "Please call `#init(Context)` method to initialize jLatexMath"。
 * 生产环境由 [com.moge.app.MogeApplication.onCreate] 负责 init；
 * 测试环境由 Robolectric 提供 Context 与合并后的 assets。
 *
 * 这组测试回答两个设计问题：
 * 1. aligned→array 转换策略是否成立（array 环境真实可解析）；
 * 2. 「整块保留」兜底路径在解析失败时确实会走占位符（而不是侥幸渲染）。
 *
 * ⚠️ **运行环境要求：必须显式指定 JDK 21**（`JAVA_HOME=D:/tools/jdk-21.0.12+8`）。
 * JDK 25 下会报 `NoClassDefFoundError: Could not initialize class ...TeXFormula`，
 * 本类 7 项全红但根因不在被测逻辑里。根因由 [JLatexMathAvailabilityTest] 给出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // 避免启动 LiXingApplication 的后台协程污染其他测试
class JLatexMathArraySupportTest {

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication() as Application
        JLatexMathAndroid.init(app)
    }

    private fun assertParses(latex: String) {
        try {
            TeXFormula(latex)
        } catch (e: ParseException) {
            fail("应可解析却 ParseException: $latex\n${e.message}")
        } catch (e: Throwable) {
            fail("应可解析却抛出 ${e.javaClass.simpleName}: $latex\n${e.message}")
        }
    }

    private fun assertNotParses(latex: String) {
        try {
            TeXFormula(latex)
        } catch (t: Throwable) {
            return
        }
        fail("预期解析失败却成功了: $latex")
    }

    @Test
    fun basicArrayEnvironmentIsSupported() {
        assertParses("\\begin{array}{cc} a & b \\\\ c & d \\end{array}")
    }

    @Test
    fun sanitizedArrayFromAlignedIsSupported() {
        // 截图 122 里失败的公式：aligned 转 array 后、按行解包前的整块形态
        assertParses(
            "\\begin{array}{cc} \\exp(u) = 1+u+\\frac{u^2}{2} \\\\ = 1-\\frac{t}{2}+O(t^4) \\end{array}",
        )
    }

    @Test
    fun unwrappedSingleRowsAreSupported() {
        // splitFormula 按行解包后（无环境包裹）的每一行都必须独立可解析
        assertParses("\\exp(u) = 1+u+\\frac{u^2}{2}")
        assertParses("= 1-\\frac{t}{2}+O(t^4)")
    }

    @Test
    fun matrixAndCasesEnvironmentsAreSupported() {
        // FormulaWrapping 对这两类环境整块保留，前提是 JLatexMath 本身支持
        assertParses("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}")
        assertParses("\\begin{cases} x & y \\\\ z & w \\end{cases}")
    }

    @Test
    fun sanitizePipelineOutputParsesEndToEnd() {
        // 端到端：模型输出的 aligned 原文（转换器按行扫描，\begin{aligned} 需在行首附近）
        // → sanitizeReplyLatex → 真实解析器验收
        val markdown = """
            $$
            \begin{aligned}
            \exp(u) &= 1+u+\frac{u^2}{2} \\
            &= 1-\frac{t}{2}+O(t^4)
            \end{aligned}
            $$
        """.trimIndent()
        val sanitized = sanitizeReplyLatex(markdown)
        val arrayMatch = Regex("""\\begin\{array\}.*\\end\{array\}""", RegexOption.DOT_MATCHES_ALL)
            .find(sanitized)
        assertTrue("aligned 应被转换为 array，实际: $sanitized", arrayMatch != null)
        assertParses(arrayMatch!!.value)
    }

    @Test
    fun equationEnvironmentIsUnwrappedSoItRenders() {
        // equation 是 JLatexMath 唯一不认识的常见环境（实测 "Unknown environment: equation"，
        // 而 align/gather/multline/eqnarray/split 都能渲染）。
        // 清洗后必须剥掉标签并补上 $$ 才能渲染——否则每次都会失败，
        // 表现为「同一处公式怎么改都渲染不出来」。
        val markdown = "\\begin{equation}\nE = mc^2\n\\end{equation}"
        val sanitized = sanitizeReplyLatex(markdown)
        assertFalse("equation 标签应被剥掉，实际: $sanitized", sanitized.contains("\\begin{equation}"))
        val math = Regex("""\$\$([\s\S]*?)\$\$""").find(sanitized)
        assertTrue("应被 $$ 包裹成公式块，实际: $sanitized", math != null)
        assertParses(math!!.groupValues[1].trim())
    }

    @Test
    fun missingPackageCommandsAreDowngradedSoTheyRender() {
        // cancel / xcolor 等命令在 JLatexMath 里不存在，降级后必须可解析
        assertParses(sanitizeReplyLatex("\\cancel{x}").trim())
        assertParses(sanitizeReplyLatex("\\color{red} x").trim())
    }

    @Test
    fun unclosedEnvironmentFailsSoPlaceholderPathEngages() {
        // JLatexMath 相当宽容（不配对的括号、& 都能容忍，测试已验证），
        // 但未闭合的环境仍会失败 → 真机上这类输入走占位符兜底。
        // 说明清洗/拆分管线必须保证产物结构完整（begin/end 成对）。
        assertNotParses("\\begin{array}{cc} a & b")
    }
}
