package com.moge.app.ui.photo

import com.moge.app.data.image.PhotoCropEdit
import com.moge.app.data.image.applyPhotoCrop
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.shape.RoundedCornerShape

/** 可移动、可缩放的自由裁剪器；宽高始终可以独立调整。 */
@Composable
fun PhotoCropDialog(
    path: String,
    onCropped: () -> Unit,
    onDismiss: () -> Unit,
    /** 拍题流程里叫「重拍」；其余场景是「取消」。 */
    dismissLabel: String = "取消",
    onCropStarted: suspend () -> Unit = {},
    onCropClosed: () -> Unit = {},
    saveCrop: (suspend (PhotoCropEdit) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val editor = remember(path) { CropViewportState(scope) }
    val motionEnabled = com.moge.app.ui.theme.MogeTheme.motionEnabled
    var quarterTurns by rememberSaveable(path) { mutableIntStateOf(0) }
    val sourcePreview = remember(path) { decodeUprightPhoto(path, 1600) }
    val preview = remember(sourcePreview, quarterTurns) {
        sourcePreview?.let { runCatching { rotatePhotoBitmap(it, quarterTurns) }.getOrNull() }
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val viewportMaxSide = cropViewportMaxSide()
    val viewport = editor.viewport
    val cropRect = editor.cropRect
    LaunchedEffect(path) { onCropStarted() }
    DisposableEffect(path) { onDispose { onCropClosed() } }
    LaunchedEffect(path, preview) {
        editor.reset(preview?.let { IntSize(it.width, it.height) } ?: IntSize.Zero)
        error = null
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surface) {
          Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(
                // 小屏/分屏兜底：内容超高时可滚动（裁剪框内的手势有自己的 pointerInput，
                // 会消费掉拖动事件，不会被滚动条抢走）。按钮行固定在滚动区外，横屏矮屏也始终看得见。
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("裁剪照片", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(enabled = preview != null && !saving, onClick = {
                        editor.interrupt()
                        quarterTurns = (quarterTurns + 3) % 4
                        error = null
                    }) {
                        Icon(Icons.Filled.RotateLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("逆时针 90°")
                    }
                }
                Text(
                    "双指缩放/平移照片；拖动框内移动选框，拖动边角自由改变宽高",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (preview != null) {
                    PhotoCropViewport(preview, editor, saving, motionEnabled,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            // Limit width before filling, including room for the edge handles.
                            .sizeIn(maxWidth = viewportMaxSide, maxHeight = viewportMaxSide)
                            .fillMaxWidth()
                            .aspectRatio(1f))
                } else {
                    Text("照片加载失败", color = MaterialTheme.colorScheme.error)
                }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text(dismissLabel) }
                    TextButton(
                        enabled = preview != null && cropRect != Rect.Zero && viewport != IntSize.Zero && !saving,
                        onClick = {
                            if (saving) return@TextButton
                            val sourcePreview = preview ?: return@TextButton
                            editor.interrupt()
                            val selected = editor.framing
                            val wholePhoto = isWholePhotoSelected(IntSize(sourcePreview.width, sourcePreview.height), viewport, selected)
                            if (wholePhoto && quarterTurns == 0 && saveCrop == null) {
                                onCropped()
                                return@TextButton
                            }
                            saving = true
                            error = null
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        val edit = if (wholePhoto) PhotoCropEdit(quarterTurns) else cropSelectionEdit(
                                            IntSize(sourcePreview.width, sourcePreview.height), viewport,
                                            selected.zoom, selected.imageOffset, selected.cropRect, quarterTurns)
                                        if (saveCrop != null) saveCrop(edit) else applyPhotoCrop(path, edit)
                                    }
                                    PhotoEdits.changed()
                                    onCropped()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: "裁剪保存失败，请重试"
                                } catch (_: OutOfMemoryError) {
                                    error = "内存不足以保存原始分辨率，请选取较小区域后重试"
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    ) { Text(if (saving) "保存中…" else "确定") }
                }
            }
        }
    }
}

/**
 * 裁剪视口（正方形）的最大边长：按窗口高度减去标题、提示和按钮来算，
 * 否则横屏下边长跟着屏宽走，弹窗比屏幕还高。极端小屏兜底 200dp。
 */
@Composable
private fun cropViewportMaxSide(): Dp {
    val configuration = LocalConfiguration.current
    // 标题 + 提示 + 固定按钮行 + 弹窗边距，约 190dp；横屏矮屏（如 540px）兜底 160dp。
    val usableHeight = configuration.screenHeightDp.dp - 190.dp
    return minOf(configuration.screenWidthDp.dp, usableHeight).coerceAtLeast(160.dp)
}
