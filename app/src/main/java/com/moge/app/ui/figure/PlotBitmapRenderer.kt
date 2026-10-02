package com.moge.app.ui.figure

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import com.moge.app.domain.plot.MarkArea
import com.moge.app.domain.plot.MarkLine
import com.moge.app.domain.plot.PlotSpec
import com.moge.app.domain.plot.Series
import com.moge.app.domain.plot.niceTicks
import com.moge.app.domain.plot.prettifyPlotLabel
import com.moge.app.domain.plot.sampleShadePolygons
import com.moge.app.domain.plot.sampleSeries
import ru.noties.jlatexmath.JLatexMathDrawable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * 结构化参数 → 位图（Android Canvas 版）。
 *
 * 与 PC 上用 Java2D 验证过的版本是同一套绘制流程，差别只在绘图 API：
 * BufferedImage→Bitmap、Graphics2D→Canvas、Path2D→Path、Font→Paint。
 * domain 层的模型、刻度算法、表达式采样、坐标变换全部原样复用。
 *
 * 所有尺寸按 density 缩放，保证不同屏幕观感一致。
 */
class PlotBitmapRenderer(
    private val density: Float,
    private val theme: Theme = Theme(),
) {

    data class Theme(
        val background: Int = 0xFF1C1C1E.toInt(),
        val text: Int = 0xFFC9C9CE.toInt(),
        val subText: Int = 0xFF8E8E93.toInt(),
        // 坐标轴线：明显比网格亮（网格 0x2B2B2F、刻度文案 0x8E8E93）。
        // 提到接近刻度文案的亮度是为了满足「图中要有更明显的 xy 坐标轴」——
        // 早先与网格同档，轴在深色背景上几乎看不见（用户反馈）。
        val axis: Int = 0xFFA8A8B0.toInt(),
        val grid: Int = 0xFF2B2B2F.toInt(),
        val markLine: Int = 0xFF6A6A70.toInt(),
        val markArea: Int = 0x1FFFC857,
        val seriesColors: List<Int> = listOf(
            0xFF5AA9FF.toInt(),
            0xFFFFC857.toInt(),
            0xFF3DDC97.toInt(),
            0xFFFF7EB6.toInt(),
            0xFFB28DFF.toInt(),
            0xFFFF8A65.toInt(),
            0xFF4DDDE0.toInt(),
            0xFFE5E879.toInt(),
            0xFFD2A679.toInt(),
            0xFFF06F78.toInt(),
            0xFF9AC8FF.toInt(),
            0xFFC4CFD9.toInt(),
        ),
    ) {
        companion object {
            /** 深色（黑板）主题：原版笔色，底色换成黑板卡片色，贴在板面上不显突兀。 */
            val DARK = Theme(background = 0xFF24332D.toInt(), grid = 0xFF33443C.toInt())

            /**
             * 浅色（方格本）主题：白底上用更深、饱和度更高的笔色，
             * 保证细线与浅色填充（38/255 透明度）在白纸上仍看得清。
             */
            val LIGHT = Theme(
                background = 0xFFFFFFFF.toInt(),
                text = 0xFF1A1B1E.toInt(),
                subText = 0xFF5F6570.toInt(),
                axis = 0xFF3A3F47.toInt(),
                grid = 0xFFE3E7EC.toInt(),
                markLine = 0xFF9097A0.toInt(),
                markArea = 0x1F1F3A8A,
                seriesColors = listOf(
                    0xFF1F5FD1.toInt(),
                    0xFFD9362B.toInt(),
                    0xFF138A4F.toInt(),
                    0xFFC2185B.toInt(),
                    0xFF6A3FC4.toInt(),
                    0xFFE06A00.toInt(),
                    0xFF00838F.toInt(),
                    0xFF8A7A00.toInt(),
                    0xFF8D5524.toInt(),
                    0xFFB71C3C.toInt(),
                    0xFF4F8BE8.toInt(),
                    0xFF5A6675.toInt(),
                ),
            )
        }
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    /**
     * 公式绘制器缓存：同一个图里轴标签/图例常重复出现，缓存避免反复走 TeX 解析。
     * key 是「latex + 字号」，值是已经量好尺寸的 drawable。
     */
    private val texCache = HashMap<String, JLatexMathDrawable>()

    /**
     * 标签夹取边界（画布坐标）。绘制过程中会被临时收紧到**绘图区**，
     * 保证图内的注释/标记标签不会画到图片外面。
     */
    private var labelBounds: RectF = RectF()

    private fun dp(v: Float) = v * density

    /**
     * 画一段文本，**自动识别 LaTeX 并排版**。
     *
     * 图上的标题 / 轴标签 / 图例都是模型自由填的字符串，它经常直接塞 `$S_c(f)$`、
     * `\frac{1}{T}`、`\sum`，也经常**中英混排**（「功率谱密度 $S_c(f)$」）。
     *
     * 关键约束：**公式片段里绝不能出现中文**。JLatexMath 的字体没有 CJK 字形，
     * 汉字宽度退化为 0（实测：`功率谱功率谱功率谱` 量出的宽度与单个「功率谱」
     * 完全相同，都是 12px）⇒ 所有汉字堆在同一坐标，图上文字重叠成一团。
     * 所以 [splitLabelPieces] 会把标签切成「公式 / 普通文字」交替的片段，
     * 公式交给 JLatexMath、其余交给 Canvas 文字，逐段排布。
     *
     * 公式排不出来（写法不合法）时**回落到纯文本**，绝不让绘图整体失败。
     * [alignCenter] 为 true 时 [x] 表示整段的中心，否则表示左边界；[baselineY] 是文字基线。
     *
     * **夹取**：标签会按 [labelBounds] 收进可视区——图内注释传 **绘图区** 边界，
     * 于是贴着右边缘的注释不会画到图片外面去（用户反馈的「注释文字右侧溢出图片」）。
     * 装不下时先**左移贴左边界**；连左对齐都装不下（标签比可视区还宽）才**截断加省略号**，
     * 保证任何情况下都不会有文字画到边界之外。
     */
    private fun drawSmartText(
        canvas: Canvas,
        raw: String,
        x: Float,
        baselineY: Float,
        textSizePx: Float,
        color: Int,
        alignCenter: Boolean = false,
    ) {
        val pieces = splitLabelPieces(raw)
        if (pieces.isEmpty()) return
        val bounds = labelBounds
        val gap = if (pieces.size > 1) dp(3f) else 0f

        // 先按原始请求安排起点，再按可视宽度裁掉装不下的尾部片段。
        // 决策部分抽成纯函数 [planLabelLayout]，便于在单测里精确验证
        // （Robolectric 的 Paint.measureText 每字符恒返回 1px，无法用真实排版验证）。
        val widths = pieces.map { pieceWidth(it, textSizePx) }
        val plan = planLabelLayout(
            widths = widths,
            gap = gap,
            ellipsisWidth = ellipsisWidth(textSizePx),
            anchorX = x,
            alignCenter = alignCenter,
            leftLimit = bounds.left,
            rightLimit = bounds.right,
        )
        if (!plan.visible) return
        var cursorX = plan.startX

        for (index in 0 until plan.keepCount) {
            val piece = pieces[index]
            val w = widths[index]
            val latex = piece.latex
            val texWidth = if (latex != null) texWidthOrNull(latex, textSizePx) else null
            val drawable = if (latex != null && texWidth != null) texDrawable(latex, textSizePx) else null
            if (drawable != null) {
                val h = drawable.intrinsicHeight
                // 公式以「视觉垂直居中于原文字行」的方式对齐：基线大致在行高的 72% 处
                val top = baselineY - h * 0.72f
                // 用 canvas 平移定位、给 drawable 一个「原点在 (0,0)」的 bounds：
                // 位置完全由我们决定，不依赖库对 bounds.left/top 的处理方式。
                val save = canvas.save()
                canvas.translate(cursorX, top)
                drawable.setBounds(0, 0, w.toInt(), h)
                // 公式颜色跟随当前主题文字色
                drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN)
                drawable.draw(canvas)
                canvas.restoreToCount(save)
            } else {
                // 纯文本片段；公式不可用时把公式源码按纯文本画出来（带降级替换）
                drawPlainText(canvas, latex ?: piece.text, cursorX, baselineY, textSizePx, color)
            }
            cursorX += w + gap
        }
        if (plan.truncated) {
            drawPlainText(canvas, ELLIPSIS, cursorX, baselineY, textSizePx, color)
        }
    }

    /** 省略号在给定字号下的宽度。 */
    private fun ellipsisWidth(textSizePx: Float): Float {
        textPaint.textSize = textSizePx
        return textPaint.measureText(ELLIPSIS)
    }

    /**
     * 画一段纯文本（含 LaTeX 命令的 Unicode 降级）。
     *
     * 这里也做一次夹取：单个片段本身很长时（一个不带 `$` 的超长中文串）
     * 不会走上面的分段裁剪路径，画到边界外就再也裁不回来了。
     */
    private fun drawPlainText(
        canvas: Canvas,
        raw: String,
        x: Float,
        baselineY: Float,
        textSizePx: Float,
        color: Int,
    ) {
        textPaint.textSize = textSizePx
        textPaint.color = color
        val text = prettifyPlotLabel(raw)
        if (text.isEmpty()) return
        val bounds = labelBounds
        val available = bounds.right - bounds.left
        var draw = text
        if (available > 0f) {
            if (x + textPaint.measureText(draw) > bounds.right) {
                // 二分出能放下的最长前缀（按字符切，末尾补省略号）
                var lo = 0
                var hi = draw.length
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    val candidate = draw.substring(0, mid) + ELLIPSIS
                    if (x + textPaint.measureText(candidate) <= bounds.right) lo = mid else hi = mid - 1
                }
                draw = if (lo <= 0) ELLIPSIS else draw.substring(0, lo) + ELLIPSIS
            }
        }
        canvas.drawText(draw, x, baselineY, textPaint)
    }

    /**
     * 公式排版的可用宽度；**不可用时返回 null**（构造失败、或量出的尺寸异常）。
     *
     * 尺寸异常必须挡住：零/负宽会让 `setBounds` 变成空矩形（画不出东西），
     * 宽到没边（超过 [MAX_TEX_WIDTH_EM] 个字号）则说明这条公式的度量不可信，
     * 直接把标签挤出画布。两种情况都回落成纯文本更可控。
     */
    private fun texWidthOrNull(latex: String, textSizePx: Float): Float? {
        val drawable = texDrawable(latex, textSizePx) ?: return null
        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        if (w <= 0 || h <= 0 || w > textSizePx * MAX_TEX_WIDTH_EM) return null
        return w.toFloat()
    }

    /** 单个片段的绘制宽度。 */
    private fun pieceWidth(piece: LabelPiece, textSizePx: Float): Float {
        val latex = piece.latex
        if (latex != null) {
            texWidthOrNull(latex, textSizePx)?.let { return it }
            return plainTextWidth(latex, textSizePx)
        }
        return plainTextWidth(piece.text, textSizePx)
    }

    private fun plainTextWidth(raw: String, textSizePx: Float): Float {
        textPaint.textSize = textSizePx
        return textPaint.measureText(prettifyPlotLabel(raw))
    }

    /** 量出 [drawSmartText] 会画的总宽度，用于居中/避让。 */
    private fun smartTextWidth(raw: String, textSizePx: Float): Float {
        val pieces = splitLabelPieces(raw)
        if (pieces.isEmpty()) return 0f
        val gap = if (pieces.size > 1) dp(3f) else 0f
        return pieces.sumOf { pieceWidth(it, textSizePx).toDouble() }.toFloat() +
            gap * (pieces.size - 1)
    }

    /** 构造（并缓存）公式 drawable；写法非法时返回 null，由调用方回落到纯文本。 */
    private fun texDrawable(latex: String, textSizePx: Float): JLatexMathDrawable? {
        val key = "$textSizePx::$latex"
        texCache[key]?.let { return it }
        return runCatching {
            JLatexMathDrawable.builder(latex)
                .textSize(textSizePx)
                .color(theme.text)
                .build()
        }.getOrNull()?.also { texCache[key] = it }
    }

    fun render(spec: PlotSpec, widthPx: Int, heightPx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(theme.background)
        drawAll(canvas, spec, widthPx.toFloat(), heightPx.toFloat())
        return bitmap
    }

    /** 绘制入口（internal 便于测试注入记录型 Canvas 校验定位）。 */
    internal fun drawAll(canvas: Canvas, spec: PlotSpec, width: Float, height: Float) {
        // 整个画面的绘图范围：所有标签的默认夹取边界。
        labelBounds = RectF(0f, 0f, width, height)

        // ---- 1. 采样：表达式与点集在这里汇合，之后只认点集 ----
        val sampled: List<List<Pair<Double, Double?>>> = spec.series.map { s ->
            val xMin = spec.x.min ?: s.points?.minOfOrNull { it.first } ?: -5.0
            val xMax = spec.x.max ?: s.points?.maxOfOrNull { it.first } ?: 5.0
            sampleSeries(s, xMin, xMax, 900)
        }
        val allPoints = sampled.flatten()

        // ---- 2. 数据范围 ----
        // x 轴是「定义域」：以模型声明的区间为准，但要保证点集不越出视野。
        // y 轴用 balancedRange：主体完整 + 适当边距 + 极端离群点折叠（见其注释）。
        val xDataLo = allPoints.minOfOrNull { it.first } ?: -1.0
        val xDataHi = allPoints.maxOfOrNull { it.first } ?: 1.0
        val (xLo, xHi) = conservativeRange(xDataLo, xDataHi, spec.x.min, spec.x.max)
        val yRange = balancedRange(allPoints.map { it.second }, spec.y.min, spec.y.max)
        val yLo = yRange.lo
        val yHi = yRange.hi
        val spanX = if (xHi - xLo == 0.0) 1.0 else xHi - xLo
        val spanY = if (yHi - yLo == 0.0) 1.0 else yHi - yLo

        // ---- 3. 布局与坐标变换 ----
        // 边距的比例（相对画布宽/高），而不是固定 dp。
        //
        // 为什么不能用固定 dp：画布尺寸是**物理像素**（PlotImageStore 按屏宽的 92% 取，
        // 典型 993×575），而 dp 会随 density 放大——2.75~3.0 的机器上光是左右内边距
        // 就要吃掉 220~240px、上下再吃掉 300+px，绘图区只剩 ~750×250（约 3:1 的扁条）。
        // 曲线被压成一条线，抛物线之类「要先看清形状」的图完全失真（用户反馈
        // 「画图拉伸的度还是极端，高度和宽度都超出图片」）。改成按画布尺寸取比例后，
        // 绘图区宽高比只由画布比例决定，与 density 无关。
        val padLeft = width * LEFT_PAD_RATIO
        val padRight = width * RIGHT_PAD_RATIO
        val hasLegend = spec.legend || spec.series.size > 1
        // 图例改到绘图区上方（标题下面），所以上边距要把它一并算进去
        val padTop = height *
            ((if (spec.title.isNotEmpty()) TITLE_BAND_RATIO else TOP_PAD_RATIO) +
                (if (hasLegend) LEGEND_BAND_RATIO else 0f))
        val padBottom = height * BOTTOM_PAD_RATIO
        // 字号同样按画布缩放：画布是固定像素尺寸，用 dp 会在高 density 机器上
        // 把标签顶到挤出画面。
        val titleSize = width * 0.031f
        val labelSize = width * 0.026f
        val tickSize = width * 0.024f

        val xTicks = spec.x.ticks ?: niceTicks(xLo, xHi, 5)
        val yTicks = spec.y.ticks ?: niceTicks(yLo, yHi, 4)

        // y 轴刻度文案的排版方案：**在确定绘图区位置之前先算**。
        //
        // 为什么必须提前算：左侧留白原本是固定的画布宽 8.2%，参数化刻度文案
        // （如 `$N_0(2\pi f_c)^2$`）排版后远比这宽，收进留白后被截成「…」——
        // 用户看到的就是「左边稳定显示三个点」。这里改成两段式：
        // 1. 先按基准字号量一遍最宽的那条刻度文案；
        // 2. 装得下就沿用基准字号；装不下就**压缩字号**，仍装不下才**加宽左侧留白**。
        // 两条路都能保住完整文案，绝不会退化成省略号。
        // 注意：这里只能用「夹取到画布」量，因为此时绘图区还没定位。
        val yTickRaws = yTicks.map { spec.y.tickLabels[it] ?: formatTick(it) }
        val yLabelPlan = planYTickSize(
            widths = yTickRaws.map { smartTextWidth(it, tickSize) },
            available = padLeft - width * Y_TICK_GAP_RATIO,
            baseSize = tickSize,
        )
        val tickSizeY = yLabelPlan.textSize
        // 缩字号后仍嫌不够，就把左侧留白放宽到内容需要的宽度（有上限，见其注释）
        val padLeftExtra = (yLabelPlan.requiredWidth + width * Y_TICK_GAP_RATIO - padLeft)
            .coerceIn(0f, width * LEFT_PAD_EXTRA_MAX_RATIO)
        val padLeftFinal = padLeft + padLeftExtra
        val plotX = padLeftFinal
        val plotY = padTop
        val plotW = width - padLeftFinal - padRight
        val plotH = height - padTop - padBottom

        // ---- 3b. 绘图区**内缩**：给曲线四周留出视觉余量 ----
        //
        // 这里要把两件事彻底分开，它们之前被混成了一件：
        //   · **坐标轴范围**（xLo/xHi）—— 语义量。模型显式给的 min/max 是定义域，
        //     必须精确保留，`±B/2` 的刻度与 markLine 才落在正确位置上；
        //   · **绘图区物理边界**（下面这个内缩矩形）—— 纯视觉量。曲线不必顶到边界，
        //     留一圈空隙才看得出形状、也才好看。
        //
        // 早先的做法是把「留余量」做在**范围**上（把范围往外扩 4%），结果定义域被
        // 撑大、`±B/2` 的竖线跑进框内，看起来像曲线越过了定义域边界（用户第二轮反馈）。
        // 改成只外扩范围后，曲线又正好顶死在框线上，既不好看也看不出特征（用户第三轮反馈）。
        // 现在：范围一动不动，只把绘图区**向内缩**——两个诉求同时满足。
        //
        // 注意 [sx]/[sy] 映射的是内缩后的矩形，所以曲线、网格、markLine、markArea
        // 全部自动获得余量；坐标轴画在内缩矩形的边上，刻度仍按真实数值定位。
        val insetX = plotW * PLOT_INSET_X_RATIO
        val insetY = plotH * PLOT_INSET_Y_RATIO
        val drawX = plotX + insetX
        val drawY = plotY + insetY
        val drawW = (plotW - insetX * 2f).coerceAtLeast(1f)
        val drawH = (plotH - insetY * 2f).coerceAtLeast(1f)

        // 坐标变换：绘图区（含内缩余量）定位之后才能定义
        fun sx(x: Double): Float = drawX + ((x - xLo) / spanX * drawW).toFloat()
        fun sy(y: Double): Float = drawY + drawH - ((y - yLo) / spanY * drawH).toFloat()

        // Filled regions stay behind the grid, axes and every data series.
        val plotBounds = RectF(drawX, drawY, drawX + drawW, drawY + drawH)
        val fillSave = canvas.save()
        canvas.clipRect(plotBounds)
        for (area in spec.markAreas) {
            val region = Path().apply { addRect(sx(area.x0), sy(area.y1 ?: yHi), sx(area.x1), sy(area.y0 ?: yLo), Path.Direction.CW) }
            drawRegion(canvas, region, plotBounds, plotColor(area.color, area.colorIndex), area.opacity, area.pattern)
        }
        for (region in spec.shades) {
            val polygons = sampleShadePolygons(region, xLo, xHi, spanY)
            val path = Path().apply {
                for (polygon in polygons) {
                    polygon.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(sx(x), sy(y)) else lineTo(sx(x), sy(y)) }
                    close()
                }
            }
            drawRegion(canvas, path, plotBounds, plotColor(region.color, region.colorIndex), region.opacity, region.pattern)
        }
        spec.series.forEachIndexed { index, series ->
            if (series.fill) {
                fillPaint.color = withAlpha(plotColor(series.color, series.colorIndex), (38 * series.opacity).toInt())
                fillArea(canvas, sampled[index], ::sx, ::sy, sy(0.0).coerceIn(drawY, drawY + drawH))
            }
        }
        canvas.restoreToCount(fillSave)

        // ---- 5. 网格 ----
        linePaint.color = theme.grid
        linePaint.strokeWidth = dp(0.8f)
        linePaint.pathEffect = null
        if (spec.x.grid) {
            for (t in xTicks) {
                val px = sx(t)
                canvas.drawLine(px, drawY, px, drawY + drawH, linePaint)
            }
        }
        if (spec.y.grid) {
            for (t in yTicks) {
                val py = sy(t)
                canvas.drawLine(drawX, py, drawX + drawW, py, linePaint)
            }
        }

        // ---- 7. 坐标轴 ----
        // 参照教材频谱插图（用户给出的参考图）的轴样式：
        //   · x 轴是一条**长横线**，向左/右都伸到绘图区之外，右端下方标轴名；
        //   · x=0 处一条**通高竖线**，从绘图区上方一直落到 x 轴（穿过整条曲线）；
        //   · 曲线的定义域端点（±B/2 之类）由 markLine 画成实线竖线，只从 x 轴升到曲线。
        // 这样轴本身就「看得出来是轴」，而不是一段贴着数据的短线。
        linePaint.color = theme.axis
        linePaint.strokeWidth = dp(AXIS_STROKE_DP)
        linePaint.pathEffect = null

        // x 轴横线：左右各伸出绘图区，伸出量为画布宽的比例。
        // 用外层绘图区（plotX..plotX+plotW）为基准而不是内缩矩形，
        // 这样「轴长度」与内缩比例解耦，调内缩不会连带动到轴长。
        // 默认让横轴穿过数学原点；非负谱/功率图的 y 范围以 0 为下界时，
        // sy(0) 正好落在底边，视觉上仍保持传统的底部横轴。
        // position=bottom 可用于明确要求底部布局的图，origin 在范围包含 0
        // 时强制使用 y=0（范围不含 0 时安全退回底部）。
        val zeroInsideY = yLo <= 0.0 && 0.0 <= yHi
        val axisY = when (spec.x.position) {
            "bottom" -> drawY + drawH
            "origin", "auto" -> if (zeroInsideY) sy(0.0) else drawY + drawH
            else -> if (zeroInsideY) sy(0.0) else drawY + drawH
        }
        val axisLeft = plotX - width * AXIS_OVERHANG_L_RATIO
        val axisRight = plotX + plotW + width * AXIS_OVERHANG_R_RATIO
        canvas.drawLine(axisLeft, axisY, axisRight, axisY, linePaint)

        // y 轴竖线：画在绘图区左边（含内缩），上下都略伸出。
        // 注意这里只画 x=0 那一根通高竖线当 y 轴**是错的**：坐标系原点不一定在
        // x 范围内（如 y=1/x 的窗口、y=2^x 只有正值）。所以分两种：
        //   · 0 落在 x 范围内 ⇒ 竖线画在 x=0（真正的 y 轴，参考图的情形）；
        //   · 否则 ⇒ 竖线仍画在绘图区左边，退化成「左边界轴线」。
        val zeroInsideX = xLo <= 0.0 && 0.0 <= xHi
        val yAxisX = if (zeroInsideX) sx(0.0) else drawX
        // 竖线顶端伸到绘图区之上（参考图里竖向轴线明显高过曲线顶端）
        // 延伸线只能占用绘图区内的留白，不能穿过标题/图例或把箭头画到画布外。
        val yAxisTop = maxOf(plotY, drawY - plotH * AXIS_VERT_OVERHANG_RATIO)
        // 纵轴必须贯穿横轴下方的负半轴；横轴移到原点后不能仍只画到
        // axisY 附近，否则 y<0 的数据会落在没有纵轴的半幅区域。
        val yAxisBottom = drawY + drawH + height * AXIS_VERT_BELOW_RATIO
        canvas.drawLine(yAxisX, yAxisTop, yAxisX, yAxisBottom, linePaint)

        // 轴端箭头：替代原先的「小刻度」——箭头对「这是坐标轴」的表达比一小截刻度
        // 更明确，也是教材插图的通行画法（用户要求「坐标轴头部改成箭头」）。
        //
        // 尺寸一律取**画布宽/高的比例**，绝不能用 dp：画布是物理像素，dp 会随
        // density 放大，在高密度屏上箭头会大得离谱（这是本项目 v1.0.32 的真根因，
        // 内边距与字号都踩过同一个坑）。
        drawAxisArrowX(canvas, axisRight, axisY, linePaint, +1, width, height)
        drawAxisArrowY(canvas, yAxisX, yAxisTop, linePaint, -1, width, height)

        // 轴名贴在**轴的远端**（教材参考图里 `f` 就标在 x 轴右端下方）。
        // 位置取轴末端而不是绘图区中心——居中那个位置留给「轴标题」的语义已废弃，
        // 统一改成端点轴名（见第 9 步的说明）。
        //
        // 与端刻度错开：若两者都紧贴 `axisRight`，轴名会压在端刻度上。
        // 这里把轴名**右移半个字宽**并让它以自身左边缘贴住轴端右侧一点点，
        // 保证「刻度在上、轴名在下且不重叠」。
        if (spec.x.label.isNotEmpty()) {
            val axisNameSize = tickSize * 1.02f
            val w = smartTextWidth(spec.x.label, axisNameSize)
            val nameX = (axisRight + width * 0.006f)
                .coerceAtMost(width - w - width * 0.004f)
            drawSmartText(
                canvas,
                spec.x.label,
                nameX,
                axisY + height * 0.030f,
                axisNameSize,
                theme.text,
            )
        }

        // ---- 7b. 断轴标记：有数据被折叠到范围外时，在竖直轴上画「断口」 ----
        // 竖轴的位置可能已经挪到 x=0（见上），断口必须跟着走，否则断口会画在空处。
        if (yRange.foldedHigh) drawAxisBreak(canvas, yAxisX, drawY + dp(9f))
        if (yRange.foldedLow) drawAxisBreak(canvas, yAxisX, drawY + drawH - dp(9f))

        // ---- 8. 刻度与标签 ----
        // 刻度文字夹在**画布**内（它们本来就在绘图区外、贴着轴排布），
        // 但要留出底部/左侧的固定余量，避免被裁掉半个字。
        val tickBottomLimit = height - height * 0.012f
        // x 轴：参数化刻度（如 `-f_c-B/2`、`O`、`f_c+B/2`）比纯数字宽得多，
        // 挨着画会糊成一片（用户截图里 `-f_c-B/2-f_cf_c-B/2` 挤在一起就是这个）。
        // 这里按「与上一个标签是否重叠」做一次过滤：放不下就跳过这一个刻度，
        // 宁可少标几个，也不要叠成一团看不清。
        var lastLabelRight = -Float.MAX_VALUE
        val minGap = tickSize * 0.8f
        for (t in xTicks) {
            val raw = spec.x.tickLabels[t] ?: formatTick(t)
            val w = smartTextWidth(raw, tickSize)
            val centerX = sx(t)
            val left = centerX - w / 2f
            if (left < lastLabelRight + minGap) continue
            drawSmartText(
                canvas,
                raw,
                centerX,
                min(axisY + height * 0.038f, tickBottomLimit),
                tickSize,
                theme.subText,
                alignCenter = true,
            )
            lastLabelRight = centerX + w / 2f
        }
        // y 轴：右对齐到绘图区左侧，并且**把夹取范围收进绘图区左侧的留白**。
        // 参数化的刻度文案可能很长（如 $2\pi^2N_0(f_c+B/2)^2$），
        // 不收紧的话它会横向伸进绘图区、压住曲线（用户反馈「左边的字挡住图像」）。
        //
        // 但夹取会导致截断加省略号，而 y 轴文案**不能被截**（截出来就是用户看到的
        // 「左边稳定显示三个点」）。所以这里的设计是：**先量后裁**——
        // 上面已经按实际文案宽度定好了字号与绘图区位置，正常情况下列文正好填满留白、
        // 不带省略号；这里的夹取只作为最后兜底（例如留白已放到上限仍装不下）。
        val savedBounds = labelBounds
        labelBounds = RectF(0f, drawY, (drawX - width * 0.008f).coerceAtLeast(0f), drawY + drawH)
        // y 轴刻度文案的字号已由 planYTickSize 定好：装得下就用基准字号，
        // 装不下先缩字号、必要时绘图区已被右移，**不再出现「只剩三个点」**。
        for ((index, t) in yTicks.withIndex()) {
            val raw = yTickRaws[index]
            val w = smartTextWidth(raw, tickSizeY)
            drawSmartText(
                canvas,
                raw,
                drawX - width * Y_TICK_GAP_RATIO - w,
                sy(t) + tickSizeY * 0.36f,
                tickSizeY,
                theme.subText,
            )
        }
        labelBounds = savedBounds

        // ---- 9. 轴标题 ----
        // x 轴名已在第 7 步贴着**轴末端**画好了（参考图的画法），这里不再重复居中绘制，
        // 否则同一串文字会在图上出现两次。
        if (spec.y.label.isNotEmpty()) {
            val withUnit = if (spec.y.unit.isNotEmpty()) "${spec.y.label} (${spec.y.unit})" else spec.y.label
            val axisX = width * 0.018f
            val axisCenterY = drawY + drawH / 2
            // 竖排轴标题：先把文字沿 y 轴的**可用长度**（绘图区高度）当作横向限额，
            // 在未旋转的坐标系里截断，再整体旋转 -90°。
            // 这样不必去推「旋转后坐标 → 原坐标」的映射，逻辑与横排标签完全一致：
            // 用 planLabelLayout 拿到「保留几段、起点在哪」，然后照常绘制即可。
            val save = canvas.save()
            canvas.rotate(-90f, axisX, axisCenterY)
            labelBounds = RectF(
                axisCenterY - drawH / 2,
                axisX,
                axisCenterY + drawH / 2,
                axisX + drawH,
            )
            drawSmartText(
                canvas,
                withUnit,
                axisCenterY,
                axisX + labelSize * 0.34f,
                labelSize,
                theme.text,
                alignCenter = true,
            )
            labelBounds = RectF(0f, 0f, width, height)
            canvas.restoreToCount(save)
        }

        // ---- 10. 标题 ----
        if (spec.title.isNotEmpty()) {
            drawSmartText(canvas, spec.title, width / 2, height * 0.052f, titleSize, theme.text, alignCenter = true)
        }

        // ---- 11. markLine ----
        // 竖直标记线（`±B/2` 这类**定义域边界**）改成**实线**，并且只画到曲线为止。
        //
        // 教材参考图里这两条边界是实线、从 x 轴升到曲线端点、不越到曲线之上——
        // 早先用贯穿整个绘图区高度的虚线，看起来像「辅助网格线」而不像边界
        // （用户反馈希望更接近参考图）。这里：
        //   · 有曲线经过 ⇒ 画到该 x 处曲线的最高点（端点正好接住曲线）；
        //   · 没有曲线经过（纯标记位置）⇒ 退化为整段实线。
        // 颜色仍用 markLine（比轴线暗），保证边界不抢曲线的视觉重心。
        for (line in spec.markLines) {
            if (line.x == null) {
                val y = line.y ?: continue
                if (y !in yLo..yHi) continue
                linePaint.color = theme.markLine
                linePaint.strokeWidth = dp(MARK_LINE_STROKE_DP)
                linePaint.pathEffect = null
                canvas.drawLine(drawX, sy(y), drawX + drawW, sy(y), linePaint)
                line.label?.let { drawSmartText(canvas, it, drawX + width * .02f, sy(y) - height * .012f, tickSize, theme.subText) }
                continue
            }
            val x = line.x
            if (x !in xLo..xHi) continue
            val px = sx(x)
            val curveTop = curveTopAt(sampled, x, ::sx, ::sy)
            linePaint.color = theme.markLine
            linePaint.strokeWidth = dp(MARK_LINE_STROKE_DP)
            linePaint.pathEffect = null
            val top = curveTop ?: drawY
            val bottom = axisY
            canvas.drawLine(px, min(top, bottom), px, maxOf(top, bottom), linePaint)
            line.label?.let {
                // 放进绘图区顶部：绘图区上方已经让给图例了，放外面会叠在一起。
                // 夹在绘图区内，贴边的标记标签不会横穿到图片外面。
                val save = canvas.save()
                labelBounds = RectF(drawX, drawY, drawX + drawW, drawY + drawH)
                drawSmartText(canvas, it, px, drawY + height * 0.025f, tickSize, theme.subText, alignCenter = true)
                canvas.restoreToCount(save)
                labelBounds = RectF(0f, 0f, width, height)
            }
        }

        // Lines above axes; points above all lines, including points located exactly on an axis.
        val seriesSave = canvas.save()
        canvas.clipRect(plotBounds)
        spec.series.forEachIndexed { index, series ->
            if (series.style != "marker") drawPolyline(canvas, sampled[index], ::sx, ::sy,
                withAlpha(plotColor(series.color, series.colorIndex), (255 * series.opacity).toInt()), series.width, series.style)
        }
        canvas.restoreToCount(seriesSave)
        val markerSave = canvas.save()
        // Keep endpoint markers whole; reject out-of-range centers instead of showing folded points.
        canvas.clipRect(drawX - dp(8f), drawY - dp(8f), drawX + drawW + dp(8f), drawY + drawH + dp(8f))
        spec.series.forEachIndexed { index, series ->
            if (series.style == "marker" || series.style == "line_marker") {
                val color = withAlpha(plotColor(series.color, series.colorIndex), (255 * series.opacity).toInt())
                sampled[index].forEach { (x, y) ->
                    if (y != null && y.isFinite() && x in xLo..xHi && y in yLo..yHi)
                        drawMarker(canvas, sx(x), sy(y), dp(series.markerSize), series.markerShape, color)
                }
            }
        }
        canvas.restoreToCount(markerSave)
        labelBounds = plotBounds
        spec.markAreas.forEach { area -> area.label?.let {
            drawSmartText(canvas, it, (sx(area.x0) + sx(area.x1)) / 2, drawY + height * .058f, tickSize, theme.subText, true)
        } }
        spec.shades.forEach { region -> region.label?.let {
            val middle = region.points?.map { p -> p.first }?.average() ?: ((region.x0 ?: xLo) + (region.x1 ?: xHi)) / 2
            drawSmartText(canvas, it, sx(middle), drawY + height * .085f, tickSize, theme.subText, true)
        } }
        labelBounds = RectF(0f, 0f, width, height)

        // ---- 12. 图例：横排在标题下方、绘图区之外 ----
        // 早先画在绘图区内部左上角，会被曲线压住（左右对称的谱线尤其明显）。
        if (hasLegend) {
            val legendY = plotY - height * 0.015f
            var xx = plotX
            spec.series.forEach { s ->
                val label = s.label
                if (prettifyPlotLabel(label).isEmpty()) return@forEach
                val swatchW = width * 0.021f
                val itemWidth = swatchW + smartTextWidth(label, tickSize) + width * 0.013f
                // 排不下就不再画，绝不让图例伸出画布被裁成半截
                if (xx + itemWidth > plotX + plotW + width * 0.014f) return@forEach
                val color = plotColor(s.color, s.colorIndex)
                linePaint.color = color
                linePaint.strokeWidth = dp(2f)
                linePaint.pathEffect = lineEffect(s.style)
                if (s.style != "marker") canvas.drawLine(xx, legendY - height * 0.008f, xx + swatchW * 0.78f, legendY - height * 0.008f, linePaint)
                if (s.style == "marker" || s.style == "line_marker") drawMarker(canvas, xx + swatchW * .4f, legendY - height * .008f, dp(2.5f), s.markerShape, color)
                linePaint.pathEffect = null
                // 图例项也要夹取：最后一个图例项贴着右边界时不能画出去
                val save = canvas.save()
                labelBounds = RectF(plotX, plotY - height * 0.06f, plotX + plotW + width * 0.014f, plotY)
                drawSmartText(canvas, label, xx + swatchW * 0.95f, legendY, tickSize, theme.subText)
                canvas.restoreToCount(save)
                labelBounds = RectF(0f, 0f, width, height)
                xx += itemWidth
            }
        }
    }

    /**
     * 断轴标记：两条短斜线（制图惯例符号）画在竖直坐标轴上，表示「轴在此处被折叠，
     * 仍有超出范围的数据被压缩显示」——保证数据不被无声丢弃。
     */
    private fun drawAxisBreak(canvas: Canvas, axisX: Float, y: Float) {
        linePaint.color = theme.axis
        linePaint.strokeWidth = dp(1.2f)
        linePaint.pathEffect = null
        val w = dp(5f)
        val h = dp(4f)
        val gap = dp(2.6f)
        canvas.drawLine(axisX - w, y + h, axisX + w, y - h, linePaint)
        canvas.drawLine(axisX - w, y + h + gap, axisX + w, y - h + gap, linePaint)
    }

    /**
     * x 轴末端的箭头（两条短斜线构成 `>` 或 `<`）。
     *
     * @param dir `+1` 画成指向右的 `>`（x 轴右端用），`-1` 画成指向左的 `<`。
     * @param canvasW 画布宽（物理像素），箭臂长度按它的比例取。
     *
     * 尺寸全部按**画布宽的比例**给定：0.011 这个值沿用 `docs/prototype/PlotAxisStyles.java`
     * 里在 1000px 宽画布上比过的结果（箭臂约 11px），肉眼刚好、又不至于抢戏。
     * **绝不能改成 dp**——画布是物理像素，dp 会随 density 放大（v1.0.32 真根因）。
     */
    private fun drawAxisArrowX(
        canvas: Canvas,
        tipX: Float,
        y: Float,
        paint: Paint,
        dir: Int,
        canvasW: Float,
        canvasH: Float,
    ) {
        val len = canvasW * AXIS_ARROW_X_LEN_RATIO
        val half = len * AXIS_ARROW_HALF_RATIO
        canvas.drawLine(tipX, y, tipX - len * dir, y - half, paint)
        canvas.drawLine(tipX, y, tipX - len * dir, y + half, paint)
    }

    /**
     * y 轴末端的箭头（两条短斜线构成 `^` 或 `v`）。
     *
     * @param dir `-1` 画成指向上的 `^`（y 轴顶端用），`+1` 画成指向下的 `v`。
     * @param canvasH 画布高（物理像素），纵向箭臂按它的比例取。
     *
     * 纵向箭臂取**画布高的比例**（横向取宽），保持箭头在长宽比不同的画布上不变形：
     * 绘图区约 1.8:1 偏扁，若纵向也用宽的比例，箭头会被压成扁尖、不像箭头。
     */
    private fun drawAxisArrowY(
        canvas: Canvas,
        x: Float,
        tipY: Float,
        paint: Paint,
        dir: Int,
        canvasW: Float,
        canvasH: Float,
    ) {
        val len = canvasH * AXIS_ARROW_Y_LEN_RATIO
        val half = len * AXIS_ARROW_HALF_RATIO
        canvas.drawLine(x, tipY, x - half, tipY - len * dir, paint)
        canvas.drawLine(x, tipY, x + half, tipY - len * dir, paint)
    }

    /**
     * 求某条竖线在 `x` 处应该画到多高——即**该处曲线的最高点**（画布 y）。
     *
     * 用途：定义域边界（`±B/2`）的实线要正好接住曲线端点，而不是贯穿整个绘图区
     * （参考图的画法）。取「所有序列中最高的一条」，多条曲线时不会被靠后的序列遮住。
     *
     * 找不到（该 x 处没有任何序列的有效点、或全部超出绘图区）时返回 null，
     * 由调用方退化为整段绘制。
     */
    private fun curveTopAt(
        sampled: List<List<Pair<Double, Double?>>>,
        x: Double,
        sx: (Double) -> Float,
        sy: (Double) -> Float,
    ): Float? {
        var best: Float? = null
        for (pts in sampled) {
            for ((px, py) in pts) {
                if (py == null || !py.isFinite()) continue
                // 找与该 x 最接近的采样点；采样是等距的，容差取相邻点的判定即可
                if (abs(px - x) > CURVE_TOP_X_TOLERANCE) continue
                val y = sy(py)
                if (best == null || y < best) best = y
            }
        }
        return best
    }

    /** 画折线：null 处断开——发散点（1/x 在 0 等）绝不能连成一条竖直长线。 */
    private fun drawPolyline(
        canvas: Canvas,
        pts: List<Pair<Double, Double?>>,
        sx: (Double) -> Float,
        sy: (Double) -> Float,
        color: Int,
        width: Float,
        style: String,
    ) {
        linePaint.color = color
        linePaint.strokeWidth = dp(width)
        linePaint.strokeCap = Paint.Cap.ROUND
        linePaint.strokeJoin = Paint.Join.ROUND
        linePaint.pathEffect = lineEffect(style)

        val path = Path()
        var started = false
        for ((x, y) in pts) {
            if (y == null || !y.isFinite()) {
                started = false
                continue
            }
            val px = sx(x)
            val py = sy(y)
            if (!started) {
                path.moveTo(px, py)
                started = true
            } else {
                path.lineTo(px, py)
            }
        }
        canvas.drawPath(path, linePaint)
        linePaint.pathEffect = null
    }

    /** 面积填充：按连续段分别闭合到基线。 */
    private fun fillArea(
        canvas: Canvas,
        pts: List<Pair<Double, Double?>>,
        sx: (Double) -> Float,
        sy: (Double) -> Float,
        baseline: Float,
    ) {
        val path = Path()
        var segment = mutableListOf<Pair<Float, Float>>()
        var hasSegment = false

        fun flush() {
            if (segment.size >= 2) {
                path.moveTo(segment.first().first, baseline)
                for (p in segment) path.lineTo(p.first, p.second)
                path.lineTo(segment.last().first, baseline)
                path.close()
                hasSegment = true
            }
            segment = mutableListOf()
        }

        for ((x, y) in pts) {
            if (y == null || !y.isFinite()) {
                flush()
            } else {
                segment += sx(x) to sy(y)
            }
        }
        flush()
        if (hasSegment) canvas.drawPath(path, fillPaint)
    }

    private fun plotColor(custom: String?, index: Int): Int =
        custom?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }?.let { Color.parseColor(it) }
            ?: theme.seriesColors[Math.floorMod(index, theme.seriesColors.size)]

    private fun lineEffect(style: String): DashPathEffect? = when (style) {
        "dashed" -> DashPathEffect(floatArrayOf(dp(7f), dp(4f)), 0f)
        "dotted" -> DashPathEffect(floatArrayOf(dp(1f), dp(4f)), 0f)
        "dashdot" -> DashPathEffect(floatArrayOf(dp(9f), dp(4f), dp(1f), dp(4f)), 0f)
        else -> null
    }

    private fun drawMarker(canvas: Canvas, x: Float, y: Float, radius: Float, shape: String, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = if (shape == "open_circle" || shape == "cross") Paint.Style.STROKE else Paint.Style.FILL
            strokeWidth = dp(1.6f)
        }
        when (shape) {
            "square" -> canvas.drawRect(x - radius, y - radius, x + radius, y + radius, paint)
            "diamond", "triangle" -> {
                val path = Path().apply {
                    moveTo(x, y - radius)
                    lineTo(x + radius, if (shape == "diamond") y else y + radius)
                    if (shape == "diamond") lineTo(x, y + radius)
                    lineTo(x - radius, if (shape == "diamond") y else y + radius)
                    close()
                }
                canvas.drawPath(path, paint)
            }
            "cross" -> {
                canvas.drawLine(x - radius, y - radius, x + radius, y + radius, paint)
                canvas.drawLine(x - radius, y + radius, x + radius, y - radius, paint)
            }
            else -> {
                if (shape == "open_circle") {
                    fillPaint.color = theme.background
                    canvas.drawCircle(x, y, radius, fillPaint)
                }
                canvas.drawCircle(x, y, radius, paint)
            }
        }
    }

    private fun drawRegion(canvas: Canvas, path: Path, bounds: RectF, color: Int, opacity: Double, pattern: String) {
        fillPaint.color = withAlpha(color, (opacity * 255).toInt())
        fillPaint.pathEffect = null
        if (pattern == "solid") { canvas.drawPath(path, fillPaint); return }
        val save = canvas.save()
        canvas.clipPath(path)
        linePaint.color = withAlpha(color, (opacity * 255).toInt())
        linePaint.strokeWidth = dp(1f)
        linePaint.pathEffect = null
        val spacing = dp(8f).coerceAtLeast(4f)
        var x = bounds.left - bounds.height()
        while (x <= bounds.right) {
            canvas.drawLine(x, bounds.bottom, x + bounds.height(), bounds.top, linePaint)
            if (pattern == "crosshatch") canvas.drawLine(x, bounds.top, x + bounds.height(), bounds.bottom, linePaint)
            x += spacing
        }
        canvas.restoreToCount(save)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )

    private fun formatTick(v: Double): String = when {
        abs(v - v.toLong()) < 1e-9 -> v.toLong().toString()
        abs(v) < 1e-3 -> String.format(Locale.ROOT, "%.2e", v)
        abs(v) >= 10000 -> String.format(Locale.ROOT, "%.0f", v)
        else -> String.format(Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }
}

