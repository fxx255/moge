package com.moge.app.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.moge.app.data.llm.GenerationNetworkSession
import com.moge.app.data.llm.NetworkAvailability
import com.moge.app.data.llm.NetworkRecoveryStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Observes connectivity only while waiting; an available but unvalidated VPN may still reach the model. */
@Singleton
class GenerationNetworkRecovery @Inject constructor(@ApplicationContext context: Context) : NetworkAvailability {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    override val available: Boolean get() = connectivity == null || runCatching {
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    override suspend fun awaitAvailable(timeoutMs: Long): Boolean {
        if (available) return true
        val manager = connectivity ?: return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // These versions have no per-UID unblock callback. activeNetwork stays null while
            // access is blocked, even when the default network still advertises INTERNET.
            return withTimeoutOrNull(timeoutMs) {
                while (!available) delay(1_000)
                true
            } ?: false
        }
        val ready = CompletableDeferred<Unit>()
        val callback = RecoveryNetworkCallback { ready.complete(Unit) }
        return withTimeoutOrNull(timeoutMs) {
            try {
                manager.registerDefaultNetworkCallback(callback)
            } catch (_: RuntimeException) {
                // Let the transport try if the system cannot register a listener.
                return@withTimeoutOrNull true
            }
            try {
                ready.await()
                true
            } finally {
                runCatching { manager.unregisterNetworkCallback(callback) }
            }
        } ?: false
    }

    fun session(status: (NetworkRecoveryStatus?) -> Unit) = GenerationNetworkSession(this, status)
}

/** The framework delivers these callbacks in order on its connectivity thread. */
private class RecoveryNetworkCallback(private val onReady: () -> Unit) : ConnectivityManager.NetworkCallback() {
    private var current: Network? = null
    private var hasInternet = false
    private var unblocked = false

    override fun onAvailable(network: Network) {
        current = network
        hasInternet = false
        // Android 10+ reports the initial blocked state after capabilities.
        unblocked = false
    }

    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        if (network != current) return
        hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        notifyIfReady()
    }

    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
        if (network != current) return
        unblocked = !blocked
        notifyIfReady()
    }

    override fun onLost(network: Network) {
        if (network == current) current = null
    }

    private fun notifyIfReady() {
        if (hasInternet && unblocked) onReady()
    }
}
