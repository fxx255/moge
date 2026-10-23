package com.moge.app.data.parse

import com.moge.app.domain.plot.PolarAxes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

internal fun parsePolarAxes(element: JsonElement?): PolarAxes? {
    if (element == null || (element as? JsonPrimitive)?.booleanOrNull == false) return null
    if ((element as? JsonPrimitive)?.booleanOrNull == true) return PolarAxes()
    val obj = element as? JsonObject ?: error("极坐标设置无效")
    val radius = obj["radiusMax"]?.let {
        (it as? JsonPrimitive)?.doubleOrNull?.takeIf { value -> value.isFinite() && value > 0 }
            ?: error("极坐标半径上限必须为正数")
    }
    val ticks = (obj["radialTicks"] as? JsonArray)?.take(20)?.mapNotNull {
        (it as? JsonPrimitive)?.doubleOrNull?.takeIf { value -> value.isFinite() && value > 0 }
    }?.distinct()?.sorted()
    val step = (obj["angleStepDegrees"] as? JsonPrimitive)?.intOrNull ?: 30
    return PolarAxes(radius, ticks, step, (obj["grid"] as? JsonPrimitive)?.booleanOrNull ?: true)
}
