package com.moge.app.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数学环境的 `$$` 定界符**必须守恒**。
 *
 * 用户 v1.0.36 反馈：第 4 节「最终结果」里那条 `S_{Y_c}(f) = S_{Y_s}(f) = \begin{cases}…`
 * 没有渲染，而是 `=` 之后的部分以 LaTeX 源码形式原样露出。
 *
 * 根因：`convertAlignedEnvironments` 判断「这个环境是否已被 `$$` 包裹」时，
 * 只看**上一行是否以 `$$` 开头**。而模型很常写成
 *
 *     $$
 *     S_{Y_c}(f) = S_{Y_s}(f) =      ← 等号收尾，环境在下一行
 *     \begin{cases}
 *     …
 *     \end{cases}
 *     $$
 *
 * 此时上一行既不以 `$$` 开头、也不以 `$$` 结尾 ⇒ 被判为「未包裹」，
 * 于是在**已经打开的显示块内部**又补了一对 `$$`，把块从中间劈开：
 * `=` 之前的内容是公式，`\begin{cases}` 起始的后半段掉出数学上下文、按源码显示。
 *
 * 正确判据是**数 `$$` 的收支**：从文档开头累计到环境起始行之前，出现**奇数**个
 * `$$` 就说明显示块已打开，绝不能再补。
 *
 * 注意：JLatexMath **完全支持** cases（15/15 实测通过，含截图里的畸形写法），
 * 所以这不是渲染能力问题，纯粹是定界符配对问题。
 */
class CasesDelimiterTest {

    /** 统计 `$$` 的个数（成对出现才算一个定界符）。 */
    private fun countDisplayDelimiters(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length - 1) {
            if (text[i] == '$' && text[i + 1] == '$') {
                count++
                i += 2
            } else {
                i++
            }
        }
        return count
    }

    /** 模型「等号收尾 + 环境换行」的写法：这是用户截图里的真实形态。 */
    private val casesAfterEquals = listOf(
        "4. 最终结果",
        "",
        "${'$'}${'$'}",
        "S_{Y_c}(f) = S_{Y_s}(f) =",
        "\\begin{cases}",
        "4\\pi^2N_0\\big(f^2+f_c^2\\big), & |f|\\le \\dfrac B2\\\\",
        "0, & |f|>\\dfrac B2",
        "\\end{cases}",
        "${'$'}${'$'}",
    ).joinToString("\n")

    @Test
    fun `等号后换行的 cases 不会被补出多余的美元定界符`() {
        val out = sanitizeReplyLatex(normalizeReplyMarkdown(casesAfterEquals))

        val input = countDisplayDelimiters(casesAfterEquals)
        val result = countDisplayDelimiters(out)
        println("PROBE delimiters input=$input result=$result\n$out")

        assertEquals(
            "sanitize 不得改变 \$\$ 定界符数量（多补一对会把显示块劈成两半，" +
                "后半段会以 LaTeX 源码露出）",
            input,
            result,
        )
        // 环境仍在同一个显示块里：`=` 与 `\begin{cases}` 之间不能插进 `$$`
        assertTrue(
            "环境必须仍在同一个 \$\$ 块内，实际:\n$out",
            !Regex("""=\s*\n\s*\$\$""").containsMatchIn(out),
        )
    }

    @Test
    fun `裸 cases 环境（模型漏写美元定界符）仍会被正确包裹`() {
        val bare = listOf(
            "4. 最终结果",
            "",
            "S_{Y_c}(f) = S_{Y_s}(f) =",
            "\\begin{cases}",
            "4\\pi^2N_0\\big(f^2+f_c^2\\big), & |f|\\le \\dfrac B2\\\\",
            "0, & |f|>\\dfrac B2",
            "\\end{cases}",
        ).joinToString("\n")

        val out = sanitizeReplyLatex(normalizeReplyMarkdown(bare))
        println("PROBE bare result=$out")

        assertEquals("裸环境应被补成一对 \$\$", 2, countDisplayDelimiters(out))
        assertTrue("应包含 \\begin{cases}", out.contains("\\begin{cases}"))
    }

    @Test
    fun `aligned 环境在已打开的显示块内也不会被重复包裹`() {
        val md = listOf(
            "${'$'}${'$'}",
            "f(x) =",
            "\\begin{aligned}",
            "a &= b \\\\",
            "c &= d",
            "\\end{aligned}",
            "${'$'}${'$'}",
        ).joinToString("\n")

        val out = sanitizeReplyLatex(normalizeReplyMarkdown(md))
        println("PROBE aligned result=$out")

        assertEquals(
            "已打开的显示块内不得再补 \$\$",
            countDisplayDelimiters(md),
            countDisplayDelimiters(out),
        )
    }

    @Test
    fun `代码块里的美元定界符不参与配对统计`() {
        val md = listOf(
            "```",
            "${'$'}${'$'}",
            "\\begin{cases} a & b \\end{cases}",
            "${'$'}${'$'}",
            "```",
            "",
            "正文里的公式 ${'$'}${'$'}x = 1${'$'}${'$'}",
        ).joinToString("\n")

        val out = sanitizeReplyLatex(normalizeReplyMarkdown(md))
        println("PROBE codeblock result=$out")

        // 代码块内容必须原样保留
        assertTrue("代码块内的 \$\$ 应原样保留", out.contains("```\n${'$'}${'$'}"))
    }
}
