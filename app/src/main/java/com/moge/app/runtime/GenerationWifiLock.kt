package com.moge.app.runtime

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/** On Android 8–13, protect Wi-Fi during generation. Doze still requires a battery exemption. */
internal class GenerationWifiLock(context: Context) {
    private val context = context.applicationContext
    private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    @Suppress("DEPRECATION")
    fun start() {
        // Android 14+ maps HIGH_PERF to LOW_LATENCY, which is inactive with the screen off.
        // Avoid that unnecessary lock; the user's battery exemption protects background networking.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            val lease = wifiLock ?: context.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "moge:generation:wifi")
                ?.apply { setReferenceCounted(false) }?.also { wifiLock = it }
            if (lease?.isHeld == false) lease.acquire()
        }.onFailure { Log.w("MogeKeepAlive", "获取生成 Wi-Fi 锁失败", it) }
    }

    @Synchronized
    fun stop() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
            .onFailure { Log.w("MogeKeepAlive", "释放生成 Wi-Fi 锁失败", it) }
    }
}