/** 标签片段：要么是交给 JLatexMath 的公式（[latex]），要么是直接画的普通文字（[text]）。 */
internal data class LabelPiece(val text: String = "", val latex: String? = null)

/** 公式宽度的可信上限（按字号计）：超过这么多 em 说明度量不可信，回落纯文本。 */
private const val MAX_TEX_WIDTH_EM = 40f

/**
 * 布局比例（相对**画布**宽/高）。
 *
 * 这些值取代了原先的固定 dp——画布尺寸来自物理像素，dp 会随 density 放大，
 * 在高密度屏上会把绘图区压成扁条（详见 `drawAll` 里的注释）。
 * 取值参照常见出版物的图表比例：左右总计约 10% 宽、底部约 12% 高，
 * 让绘图区稳定落在 1.5~1.8 : 1，既能看清抛物线的弯曲、也不至于把横向刻度挤掉。
 */
private const val LEFT_PAD_RATIO = 0.082f
// 右侧留白取与左侧对称的 8.2%：这样两条轴外侧的空白一致，曲线天然居中。
// 该值同时要容纳「x 轴伸出绘图区」的部分（AXIS_OVERHANG_R_RATIO=0.030）
// 与轴名（如 `f`，约 3.5%），8.2% 有富余，不会把轴或轴名挤到画布外。
private const val RIGHT_PAD_RATIO = 0.082f
private const val TOP_PAD_RATIO = 0.055f
private const val TITLE_BAND_RATIO = 0.085f
private const val LEGEND_BAND_RATIO = 0.048f
private const val BOTTOM_PAD_RATIO = 0.125f

