package com.moge.app.domain.plot

import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A circular viewport, independent of the angular domain of each curve. */
@Serializable
data class PolarAxes(
    val radiusMax: Double? = null,
    val radialTicks: List<Double>? = null,
    val angleStepDegrees: Int = 30,
    val grid: Boolean = true,
) {
    init {
        require(radiusMax == null || radiusMax.isFinite() && radiusMax > 0) { "极坐标半径上限必须为正数" }
        require(angleStepDegrees in listOf(15, 30, 45, 60, 90)) { "极坐标角度间隔无效" }
    }
}

fun compilePolarExpression(expression: String): (Double) -> Double =
    ExprEval.compile(expression.replace("θ", "theta").replace("π", "pi"), variable = "theta")

/** Negative radii keep their mathematical direction; undefined angles break the curve. */
fun samplePolarSeries(series: Series, radiusMax: Double? = null, count: Int = 900): List<Pair<Double, Double?>> {
    series.points?.let { return it.map { point -> point.first to point.second } }
    val expression = series.expr ?: return emptyList()
    require(count > 0 && series.thetaMin.isFinite() && series.thetaMax.isFinite() &&
        series.thetaMax > series.thetaMin && (series.thetaMax - series.thetaMin).isFinite()) { "极坐标角度区间无效" }
    val radius = compilePolarExpression(expression)
    val result = ArrayList<Pair<Double, Double?>>(count + 1)
    var finite = 0
    fun pointAt(theta: Double): Pair<Double, Double>? {
        val r = runCatching { radius(theta) }.getOrNull()
        val x = r?.times(cos(theta))
        val y = r?.times(sin(theta))
        if (x == null || y == null || !x.isFinite() || !y.isFinite()) return null
        finite++
        // Keep far-off poles out of the Float-based Canvas path entirely.
        if (radiusMax != null && hypot(x, y) / radiusMax > 1e6) return null
        return x to y
    }
    fun append(point: Pair<Double, Double>?) {
        if (point == null) {
            result += 0.0 to null
            return
        }
        // A pole can evaluate to a huge finite number due to trig rounding.
        // Do not connect its two branches through the visible circular window.
        result.lastOrNull()?.let { (x, y) ->
            if (radiusMax != null && y != null && hypot(point.first - x, point.second - y) > radiusMax * 4)
                result += 0.0 to null
        }
        result += point
    }
    var previousTheta: Double? = null
    var previous: Pair<Double, Double>? = null
    for (i in 0..count) {
        val theta = series.thetaMin + (series.thetaMax - series.thetaMin) * (i.toDouble() / count)
        val point = pointAt(theta)
        val lastTheta = previousTheta
        if (lastTheta != null && (previous == null) != (point == null)) {
            // sqrt-domain endpoints can approach the pole very steeply. Refine
            // only valid/invalid transitions instead of oversampling every curve.
            var inside = if (point != null) theta else lastTheta
            var outside = if (point == null) theta else lastTheta
            var boundary = point ?: previous
            repeat(24) {
                val middle = inside / 2 + outside / 2
                val candidate = pointAt(middle)
                if (candidate == null) outside = middle else {
                    inside = middle
                    boundary = candidate
                }
            }
            append(boundary)
        }
        append(point)
        previousTheta = theta
        previous = point
    }
    require(finite > 0) { "极坐标表达式在给定区间内没有有效取值" }
    return result
}

fun polarRadius(points: List<Pair<Double, Double?>>, axes: PolarAxes): Double {
    axes.radiusMax?.let { return it }
    val peak = points.mapNotNull { (x, y) -> y?.let { hypot(x, it).takeIf(Double::isFinite) } }.maxOrNull() ?: 0.0
    return (peak * 1.08).takeIf { it.isFinite() && it > 0 } ?: 1.0
}

/** Familiar radian labels without decimal approximations of pi. */
fun polarAngleLabel(degrees: Int): String {
    if (degrees == 0) return "0"
    var a = degrees
    var b = 180
    while (b != 0) { val remainder = a % b; a = b; b = remainder }
    val numerator = degrees / a
    val denominator = 180 / a
    val top = if (numerator == 1) "π" else "${numerator}π"
    return if (denominator == 1) top else "$top/$denominator"
}
