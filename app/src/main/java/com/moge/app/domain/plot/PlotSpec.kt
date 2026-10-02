package com.moge.app.domain.plot

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.tan
import kotlin.math.asin
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.sinh
import kotlin.math.cosh
import kotlin.math.tanh

/*
 * 方案 A 的核心层：结构化参数 → 点集。
 *
 * 这一层**完全不碰绘图 API**，所以 PC 上用 Java2D、Android 上用 Canvas，
 * 代码都可以原样复用；而且它是纯函数，可以直接写单测。
 */

/** 一条数据序列：表达式与点集二选一，最终都变成点集走同一条渲染路径。 */
@Serializable
data class Series(
    val label: String = "",
    val expr: String? = null,
    val points: List<Pair<Double, Double>>? = null,
    /** line | dashed | dotted | dashdot | marker | line_marker */
    val style: String = "line",
    val fill: Boolean = false,
    val colorIndex: Int = 0,
    val opacity: Double = 1.0,
    val width: Float = 2f,
    val color: String? = null,
    val markerShape: String = "circle",
    val markerSize: Float = 3f,
)

@Serializable
data class Axis(
    val label: String = "",
    val unit: String = "",
    /** 为空表示按数据自动求范围（会留边距）。 */
    val min: Double? = null,
    val max: Double? = null,
    val grid: Boolean = true,
    /** 显式刻度；为空则用 [niceTicks] 自动生成。 */
    val ticks: List<Double>? = null,
    /** 刻度文案覆盖，例如把 5.0 显示成 "f_c"。 */
    val tickLabels: Map<Double, String> = emptyMap(),
    /**
     * 坐标轴位置策略。仅横轴当前使用：auto 在显示范围包含 0 时穿过 y=0，
     * 否则放在绘图区底部；bottom 强制放在底部；origin 要求尽量放在原点。
     */
    val position: String = "auto",
)

@Serializable
data class MarkLine(val x: Double? = null, val y: Double? = null, val label: String? = null)

@Serializable
data class MarkArea(val x0: Double, val x1: Double, val label: String? = null,
    val y0: Double? = null, val y1: Double? = null, val colorIndex: Int = 1,
    val color: String? = null, val opacity: Double = .12, val pattern: String = "solid")

/** A region between two functions, or a closed polygon in data coordinates. */
@Serializable
data class ShadeRegion(
    val x0: Double? = null, val x1: Double? = null,
    val upper: String = "0", val lower: String = "0",
    val points: List<Pair<Double, Double>>? = null,
    val colorIndex: Int = 0, val color: String? = null,
    val opacity: Double = .18, val pattern: String = "solid", val label: String? = null,
)

@Serializable
data class PlotSpec(
    val title: String = "",
    val x: Axis = Axis(),
    val y: Axis = Axis(),
    val series: List<Series> = emptyList(),
    val legend: Boolean = false,
    val markLines: List<MarkLine> = emptyList(),
    val markAreas: List<MarkArea> = emptyList(),
    val widthPx: Int = 960,
    val heightPx: Int = 560,
    val shades: List<ShadeRegion> = emptyList(),
)

/**
 * 生成「好看」的刻度值（1 / 2 / 5 × 10ⁿ 序列）。
 *
 * 这是图显得专业还是业余的分水岭：直接用 min/max 等分会出现 0.3333 这种刻度，
 * 而 nice ticks 只会给出 0.2 / 0.5 / 1 这类读数舒服的值。纯函数，可单测。
 */
fun niceTicks(min: Double, max: Double, target: Int = 6): List<Double> {
    if (!min.isFinite() || !max.isFinite() || max <= min) return listOf(min)
    val rawStep = (max - min) / target
    val magnitude = 10.0.pow(floor(log10(rawStep)))
    val normalized = rawStep / magnitude
    // 取「最接近」的 1/2/5/10，而不是向上取到最近档：rawStep=3 时向上会跳到 5，
    // 刻度只剩 -5/0/5 三个，图看起来很空。[minByOrNull] 取第一个最小者，
    // 所以 1.5 这种正好在中点的值会落到 1。
    val step = listOf(1.0, 2.0, 5.0, 10.0)
        .minByOrNull { abs(it - normalized) }!!
        .times(magnitude)
    // 逐次累加会带出 0.6000000000000001 这类浮点尾巴，按步长精度舍回去，
    // 否则刻度文案、markLine 匹配都会被脏值影响。
    val precision = 10.0.pow(-floor(log10(step)))
    val result = mutableListOf<Double>()
    var v = ceil(min / step - 1e-9) * step
    var guard = 0
    while (v <= max + step * 1e-9 && guard < 200) {
        val rounded = round(v * precision) / precision
        result += if (abs(rounded) < step * 1e-6) 0.0 else rounded
        v += step
        guard++
    }
    return result
}

