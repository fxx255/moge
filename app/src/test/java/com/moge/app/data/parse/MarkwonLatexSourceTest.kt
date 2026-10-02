package com.moge.app.data.parse

import android.app.Application
import io.noties.markwon.Markwon
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
 * 真实显示链路：`Markwon.parse` → `JLatexMathBlock.latex()` → JLatexMath。
 *
 * 为什么必须有这一组，而不是只对每个公式单独调 `JLatexMathDrawable.builder()`：
 *
 * Markwon 4.6.2 的 `JLatexMathBlockParser` 在 `addLine` 时**每行都会补一个 LF**，
 * `closeBlock` 直接把 `builder.toString()`（**不 trim**）交给 LaTeX。
 * 于是 Markwon 真正交给 JLatexMath 的字符串与我们自己的中间结果**并不相同**：它自带换行。
 *
 * 之前犯的错误：用 `\$\$([\s\S]*?)\$\$` 正则从 prepared markdown 里抠出公式再 `trim()`，
 * 那测的是**我们自己拼的字符串**，不是 Markwon 真正喂给引擎的那份 —— 于是
 * 「尾部的反斜杠没跟空格一起消失」这类问题会被 trim 悄悄抹平、测不出来。
 *
 * 这里用 visitor **直接从 Markwon AST 里取** block 源码，并且**不 trim**，
 * 与真实渲染时的输入保持一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MarkwonLatexSourceTest {

    private val ctx: Application get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() { JLatexMathAndroid.init(ctx) }

    private fun markwon(): Markwon = Markwon.builder(ctx)
        .usePlugin(io.noties.markwon.inlineparser.MarkwonInlineParserPlugin.create())
        .usePlugin(io.noties.markwon.ext.latex.JLatexMathPlugin.create(17f) { b -> b.inlinesEnabled(true) })
        .build()

    /**
     * 从 Markwon AST 里取出所有公式块的**原始 latex**（不 trim）。
     *
     * JLatexMathNode 的字段是 package-private 的 `latex()` 取值器；用反射读取，
     * 避免把测试绑死在 Markwon 内部 API 的类名上（升级时这里会明确报错，而不是静默失效）。
     */
    private lateinit var blockSources: List<String>
    private lateinit var inlineSources: List<String>

    private fun collect(markdown: String) {
        val blocks = ArrayList<String>()
        val inlines = ArrayList<String>()
        val node = markwon().parse(markdown)
        visit(node) { node ->
            val name = node.javaClass.name
            try {
                val getter = node.javaClass.getDeclaredMethod("latex")
                getter.isAccessible = true
                val value = getter.invoke(node) as? String ?: return@visit
                if (name.contains("JLatexMathBlock")) blocks += value
                else if (name.contains("JLatexMathInline")) inlines += value
            } catch (e: NoSuchMethodException) {
                return@visit
            }
        }
        blockSources = blocks
        inlineSources = inlines
    }

    private fun visit(node: org.commonmark.node.Node, action: (org.commonmark.node.Node) -> Unit) {
        action(node)
        var child = node.firstChild
        while (child != null) {
            visit(child, action)
            child = child.next
        }
    }

    /** Markwon 交给引擎的那份源码必须能解析（不 trim，正是它的原样输入）。 */
    private fun assertEveryRawSourceBuilds(label: String) {
        assertTrue("应当解析出公式块: $label", blockSources.isNotEmpty())
        for (source in blockSources + inlineSources) {
            val error = runCatching {
                JLatexMathDrawable.builder(source).textSize(36f).build()
            }.exceptionOrNull()
            if (error != null) {
                throw AssertionError(
                    "Markwon 交给引擎的源码解析失败（$label）:\n" +
                        "源码(不trim,已转义)=${source.replace("\n", "\\n")}\n" +
                        "错误: ${error.javaClass.simpleName}: ${error.message}",
                )
            }
        }
    }

    /**
     * 用户那条回答里的长推导链：`\ \Rightarrow\ ` 之间的连接。
     *
     * 窄屏会把它拆开；拆开后**每一段都不能以孤立反斜杠结尾**（见 trimSegment）。
     */
    @Test fun escapedSpaceBeforeImplicationSurvivesRealMarkwonPipeline() {
        val formula = """1=\frac{1}{4}\left(\frac{1}{n}+\frac{1}{4}\right)\ \Rightarrow\ """ +
            """\frac{1}{n}+\frac{1}{4}=4\ \Rightarrow\ \frac{1}{n}=\frac{15}{4}\ """ +
            """\Rightarrow\ n=\frac{4}{15}\approx0.267"""
        val dd = "\$\$"
        for (width in listOf(240, 420, 900)) {
            val prepared = wrapLongFormulas(
                sanitizeReplyLatex(normalizeReplyMarkdown("$dd\n$formula\n$dd")),
                width,
            ) { JLatexMathDrawable.builder(it).textSize(36f).build().intrinsicWidth }
            collect(prepared)
            assertEveryRawSourceBuilds("width=$width")
        }
    }

    /** 绝不留下「以孤立反斜杠结尾」的公式块 —— Markwon 补 LF 后这就是坏的控制序列。 */
    @Test fun noProducedBlockEndsWithLoneBackslash() {
        val formula = """1=\frac{1}{4}\left(\frac{1}{n}+\frac{1}{4}\right)\ \Rightarrow\ n=\frac{4}{15}"""
        val dd = "\$\$"
        for (width in listOf(200, 320, 640)) {
            val prepared = wrapLongFormulas(
                sanitizeReplyLatex(normalizeReplyMarkdown("$dd\n$formula\n$dd")),
                width,
            ) { JLatexMathDrawable.builder(it).textSize(36f).build().intrinsicWidth }
            collect(prepared)
            assertTrue("应有公式块 @$width", blockSources.isNotEmpty())
            for (source in blockSources) {
                val trimmedForProbe = source.trimEnd()
                assertTrue(
                    "块源码不应以孤立反斜杠结尾 @$width: ${trimmedForProbe.replace("\n", "\\n")}",
                    !trimmedForProbe.endsWith("\\"),
                )
            }
        }
    }

    /** 该回答里的其余公式同样要在真实链路上成立。 */
    @Test fun otherFormulasFromThatAnswerSurviveRealPipeline() {
        val formulas = listOf(
            """e^{\frac{4}{n}} = \frac{1}{4}\left(\frac{1}{n} + \frac{1}{4}\right)e^{-\frac{4}{n}},\quad n=?""",
            """e^{\frac{8}{n}} = \frac{1}{4}\left(\frac{1}{n}+\frac{1}{4}\right) = \frac{n+4}{16n}\quad(n\neq 0)""",
            """e^{8t}=\frac{1}{32}\ \Rightarrow\ t^*=-\frac{\ln 32}{8}\approx-0.433""",
        )
        val dd = "\$\$"
        formulas.forEachIndexed { index, formula ->
            val prepared = wrapLongFormulas(
                sanitizeReplyLatex(normalizeReplyMarkdown("$dd\n$formula\n$dd")),
                420,
            ) { JLatexMathDrawable.builder(it).textSize(36f).build().intrinsicWidth }
            collect(prepared)
            assertEveryRawSourceBuilds("formula#$index")
        }
    }
}
