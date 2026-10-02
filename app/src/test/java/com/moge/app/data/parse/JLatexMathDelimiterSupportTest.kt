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
 * 能力探测：左右成对定界符命令（`\lvert`/`\rvert`/`\lVert`/`\rVert`/`\lbrace` 等）
 * 在 JLatexMath 里到底能不能解析。
 *
 * 背景：用户截图里 `\lvert y_L(T)\rvert` 报 ParseException，整条公式退化成
 * 「⚠ 公式无法渲染」占位框。这与之前 `\cancel`/`\color`/`\frac B2` 是同一类问题——
 * **模型用标准 LaTeX 写法、但 JLatexMath 不认识**，必须靠 [sanitizeReplyLatex]
 * 按语义降级，否则用户看到的是一条渲染失败的灰框。
 *
 * 这组测试先「探测真相」再断言降级结果，避免照着猜测去改清洗管线。
 *
 * ⚠️ **必须用 JDK 21 运行**（`JAVA_HOME=D:/tools/jdk-21.0.12+8`）。JDK 25 下
 * `TeXFormula` 静态初始化失败，本类会整片假红，根因见 [JLatexMathAvailabilityTest]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class JLatexMathDelimiterSupportTest {

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication() as Application
        JLatexMathAndroid.init(app)
    }

    private fun parses(latex: String): Boolean = try {
        TeXFormula(latex)
        true
    } catch (e: ParseException) {
        false
    } catch (e: Throwable) {
        false
    }

    private fun assertParses(latex: String, why: String = "") {
        if (!parses(latex)) fail("应可解析却失败: $latex ${if (why.isEmpty()) "" else "($why)"}")
    }

    /**
     * 探测表：把「模型常用的成对定界符写法」逐个喂给真实解析器，把结论钉在测试里。
     * 失败的那些必须在 sanitize 里被降级，成功的那些则**不许**被误改（见下一个测试）。
     */
    @Test
    fun probeWhichDelimiterCommandsAreSupported() {
        val samples = listOf(
            "\\lvert y \\rvert",
            "\\lVert y \\rVert",
            "\\lvert x \\rvert^2",
            "|y|",
            "\\left| y \\right|",
            "\\left\\lvert y \\right\\rvert",
            "\\|y\\|",
            "\\left\\| y \\right\\|",
            "\\lbrace x \\rbrace",
            "\\langle x \\rangle",
            "\\lceil x \\rceil",
            "\\lfloor x \\rfloor",
        )
        val report = samples.joinToString("\n") { s ->
            val ok = parses(s)
            "  ${if (ok) "OK  " else "FAIL"}  $s"
        }
        println("=== JLatexMath 成对定界符探测 ===\n$report")
        // 探测本身不断言某个具体结果（结果随依赖版本变化），但**必须**保证
        // 「竖线语义」的各种写法至少有一种可用，否则清洗管线无从降级。
        assertTrue(
            "至少应有可用的竖线写法（|y| 或 \\left| \\right|）\n$report",
            parses("|y|") || parses("\\left| y \\right|"),
        )
    }

    /** 用户截图里的真实失败样本：降级后必须能解析。 */
    @Test
    fun screenshotFormulaIsDowngradedAndRenders() {
        val raw = "\\lvert y_L(T)\\rvert"
        val fixed = sanitizeReplyLatex(raw).trim()
        assertParses(fixed, "截图失败样本降级后")
        assertFalse("降级后不应再残留 \\lvert", fixed.contains("\\lvert"))
        assertFalse("降级后不应再残留 \\rvert", fixed.contains("\\rvert"))
        assertTrue("应降级成竖线", fixed.contains("|"))
    }

    /** 双竖线（范数）语义应降级为 `\|`，不能变成单竖线（那是绝对值/模，含义不同）。 */
    @Test
    fun doubleBarIsDowngradedToNormNotAbsolute() {
        val fixed = sanitizeReplyLatex("\\lVert x \\rVert").trim()
        assertParses(fixed, "\\lVert 降级后")
        assertFalse("不应残留 \\lVert", fixed.contains("\\lVert"))
        assertFalse("不应残留 \\rVert", fixed.contains("\\rVert"))
        assertTrue("范数应保留双竖线语义，实际: $fixed", fixed.contains("\\|"))
    }

    /** 大定界符（`\left\lvert … \right\rvert`）要连 `\left`/`\right` 一起正确处理。 */
    @Test
    fun leftRightWithDelimiterCommandsIsHandled() {
        val fixed = sanitizeReplyLatex("\\left\\lvert y \\right\\rvert").trim()
        assertParses(fixed, "\\left\\lvert…\\right\\rvert 降级后")
        assertFalse("不应残留 \\lvert", fixed.contains("\\lvert"))
        assertFalse("不应残留 \\rvert", fixed.contains("\\rvert"))
    }

    /** 原本就合法的写法不许被改动（降级要幂等、且不误伤）。 */
    @Test
    fun alreadyValidFormsAreUntouched() {
        for (s in listOf("|y|", "\\left| y \\right|", "\\|x\\|", "\\left\\| x \\right\\|")) {
            val fixed = sanitizeReplyLatex(s).trim()
            assertParses(fixed, "本就合法的写法: $s")
        }
        // 单竖线写法不该被塞进多余的反斜杠
        assertFalse(
            "原本的 |y| 不应被改写",
            sanitizeReplyLatex("|y|").trim().contains("\\|"),
        )
    }

    /** 竖线类命令出现在 matrix/array 单元里也要能降级（否则整块环境渲染失败）。 */
    @Test
    fun delimiterInsideEnvironmentIsDowngraded() {
        val markdown = """
            $$
            \begin{array}{cc}
            \lvert a \rvert & \lVert b \rVert \\
            c & d
            \end{array}
            $$
        """.trimIndent()
        val sanitized = sanitizeReplyLatex(markdown)
        val block = Regex("""\\begin\{array\}.*\\end\{array\}""", RegexOption.DOT_MATCHES_ALL)
            .find(sanitized)
        assertTrue("array 块应保留", block != null)
        assertParses(block!!.value, "环境内的定界符降级后")
    }

    /** `\mid`（集合分隔）与竖线语义相近但含义不同，不应被竖线替换规则破坏。 */
    @Test
    fun midIsNotBrokenByBarRules() {
        assertParses(sanitizeReplyLatex("\\{x \\mid x > 0\\}").trim(), "\\mid 写法")
    }
}
