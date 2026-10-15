package com.moge.app.ui.photo

import android.graphics.Bitmap
import android.graphics.Rect as PixelRect
import com.moge.app.data.image.decodePhotoCrop
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape

/** 可移动、可缩放的自由裁剪器；宽高始终可以独立调整。 */
@Composable
fun PhotoCropDialog(
    path: String,
    onCropped: () -> Unit,
    onDismiss: () -> Unit,
    /** 拍题流程里叫「重拍」；其余场景是「取消」。 */
    dismissLabel: String = "取消",
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
    val zoom = editor.zoom
    val imageOffset = editor.imageOffset
    val cropRect = editor.cropRect
    LaunchedEffect(path, preview) {
        editor.reset(preview?.let { IntSize(it.width, it.height) } ?: IntSize.Zero)
        error = null
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
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
                    val baseScale = if (viewport == IntSize.Zero) 1f else max(
                        viewport.width.toFloat() / preview.width,
                        viewport.height.toFloat() / preview.height,
                    )
                    val displayedWidth = preview.width * baseScale * zoom
                    val displayedHeight = preview.height * baseScale * zoom
                    val displayedLeft = (viewport.width - displayedWidth) / 2f + imageOffset.x
                    val displayedTop = (viewport.height - displayedHeight) / 2f + imageOffset.y

                    Box(
                        modifier = Modifier
                            // 约束顺序很关键：sizeIn 必须在 fillMaxWidth 之前——
                            // 反过来 fillMaxWidth 会先把宽度钉死成父容器宽度，
                            // 后面的 max 形同虚设（v1.0.5 弹窗上下被裁的根因）。
                            .sizeIn(maxWidth = viewportMaxSide, maxHeight = viewportMaxSide)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black)
                            .onSizeChanged(editor::resize)
                            .cropGestures(editor, saving, motionEnabled),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Draw in viewport pixels so the preview and crop mapping use exactly the same transform.
                        // A sized Image child is constrained back to the square parent and would distort portrait images.
                        Canvas(Modifier.matchParentSize()) {
                            drawImage(
                                image = preview.asImageBitmap(),
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(preview.width, preview.height),
                                dstOffset = IntOffset(displayedLeft.roundToInt(), displayedTop.roundToInt()),
                                dstSize = IntSize(
                                    displayedWidth.roundToInt().coerceAtLeast(1),
                                    displayedHeight.roundToInt().coerceAtLeast(1),
                                ),
                            )
                        }
                        Canvas(Modifier.matchParentSize()) {
                            if (cropRect == Rect.Zero) return@Canvas
                            val shade = Color.Black.copy(alpha = 0.58f)
                            val box = cropRect
                            drawRect(shade, Offset.Zero, Size(size.width, box.top))
                            drawRect(shade, Offset(0f, box.bottom), Size(size.width, size.height - box.bottom))
                            drawRect(shade, Offset(0f, box.top), Size(box.left, box.height))
                            drawRect(shade, Offset(box.right, box.top), Size(size.width - box.right, box.height))

                            val gridColor = Color.White.copy(alpha = 0.55f)
                            drawRect(
                                Color.White,
                                topLeft = box.topLeft,
                                size = box.size,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                            )
                            drawLine(gridColor, Offset(box.left + box.width / 3f, box.top), Offset(box.left + box.width / 3f, box.bottom), 1.dp.toPx())
                            drawLine(gridColor, Offset(box.left + box.width * 2f / 3f, box.top), Offset(box.left + box.width * 2f / 3f, box.bottom), 1.dp.toPx())
                            drawLine(gridColor, Offset(box.left, box.top + box.height / 3f), Offset(box.right, box.top + box.height / 3f), 1.dp.toPx())
                            drawLine(gridColor, Offset(box.left, box.top + box.height * 2f / 3f), Offset(box.right, box.top + box.height * 2f / 3f), 1.dp.toPx())
                            listOf(box.topLeft, box.topRight, box.bottomLeft, box.bottomRight).forEach { corner ->
                                drawCircle(Color.White, radius = 7.dp.toPx(), center = corner)
                                drawCircle(Color.Black.copy(alpha = 0.45f), radius = 3.dp.toPx(), center = corner)
                            }
                            val halfHandle = 12.dp.toPx()
                            val handleStroke = 5.dp.toPx()
                            drawLine(Color.White, Offset(box.center.x - halfHandle, box.top), Offset(box.center.x + halfHandle, box.top), handleStroke)
                            drawLine(Color.White, Offset(box.center.x - halfHandle, box.bottom), Offset(box.center.x + halfHandle, box.bottom), handleStroke)
                            drawLine(Color.White, Offset(box.left, box.center.y - halfHandle), Offset(box.left, box.center.y + halfHandle), handleStroke)
                            drawLine(Color.White, Offset(box.right, box.center.y - halfHandle), Offset(box.right, box.center.y + halfHandle), handleStroke)
                        }
                    }

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
                            val sourcePreview = preview ?: return@TextButton
                            editor.interrupt()
                            val selected = editor.framing
                            val wholePhoto = isWholePhotoSelected(IntSize(sourcePreview.width, sourcePreview.height), viewport, selected)
                            if (wholePhoto && quarterTurns == 0) {
                                onCropped()
                                return@TextButton
                            }
                            saving = true
                            error = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        if (wholePhoto) {
                                            if (quarterTurns != 0) rotatePhotoAndSave(path, quarterTurns)
                                        } else cropAndSave(
                                            path,
                                            sourcePreview,
                                            viewport,
                                            selected.zoom,
                                            selected.imageOffset,
                                            selected.cropRect,
                                            quarterTurns,
                                        )
                                    }
                                }
                                saving = false
                                result.onSuccess { onCropped() }
                                    .onFailure { error = it.message ?: "裁剪保存失败，请重试" }
                            }
                        },
                    ) { Text(if (saving) "保存中…" else "确定") }
                }
            }
        }
    }
}

internal fun cropAndSave(
    path: String,
    preview: Bitmap,
    viewport: IntSize,
    zoom: Float,
    imageOffset: Offset,
    cropRect: Rect,
    quarterTurns: Int = 0,
) {
    val cropped = decodePhotoCrop(path, quarterTurns) { width, height ->
        val bounds = cropBoundsInSource(
            previewSize = IntSize(preview.width, preview.height), sourceSize = IntSize(width, height),
            viewport = viewport, zoom = zoom, imageOffset = imageOffset, cropRect = cropRect,
        )
        PixelRect(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height)
    } ?: error("无法读取照片，图片可能过大或格式不受支持")
    try { savePhotoBitmap(path, cropped) } finally { cropped.recycle() }
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
