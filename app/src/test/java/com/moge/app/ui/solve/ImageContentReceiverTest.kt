package com.moge.app.ui.solve

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ImageContentReceiverTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `image items are consumed once while text documents and links remain available`() {
        val image = ClipboardImageFixture(compose.activity)
        val document = ClipboardImageFixture(compose.activity, mime = "application/pdf")
        val clip = ClipData("混合内容", arrayOf("image/png", "text/plain"), ClipData.Item(image.uri)).apply {
            addItem(ClipData.Item("普通文字"))
            addItem(ClipData.Item(document.uri))
            addItem(ClipData.Item(Uri.parse("https://example.com/image.png")))
            addItem(ClipData.Item(image.uri))
        }
        assertEquals(listOf(image.uri), paste(clip, "问题普通文字"))
    }

    @Test fun `declared image still pastes when metadata is inaccessible`() {
        val image = ClipboardImageFixture(compose.activity, denyMetadata = true)
        val clip = ClipData("图片", arrayOf("image/png"), ClipData.Item(image.uri))
        assertEquals(listOf(image.uri), paste(clip, "问题"))
    }

    @Test fun `plain copied image address stays plain text`() {
        val clip = ClipData.newPlainText("地址", "https://example.com/image.png")
        assertTrue(paste(clip, "问题https://example.com/image.png").isEmpty())
    }

    private fun paste(clip: ClipData, expectedText: String): List<Uri> {
        val text = mutableStateOf("问题")
        var images = emptyList<Uri>()
        compose.setContent { MogeTheme {
            ComposerTextField(text.value, { text.value = it }, "输入问题", { images = it })
        } }
        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        compose.runOnIdle {
            (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        }
        field.performSemanticsAction(SemanticsActions.PasteText) { it() }
        field.assertTextEquals(expectedText)
        compose.runOnIdle { assertEquals(expectedText, text.value) }
        return images
    }
}
