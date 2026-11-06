package com.moge.app.domain.diagram

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LfsrLabelDeviceTest {
    /** Android's ICU regex parser is stricter about braces than the JVM parser. */
    @Test fun storedStateLabelsMoveToTheirOutputWiresOnAndroid() {
        val labels = listOf("\$a_{n-1}\$", "a_0", "a₁")
        val spec = LfsrSpec(stages = 3, taps = listOf(1, 3), registerLabels = labels)
        assertEquals(listOf("\$D_{0}\$", "\$D_{1}\$", "\$D_{2}\$"), (0..2).map(spec::registerLabel))
        assertEquals(labels, (0..2).map(spec::stageOutputLabel))
        assertEquals("D0", spec.copy(registerLabels = listOf("D0", "D1", "D2")).registerLabel(0))
    }
}
