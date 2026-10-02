package com.moge.app.data.parse

import com.moge.app.data.parse.sanitizeReplyLatex
import com.moge.app.data.parse.splitFormula
import com.moge.app.data.parse.wrapLongFormulas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormulaWrappingTest {
    private val d = "$$"
    private val measure: (String) -> Int = { it.length * 10 }

    @Test
    fun `damaged inline formula cannot capture later valid math and prose`() {
        val input = "结论：双纽线 $$\\rho^2=\\cos22θ 所围成的图形面积为\n\n${d}S=1.$$"
        val result = wrapLongFormulas(input, 1000, measure)
        assertTrue(result, result.contains("$$\\rho^2=\\cos22θ$$ 所围成的图形面积为"))
        assertTrue(result, result.endsWith("${d}S=1.$$"))
    }

    @Test
    fun `multiline math may open after prose and close before more inline formulas`() {
        val input = "**结论先说**：$$\n\\rho^2=\\cos 2\\theta\n" +
            "$$ 的两瓣占据 $$\\left[-\\tfrac{\\pi}{4},\\tfrac{\\pi}{4}\\right]$$（右瓣）。"
        val result = wrapLongFormulas(input, 1000, measure)
        assertTrue(result, result.contains("**结论先说**：\n$$\n\\rho^2=\\cos 2\\theta\n$$"))
        assertTrue(result, result.contains("的两瓣占据 $$\\left[-\\tfrac{\\pi}{4},\\tfrac{\\pi}{4}\\right]$$（右瓣）。"))
    }

    @Test
    fun `closing display delimiter before prose does not swallow following paragraphs`() {
        val result = wrapLongFormulas("$$\nx=1\n$$ 所以成立。\n\n下一段 ${d}y=2$$。", 1000, measure)
        assertEquals("$$\nx=1\n$$\n 所以成立。\n\n下一段 ${d}y=2$$。", result)
    }

    @Test
    fun `sized absolute and invisible delimiters stay paired when wrapping`() {
        listOf(
            "P=\\left|a+b+c+d\\right|+z",
            "P=\\left.f(x)+g(x)\\right|_{x=0}+z",
            "P=\\left\\langle a+b+c\\right\\rangle+z",
        ).forEach { latex ->
            splitFormula(latex, 80, measure).forEach { piece ->
                assertEquals(piece, piece.split("\\left").size, piece.split("\\right").size)
            }
        }
    }

    @Test
    fun `single line cases inside multiline display does not gain delimiters`() {
        val markdown = "$$\nP_{y_c}(f)=P_{y_s}(f)=\n" +
            "\\begin{cases}4\\pi^2N_0\\left(f_c^2+f^2\\right)&|f|<B/2\\\\0&|f|>B/2\\end{cases}\n$$"
        assertEquals(markdown, sanitizeReplyLatex(markdown))
    }

    @Test
    fun `short formulas stay untouched`() {
        val markdown = "已知 ${d}x^2=1${d}，求值。"
        assertEquals(markdown, wrapLongFormulas(markdown, 100, measure))
    }

    @Test
    fun `long display formula is split into renderable blocks`() {
        val latex = "f(x)=2^x, g(x)=x+1, h(x)=x^2+2x+1"
        val blocks = splitFormula(latex, 120, measure)
        assertTrue(blocks.size >= 2)
        assertTrue(blocks.all { measure(it) <= 120 })
    }

    @Test
    fun `commas inside braces never split`() {
        val latex = "f(x_{1,2})=x_{1,2}+x_{3,4}+x_{5,6}+x_{7,8}+x_{9,10}"
        val blocks = splitFormula(latex, 200, measure)
        assertTrue(blocks.all { measure(it) <= 200 })
        assertTrue(blocks.none { it.endsWith(",") || it.startsWith(",") })
    }

    @Test
    fun `long inline formula becomes display blocks`() {
        val line = "值 ${d}a_1+a_2+a_3+a_4+a_5+a_6+a_7+a_8+a_9+a_{10}${d} 结束。"
        val lines = wrapLongFormulas(line, 120, measure).split("\n")
        assertEquals("值", lines.first())
        assertEquals("结束。", lines.last())
        assertTrue(lines.count { it == d } >= 2)
    }

    @Test
    fun `display block long formula wraps`() {
        val markdown = "$$\nf(x)=2^x, g(x)=x+1\n$$"
        val wrapped = wrapLongFormulas(markdown, 120, measure)
        assertTrue(wrapped.split("\n").count { it == d } >= 4)
    }

    @Test
    fun `aligned rows with variable ampersand counts are padded to uniform columns`() {
        // 第一行用 & 对齐，第二行没有 &；以前会生成 array{cc} 但只有 1 列的坏 array，JLatexMath 解析失败。
        val markdown = """$$
            \begin{aligned}
            & e^{-x} = \left(x^2 - \frac{x^4}{3}\right) \left(1 - x + \frac{x^2}{2}\right) + O(x^5) \\
            & = x^2 - x^3 + \frac{1}{6}x^4 + O(x^5)
            \end{aligned}
            $$"""
        val out = sanitizeReplyLatex(markdown)
        assertTrue("aligned 应被改写为 array：$out", out.contains("\\begin{array}"))
        // 转换后每行都应该有相同数量的 &：这里每行至少 1 个
        val arrayBody = out.substringAfter("\\begin{array}{cc}").substringBefore("\\end{array}")
        val lines = arrayBody.split(" \\\\ ")
        assertTrue("array 应至少有两行：$arrayBody", lines.size >= 2)
        val ampCounts = lines.map { it.count { c -> c == '&' } }.toSet()
        assertEquals("array 各行 & 数必须一致，实际：$ampCounts", 1, ampCounts.size)
    }

    @Test
    fun `aligned with unbalanced braces is kept untouched`() {
        // 大括号不配对就别冒险转 array
        val markdown = """$$
            \begin{aligned}
            & a = \frac{1}{2 \\
            & b = c
            \end{aligned}
            $$"""
        val out = sanitizeReplyLatex(markdown)
        assertFalse("不应该转 array：$out", out.contains("\\begin{array}"))
        assertTrue("应保留 aligned：$out", out.contains("\\begin{aligned}"))
    }

    @Test
    fun `aligned with unmatched left right is kept untouched`() {
        val markdown = """$$
            \begin{aligned}
            & a = \left( \frac{1}{2} \\
            & b = c
            \end{aligned}
            $$"""
        val out = sanitizeReplyLatex(markdown)
        assertFalse(out.contains("\\begin{array}"))
    }

    @Test
    fun `over-wide array environment is unwrapped into standalone rows`() {
        // 真机截图的坏例：array 整体放不下时，以前会在环境内部的 \\ 处硬切，
        // 产生带无配对 \begin{array} / \end{array} 的孤儿片段（「⚠ 公式无法渲染」占位框）。
        val latex = "\\begin{array}{cc} \\exp(u) & = 1+u+\\frac{u^2}{2} \\\\ = 1-\\frac{t}{2}+O(t^4) & \\end{array}"
        val pieces = splitFormula(latex, 400, measure)
        assertEquals(
            listOf("\\exp(u) = 1+u+\\frac{u^2}{2}", "= 1-\\frac{t}{2}+O(t^4)"),
            pieces,
        )
    }

    @Test
    fun `splitting never orphans an environment marker`() {
        // 任何拆分结果都不允许出现无配对的环境标记
        val latex = "\\begin{array}{cc} a & = b+c+d+e+f \\\\ = g+h+i+j+k+l & \\end{array}"
        val pieces = splitFormula(latex, 100, measure)
        assertTrue("应拆出多段：$pieces", pieces.size >= 2)
        pieces.forEach { piece ->
            assertEquals("环境标记必须成对出现在同一段里：$piece", piece.count { it == '{' }, piece.count { it == '}' })
        }
        assertTrue(pieces.none { it.trim().endsWith("\\begin{array}{cc}") })
        assertTrue(pieces.none { it.trim().startsWith("\\end{array}") })
    }

    @Test
    fun `matrix environments are never shredded`() {
        // pmatrix/cases 是真正的矩阵/表格：& 是分隔符，宁可整块不拆也不能切开环境
        val matrix = "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}"
        assertEquals(listOf(matrix), splitFormula(matrix, 10, measure))
        val cases = "\\begin{cases} a & x>0 \\\\ b & x<0 \\end{cases}"
        assertEquals(listOf(cases), splitFormula(cases, 10, measure))
    }

    @Test
    fun `aligned with brackets pairing across rows stays whole`() {
        // 真机截图的第三种坏例：\left[ 在一行打开、\right] 在下一行闭合。
        // 按行解包会留下带孤立 \left[ 和残留 \\ 的坏行，此时必须整块保留（原环境是合法 LaTeX）。
        val latex = "\\begin{aligned} & = e^e\\left[\\frac{e}{8}t^2+ \\\\ O(t^4)\\right] + x \\end{aligned}"
        val pieces = splitFormula(latex, 10, measure)
        assertEquals("括号跨行时应整块保留：$pieces", listOf(latex), pieces)
        pieces.forEach { piece ->
            assertEquals("\\left 与 \\right 必须同段配对：$piece",
                piece.split("\\left").size - 1, piece.split("\\right").size - 1)
        }
    }

    @Test
    fun `well-fitting array stays untouched`() {
        val latex = "\\begin{array}{cc} a & b \\\\ c & d \\end{array}"
        assertEquals(listOf(latex), splitFormula(latex, 1000, measure))
    }

    @Test
    fun `thin space comma command is never a split point`() {
        // 真机截图的第四种坏例：\, 里的逗号被当成优先级 1 断点，
        // 切出以孤立 \ 结尾的非法片段（占位框显示 -y\,\mathrm dx+x\），
        // 逗号留在下一段开头渲染成 ",dy"。符号命令必须与反斜杠整体跳过。
        val latex = "x\\,\\mathrm dy - y\\,\\mathrm dx + x\\,\\mathrm dy = -r\\sin\\theta"
        val pieces = splitFormula(latex, 100, measure)
        assertTrue("应拆出多段：$pieces", pieces.size >= 2)
        pieces.forEach { piece ->
            assertFalse("片段不允许以孤立反斜杠结尾：$piece", piece.endsWith("\\"))
            assertFalse("片段不允许以逗号开头（逗号应属于 \\, 命令）：$piece", piece.startsWith(","))
        }
        // 拼接还原后所有 \, 命令必须完好
        assertTrue(pieces.joinToString(" ").contains("\\,\\mathrm"))
    }

    @Test
    fun `escaped braces do not corrupt depth tracking`() {
        // \{ \} 转义花括号不能计入分组深度，否则深度错位后会在 {} 外误切或漏切
        val latex = "f\\{x\\}=g\\{y\\}+h\\{z\\}+k\\{w\\}+m\\{v\\}+n\\{u\\}"
        val pieces = splitFormula(latex, 200, measure)
        pieces.forEach { piece ->
            assertEquals("转义花括号必须成对保留：$piece",
                piece.split("\\{").size - 1, piece.split("\\}").size - 1)
        }
    }

    @Test
    fun `bare single-line cases environment gets wrapped by sanitizer`() {
        // 真机截图（平板横屏）的第五种坏例：模型把 cases 写成不带 $$ 的单行裸环境，
        // convertAlignedEnvironments 以前只往后续行找闭合，单行永远匹配不到 → 整段落成
        // Markdown 纯文本（\\ 被 Markdown 吃成 \、& 原样露出）。
        val markdown = "当 \$a \\ne 1\$ 时：\n" +
            "\\begin{cases} 0, & n < 0 \\\\ \\dfrac{1-a^{n+1}}{1-a}, & 0 \\le n < N \\end{cases}"
        val out = sanitizeReplyLatex(markdown)
        val displayCount = out.split(d).size - 1
        assertTrue("单行裸环境应被补上 $$ 包裹：$out", displayCount >= 2)
        assertTrue("cases 内容必须仍在：$out", out.contains("\\begin{cases}"))
    }

    @Test
    fun `single-line environment already inside display is left alone`() {
        // 已经包在 $$…$$ 里的单行环境不能再补一层，否则 $$ 错配
        val markdown = "${d}y(n) = \\begin{cases} a & b \\\\ c & d \\end{cases}${d}"
        assertEquals(markdown, sanitizeReplyLatex(markdown))
    }

    @Test
    fun `display opener with trailing content opens the block`() {
        // "$$y(n) =" 形式的开头（$$ 后带内容）必须能开启显示块，
        // 否则后续行的裸环境整段落成纯文本。
        val markdown = "${d}y(n) =\n\\begin{cases} a & b \\\\ c & d \\end{cases}\n${d}"
        val wrapped = wrapLongFormulas(markdown, 10_000, measure)
        // 旧行为：$$y(n) = 整行留成纯文本、环境行也是裸文本
        assertFalse("带内容开头不应再残留为纯文本：$wrapped", wrapped.contains("${d}y(n) ="))
        assertTrue("环境内容应保留：$wrapped", wrapped.contains("\\begin{cases}"))
        assertTrue("应进入显示块（成对 $$）：$wrapped", wrapped.split("\n").count { it == d } >= 2)
    }

    @Test
    fun `display closer with leading content closes the block`() {
        val markdown = "${d}\nf(x)=1\nx^2+1${d}"
        val wrapped = wrapLongFormulas(markdown, 10_000, measure)
        val lines = wrapped.split("\n")
        assertTrue("应进入显示块（成对 $$）：$wrapped", lines.count { it == d } >= 2)
        assertTrue("内容应保留：$wrapped", wrapped.contains("x^2+1"))
        assertTrue("不应残留「内容+$$」的行：$wrapped", lines.none { it.endsWith(d) && it.length > 2 })
    }

    @Test
    fun `single-line inline formula with both delimiters is untouched by opener handling`() {
        val markdown = "${d}x^2=1${d} 是方程。"
        assertEquals(markdown, wrapLongFormulas(markdown, 10_000, measure))
    }
}