/** 刻度文案与坐标轴之间的横向间隙（相对画布宽）。 */
private const val Y_TICK_GAP_RATIO = 0.010f

/**
 * 绘图区内缩比例（相对绘图区自身宽/高）：曲线四周的**视觉余量**。
 *
 * 这是「留余量」的正确落点 —— 它改的是**画在哪**，而不是**范围是多少**：
 * 坐标轴范围仍严格等于定义域（刻度 `±B/2` 位置精确），但曲线的首末点与
 * 上下极值都不会顶到框线上，与轴之间留出一圈空隙。
 *
 * **横向取值沿革（用户四轮反馈收敛而来）**：
 * 1. 0% —— 曲线顶死在框线角上，「既不直观也看不出曲线特征、也不美观」；
 * 2. 5% —— 曲线仍占 90% 宽，用户仍反馈「曲线占满整个画出的横坐标范围」；
 * 3. 15% —— 曲线占绘图区 70%，用户认可但仍嫌「轴不明显」；
 * 4. **23.68%** —— 用户给出教材参考图（频谱插图）并明确「图像比例向它看齐」。
 *    对参考图做像素级测量后得到：**数据只占图宽 44.0%**，左右留白各约 28%。
 *
 * ⚠️ **这个值不能凭「内缩 = 1 - 44%」直接取 0.28**——基准不同：
 * 内缩是相对**绘图区**（已扣掉左右留白），而 44% 是相对**画布**。真正的公式是
 * ```
 * drawW = 画布宽 × (1 - LEFT_PAD - RIGHT_PAD) × (1 - 2×本文)
 * ```
 * 在左右留白都取 8.2% 的前提下，解 `drawW = 0.44 × 画布宽` 得本文 = **0.2368**。
 * 两侧留白对称则曲线自然居中（本值 + 对称留白 ⇒ 左右间距各 28%，零偏差）。
 *
 * 若改成别的留白，必须用上式重解，否则曲线会偏到一边（实测 8.2%/7.5% 时
 * 数据只占 37.1%、左右间距差 9.8px）。
 *
 * **纵向取 3%**：参考图里曲线两端几乎顶到标题下方，纵向余量远小于横向。
 * 早先取 7% 时曲线被压得偏扁，与参考图的「高瘦」观感不符。
 */
