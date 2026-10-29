package com.moge.app.ui.markdown

import org.junit.Assert.*
import org.junit.Test

class ReadingOffsetTest {
    private val body = (0..50).joinToString("\n") { "第 $it 行：这是各行不同的正文内容 $it。" }

    @Test fun `appending final ink leaves offset unchanged`() {
        assertEquals(130, readingOffsetAfterEdit(body, body + "生成结束。", 130))
    }

    @Test fun `prepended summary shifts offset to same prose`() {
        val summary = "简明答案。\n\n"
        assertEquals(130 + summary.length, readingOffsetAfterEdit(body, summary + body, 130))
    }

    @Test fun `summary insertion and tail formatting can both change`() {
        val summary = "简明答案。\n\n"
        assertEquals(130 + summary.length, readingOffsetAfterEdit(body, summary + body + "补充。", 130))
    }

    @Test fun `prose can move into a different native block after table completion`() {
        val split = body.indexOf("第 20 行")
        val at = body.indexOf("第 25 行")
        assertEquals(at - split, readingOffsetAfterEdit(body, body.substring(split), at))
    }

    @Test fun `repeated context chooses nearest occurrence`() {
        val repeated = "相同内容。".repeat(100)
        assertEquals(300, readingOffsetAfterEdit(repeated, repeated + "结尾。", 300))
    }

    @Test fun `unrelated replacement and empty content have no reading anchor`() {
        assertNull(readingOffsetAfterEdit(body, "不同的回答", 130))
        assertNull(readingOffsetAfterEdit("", body, 0))
        assertNull(readingOffsetAfterEdit(body, "", 130))
    }
}
