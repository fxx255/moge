package com.moge.app.ui.theme

import com.moge.app.data.prefs.Appearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {

    @Test
    fun systemFollowsDarkMode() {
        assertTrue(usesChalk(Appearance.SYSTEM, systemDark = true))
        assertFalse(usesChalk(Appearance.SYSTEM, systemDark = false))
    }

    @Test
    fun explicitChoiceIgnoresSystem() {
        assertFalse(usesChalk(Appearance.PAPER, systemDark = true))
        assertTrue(usesChalk(Appearance.CHALK, systemDark = false))
    }

    @Test
    fun paletteExtrasMatchScheme() {
        assertFalse(PaperExtras.isChalk)
        assertTrue(ChalkExtras.isChalk)
        assertEquals(Paper, PaperColorScheme.background)
        assertEquals(Board, ChalkColorScheme.background)
    }
}
