package com.moge.app.runtime

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.moge.app.BuildConfig
import com.moge.app.core.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A bounded, local trace of the OS protection around generation. No prompts, keys, URLs,
 * network identifiers or exception messages are stored. SharedPreferences.apply persists
 * asynchronously so a process death can still be distinguished from a socket failure.
 */
@Singleton
class BackgroundGenerationDiagnostics @Inject constructor(
    @ApplicationContext context: Context,
    @param:ApplicationScope private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val context = context.applicationContext
    private val preferences by lazy { this.context.getSharedPreferences("background_generation", Context.MODE_PRIVATE) }
    private val power get() = context.getSystemService(PowerManager::class.java)
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)
    private var cpuHeld: () -> Boolean = { false }
    private var wifiHeld: () -> Boolean = { false }
    private var session = 0L
    private var monitoring = false
    private var receiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var sampling: Job? = null
    private var service: Any? = null
    private var serviceState = "not_started"
    private var blocked: Boolean? = null

    @Synchronized
    internal fun begin(
        cpuHeld: () -> Boolean,
        wifiHeld: () -> Boolean,
        sampleLiveness: Boolean = BuildConfig.BACKGROUND_LIVENESS_SAMPLING,
    ) {
        if (monitoring) return
        this.cpuHeld = cpuHeld
        this.wifiHeld = wifiHeld
        monitoring = true
        blocked = null
        val current = ++session
        append("generation_begin liveness=${if (sampleLiveness) "periodic" else "events_only"}")
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                event(current, when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> "screen_off"
                    Intent.ACTION_SCREEN_ON -> "screen_on"
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "idle_changed"
                    else -> "power_changed"
                })
            }
        }
        runCatching {
            ContextCompat.registerReceiver(context, listener, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = listener
        }.onFailure { append("screen_observer_failed:${it.javaClass.simpleName}") }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = event(current, "network_available")
            override fun onLost(network: Network) = event(current, "network_lost")
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                synchronized(this@BackgroundGenerationDiagnostics) {
                    if (!monitoring || current != session) return
                    this@BackgroundGenerationDiagnostics.blocked = blocked
                    append("network_blocked_changed")
                }
            }
        }
        runCatching {
            connectivity?.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure { append("network_observer_failed:${it.javaClass.simpleName}") }
        if (sampleLiveness) startSampling(current)
    }

    /** Optional probe: its scheduling and I/O may perturb vendor sleep policies. */
    private fun startSampling(expected: Long) {
        var lastElapsed = SystemClock.elapsedRealtime()
        var lastUptime = SystemClock.uptimeMillis()
        sampling = scope.launch {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                synchronized(this@BackgroundGenerationDiagnostics) {
                    if (!monitoring || session != expected) return@launch
                    val elapsed = SystemClock.elapsedRealtime()
                    val uptime = SystemClock.uptimeMillis()
                    val gap = elapsed - lastElapsed
                    // No periodic disk writes while the screen is on and scheduling is normal.
                    // A delayed callback after waking is useful even if SCREEN_ON arrived first.
                    val screenOff = runCatching { power?.isInteractive == false }.getOrDefault(false)
                    if (screenOff || gap > SAMPLE_INTERVAL_MS * 2) {
                        append("liveness gapMs=$gap awakeGapMs=${uptime - lastUptime}")
                    }
                    lastElapsed = elapsed
                    lastUptime = uptime
                }
            }
        }
    }

    @Synchronized
    internal fun end() {
        monitoring = false // Late callbacks may not enter the next generation's trace.
        sampling?.cancel()
        sampling = null
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        networkCallback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        receiver = null
        networkCallback = null
        append("generation_end")
        cpuHeld = { false }
        wifiHeld = { false }
    }

    @Synchronized
    private fun event(expected: Long, name: String) {
        if (monitoring && session == expected) append(name)
    }

    @Synchronized
    internal fun serviceRequested() {
        if (service == null) serviceState = "starting"
        append("service_requested")
    }

    @Synchronized
    internal fun serviceReady(instance: Any) {
        service = instance
        serviceState = "foreground"
        append("service_foreground")
    }

    @Synchronized
    internal fun serviceFailed(error: Throwable) {
        serviceState = "failed"
        append("service_failed:${error.javaClass.simpleName}")
    }

    @Synchronized
    internal fun serviceDestroyed(instance: Any) {
        if (service !== instance) return
        service = null
        serviceState = "stopped"
        append(if (monitoring) "service_destroyed_while_generating" else "service_destroyed")
    }

    @Synchronized
    internal fun failure(error: Throwable) {
        // Only class names: transport messages may contain the user's private endpoint.
        val causes = generateSequence(error) { it.cause }.take(5)
            .joinToString("<-") { it.javaClass.simpleName }
        // Only allow-listed OS error codes, never the surrounding transport message or URL.
        val codes = generateSequence(error) { it.cause }.take(5)
            .flatMap { NETWORK_ERROR_CODE.findAll(it.message.orEmpty()).map { match -> match.value } }
            .distinct().joinToString(",")
        append("request_failed:$causes${if (codes.isEmpty()) "" else " errno=$codes"}")
    }

    /** Called on IO by the settings ViewModel; read the saved trace after a process restart too. */
    @Synchronized
    fun report(): String = buildString {
        appendLine("墨格后台生成诊断 ${BuildConfig.VERSION_NAME}")
        appendLine("package=${context.packageName} build=${BuildConfig.BUILD_TYPE} code=${BuildConfig.VERSION_CODE} " +
            "debuggable=${context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0} " +
            "debugger=${Debug.isDebuggerConnected()} livenessSampling=${BuildConfig.BACKGROUND_LIVENESS_SAMPLING}")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT} · pid=${Process.myPid()}")
        append(preferences.getString(TRACE, "暂无生成记录"))
    }

    private fun append(event: String) {
        // Diagnostics must never turn an otherwise valid generation into a failure.
        runCatching {
            val manager = power
            val process = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(process)
            val network = connectivity?.let { it.getNetworkCapabilities(it.activeNetwork) }
            val transport = when {
                network == null -> "none"
                network.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                else -> "other"
            }
            val line = "wall=${System.currentTimeMillis()} elapsed=${SystemClock.elapsedRealtime()} " +
                "uptime=${SystemClock.uptimeMillis()} pid=${Process.myPid()} $event " +
                "importance=${process.importance} " +
                "fgs=$serviceState screen=${manager?.isInteractive} idle=${manager?.isDeviceIdleMode} " +
                "powerSave=${manager?.isPowerSaveMode} exempt=${manager?.isIgnoringBatteryOptimizations(context.packageName)} " +
                "cpu=${cpuHeld()} wifiLock=${wifiHeld()} network=$transport " +
                "validated=${network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} blocked=$blocked"
            val previous = preferences.getString(TRACE, "").orEmpty().lineSequence().filter { it.isNotBlank() }
            val trace = (previous + sequenceOf(line)).toList().takeLast(48).joinToString("\n")
            preferences.edit().putString(TRACE, trace).apply()
        }
    }

    private companion object {
        const val TRACE = "trace"
        const val SAMPLE_INTERVAL_MS = 2_000L
        val NETWORK_ERROR_CODE = Regex("\\b(?:ECONNRESET|ECONNABORTED|ETIMEDOUT|ENETUNREACH|ENETDOWN|EHOSTUNREACH|EPIPE)\\b")
    }
}