private const val PLOT_INSET_X_RATIO = 0.2368f
private const val PLOT_INSET_Y_RATIO = 0.03f

/** y 轴靠缩字号仍不够时，左侧留白最多再额外加宽的画布宽比例。 */
private const val LEFT_PAD_EXTRA_MAX_RATIO = 0.10f

/** y 轴刻度文案允许缩到的最小字号（相对基准字号）。再小就放弃缩字、改加宽留白。 */
internal const val Y_TICK_MIN_SIZE_SCALE = 0.62f

/** 标签被夹取到装不下时的省略号。 */
private const val ELLIPSIS = "…"

/**
 * [PlotBitmapRenderer.curveTopAt] 判定「采样点是否落在该竖线上」的 x 容差。
 *
 * 采样是等距的（900 个点在定义域上均分），所以该容差只需略大于一个采样步长
 * 就能稳稳命中边界处的那个端点。定义域跨度为 1 时步长约 0.0011，取 1e-3 量级足够；
 * 取得过大则会把远处的曲线误当成边界点，竖线会被画得过矮。
 */
private const val CURVE_TOP_X_TOLERANCE = 0.02

/** 坐标轴线宽（dp）：比网格（0.8）与曲线（2+）之间取一档，保证「看得见但不喧宾夺主」。 */
private const val AXIS_STROKE_DP = 1.5f

