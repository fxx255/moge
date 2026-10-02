package com.moge.app.data.llm

import com.moge.app.data.parse.ReplyParser
import com.moge.app.data.llm.mergeContinuation
import com.moge.app.data.parse.normalizeReplyMarkdown
import com.moge.app.data.llm.reconcileStreamedReply
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinuationTest {
    private val dollar = '$'
    private fun parseFragment(text: String): String = ReplyParser.parse(
        buildJsonObject { put("reply", text) }.toString(), normalizeMarkdown = false,
    ).reply

    @Test fun `formula split between JSON replies is normalized only after merging`() {
        val first = "结论：双纽线 $dollar\\rho^2=\\cos 2"
        val second = "\\theta$dollar 所围面积为 ${dollar}1$dollar。"
        val joined = mergeContinuation(parseFragment(first), parseFragment(second))
        assertEquals(first + second, joined)
        assertEquals("结论：双纽线 $$\\rho^2=\\cos 2\\theta$$ 所围面积为 $$" + "1$$。", normalizeReplyMarkdown(joined))
    }

    @Test fun `replayed incomplete paragraph replaces the truncated formula`() {
        val start = "已完成的推导。\n\n"
        val prefix = "**结论：双纽线 $$\\rho^2=\\cos 2"
        val complete = prefix + "\\theta$$ 所围面积为 1。**"
        assertEquals(start + complete, mergeContinuation(start + prefix, complete))
    }

    @Test fun `literal continuation preserves whitespace and repeated mathematical digits`() {
        assertEquals("公式 x=22", mergeContinuation("公式 x=2", "2"))
        assertEquals("abc\n\n后文", mergeContinuation("abc", "\n\n后文"))
        assertEquals("", mergeContinuation("", ""))
    }

    @Test fun `overlapping replay is not duplicated`() {
        val overlap = "这是前一轮已经写出的足够长的一段结论。"
        assertEquals("前文。" + overlap + "后文。", mergeContinuation("前文。" + overlap, overlap + "后文。"))
    }

    @Test fun `short final placeholder cannot replace a complete streamed answer`() {
        val streamed = "详细解答：" + "每一步都说明了推导依据。".repeat(18)
        assertEquals(streamed, reconcileStreamedReply(streamed, "见上"))
        assertEquals("最终修正的答案", reconcileStreamedReply(streamed, "最终修正的答案"))
    }

    @Test fun `short non-placeholder streamed answer survives a final placeholder`() {
        val streamed = "结论：应选 A。"
        assertEquals(streamed, reconcileStreamedReply(streamed, "见上"))
    }

    @Test fun `placeholder-only final response does not become successful answer`() {
        assertEquals("", reconcileStreamedReply("", "见上"))
        assertEquals("", reconcileStreamedReply("见上", "见上"))
        assertEquals("", reconcileStreamedReply("", "如上所述。"))
    }
}
