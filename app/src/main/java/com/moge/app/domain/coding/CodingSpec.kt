package com.moge.app.domain.coding

import kotlinx.serialization.Serializable

/** D^0 is the current input; state bits are ordered newest first. */
@Serializable
data class CodingSpec(
    val version: Int = 1,
    val memory: Int = 0,
    val generators: List<List<Int>> = emptyList(),
    val n: Int = 0,
    val k: Int = 0,
    val generatorExponents: List<Int> = emptyList(),
    val outputMode: String = "serial",
) {
    fun validateConvolutional(stateGraph: Boolean = false) {
        require(version == 1) { "不支持的编码参数版本" }
        require(memory in 1..8) { "卷积码记忆级数需为 1～8" }
        require(!stateGraph || memory <= 3) { "完整状态图最多支持 8 个状态，请使用状态转移表说明更大的状态空间" }
        require(generators.size in 1..4) { "请提供 1～4 路输出的生成多项式" }
        generators.forEach { taps ->
            require(taps.isNotEmpty() && taps.size <= memory + 1 &&
                taps.distinct().size == taps.size && taps.all { it in 0..memory }) { "生成多项式抽头指数非法或重复" }
        }
        require(generators.any { memory in it }) { "记忆级数与生成多项式最高次数不一致" }
        require(outputMode in setOf("serial", "parallel")) { "输出方式需为 serial 或 parallel" }
    }

    fun validateCyclic() {
        require(version == 1) { "不支持的编码参数版本" }
        require(n in 2..63 && k in 1 until n && n - k in 1..8) { "循环码需满足 1≤k<n≤63，校验位数为 1～8" }
        val degree = n - k
        require(generatorExponents.distinct().size == generatorExponents.size &&
            generatorExponents.all { it in 0..degree } && 0 in generatorExponents && degree in generatorExponents) {
            "生成多项式需为 n−k 次，最高项和常数项为 1，抽头不能重复"
        }
        val modulus = Gf2Polynomial.mask(generatorExponents)
        require(Gf2Polynomial.xPowerRemainder(n, modulus) == 1L) { "生成多项式不能整除 xⁿ+1，不是该码长的循环码" }
    }
}

object Gf2Polynomial {
    fun mask(exponents: List<Int>): Long = exponents.fold(0L) { value, exponent -> value or (1L shl exponent) }
    fun remainder(value: Long, divisor: Long): Long {
        require(value >= 0 && divisor > 1)
        var result = value
        val degree = 63 - java.lang.Long.numberOfLeadingZeros(divisor)
        for (bit in 62 downTo degree) {
            if ((result and (1L shl bit)) != 0L) result = result xor (divisor shl (bit - degree))
        }
        return result
    }
    fun xPowerRemainder(exponent: Int, divisor: Long): Long {
        var result = 1L
        repeat(exponent) { result = remainder(result shl 1, divisor) }
        return result
    }
}

data class CodeTransition(val from: Int, val input: Int, val to: Int, val output: List<Int>) {
    val label: String get() = output.joinToString("") + "($input)"
}

object ConvolutionalCode {
    fun step(spec: CodingSpec, state: Int, input: Int): CodeTransition {
        spec.validateConvolutional()
        require(state in 0 until (1 shl spec.memory) && input in 0..1)
        val outputs = spec.generators.map { taps ->
            taps.fold(0) { parity, tap ->
                parity xor if (tap == 0) input else ((state shr (spec.memory - tap)) and 1)
            }
        }
        return CodeTransition(state, input, (input shl (spec.memory - 1)) or (state shr 1), outputs)
    }
    fun transitions(spec: CodingSpec): List<CodeTransition> {
        spec.validateConvolutional(stateGraph = true)
        return (0 until (1 shl spec.memory)).flatMap { state -> (0..1).map { step(spec, state, it) } }
    }
    fun encode(spec: CodingSpec, input: List<Int>): List<Int> {
        var state = 0
        return input.flatMap { bit -> step(spec, state, bit).also { state = it.to }.output }
    }
}

object CyclicCode {
    /** Systematic serial encoder: message bits first, then the remainder, MSB first. */
    fun encode(spec: CodingSpec, message: List<Int>): List<Int> {
        spec.validateCyclic()
        require(message.size == spec.k && message.all { it in 0..1 })
        val degree = spec.n - spec.k
        val mask = (1 shl degree) - 1
        val taps = Gf2Polynomial.mask(spec.generatorExponents).toInt() and mask
        var register = 0
        message.forEach { bit ->
            val feedback = bit xor ((register shr (degree - 1)) and 1)
            register = ((register shl 1) and mask) xor if (feedback == 1) taps else 0
        }
        return message + (degree - 1 downTo 0).map { (register shr it) and 1 }
    }
}
