package com.moge.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * 方格纸页面骨架：底层方格、顶部「返回 + 荧光笔标题 + 操作」，其余交给内容。
 * 不用 TopAppBar，让标题直接写在纸上。
 */
@Composable
fun PaperScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    backgroundOffsetY: () -> Float = { 0f },
    contentBehindHeader: Boolean = false,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.(PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    var headerPixels by remember { mutableIntStateOf(0) }
    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().onSizeChanged { headerPixels = it.height }
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            } else Box(Modifier.padding(start = 12.dp))
            Box(Modifier.weight(1f)) { HighlightedTitle(title, maxLines = 1) }
            actions()
        }
    }
    GridPaper(modifier = modifier.fillMaxSize(), offsetY = backgroundOffsetY) {
        // 没有 Surface 托底，默认内容色是黑的；黑板主题下要显式换成墨色
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (contentBehindHeader) {
                    Column(Modifier.fillMaxSize()) {
                        content(PaddingValues(top = with(density) { headerPixels.toDp() } + 20.dp))
                    }
                    PaperHeaderBackdrop(Modifier.fillMaxWidth()
                        .height(with(density) { headerPixels.toDp() } + 40.dp), offsetY = backgroundOffsetY)
                    header()
                } else {
                    PaperHeaderBackdrop(Modifier.fillMaxWidth()
                        .height(with(density) { headerPixels.toDp() } + 40.dp), offsetY = backgroundOffsetY)
                    Column(Modifier.fillMaxSize()) {
                        header()
                        content(PaddingValues())
                    }
                }
            }
        }
    }
}
