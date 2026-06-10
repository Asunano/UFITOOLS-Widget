package com.ufi_toolswidget.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ufi_toolswidget.MainActivity
import com.ufi_toolswidget.R
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.NotificationMonitor
import com.ufi_toolswidget.util.SPUtil

/**
 * 后台保活前台服务。
 *
 * 通过常驻前台通知提高进程优先级，防止系统回收导致
 * [NotificationMonitor] 协程中断，确保通知功能在任何场景下正常工作。
 */
class BackgroundMonitorService : Service() {

    companion object {
        private const val TAG = "BgMonitorService"
        private const val CHANNEL_ID = "background_monitor"
        private const val CHANNEL_NAME = "后台监控"
        private const val NOTIFICATION_ID = 9001

        /** 启动保活服务 */
        fun start(context: Context) {
            try {
                val intent = Intent(context, BackgroundMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                DebugLogger.d(TAG, "Service start requested")
            } catch (e: Exception) {
                DebugLogger.w(TAG, "Service start failed: ${e.message}")
            }
        }

        /** 停止保活服务 */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, BackgroundMonitorService::class.java))
                DebugLogger.d(TAG, "Service stop requested")
            } catch (e: Exception) {
                DebugLogger.w(TAG, "Service stop failed: ${e.message}")
            }
        }

        /** 根据 SP 状态自动启动或停止服务 */
        fun syncState(context: Context) {
            if (SPUtil.getBackgroundServiceEnabled(context)
                && SPUtil.getNotificationEnabled(context)) {
                start(context)
            } else {
                stop(context)
            }
        }

        /** 刷新前台通知内容（用于用户修改自定义标题/内容后即时生效） */
        fun refreshNotification(context: Context) {
            if (!SPUtil.getBackgroundServiceEnabled(context)) return
            try {
                val intent = Intent(context, BackgroundMonitorService::class.java).apply {
                    action = ACTION_REFRESH_NOTIFICATION
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                DebugLogger.w(TAG, "refreshNotification failed: ${e.message}")
            }
        }

        private const val ACTION_REFRESH_NOTIFICATION = "com.ufi_toolswidget.ACTION_REFRESH_NOTIFICATION"
    }

    /**
     * 前台服务本身通过 IMPORTANCE_DEFAULT 通知保持可见性，防止国产 ROM 静默降级。
     * 不再持有长时间 WakeLock：CPU 休眠时由 AlarmReceiver.setAlarmClock 定期唤醒执行检查。
     * 长时间 PARTIAL_WAKE_LOCK 会阻止 CPU 进入深度睡眠，每天额外消耗 12-30% 电池。
     */

    override fun onCreate() {
        super.onCreate()
        try {
            createServiceChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
            DebugLogger.d(TAG, "Service created, foreground notification shown")
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Service onCreate failed: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 收到刷新通知的 action 时，更新前台通知内容
        if (intent?.action == ACTION_REFRESH_NOTIFICATION) {
            updateNotification()
        }
        // 被系统杀死后自动重启，保证保活连续性
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        DebugLogger.d(TAG, "Service destroyed")
    }

    private fun createServiceChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT  // 默认可见性，防止国产 ROM 静默降级
            ).apply {
                description = "保持后台监控运行，确保通知功能正常工作"
                setShowBadge(false)  // 不在应用图标上显示角标
                setSound(null, null) // 不播放声音，但仍然可见
                enableVibration(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val customTitle = SPUtil.getCustomNotifTitle(this).ifEmpty { "后台监控运行中" }
        val customText = SPUtil.getCustomNotifText(this).ifEmpty { "通知功能正在后台持续工作" }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(customTitle)
            .setContentText(customText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** 更新已显示的前台通知（用户修改自定义内容后调用） */
    private fun updateNotification() {
        try {
            val notification = buildNotification()
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, notification)
            DebugLogger.d(TAG, "Foreground notification refreshed")
        } catch (e: Exception) {
            DebugLogger.w(TAG, "updateNotification failed: ${e.message}")
        }
    }
}
