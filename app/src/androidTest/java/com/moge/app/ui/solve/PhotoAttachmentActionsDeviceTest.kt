package com.moge.app.ui.solve

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.capture.ConfirmSheet
import com.moge.app.ui.components.GridPaper
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Overlaid actions must leave the photo centre available for previewing. */
@RunWith(AndroidJUnit4::class)
class PhotoAttachmentActionsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun previewDeleteAndCropKeepSeparateTouchTargetsInBothThemes() {
        val photo = File(compose.activity.cacheDir, "photo-actions.png")
        val bitmap = Bitmap.createBitmap(320, 400, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(237, 228, 206))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 32f }
            canvas.drawText("x + y = t", 24f, 80f, paint)
            canvas.drawText("x - y = m", 24f, 150f, paint)
            canvas.drawText("dx dy = 1/2 dt dm", 24f, 220f, paint)
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        var appearance by mutableStateOf(Appearance.PAPER)
        var confirming by mutableStateOf(false)
        var paths by mutableStateOf(listOf(photo.absolutePath))
        var opened = 0
        var removed = 0
        var cropped = 0
        compose.setContent { MogeTheme(appearance) {
            GridPaper(Modifier.fillMaxSize()) {
                if (!confirming) Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                    FollowUpBar(SolveUiState(photos = paths), {}, {}, {}, {}, {},
                        onOpenPendingPhoto = { opened++ },
                        onRemovePendingPhoto = { removed++; paths = paths - it })
                }
                if (confirming) ConfirmSheet(paths, "", false, {},
                    { removed++; paths = paths - it }, { opened++ }, {}, {}, {},
                    onCropPhoto = { cropped++ })
            }
        } }
        val density = compose.activity.resources.displayMetrics.density
        try {
            for (theme in listOf(Appearance.PAPER, Appearance.CHALK)) {
                compose.runOnIdle { appearance = theme; paths = listOf(photo.absolutePath) }
                for (confirm in listOf(false, true)) {
                    compose.runOnIdle { confirming = confirm; paths = listOf(photo.absolutePath) }
                    compose.waitForIdle()
                    val previewDescription = if (confirm) "第 1 张照片" else "待发的第 1 张照片"
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithContentDescription(previewDescription).fetchSemanticsNodes().isNotEmpty()
                    }
                    val preview = compose.onNodeWithContentDescription(previewDescription)
                    val delete = compose.onNodeWithContentDescription("删掉第 1 张照片")
                    val previewBounds = preview.fetchSemanticsNode().boundsInRoot
                    val deleteBounds = delete.fetchSemanticsNode().boundsInRoot
                    assertTrue("Delete must stay at the upper right", deleteBounds.top < previewBounds.center.y &&
                        deleteBounds.center.x > previewBounds.center.x)
                    assertTrue("Keep a 48dp touch target", deleteBounds.width >= 48f * density - 1f &&
                        deleteBounds.height >= 48f * density - 1f)
                    val openBefore = opened
                    preview.performTouchInput { click(center) }
                    compose.runOnIdle { assertEquals(openBefore + 1, opened) }
                    if (confirm) {
                        val cropBefore = cropped
                        compose.onNodeWithContentDescription("裁剪第 1 张照片").performTouchInput { click(center) }
                        compose.runOnIdle { assertEquals(cropBefore + 1, cropped) }
                    }
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        File(instrumentation.targetContext.getExternalFilesDir(null),
                            "photo-actions-${theme.name.lowercase()}-${if (confirm) "confirm" else "composer"}.png")
                            .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { screenshot.recycle() }
                    val removeBefore = removed
                    // Hit the visible badge rather than invoking a semantics-only click.
                    delete.performTouchInput { click(topRight + androidx.compose.ui.geometry.Offset(-15f * density, 15f * density)) }
                    compose.runOnIdle {
                        assertEquals(removeBefore + 1, removed)
                        assertTrue(paths.isEmpty())
                        confirming = false
                    }
                    compose.waitForIdle()
                }
            }
        } finally { photo.delete() }
    }
}
