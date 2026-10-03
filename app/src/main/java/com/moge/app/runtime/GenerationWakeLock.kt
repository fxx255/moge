package com.moge.app.runtime

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keep the CPU available while receiving a response, without keeping the screen on. */
internal class GenerationWakeLock(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val lock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var renewal: Job? = null

    fun start() = synchronized(lock) {
        acquireSafely()
        if (renewal?.isActive == true) return@synchronized
        renewal = scope.launch {
            val self = currentCoroutineContext()[Job]
            while (isActive) {
                delay(RENEW_INTERVAL_MS)
                synchronized(lock) { if (renewal === self) acquireSafely() }
            }
        }
    }

    fun stop() = synchronized(lock) {
        renewal?.cancel()
        renewal = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            .onFailure { Log.w("MogeKeepAlive", "释放生成唤醒锁失败", it) }
        Unit
    }

    private fun acquireSafely() {
        runCatching {
            val current = wakeLock ?: context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "moge:generation")
                ?.apply { setReferenceCounted(false) }?.also { wakeLock = it }
            // Timeout is a second safeguard in addition to every task's finally block.
            current?.acquire(LEASE_TIMEOUT_MS)
        }.onFailure { Log.w("MogeKeepAlive", "获取生成唤醒锁失败", it) }
    }

    companion object {
        internal const val RENEW_INTERVAL_MS = 5 * 60 * 1000L
        internal const val LEASE_TIMEOUT_MS = 15 * 60 * 1000L
    }
}
