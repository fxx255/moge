package com.moge.app.ui.viewer

import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.domain.diagram.DiagramLayoutProfile
import com.moge.app.domain.diagram.DiagramSpec
import com.moge.app.domain.diagram.LfsrSpec
import com.moge.app.ui.figure.DiagramRenderer
import com.moge.app.ui.theme.MogeTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real Android gallery provider and the actual viewer save action. */
@RunWith(AndroidJUnit4::class)
class PhotoViewerGalleryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun galleryImages(): Set<Uri> = buildSet {
        app.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf("Pictures/Moge/"), null)?.use { cursor ->
            while (cursor.moveToNext()) add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)))
        }
    }

    @Test fun whiteFigureKeepsTopActionsReadable() {
        val photo = File(app.cacheDir, "white-figure-actions.png")
        val bitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
        }
        try { photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        compose.setContent { MogeTheme { PhotoViewer(listOf(photo.path), 0, {}, preferProcessed = false) } }
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("图片 1，共 1 张").fetchSemanticsNodes().isNotEmpty() }
            val shot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                File(app.getExternalFilesDir(null), "white-figure-actions.png").outputStream().use {
                    shot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                val image = compose.onNodeWithContentDescription("图片 1，共 1 张").fetchSemanticsNode().boundsInRoot
                for (description in listOf("保存到相册", "分享")) {
                    val bounds = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
                    assertTrue("$description stays outside the image: toolbar=$bounds image=$image", bounds.bottom <= image.top)
                    assertTrue("$description keeps white foreground on the black toolbar",
                        (bounds.left.toInt() until bounds.right.toInt()).any { x ->
                        (bounds.top.toInt() until bounds.bottom.toInt()).any { y -> Color(shot.getPixel(x, y)).red > 0.9f }
                    })
                }
            } finally { shot.recycle() }
        } finally { photo.delete() }
    }

    @Test fun repeatedSaveAndReopenKeepOneCopyAndDeletedPhotosCanBeSavedAgain() {
        val before = galleryImages()
        val receiptPrefs = app.getSharedPreferences("gallery_save_receipts_v1", 0)
        val beforeReceipts = receiptPrefs.all.keys.toSet()
        val photo = File(app.cacheDir, "gallery-lfsr-device.png")
        val bitmap = DiagramRenderer.render(DiagramSpec("三阶 LFSR · 第 1、3 级输出反馈", profile = DiagramLayoutProfile.LFSR,
            lfsr = LfsrSpec(stages = 3, taps = listOf(1, 3), clockLabel = "")))
        try {
            // Make this fixture unique without changing the textbook diagram's visible structure.
            bitmap.setPixel(1, 1, System.nanoTime().toInt() or 0xFF000000.toInt())
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        var open by mutableStateOf(true)
        compose.setContent { MogeTheme { if (open) PhotoViewer(listOf(photo.path), 0, { open = false }, preferProcessed = false) } }
        val save = compose.onNodeWithContentDescription("保存到相册")
        fun savedActionFinished() = !save.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("图片 1，共 1 张").fetchSemanticsNodes().isNotEmpty() }
            save.performClick()
            compose.waitUntil(10_000) { (galleryImages() - before).size == 1 }
            compose.waitUntil(10_000) { savedActionFinished() }
            repeat(3) {
                save.performClick()
                compose.waitUntil(5_000) { savedActionFinished() && compose.onAllNodesWithText("这张图片已保存到图库，无需重复保存").fetchSemanticsNodes().isNotEmpty() }
            }
            assertEquals(1, (galleryImages() - before).size)
            compose.runOnIdle { open = false }
            compose.runOnIdle { open = true }
            save.performClick()
            compose.waitUntil(5_000) { savedActionFinished() && compose.onAllNodesWithText("这张图片已保存到图库，无需重复保存").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, (galleryImages() - before).size)
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { shot ->
                try { File(app.getExternalFilesDir(null), "lfsr-gallery-duplicate.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { shot.recycle() }
            }
            val first = (galleryImages() - before).single()
            app.contentResolver.delete(first, null, null)
            save.performClick()
            compose.waitUntil(10_000) { (galleryImages() - before).size == 1 }
            compose.waitUntil(10_000) { savedActionFinished() }
            assertNotEquals(first, (galleryImages() - before).single())
        } finally {
            compose.runOnIdle { open = false }
            (galleryImages() - before).forEach { app.contentResolver.delete(it, null, null) }
            receiptPrefs.edit().apply { (receiptPrefs.all.keys - beforeReceipts).forEach { remove(it) } }.commit()
            photo.delete()
        }
    }
}
