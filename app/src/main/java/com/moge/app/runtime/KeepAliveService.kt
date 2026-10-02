package com.moge.app.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.moge.app.MainActivity
import com.moge.app.R
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
    @ApplicationContext private val context: Context,
) : GenerationGuard {

    private val holders = ConcurrentHashMap<String, String>()

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
            if (!startServiceSafely(conversationId)) holders.remove(requestId, token)
            return token
        }
    }

    override fun release(token: String) {
        synchronized(lock) {
            val requestId = token.substringBeforeLast('#')
            // 只有仍持有该令牌的调用者才有权释放；晚到的旧 release 不会误停服务。
            if (holders.remove(requestId, token) && holders.isEmpty()) stopServiceSafely()
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

/**
 * 生成期间的常驻通知。服务只抬高进程优先级，不承载生成逻辑。
 *
 * `START_NOT_STICKY`：进程被杀后不由系统拉起，恢复走持久记录的重发入口。
 */
class KeepAliveService : Service() {

    private var generationManager: GenerationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 注入失败也不该让服务崩溃（保活是尽力而为）。
        generationManager = runCatching {
            EntryPointAccessors.fromApplication(applicationContext, KeepAliveEntryPoint::class.java)
                .generationManager()
        }.onFailure { Log.w(TAG, "注入生成管理器失败，超时回调将无法取消网络", it) }.getOrNull()
        ensureChannel(this)
        val notification = buildNotification(generationManager?.activeConversationId())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(intent?.getStringExtra(EXTRA_CONVERSATION_ID)))
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15 起 dataSync 前台服务有运行时长上限，超时后系统调用这里。
     * 必须实现双参数版本：只实现单参数的 `onTimeout(startId)` 在 15 上不会被调用。
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        handleTimeout()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        handleTimeout()
    }

    /** 取消网络与落 INTERRUPTED 交给管理器自己的作用域，服务随即停止。 */
    private fun handleTimeout() {
        Log.w(TAG, "前台服务超时：中断活动请求并停止服务")
        generationManager?.onForegroundServiceTimeout()
        stopSelf()
    }

    private fun buildNotification(conversationId: String?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.keepalive_title))
            .setContentText(getString(R.string.keepalive_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent(conversationId))
            .build()

    private fun contentIntent(conversationId: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            conversationId?.let { putExtra(EXTRA_CONVERSATION_ID, it) }
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        // 请求码按会话区分：不同题目的通知点击各自落回对应题目。
        return PendingIntent.getActivity(
            this,
            conversationId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "MogeKeepAlive"
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "generation"

        /** 通知携带的会话 id；MainActivity 据此打开对应题目。 */
        const val EXTRA_CONVERSATION_ID = "moge_conversation_id"

        fun intent(context: Context, conversationId: String?): Intent =
            Intent(context, KeepAliveService::class.java).apply {
                conversationId?.let { putExtra(EXTRA_CONVERSATION_ID, it) }
            }

        /** 幂等；Application 启动与服务创建时各调一次。 */
        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.channel_generation_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.channel_generation_desc) },
            )
        }
    }
}

/** Service 不走 @AndroidEntryPoint，经入口点取单例管理器。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface KeepAliveEntryPoint {
    fun generationManager(): GenerationManager
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RuntimeModule {
    @Binds
    abstract fun bindGuard(impl: ForegroundGenerationGuard): GenerationGuard

    @Binds
    abstract fun bindFigureRenderer(impl: DefaultFigureRenderer): FigureRenderer

    @Binds
    abstract fun bindClock(impl: SystemMonotonicClock): com.moge.app.data.llm.MonotonicClock
}

/** 注入用的单调时钟（测试替换成假时钟）。 */
class SystemMonotonicClock @Inject constructor() : com.moge.app.data.llm.MonotonicClock {
    override fun nanoTime(): Long = System.nanoTime()
}
