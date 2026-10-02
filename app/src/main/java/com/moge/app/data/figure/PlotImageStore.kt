package com.moge.app.data.figure

import android.content.Context
import android.graphics.Bitmap
import com.moge.app.domain.plot.PlotSpec
import com.moge.app.ui.figure.PlotBitmapRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 [PlotSpec] 渲染成 PNG 并持久化到 `filesDir/plots/`，同时写 spec 台账。
 *
 * 砺行把函数图放在 cacheDir 且没有台账，系统一清缓存就只剩「图表已过期」；
 * 这里与框图同一套机制（[FigureImageStore]），丢了能重画，切换主题能出对应变体。
 */
@Singleton
class PlotImageStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : FigureImageStore<PlotSpec>(
    rootDir = { context.filesDir },
    dirName = "plots",
    prefix = "plot",
    serializer = PlotSpec.serializer(),
    renderVersion = "p1",
) {
    override fun draw(spec: PlotSpec, dark: Boolean): Bitmap {
        val metrics = context.resources.displayMetrics
        // 按短边定宽：横屏时重画也得到同样的图，不会因为屏幕变宽而把字缩小
        val shortSide = minOf(metrics.widthPixels, metrics.heightPixels)
        val widthPx = (shortSide * 0.92f).toInt().coerceIn(600, 1600)
        val heightPx = (widthPx * PLOT_HEIGHT_RATIO).toInt()
        val theme = if (dark) PlotBitmapRenderer.Theme.DARK else PlotBitmapRenderer.Theme.LIGHT
        return PlotBitmapRenderer(metrics.density, theme).render(spec, widthPx, heightPx)
    }

    override fun describe(spec: PlotSpec): String = "title=${spec.title} series=${spec.series.size}"

    private companion object {
        /** 画布高宽比：0.66 时绘图区约 1.5:1，曲线形状与刻度密度都有余量。 */
        const val PLOT_HEIGHT_RATIO = 0.66f
    }
}
