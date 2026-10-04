package com.moge.app.ui.solve

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerTextBindingTest {
    @Test fun `delayed echoes do not overwrite newer input or move selection`() {
        val binding = ComposerTextBinding("")
        binding.field.setTextAndPlaceCursorAtEnd("a")
        binding.reportText("a") { }
        binding.field.setTextAndPlaceCursorAtEnd("abc")
        binding.reportText("abc") { }
        binding.field.edit { selection = TextRange(1, 2) }
        binding.acceptExternal("a")
        assertEquals("abc", binding.field.text.toString())
        assertEquals(TextRange(1, 2), binding.field.selection)
        binding.acceptExternal("abc")
        assertEquals(TextRange(1, 2), binding.field.selection)
        binding.acceptExternal("")
        assertEquals("", binding.field.text.toString())
        var reports = 0
        binding.reportText("") { reports++ }
        assertEquals(0, reports)
    }
}
