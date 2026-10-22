package com.moge.app.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.MainActivity
import com.moge.app.data.llm.OkHttpCancellation
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real foreground-service lifecycle and a slow socket; no provider calls or saved model settings. */
@RunWith(AndroidJUnit4::class)
class BackgroundGenerationDeviceTest {
    @Test fun foregroundLeaseAndStreamSurviveLeavingActivity() = verify(screenOff = false)

    @Test fun foregroundLeaseAndStreamSurviveScreenOffAndReleaseAfterwards() = verify(screenOff = true)

    private fun verify(screenOff: Boolean) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val entry = EntryPointAccessors.fromApplication(context, KeepAliveEntryPoint::class.java)
        val guard = entry.generationGuard()
        val power = context.getSystemService(PowerManager::class.java)
        val client = OkHttpClient.Builder().readTimeout(15, TimeUnit.SECONDS).build()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val token = withContext(Dispatchers.Main) { guard.acquire(UUID.randomUUID().toString()) }
        fun shell(command: String) {
            instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
        }
        fun foregroundRunning(): Boolean {
            @Suppress("DEPRECATION")
            return context.getSystemService(ActivityManager::class.java).getRunningServices(100)
                .any { it.service.className == KeepAliveService::class.java.name && it.foreground }
        }
        try {
            withTimeout(10_000) { while (!foregroundRunning()) delay(50) }
            val writer = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (reader.readLine().orEmpty().isNotEmpty()) Unit
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                    repeat(20) { index ->
                        output.write("data: $index\n\n".toByteArray())
                        output.flush()
                        delay(1_000)
                    }
                }
            }
            val reader = async(Dispatchers.IO) {
                OkHttpCancellation.execute(client, Request.Builder()
                    .url("http://127.0.0.1:${server.localPort}/stream").build()).use { response ->
                    response.body!!.string()
                }
            }
            delay(2_000)
            shell("input keyevent 3")
            if (screenOff) {
                shell("input keyevent 223")
                val slept = withTimeoutOrNull(5_000) {
                    while (power.isInteractive) delay(50)
                    true
                } == true
                // Some emulator firmware ignores sleep entirely; this cannot count as screen-off QA.
                assumeTrue("Device firmware did not enter sleep; screen-off remains unverified", slept)
            }
            val body = withTimeout(35_000) { reader.await() }
            writer.await()
            if (screenOff) assertFalse("Screen must remain off while the stream completes", power.isInteractive)
            assertTrue(foregroundRunning())
            assertEquals(20, body.lineSequence().count { it.startsWith("data:") })
            guard.recordFailure(token, java.net.SocketException("test-only snapshot"))
            val trace = entry.backgroundDiagnostics().report()
            if (screenOff) {
                assertTrue(trace.contains("screen_off"))
                assertTrue(trace.lineSequence().last().contains("screen=false"))
            }
            assertTrue(trace.lineSequence().last().contains("fgs=foreground"))
            assertTrue(trace.lineSequence().last().contains("cpu=true"))
        } finally {
            guard.release(token)
            server.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            shell("input keyevent 224")
            scenario.close()
        }
        withTimeout(10_000) { while (foregroundRunning()) delay(50) }
        assertEquals(0, guard.activeCount())
    }
}
