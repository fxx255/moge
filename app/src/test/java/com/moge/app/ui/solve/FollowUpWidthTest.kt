package com.moge.app.ui.solve

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w1280dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FollowUpWidthTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `long follow up grows with available width while short notes stay compact`() =
        assertResponsive("请继续说明这个推导过程，以及每一步中公式的含义。".repeat(12))

    @Test fun `formula follow up rewraps using the available tablet width`() =
        assertResponsive("由 \$A^*A=|A|I=0\$ 可得对每一列向量都有 \$A^*\\alpha_i=0\$，请解释其中的线性关系。".repeat(8))

    private fun assertResponsive(source: String) {
        val width = mutableStateOf(360.dp)
        val text = mutableStateOf(source)
        compose.setContent { MogeTheme {
            Box(Modifier.width(width.value)) {
                FollowUpNote(SolveItem.Question("q", text.value, emptyList(), "", false), onOpenImages = { _, _ -> })
            }
        } }
        val note = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val narrow = note.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { width.value = 1200.dp }
        val wide = note.fetchSemanticsNode().boundsInRoot
        assertTrue("Long notes must use the tablet width: $narrow -> $wide", wide.width > narrow.width * 2)
        assertTrue("Wider notes must rewrap into fewer lines", wide.height < narrow.height)
        compose.runOnIdle { text.value = "继续" }
        assertTrue("A short note should still wrap its content", note.fetchSemanticsNode().boundsInRoot.width < narrow.width)
    }
}
