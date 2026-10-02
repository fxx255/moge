package com.moge.app.ui.export

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ExportLayoutTest {
    @Test fun `pagination covers every pixel in order and keeps each slice bounded`() {
        val random = Random(81)
        repeat(100) {
            val boundaries = listOf(0) + (1..150).runningFold(0) { y, _ -> y + random.nextInt(1, 5500) }.drop(1)
            val slices = paginateBoundaries(boundaries, 2600)
            assertEquals(0, slices.first().top)
            assertEquals(boundaries.last(), slices.last().bottom)
            assertTrue(slices.all { it.height <= 2600 && it.bottom > it.top })
            assertTrue(slices.zipWithNext().all { (a, b) -> a.bottom == b.top })
            assertTrue(slices.all { it.top in boundaries && it.bottom in boundaries })
        }
    }

    @Test fun `oversized formula line is scaled whole instead of cut`() {
        val slices = paginateBoundaries(listOf(0, 30, 9030, 9080), 3000)
        assertEquals(3, slices.size)
        assertEquals(30, slices[1].top)
        assertEquals(9030, slices[1].bottom)
        assertEquals(3000, slices[1].height)
    }

    @Test fun `oversized source is retained exactly including surrogate pairs`() {
        val source = "````\n" + "a😀".repeat(5000) + "\n````"
        val chunks = boundedSourceChunks(source)
        assertEquals(source, chunks.joinToString("") { it.source })
        assertTrue(chunks.all { it.source.length <= ExportLimits.SOURCE_CHUNK && it.plain })
        assertTrue(chunks.none { it.source.last().isHighSurrogate() || it.source.first().isLowSurrogate() })
    }

    @Test fun `blank lines inside complete math and fences are kept together`() {
        val block = "\$\$x\n\ny\$\$"
        val fence = "```\nx\n\ny\n```"
        val source = "a\n\n$block\n\n$fence\n\nz"
        val chunks = boundedSourceChunks(source, 25)
        assertEquals(source, chunks.joinToString("") { it.source })
        assertTrue(chunks.any { block in it.source })
        assertTrue(chunks.any { fence in it.source })
    }

    @Test fun `table grouping retains every column and surplus model cells`() {
        val table = parseExportTable("| A | B |\n|---|---|\n| one | two | three | four | five | six | seven |")!!
        assertEquals(7, table.columns)
        assertEquals((0..6).toList(), tableColumnGroups(table.columns).flatMap { it.toList() })
        assertTrue(tableColumnGroups(table.columns).all { it.count() <= 3 })
        assertEquals("seven", table.rows.single().last())
    }

    @Test fun `table escaped and code pipes stay in their own cells`() {
        assertEquals(listOf("a\\|b", "`c|d`", ""), tableCells("| a\\|b | `c|d` | |"))
        assertEquals(listOf("a", ""), tableCells("|a||"))
    }

    @Test fun `default export contains selected proof final answer and all figures with missing anchor labels`() {
        val content = AnswerExportContent("selected", "question", "recognized", listOf("photo"),
            "body\n[[FIGURE:2]]\ntail\n[[FIGURE:9]]", "answer", listOf("first", ""))
        val parts = exportParts(content, ExportChoice.FULL)
        assertEquals(listOf("photo", "", "first"), parts.filterIsInstance<ExportPart.Image>().map { it.path })
        assertTrue(parts.filterIsInstance<ExportPart.Text>().any { it.source == "recognized" })
        assertTrue(parts.filterIsInstance<ExportPart.Text>().any { it.source == "answer" })
        assertTrue(parts.filterIsInstance<ExportPart.Text>().any { "图 9 未能生成" in it.source })
    }

    @Test fun `answer-only uses explicit answer and its anchors while retaining the question`() {
        val content = AnswerExportContent("title", "selected", questionPhotos = listOf("photo"),
            answerText = "not selected", finalAnswer = "42\n[[FIGURE:2]]", figurePaths = listOf("one", "two"))
        val parts = exportParts(content, ExportChoice.ANSWER_ONLY)
        assertFalse(parts.filterIsInstance<ExportPart.Text>().any { "not selected" in it.source })
        assertTrue(parts.filterIsInstance<ExportPart.Text>().any { it.source == "selected" })
        assertEquals(listOf("photo", "two"), parts.filterIsInstance<ExportPart.Image>().map { it.path })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `answer-only never guesses an answer from prose`() {
        exportParts(AnswerExportContent("", "", answerText = "first paragraph"), ExportChoice.ANSWER_ONLY)
    }

    @Test fun `inline summary and lecture references export each figure once with intact punctuation`() {
        val content = AnswerExportContent("title", "q", answerText = "结构（[[FIGURE:1]]）与曲线 [[FIGURE:2]]。",
            finalAnswer = "见 [[FIGURE:1]]。", figurePaths = listOf("diagram", "curve"))
        val parts = exportParts(content, ExportChoice.FULL)
        assertEquals(listOf("diagram", "curve"), parts.filterIsInstance<ExportPart.Image>().map { it.path })
        val prose = parts.filterIsInstance<ExportPart.Text>().map { it.source }
        assertTrue("见 图 1。" in prose)
        assertTrue("结构（图 1）与曲线 图 2。" in prose)
        assertTrue(prose.none { "[[FIGURE:" in it })
    }

    @Test fun `answer only retains inline figure and leaves code samples untouched`() {
        val example = "```text\n[[FIGURE:2]]\n```"
        val content = AnswerExportContent("title", "q", answerText = "lecture",
            finalAnswer = "答案见 [[FIGURE:1]]。\n\n$example", figurePaths = listOf("curve", "unused"))
        val parts = exportParts(content, ExportChoice.ANSWER_ONLY)
        assertEquals(listOf("curve"), parts.filterIsInstance<ExportPart.Image>().map { it.path })
        assertTrue(parts.filterIsInstance<ExportPart.Text>().any { "答案见 图 1。" == it.source })
        assertTrue(parts.filterIsInstance<ExportPart.Text>().any { example == it.source })
    }
}
