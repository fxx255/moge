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
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

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
        runCatching {
            ensureChannel(this)
            val notification = buildNotification(generationManager?.activeConversationId())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            Log.w(TAG, "系统拒绝前台保活，生成仍由应用管理器持有", it)
            stopSelf()
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
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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
