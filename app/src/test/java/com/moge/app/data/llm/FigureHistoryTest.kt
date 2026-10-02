package com.moge.app.data.llm

import com.moge.app.data.parse.IncrementalReplyDecoder
import com.moge.app.data.parse.findFigureAnchors
import com.moge.app.runtime.historyAnswerText
import org.junit.Assert.*
import org.junit.Test

class FigureHistoryTest {
    @Test fun `new turn cumulative numbering is localized only when correspondence is unambiguous`() {
        assertEquals("[[FIGURE:1]]", localizeFigureAnchors("[[FIGURE:7]]", 1))
        assertEquals("[[FIGURE:1]]\n[[FIGURE:2]]", localizeFigureAnchors("[[FIGURE:3]]\n[[FIGURE:4]]", 2))
        assertEquals("[[FIGURE:2]]", localizeFigureAnchors("[[FIGURE:2]]", 2))
        assertEquals("[[FIGURE:2]]\n[[FIGURE:4]]", localizeFigureAnchors("[[FIGURE:2]]\n[[FIGURE:4]]", 2))
        assertEquals("[[FIGURE:3]]", offsetFigureAnchors(localizeFigureAnchors("[[FIGURE:7]]", 1), 2))
    }

    @Test fun `history carries final answer but no reusable figure anchor`() {
        val result = historyAnswerText("说明\n[[FIGURE:1]]\n完整讲解", "x = 2")
        assertTrue(result.contains("x = 2"))
        assertTrue(result.contains("历史回答的图 1"))
        assertFalse(result.contains("[[FIGURE:"))
    }

    @Test fun `inline cumulative anchors localize then offset with repeated references and punctuation intact`() {
        val text = "结构见 [[FIGURE:7]]，曲线见 [[ Figure : 8 ]]；再看 [[figure:7]]。"
        val localized = "结构见 [[FIGURE:1]]，曲线见 [[FIGURE:2]]；再看 [[FIGURE:1]]。"
        assertEquals(localized, localizeFigureAnchors(text, 2))
        assertEquals("结构见 [[FIGURE:4]]，曲线见 [[FIGURE:5]]；再看 [[FIGURE:4]]。",
            offsetFigureAnchors(localizeFigureAnchors(text, 2), 3))
    }

    @Test fun `localization ignores code examples and leaves ambiguous inline numbering unchanged`() {
        val code = "`[[FIGURE:1]]`\n~~~json\n[[FIGURE:99]]\n~~~\n"
        assertEquals("${code}结构见 [[FIGURE:1]]，曲线见 [[FIGURE:2]]。",
            localizeFigureAnchors("${code}结构见 [[FIGURE:7]]，曲线见 [[FIGURE:8]]。", 2))
        val incomplete = "结构见 [[FIGURE:7]]。"
        assertEquals(incomplete, localizeFigureAnchors(incomplete, 2))
        val gapped = "结构见 [[FIGURE:7]]，曲线见 [[FIGURE:9]]。"
        assertEquals(gapped, localizeFigureAnchors(gapped, 2))
    }

    @Test fun `history sanitizes inline anchors in both final answer and explanation`() {
        val result = historyAnswerText("曲线见 [[figure : 2 ]]。", "结构见 [[FIGURE:1]]。")
        assertEquals("最终答案：\n结构见 （历史回答的图 1，新回答需要重新生成图形）。\n\n" +
            "曲线见 （历史回答的图 2，新回答需要重新生成图形）。", result)
        assertTrue(findFigureAnchors(result).isEmpty())
    }

    @Test fun `history preserves code examples but never emits a reusable prose anchor`() {
        val code = "示例 `[[FIGURE:1]]`：\n```json\n[[FIGURE:2]]\n```"
        val result = historyAnswerText("$code\n实际见 [[FIGURE:3]]。", "")
        assertEquals("$code\n实际见 （历史回答的图 3，新回答需要重新生成图形）。", result)
        assertTrue(findFigureAnchors(result).isEmpty())
    }

    @Test fun `history does not duplicate a final answer already contained in the explanation`() {
        val text = "结构见 [[FIGURE:1]]。\n讲解。"
        assertEquals("结构见 （历史回答的图 1，新回答需要重新生成图形）。\n讲解。",
            historyAnswerText(text, "结构见 [[FIGURE:1]]。"))
    }

    @Test fun `streamed final answer requires closing quote and excludes nested keys`() {
        val decoder = IncrementalReplyDecoder("answer")
        decoder.append("{\"other\":{\"answer\":\"错误\"},\"answer\":\"x = ")
        assertFalse(decoder.valueComplete)
        decoder.append("2\",\"reply\":\"推导\"}")
        assertTrue(decoder.valueComplete)
        assertEquals("x = 2", decoder.text)
        decoder.reset()
        decoder.append("{\"answer\":null,\"reply\":\"推导\"}")
        assertFalse(decoder.valueComplete)
    }
}
