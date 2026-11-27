package com.moge.app.runtime

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [33], application = Application::class)
class GenerationNetworkRecoveryTest {
    private val manager = mockk<ConnectivityManager>(relaxed = true)
    private val registered = slot<ConnectivityManager.NetworkCallback>()
    private val network = mockk<Network>()
    private val internet = mockk<NetworkCapabilities> {
        every { hasCapability(any()) } returns false
        every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
    }

    private fun recovery(): GenerationNetworkRecovery {
        every { manager.activeNetwork } returns null
        every { manager.getNetworkCapabilities(null) } returns null
        every { manager.registerDefaultNetworkCallback(capture(registered)) } returns Unit
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.CONNECTIVITY_SERVICE) manager else super.getSystemService(name)
        }
        return GenerationNetworkRecovery(context)
    }

    @Test fun `unblocking the same network releases the wait without another capabilities event`() = runTest {
        val recovery = recovery()
        val wait = async(start = CoroutineStart.UNDISPATCHED) { recovery.awaitAvailable(60_000) }
        val callback = registered.captured
        callback.onAvailable(network)
        callback.onCapabilitiesChanged(network, internet)
        callback.onBlockedStatusChanged(network, true)
        testScheduler.runCurrent()
        assertFalse(wait.isCompleted)
        callback.onBlockedStatusChanged(network, false)
        assertTrue(wait.await())
        verify(exactly = 1) { manager.unregisterNetworkCallback(callback) }
    }

    @Test fun `handover discards old capability and blocked status`() = runTest {
        val recovery = recovery()
        val wait = async(start = CoroutineStart.UNDISPATCHED) { recovery.awaitAvailable(60_000) }
        val callback = registered.captured
        val replacement = mockk<Network>()
        callback.onAvailable(network)
        callback.onCapabilitiesChanged(network, internet)
        callback.onAvailable(replacement)
        callback.onLost(network)
        callback.onBlockedStatusChanged(network, false)
        callback.onBlockedStatusChanged(replacement, false)
        testScheduler.runCurrent()
        assertFalse(wait.isCompleted)
        callback.onCapabilitiesChanged(replacement, internet)
        assertTrue(wait.await())
    }

    @Test @Config(sdk = [28])
    fun `older Android waits for UID access even when the network advertises internet`() = runTest {
        val recovery = recovery()
        every { manager.getNetworkCapabilities(network) } returns internet
        val wait = async(start = CoroutineStart.UNDISPATCHED) { recovery.awaitAvailable(60_000) }
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        assertFalse(wait.isCompleted)

        // Same network, no capability or validation change. Only the UID access is restored.
        every { manager.activeNetwork } returns network
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertTrue(wait.await())
        verify(exactly = 0) { manager.registerDefaultNetworkCallback(any()) }
    }

    @Test @Config(sdk = [28])
    fun `older Android stops polling after timeout or cancellation`() = runTest {
        val recovery = recovery()
        assertFalse(recovery.awaitAvailable(1_500))
        val wait = async(start = CoroutineStart.UNDISPATCHED) { recovery.awaitAvailable(60_000) }
        wait.cancelAndJoin()
        clearMocks(manager, answers = false)
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        verify(exactly = 0) { manager.activeNetwork }
    }

    @Test fun `timeout and cancellation each unregister their own observer once`() = runTest {
        val recovery = recovery()
        assertFalse(recovery.awaitAvailable(60_000))
        val timedOut = registered.captured
        val wait = async(start = CoroutineStart.UNDISPATCHED) { recovery.awaitAvailable(60_000) }
        val cancelled = registered.captured
        wait.cancelAndJoin()
        verify(exactly = 1) { manager.unregisterNetworkCallback(timedOut) }
        verify(exactly = 1) { manager.unregisterNetworkCallback(cancelled) }
    }
}