/** 定义域边界竖线的线宽（dp）。 */
private const val MARK_LINE_STROKE_DP = 1.2f

/**
 * x 轴横线左右**伸出绘图区**的量（相对画布宽）。
 *
 * 教材参考图的 x 轴是一条明显长于数据范围的长横线，右端还要留出轴名（如 `f`）的位置。
 * 左 5% / 右 7.5% 是照参考图实测的（轴左端在图宽 5%、右端在 92.5%）反推得来。
 * 注意右侧伸出量必须 ≤ `RIGHT_PAD_RATIO`，否则轴会画到画布外。
 */
private const val AXIS_OVERHANG_L_RATIO = 0.012f
private const val AXIS_OVERHANG_R_RATIO = 0.030f

/** y 轴竖线顶端**伸出绘图区上方**的量（相对绘图区高）。参考图里竖线明显高过曲线顶端。 */
private const val AXIS_VERT_OVERHANG_RATIO = 0.10f

/** y 轴竖线底端**穿过 x 轴**往下伸的量（相对画布高）。让两轴相交处有个出头，更像坐标轴。 */
private const val AXIS_VERT_BELOW_RATIO = 0.012f

/**
 * 坐标轴末端箭头的尺寸（相对**画布**宽/高，绝不能用 dp）。
 *
 * 箭臂长度：横向 0.011 × 画布宽、纵向 0.017 × 画布高。
 * 横向取值沿用 `docs/prototype/PlotAxisStyles.java` 里在 1000px 宽画布上比过的结果；
 * 纵向取 0.017 是为了让**视觉重量与横向相当**——标准画布 993×575 下横箭臂约 10.9px、
 * 纵箭臂约 9.8px，两者肉眼接近。若纵向也按 0.013 取（约 7.5px），y 轴箭头会明显
 * 比 x 轴的瘦一圈，两个箭头看着不成套（`plot-arrow-zoom.png` 里能看到这个差异）。
 *
 * [AXIS_ARROW_HALF_RATIO] 是箭臂张开半角的控制量：`half = len × 0.5`
 * 对应约 26.6° 的张开半角（`atan(0.5) ≈ 26.6°`），是常见的箭头形状。
 * 太大变钝、太小变尖细。
 *
 * 横纵各取**自身方向**的比例：绘图区约 1.8:1 偏扁，若纵向也用宽的比例，箭头会被
 * 压成扁尖、不像箭头。
 */
