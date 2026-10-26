package com.moge.app.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.sin

internal val PaperControlHorizontalTravel = 3.dp
internal val PaperControlVerticalTravel = PaperControlHorizontalTravel * .6f

internal fun paperControlSwipeFraction(progress: Float): Float =
    sin(progress.coerceIn(0f, 1f) * Math.PI).toFloat().coerceAtLeast(0f)

internal fun paperControlScrollFraction(delta: Float, millis: Long, density: Float): Float =
    (delta / density / millis.coerceIn(8L, 48L) * 1000f / 2000f).coerceIn(-1f, 1f)

/** Watches finger-driven scrolls without consuming them or following inertial flings. */
internal class PaperControlSway(private val density: Float, private val enabled: Boolean) : NestedScrollConnection {
    internal val displacement = Animatable(0f)
    internal val changes = Channel<Float>(Channel.CONFLATED)
    val fraction: Float get() = displacement.value
    private var lastTime: Long? = null

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (enabled && source == NestedScrollSource.UserInput && available.y != 0f) {
            val time = SystemClock.uptimeMillis()
            val elapsed = lastTime?.let { time - it } ?: 16L
            lastTime = time
            changes.trySend(paperControlScrollFraction(available.y, elapsed, density))
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        lastTime = null
        changes.trySend(0f)
        return Velocity.Zero
    }
}

@Composable
internal fun rememberPaperControlSway(): PaperControlSway {
    val density = LocalDensity.current.density
    val enabled = MogeTheme.motionEnabled
    val sway = remember(density, enabled) { PaperControlSway(density, enabled) }
    LaunchedEffect(sway) {
        sway.changes.receiveAsFlow().collectLatest { target ->
            if (target != 0f) {
                sway.displacement.animateTo(target, tween(55))
                delay(90)
            }
            sway.displacement.animateTo(0f, spring(dampingRatio = .85f, stiffness = 500f))
        }
    }
    return sway
}
