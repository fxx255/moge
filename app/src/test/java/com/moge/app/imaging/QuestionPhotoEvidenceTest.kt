package com.moge.app.imaging

import com.moge.app.imaging.book.Corner
import com.moge.app.imaging.book.Geometry
import com.moge.app.imaging.book.safePageCorners
import org.junit.Assert.*
import org.junit.Test

class QuestionPhotoEvidenceTest {
    @Test fun `question cues distinguish exercises from prose and receipts`() {
        listOf("已知 x+1=3，求 x 的值", "1+1=?", "Solve the equation x + 1 = 3.",
            "What is the result?\n A. 1\n B. 2\n C. 3\n D. 4").forEach { assertTrue(it, looksLikeQuestion(it)) }
        listOf("", "阳光洒在书页上，这是美好的一天。", "超市小票 合计 123.50 元", "2026-10-20", "x(t)")
            .forEach { assertFalse(it, looksLikeQuestion(it)) }
    }

    @Test fun `page rectangle must contain all question text before perspective cropping`() {
        val detected = listOf(Corner(.1f, .1f), Corner(.9f, .1f), Corner(.9f, .9f), Corner(.1f, .9f))
        val inside = QuestionPhotoEvidence("已知", listOf(TextRegion(.2f, .2f, .8f, .8f)))
        assertEquals(detected, safePageCorners(detected, inside))
        assertEquals(Geometry.fullFrame, safePageCorners(detected,
            inside.copy(regions = inside.regions + TextRegion(.1f, .92f, .8f, .98f))))
        assertEquals(Geometry.fullFrame, safePageCorners(detected.map { Corner(it.x / 2, it.y / 2) }, inside))
    }
}
