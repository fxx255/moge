package com.moge.app.runtime

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GenerationWifiLockTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val wifi get() = Shadows.shadowOf(context.getSystemService(WifiManager::class.java))

    @Test
    @Config(sdk = [34])
    fun `Android 14 does not acquire a low latency lock that cannot protect screen off networking`() {
        val protection = GenerationWifiLock(context)
        protection.start()
        assertEquals(0, wifi.activeLockCount)
        protection.stop()
    }

    @Test fun `repeated acquisition shares one lease and stop releases it`() {
        val protection = GenerationWifiLock(context)
        protection.start()
        protection.start()
        assertEquals(1, wifi.activeLockCount)
        protection.stop()
        protection.stop()
        assertEquals(0, wifi.activeLockCount)
        protection.start()
        assertEquals(1, wifi.activeLockCount)
        protection.stop()
        assertEquals(0, wifi.activeLockCount)
    }

    @Test fun `guard releases wifi only after the last request and ignores stale tokens`() {
        val guard = ForegroundGenerationGuard(context)
        val old = guard.acquire("first")
        val other = guard.acquire("second")
        assertEquals(1, wifi.activeLockCount)
        guard.release(old)
        assertEquals(1, wifi.activeLockCount)
        val next = guard.acquire("first")
        guard.release(old)
        guard.release(other)
        assertEquals(1, wifi.activeLockCount)
        guard.release(next)
        assertEquals(0, wifi.activeLockCount)
    }
}
