package com.moge.app.ui.solve

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.llm.AnswerUsage
import com.moge.app.data.llm.AnswerUsageCodec
import com.moge.app.data.llm.UsageSample
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.theme.MogeTheme
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnswerUsageFooterDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun totalsAndIncompleteUsageWrapBesideActionsInBothThemesAtLargeFont() {
        val theme = mutableStateOf(Appearance.PAPER)
        val fontScale = mutableStateOf(1.6f)
        val usage = AnswerUsage().plus(UsageSample(7100, 5800, 18100)).plus(UsageSample(15700, 15500, 5900))
        val saved = mutableStateOf(AnswerUsageCodec.encode(usage))
        compose.setContent { MogeTheme(theme.value) {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale.value),
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            ) {
                Box(Modifier.width(360.dp).padding(12.dp)) {
                    AnswerSheet(SolveItem.Answer("usage", AnswerState.COMPLETED, "累计用量测试",
                        usageJson = saved.value, durationMs = 120_000, regenerateRequestId = "request"), {}, {}, true, { _, _ -> })
                }
            }
        } }
        for (appearance in listOf(Appearance.PAPER, Appearance.CHALK)) {
            for ((name, json, scale) in listOf(
                Triple("compact", AnswerUsageCodec.encode(AnswerUsage().plus(UsageSample(5200, 0, 2300))), 1f),
                Triple("total", AnswerUsageCodec.encode(usage), 1.6f),
                Triple("partial", AnswerUsageCodec.encode(usage.plus(null)), 1.6f),
            )) {
                compose.runOnIdle { theme.value = appearance; saved.value = json; fontScale.value = scale }
                val expected = requireNotNull(usageLabel(json, durationMs = 120_000))
                assertTrue(expected.startsWith("输入 "))
                assertTrue(expected.contains("输出 "))
                if (name != "compact") assertTrue(expected.contains("缓存 "))
                assertFalse("Request counts must not appear in the footer", expected.contains("次）"))
                assertFalse("The removed answer-total prefix must stay hidden", expected.contains("本次累计"))
                if (name == "total") assertTrue(expected.endsWith("200.0 tps"))
                if (name == "partial") assertFalse("Incomplete output cannot claim an average speed", expected.contains("tps"))
                val footer = compose.onNodeWithText(expected).assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                footer.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue(layouts.isNotEmpty())
                assertFalse("Usage text must wrap without clipping", layouts.single().hasVisualOverflow)
                if (name == "compact") assertTrue("Normal usage should fit within two lines", layouts.single().lineCount <= 2)
                compose.onNodeWithContentDescription("重新生成").assertIsDisplayed()
                compose.onNodeWithContentDescription("收藏到题册").assertIsDisplayed()
                compose.onNodeWithContentDescription("分享对话图片").assertIsDisplayed()
                val usageBounds = footer.fetchSemanticsNode().boundsInRoot
                val actionBounds = compose.onNodeWithContentDescription("重新生成").fetchSemanticsNode().boundsInRoot
                assertTrue("Usage must sit to the left of the three actions", usageBounds.right <= actionBounds.left)
                assertTrue("Usage and actions must share a row", usageBounds.top < actionBounds.bottom &&
                    usageBounds.bottom > actionBounds.top)
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    try {
                        File(instrumentation.targetContext.getExternalFilesDir(null), "usage-$appearance-$name.png")
                            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { bitmap.recycle() }
                }
            }
        }
    }
}
