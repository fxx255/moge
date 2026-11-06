package com.moge.app.domain.coding

import kotlinx.serialization.Serializable

/** Time steps include the memory-length zero tail when terminated is true. */
@Serializable
data class TrellisSpec(
    val version: Int = 1,
    val steps: Int = 7,
    val terminated: Boolean = true,
    val initialState: Int = 0,
    val mode: String = "outputs",
    val receivedBits: String = "",
    val inputBits: String = "",
) {
    fun validate(code: CodingSpec) {
        code.validateConvolutional(stateGraph = true)
        require(version == 1) { "不支持的网格图参数版本" }
        require(steps in 1..16 && (!terminated || steps >= code.memory)) { "网格图支持 1～16 拍，终止图须包含记忆级数对应的补零拍" }
        require(initialState in 0 until (1 shl code.memory)) { "网格图初态超出状态范围" }
        require(mode in setOf("outputs", "distances", "survivors")) { "网格图模式需为 outputs、distances 或 survivors" }
        require(receivedBits.all { it == '0' || it == '1' } &&
            (receivedBits.isEmpty() || receivedBits.length == steps * code.generators.size)) {
            "接收序列需为 steps × 输出路数个二进制位，包含尾部补零对应的接收位"
        }
        require(mode == "outputs" || receivedBits.isNotEmpty()) { "距离图与幸存路径图需要完整接收序列 receivedBits" }
        require(inputBits.all { it == '0' || it == '1' } &&
            (inputBits.isEmpty() || inputBits.length == steps - if (terminated) code.memory else 0)) {
            "inputBits 应为信息位，终止图由客户端自动补 memory 个 0，总长须与 steps 一致"
        }
        require(mode != "survivors" || inputBits.isEmpty()) { "幸存路径按接收序列译码，不使用指定输入路径" }
    }
}