private const val AXIS_ARROW_X_LEN_RATIO = 0.011f
private const val AXIS_ARROW_Y_LEN_RATIO = 0.017f
private const val AXIS_ARROW_HALF_RATIO = 0.5f

/**
 * y 轴刻度文案的排版方案。
 *
 * 背景：y 轴的参数化刻度（`$N_0(2\pi f_c)^2$`）比纯数字宽得多，而左侧留白是按
 * 画布宽固定取比例的。以前的做法是「留白装不下就截断加省略号」，
 * 于是那条长文案只剩一个「…」，用户看到的是「左边稳定显示三个点」
 * （三个点正是中文省略号 … 的视觉形态）。
 *
 * 这里改成优先**缩字号**：只要缩到 [Y_TICK_MIN_SIZE_SCALE] 以上能装下，就用缩小的字号
 * 完整绘制；字号缩到下限仍装不下时，才返回所需的额外留白宽度，由调用方把绘图区右移。
 * 两条路结果都是**完整文案**，省略号不再是常态。
 *
 * @param widths   各刻度文案在 [baseSize] 下的测量宽度（取最大值作为约束）
 * @param available 当前可供 y 轴文案使用的宽度（左侧留白减去与轴的间隙）
 * @param baseSize 基准字号
 * @return 实际可用字号与「完整显示所需的总宽度」
 */
