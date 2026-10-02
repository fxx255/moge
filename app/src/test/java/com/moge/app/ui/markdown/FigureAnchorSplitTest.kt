package com.moge.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插图锚点切分：正文里的 `[[FIGURE:n]]` 要把回答切成「文字 / 图 / 文字 / 图」交替，
 * 让生成的图表出现在讲解它的那段文字中间，而不是统一堆在气泡末尾（v1.0.23 bug）。
 */
class FigureAnchorSplitTest {

    @Test
    fun `没有锚点时不切分`() {
        val result = splitFigureSegments("只有正文，没有图。", figureCount = 2)
        assertEquals(1, result.size)
        assertEquals("只有正文，没有图。", result[0].text)
        assertNull(result[0].figureIndex)
    }

    @Test
    fun `没有图时锚点转为缺图提示并保留前后文字`() {
        // 图没渲染出来（plots 被截断丢掉 / 渲染失败）时，锚点绝不能原样显示给用户。
        // 以前 figureCount<=0 直接返回原文，正文里就裸着 [[FIGURE:1]]。
        val content = "正文\n[[FIGURE:1]]\n后面"
        val result = splitFigureSegments(content, figureCount = 0)
        assertTrue(
            "锚点必须从文字里剥掉，实际: $result",
            result.none { it.text.contains("[[FIGURE:") },
        )
        assertEquals(1, result.size)
        assertEquals("正文\n（图 1 未能生成）\n后面", result[0].text)
        assertNull(result[0].figureIndex)
    }

    @Test
    fun `只有锚点而没有图片时显示缺图提示`() {
        val result = splitFigureSegments("[[FIGURE:1]]", figureCount = 0)
        assertEquals(1, result.size)
        assertTrue(result[0].text.contains("图 1 未能生成"))
        assertNull(result[0].figureIndex)
    }

    @Test
    fun `锚点把正文切成三段`() {
        val content = "由图可见主瓣宽度为 2/T。\n[[FIGURE:1]]\n接下来分析旁瓣结构。"
        val result = splitFigureSegments(content, figureCount = 1)
        assertEquals(3, result.size)
        assertEquals("由图可见主瓣宽度为 2/T。", result[0].text)
        assertNull(result[0].figureIndex)
        assertEquals(0, result[1].figureIndex)
        assertEquals("接下来分析旁瓣结构。", result[2].text)
    }

    @Test
    fun `多张图按出现顺序排列`() {
        val content = "开头\n[[FIGURE:1]]\n中间说明\n[[FIGURE:2]]\n结尾"
        val result = splitFigureSegments(content, figureCount = 2)
        assertEquals(5, result.size)
        assertEquals(0, result[1].figureIndex)
        assertEquals("中间说明", result[2].text)
        assertEquals(1, result[3].figureIndex)
        assertEquals("结尾", result[4].text)
    }

    @Test
    fun `锚点序号是 1-based 对应 plots 下标 0`() {
        val result = splitFigureSegments("A\n[[FIGURE:2]]\nB", figureCount = 3)
        assertEquals(1, result[1].figureIndex)
    }

    @Test
    fun `越界锚点显示缺图提示且不占用其他图片`() {
        val result = splitFigureSegments("前\n[[FIGURE:3]]\n后", figureCount = 1)
        assertEquals(1, result.size)
        assertEquals("前\n（图 3 未能生成）\n后", result[0].text)
        assertTrue(result.none { it.figureIndex != null })
    }

    @Test
    fun `锚点允许行内留白与大小写与空格`() {
        val variants = listOf(
            "前\n[[FIGURE:1]]\n后",
            "前\n  [[FIGURE:1]]  \n后",
            "前\n[[figure: 1]]\n后",
            "前\n[[ Figure : 1 ]]\n后",
        )
        variants.forEach { content ->
            val result = splitFigureSegments(content, figureCount = 1)
            assertEquals("$content 未识别", 3, result.size)
            assertEquals("$content 未识别", 0, result[1].figureIndex)
        }
    }

    @Test
    fun `最终答案里的行内锚点插图并保留中文标点`() {
        val content = "结构见 [[FIGURE:1]]。后续说明。"
        val result = splitFigureSegments(content, figureCount = 1)
        assertEquals(listOf(
            FigureSegment("结构见 图 1。后续说明。", null),
            FigureSegment("", 0),
        ), result)
    }

