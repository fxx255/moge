package com.moge.app.ui.solve

import android.graphics.Bitmap
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.prefs.Appearance
import com.moge.app.data.prefs.UserSettings
import com.moge.app.ui.markdown.BaselineLatexSpan
import com.moge.app.ui.settings.DisplaySettingsSection
import com.moge.app.ui.theme.MogeTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisplayScaleDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun displayControlsResizeTextAndInterfaceWithReadableMathInBothThemes() {
        var prefs by mutableStateOf(UserSettings())
        val formula = """\begin{aligned}
            c(x)\bmod g(x)&=c_6(x^2+1)+c_5(x^2+x+1)\\
            &+c_4(x^2+x)+1\\
            &=(c_6+c_5+c_4)x^2\\
            &+(c_5+c_4)x+(c_6+c_5+1).
            \end{aligned}""".trimIndent()
        compose.setContent {
            MogeTheme(prefs.appearance, bodyTextSizeSp = prefs.bodyTextSizeSp, uiScalePercent = prefs.uiScalePercent) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState()).padding(12.dp)) {
                        DisplaySettingsSection(prefs, { prefs = prefs.copy(bodyTextSizeSp = it) },
                            { prefs = prefs.copy(uiScalePercent = it) })
                        Box(Modifier.size(48.dp).testTag("size-probe"))
                        AnswerSheet(SolveItem.Answer("size", AnswerState.COMPLETED,
                            "将它们代入 \$\$c(x)=c_6x^6+c_5x^5+c_4x^4+1\$\$，可得：\n\n$$\n$formula\n$$",
                            regenerateRequestId = "r"), {}, {}, true, { _, _ -> })
                    }
                }
            }
        }
        fun choose(tag: String, label: String) {
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
            compose.onNode(isPopup()).assertDoesNotExist()
        }
        fun probe() = compose.onNodeWithTag("size-probe").fetchSemanticsNode().boundsInRoot.width
        fun answerView() = native(compose.activity.window.decorView).first { it.text.startsWith("将它们代入") }
        val initialSize = probe()
        compose.waitForIdle()
        val initialText = answerView().textSize
        for (appearance in listOf(Appearance.PAPER, Appearance.CHALK)) {
            compose.runOnIdle { prefs = prefs.copy(appearance = appearance) }
            for (percent in listOf(150, 75, 100)) {
                choose("interface-scale-selector", "$percent%")
                choose("body-text-size-selector", "24")
                assertEquals(initialSize * percent / 100f, probe(), 1f)
                compose.waitForIdle()
                compose.runOnIdle {
                    val view = answerView()
                    assertEquals(initialText * percent / 100f * 24f / 17f, view.textSize, 1f)
                    val text = view.text as Spanned
                    val spans = text.getSpans(0, text.length, BaselineLatexSpan::class.java)
                    assertTrue("Formula spans must survive size changes", spans.isNotEmpty())
                    spans.forEach { span ->
                        assertTrue("Formula must fit inside its text view",
                            span.drawable.bounds.width() <= view.width - view.totalPaddingLeft - view.totalPaddingRight)
                        val line = view.layout.getLineForOffset(text.getSpanStart(span))
                        val top = view.totalPaddingTop + view.layout.getLineBaseline(line) - span.baselineOffset(view.paint)
                        assertTrue("Formula top must remain visible", top >= 0f)
                        assertTrue("Formula bottom must remain visible", top + span.drawable.bounds.height() <= view.height)
                    }
                    assertFalse("No source/error fallback", text.contains("[部分内容无法渲染"))
                    assertEquals(0, view.scrollY)
                }
                compose.onNodeWithContentDescription("分享对话图片").performScrollTo().assertIsDisplayed()
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    try {
                        File(instrumentation.targetContext.getExternalFilesDir(null), "display-$appearance-$percent.png")
                            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { bitmap.recycle() }
                }
            }
        }
    }

    private fun native(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { native(view.getChildAt(it)) }
        else -> emptyList()
    }
}