internal fun planYTickSize(
    widths: List<Float>,
    available: Float,
    baseSize: Float,
): YTickSizePlan {
    val widest = widths.filter { it.isFinite() && it > 0f }.maxOrNull() ?: 0f
    if (widest <= 0f || baseSize <= 0f) return YTickSizePlan(baseSize, 0f)
    if (available <= 0f) return YTickSizePlan(baseSize, widest)

    if (widest <= available) return YTickSizePlan(baseSize, widest)

    // 按宽度线性缩字号（字号与文本宽度近似成正比，够用且可预期）
    val scaled = baseSize * (available / widest)
    val minSize = baseSize * Y_TICK_MIN_SIZE_SCALE
    return if (scaled >= minSize) {
        YTickSizePlan(scaled, widest * (scaled / baseSize))
    } else {
        // 缩到下限仍不够 ⇒ 改用加宽留白，字号保持下限（此时缩放已到极限）
        YTickSizePlan(minSize, widest * (minSize / baseSize))
    }
}

/** [planYTickSize] 的结果：实际字号 + 该字号下最宽文案的宽度。 */
internal data class YTickSizePlan(val textSize: Float, val requiredWidth: Float)

/**
 * 一段标签的落位方案（由 [planLabelLayout] 纯函数算出）。
 *
 * @param keepCount 实际画出的前几个片段（0 表示一个片段都放不下）
 * @param startX    第一个片段的起点（已夹在 [leftLimit]..[rightLimit] 内）
 * @param truncated 是否被截断（调用方据此在末尾补省略号）
 * @param visible    是否有任何内容可画（false 时调用方直接跳过）
 */
internal data class LabelPlan(
    val keepCount: Int,
    val startX: Float,
    val truncated: Boolean,
    val visible: Boolean,
)

/**
 * 规划一段标签的落位：**把内容收进 `[leftLimit, rightLimit]`**，装不下就截断。
 *
 * 抽成纯函数的原因：这里全是几何决策，与绘图 API 无关，可以精确单测；
 * 而真机之外的环境（Robolectric）`Paint.measureText` 每字符恒返回 1px，
 * 根本量不出真实宽度 ⇒ 任何基于像素的断言都是假绿。
 *
 * 规则（按优先级）：
 * 1. 全部片段都装得下 → 原样画，起点按 [alignCenter] 居中或取 [anchorX]，
 *    随后把整体夹进边界（先贴左，再贴右）；
 * 2. 装不下 → 从左往右保留能装下的片段，**给省略号留出宽度**；
 * 3. 连一个片段 + 省略号都放不下 → 只画省略号；
 * 4. 边界无效（right ≤ left）→ 视为不可见，调用方跳过（防止画到奇怪的位置）。
 */
internal fun planLabelLayout(
    widths: List<Float>,
    gap: Float,
    ellipsisWidth: Float,
    anchorX: Float,
    alignCenter: Boolean,
    leftLimit: Float,
    rightLimit: Float,
): LabelPlan {
    if (widths.isEmpty()) return LabelPlan(0, anchorX, truncated = false, visible = false)
    val available = rightLimit - leftLimit
    if (available <= 0f) return LabelPlan(0, anchorX, truncated = false, visible = false)

    val fullWidth = widths.sum() + gap * (widths.size - 1)
    var keepCount = widths.size
    var total = fullWidth
    var truncated = false
    if (fullWidth > available) {
        truncated = true
        var acc = 0f
        var keep = 0
        for (i in widths.indices) {
            val add = widths[i] + if (i == 0) 0f else gap
            if (acc + add + ellipsisWidth <= available) {
                acc += add
                keep = i + 1
            } else {
                break
            }
        }
        keepCount = keep
        total = if (keep == 0) ellipsisWidth else acc + ellipsisWidth
    }

    var startX = if (alignCenter) anchorX - total / 2f else anchorX
    if (startX < leftLimit) startX = leftLimit
    if (startX + total > rightLimit) startX = rightLimit - total
    if (startX < leftLimit) startX = leftLimit
    return LabelPlan(keepCount, startX, truncated, visible = true)
}

/**
 * 坐标范围（可带折叠标记）。
 *
 * [foldedLow]/[foldedHigh] 为 true 表示该侧有数据落在范围之外、被**折叠**到边界：
 * 曲线在边界处被裁断，并在轴上画出断轴标记，提示「这里还有数据，只是被压缩显示」。
 */
internal data class AxisRange(
    val lo: Double,
    val hi: Double,
    val foldedLow: Boolean = false,
    val foldedHigh: Boolean = false,
)

/**
 * 平衡的坐标范围：**主体数据完整显示 + 适当边距 + 极端离群点折叠**。
 *
 * 背景：早先「模型给什么范围就用什么范围」会让曲线极值贴框（谷底压在坐标轴底线上）；
 * 而改成「必须覆盖全部数据」又走到另一个极端——个别离群点会把主体曲线压成一条线。
 * 现在折中（[fenceFactor] 为 Tukey 围栏系数）：
 * 1. 用 **Tukey 规则**识别离群点：`Q1 - k·IQR` / `Q3 + k·IQR` 之外的点才算离群；
 *    **没有离群点时主体 = 全量数据**（不像分位数那样把正常的极值也裁掉）；
 * 2. 模型显式给的范围优先，但**至少要覆盖主体范围**（否则视为把主体裁掉，会扩展它）；
 * 3. 最后统一加 [marginRatio] 边距，保证曲线不贴框线；
 * 4. 落在最终范围外的数据**不撑大坐标轴**，而是折叠到边界（由调用方裁剪 + 画断轴标记）。
 */
internal fun balancedRange(
    values: List<Double?>,
    specLo: Double?,
    specHi: Double?,
    marginRatio: Double = 0.06,
    fenceFactor: Double = 1.5,
): AxisRange {
    val finite = values.filter { it != null && it.isFinite() }.map { it!! }.sorted()
    if (finite.isEmpty()) return AxisRange(specLo ?: -1.0, specHi ?: 1.0)

    val dataLo = finite.first()
    val dataHi = finite.last()
    // Tukey 围栏：IQR 为 0（大量重复值，如阶梯/常数）时不裁剪，直接用完整范围
    val q1 = quantile(finite, 0.25)
    val q3 = quantile(finite, 0.75)
    val iqr = q3 - q1
    val lowerFence = q1 - fenceFactor * iqr
    val upperFence = q3 + fenceFactor * iqr
    val coreLo = finite.firstOrNull { it >= lowerFence } ?: dataLo
    val coreHi = finite.lastOrNull { it <= upperFence } ?: dataHi
    val (useLo, useHi) = if (iqr <= 0.0 || coreHi <= coreLo) dataLo to dataHi else coreLo to coreHi

    // 对非负图，0 是有语义的基线，不是普通数据点。带外频谱常用显式 y.min=0，
    // 也可能通过 points 把 y=0 的零谱段画出来；如果继续给主体下方加边距，
    // 零谱段就会浮在横轴上方（横轴当前位于绘图区底边）。只有确认数据没有实质
    // 负值时才启用此规则，含正负值的波形仍按正常范围留白。
    val hasZeroBaseline = useLo >= -1e-12 &&
        (specLo?.let { abs(it) <= 1e-12 } == true || abs(dataLo) <= 1e-12)

    var lo = specLo ?: useLo
    var hi = specHi ?: useHi
    // 模型范围不能裁掉主体（极少数情况下它给的窗口会把主要特征切掉）
    if (lo > useLo) lo = useLo
    if (hi < useHi) hi = useHi

    if (hi - lo <= 0.0) {
        val pad = (abs(lo) + 1.0) * 0.1
        lo -= pad
        hi += pad
    }
    // 边距按**主体范围**四周留白：极端点被折叠时，贴框的必须是「主体的边缘」而不是
    // 离群点，所以用主体跨度算边距、并且只做外扩（minOf/maxOf）——模型给的更宽窗口不会被缩掉。
    val coreMargin = ((useHi - useLo).takeIf { it > 0.0 } ?: 0.0) * marginRatio
    if (coreMargin > 0.0) {
        lo = minOf(lo, useLo - coreMargin)
        hi = maxOf(hi, useHi + coreMargin)
    }
    if (hasZeroBaseline) lo = 0.0
    return AxisRange(lo, hi, foldedLow = dataLo < lo, foldedHigh = dataHi > hi)
}

