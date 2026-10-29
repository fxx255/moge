package com.moge.app.runtime

import android.app.Application
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.net.SocketException

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [33], application = Application::class)
class BackgroundGenerationProtectionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

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
        diagnostics.serviceDestroyed(older)
        diagnostics.failure(SocketException())
        assertTrue(diagnostics.report().lineSequence().last().contains("fgs=foreground"))
        diagnostics.serviceDestroyed(newer)
        assertTrue(diagnostics.report().contains("service_destroyed_while_generating"))
        assertTrue(diagnostics.report().lineSequence().last().contains("fgs=stopped"))
        diagnostics.end()
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
