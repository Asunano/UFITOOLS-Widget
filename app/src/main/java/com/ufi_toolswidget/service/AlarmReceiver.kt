package com.ufi_toolswidget.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.NotificationMonitor
import com.ufi_toolswidget.util.SPUtil

/**
 * 精确定时器接收器：穿透 Doze 模式，确保后台通知检查不会因 CPU 休眠而中断。
 *
 * 使用 [AlarmManager.setAlarmClock] 设置闹钟，这是 Android 唯一不受
 * Doze / App Standby 任何限制的 API，能保证精确触发且无频率上限。
 *
 * 与 [NotificationMonitor]（协程轮询）和 WorkManager（周期任务）互补：
 * - NotificationMonitor 提供最快 15s 轮询，但进程死亡即失效
 * - WorkManager 持久但受 Doze 延迟（最小 15min + 维护窗口）
 * - AlarmReceiver 填补空白：持久化 + Doze 完全穿透 + 可调间隔
 *
 * 进程死亡自动恢复：
 * - 当用户开启自动恢复功能时，闹钟触发后检测前台服务是否存活
 * - 若进程已被杀死，自动重启 BackgroundMonitorService 和 NotificationMonitor
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val ACTION_CHECK = "com.ufi_toolswidget.ACTION_ALARM_CHECK"
        private const val REQUEST_CODE = 8472

        /**
         * 最小闹钟间隔（毫秒）。
         * setAlarmClock 不受 Doze 频率限制，5 分钟可保证及时性。
         */
        private const val MIN_ALARM_INTERVAL_MS = 5 * 60 * 1000L

        @Volatile
        private var wakeLock: PowerManager.WakeLock? = null

        /**
         * 调度下一次闹钟。
         * 可在 Application.onCreate()、BootReceiver、前台服务等处调用。
         * 仅当通知功能开启时生效。
         */
        fun scheduleNext(context: Context) {
            val appCtx = context.applicationContext
            if (!SPUtil.getNotificationEnabled(appCtx)) {
                DebugLogger.d(TAG, "Notifications disabled, skip alarm scheduling")
                return
            }

            val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // 计算触发时间：基于用户设置的监控间隔，但不低于最小值
            val monitorSec = SPUtil.getMonitorIntervalSec(appCtx).toLong().coerceIn(15, 600)
            val intervalMs = (monitorSec * 1000L).coerceAtLeast(MIN_ALARM_INTERVAL_MS)
            val triggerAtMs = SystemClock.elapsedRealtime() + intervalMs

            val intent = Intent(appCtx, AlarmReceiver::class.java).apply {
                action = ACTION_CHECK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                appCtx, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 优先使用 setAlarmClock —— 唯一不受 Doze 任何限制的 API
            // showIntent 传 null：不显示状态栏闹钟图标，不影响穿透能力
            try {
                val alarmInfo = AlarmManager.AlarmClockInfo(triggerAtMs, null)
                alarmManager.setAlarmClock(alarmInfo, pendingIntent)
                DebugLogger.d(TAG, "AlarmClock scheduled in ${intervalMs / 1000}s")
                return
            } catch (e: SecurityException) {
                DebugLogger.w(TAG, "setAlarmClock denied: ${e.message}, trying setExact")
            } catch (e: Exception) {
                DebugLogger.w(TAG, "setAlarmClock failed: ${e.message}, trying setExact")
            }

            // 降级 1：精确闹钟（Doze 期间约 9 分钟一次上限）
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (!alarmManager.canScheduleExactAlarms()) {
                        DebugLogger.w(TAG, "SCHEDULE_EXACT_ALARM not granted, fallback to inexact")
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMs, pendingIntent
                        )
                        return
                    }
                }
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMs, pendingIntent
                )
                DebugLogger.d(TAG, "Exact alarm scheduled in ${intervalMs / 1000}s")
            } catch (e: Exception) {
                // 降级 2：非精确闹钟（仍可工作，时间精度更低）
                DebugLogger.w(TAG, "Exact alarm failed: ${e.message}, fallback to inexact")
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMs, pendingIntent
                )
            }
        }

        /** 取消已调度的闹钟 */
        fun cancel(context: Context) {
            val alarmManager = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = ACTION_CHECK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            DebugLogger.d(TAG, "Alarm cancelled")
        }

        @Synchronized
        private fun acquireWakeLock(context: Context): PowerManager.WakeLock {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "UFITOOLS:AlarmCheckLock"
            )
            lock.setReferenceCounted(false)
            lock.acquire(30_000L) // 30 秒超时保护
            wakeLock = lock
            return lock
        }

        @Synchronized
        private fun releaseWakeLock() {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) return

        DebugLogger.d(TAG, "Alarm fired, acquiring WakeLock")
        acquireWakeLock(context)

        val appCtx = context.applicationContext

        // 通知功能关闭时不再链式调度
        if (!SPUtil.getNotificationEnabled(appCtx)) {
            DebugLogger.d(TAG, "Notifications disabled, skip")
            releaseWakeLock()
            return
        }

        // ─── 进程死亡自动恢复 ───
        // 用户开启自动恢复时，检测前台服务是否存活，若进程已被杀死则自动重启
        if (SPUtil.getAutoRecoverEnabled(appCtx)
            && SPUtil.getBackgroundServiceEnabled(appCtx)) {
            try {
                // 重新调度 WorkManager 周期性保活任务（如果启用）
                com.ufi_toolswidget.BackgroundKeepAliveActivity
                    .schedulePeriodicWorkerIfEnabled(appCtx)

                // 重启前台保活服务
                BackgroundMonitorService.start(appCtx)

                // 重启 NotificationMonitor 协程轮询
                NotificationMonitor.start(appCtx)

                DebugLogger.d(TAG, "Auto-recovery: services restarted")
            } catch (e: Exception) {
                DebugLogger.w(TAG, "Auto-recovery failed: ${e.message}")
            }
        }

        // 使用 NotificationMonitor 的公开方法执行一次性检查
        // 内部使用 Dispatchers.IO，检查完成后自动释放 WakeLock 并调度下一次闹钟
        NotificationMonitor.performOneShotCheck(appCtx) {
            releaseWakeLock()
            scheduleNext(appCtx)
        }
    }
}
