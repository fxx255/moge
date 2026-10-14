package com.moge.app.ui.settings

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.MainActivity
import com.moge.app.data.prefs.Appearance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the real settings, navigation and lifecycle, preserving the device's preferences. */
@RunWith(AndroidJUnit4::class)
class ConversationScreenOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun hasScreenOnFlag(): Boolean {
        var enabled = false
        fun ownsFlag(view: View): Boolean = view.keepScreenOn || view is ViewGroup && (0 until view.childCount).any { ownsFlag(view.getChildAt(it)) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            enabled = ownsFlag(compose.activity.window.decorView)
        }
        return enabled
    }

    @Test fun settingKeepsOnlyVisibleConversationAwakeAndSurvivesRestart() {
        val original = runBlocking { compose.activity.settings.current() }
        try {
            runBlocking { compose.activity.settings.setKeepScreenOnInConversation(false) }
            compose.waitUntil(5000) { !hasScreenOnFlag() }
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithText("对话中屏幕常亮").performScrollTo().assertIsOff().performClick().assertIsOn()
            assertFalse("Settings must not retain the conversation screen flag", hasScreenOnFlag())
            for ((name, theme) in listOf("paper" to Appearance.PAPER, "chalk" to Appearance.CHALK)) {
                runBlocking { compose.activity.settings.setAppearance(theme) }
                compose.waitForIdle()
                val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(compose.activity.getExternalFilesDir(null), "screen-on-setting-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(5000) { hasScreenOnFlag() }
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            compose.waitUntil(5000) { !hasScreenOnFlag() }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(5000) { hasScreenOnFlag() }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(5000) { hasScreenOnFlag() }
            compose.onNodeWithContentDescription("设置").performClick()
            compose.waitUntil(5000) { !hasScreenOnFlag() }
            compose.onNodeWithText("对话中屏幕常亮").performScrollTo().assertIsOn().performClick().assertIsOff()
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(5000) { !hasScreenOnFlag() }
        } finally {
            runBlocking {
                compose.activity.settings.setKeepScreenOnInConversation(original.keepScreenOnInConversation)
                compose.activity.settings.setAppearance(original.appearance)
            }
        }
    }
}
