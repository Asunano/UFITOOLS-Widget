package com.ufi_toolswidget.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.MainDialogHelper
import com.ufi_toolswidget.util.NotificationHelper
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.TrafficRecordManager
import com.ufi_toolswidget.util.WifiCrawl
import com.ufi_toolswidget.widget.BaseWifiWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class WifiWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG = "WifiWorker"

        /** TCP ping 超时（毫秒），内网正常 < 100ms */
        private const val PING_TIMEOUT_MS = 1000

        /** API 请求连续失败多少次后才标记 stopped（ping 通过的前提下） */
        const val API_MAX_FAILURES = 3

        /** 网络不通连续多少次后才标记 stopped */
        const val NETWORK_MAX_FAILURES = 2

        /** 失败原因常量 */
        const val REASON_NETWORK = "network"   // 设备不在线/网络不通
        const val REASON_API = "api"           // 端口/Token/认证配置错误

        // ── 以下方法统一委托给 SPUtil（线程安全 + commit()）──

        fun isWorkerStopped(context: Context) = SPUtil.isWorkerStopped(context)

        fun getFailureSummary(context: Context) = SPUtil.getWorkerFailureSummary(context)

        fun resetFailureState(context: Context) = SPUtil.resetWorkerFailureState(context)

        fun incrementNetworkFailureCount(context: Context) = SPUtil.incrementNetworkFailureCount(context)

        fun incrementApiFailureCount(context: Context) = SPUtil.incrementApiFailureCount(context)

        fun markWorkerStoppedNetwork(context: Context) = SPUtil.markWorkerStoppedNetwork(context)

        fun markWorkerStoppedApi(context: Context) = SPUtil.markWorkerStoppedApi(context)

        /** 仅重置网络失败计数（ping 恢复时，内部委托 SPUtil） */
        private fun resetNetworkFailCount(context: Context) = SPUtil.resetNetworkFailureCount(context)

        /**
         * TCP ping 设备 IP:端口，检测网络是否可达。
         * 内网环境正常响应远低于 1 秒，超过则视为不可达。
         */
        private fun pingDevice(ip: String, port: Int): Boolean {
            return try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, port), PING_TIMEOUT_MS)
                    Log.d(TAG, "Ping $ip:$port OK")
                    true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ping $ip:$port FAILED: ${e.message}")
                false
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext

        val host = SPUtil.getDeviceHost(ctx)
        val port = SPUtil.getDevicePortInt(ctx)

        val prevStopped = SPUtil.isWorkerStopped(ctx)
        val prevApiFails = SPUtil.getApiFailureCount(ctx)
        val prevNetFails = SPUtil.getNetworkFailureCount(ctx)
        DebugLogger.d(TAG, "doWork() started: addr=$host:$port, prevStopped=$prevStopped, apiFails=$prevApiFails, netFails=$prevNetFails")

        // ====== 步骤 1：每次运行都先 ping 检测网络可达性 ======
        // 即使之前被 stopped，也要尝试 ping — 这样设备恢复后能自动感知
        val pingOk = pingDevice(host, port)
        DebugLogger.d(TAG, "doWork: ping $host:$port = $pingOk")

        if (!pingOk) {
            // 网络不通 → 设备离线，通知检查上下线状态
            NotificationHelper.checkDeviceOnlineStatus(ctx, isOnline = false)
            // （原子递增，避免与前台竞态）
            val networkFails = incrementNetworkFailureCount(ctx)
            DebugLogger.w(TAG, "doWork: network unreachable ($networkFails/$NETWORK_MAX_FAILURES)")

            Log.w(TAG, "Device unreachable ($networkFails/$NETWORK_MAX_FAILURES)")

            if (networkFails >= NETWORK_MAX_FAILURES) {
                // 连续多次网络不通 → 标记 stopped（UI 展示用），但返回 retry 让 WorkManager 继续调度
                // 使用 retry 而非 failure：failure 会导致 PeriodicWorkRequest 永久停止，
                // 用户不开 App 就无法恢复；retry 则下一次调度时 ping 逻辑可自动检测设备恢复。
                markWorkerStoppedNetwork(ctx)
                SPUtil.setReconnecting(ctx, false)
                DebugLogger.e(TAG, "doWork: NETWORK threshold reached, setting stopped=true, reason=network (retry)")
                BaseWifiWidget.renderAllWidgets(ctx)
                DebugLogger.flushToFile()
                Log.w(TAG, "Network unreachable threshold reached, worker will retry on next interval")
                return@withContext Result.retry()
            }

            // 还没达到阈值 → 继续重试（WorkManager 下次调度再试）
            // 不清除 API 计数 — 网络问题优先，等网络通了再试 API
            DebugLogger.flushToFile()
            return@withContext Result.retry()
        }

        // ====== 步骤 2：ping 通过 → 网络恢复了 ======
        // 重置网络失败计数（网络恢复了）
        resetNetworkFailCount(ctx)
        DebugLogger.d(TAG, "doWork: ping OK, reset network fail count")

        // 如果之前因网络问题被 stopped，此时自动解除 stopped 状态
        val wasNetworkStopped = SPUtil.isWorkerStopped(ctx) &&
                SPUtil.getWorkerStopReason(ctx) == REASON_NETWORK
        if (wasNetworkStopped) {
            resetFailureState(ctx)
            DebugLogger.i(TAG, "doWork: network recovered from stopped, auto-resuming (api failure count also reset)")
            Log.d(TAG, "Network recovered, auto-resuming worker")
            BaseWifiWidget.renderAllWidgets(ctx) // 更新小组件路由器图标
        }

        // ====== 步骤 3：网络可达，尝试 API 请求 ======
        val apiFails = SPUtil.getApiFailureCount(ctx)
        DebugLogger.d(TAG, "doWork: trying API fetch, current apiFails=$apiFails")

        try {
            val data = WifiCrawl.getWifiData(ctx)
            if (data != null) {
                // 基本合理性校验：防止坏数据写入 SP 和流量历史
                if (data.dailyRawBytes < 0 || data.monthlyRawBytes < 0) {
                    DebugLogger.e(TAG, "doWork: data validation failed — negative traffic (daily=${data.dailyRawBytes}, monthly=${data.monthlyRawBytes})")
                    Log.w(TAG, "Data validation failed: negative traffic values")
                    // 不走 return，落入下方 API 失败处理路径
                } else {
                    SPUtil.saveData(ctx, data)
                    // 记录流量数据到 Room（零额外网络开销，数据已在 data 中）
                    TrafficRecordManager.saveRecord(ctx, data.dailyRawBytes, data.monthlyRawBytes)
                    // 全部成功 → 清除所有失败状态
                    resetFailureState(ctx)
                    SPUtil.setReconnecting(ctx, false)
                    // 后台通知检测（仅系统通知栏，不显示应用内 Toast）
                    NotificationHelper.checkAndNotify(
                        context = ctx,
                        dailyFlowStr = data.dailyFlow,
                        monthlyFlowStr = data.flow,
                        tempStr = MainDialogHelper.getHighestTemp(data),
                        cpuStr = data.cpu,
                        memStr = data.mem,
                        batteryPercent = data.batteryPercent,
                        isDeviceOnline = true,
                        activity = null
                    )
                    DebugLogger.i(TAG, "doWork: API success, all failure states cleared")
                    DebugLogger.flushToFile()
                    BaseWifiWidget.renderAllWidgets(ctx)
                    Log.d(TAG, "Data fetch succeeded, all failure states cleared")
                    return@withContext Result.success()
                }
            }
        } catch (e: CancellationException) {
            throw e  // 协程取消必须传播，不能被 catch(Exception) 吞掉
        } catch (e: Exception) {
            DebugLogger.e(TAG, "doWork: API exception: ${e.message}")
            Log.e(TAG, "Data fetch exception: ${e.message}", e)
        }

        // API 请求失败（data == null 或异常）（原子递增，避免与前台竞态）
        // API 失败说明设备不可达，通知检查上下线状态
        NotificationHelper.checkDeviceOnlineStatus(ctx, isOnline = false)
        val newApiFails = incrementApiFailureCount(ctx)
        DebugLogger.w(TAG, "doWork: API failed ($newApiFails/$API_MAX_FAILURES), lastError=${WifiCrawl.lastError}")
        Log.w(TAG, "API fetch failed ($newApiFails/$API_MAX_FAILURES)")

        if (newApiFails >= API_MAX_FAILURES) {
            // 连续 API 失败达到阈值 → 标记 stopped（UI 展示用），但返回 retry 让 WorkManager 继续调度
            // 使用 retry 而非 failure：failure 会导致 PeriodicWorkRequest 永久停止，
            // 用户不开 App 就无法恢复；retry 则下一次调度时 API 请求可自动重试。
            markWorkerStoppedApi(ctx)
            SPUtil.setReconnecting(ctx, false)
            DebugLogger.e(TAG, "doWork: API threshold reached, setting stopped=true, reason=api (retry)")
            DebugLogger.flushToFile()
            BaseWifiWidget.renderAllWidgets(ctx)
            Log.w(TAG, "API failure threshold reached, worker will retry on next interval")
            return@withContext Result.retry()
        }

        // 还有重试配额 → 让 WorkManager 调度下次运行
        DebugLogger.flushToFile()
        Result.retry()
    }
}
