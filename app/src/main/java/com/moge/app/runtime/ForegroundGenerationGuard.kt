package com.moge.app.runtime

import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 生成期间的进程保活契约。
 *
 * 没有保活时 App 切后台/熄屏后进程降级为 cached，随后被系统冻结（Android 12+
 * Cached Apps Freezer）或被厂商 ROM 查杀，SSE 读取随之中断。生成期间启动前台服务
 * 抬高进程优先级，可以显著降低冻结/查杀概率。
 */
interface GenerationGuard {
    /**
     * 按 **requestId 持有**一个保护令牌。
     *
     * 不用引用计数：A 结束的 `release` 恰好排在 B 的 `acquire` 之后时，计数实现会把
     * B 刚拿到的保护一起扣掉。按 id 持有天然幂等：同一 id 重复 acquire 不叠加，
     * 未持有过的 id release 无副作用。
     *
     * [conversationId] 带进通知，点击通知直接回到那道题。
     */
    fun acquire(requestId: String, conversationId: String? = null): String

    /** 释放令牌；所有出口（成功/失败/取消）都必须调用。 */
    fun release(token: String)

    /** 当前受保护的请求数，供测试与诊断断言。 */
    fun activeCount(): Int
}

/**
 * 按 requestId 持有令牌的前台服务保护。
 *
 * - Android 12+ 后台启动前台服务受限 → 启动失败时**不崩溃**，只是少了保活；
 * - Android 13 通知权限未授予 → 服务仍能起，通知可能不显示，不影响正确性；
 * - Android 14 要求声明服务类型 → FOREGROUND_SERVICE_TYPE_DATA_SYNC；
 * - Android 15 dataSync 有超时 → 超时回调里交给管理器收尾并停止服务。
 */
@Singleton
class ForegroundGenerationGuard @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : GenerationGuard {

    private val holders = ConcurrentHashMap<String, String>()
    private val cpu = GenerationWakeLock(context)
    private val wifi = GenerationWifiLock(context)

    /**
     * 「改持有者集合 + 起/停服务」是一个复合动作，必须串行：否则 A 刚 putIfAbsent
     * 还没 startService 时，B 已 remove 并看到空集合去 stopService，
     * A 以为自己有保护，服务却已被停掉。
     */
    private val lock = Any()

    override fun acquire(requestId: String, conversationId: String?): String {
        synchronized(lock) {
            val token = "$requestId#${System.nanoTime()}"
            val previous = holders.putIfAbsent(requestId, token)
            if (previous != null) return previous
            // 启动失败时不保留持有者：activeCount() 不能虚报「已受保护」。
            if (!startServiceSafely(conversationId)) {
                holders.remove(requestId, token)
            } else {
                cpu.start()
                wifi.start()
            }
            return token
        }
    }

    override fun release(token: String) {
        synchronized(lock) {
            val requestId = token.substringBeforeLast('#')
            // 只有仍持有该令牌的调用者才有权释放；晚到的旧 release 不会误停服务。
            if (holders.remove(requestId, token) && holders.isEmpty()) {
                cpu.stop()
                wifi.stop()
                stopServiceSafely()
            }
        }
    }

    override fun activeCount(): Int = holders.size

    private fun startServiceSafely(conversationId: String?): Boolean =
        runCatching { context.startForegroundService(KeepAliveService.intent(context, conversationId)) }
            .onFailure { Log.w(TAG, "启动保活前台服务失败", it) }
            .isSuccess

    private fun stopServiceSafely() {
        runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
            .onFailure { Log.w(TAG, "停止保活前台服务失败", it) }
    }

    private companion object {
        const val TAG = "MogeKeepAlive"
    }
}
