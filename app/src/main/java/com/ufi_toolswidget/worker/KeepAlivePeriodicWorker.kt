package com.ufi_toolswidget.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.NotificationHelper
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.WifiCrawl

/**
 * WorkManager 周期性保活 Worker。
 *
 * 作为 NotificationMonitor（进程内协程）的补充：
 * - NotificationMonitor 随进程死亡而停止
 * - 本 Worker 由 WorkManager 调度，进程死亡后仍可重新唤醒
 *
 * 每次执行时：
 * 1. 调用轻量 API 获取设备状态
 * 2. 检查通知阈值并触发通知
 *
 * 最小周期：15 分钟（WorkManager 限制）。
 */
class KeepAlivePeriodicWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "KeepAliveWorker"
        const val WORK_NAME = "keep_alive_periodic"
    }

    override suspend fun doWork(): Result {
        if (!SPUtil.getNotificationEnabled(applicationContext)) {
            DebugLogger.logSys(TAG, "Notifications disabled, skipping")
            return Result.success()
        }

        return try {
            val info = WifiCrawl.fetchNotificationBaseInfo(applicationContext)
            if (info != null) {
                // 假设设备在线（成功获取到数据即为在线）
                NotificationHelper.checkAndNotify(
                    context = applicationContext,
                    dailyFlowStr = info.dailyFlowStr,
                    monthlyFlowStr = info.monthlyFlowStr,
                    tempStr = info.tempStr,
                    cpuStr = info.cpuStr,
                    memStr = info.memStr,
                    batteryPercent = info.batteryPercent,
                    isDeviceOnline = true
                )
                DebugLogger.logSys(TAG, "Periodic check completed successfully")
                Result.success()
            } else {
                DebugLogger.w(TAG, "Failed to fetch device info")
                // 获取数据失败，可能设备不在线，仍通知检查设备在线状态
                NotificationHelper.checkAndNotify(
                    context = applicationContext,
                    dailyFlowStr = "",
                    monthlyFlowStr = "",
                    tempStr = "",
                    cpuStr = "",
                    memStr = "",
                    batteryPercent = -1,
                    isDeviceOnline = false
                )
                Result.retry()
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Periodic worker error: ${e.message}")
            Result.retry()
        }
    }
}
