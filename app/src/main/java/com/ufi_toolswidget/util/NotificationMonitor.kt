package com.ufi_toolswidget.util

import android.content.Context
import com.ufi_toolswidget.worker.WifiWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 后台通知监控器。
 *
 * 独立于 MainActivity 和 WifiWorker，在应用生命周期内持续运行。
 * 周期性地轻量级获取设备 baseInfo 并检查通知阈值，
 * 当超出阈值时通过系统通知栏推送提醒。
 *
 * 与 [WifiWorker] 和 [WifiCrawl.getWifiData] 不同：
 * - 只请求 /api/deviceInfo 一个端点，不发起并发请求
 * - 没有冷却延迟，性能开销极小
 * - 60 秒间隔，实时性远高于 Worker 周期
 *
 * 在 [UfiToolsApplication.onCreate] 中启动。
 */
object NotificationMonitor {

    private const val TAG = "NotificationMonitor"

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 获取当前设置的监控间隔（毫秒）。
     * 从 SP 动态读取，支持用户在通知管理界面实时调整。
     */
    private fun getIntervalMs(context: Context): Long {
        val sec = SPUtil.getMonitorIntervalSec(context).coerceIn(15, 600)
        return sec * 1000L
    }

    /**
     * 启动后台通知监控。
     * 在 Application.onCreate() 中调用，应用进程存活期间持续运行。
     *
     * @param context 上下文（建议传 ApplicationContext）
     */
    fun start(context: Context) {
        if (job?.isActive == true) {
            DebugLogger.d(TAG, "start: already running, skip")
            return
        }
        val appCtx = context.applicationContext
        job = scope.launch {
            val initialInterval = getIntervalMs(appCtx)
            DebugLogger.i(TAG, "NotificationMonitor started, interval=${initialInterval}ms")
            // 首次启动时立即执行一次检查，无需等待
            performCheck(appCtx)

            while (isActive) {
                // 每次循环都重新读取间隔，支持用户实时调整
                val intervalMs = getIntervalMs(appCtx)
                delay(intervalMs)
                performCheck(appCtx)
            }
        }
    }

    /**
     * 停止后台通知监控。
     */
    fun stop() {
        job?.cancel()
        job = null
        DebugLogger.d(TAG, "NotificationMonitor stopped")
    }

    /**
     * 执行一次完整的通知检查。
     * 轻量级获取设备数据并调用 [NotificationHelper.checkAndNotify]。
     */
    private suspend fun performCheck(context: Context) {
        try {
            val info = WifiCrawl.fetchNotificationBaseInfo(context)
            if (info != null) {
                NotificationHelper.checkAndNotify(
                    context = context,
                    dailyFlowStr = info.dailyFlowStr,
                    monthlyFlowStr = info.monthlyFlowStr,
                    tempStr = info.tempStr,
                    cpuStr = info.cpuStr,
                    memStr = info.memStr,
                    batteryPercent = info.batteryPercent,
                    isDeviceOnline = !WifiWorker.isWorkerStopped(context),
                    activity = null
                )
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "performCheck failed: ${e.message}")
        }
    }
}
