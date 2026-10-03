package com.moge.app.runtime

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GenerationWakeLockTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `foreground holders share protection and a stale release cannot stop a new holder`() {
        val guard = ForegroundGenerationGuard(context)
        val old = guard.acquire("r1")
        val other = guard.acquire("r2")
        val lease = ShadowPowerManager.getLatestWakeLock()
        assertEquals(2, guard.activeCount())
        guard.release(old)
        assertTrue(lease.isHeld)
        val next = guard.acquire("r1")
        guard.release(old)
        assertEquals(2, guard.activeCount())
        assertTrue(lease.isHeld)
        guard.release(other)
        assertTrue(lease.isHeld)
        guard.release(next)
        assertFalse(lease.isHeld)
        assertEquals(0, guard.activeCount())
    }

    @Test fun `cpu lease is not reference counted and every stop releases it`() = runTest {
        val cpu = GenerationWakeLock(context, backgroundScope)
        cpu.start()
        val lease = ShadowPowerManager.getLatestWakeLock()
        assertTrue(lease.isHeld)
        assertFalse(Shadows.shadowOf(lease).isReferenceCounted)
        cpu.start()
        cpu.stop()
        assertFalse(lease.isHeld)
        cpu.stop()
        assertFalse(lease.isHeld)
    }

    @Test fun `live lease renews and stopped lease cannot be reacquired by its old timer`() = runTest {
        val cpu = GenerationWakeLock(context, backgroundScope)
        cpu.start()
        runCurrent()
        val lease = ShadowPowerManager.getLatestWakeLock()
        val held = Shadows.shadowOf(lease).timesHeld
        advanceTimeBy(GenerationWakeLock.RENEW_INTERVAL_MS + 1)
        runCurrent()
        assertTrue(Shadows.shadowOf(lease).timesHeld > held)
        cpu.stop()
        val stopped = Shadows.shadowOf(lease).timesHeld
        advanceTimeBy(GenerationWakeLock.RENEW_INTERVAL_MS * 2)
        runCurrent()
        assertFalse(lease.isHeld)
        assertEquals(stopped, Shadows.shadowOf(lease).timesHeld)
        cpu.start()
        assertTrue(lease.isHeld)
        cpu.stop()
    }
}
