package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.ui.solve.AnswerSheet
import com.moge.app.ui.solve.AnswerState
import com.moge.app.ui.solve.SolveItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MultipleFigureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `summary and lecture show one image while answer is folded or expanded`() {
        val file = Files.createTempFile("moge-summary-figure", ".png").toFile()
        try {
            val bitmap = Bitmap.createBitmap(80, 30, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.RED)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            compose.setContent {
                MogeTheme {
                    AnswerSheet(SolveItem.Answer("a", AnswerState.COMPLETED, "讲解见 [[FIGURE:1]]。",
                        finalAnswer = "结果见 [[FIGURE:1]]。", figurePaths = listOf(file.absolutePath)),
                        {}, {}, true, { _, _ -> }, answerFirst = true)
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("图 1").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("展开解答").performClick()
            compose.waitForIdle()
            compose.onAllNodesWithContentDescription("图 1").assertCountEquals(1)
            compose.onNodeWithText("收起解答").performClick()
            compose.waitForIdle()
            compose.onAllNodesWithContentDescription("图 1").assertCountEquals(1)
        } finally { file.delete() }
    }

    @Test fun `multiple answers and same-count regenerated paths retain independent visible images`() {
        val directory = Files.createTempDirectory("moge-figure-test").toFile()
        fun png(name: String, color: Int): String = File(directory, name).also { file ->
            val bitmap = Bitmap.createBitmap(80, 30, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }.absolutePath
        try {
            val first = png("first.png", android.graphics.Color.RED)
            val second = png("second.png", android.graphics.Color.BLUE)
            val replacement = png("replacement.png", android.graphics.Color.GREEN)
            val current = mutableStateOf(second)
            var clicked: String? = null
            compose.setContent {
                MogeTheme {
                    Column(Modifier.requiredWidth(300.dp)) {
                        AnswerMarkdownBody("[[FIGURE:1]]", listOf(first)) { paths, index -> clicked = paths[index] }
                        AnswerMarkdownBody("[[FIGURE:1]]", listOf(current.value)) { paths, index -> clicked = paths[index] }
                    }
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("图 1").fetchSemanticsNodes().size == 2 }
            compose.onAllNodesWithContentDescription("图 1").assertCountEquals(2)[1].performClick()
            assertEquals(second, clicked)
            compose.runOnIdle { current.value = replacement }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("图 1").fetchSemanticsNodes().size == 2 }
            compose.onAllNodesWithContentDescription("图 1")[1].performClick()
            assertEquals(replacement, clicked)
            compose.onAllNodesWithContentDescription("图 1")[0].performClick()
            assertEquals(first, clicked)
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }
}
