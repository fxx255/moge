package com.moge.app.data.parse

import com.moge.app.domain.diagram.DiagramSpec
import com.moge.app.domain.plot.PlotSpec

/**
 * 一轮回答里的一个图表槽位。
 *
 * [Missing] 是**占位**：模型给了一个无法解析的图（缺标题、参数非法），
 * 这一格仍然要占号 —— 否则后面 `[[FIGURE:n]]` 的锚点会整体错位，
 * 正文说「见下图」而图指向了别的图。
 */
sealed interface ReplyFigure {
    data class Plot(val spec: PlotSpec) : ReplyFigure
    data class Diagram(val spec: DiagramSpec) : ReplyFigure
    data object Missing : ReplyFigure
}

/** 工具图保留预留图号；内联协议按 plots、diagrams 排序。两者都包含失败槽位。 */
fun ParsedReply.orderedFigures(): List<ReplyFigure> =
    toolFigures ?: (plotSlots.map { it?.let(ReplyFigure::Plot) ?: ReplyFigure.Missing } +
        diagramSlots.map { it?.let(ReplyFigure::Diagram) ?: ReplyFigure.Missing })
