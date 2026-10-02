package com.moge.app.data.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 多轮续写时合并各轮图表列表，锚点必须按已累积的图表数平移。
 *
 * 背景：长推导撞上输出上限会自动续写。第 1 轮模型输出正文 + plots 若干张；
 * 第 2 轮模型只续写文字，它并不知道前面已有几张图，如果之后再画图，锚点仍从
 * `[[FIGURE:1]]` 起编号。若不平移，`[[FIGURE:1]]` 会错误地指向第 1 轮的第一张图。
 * 平移规则必须与 `splitFigureSegments` 的锚点解析保持一致（大小写不敏感、
 * 允许行内留白与句中引用，跳过 Markdown 代码示例）。
 *
 * ⚠️ 这里测的 [offsetFigureAnchors] 就是**生成管理器实际调用的那一份**
 * ：以前界面与管理器各有一份实现，
 * 旧测试只覆盖界面那份，管理器那份（行为还不一样）完全没有测试保护。
 */
class FigureAnchorOffsetTest {

    @Test
    fun `base 为 0 时原样返回`() {
        val text = "见下图：\n\n[[FIGURE:1]]\n\n后面的文字"
        assertEquals(text, offsetFigureAnchors(text, 0))
    }

    @Test
    fun `单个锚点按 base 平移`() {
        val shifted = offsetFigureAnchors("正文\n\n[[FIGURE:1]]\n\n尾注", 2)
        assertEquals("正文\n\n[[FIGURE:3]]\n\n尾注", shifted)
    }

    @Test
    fun `多个锚点全部平移`() {
        val shifted = offsetFigureAnchors("[[FIGURE:1]]\n文字\n[[FIGURE:2]]", 3)
        assertEquals("[[FIGURE:4]]\n文字\n[[FIGURE:5]]", shifted)
    }

    @Test
    fun `大小写与留白变体同样平移`() {
        val shifted = offsetFigureAnchors("  [[ figure : 2 ]]  ", 1)
        assertEquals("  [[FIGURE:3]]  ", shifted)
    }

    @Test
    fun `行内锚点平移并保留句中留白与标点`() {
        val text = "前文 [[FIGURE:1]] 后文"
        assertEquals("前文 [[FIGURE:6]] 后文", offsetFigureAnchors(text, 5))
        assertEquals("结构见 [[FIGURE:3]]。", offsetFigureAnchors("结构见 [[FIGURE:1]]。", 2))
    }

    @Test
    fun `代码示例里的编号不平移但后续真实引用会平移`() {
        val code = "示例 `[[FIGURE:1]]` 与 ``含 ` [[FIGURE:2]]``。\n```json\n[[FIGURE:1]]\n```"
        assertEquals("$code\n实际 [[FIGURE:4]]。", offsetFigureAnchors("$code\n实际 [[FIGURE:2]]。", 2))
    }

    @Test
    fun `未闭合围栏保护后续协议示例`() {
        val text = "已完成 [[FIGURE:1]]。\n~~~text\n[[FIGURE:2]]"
        assertEquals("已完成 [[FIGURE:4]]。\n~~~text\n[[FIGURE:2]]", offsetFigureAnchors(text, 3))
    }

    @Test
    fun `无效编号与偏移溢出不会指向另一张图片`() {
        val text = "[[FIGURE:0]] [[FIGURE:2147483647]] [[FIGURE:999999999999999999999]]"
        assertEquals(text, offsetFigureAnchors(text, 1))
    }

    @Test
    fun `没有锚点的正文原样返回`() {
        val text = "这是一段普通正文，提到图但没有任何锚点。"
        assertEquals(text, offsetFigureAnchors(text, 3))
    }
}
