package com.ufi_toolswidget.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.WifiCrawl
import com.ufi_toolswidget.widget.BaseWifiWidget
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

        /** SP 键 */
        const val KEY_WORKER_STOPPED = "worker_stopped_by_failure"
        const val KEY_API_FAILURE_COUNT = "worker_api_failure_count"
        const val KEY_NETWORK_FAILURE_COUNT = "worker_network_failure_count"

        /** 失败原因常量 */
        const val REASON_NETWORK = "network"   // 设备不在线/网络不通
        const val REASON_API = "api"           // 端口/Token/认证配置错误

        /**
         * TCP ping 设备 IP:端口，检测网络是否可达。
         * 内网环境正常响应远低于 1 秒，超过则视为不可达。
         */
        private fun pingDevice(ip: String, port: Int): Boolean {
            return try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), PING_TIMEOUT_MS)
                socket.close()
                Log.d(TAG, "Ping $ip:$port OK")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Ping $ip:$port FAILED: ${e.message}")
                false
            }
        }

        /**
         * 重置失败状态（手动刷新、配置变更、Worker 启动时调用）
         */
        fun resetFailureState(context: Context) {
            val sp = context.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
            val prevStopped = sp.getBoolean(KEY_WORKER_STOPPED, false)
            sp.edit()
                .putInt(KEY_API_FAILURE_COUNT, 0)
                .putInt(KEY_NETWORK_FAILURE_COUNT, 0)
                .putBoolean(KEY_WORKER_STOPPED, false)
                .apply()
            SPUtil.setWorkerStopReason(context, "")
            DebugLogger.i(TAG, "resetFailureState called (prevStopped=$prevStopped)")
            Log.d(TAG, "Failure state reset")
        }

        /**
         * 检查 worker 是否因连续失败被停止
         */
        fun isWorkerStopped(context: Context): Boolean {
            return context.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
                .getBoolean(KEY_WORKER_STOPPED, false)
        }

        /**
         * 获取当前失败原因（用于外部显示状态）
         */
        fun getFailureSummary(context: Context): String {
            val sp = context.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
            val stopped = sp.getBoolean(KEY_WORKER_STOPPED, false)
            if (!stopped) return ""
            return SPUtil.getWorkerStopReason(context).ifEmpty { "unknown" }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val sp = ctx.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)

        val ip = SPUtil.getDeviceIp(ctx)
        val port = SPUtil.getDevicePort(ctx).toIntOrNull() ?: 2333

        val prevStopped = sp.getBoolean(KEY_WORKER_STOPPED, false)
        val prevApiFails = sp.getInt(KEY_API_FAILURE_COUNT, 0)
        val prevNetFails = sp.getInt(KEY_NETWORK_FAILURE_COUNT, 0)
        DebugLogger.d(TAG, "doWork() started: ip=$ip:$port, prevStopped=$prevStopped, apiFails=$prevApiFails, netFails=$prevNetFails")

        // ====== 步骤 1：每次运行都先 ping 检测网络可达性 ======
        // 即使之前被 stopped，也要尝试 ping — 这样设备恢复后能自动感知
        val pingOk = pingDevice(ip, port)
        DebugLogger.d(TAG, "doWork: ping $ip:$port = $pingOk")

        if (!pingOk) {
            // 网络不通
            val networkFails = sp.getInt(KEY_NETWORK_FAILURE_COUNT, 0) + 1
            sp.edit().putInt(KEY_NETWORK_FAILURE_COUNT, networkFails).apply()
            DebugLogger.w(TAG, "doWork: network unreachable ($networkFails/$NETWORK_MAX_FAILURES)")

            Log.w(TAG, "Device unreachable ($networkFails/$NETWORK_MAX_FAILURES)")

            if (networkFails >= NETWORK_MAX_FAILURES) {
                // 连续多次网络不通 → 标记 stopped，省电
                sp.edit()
                    .putBoolean(KEY_WORKER_STOPPED, true)
                    .putInt(KEY_API_FAILURE_COUNT, 0)
                    .apply()
                SPUtil.setWorkerStopReason(ctx, REASON_NETWORK)
                DebugLogger.e(TAG, "doWork: NETWORK threshold reached, setting stopped=true, reason=network")
                BaseWifiWidget.renderAllWidgets(ctx)
                Log.w(TAG, "Network unreachable threshold reached, worker stopped")
                return@withContext Result.failure()
            }

            // 还没达到阈值 → 继续重试（WorkManager 下次调度再试）
            // 不清除 API 计数 — 网络问题优先，等网络通了再试 API
            return@withContext Result.retry()
        }

        // ====== 步骤 2：ping 通过 → 网络恢复了 ======
        // 重置网络失败计数（网络恢复了）
        sp.edit().putInt(KEY_NETWORK_FAILURE_COUNT, 0).apply()
        DebugLogger.d(TAG, "doWork: ping OK, reset network fail count")

        // 如果之前因网络问题被 stopped，此时自动解除 stopped 状态
        val wasNetworkStopped = sp.getBoolean(KEY_WORKER_STOPPED, false) &&
                SPUtil.getWorkerStopReason(ctx) == REASON_NETWORK
        if (wasNetworkStopped) {
            sp.edit().putBoolean(KEY_WORKER_STOPPED, false).apply()
            SPUtil.setWorkerStopReason(ctx, "")
            DebugLogger.i(TAG, "doWork: network recovered from stopped, auto-resuming")
            Log.d(TAG, "Network recovered, auto-resuming worker")
            BaseWifiWidget.renderAllWidgets(ctx) // 更新小组件路由器图标
        }

        // ====== 步骤 3：网络可达，尝试 API 请求 ======
        val apiFails = sp.getInt(KEY_API_FAILURE_COUNT, 0)
        DebugLogger.d(TAG, "doWork: trying API fetch, current apiFails=$apiFails")

        try {
            val data = WifiCrawl.getWifiData(ctx)
            if (data != null) {
                SPUtil.saveData(ctx, data)
                // 全部成功 → 清除所有失败状态
                sp.edit()
                    .putInt(KEY_API_FAILURE_COUNT, 0)
                    .putBoolean(KEY_WORKER_STOPPED, false)
                    .apply()
                SPUtil.setWorkerStopReason(ctx, "")
                DebugLogger.i(TAG, "doWork: API success, all failure states cleared")
                BaseWifiWidget.renderAllWidgets(ctx)
                Log.d(TAG, "Data fetch succeeded, all failure states cleared")
                return@withContext Result.success()
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "doWork: API exception: ${e.message}")
            Log.e(TAG, "Data fetch exception: ${e.message}", e)
        }

        // API 请求失败（data == null 或异常）
        val newApiFails = apiFails + 1
        sp.edit().putInt(KEY_API_FAILURE_COUNT, newApiFails).apply()
        DebugLogger.w(TAG, "doWork: API failed ($newApiFails/$API_MAX_FAILURES), lastError=${WifiCrawl.lastError}")
        Log.w(TAG, "API fetch failed ($newApiFails/$API_MAX_FAILURES)")

        if (newApiFails >= API_MAX_FAILURES) {
            // 连续 API 失败达到阈值 → 标记 stopped
            sp.edit().putBoolean(KEY_WORKER_STOPPED, true).apply()
            SPUtil.setWorkerStopReason(ctx, REASON_API)
            DebugLogger.e(TAG, "doWork: API threshold reached, setting stopped=true, reason=api")
            BaseWifiWidget.renderAllWidgets(ctx)
            Log.w(TAG, "API failure threshold reached, worker stopped")
            return@withContext Result.failure()
        }

        // 还有重试配额 → 让 WorkManager 调度下次运行
        Result.retry()
    }
}
