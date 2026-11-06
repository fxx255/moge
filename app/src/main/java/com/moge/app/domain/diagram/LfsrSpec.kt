package com.moge.app.domain.diagram

import kotlinx.serialization.Serializable

/** Fibonacci LFSR. Tap 1 is the output of the leftmost (input) register. */
@Serializable
data class LfsrSpec(
    val version: Int = 1,
    val stages: Int = 0,
    val taps: List<Int> = emptyList(),
    /** Device names inside the boxes, independent of their output signals. */
    val registerLabels: List<String> = emptyList(),
    val stageOutputLabels: List<String> = emptyList(),
    val clockLabel: String = "CP",
    val outputLabel: String = "输出",
) {
    fun validate() {
        require(version == 1) { "不支持的 lfsr 版本" }
        require(stages in 2..16) { "LFSR 支持 2～16 级寄存器" }
        require(taps.isNotEmpty() && taps.size == taps.distinct().size && taps.all { it in 1..stages }) {
            "taps 是从左侧输入端起、从 1 编号的寄存器输出抽头，不能重复或越界"
        }
        require(registerLabels.isEmpty() || registerLabels.size == stages) { "registerLabels 必须与级数一致" }
        require(registerLabels.all { it.isNotBlank() && it.length <= 40 && '\n' !in it }) { "寄存器标签应为不超过 40 字符的单行文字或公式" }
        require(stageOutputLabels.isEmpty() || stageOutputLabels.size == stages) { "stageOutputLabels 必须与级数一致" }
        require(stageOutputLabels.all { it.isNotBlank() && it.length <= 40 && '\n' !in it }) { "每级输出标签应为不超过 40 字符的单行文字或公式" }
        require(clockLabel.length <= 16 && outputLabel.length <= 24 && '\n' !in clockLabel && '\n' !in outputLabel) {
            "时钟和输出标签过长或含换行"
        }
    }

    fun registerLabel(index: Int): String = registerLabels.getOrNull(index)
        ?.takeUnless { it.trim() == "寄存器" || legacyOutputLabel(index) != null } ?: "\$D_{$index}\$"

    fun stageOutputLabel(index: Int): String = stageOutputLabels.getOrNull(index)
        ?: legacyOutputLabel(index) ?: "\$a_{$index}\$"

    // Earlier specs placed a_n / a0 in registerLabels. Keep those signals on their
    // original stage outputs when reopening a stored diagram from that template.
    private fun legacyOutputLabel(index: Int): String? = registerLabels.getOrNull(index)?.takeIf {
        LEGACY_STATE_LABEL.matches(it.trim().removeSurrounding("$").trim())
    }

    companion object {
        private val LEGACY_STATE_LABEL = Regex("""a(?:_\{[^{}]+\}|_[A-Za-z0-9+−\-]+|[0-9₀₁₂₃₄₅₆₇₈₉]+)""")
    }
}
