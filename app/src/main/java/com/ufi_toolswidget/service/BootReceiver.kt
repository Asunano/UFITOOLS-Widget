package com.ufi_toolswidget.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ufi_toolswidget.BackgroundKeepAliveActivity
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.SPUtil

/**
 * 开机广播接收器：
 * 1. 如果用户开启了后台保活，设备重启后自动恢复前台服务。
 * 2. 如果用户开启了 WorkManager 周期性任务，重新调度。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                if (SPUtil.getNotificationEnabled(context)
                    && SPUtil.getBackgroundServiceEnabled(context)) {
                    BackgroundMonitorService.start(context)
                    DebugLogger.d("BootReceiver", "Background monitor service auto-started after boot")
                }
            } catch (e: Exception) {
                DebugLogger.w("BootReceiver", "Auto-start failed: ${e.message}")
            }

            // 重新调度 WorkManager 周期性保活任务
            try {
                BackgroundKeepAliveActivity.schedulePeriodicWorkerIfEnabled(context)
            } catch (e: Exception) {
                DebugLogger.w("BootReceiver", "WorkManager re-schedule failed: ${e.message}")
            }
        }
    }
}
