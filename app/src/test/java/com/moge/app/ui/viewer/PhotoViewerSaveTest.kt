package com.moge.app.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.moge.app.data.image.GalleryTestProvider
import com.moge.app.ui.theme.MogeTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PhotoViewerSaveTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var media: GalleryTestProvider
    private lateinit var image: File
    private var open by mutableStateOf(true)
    private val button get() = compose.onNodeWithContentDescription("保存到相册")
    private fun awaitReady() = compose.waitUntil(5_000) {
        !button.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
    }

    @Before fun setup() {
        context.getSharedPreferences("gallery_save_receipts_v1", 0).edit().clear().commit()
        media = GalleryTestProvider(context)
        ShadowContentResolver.registerProviderInternal("media", media)
        image = File(context.cacheDir, "viewer-save.png")
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        try { image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        compose.setContent { MogeTheme { if (open) PhotoViewer(listOf(image.path), 0, { open = false }, preferProcessed = false) } }
    }
    @After fun cleanup() { media.writeGate?.countDown(); image.delete() }

    @Test fun `saving again and reopening the photo show already saved without making more copies`() {
        button.performClick()
        compose.waitUntil(5_000) { media.rows.size == 1 }
        awaitReady()
        button.performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("这张图片已保存到图库，无需重复保存").fetchSemanticsNodes().isNotEmpty() }
        awaitReady()
        assertEquals(1, media.inserts.get())
        compose.runOnIdle { open = false }
        compose.runOnIdle { open = true }
        button.performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("这张图片已保存到图库，无需重复保存").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, media.inserts.get())
    }

    @Test fun `saving disables the action and stale repeated click callbacks cannot queue more writes`() {
        val gate = CountDownLatch(1)
        media.writeGate = gate
        button.performClick()
        compose.waitUntil(5_000) { media.writing }
        try {
            button.assertIsNotEnabled()
            repeat(3) { button.performSemanticsAction(SemanticsActions.OnClick) { it() } }
        } finally { gate.countDown() }
        compose.waitUntil(5_000) { media.rows.values.singleOrNull()?.length()?.let { it > 0 } == true }
        awaitReady()
        assertEquals(1, media.inserts.get())
    }
}
