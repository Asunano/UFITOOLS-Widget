package com.ufi_toolswidget.util

import android.content.Context
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 调试日志收集器。
 * 在内存中保存最近 N 条日志，支持复制/分享/导出。
 * 日志按时间排序，最新在前。
 */
object DebugLogger {

    private const val TAG = "DebugLogger"
    private const val MAX_ENTRIES = 500

    /** 所有日志条目（线程安全） */
    private val entries = ConcurrentLinkedQueue<Entry>()

    /** 是否启用调试模式 */
    @Volatile var enabled = false

    data class Entry(
        val time: Long,
        val level: String,
        val tag: String,
        val message: String,
    ) {
        fun formatted(): String {
            val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
            return "[${sdf.format(Date(time))}] [$level] [$tag] $message"
        }
    }

    /** 记录一条调试日志 */
    fun log(level: String, tag: String, message: String) {
        if (!enabled) return
        val entry = Entry(System.currentTimeMillis(), level, tag, message)
        entries.add(entry)
        // 保持数量上限
        while (entries.size > MAX_ENTRIES) {
            entries.poll()
        }
        // 同步写入 Logcat
        when (level) {
            "E" -> Log.e(tag, message)
            "W" -> Log.w(tag, message)
            "D" -> Log.d(tag, message)
            "I" -> Log.i(tag, message)
            else -> Log.d(tag, message)
        }
    }

    /** 记录异常 */
    fun logException(tag: String, message: String, throwable: Throwable) {
        if (!enabled) return
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val fullMsg = "$message\n${sw.toString()}"
        log("E", tag, fullMsg)
    }

    // 便捷方法
    fun d(tag: String, message: String) = log("D", tag, message)
    fun i(tag: String, message: String) = log("I", tag, message)
    fun w(tag: String, message: String) = log("W", tag, message)
    fun e(tag: String, message: String) = log("E", tag, message)

    /** 获取所有日志（最新在前） */
    fun getAll(): List<Entry> = entries.toList().reversed()

    /** 获取最近 N 条日志（最新在前） */
    fun getRecent(n: Int = 100): List<Entry> = entries.toList().takeLast(n).reversed()

    /** 获取所有日志的文本表示（用于分享/复制） */
    fun getAllText(): String = getAll().joinToString("\n") { it.formatted() }

    /** 获取最近 N 条的文本表示 */
    fun getRecentText(n: Int = 100): String = getRecent(n).joinToString("\n") { it.formatted() }

    /** 获取日志条数 */
    fun size(): Int = entries.size

    /** 清空日志 */
    fun clear() {
        entries.clear()
    }

    /**
     * 导出日志到应用私有目录，返回文件路径
     */
    fun exportToFile(context: Context): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val fileName = "debug_log_${sdf.format(Date())}.txt"
        val file = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, fileName)
        file.writeText(getAllText())
        Log.d(TAG, "Log exported to: ${file.absolutePath}")
        return file.absolutePath
    }

    /**
     * 转储当前关键状态（SP 中的 Worker 状态 + 配置信息）
     */
    fun dumpState(context: Context): String {
        val sp = context.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
        val sb = StringBuilder()
        sb.appendLine("========== 当前状态快照 ==========")
        sb.appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        sb.appendLine()

        // 连接配置
        sb.appendLine("--- 连接配置 ---")
        sb.appendLine("device_ip: ${SPUtil.getDeviceIp(context)}")
        sb.appendLine("device_port: ${SPUtil.getDevicePort(context)}")
        sb.appendLine("base_url: ${SPUtil.buildBaseUrl(context)}")
        sb.appendLine("auth_token: ${SPUtil.getAuthToken(context).take(20)}...")
        sb.appendLine("raw_token: ${SPUtil.getRawToken(context).take(10)}...")
        sb.appendLine()

        // Worker 状态
        sb.appendLine("--- Worker 状态 ---")
        sb.appendLine("worker_stopped_by_failure: ${sp.getBoolean("worker_stopped_by_failure", false)}")
        sb.appendLine("worker_api_failure_count: ${sp.getInt("worker_api_failure_count", 0)}")
        sb.appendLine("worker_network_failure_count: ${sp.getInt("worker_network_failure_count", 0)}")
        sb.appendLine("worker_stop_reason: ${SPUtil.getWorkerStopReason(context)}")
        sb.appendLine()

        // 刷新配置
        sb.appendLine("--- 刷新配置 ---")
        sb.appendLine("refresh_interval: ${SPUtil.getRefreshInterval(context)} min")
        sb.appendLine("main_refresh_seconds: ${SPUtil.getMainRefreshSeconds(context)} s")
        sb.appendLine()

        // 主题配置
        sb.appendLine("--- 主题配置 ---")
        sb.appendLine("app_theme: ${SPUtil.getAppTheme(context)}")
        sb.appendLine("widget_theme: ${SPUtil.getWidgetTheme(context)}")
        sb.appendLine("color_theme: ${SPUtil.getColorThemeIndex(context)}")
        sb.appendLine()

        // 最近数据
        sb.appendLine("--- 最近缓存数据 ---")
        sb.appendLine("update_time: ${sp.getString("update_time", "never")}")
        sb.appendLine("signal: ${sp.getString("signal", "N/A")}")
        sb.appendLine("model: ${sp.getString("model", "N/A")}")
        sb.appendLine("device_model: ${sp.getString("device_model", "N/A")}")
        sb.appendLine("cpu: ${sp.getString("cpu", "N/A")}")
        sb.appendLine("mem: ${sp.getString("mem", "N/A")}")
        sb.appendLine("daily_flow: ${sp.getString("daily_flow", "N/A")}")
        sb.appendLine("flow: ${sp.getString("flow", "N/A")}")
        sb.appendLine()

        // 错误信息
        sb.appendLine("--- 最后一次请求信息 ---")
        sb.appendLine("WifiCrawl.lastError: ${WifiCrawl.lastError}")
        sb.appendLine("WifiCrawl.lastRawResponse: ${WifiCrawl.lastRawResponse.take(500)}")
        sb.appendLine()

        sb.appendLine("--- 日志数量 ---")
        sb.appendLine("debug_entries: ${entries.size}")
        sb.appendLine()

        return sb.toString()
    }
}
