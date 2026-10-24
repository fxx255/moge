package com.moge.app.ui.settings

import android.app.Application
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.moge.app.data.prefs.*
import com.moge.app.ui.markdown.MarkdownChunk
import com.moge.app.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class LanguageFontSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun changingInterfaceLanguageKeepsAnswerLanguageAndPageState() {
        var prefs by mutableStateOf(UserSettings(answerLanguage = AppLanguage.JAPANESE))
        var mounts = 0
        compose.setContent {
            MogeTheme(language = prefs.interfaceLanguage, font = prefs.font) {
                DisposableEffect(Unit) { mounts++; onDispose { mounts-- } }
                LanguageFontSettingsSection(prefs,
                    onInterfaceLanguage = { prefs = prefs.copy(interfaceLanguage = it) },
                    onAnswerLanguage = { prefs = prefs.copy(answerLanguage = it) },
                    onFont = { prefs = prefs.copy(font = it) })
            }
        }
        fun option(name: String) = compose.onNode(hasText(name) and hasAnyAncestor(isPopup()))
        compose.onNodeWithTag("interface-language-selector").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        option("English").performScrollTo().performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithText("App language").assertExists()
        compose.onNodeWithText("Answer language").assertExists()
        compose.onNodeWithText("日本語").assertExists()
        compose.runOnIdle {
            assertEquals(AppLanguage.JAPANESE, prefs.answerLanguage)
            assertEquals(1, mounts)
        }
        compose.onNodeWithTag("answer-language-selector").performClick()
        option("繁體中文").performClick()
        compose.onNodeWithText("App language").assertExists()
        compose.runOnIdle { assertEquals(AppLanguage.TRADITIONAL_CHINESE, prefs.answerLanguage) }
        compose.onNodeWithTag("font-selector").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        option("System default").assertIsSelected()
        option("Resource Han Rounded").assertExists()
        option("LXGW WenKai").performScrollTo().performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(AppFont.WENKAI, prefs.font)
            assertEquals(AppLanguage.ENGLISH, prefs.interfaceLanguage)
            assertEquals(AppLanguage.TRADITIONAL_CHINESE, prefs.answerLanguage)
        }
    }

    @Test fun allFontsLoadAndApplyToNativeMarkdown() {
        var selected by mutableStateOf(AppFont.SYSTEM)
        var applied: Typeface? = null
        compose.setContent {
            MogeTheme(font = selected) {
                applied = LocalAppTypeface.current
                MarkdownChunk("简体 繁體 日本語 English", fixedWidthPx = null)
            }
        }
        for (font in AppFont.entries) {
            compose.runOnIdle { selected = font }
            compose.waitForIdle()
            compose.runOnIdle {
                val expected = appTypeface(compose.activity, font)
                assertEquals(expected, applied)
                fun nativeViews(view: android.view.View): List<android.widget.TextView> =
                    if (view is android.widget.TextView) listOf(view)
                    else if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { nativeViews(view.getChildAt(it)) }
                    else emptyList()
                val markdown = nativeViews(compose.activity.window.decorView).first { it.text.contains("简体") }
                assertEquals(expected, markdown.typeface)
                if (font != AppFont.SYSTEM) assertNotEquals(Typeface.DEFAULT, expected)
            }
        }
    }
}