/**
 * 安全表达式求值器（shunting-yard）。
 *
 * 白名单函数 + 白名单变量，绝不使用脚本引擎 —— 模型输出的字符串永远不会被当代码执行。
 * 与手机端原型完全同一套规则，包括：一元负号优先级、^ 右结合、隐式乘法。
 */
object ExprEval {

    private val consts = mapOf("pi" to PI, "e" to kotlin.math.E)

    private val funcs: Map<String, (Double) -> Double> = mapOf(
        "sin" to ::sin, "cos" to ::cos, "tan" to ::tan,
        "asin" to ::asin, "acos" to ::acos, "atan" to ::atan,
        "exp" to ::exp, "ln" to ::ln, "log" to ::log10,
        "log2" to { x: Double -> ln(x) / ln(2.0) },
        "sqrt" to ::sqrt, "abs" to ::abs, "floor" to ::floor, "ceil" to ::ceil,
        "round" to { x: Double -> floor(x + 0.5) }, "sign" to ::sign,
        "sinh" to ::sinh, "cosh" to ::cosh, "tanh" to ::tanh,
    )

    private sealed interface Tok {
        data class Num(val v: Double) : Tok
        data class Name(val v: String) : Tok
        data class Op(val v: String) : Tok
        data class Func(val v: String) : Tok
    }

    // 用 Double 存优先级：一元负号要占 2.5 这个「介于 * 和 ^ 之间」的位置
    private val precedence = mapOf(
        "+" to 1.0, "-" to 1.0, "*" to 2.0, "/" to 2.0, "^" to 3.0,
    )

    // 一元负号优先级：低于 ^(3)、高于 *(2) ⇒ -x^2 解析为 -(x^2)，-x*y 解析为 (-x)*y
    private const val UNARY_PREC = 2.5

    fun compile(expr: String): (Double) -> Double {
        val rpn = toRpn(insertImplicitMul(tokenize(expr)))
        // 编译期就把未知符号挑出来：只生成逆波兰式的话，写错的表达式要等到求值
        // （也就是渲染已经开始之后）才暴露。变量只允许 x，其余必须命中常量表。
        for (tok in rpn) {
            if (tok is Tok.Name && tok.v != "x" && !consts.containsKey(tok.v)) {
                throw IllegalArgumentException("未知符号 ${tok.v}")
            }
        }
        return { x -> eval(rpn, x) }
    }

