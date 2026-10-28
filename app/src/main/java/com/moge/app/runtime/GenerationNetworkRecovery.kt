package com.moge.app.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.moge.app.data.llm.GenerationNetworkSession
import com.moge.app.data.llm.NetworkAvailability
import com.moge.app.data.llm.NetworkRecoveryStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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
        var observer: ConnectivityManager.NetworkCallback? = null
        return try { withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val decided = AtomicBoolean(false)
                fun ready() {
                    if (available && decided.compareAndSet(false, true)) continuation.resume(true)
                }
                val callback = object : ConnectivityManager.NetworkCallback() {
                    private fun check() {
                        ready()
                    }
                    override fun onAvailable(network: Network) = check()
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = check()
                }
                observer = callback
                continuation.invokeOnCancellation {
                    decided.set(true)
                    runCatching { connectivity?.unregisterNetworkCallback(callback) }
                }
                try {
                    connectivity?.registerDefaultNetworkCallback(callback)
                    ready()
                } catch (_: Exception) {
                    if (decided.compareAndSet(false, true)) continuation.resume(true)
                }
            }
        } ?: false } finally {
            observer?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        }
    }

    fun session(status: (NetworkRecoveryStatus?) -> Unit) = GenerationNetworkSession(this, status)
}
