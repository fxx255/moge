package com.moge.app.ui.figure

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.moge.app.domain.coding.CodeTransition
import com.moge.app.domain.coding.CodingSpec
import com.moge.app.domain.coding.ConvolutionalCode
import com.moge.app.domain.diagram.DiagramLayoutProfile
import com.moge.app.domain.diagram.DiagramSpec
import kotlin.math.*

/** Parameter-derived textbook templates, preserving the user's reference arrangement and notation. */
object CodingDiagramRenderer {
    internal data class P(val x: Float, val y: Float) {
        operator fun plus(other: P) = P(x + other.x, y + other.y)
        operator fun minus(other: P) = P(x - other.x, y - other.y)
        operator fun times(value: Float) = P(x * value, y * value)
        fun unit(): P = this * (1f / hypot(x, y).coerceAtLeast(.001f))
    }
    private class Drawing(val canvas: Canvas, dark: Boolean) {
        val background = if (dark) DiagramRenderer.DARK_BACKGROUND else Color.WHITE
        val ink = if (dark) 0xFFDDE1E3.toInt() else 0xFF253341.toInt()
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; style = Paint.Style.STROKE; strokeWidth = 2.6f
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 24f }
        fun label(value: String, x: Float, y: Float, size: Float = 24f, center: Boolean = false, color: Int = ink) {
            text.textSize = size; text.color = color
            text.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
            canvas.drawText(value, x, y - (text.ascent() + text.descent()) / 2, text)
        }
        fun line(vararg points: P, paint: Paint = stroke) {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            canvas.drawPath(path, paint)
        }
        fun arrow(end: P, previous: P, color: Int = ink) {
            val angle = atan2(end.y - previous.y, end.x - previous.x)
            val path = Path().apply {
                moveTo(end.x, end.y)
                lineTo(end.x - 12f * cos(angle - .42f), end.y - 12f * sin(angle - .42f))
                lineTo(end.x - 12f * cos(angle + .42f), end.y - 12f * sin(angle + .42f))
                close()
            }
            canvas.drawPath(path, Paint(stroke).apply { this.color = color; style = Paint.Style.FILL; pathEffect = null })
        }
        fun dot(x: Float, y: Float) = canvas.drawCircle(x, y, 4f, Paint(stroke).apply { style = Paint.Style.FILL })
        fun circle(x: Float, y: Float, radius: Float, value: String, size: Float = 30f) {
            canvas.drawCircle(x, y, radius, Paint().apply { color = background })
            canvas.drawCircle(x, y, radius, stroke)
            label(value, x, y, if (value == "+") 34f else size, true)
        }
        fun box(x: Float, y: Float, width: Float, value: String, height: Float = 58f) {
            val rect = RectF(x, y - height / 2, x + width, y + height / 2)
            canvas.drawRect(rect, Paint().apply { color = background })
            canvas.drawRect(rect, stroke)
            label(value, x + width / 2, y, center = true)
        }
        /** Bare textbook SPDT contacts, without a UI-style container. */
        fun phaseSwitch(x: Float, y: Float, name: String, upper: Boolean = true) {
            for (dy in listOf(-34f, 34f)) canvas.drawCircle(x, y + dy, 4f, stroke)
            dot(x + 72, y)
            line(P(x + 72, y), P(x + 5, y + if (upper) -32f else 32f))
            line(P(x + 72, y), P(x + 98, y))
            label(name, x + 42, y - 63, 22f, true)
        }
    }

    fun render(spec: DiagramSpec, widthPx: Int, heightPx: Int, dark: Boolean): Bitmap {
        val code = requireNotNull(spec.coding) { "缺少编码参数" }
        val cyclic = spec.profile == DiagramLayoutProfile.CYCLIC_ENCODER
        val stateGraph = spec.profile == DiagramLayoutProfile.CONVOLUTIONAL_STATE_GRAPH
        if (cyclic) code.validateCyclic() else code.validateConvolutional(stateGraph)
        val width = when {
            stateGraph -> if (code.memory == 3) 1300f else 1200f
            cyclic -> max(1080f, 590f + (code.n - code.k) * 166f)
            else -> max(1100f, 660f + code.memory * 170f)
        }
        val height = when {
            stateGraph -> when (code.memory) { 1 -> 480f; 2 -> 560f; else -> 1100f }
            cyclic -> 740f
            else -> if (code.generators.size <= 2) 580f else 880f
        }
        val scale = minOf(2.5f, widthPx / width, heightPx / height, sqrt(8_000_000f / (width * height)))
        require(scale > 0)
        // Floor both axes: independent rounding can exceed the shared 8-megapixel budget.
        val bitmap = Bitmap.createBitmap(floor(width * scale).toInt().coerceAtLeast(1),
            floor(height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val d = Drawing(Canvas(bitmap).apply { scale(scale, scale) }, dark)
        d.canvas.drawColor(d.background)
        val title = spec.title.ifBlank {
            when {
                cyclic -> "(" + code.n + "," + code.k + ") 系统循环码编码器"
                stateGraph -> "卷积码状态转移图"
                else -> "卷积码编码器 · 码率 1/" + code.generators.size + " · K=" + (code.memory + 1)
            }
        }
        d.text.textSize = 28f
        val titleSize = min(28f, 28f * (width - 64f) / d.text.measureText(title).coerceAtLeast(1f))
        d.label(title, 32f, 36f, titleSize)
        when {
            cyclic -> cyclic(d, code, width)
            stateGraph -> states(d, code, width, height)
            else -> convolutional(d, code, width, height)
        }
        return bitmap
    }

    private fun subscript(value: Int): String = value.toString().map { "₀₁₂₃₄₅₆₇₈₉"[it.digitToInt()] }.joinToString("")
    private fun polynomial(taps: List<Int>, variable: String = "D"): String =
        taps.sorted().joinToString(" + ") { exponent ->
            when (exponent) {
                0 -> "1"
                1 -> variable
                else -> variable + exponent.toString().map { "⁰¹²³⁴⁵⁶⁷⁸⁹"[it.digitToInt()] }.joinToString("")
            }
        }

    internal data class TapBranch(val output: Int, val tap: Int, val points: List<P>)
    internal data class ConvolutionalLayout(
        val y: Float, val taps: List<Float>, val sumX: Float, val rows: List<Float>,
        val radii: List<Float>, val branches: List<TapBranch>,
    )

    /** Shared geometric plan: a far output's stems fan out beyond the nearer adder, never through it. */
    internal fun convolutionalLayout(code: CodingSpec): ConvolutionalLayout {
        code.validateConvolutional()
        val y = if (code.generators.size <= 2) 280f else 420f
        val pitch = if (code.memory == 2) 210f else 170f
        val taps = (0..code.memory).map { 220f + it * pitch }
        val sumX = (taps.first() + taps.last()) / 2
        val rows = code.generators.indices.map { index ->
            y + (if (index % 2 == 0) -1 else 1) * (110f + (index / 2) * 140f)
        }
        val radii = code.generators.map { if (it.size == 1) 0f else max(27f, it.size * 4.5f) }
        val branches = code.generators.flatMapIndexed { output, terms ->
            val rowY = rows[output]
            val side = if (rowY < y) 1 else -1
            val left = terms.filter { taps[it] < sumX - 1 }.sorted()
            val right = terms.filter { taps[it] > sumX + 1 }.sorted()
            terms.sorted().map { tap ->
                val x = taps[tap]
                val degrees = when {
                    x < sumX - 1 -> 180f - if (left.size == 1) 0f else 60f * left.indexOf(tap) / (left.size - 1)
                    x > sumX + 1 -> if (right.size == 1) 0f else 60f * (right.size - 1 - right.indexOf(tap)) / (right.size - 1)
                    else -> 90f
                }
                val angle = degrees * side * PI.toFloat() / 180
                val end = P(sumX + cos(angle) * radii[output], rowY + sin(angle) * radii[output])
                val bendY = if (abs(end.y - rowY) < 1) rowY else rowY + side * 63
                val points = when {
                    output >= 2 -> {
                        val laneX = x + if (x < sumX) -74f else 74f
                        val fanY = y - side * 44
                        listOf(P(x, y), P(x, fanY), P(laneX, fanY), P(laneX, bendY), end)
                    }
                    abs(x - sumX) < 1 -> listOf(P(x, y), end)
                    else -> listOf(P(x, y), P(x, bendY), end)
                }
                TapBranch(output, tap, points)
            }
        }
        return ConvolutionalLayout(y, taps, sumX, rows, radii, branches)
    }

    internal fun convolutionalOutputs(plan: ConvolutionalLayout, width: Float): List<List<P>> =
        plan.rows.mapIndexed { output, rowY ->
            val side = if (rowY < plan.y) -1 else 1
            val selectorX = width - 225
            val railY = rowY + side * (max(27f, plan.radii[output]) + 41)
            val contactY = plan.y + side * if (plan.rows.size <= 2) 58f else (45f + (output / 2) * 65f)
            val laneX = if (plan.rows.size <= 2) selectorX else selectorX - 48 + (output / 2) * 24
            listOf(P(plan.sumX, rowY + side * plan.radii[output]), P(plan.sumX, railY),
                P(laneX, railY), P(laneX, contactY), P(selectorX, contactY))
        }

    private fun convolutional(d: Drawing, code: CodingSpec, width: Float, height: Float) {
        val plan = convolutionalLayout(code)
        val y = plan.y
        val taps = plan.taps
        val sumX = plan.sumX
        val rows = plan.rows
        val selectorX = width - 225
        val outputWires = convolutionalOutputs(plan, width)
        d.label("输入信息", 32f, y - 28, 23f)
        d.label("序列 u", 32f, y + 4, 23f)
        d.line(P(151f, y), P(taps.last() + 22, y))
        // The reference uses wide blank delay rectangles for K=3 and compact rectangles for K=4.
        for (i in 1..code.memory) {
            val boxWidth = if (code.memory == 2) 144f else 82f
            d.box(taps[i - 1] + 32, y, boxWidth, "", if (code.memory == 2) 50f else 58f)
            d.arrow(P(taps[i - 1] + 30, y), P(taps[i - 1], y))
        }
        d.label("uⱼ", taps.first() + 7, y - 35, 23f)
        if (code.memory <= 2) for (i in 1..code.memory) {
            d.label("uⱼ₋" + subscript(i), taps[i] + 9, y - 35, 23f)
        }
        val outputContacts = mutableListOf<P>()
        code.generators.forEachIndexed { output, terms ->
            val rowY = rows[output]
            val upper = rowY < y
            val radius = plan.radii[output]
            val branches = plan.branches.filter { it.output == output }
            branches.forEach { branch ->
                d.line(*branch.points.toTypedArray())
                d.dot(taps[branch.tap], y)
                if (output >= 2 && branch.tap in code.generators[output % 2]) {
                    d.dot(branch.points[1].x, branch.points[1].y)
                }
            }
            if (terms.size > 1) {
                d.circle(sumX, rowY, radius, "+")
                branches.forEach { branch -> d.arrow(branch.points.last(), branch.points[branch.points.lastIndex - 1]) }
            }
            val outer = if (upper) -1 else 1
            val wire = outputWires[output]
            val start = wire.first()
            val railY = wire[1].y
            val contact = wire.last()
            val contactY = contact.y
            d.line(*wire.toTypedArray())
            d.arrow(P(sumX, railY - outer * 12), start)
            // The inner output label stays beside its own lead, clear of the outer adder's inputs.
            val generatorY = if (code.generators.size > 2 && output < 2) (start.y + railY) / 2 else railY + outer * 23
            d.label("g" + superscript(output + 1), sumX + 13, generatorY, 24f)
            if (code.outputMode == "serial" && code.generators.size > 1) {
                d.canvas.drawCircle(contact.x, contact.y, 5f, d.stroke)
                d.label("cⱼ" + superscript(output + 1),
                    if (code.generators.size <= 2) selectorX - 66 else selectorX + 14,
                    contactY + outer * 24, 24f)
                outputContacts += contact
            } else {
                d.line(contact, P(width - 35, contactY)); d.arrow(P(width - 35, contactY), contact)
                d.label("cⱼ" + superscript(output + 1), width - 125, contactY - 26, 24f)
            }
        }
        if (outputContacts.isNotEmpty()) {
            val pivot = P(selectorX + 78, y)
            d.line(P(selectorX + 6, outputContacts.first().y + 3), pivot, P(width - 35, y))
            d.dot(pivot.x, pivot.y); d.arrow(P(width - 35, y), pivot)
            // Small rotational arrow beside the commutator blade, matching the mechanical selector symbol.
            val rotation = Path().apply {
                moveTo(selectorX + 53, y - 21)
                cubicTo(selectorX + 48, y - 5, selectorX + 42, y + 10, selectorX + 35, y + 27)
            }
            d.canvas.drawPath(rotation, d.stroke)
            d.arrow(P(selectorX + 35, y + 27), P(selectorX + 42, y + 10))
            d.label("输出码字序列", selectorX + 74, y - 39, 20f)
            d.label("c", width - 69, y + 28, 26f)
        }
        val formulaY = height - 82
        code.generators.forEachIndexed { i, terms ->
            val x = if (i % 2 == 0) 32f else width / 2 + 16
            d.label("g" + superscript(i + 1) + "(D) = " + polynomial(terms), x, formulaY + (i / 2) * 30, 21f)
        }
        d.label("矩形：一拍延迟    +：模 2 加法    串行输出按 cⱼ⁽¹⁾、cⱼ⁽²⁾… 顺序", 32f, height - 22, 19f)
    }

    private fun superscript(value: Int): String = "⁽" + value.toString().map {
        "⁰¹²³⁴⁵⁶⁷⁸⁹"[it.digitToInt()]
    }.joinToString("") + "⁾"

    private fun cyclic(d: Drawing, code: CodingSpec, width: Float) {
        val r = code.n - code.k
        val y = 280f
        val xorX = 126f
        val switchX = 224f
        val start = 370f
        val xs = (0 until r).map { start + it * 166f }
        val last = xs.last() + 118
        val selectorX = width - 185
        d.label("g(x) = " + polynomial(code.generatorExponents, "x"), 32f, 85f, 25f)
        d.label("初态全 0 · 信息高次位先输入 · r = n − k = " + r, 32f, 122f, 22f)
        // The highest register bit returns to the input XOR; the gated feedback fans out below.
        d.line(P(last, y), P(last, 177f), P(xorX, 177f), P(xorX, y - 34 - 25))
        d.arrow(P(xorX, y - 34 - 25), P(xorX, 177f))
        d.label("最高位反馈", (xorX + last) / 2, 158f, 22f, true)
        d.line(P(32f, y - 34), P(xorX - 25, y - 34))
        d.label("u", 38f, y - 64, 26f)
        d.circle(xorX, y - 34, 25f, "+")
        d.arrow(P(xorX - 25, y - 34), P(xorX - 55, y - 34))
        d.line(P(xorX + 25, y - 34), P(switchX - 5, y - 34))
        d.label("f", 178f, y - 60, 25f)
        d.line(P(181f, y + 34), P(switchX - 5, y + 34))
        d.label("0", 180f, y + 60, 24f)
        d.phaseSwitch(switchX, y, "K₁")
        d.line(P(switchX + 98, y), P(start, y))
        d.arrow(P(start - 2, y), P(switchX + 98, y))
        val gatedX = 343f
        val busY = 403f
        val feedbackTaps = code.generatorExponents.filter { it in 1 until r }.sorted()
        if (feedbackTaps.isNotEmpty()) {
            val lastAdder = xs[feedbackTaps.last()] - 40
            d.line(P(gatedX, y), P(gatedX, busY), P(lastAdder, busY))
            d.dot(gatedX, y)
            feedbackTaps.forEach { tap ->
                val ax = xs[tap] - 40
                d.line(P(ax, busY), P(ax, y + 23)); d.dot(ax, busY)
                d.arrow(P(ax, y + 23), P(ax, busY))
            }
            d.label("反馈 f 分配到生成多项式抽头", gatedX, busY + 31, 21f)
        }
        for (i in 0 until r) {
            val x = xs[i]
            if (i > 0) {
                d.line(P(xs[i - 1] + 82, y), P(x, y))
                if (i in feedbackTaps) {
                    d.circle(x - 40, y, 23f, "+")
                    d.label("g" + subscript(i) + " = 1", x - 40, y - 43, 20f, true)
                    d.arrow(P(x - 63, y), P(xs[i - 1] + 82, y))
                }
                d.arrow(P(x - 2, y), P(x - 17, y))
            }
            d.box(x, y, 82f, "S" + subscript(i))
            d.label("x" + if (i == 0) "⁰" else if (i == 1) "¹" else i.toString().map { "⁰¹²³⁴⁵⁶⁷⁸⁹"[it.digitToInt()] }.joinToString(""),
                x + 41, y + 49, 21f, true)
        }
        d.line(P(xs.last() + 82, y), P(last, y)); d.dot(last, y)
        // Parity uses the upper output contact, information the lower: neither bypass crosses the other.
        val outputY = 550f
        d.line(P(last, y), P(last, outputY - 34), P(selectorX - 5, outputY - 34))
        d.label("校验位", last - 94, outputY - 59, 22f)
        d.line(P(57f, y - 34), P(57f, outputY + 34), P(selectorX - 5, outputY + 34)); d.dot(57f, y - 34)
        d.label("原信息位", 84f, outputY + 12, 22f)
        // K2 selects the lower information contact, synchronously with K1's upper feedback contact.
        d.phaseSwitch(selectorX, outputY, "K₂", upper = false)
        d.line(P(selectorX + 98, outputY), P(width - 30, outputY))
        d.arrow(P(width - 30, outputY), P(selectorX + 98, outputY))
        d.label("c", width - 61, outputY - 28, 26f)
        d.label("K₁、K₂ 同步切换", 32f, 651f, 23f)
        d.label("① 信息阶段（k 拍）：K₁ 接 f，K₂ 输出 u    ② 校验阶段（r 拍）：K₁ 接 0，K₂ 移出余式", 32f, 689f, 21f)
        d.label("Sᵢ：余式的 xⁱ 系数寄存器    +：模 2 加法", 32f, 720f, 20f)
    }

    private data class Curve(val edge: CodeTransition, val start: P, val c1: P, val c2: P, val end: P) {
        fun at(t: Float): P {
            val u = 1 - t
            return start * (u * u * u) + c1 * (3 * u * u * t) + c2 * (3 * u * t * t) + end * (t * t * t)
        }
        fun path(): Path = Path().apply {
            moveTo(start.x, start.y); cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
        }
    }

    private fun states(d: Drawing, code: CodingSpec, width: Float, height: Float) {
        val count = 1 shl code.memory
        val center = P(width / 2, when (count) { 2 -> 245f; 4 -> 280f; else -> 580f })
        val radius = 38f
        val cycle = listOf(0, 4, 2, 5, 6, 7, 3, 1)
        val positions = when (count) {
            2 -> mapOf(0 to P(320f, center.y), 1 to P(width - 320, center.y))
            // Supplied Fig. 9.5.4: a/s0 on the left, b/s1 above, c/s2 below, d/s3 on the right.
            4 -> mapOf(0 to P(240f, center.y), 2 to P(center.x, 170f),
                1 to P(center.x, 390f), 3 to P(width - 240, center.y))
            else -> cycle.mapIndexed { index, state ->
                val angle = PI.toFloat() + 2 * PI.toFloat() * index / count
                state to P(center.x + cos(angle) * 360, center.y + sin(angle) * 300)
            }.toMap()
        }
        fun clipped(p: P, toward: P) = p + (toward - p).unit() * radius
        val curves = ConvolutionalCode.transitions(code).map { edge ->
            val from = positions.getValue(edge.from)
            val to = positions.getValue(edge.to)
            if (edge.from == edge.to) {
                val out = (from - center).unit()
                val side = P(-out.y, out.x)
                Curve(edge, from + out * 28f + side * 25f,
                    from + out * 130f + side * 74f, from + out * 130f - side * 74f,
                    from + out * 28f - side * 25f)
            } else {
                val delta = to - from
                val normal = P(-delta.y, delta.x).unit()
                val specialControls = if (count != 8) null else when (edge.from to edge.to) {
                    1 to 4 -> P(30f, from.y) to P(30f, to.y)
                    4 to 6 -> P(385f, 128f) to P(1170f, 128f)
                    6 to 3 -> P(1235f, 615f) to P(1180f, 1030f)
                    else -> null
                }
                if (specialControls != null) {
                    Curve(edge, clipped(from, specialControls.first), specialControls.first,
                        specialControls.second, clipped(to, specialControls.second))
                } else {
                    val control = when {
                        count == 4 -> when (edge.from to edge.to) {
                            0 to 2 -> P(420f, 145f)
                            2 to 3 -> P(width - 420, 145f)
                            3 to 1 -> P(width - 420, 415f)
                            1 to 0 -> P(420f, 415f)
                            2 to 1 -> P(center.x + 95, center.y)
                            else -> P(center.x - 95, center.y)
                        }
                        count == 8 && edge.from == 2 && edge.to == 1 -> P(440f, 550f)
                        count == 8 && edge.from == 3 && edge.to == 5 -> P(860f, 600f)
                        else -> (from + to) * .5f + normal * if (count == 8) -65f else 100f
                    }
                    val start = clipped(from, control)
                    val end = clipped(to, control)
                    Curve(edge, start, start + (control - start) * (2f / 3),
                        end + (control - end) * (2f / 3), end)
                }
            }
        }
        curves.forEach { curve ->
            d.canvas.drawPath(curve.path(), d.stroke)
            d.arrow(curve.end, curve.c2)
        }
        val occupied = positions.values.map { RectF(it.x - radius - 12, it.y - radius - 32,
            it.x + radius + 12, it.y + radius + 38) }.toMutableList()
        curves.sortedByDescending { it.edge.from == it.edge.to }.forEach { curve ->
            val value = curve.edge.label
            d.text.textSize = 24f
            val halfWidth = d.text.measureText(value) / 2 + 6
            fun rect(p: P) = RectF(p.x - halfWidth, p.y - 17, p.x + halfWidth, p.y + 17)
            val candidates = listOf(.5f, .38f, .62f, .28f, .72f).flatMap { t ->
                val onCurve = curve.at(t)
                val tangent = (curve.at((t + .01f).coerceAtMost(1f)) - curve.at((t - .01f).coerceAtLeast(0f))).unit()
                var normal = P(-tangent.y, tangent.x)
                if (normal.x * (onCurve.x - center.x) + normal.y * (onCurve.y - center.y) < 0) normal = normal * -1f
                val distance = when {
                    curve.edge.from == curve.edge.to -> 48f
                    count == 4 && setOf(curve.edge.from, curve.edge.to) == setOf(1, 2) -> 44f
                    else -> 23f
                }
                listOf(onCurve + normal * distance, onCurve + normal * (distance + 16), onCurve)
            }
            fun score(p: P): Int {
                val area = rect(p)
                var score = occupied.count { RectF.intersects(it, area) } * 1000
                if (area.left < 20 || area.right > width - 20 || area.top < 72 || area.bottom > height - 58) score += 10000
                curves.forEach { other ->
                    score += (0..40).count { step -> other.at(step / 40f).let { area.contains(it.x, it.y) } } * 30
                }
                return score
            }
            val p = candidates.minBy { score(it) }
            occupied += rect(p)
            d.canvas.drawRect(rect(p), Paint().apply { color = d.background })
            d.label(value, p.x, p.y, 24f, true)
        }
        positions.forEach { (state, p) ->
            d.circle(p.x, p.y, radius, state.toString(2).padStart(code.memory, '0'), 29f)
            val ordinal = Integer.reverse(state) ushr (32 - code.memory)
            d.label("s" + subscript(ordinal), p.x, p.y + radius + 23, 23f, true)
            if (count == 4) d.label(('a'.code + ordinal).toChar().toString(), p.x, p.y - radius - 20, 23f, true)
        }
        d.label("边标注：输出(输入)    状态位：最近输入在前", 32f, height - 25, 20f)
    }
}
