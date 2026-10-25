package com.moge.app.ui.components

import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect

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
    headerStyle: PaperHeaderStyle = PaperHeaderStyle.Classic,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.(PaddingValues) -> Unit,
) {
    val strings = rememberAppStrings()
    val hasPaperHeader = headerStyle != PaperHeaderStyle.Classic
    val density = LocalDensity.current
    var headerPixels by remember { mutableIntStateOf(0) }
    val headerRow: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().headerInputShield()
            .then(if (hasPaperHeader) Modifier.heightIn(min = PaperHeaderMinHeight) else Modifier)
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = if (hasPaperHeader) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onBack != null) PaperHeaderIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.text(R.string.ui_back_11d02))
            } else Box(Modifier.padding(start = 12.dp))
            Box(Modifier.weight(1f)) { HighlightedTitle(title, maxLines = 1,
                style = if (hasPaperHeader) paperHeaderTitleStyle()
                    else MaterialTheme.typography.headlineMedium) }
            actions()
        }
    }
    val header: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().onSizeChanged { headerPixels = it.height }) {
            if (hasPaperHeader) PaperStripHeader(
                progress = { if (headerStyle == PaperHeaderStyle.StackedRight) 1f else 0f },
                underlayTilt = 2.6f,
                showUnderlay = headerStyle != PaperHeaderStyle.Single,
                modifier = Modifier.fillMaxWidth(), content = headerRow)
            else headerRow()
        }
    }
    GridPaper(modifier = modifier.fillMaxSize(), offsetY = backgroundOffsetY) {
        // 没有 Surface 托底，默认内容色是黑的；黑板主题下要显式换成墨色
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (contentBehindHeader) {
                    Column(Modifier.fillMaxSize().drawWithContent {
                        if (hasPaperHeader) clipRect(top = headerPixels.toFloat().coerceAtMost(size.height)) {
                            this@drawWithContent.drawContent()
                        } else drawContent()
                    }) {
                        content(PaddingValues(top = with(density) { headerPixels.toDp() } + 20.dp))
                    }
                    if (!hasPaperHeader) PaperHeaderBackdrop(Modifier.fillMaxWidth()
                        .height(with(density) { headerPixels.toDp() } + 40.dp), offsetY = backgroundOffsetY)
                    header()
                } else {
                    if (!hasPaperHeader) PaperHeaderBackdrop(Modifier.fillMaxWidth()
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