/** 线性插值分位数（[sorted] 必须已升序）。 */
private fun quantile(sorted: List<Double>, p: Double): Double {
    if (sorted.isEmpty()) return 0.0
    val idx = (sorted.size - 1) * p.coerceIn(0.0, 1.0)
    val lower = idx.toInt()
    val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
    val frac = idx - lower
    return sorted[lower] + (sorted[upper] - sorted[lower]) * frac
}

/**
 * x 轴（定义域）的坐标范围。
 *
 * 优先级（**模型显式声明的区间永远不被外扩**）：
 * 1. 模型给了 min/max ⇒ **原样采用**。这不是「视野」而是**定义域**：频谱题里
 *    `±B/2` 就是曲线的存在区间，外扩会让曲线看起来越过了自己的定义域边界
 *    （用户反馈「曲线稳定溢出该有的 -B/2 到 B/2 之间」）。
 *    留白在这里是有害的：外扩后 `±B/2` 的 markLine 竖线落进绘图区内部，
 *    曲线自然「穿过」了本该终止的那条线。
 * 2. 模型没给 ⇒ 用数据范围，**两端各留 [marginRatio] 边距**。
 *    这时若不留白，曲线首末点必然压在绘图区左右边框上，看起来像整张图被切断
 *    （用户反馈「左右看上去还是截断的」）——但那是「自动推断范围」的问题，
 *    应该在推断侧解决，而不是去改模型显式给的定义域。
 *
 * 单独的 min 或 max 只对缺的那一侧做推断 + 留白。
 */
internal fun conservativeRange(
    dataLo: Double,
    dataHi: Double,
    specLo: Double?,
    specHi: Double?,
    marginRatio: Double = X_MARGIN_RATIO,
): Pair<Double, Double> {
    var lo = minOf(specLo ?: dataLo, dataLo)
    var hi = maxOf(specHi ?: dataHi, dataHi)

    // 模型显式声明了**合法且能覆盖数据**的区间 ⇒ 这是定义域，原样返回。
    // 采样本就在该区间内，所以点集不会越出；曲线终止在两端正是我们想要的
    // （用户反馈「曲线稳定溢出该有的 -B/2 到 B/2 之间」就是被外扩害的）。
    //
    // 但定义域**不能裁掉数据**：模型偶尔会给一个比实际曲线更窄的窗口，
    // 这时必须扩展到覆盖数据（否则曲线被无声截断），也就退回到下面带留白的推断分支。
    val specCoversData = specLo != null && specHi != null &&
        specHi > specLo && specLo <= dataLo + CARET_EPS && specHi >= dataHi - CARET_EPS
    if (specCoversData) return specLo!! to specHi!!

    val span = hi - lo
    if (span <= 0.0 || !span.isFinite()) return lo to hi
    val pad = span * marginRatio
    val eps = span * 1e-6
    // 只给**推断出来的那一侧**留白：模型给了边界、且该边界没被数据推翻的那侧要保持精确
    if ((specLo == null || specLo > dataLo) && dataLo <= lo + eps) lo -= pad
    if ((specHi == null || specHi < dataHi) && dataHi >= hi - eps) hi += pad
    return lo to hi
}

/** 判断「模型给的边界是否已覆盖数据」时的容差（相对跨度），吸收浮点与采样步长的误差。 */
private const val CARET_EPS = 1e-9

/** x 轴两端的最小留白比例（相对横轴跨度）。 */
internal const val X_MARGIN_RATIO = 0.04

/** `$...$` / `$$...$$` 分组（非贪婪，取第一对定界符之间的内容）。 */
private val DOLLAR_LATEX_GROUP = Regex("""\$\$?(.+?)\$\$?""")

/** 反斜杠命令（`\frac`、`\sum`…）：没有美元符号时用它判断整串是不是公式。 */
/**
 * 数学式特征：下标/上标、LaTeX 命令，或数学运算符。
 *
 * 出现任一就认为这串是**数学式**而不是普通文字，交给公式引擎排版。
 * 刻度与轴标签里 `f_c`、`f_c-B/2`、`-B/2` 这种写法极常见：以前只认反斜杠命令，
 * 于是这类标签被当成纯文本、原样画成 `-f_c-B/2`（下划线还在），
 * 用户看到的就是「坐标轴上的注释没有公式渲染」（v1.0.35 反馈）。
 *
 * 运算符里**故意不含 `-`**：连字符在普通文字里太常见（`state-of-the-art`），
 * 但 `-B/2` 这种带 `/` 的仍会被命中原子的 `/`。
 */
private val LATEX_HINT = Regex("""[_^\\]|[<>=+*/]""")

/**
 * 给 `\frac` 的裸参数补上花括号：`\frac B2` → `\frac{B}{2}`。
 *
 * 标准 LaTeX 允许 `\frac B2`（两个单 token），但 **JLatexMath 不接受**，
 * 会抛 ParseException，图上就只剩一块「公式无法渲染」的灰色占位
 * （用户反馈的那条 `\frac B2<|f|<f_c+\frac B2` 整条渲染失败即是此因）。
 * 这里只补花括号、不动其它结构；本来就是 `\frac{}{}` 的写法是幂等的。
 */
private val FRAC_BRACELESS =
    Regex("""\\frac\s*(\{[^{}]*\}|[A-Za-z0-9])\s*(\{[^{}]*\}|[A-Za-z0-9])""")

/** 规范化图内公式：见 [FRAC_BRACELESS]。 */
internal fun normalizeLatexFractions(latex: String): String =
    FRAC_BRACELESS.replace(latex) { m ->
        val num = m.groupValues[1].removeSurrounding("{", "}")
        val den = m.groupValues[2].removeSurrounding("{", "}")
        """\frac{$num}{$den}"""
    }

/**
 * CJK 及全角标点区间。这类字符**不能**交给 JLatexMath：
 * 其内置字体没有中文字形，字符宽度退化为 0（实测「功率谱」与
 * 「功率谱功率谱功率谱」量出的宽度同为 12px）⇒ 所有汉字堆叠在同一坐标，
 * 图上文字重叠成一团（用户反馈的「文字全挤在一起」）。
 */
private val CJK_RANGE = Regex("""[\u2E80-\u9FFF\uF900-\uFAFF\uFE30-\uFE4F\uFF00-\uFFEF\u3000-\u303F]""")

/** 该串是否含中文字符（含 CJK 标点与全角形式）。 */
internal fun containsCjk(text: String): Boolean = CJK_RANGE.containsMatchIn(text)

/**
 * 把标签切成「公式 / 普通文字」交替的片段。
 *
 * 规则：
 * 1. `$...$` 分组 → 公式片段；**公式体内含中文时退回普通文字**（否则会叠成一团）；
 * 2. 分组之外的内容 → 普通文字片段（含 LaTeX 命令时由 `prettifyPlotLabel` 降级成 Unicode）；
 * 3. 整串没有美元分组、也不含中文、但含数学式特征（`_` / `^` / 反斜杠命令）→ 整串当公式；
 * 4. 其余情况 → 一个普通文字片段（保持旧行为）。
 *
 * 第 3 条是 v1.0.35 补的：以前只认反斜杠命令，于是 `f_c-B/2` 这类**纯符号刻度**
 * 被当纯文本原样画出（下划线还在），用户看到「坐标轴上的注释没有公式渲染」。
 *
 * 所有进公式引擎的串都会先过 [normalizeLatexFractions]，
 * 避免 `\frac B2` 这种写法直接抛 ParseException。
 *
 * 这样「功率谱密度 $S_c(f)$」会拆成两段依次排布，中文与公式各自清晰可读。
 */
internal fun splitLabelPieces(raw: String): List<LabelPiece> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return emptyList()

    val pieces = mutableListOf<LabelPiece>()
    var cursor = 0
    for (match in DOLLAR_LATEX_GROUP.findAll(trimmed)) {
        val before = trimmed.substring(cursor, match.range.first).trim()
        if (before.isNotEmpty()) pieces += LabelPiece(text = before)
        val body = match.groupValues[1].trim()
        when {
            body.isEmpty() -> Unit
            containsCjk(body) -> pieces += LabelPiece(text = body)
            else -> pieces += LabelPiece(latex = normalizeLatexFractions(body))
        }
        cursor = match.range.last + 1
    }
    val tail = trimmed.substring(cursor).trim()
    if (tail.isNotEmpty()) pieces += LabelPiece(text = tail)

    if (pieces.isEmpty()) return listOf(LabelPiece(text = trimmed))
    if (pieces.size == 1 && pieces[0].latex == null &&
        !containsCjk(trimmed) && LATEX_HINT.containsMatchIn(trimmed)
    ) {
        return listOf(LabelPiece(latex = normalizeLatexFractions(trimmed)))
    }
    return pieces
}
