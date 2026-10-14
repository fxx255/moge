package com.moge.app.ui.viewer

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.moge.app.ui.markdown.LocalFigurePathResolver
import com.moge.app.ui.photo.rotatePhotoAndSave
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.ui.theme.MonoFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏看图：左右翻页、双指 / 双击缩放、下拉关闭，顶栏可保存到相册与系统分享。
 *
 * @param rotatable 用户照片可以逆时针旋转 90° 并原地保存（拍歪了能纠正，之后送模型的也是转正后的图）；
 *   生成的图表不开放旋转：它会按 spec 重画，旋转结果留不住。
 */
@Composable
fun PhotoViewer(
    paths: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    rotatable: Boolean = false,
) {
    if (paths.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resolver = LocalFigurePathResolver.current
    val dark = MogeTheme.paper.isChalk
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, paths.lastIndex),
        pageCount = { paths.size },
    )

    // 每次操作前都重新解析：用户可能刚在设置里清过图表缓存，或者切换过日夜主题
    fun withCurrentFile(action: suspend (String) -> String?) {
        val stored = paths.getOrNull(pagerState.currentPage) ?: return
        busy = true
        message = null
        scope.launch {
            val result = runCatching {
                val path = withContext(Dispatchers.IO) { resolver.resolve(stored, dark) }
                    ?: error("图片文件不存在")
                action(path)
            }
            busy = false
            message = result.fold({ it }, { it.message ?: "操作失败，请重试" })
        }
    }

    val saveToGallery = {
        withCurrentFile { path ->
            val album = withContext(Dispatchers.IO) { saveImageToGallery(context, path) }
            "已保存到相册 $album"
        }
    }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveToGallery() else message = "没有存储权限，无法保存"
    }

    LaunchedEffect(message) {
        if (message != null) {
            delay(2_500)
            message = null
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black).testTag("photo-viewer"),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !busy,
            ) { page ->
                ZoomableImage(
                    storedPath = paths[page],
                    pageLabel = "图片 ${page + 1}，共 ${paths.size} 张",
                    onTapToClose = { if (!busy) onDismiss() },
                    onSwipeDownToClose = { if (!busy) onDismiss() },
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, enabled = !busy) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (paths.size > 1) {
                        Text(
                            text = "${pagerState.currentPage + 1} / ${paths.size}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = MonoFamily),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    if (rotatable) {
                        IconButton(
                            enabled = !busy,
                            onClick = {
                                withCurrentFile { path ->
                                    withContext(Dispatchers.IO) { rotatePhotoAndSave(path) }
                                    null
                                }
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = "逆时针旋转 90 度", tint = Color.White)
                        }
                    }
                    IconButton(
                        enabled = !busy,
                        onClick = {
                            val needsPermission = galleryNeedsLegacyPermission() &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                                PackageManager.PERMISSION_GRANTED
                            if (needsPermission) {
                                legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else {
                                saveToGallery()
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = "保存到相册", tint = Color.White)
                    }
                    IconButton(
                        enabled = !busy,
                        onClick = {
                            withCurrentFile { path ->
                                shareImage(context, path)
                                null
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "分享", tint = Color.White)
                    }
                }
            }

            message?.let { text ->
                Text(
                    text = text,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(bottom = 32.dp)
                        .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