    @Test
    fun `混合行内与独立锚点仍按槽位显示所有图片`() {
        val result = splitFigureSegments("框图（[[Figure : 2 ]]），曲线如下：\n[[FIGURE:1]]\n结束。", 2)
        assertEquals(listOf(
            FigureSegment("框图（图 2），曲线如下：", null), FigureSegment("", 1),
            FigureSegment("", 0),
            FigureSegment("结束。", null),
        ), result)
    }

    @Test
    fun `同一行的多个引用保留完整句子并在下一行前插图`() {
        val result = splitFigureSegments("对比 [[FIGURE:2]] 与 [[FIGURE:1]]（再次见 [[FIGURE:2]]）。\n后续分析。", 2)
        assertEquals(listOf(
            FigureSegment("对比 图 2 与 图 1（再次见 图 2）。", null),
            FigureSegment("", 1), FigureSegment("", 0),
            FigureSegment("后续分析。", null),
        ), result)
    }

    @Test
    fun `重复引用只显示一次图并留下可读行内图号`() {
        val result = splitFigureSegments("[[FIGURE:1]]\n再次见 [[figure:01]]。\n[[FIGURE:1]]\n[[FIGURE:2]]", 2)
        assertEquals(listOf(0, 1), result.mapNotNull { it.figureIndex })
        assertEquals("再次见 图 1。", result.first { it.text.isNotBlank() }.text)
        assertTrue(result.none { it.text.contains("[[") })
    }

    @Test
    fun `行内缺图不吞掉后面的有效图和文字`() {
        val result = splitFigureSegments("缺图 [[FIGURE:3]]，随后 [[FIGURE:2]]。", 2)
        assertEquals(listOf(1), result.mapNotNull { it.figureIndex })
        assertEquals("缺图 （图 3 未能生成），随后 图 2。", result[0].text)
        assertEquals(2, result.size)
    }

    @Test
    fun `非法与超大编号转为缺图提示而不溢出`() {
        val result = splitFigureSegments("[[FIGURE:0]] [[FIGURE:999999999999999999999]]", 1)
        assertTrue(result.none { it.figureIndex != null || it.text.contains("[[") })
        assertTrue(result.single().text.contains("图 0 未能生成"))
        assertTrue(result.single().text.contains("图 999999999999999999999 未能生成"))
    }

    @Test
    fun `围栏和行内代码里的锚点不插图也不消耗首次引用`() {
        val code = "示例 `[[FIGURE:1]]` 与 ``含 ` [[FIGURE:2]]``。\n~~~json\n[[FIGURE:1]]\n~~~"
        val result = splitFigureSegments("$code\n实际图 [[FIGURE:1]]。", 2)
        assertEquals(listOf(0), result.mapNotNull { it.figureIndex })
        assertEquals("$code\n实际图 图 1。", result[0].text)
        assertEquals(2, result.size)
    }

    @Test
    fun `仅有代码示例时保持整个正文原样`() {
        val content = "```text\n[[FIGURE:1]]\n```\n`[[FIGURE:2]]`"
        assertEquals(listOf(FigureSegment(content, null)), splitFigureSegments(content, 2))
    }

    @Test
    fun `锚点在文首或文末时不产生空文字段`() {
        val head = splitFigureSegments("[[FIGURE:1]]\n后面的话", figureCount = 1)
        assertEquals(2, head.size)
        assertEquals(0, head[0].figureIndex)
        assertEquals("后面的话", head[1].text)

        val tail = splitFigureSegments("前面的话\n[[FIGURE:1]]", figureCount = 1)
        assertEquals(2, tail.size)
        assertEquals("前面的话", tail[0].text)
        assertEquals(0, tail[1].figureIndex)
    }

    @Test
    fun `纯标题锚点不掉内容`() {
        val content = "## 分析\n[[FIGURE:1]]\n### 结论"
        val result = splitFigureSegments(content, figureCount = 1)
        assertEquals(3, result.size)
        assertEquals("## 分析", result[0].text)
        assertEquals("### 结论", result[2].text)
    }
}
