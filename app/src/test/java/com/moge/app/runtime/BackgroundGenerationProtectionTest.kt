package com.moge.app.runtime

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ComponentName
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.llm.ModelTransportFailure
import com.moge.app.data.llm.ModelTransportTrace
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.net.SocketException

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [33], application = Application::class)
class BackgroundGenerationProtectionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class ServiceCalls(base: Context) : ContextWrapper(base) {
        val starts = mutableListOf<Long>()
        var stops = 0
        override fun startForegroundService(service: Intent): ComponentName {
            starts += service.getLongExtra(KeepAliveService.EXTRA_START, 0L)
            return checkNotNull(service.component)
        }
        override fun stopService(service: Intent): Boolean {
            stops++
            return true
        }
    }

    @Test fun `cancel before promotion releases locks and lets the service stop its own startId`() {
        val calls = ServiceCalls(context)
        val guard = ForegroundGenerationGuard(calls)
        val lease = guard.acquire("rejected")
        guard.release(lease)
        assertEquals(0, calls.stops)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertEquals(0, Shadows.shadowOf(context.getSystemService(WifiManager::class.java)).activeLockCount)
        val service = Robolectric.buildService(KeepAliveService::class.java).create().get()
        assertFalse(guard.serviceStarted(calls.starts.single(), service, null))
        assertEquals(0, guard.activeCount())
    }

    @Test fun `handoff retries a rejected foreground promotion but never starts twice while pending`() {
        val calls = ServiceCalls(context)
        val guard = ForegroundGenerationGuard(calls)
        val token = guard.acquire("send")
        val service = Robolectric.buildService(KeepAliveService::class.java).create().get()
        assertFalse(guard.serviceStarted(calls.starts.single(), service, IllegalStateException("rejected")))
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        guard.updateConversation(token, "saved")
        guard.updateConversation(token, "saved")
        assertEquals(2, calls.starts.size)
        guard.release(token)
        assertFalse(guard.serviceStarted(calls.starts.last(), service, null))
        assertEquals(0, calls.stops)
    }

    @Test fun `rapid replacement reuses a pending start and stale callbacks cannot retarget or stop its successor`() {
        val calls = ServiceCalls(context)
        val diagnostics = BackgroundGenerationDiagnostics(context)
        val guard = ForegroundGenerationGuard(calls, diagnostics)
        val old = guard.acquire("send")
        guard.release(old)
        val next = guard.acquire("send")
        guard.updateConversation(next, "saved")
        guard.updateConversation(old, "stale")
        val firstStart = calls.starts.single()
        val first = Robolectric.buildService(KeepAliveService::class.java).create().get()
        assertTrue(guard.serviceStarted(firstStart, first, null))
        fun notificationTarget(): String? {
            val manager = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
            val intent = Shadows.shadowOf(manager.getNotification(KeepAliveService.NOTIFICATION_ID).contentIntent).savedIntent
            return intent.getStringExtra(KeepAliveService.EXTRA_CONVERSATION_ID)
        }
        assertEquals("saved", notificationTarget())
        guard.updateConversation(next, "updated")
        assertEquals("updated", notificationTarget())
        assertEquals(1, calls.starts.size)
        guard.release(next)
        assertEquals(1, calls.stops)
        val finalLease = guard.acquire("third", "third conversation")
        assertEquals(2, calls.starts.size)
        guard.serviceDestroyed(first)
        val retirement = diagnostics.report().lineSequence().last()
        assertTrue(retirement.contains("service_destroyed "))
        assertTrue(retirement.contains("fgs=starting"))
        val current = Robolectric.buildService(KeepAliveService::class.java).create().get()
        assertTrue(guard.serviceStarted(calls.starts.last(), current, null))
        assertFalse(guard.serviceStarted(firstStart, first, null))
        guard.serviceTimedOut(first) { fail("Old service cannot cancel a new generation") }
        guard.serviceDestroyed(first)
        assertEquals("third conversation", notificationTarget())
        guard.release(finalLease)
        assertEquals(2, calls.stops)
    }

    @Test fun `rejected foreground startup retains independent protection until the last valid release`() {
        val rejected = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName =
                throw IllegalStateException("foreground start rejected")
        }
        val diagnostics = BackgroundGenerationDiagnostics(context)
        val guard = ForegroundGenerationGuard(rejected, diagnostics)
        val first = guard.acquire("first")
        val second = guard.acquire("second")
        val cpu = ShadowPowerManager.getLatestWakeLock()
        val wifi = Shadows.shadowOf(context.getSystemService(WifiManager::class.java))
        assertTrue(cpu.isHeld)
        assertEquals(1, wifi.activeLockCount)
        assertEquals(2, guard.activeCount())
        assertTrue(diagnostics.report().contains("service_failed:IllegalStateException"))
        assertTrue(diagnostics.report().lineSequence().last().contains("fgs=failed"))
        guard.release(first)
        val next = guard.acquire("first")
        guard.release(first) // The failed service start may not break token ownership either.
        guard.release(second)
        assertTrue(cpu.isHeld)
        guard.release(next)
        assertFalse(cpu.isHeld)
        assertEquals(0, wifi.activeLockCount)
        assertEquals(0, guard.activeCount())
    }

    @Test fun `screen transition and socket failure survive restart without recording private messages`() {
        val diagnostics = BackgroundGenerationDiagnostics(context)
        diagnostics.begin({ true }, { false })
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val error = IllegalStateException("secret prompt", SocketException("ECONNRESET private.example/token"))
        diagnostics.failure(error)
        diagnostics.end()
        val saved = BackgroundGenerationDiagnostics(context).report()
        assertTrue(saved.contains("screen_off"))
        assertTrue(saved.contains("request_failed:IllegalStateException<-SocketException"))
        assertTrue(saved.contains("errno=ECONNRESET"))
        assertFalse(saved.contains("secret prompt"))
        assertFalse(saved.contains("private.example"))
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(saved, diagnostics.report()) // End removes observers, including queued callbacks.
    }

    @Test fun `late destruction of an older service cannot report a newer foreground service as stopped`() {
        val diagnostics = BackgroundGenerationDiagnostics(context)
        diagnostics.begin({ true }, { false })
        val older = Any()
        val newer = Any()
        diagnostics.serviceReady(older)
        diagnostics.serviceReady(newer)
        diagnostics.serviceTimedOut(older)
        diagnostics.serviceDestroyed(older)
        diagnostics.failure(SocketException())
        assertTrue(diagnostics.report().lineSequence().last().contains("fgs=foreground"))
        assertFalse(diagnostics.report().contains("service_timeout"))
        diagnostics.serviceTimedOut(newer)
        assertTrue(diagnostics.report().lineSequence().last().let { "service_timeout" in it && "fgs=timed_out" in it })
        diagnostics.serviceDestroyed(newer)
        assertTrue(diagnostics.report().contains("service_destroyed_while_generating"))
        assertTrue(diagnostics.report().lineSequence().last().contains("fgs=stopped"))
        diagnostics.end()
    }

    @Test @Config(sdk = [28])
    fun `Android 9 records failures with unknown standby policy`() {
        val diagnostics = BackgroundGenerationDiagnostics(context)
        diagnostics.failure(SocketException())
        val failure = diagnostics.report().lineSequence().last()
        assertTrue(failure.contains("request_failed:SocketException"))
        assertTrue(failure.contains("lowPowerStandbyEnabled=unknown lowPowerStandbyExempt=unknown"))
    }

    @Test @Config(sdk = [34])
    fun `Android 14 records standby policy and changes to the calling app exemption`() {
        val power = Shadows.shadowOf(context.getSystemService(PowerManager::class.java))
        power.setLowPowerStandbySupported(true)
        power.setLowPowerStandbyEnabled(true)
        power.setExemptFromLowPowerStandby(false)
        val diagnostics = BackgroundGenerationDiagnostics(context)
        diagnostics.failure(SocketException())
        assertTrue(diagnostics.report().lineSequence().last().contains("lowPowerStandbyEnabled=true lowPowerStandbyExempt=false"))
        power.setExemptFromLowPowerStandby(true)
        diagnostics.failure(SocketException())
        assertTrue(diagnostics.report().lineSequence().last().contains("lowPowerStandbyEnabled=true lowPowerStandbyExempt=true"))
    }

    @Test fun `transport failure persists only phase counts protocol and safe error codes`() {
        val diagnostics = BackgroundGenerationDiagnostics(context)
        val transport = ModelTransportFailure(ModelTransportTrace.Phase.UPLOAD, null, 12345, false,
            "h2", SocketException("EPIPE https://private.example?key=secret image-base64"))
        diagnostics.failure(IllegalStateException("private prompt", transport))
        val saved = BackgroundGenerationDiagnostics(context).report()
        assertTrue(saved.contains("request_failed:IllegalStateException<-ModelTransportFailure<-SocketException"))
        assertTrue(saved.contains("errno=EPIPE"))
        assertTrue(saved.contains("phase=UPLOAD bodyBytes=unknown expectedBytes=12345 reused=false protocol=h2"))
        for (privateData in listOf("private", "secret", "image-base64")) assertFalse(saved.contains(privateData))
    }

    @Test fun `screen off sampling observes liveness and stops when the request lease ends`() = runTest {
        val power = Shadows.shadowOf(context.getSystemService(PowerManager::class.java))
        power.setIsInteractive(false)
        val diagnostics = BackgroundGenerationDiagnostics(context, backgroundScope)
        diagnostics.begin({ true }, { false }, sampleLiveness = true)
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        val live = diagnostics.report().lineSequence().last()
        assertTrue(live.contains("liveness gapMs="))
        assertTrue(live.contains("awakeGapMs="))
        assertTrue(live.contains("importance="))
        assertTrue(live.contains("screen=false"))
        assertTrue(live.contains("cpu=true"))
        assertTrue(live.contains("lowPowerStandbyExempt=unknown")) // Android 13 has no per-app query.
        diagnostics.end()
        val finished = diagnostics.report()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("No timer or late callback may keep writing after release", finished, diagnostics.report())
    }

    @Test fun `passive diagnostics do not periodically write while screen off but retain failures`() = runTest {
        Shadows.shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        val diagnostics = BackgroundGenerationDiagnostics(context, backgroundScope)
        diagnostics.begin({ true }, { false }, sampleLiveness = false)
        runCurrent()
        val started = diagnostics.report()
        assertTrue(started.contains("generation_begin liveness=events_only"))
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals("Passive diagnostics must not introduce a periodic wakeup or disk write", started, diagnostics.report())
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(diagnostics.report().contains("screen_off"))
        diagnostics.failure(SocketException("ECONNRESET"))
        assertTrue(diagnostics.report().contains("errno=ECONNRESET"))
        diagnostics.end()
        val finished = diagnostics.report()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(finished, diagnostics.report())
    }
}
