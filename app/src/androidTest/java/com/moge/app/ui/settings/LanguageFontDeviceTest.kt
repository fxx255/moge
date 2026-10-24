package com.moge.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moge.app.data.prefs.*
import com.moge.app.ui.solve.UserQuestionText
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android resources, bundled font loading and state retention on MuMu 12-2. */
@RunWith(AndroidJUnit4::class)
class LanguageFontDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fourLanguagesAndFontsKeepTheDraftAtLargeFontScale() {
        var prefs by mutableStateOf(UserSettings(answerLanguage = AppLanguage.ENGLISH))
        var scale by mutableFloatStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                MogeTheme(language = prefs.interfaceLanguage, font = prefs.font, appearance = prefs.appearance) {
                    var draft by rememberSaveable { mutableStateOf("") }
                    Column {
                        LanguageFontSettingsSection(prefs,
                            onInterfaceLanguage = { prefs = prefs.copy(interfaceLanguage = it) },
                            onAnswerLanguage = { prefs = prefs.copy(answerLanguage = it) },
                            onFont = { prefs = prefs.copy(font = it) })
                        OutlinedTextField(draft, onValueChange = { draft = it }, modifier = Modifier.testTag("draft"))
                        UserQuestionText("简体 繁體 日本語 English\n\$\$x^2+y^2=1\$\$", showSourceCopy = false)
                    }
                }
            }
        }
        compose.onNodeWithTag("draft").performTextInput("Keep my draft")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        fun selectOption(name: String) {
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNode(hasText(name) and hasAnyAncestor(isPopup()))
                .performScrollTo().assertIsDisplayed().performClick()
            compose.onNode(isPopup()).assertDoesNotExist()
        }
        // Use current row label to reach each language, never recreate the Activity.
        compose.onNodeWithText("界面语言").performClick()
        selectOption("繁體中文")
        compose.onNodeWithText("介面語言").assertExists().performClick()
        selectOption("日本語")
        compose.onNodeWithText("表示言語").assertExists().performClick()
        selectOption("English")
        compose.onNodeWithText("App language").assertExists()
        compose.onNodeWithTag("draft").assertTextContains("Keep my draft")
        compose.runOnIdle { assertEquals(AppLanguage.ENGLISH, prefs.answerLanguage) }
        for ((index, name) in listOf("LXGW WenKai", "Source Han Serif", "Resource Han Rounded").withIndex()) {
            compose.onNodeWithText("Font").performClick()
            selectOption(name)
            compose.runOnIdle { assertEquals(AppFont.entries[index + 1], prefs.font) }
            compose.onNodeWithTag("draft").assertTextContains("Keep my draft")
        }
        compose.runOnIdle { scale = 1.6f; prefs = prefs.copy(appearance = Appearance.CHALK) }
        compose.onNodeWithText("Answer language").performClick()
        selectOption("日本語")
        compose.runOnIdle {
            assertEquals(AppLanguage.ENGLISH, prefs.interfaceLanguage)
            assertEquals(AppLanguage.JAPANESE, prefs.answerLanguage)
        }
        compose.onNodeWithTag("draft").assertTextContains("Keep my draft")
    }
}
