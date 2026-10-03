package com.moge.app.ui.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlin.math.roundToInt

/** 只在开关启用且首页处于前台时监听重力传感器（没有时回退加速度计）。 */
@Composable
fun LevelIndicator(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val manager = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    val sensor = remember(manager) { manager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    // 和相机图标使用同一屏幕方向；configChanges 下旋转也会重订阅。
    val rotation = rememberDisplayRotation()
    var degrees by remember(rotation) { mutableStateOf<Int?>(null) }
    DisposableEffect(manager, sensor, lifecycle, rotation) {
        val filtered = FloatArray(2)
        var seeded = false
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                for (i in 0..1) filtered[i] = if (seeded) filtered[i] * 0.8f + event.values[i] * 0.2f else event.values[i]
                seeded = true
                degrees = screenRollDegrees(filtered[0], filtered[1], rotation)?.roundToInt()
            }
        }
        fun register() { if (sensor != null) manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI) }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE -> manager?.unregisterListener(listener)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) register()
        onDispose { lifecycle.removeObserver(observer); manager?.unregisterListener(listener) }
    }
    val aligned = degrees?.let { abs(it) <= 2 } == true
    val color = if (aligned) Color(0xFFFFE066) else Color.White
    val label = when {
        sensor == null -> "设备不支持水平仪"
        degrees == null -> "请竖起手机"
        aligned -> "已水平"
        else -> "倾斜 ${abs(degrees!!)}°"
    }
    Column(modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(8.dp)
        .semantics(mergeDescendants = true) { contentDescription = "水平仪，$label" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(96.dp, 32.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            drawLine(Color.White.copy(alpha = 0.45f), Offset(0f, center.y), Offset(size.width, center.y), 1.dp.toPx())
            val guideOffset = levelGuideOffset((degrees ?: 0).toFloat(), 28.dp.toPx())
            drawLine(color, center - guideOffset, center + guideOffset, 2.dp.toPx())
            drawCircle(color, 3.dp.toPx(), center)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
