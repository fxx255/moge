package com.moge.app.domain.diagram

/** 布局产物的几何描述：全部是相对画布左上角的 px 坐标（未乘 density）。 */

data class Size(val width: Float, val height: Float)

data class DiagramPoint(val x: Float, val y: Float)

data class NodeBox(
    val node: DiagramNode,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val column: Int,
    val row: Int,
) {
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f
}

data class EdgeRoute(
    val edge: DiagramEdge,
    val start: DiagramPoint,
    val end: DiagramPoint,
    val startPort: DiagramPort = DiagramPort.AUTO,
    val endPort: DiagramPort = DiagramPort.AUTO,
    val points: List<DiagramPoint> = listOf(start, end),
)

data class DiagramLayoutResult(
    val width: Float,
    val height: Float,
    val title: String,
    val nodes: List<NodeBox>,
    val edges: List<EdgeRoute>,
    /** row → 该行的节点 id，便于测试断言「主链在 0 行、支路在下」。 */
    val rows: Map<Int, List<String>>,
    val annotations: List<DiagramAnnotation> = emptyList(),
)

/** Centered, measured annotation outside the signal nodes (e.g. bank ellipses). */
data class DiagramAnnotation(val text: String, val centerX: Float, val centerY: Float)

/** 绘制尺寸常量。 */
object DiagramMetrics {
    const val MIXER_DIAMETER = 64f
    const val JUNCTION_DIAMETER = 14f
    const val BLOCK_HEIGHT = 60f
    const val BLOCK_HEIGHT_TWO_LINE = 78f
    const val BLOCK_MIN_WIDTH = 96f
    /** 矩形框内文本的最大可用宽度：超过就**折行**，不缩字号。 */
    const val BLOCK_MAX_WIDTH = 260f
    const val BLOCK_PADDING_X = 18f
    const val BLOCK_PADDING_Y = 14f
    const val IO_HEIGHT = 34f
    const val IO_HEIGHT_TWO_LINE = 52f
    const val IO_MIN_WIDTH = 40f
    /** 无框文本的换行宽度上限。 */
    const val IO_MAX_WIDTH = 220f
    const val IO_PADDING = 6f
    const val TITLE_BAND = 40f
    const val MIN_WIDTH = 320f
    const val MIN_HEIGHT = 160f
    const val ARROW_LENGTH = 11f
    const val ARROW_HALF_WIDTH = 5.5f
    const val LABEL_GAP = 6f
}