    private fun tokenize(src: String): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    var j = i
                    while (j < src.length && (src[j].isDigit() || src[j] == '.')) j++
                    out += Tok.Num(src.substring(i, j).toDouble())
                    i = j
                }
                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_')) j++
                    out += Tok.Name(src.substring(i, j))
                    i = j
                }
                c in "+-*/^()," -> {
                    out += Tok.Op(c.toString())
                    i++
                }
                else -> throw IllegalArgumentException("非法字符 $c")
            }
        }
        return out
    }

    /** 把 2x / 2(x+1) / (x+1)(x-1) 这类隐式乘法补成显式 * —— 模型很爱这么写。 */
    private fun insertImplicitMul(tokens: List<Tok>): List<Tok> {
        val out = mutableListOf<Tok>()
        for (t in tokens) {
            val prev = out.lastOrNull()
            if (prev != null) {
                val prevIsValue = when (prev) {
                    is Tok.Num -> true
                    is Tok.Name -> !(funcs.containsKey(prev.v) && t is Tok.Op && t.v == "(")
                    is Tok.Op -> prev.v == ")"
                    else -> false
                }
                val curStartsValue = t is Tok.Num || t is Tok.Name ||
                    (t is Tok.Op && t.v == "(")
                if (prevIsValue && curStartsValue) out += Tok.Op("*")
            }
            out += t
        }
        return out
    }

    private fun toRpn(tokens: List<Tok>): List<Tok> {
        val out = mutableListOf<Tok>()
        val ops = ArrayDeque<Tok>()
        var prev: Tok? = null

        fun isOpenParen(t: Tok?) = t is Tok.Op && t.v == "("

        for (k in tokens.indices) {
            val tk = tokens[k]
            when (tk) {
                is Tok.Num -> {
                    out += tk
                    prev = tk
                }
                is Tok.Name -> {
                    val next = tokens.getOrNull(k + 1)
                    if (funcs.containsKey(tk.v) && next is Tok.Op && next.v == "(") {
                        ops.addLast(Tok.Func(tk.v))
                    } else {
                        out += tk
                    }
                    prev = tk
                }
                is Tok.Op -> {
                    when (tk.v) {
                        "(" -> {
                            ops.addLast(tk)
                            prev = tk
                        }
                        ")" -> {
                            while (ops.isNotEmpty() && !isOpenParen(ops.last())) out += ops.removeLast()
                            if (ops.isNotEmpty()) ops.removeLast()
                            if (ops.isNotEmpty() && ops.last() is Tok.Func) out += ops.removeLast()
                            prev = tk
                        }
                        "," -> {
                            while (ops.isNotEmpty() && !isOpenParen(ops.last())) out += ops.removeLast()
                            prev = tk
                        }
                        else -> {
                            val p = precedence[tk.v] ?: throw IllegalArgumentException("未知运算符 ${tk.v}")
                            val isUnary = (tk.v == "-" || tk.v == "+") &&
                                (prev == null || (prev is Tok.Op && (prev as Tok.Op).v != ")"))
                            if (isUnary) {
                                // 前缀运算符：直接压栈，不弹出任何东西（操作数还没解析完）
                                ops.addLast(Tok.Op("u-"))
                                prev = tk
                            } else {
                                while (ops.isNotEmpty()) {
                                    val top = ops.last()
                                    val topPrec = if (top is Tok.Op) {
                                        if (top.v == "u-") UNARY_PREC else (precedence[top.v] ?: 0.0)
                                    } else {
                                        0.0
                                    }
                                    if (topPrec <= 0.0) break
                                    val shouldPop = if (tk.v == "^") topPrec > p else topPrec >= p
                                    if (shouldPop) out += ops.removeLast() else break
                                }
                                ops.addLast(tk)
                                prev = tk
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
        while (ops.isNotEmpty()) out += ops.removeLast()
        return out
    }

    private fun eval(rpn: List<Tok>, x: Double): Double {
        val st = ArrayDeque<Double>()
        for (tk in rpn) {
            when (tk) {
                is Tok.Num -> st.addLast(tk.v)
                is Tok.Name -> when {
                    tk.v == "x" -> st.addLast(x)
                    consts.containsKey(tk.v) -> st.addLast(consts.getValue(tk.v))
                    else -> throw IllegalArgumentException("未知符号 ${tk.v}")
                }
                is Tok.Func -> {
                    val v = st.removeLastOrNull() ?: throw IllegalArgumentException("表达式不完整")
                    val f = funcs[tk.v] ?: throw IllegalArgumentException("未知函数 ${tk.v}")
                    st.addLast(f(v))
                }
                is Tok.Op -> {
                    if (tk.v == "u-") {
                        val v = st.removeLastOrNull() ?: throw IllegalArgumentException("表达式不完整")
                        st.addLast(-v)
                    } else {
                        val b = st.removeLastOrNull() ?: throw IllegalArgumentException("表达式不完整")
                        val a = st.removeLastOrNull() ?: throw IllegalArgumentException("表达式不完整")
                        st.addLast(
                            when (tk.v) {
                                "+" -> a + b
                                "-" -> a - b
                                "*" -> a * b
                                "/" -> a / b
                                "^" -> a.pow(b)
                                else -> throw IllegalArgumentException("未知运算符 ${tk.v}")
                            },
                        )
                    }
                }
            }
        }
        if (st.size != 1) throw IllegalArgumentException("表达式不完整")
        return st.first()
    }
}

/**
 * 把一条序列变成点集。
 *
 * 表达式与显式点集在这里汇合 —— 之后渲染器只认点集（含 null 断点），
 * 所以函数曲线、频谱、波形、眼图共用同一个渲染器。这是整个设计的支点。
 * 非有限值（1/x 在 0、log 负数、tan 极点）写 null 而非跳过：渲染时断线，
 * 否则会画出一条贯穿整幅图的竖线。
 */
fun sampleSeries(
    series: Series,
    xMin: Double,
    xMax: Double,
    count: Int = 900,
): List<Pair<Double, Double?>> {
    series.points?.let { pts ->
        return pts.map { p -> p.first to p.second }
    }
    val expr = series.expr ?: return emptyList()
    val f = ExprEval.compile(expr)
    val out = ArrayList<Pair<Double, Double?>>(count + 1)
    var finite = 0
    for (i in 0..count) {
        val x = xMin + (xMax - xMin) * i / count
        val y = runCatching { f(x) }.getOrNull()
        if (y != null && y.isFinite()) {
            out += x to y
            finite++
        } else {
            out += x to null
        }
    }
    require(finite > 0) { "表达式在给定区间内没有有效取值" }
    return out
}

/** 按数据求纵轴范围，留 8% 边距；全为 null 时退回 [fallback]。 */
fun autoRange(values: List<Double?>, fallback: Pair<Double, Double> = -1.0 to 1.0): Pair<Double, Double> {
    var lo = Double.POSITIVE_INFINITY
    var hi = Double.NEGATIVE_INFINITY
    for (v in values) {
        if (v == null || !v.isFinite()) continue
        if (v < lo) lo = v
        if (v > hi) hi = v
    }
    if (!lo.isFinite() || !hi.isFinite()) return fallback
    if (lo == hi) return (lo - 1) to (hi + 1)
    val pad = (hi - lo) * 0.08
    return (lo - pad) to (hi + pad)
}

private val LATEX_COMMAND_TO_SYMBOL = mapOf(
    "\\pi" to "π", "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ",
    "\\delta" to "δ", "\\epsilon" to "ε", "\\varepsilon" to "ε",
    "\\theta" to "θ", "\\lambda" to "λ", "\\mu" to "μ", "\\nu" to "ν",
    "\\rho" to "ρ", "\\sigma" to "σ", "\\tau" to "τ", "\\phi" to "φ",
    "\\varphi" to "φ", "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
    "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ",
    "\\Sigma" to "Σ", "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω",
    "\\times" to "×", "\\cdot" to "·", "\\div" to "÷", "\\pm" to "±",
    "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥",
    "\\neq" to "≠", "\\ne" to "≠", "\\approx" to "≈", "\\equiv" to "≡",
    "\\infty" to "∞", "\\propto" to "∝", "\\partial" to "∂", "\\nabla" to "∇",
    "\\rightarrow" to "→", "\\to" to "→", "\\leftarrow" to "←",
    "\\Rightarrow" to "⇒", "\\in" to "∈", "\\notin" to "∉",
    "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫", "\\sqrt" to "√",
)

/**
 * 把图内文字里的 LaTeX 片段降级成能直接画在画布上的写法。
 *
 * 画布没有公式排版能力，但模型很爱把 `$S_c(f)$`、`\frac{a}{b}` 这类写法直接塞进
 * 标题和轴标签，于是图上会冒出一堆美元符号和反斜杠。这里做**有损但可读**的处理：
 * 剥掉定界符、把常见命令映射成 Unicode 符号、上标数字转成 ² / ³、分式退化成 a/b。
 *
 * 真正需要排版公式的地方是回答正文（那里有 KaTeX/JLatexMath），不是图里的标签；
 * 提示词里也会要求模型图内文字直接用 Unicode，这里只是兜底。
 */
fun prettifyPlotLabel(raw: String): String {
    if (raw.isBlank()) return raw
    var s = raw.trim()
    s = s.replace("$$", "").replace("$", "")
    s = s.replace(Regex("""\\frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}"""), "$1/$2")
    s = s.replace(Regex("""\\text\s*\{([^{}]*)\}"""), "$1")
    s = s.replace(Regex("""\\mathrm\s*\{([^{}]*)\}"""), "$1")
    s = s.replace(Regex("""\\operatorname\s*\{([^{}]*)\}"""), "$1")
    // 长命令先替换，否则 \leq 会被 \le 抢先吃掉
    LATEX_COMMAND_TO_SYMBOL.entries
        .sortedByDescending { it.key.length }
        .forEach { (command, symbol) -> s = s.replace(command, symbol) }
    // 上标：先处理带花括号的写法，再处理裸写法
    for ((digit, superscript) in SUPERSCRIPTS) {
        s = s.replace("^{$digit}", superscript)
        s = s.replace("^$digit", superscript)
    }
    // 剩余的反斜杠命令（\begin 之类）直接去掉反斜杠，至少不再是乱码
    s = s.replace(Regex("""\\([a-zA-Z]+)"""), "$1")
    s = s.replace(Regex("""\s{2,}"""), " ")
    return s.trim()
}

private val SUPERSCRIPTS = listOf(
    '0' to "⁰", '1' to "¹", '2' to "²", '3' to "³", '4' to "⁴",
    '5' to "⁵", '6' to "⁶", '7' to "⁷", '8' to "⁸", '9' to "⁹",
)
