package com.moge.app.domain.diagram

import kotlinx.serialization.Serializable
import com.moge.app.domain.coding.CodingSpec

/**
 * 结构化框图 spec：由模型给出的**拓扑**（节点 / 连线），客户端负责布局与绘制。
 *
 * 设计边界：
 * - 模型**只给拓扑与语义**，不给坐标 —— 坐标由本地布局器计算，
 *   因此不需要远程 Mermaid / WebView / JS，也不依赖模型画图能力；
 * - 规模有硬上限（见 [DiagramLimits]），超出即拒收该图并给警告，
 *   坏图只丢自己，绝不影响正文；
 * - 标签可以是纯中文，也可以含 `$...$` 公式，渲染时统一交给 JLatexMath。
 */

/** 盒子 / 圆形 / 端口记号等形状。 */
@Serializable
enum class DiagramNodeShape {
    /** 矩形功能框（低通滤波器、放大器、检波器…）。 */
    BLOCK,

    /** 圆形乘法器（⊗）或相加器（Σ，用 [DiagramNode.glyph] 区分）。 */
    MIXER,

    /** 求和 / 合流点。 */
    SUM,

    /** 无框标注：输入、输出、以及任意说明文字。 */
    IO,

    /** 分支 / 交点。 */
    JUNCTION,

    /** Tall serial/parallel or multiplexing block with separate signal ports. */
    BUS,

    /** Open sampling switch; label above, sampling instant below. */
    SAMPLER,
}

/** 连线两侧挂接的端口（不需要模型给绝对坐标）。 */
@Serializable
enum class DiagramPort {
    LEFT, RIGHT, TOP, BOTTOM, AUTO,
}

@Serializable
data class DiagramNode(
    val id: String,
    val label: String,
    val shape: DiagramNodeShape,
    /** MIXER 里的符号：默认 `×`；相加器可写 `+`。 */
    val glyph: String? = null,
    /** 可选副标题（如型号、参数，写在框内第二行）。 */
    val subLabel: String? = null,
    /** 该节点的分支提示：用于把某条支路放到主链下方（例如本地载波支路）。 */
    val row: Int? = null,
    /** 主链上的次序提示（越大越靠右）。 */
    val column: Int? = null,
    /**
     * Optional semantic role used by a layout profile.  It is deliberately a
     * string so new model vocabulary can be ignored by older clients without
     * changing the wire format; the built-in profiles only consume known
     * aliases.
     */
    val role: String? = null,
)

/**
 * Older model responses sometimes omitted `shape:"mixer"` and only sent a
 * role or the `×` glyph.  Keep those diagrams visually compatible with the
 * textbook profile instead of treating the multiplier as a rectangular block.
 */
internal fun DiagramNode.renderShape(): DiagramNodeShape {
    if (shape == DiagramNodeShape.BUS || shape == DiagramNodeShape.SAMPLER) return shape
    if (shape == DiagramNodeShape.MIXER || shape == DiagramNodeShape.SUM) return shape
    val roleKey = role.orEmpty().trim().lowercase()
        .replace('-', '_').replace(' ', '_')
    val labelKey = label.trim().lowercase()
    val glyphKey = glyph.orEmpty().trim()
    if (roleKey.contains("sum") || roleKey.contains("adder") || roleKey.contains("merge") ||
        labelKey.contains("求和") || labelKey.contains("加法") || glyphKey in setOf("+", "＋", "Σ", "∑")) {
        return DiagramNodeShape.SUM
    }
    if (roleKey.contains("mixer") || roleKey.contains("mult") ||
        glyphKey in setOf("×", "x", "X", "✕", "⊗") || labelKey in setOf("乘法器", "乘法", "×", "⊗")) {
        return DiagramNodeShape.MIXER
    }
    if (roleKey in setOf("input", "output", "source", "signal_in", "signal_out") ||
        labelKey in setOf("输入", "输出", "input", "output", "source")) {
        return DiagramNodeShape.IO
    }
    return shape
}

@Serializable
data class DiagramEdge(
    val from: String,
    val to: String,
    val label: String? = null,
    val fromPort: DiagramPort = DiagramPort.AUTO,
    val toPort: DiagramPort = DiagramPort.AUTO,
    /** 虚线：可选、备注性质的连接。 */
    val dashed: Boolean = false,
    /** 合流节点的输入符号，例如求和器上方为 +、下方为 −。 */
    val polarity: String? = null,
    /** Signal lane in a parallel bundle (not a pixel coordinate). */
    val channel: Int? = null,
)

@Serializable
data class DiagramSpec(
    val title: String,
    val nodes: List<DiagramNode> = emptyList(),
    val edges: List<DiagramEdge> = emptyList(),
    /** 期望流向：row/column 缺失用于主推断。 */
    val direction: DiagramDirection = DiagramDirection.LR,
    /** Deterministic local arrangement; omitted in old specs. */
    val profile: DiagramLayoutProfile = DiagramLayoutProfile.GENERIC,
    /** The last drawn lane represents the end of a larger parallel bank. */
    val repeatLastLane: Boolean = false,
    val coding: CodingSpec? = null,
)

@Serializable
enum class DiagramDirection { LR, TB }

/**
 * Local layout profile.  The model still describes topology; a profile only
 * selects a deterministic, textbook-like arrangement for a known family of
 * diagrams.  [GENERIC] keeps the original graph layout for old saved specs.
 */
@Serializable
enum class DiagramLayoutProfile {
    GENERIC,
    TEXTBOOK_DUAL_BRANCH,
    /** Coherent I/Q receiver: parallel signal lanes with shared recovery/control paths. */
    IQ_DEMODULATOR,
    /** Topology-driven chains, parallel banks and serial/parallel communication systems. */
    COMMUNICATION,
    CONVOLUTIONAL_ENCODER,
    CYCLIC_ENCODER,
    CONVOLUTIONAL_STATE_GRAPH,
}

fun DiagramLayoutProfile.isCoding(): Boolean = this in setOf(
    DiagramLayoutProfile.CONVOLUTIONAL_ENCODER,
    DiagramLayoutProfile.CYCLIC_ENCODER,
    DiagramLayoutProfile.CONVOLUTIONAL_STATE_GRAPH,
)

/** 规模上限。超出的图拒收，而不是画成一团看不清的东西。 */
object DiagramLimits {
    const val MAX_DIAGRAMS = 4
    const val MAX_NODES = 24
    const val MAX_EDGES = 40
    const val MAX_LABEL_CHARS = 60
    const val MAX_SUB_LABEL_CHARS = 40
    const val MAX_ROLE_CHARS = 32
    const val MAX_TITLE_CHARS = 40
    const val MAX_ID_CHARS = 24
    const val MAX_ROWS = 4
    const val MAX_COLUMNS = 16
    const val MAX_CHANNELS = 4
}
