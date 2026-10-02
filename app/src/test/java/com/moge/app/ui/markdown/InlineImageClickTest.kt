package com.moge.app.ui.markdown

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 问题①的回归：占位图（PNG 缺失 / 解码失败）也必须能点开查看器，
 * 不允许出现「点了没反应」。此前占位框上没有任何 clickable，
 * 任何一张图走到占位分支就永久失去交互。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class InlineImageClickTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `占位图点击也必须打开查看器`() {
        var clicks = 0
        compose.setContent {
            Column(Modifier.requiredWidth(300.dp)) {
                // 指向不存在的文件：必然走到占位分支
                InlineFigure(path = "/nonexistent/plot-missing.png", onClick = { clicks++ })
            }
        }
        compose.waitForIdle()
        compose.waitForIdle()
        // 占位框位于 (0,0)-(300,110)，点它的中心（down+up 原语 = 一次点按）
        compose.onRoot().performTouchInput {
            down(Offset(150f, 55f))
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("占位图点击必须响应（clicks=$clicks）", clicks >= 1)
        }
    }
}
