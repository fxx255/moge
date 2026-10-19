package com.moge.app.ui.notebook

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.theme.LocalPaperMotionEnabled
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w480dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CategoryCreationKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var view: View
    private var shows = 0
    private val motion = mutableStateOf(true)
    private val names = (0..23).map { NotebookCategoryEntity(id = "item$it", name = "分类 $it") }
    private val created = mutableListOf<String>()

    private fun render(appearance: Appearance) {
        WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        val keyboard = object : SoftwareKeyboardController {
            override fun show() { shows++ }
            override fun hide() = Unit
        }
        compose.setContent { MogeTheme(appearance) {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard,
                LocalPaperMotionEnabled provides motion.value) {
                view = LocalView.current
                CategoryManagerContent(names, false, { created += it }, { _, _ -> }, { _, _ -> }, {}, {},
                    modifier = Modifier.fillMaxSize().imePadding(), listState = rememberLazyListState())
            }
        } }
    }

    private fun keyboardHeight(dp: Int) {
        compose.runOnIdle {
            val bottom = (dp * view.resources.displayMetrics.density).toInt()
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
                .setVisible(WindowInsetsCompat.Type.ime(), bottom > 0).build())
        }
    }

    @Test fun `typing and keyboard resize keep one focused draft without repeating the keyboard request`() {
        render(Appearance.PAPER)
        compose.onNodeWithText("新建分类").performClick()
        val editor = compose.onNodeWithTag("category-name-editor")
        editor.assertIsDisplayed().assertIsFocused()
        for (height in listOf(80, 160, 260, 340)) {
            keyboardHeight(height)
            compose.mainClock.advanceTimeBy(32)
        }
        editor.performTextInput("测试分类")
        editor.assertIsFocused().assertTextContains("测试分类")
        assertEquals("Focus and keyboard are requested once per draft", 1, shows)
        val draft = compose.onNodeWithTag("category-draft").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("category-list").fetchSemanticsNode().boundsInRoot
        assertTrue("Editing must not preserve the drag-delete spacer", viewport.bottom - draft.bottom < draft.height / 2)
        compose.onNodeWithContentDescription("取消新建").performClick()
        compose.onNodeWithTag("category-draft").assertDoesNotExist()
        assertTrue(created.isEmpty())
        compose.onNodeWithText("新建分类").performClick()
        editor.assertIsDisplayed().assertIsFocused()
        assertEquals("A fresh draft can still open the keyboard", 2, shows)
    }

    @Test fun `draft stays fully above the keyboard without a second scroll after resizing finishes`() {
        render(Appearance.CHALK)
        motion.value = false
        compose.onNodeWithText("新建分类").performClick()
        val fullHeight = compose.onNodeWithTag("category-manager").fetchSemanticsNode().boundsInRoot.height
        for (height in listOf(80, 160, 260, 340)) {
            keyboardHeight(height)
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
        }
        compose.onNodeWithTag("category-name-editor").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText("保存").assertIsDisplayed()
        val manager = compose.onNodeWithTag("category-manager").fetchSemanticsNode().boundsInRoot
        val draft = compose.onNodeWithTag("category-draft").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("category-list").fetchSemanticsNode().boundsInRoot
        assertTrue("Test must really resize the content above the keyboard", manager.height < fullHeight - 200)
        assertTrue("The entire edit card must remain above the keyboard", draft.bottom <= viewport.bottom)
        assertTrue("Do not leave the drag-delete spacer under the editor", viewport.bottom - draft.bottom < draft.height / 2)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals("No delayed second movement after the IME settles", draft,
            compose.onNodeWithTag("category-draft").fetchSemanticsNode().boundsInRoot)
        assertEquals(1, shows)
        compose.onNodeWithTag("category-name-editor").performTextInput("考前复习")
        compose.onNodeWithTag("category-name-editor").performImeAction()
        assertEquals(listOf("考前复习"), created)
    }
}
