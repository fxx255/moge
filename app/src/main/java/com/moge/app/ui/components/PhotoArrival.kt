package com.moge.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.MogeTheme
import java.io.File

/**
 * 记录「本次刚拍 / 刚选进来」的照片，让飞入动画只给新照片放、每个界面只放一次。
 *
 * 没登记过的路径（打开旧题、进程重启后恢复的照片）直接静止显示；
 * 已经在某个界面飞入过的，列表滑出再滑回、弹层关掉再打开都不会重播。
 */
object PhotoArrivals {
    private val played = HashMap<String, MutableSet<String>>()

    /**
     * 照片进入应用时调用（裁剪完成、追问加图）。同时登记规范路径：提交时
     * [com.moge.app.runtime.DraftStore.persistAttachments] 存的是 canonicalPath，
     * 而 `/data/user/0` 在不少机型上是 `/data/data` 的软链接。
     */
    fun markNew(paths: Collection<String>) {
        val forms = paths.flatMap { listOf(it, runCatching { File(it).canonicalPath }.getOrDefault(it)) }
        synchronized(played) { forms.forEach { played.getOrPut(it) { mutableSetOf() } } }
    }

    /** 新照片在 [surface] 上第一次出现时返回 true，之后都返回 false。 */
    fun claim(path: String, surface: String): Boolean = synchronized(played) {
        played[path]?.add(surface) ?: false
    }

    internal fun reset() = synchronized(played) { played.clear() }
}

/** 新照片从右上方轻落到纸面，关闭系统动画时直接显示完成状态。 */
@Composable
fun PhotoArrival(
    identity: String,
    surface: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val enabled = MogeTheme.motionEnabled
    val fresh = remember(identity, surface) { PhotoArrivals.claim(identity, surface) }
    val progress = remember(identity) { Animatable(if (enabled && fresh) 0f else 1f) }
    val travel = with(LocalDensity.current) { 36.dp.toPx() }
    LaunchedEffect(identity, enabled) {
        if (enabled) progress.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) else progress.snapTo(1f)
    }
    Box(modifier.graphicsLayer {
        val remaining = 1f - progress.value
        alpha = progress.value
        translationX = travel * remaining
        translationY = -travel * remaining
        scaleX = 1f - 0.12f * remaining
        scaleY = scaleX
    }, content = content)
}
