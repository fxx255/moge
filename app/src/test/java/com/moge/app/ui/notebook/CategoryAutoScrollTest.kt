package com.moge.app.ui.notebook

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class CategoryAutoScrollTest {
    @Test fun `scroll bands sit above deletion and below the header with neutral space between`() {
        val list = Rect(0f, 100f, 400f, 900f)
        val trash = Rect(0f, 700f, 400f, 1000f)
        assertTrue(categoryScrollDirection(Offset(200f, 110f), list, trash, 64f) < 0f)
        assertTrue(categoryScrollDirection(Offset(200f, 690f), list, trash, 64f) > 0f)
        for (point in listOf(Offset(200f, 400f), Offset(200f, 701f), Offset(2f, 710f), Offset(200f, 99f))) {
            assertEquals(0f, categoryScrollDirection(point, list, trash, 64f), 0f)
        }
    }
}
