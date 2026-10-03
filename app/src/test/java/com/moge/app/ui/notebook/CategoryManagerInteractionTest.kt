package com.moge.app.ui.notebook

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.moge.app.data.prefs.Appearance
import java.io.File
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.ui.theme.LocalPaperMotionEnabled
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Touch injection exercises the actual long-hold recognizer, lazy layout and confirmation dialogs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w480dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CategoryManagerInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val categories = mutableStateOf(listOf(
        NotebookCategoryEntity(id = "a", name = "代数"),
        NotebookCategoryEntity(id = "b", name = "几何"),
        NotebookCategoryEntity(id = "c", name = "概率"),
        NotebookCategoryEntity(id = "d", name = "复习"),
    ))
    private val busy = mutableStateOf(false)
    private val reorders = mutableListOf<Pair<String, Int>>()
    private val deleted = mutableListOf<String>()
    private val renamed = mutableListOf<Pair<String, String>>()
    private val created = mutableListOf<String>()

    private fun render(saveStartsBusy: Boolean = true) {
        compose.setContent {
            MogeTheme {
                CompositionLocalProvider(LocalPaperMotionEnabled provides true) {
                    CategoryManager(categories.value, busy.value, onDismiss = {},
                        onCreate = { created += it }, onRename = { id, name -> renamed += id to name },
                        onReorder = { id, offset -> reorders += id to offset; if (saveStartsBusy) busy.value = true },
                        onDelete = { id -> deleted += id; categories.value = categories.value.filterNot { it.id == id } })
                }
            }
        }
    }

    private fun hold(id: String) {
        compose.onNodeWithTag("category-handle-$id", useUnmergedTree = true).performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, 1f))
        }
        compose.waitForIdle()
    }

    private fun moveTo(point: Offset) {
        val root = compose.onNodeWithTag("category-manager").fetchSemanticsNode().boundsInRoot.topLeft
        compose.onNodeWithTag("category-manager").performTouchInput { moveTo(point - root, delayMillis = 240) }
        compose.waitForIdle()
    }

    private fun pastCard(id: String): Offset {
        val card = compose.onNodeWithTag("category-card-$id").fetchSemanticsNode().boundsInRoot
        return Offset(card.center.x, card.top + card.height * 0.8f)
    }

    private fun release() {
        compose.onNodeWithTag("category-manager").performTouchInput { up() }
        compose.waitForIdle()
    }

    private fun cancel() {
        compose.onNodeWithTag("category-manager").performTouchInput { cancel() }
        compose.waitForIdle()
    }

    private fun invokeAction(id: String, label: String) {
        val actions = compose.onNodeWithTag("category-card-$id").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertTrue(actions.single { it.label == label }.action())
        }
    }

    @Test fun `real long hold previews several slots and commits one multiple offset at release`() {
        render()
        val originalFirstTop = compose.onNodeWithTag("category-card-a").fetchSemanticsNode().boundsInRoot.top
        hold("a")
        compose.onNodeWithTag("category-drag-preview").assertExists()
        moveTo(pastCard("b"))
        assertTrue(reorders.isEmpty())
        assertTrue(compose.onNodeWithTag("category-card-b").fetchSemanticsNode().boundsInRoot.top <= originalFirstTop + 1f)
        moveTo(pastCard("c"))
        assertTrue(reorders.isEmpty())
        release()
        assertEquals(listOf("a" to 2), reorders)
        compose.onNodeWithTag("category-drag-preview").assertDoesNotExist()
        // The repository has not emitted yet: a second hold must not replay a stale move.
        hold("a")
        compose.onNodeWithTag("category-drag-preview").assertDoesNotExist()
        release()
        assertEquals(1, reorders.size)
        compose.runOnIdle { busy.value = true }
        compose.runOnIdle {
            categories.value = listOf(categories.value[1], categories.value[2], categories.value[0], categories.value[3])
            busy.value = false
        }
        hold("a")
        val first = compose.onNodeWithTag("category-card-b").fetchSemanticsNode().boundsInRoot
        moveTo(Offset(first.center.x, first.top + first.height * 0.2f))
        release()
        assertEquals(listOf("a" to 2, "a" to -2), reorders)
        assertTrue(deleted.isEmpty())
    }

    @Test fun `real delete drop asks confirmation and cancellation preserves the category`() {
        render()
        hold("b")
        moveTo(compose.onNodeWithTag("category-delete-zone").fetchSemanticsNode().boundsInRoot.center)
        compose.onNodeWithText("松手后确认删除分类").assertExists()
        assertTrue(deleted.isEmpty())
        release()
        compose.onNodeWithText("该分类的题目与历史对话会移至未分类，内容不会删除。").assertExists()
        assertTrue(deleted.isEmpty())
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("category-card-b").assertExists()
        hold("b")
        moveTo(compose.onNodeWithTag("category-delete-zone").fetchSemanticsNode().boundsInRoot.center)
        release()
        compose.onNodeWithText("删除分类", substring = false).performClick()
        assertEquals(listOf("b"), deleted)
        compose.onNodeWithTag("category-card-b").assertDoesNotExist()
        assertTrue(reorders.isEmpty())
    }

    @Test fun `pointer cancellation restores preview order and never opens delete confirmation`() {
        render()
        val top = compose.onNodeWithTag("category-card-a").fetchSemanticsNode().boundsInRoot.top
        hold("a")
        moveTo(pastCard("c"))
        cancel()
        assertEquals(top, compose.onNodeWithTag("category-card-a").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("category-drag-preview").assertDoesNotExist()
        hold("a")
        moveTo(compose.onNodeWithTag("category-delete-zone").fetchSemanticsNode().boundsInRoot.center)
        cancel()
        compose.onNodeWithText("删除分类“代数”？").assertDoesNotExist()
        assertTrue(reorders.isEmpty())
        assertTrue(deleted.isEmpty())
    }

    @Test fun `transparent curve corner returns the card without a delete or reorder`() {
        render()
        hold("a")
        val zone = compose.onNodeWithTag("category-delete-zone").fetchSemanticsNode().boundsInRoot
        moveTo(Offset(zone.left + zone.width * 0.01f, zone.top + zone.height * 0.02f))
        compose.onNodeWithText("松手后确认删除分类").assertDoesNotExist()
        release()
        compose.onNodeWithText("删除分类“代数”？").assertDoesNotExist()
        assertTrue(reorders.isEmpty())
        assertTrue(deleted.isEmpty())
    }

    @Test fun `busy and source removal during a hold cannot produce a late mutation`() {
        render()
        hold("a")
        moveTo(pastCard("c"))
        compose.runOnIdle { busy.value = true }
        release()
        assertTrue(reorders.isEmpty())
        compose.runOnIdle { busy.value = false }
        hold("a")
        moveTo(compose.onNodeWithTag("category-delete-zone").fetchSemanticsNode().boundsInRoot.center)
        compose.runOnIdle { categories.value = categories.value.filterNot { it.id == "a" } }
        release()
        compose.onNodeWithText("删除分类“代数”？").assertDoesNotExist()
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `name area does not drag and pencil edits the name inside its original card`() {
        render()
        compose.onNodeWithText("代数").performTouchInput { longClick() }
        compose.onNodeWithTag("category-drag-preview").assertDoesNotExist()
        compose.onNodeWithContentDescription("改名 代数").performTouchInput { click() }
        compose.onNodeWithText("分类改名").assertDoesNotExist()
        compose.onNodeWithTag("category-card-b").assertIsDisplayed()
        compose.onNodeWithTag("category-delete-zone").assertDoesNotExist()
        val editor = compose.onNodeWithTag("category-name-editor")
        editor.assert(hasAnyAncestor(hasTestTag("category-card-a"))).assertTextContains("代数")
        editor.performTextReplacement("   ")
        compose.onNodeWithContentDescription("保存分类名称").assertIsNotEnabled()
        editor.performTextReplacement("  线性代数  ")
        editor.performImeAction()
        assertEquals(listOf("a" to "线性代数"), renamed)
        compose.onNodeWithTag("category-name-editor").assertDoesNotExist()
        compose.onNodeWithContentDescription("改名 几何").performTouchInput { click() }
        compose.onNodeWithTag("category-name-editor").performTextReplacement("取消的草稿")
        compose.onNodeWithContentDescription("取消改名").performClick()
        assertEquals(1, renamed.size)
        compose.onNodeWithText("几何").assertExists()
        compose.onNodeWithText("新建分类").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("  错题  ")
        compose.onNodeWithText("保存").performClick()
        assertEquals(listOf("错题"), created)
    }

    @Test fun `accessibility actions reorder and delete without any pointer drag`() {
        render()
        invokeAction("b", "上移 几何")
        assertEquals(listOf("b" to -1), reorders)
        compose.runOnIdle { busy.value = true }
        compose.runOnIdle { busy.value = false }
        invokeAction("c", "删除分类 概率")
        assertTrue(deleted.isEmpty())
        compose.onNodeWithText("删除分类", substring = false).performClick()
        assertEquals(listOf("c"), deleted)
    }

    @Test fun `delete zone appears only while dragging without reducing the list and keeps thirty percent height`() {
        render()
        compose.onNodeWithTag("category-delete-zone").assertDoesNotExist()
        val manager = compose.onNodeWithTag("category-manager").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("category-list").fetchSemanticsNode().boundsInRoot
        val pencil = compose.onNodeWithContentDescription("改名 代数").fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithTag("category-handle-a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(pencil.right <= handle.left + 1f)
        hold("a")
        val zone = compose.onNodeWithTag("category-delete-zone").fetchSemanticsNode().boundsInRoot
        assertEquals(manager.height * 0.3f, zone.height, 1f)
        assertEquals(manager.bottom, zone.bottom, 1f)
        assertEquals(list, compose.onNodeWithTag("category-list").fetchSemanticsNode().boundsInRoot)
        cancel()
        compose.onNodeWithTag("category-delete-zone").assertDoesNotExist()
    }

    @Test fun `category card layout and curved zone remain legible in chalk theme`() {
        var view: View? = null
        compose.setContent { MogeTheme(Appearance.CHALK) {
            view = LocalView.current
            Surface(Modifier.fillMaxSize()) {
                CategoryManagerContent(categories.value, false, {}, { _, _ -> }, { _, _ -> }, {}, {})
            }
        } }
        compose.onNodeWithText("长按右侧三条线拖动排序").assertIsDisplayed()
        compose.onNodeWithTag("category-card-d").assertIsDisplayed()
        val card = compose.onNodeWithTag("category-card-a").fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithTag("category-handle-a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(handle.bottom <= card.bottom && handle.right <= card.right)
        compose.runOnIdle {
            val root = requireNotNull(view).rootView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val output = File("build/ui-refinement-previews/category-manager.png")
                output.parentFile?.mkdirs()
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun `unchanged repository and skipped busy emission reconcile after bounded wait without retry`() {
        render(saveStartsBusy = false)
        invokeAction("b", "上移 几何")
        assertEquals(listOf("b" to -1), reorders)
        // No repository update and no busy emission: the effect must restore the actual order.
        compose.mainClock.advanceTimeBy(CATEGORY_PENDING_RECONCILE_MS + 300L)
        compose.waitForIdle()
        assertEquals(1, reorders.size)
        compose.onNodeWithTag("category-manager").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "可以拖动排序"))
        compose.onNodeWithTag("category-list").performScrollToIndex(0)
        val a = compose.onNodeWithTag("category-card-a").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithTag("category-card-b").fetchSemanticsNode().boundsInRoot
        assertTrue("Expected repository order after reconciliation: a=$a, b=$b", a.top < b.top)
        invokeAction("b", "下移 几何")
        assertEquals(listOf("b" to -1, "b" to 1), reorders)
    }
}
