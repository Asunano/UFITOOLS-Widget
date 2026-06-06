package com.ufi_toolswidget.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 多维度调试诊断系统。
 *
 * 分类：
 * - [API/IO]   网络请求、API 调用、数据解析
 * - [UI]       视图状态、渲染事件、主题更新
 * - [SYS]      系统信息、内存、进程状态
 * - [LIFECYCLE] Activity/Fragment 生命周期
 * - [EXCEPTION] 异常捕获、崩溃处理
 * - 快照       系统状态、视图层级一键导出
 */
object DebugLogger {

    private const val TAG = "DebugLogger"
    private const val MAX_ENTRIES = 800

    /** 日志分类 */
    enum class Category(val label: String, val colorTag: String) {
        API("API/数据", "API"),
        UI("UI渲染", "UI"),
        SYS("系统", "SYS"),
        LIFECYCLE("生命周期", "LIFE"),
        EXCEPTION("异常", "EXC"),
        GENERAL("通用", "GEN"),
    }

    /** 所有日志条目（线程安全） */
    private val entries = ConcurrentLinkedQueue<Entry>()

    /** 是否启用调试模式 (通过 SP 持久化) */
    var enabled = false
        set(value) {
            field = value
            contextRef?.get()?.let { SPUtil.setDebugEnabled(it, value) }
        }

    private var contextRef: java.lang.ref.WeakReference<Context>? = null

    /** 系统信息缓存（init 时生成） */
    private val systemInfoCache = StringBuilder()

    /** UI 诊断快照（最近一次 captureUiSnapshot 的内容） */
    private var lastUiSnapshot = ""

    /** 渲染事件计数器 */
    private var renderEventCount = 0
    private var lastRenderEventTime = 0L

    data class Entry(
        val time: Long,
        val level: String,
        val category: Category,
        val tag: String,
        val message: String,
    ) {
        fun formatted(): String {
            val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
            return "[${sdf.format(Date(time))}] [${category.colorTag}] [$level] [$tag] $message"
        }
    }

    // ==================== 初始化 ====================

    /** 初始化，载入持久化配置并采集系统信息 */
    fun init(context: Context) {
        contextRef = java.lang.ref.WeakReference(context.applicationContext)
        enabled = SPUtil.getDebugEnabled(context)
        captureSystemInfo(context)
        log(Category.SYS, "DebugLogger", "DebugLogger initialized, enabled=$enabled")
    }

    // ==================== 系统信息采集 ====================

    /** 采集设备/系统/进程基础信息 */
    private fun captureSystemInfo(ctx: Context) {
        systemInfoCache.clear()
        systemInfoCache.appendLine("========== 系统信息 ==========")
        systemInfoCache.appendLine("采集时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")

        // Android & 设备
        systemInfoCache.appendLine()
        systemInfoCache.appendLine("--- 设备 ---")
        systemInfoCache.appendLine("品牌/型号: ${Build.BRAND} / ${Build.MODEL}")
        systemInfoCache.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        systemInfoCache.appendLine("构建: ${Build.DISPLAY}")
        systemInfoCache.appendLine("架构: ${Build.SUPPORTED_ABIS.joinToString(",")}")

        // 屏幕
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ctx.display?.getRealMetrics(dm)
        } else {
            wm?.defaultDisplay?.getRealMetrics(dm)
        }
        systemInfoCache.appendLine("屏幕: ${dm.widthPixels}x${dm.heightPixels}, dpi=${dm.densityDpi}, density=${dm.density}")

        // 进程
        val pm = ctx.packageManager
        val pi = try { pm.getPackageInfo(ctx.packageName, 0) } catch (_: Exception) { null }
        systemInfoCache.appendLine()
        systemInfoCache.appendLine("--- 应用 ---")
        systemInfoCache.appendLine("包名: ${ctx.packageName}")
        systemInfoCache.appendLine("版本: ${pi?.versionName ?: "unknown"} (${pi?.versionCode ?: 0})")
        systemInfoCache.appendLine("PID: ${Process.myPid()}")
        systemInfoCache.appendLine("进程名: ${getProcessName(ctx)}")

        // 内存
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        systemInfoCache.appendLine()
        systemInfoCache.appendLine("--- 运行时内存 ---")
        systemInfoCache.appendLine("PSS: ${formatMem(memInfo.totalPss.toLong() * 1024)}")
        systemInfoCache.appendLine("Native: ${formatMem(memInfo.nativePss.toLong() * 1024)}")
        systemInfoCache.appendLine("Dalvik: ${formatMem(memInfo.dalvikPss.toLong() * 1024)}")
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        systemInfoCache.appendLine("系统可用: ${formatMem(mi.availMem)}")
        systemInfoCache.appendLine("低内存: ${mi.lowMemory}")

        // 存储
        systemInfoCache.appendLine()
        systemInfoCache.appendLine("--- 存储 ---")
        val dataDir = ctx.filesDir
        systemInfoCache.appendLine("应用数据目录: ${dataDir.absolutePath}")
        systemInfoCache.appendLine("可用空间: ${formatMem(dataDir.usableSpace)}")
        systemInfoCache.appendLine("总计: ${formatMem(dataDir.totalSpace)}")

        // 运行时环境
        systemInfoCache.appendLine()
        systemInfoCache.appendLine("--- 运行时 ---")
        systemInfoCache.appendLine("VM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")
        systemInfoCache.appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
    }

    /** 获取系统信息文本 */
    fun getSystemInfo() = systemInfoCache.toString()

    // ==================== UI 诊断 ====================

    /** 捕获指定 View 的层级信息快照（不含敏感数据） */
    fun captureUiSnapshot(root: View?): String {
        if (root == null) return "根视图为 null"
        val sb = StringBuilder()
        sb.appendLine("========== UI 视图诊断 ==========")
        sb.appendLine("采集时间: ${SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())}")
        sb.appendLine("渲染事件总数: $renderEventCount")
        sb.appendLine()

        dumpViewHierarchy(root, sb, 0)
        lastUiSnapshot = sb.toString()
        return lastUiSnapshot
    }

    /** 获取上一次 UI 快照 */
    fun getLastUiSnapshot() = lastUiSnapshot.ifEmpty { "(尚未采集 UI 快照)" }

    /** 递归打印视图层级 */
    private fun dumpViewHierarchy(view: View, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        val cls = view.javaClass.simpleName
        val id = if (view.id != View.NO_ID) try { view.resources.getResourceEntryName(view.id) } catch (_: Exception) { "#${view.id}" } else "NO_ID"
        val visibility = when (view.visibility) {
            View.VISIBLE -> "V"
            View.INVISIBLE -> "I"
            View.GONE -> "G"
            else -> "?"
        }
        val dims = "(${view.width}x${view.height})"

        // 额外信息
        val extras = mutableListOf<String>()
        if (view is TextView) {
            extras.add("text=\"${view.text}\"")
            extras.add("size=${view.textSize}")
            extras.add("color=#${Integer.toHexString(view.currentTextColor)}")
        }
        extras.add("vis=$visibility")
        extras.add(dims)

        sb.appendLine("$indent$cls [$id] ${extras.joinToString(" ")}")

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                dumpViewHierarchy(view.getChildAt(i), sb, depth + 1)
            }
        }
    }

    // ==================== 日志记录（分类） ====================

    /** 记录一条带分类的调试日志 */
    fun log(category: Category, tag: String, message: String, force: Boolean = false) {
        if (!enabled && !force) return

        val maskedMsg = maskSensitiveInfo(message)
        val entry = Entry(System.currentTimeMillis(), categoryLevel(category), category, tag, maskedMsg)
        entries.add(entry)

        while (entries.size > MAX_ENTRIES) {
            entries.poll()
        }

        // Logcat 输出
        val logMsg = "[${category.colorTag}] [$tag] $maskedMsg"
        when (category) {
            Category.EXCEPTION -> Log.e(tag, logMsg)
            else -> Log.d(tag, logMsg)
        }

        // 统计渲染事件
        if (category == Category.UI) {
            renderEventCount++
            lastRenderEventTime = System.currentTimeMillis()
        }

        saveEntryToFile(entry)
    }

    private fun categoryLevel(cat: Category) = when (cat) {
        Category.EXCEPTION -> "E"
        Category.SYS -> "I"
        else -> "D"
    }

    /** 便捷方法：按分类记录 */
    fun logApi(tag: String, msg: String) = log(Category.API, tag, msg)
    fun logApiErr(tag: String, msg: String) = log(Category.API, tag, msg)
    fun logUi(tag: String, msg: String) = log(Category.UI, tag, msg)
    fun logSys(tag: String, msg: String) = log(Category.SYS, tag, msg)
    fun logLife(tag: String, msg: String) = log(Category.LIFECYCLE, tag, msg)
    fun logExc(tag: String, msg: String) = log(Category.EXCEPTION, tag, msg)

    /** 通用记录（向后兼容） */
    fun d(tag: String, message: String) = log(Category.GENERAL, tag, message)
    fun i(tag: String, message: String) = log(Category.GENERAL, tag, message)
    fun w(tag: String, message: String, force: Boolean = false) = log(Category.GENERAL, tag, message, force)
    fun e(tag: String, message: String, force: Boolean = false) = log(Category.EXCEPTION, tag, message, force)

    /** 记录崩溃异常 */
    fun logCrash(ex: Throwable) {
        val sw = StringWriter()
        ex.printStackTrace(PrintWriter(sw))
        log(Category.EXCEPTION, "CrashHandler", sw.toString(), force = true)
    }

    // ==================== 查询方法 ====================

    fun getAll(): List<Entry> = entries.toList().reversed()
    fun getRecent(n: Int = 100): List<Entry> = entries.toList().takeLast(n).reversed()
    fun size(): Int = entries.size

    /** 按分类筛选 */
    fun getByCategory(cat: Category, n: Int = 50): List<Entry> =
        entries.toList().filter { it.category == cat }.takeLast(n).reversed()

    /** 获取所有日志的格式化文本 */
    fun getAllText(): String = getAll().joinToString("\n") { it.formatted() }

    /** 获取分类统计 */
    fun getCategoryStats(): Map<Category, Int> =
        entries.groupBy { it.category }.mapValues { it.value.size }

    // ==================== 全量诊断报告 ====================

    /** 生成完整诊断报告：系统信息 + UI快照 + 分类日志 + API状态 */
    fun generateFullReport(context: Context, rootView: View? = null): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        sb.appendLine("╔══════════════════════════════════════════╗")
        sb.appendLine("║     UFITOOLS-Widget 全量诊断报告          ║")
        sb.appendLine("║     生成时间: $now               ║")
        sb.appendLine("╚══════════════════════════════════════════╝")
        sb.appendLine()

        // 1. 系统信息
        sb.appendLine(systemInfoCache.toString())
        sb.appendLine()

        // 2. UI 诊断（如有）
        if (rootView != null) {
            captureUiSnapshot(rootView)
        }
        if (lastUiSnapshot.isNotEmpty()) {
            sb.appendLine(lastUiSnapshot)
            sb.appendLine()
        }

        // 3. 日志分类统计
        val stats = getCategoryStats()
        if (stats.isNotEmpty()) {
            sb.appendLine("========== 日志统计 ==========")
            stats.toList().sortedByDescending { it.second }.forEach { (cat, count) ->
                sb.appendLine("  ${cat.label}: $count 条")
            }
            sb.appendLine("  总计: ${entries.size} 条")
            sb.appendLine()
        }

        // 4. 最近 50 条 API 日志
        val apiLogs = getByCategory(Category.API, 50)
        if (apiLogs.isNotEmpty()) {
            sb.appendLine("========== 最近 API 日志 (50条) ==========")
            apiLogs.forEach { sb.appendLine(it.formatted()) }
            sb.appendLine()
        }

        // 5. 最近 30 条 UI 日志
        val uiLogs = getByCategory(Category.UI, 30)
        if (uiLogs.isNotEmpty()) {
            sb.appendLine("========== 最近 UI 渲染日志 (30条) ==========")
            uiLogs.forEach { sb.appendLine(it.formatted()) }
            sb.appendLine()
        }

        // 6. 异常日志（最近 20 条）
        val excLogs = getByCategory(Category.EXCEPTION, 20)
        if (excLogs.isNotEmpty()) {
            sb.appendLine("========== 最近异常日志 (20条) ==========")
            excLogs.forEach { sb.appendLine(it.formatted()) }
            sb.appendLine()
        }

        // 7. API 状态快照
        sb.appendLine(dumpApiState(context))

        return sb.toString()
    }

    /** API 状态快照（连接/WiFiCrawl 最后状态） */
    fun dumpApiState(context: Context): String {
        val sp = context.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
        val sb = StringBuilder()
        sb.appendLine("========== API 连接状态 ==========")
        sb.appendLine("目标地址: ${desensitize(SPUtil.getDeviceAddress(context))}")
        sb.appendLine("完整 URL: ${desensitize(SPUtil.buildBaseUrl(context))}")
        sb.appendLine("认证令牌: [ENCRYPTED]")
        sb.appendLine()
        sb.appendLine("[运行状态]")
        sb.appendLine("Worker 停止: ${sp.getBoolean("worker_stopped_by_failure", false)}")
        sb.appendLine("失败计数 (API/Net): ${sp.getInt("worker_api_failure_count", 0)} / ${sp.getInt("worker_network_failure_count", 0)}")
        sb.appendLine("停止原因: ${SPUtil.getWorkerStopReason(context)}")
        sb.appendLine("探测协议: ${SPUtil.getDeviceProtocol(context)}")
        sb.appendLine()
        sb.appendLine("[最后请求]")
        sb.appendLine("错误: ${WifiCrawl.lastError}")
        sb.appendLine("响应 (脱敏): ${maskSensitiveInfo(WifiCrawl.lastRawResponse).take(1000)}")
        return sb.toString()
    }

    /** 转储当前状态（向后兼容别名） */
    fun dumpState(context: Context): String = dumpApiState(context)

    // ==================== 清理 ====================

    fun clear() {
        entries.clear()
        lastUiSnapshot = ""
        renderEventCount = 0
        contextRef?.get()?.let { ctx ->
            try {
                val file = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "app_debug.log")
                if (file.exists()) file.delete()
            } catch (_: Exception) {}
        }
    }

    /** 获取持久化的日志内容 */
    fun getPersistentLogs(ctx: Context): String {
        return try {
            val file = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "app_debug.log")
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            "读取持久化日志失败: ${e.message}"
        }
    }

    // ==================== 脱敏 ====================

    /** 公开脱敏方法，供 CrashHandler 等外部组件使用 */
    fun maskSensitive(input: String): String {
        if (input.isEmpty()) return input
        return input
            .replace(Regex("(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"), "$1.***.***.***")
            .replace(Regex("\\b\\d{15,17}\\b"), "***************")
            .replace(Regex("\"(token|password|auth_token|imei)\"\\s*:\\s*\"[^\"]+\""), "\"$1\":\"***\"")
            .replace(Regex("Authorization: [^\\s]+"), "Authorization: [MASKED]")
    }

    /** 智能识别并脱敏敏感信息 */
    private fun maskSensitiveInfo(input: String): String {
        if (input.isEmpty()) return input
        return input
            .replace(Regex("(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"), "$1.***.***.***")
            .replace(Regex("\\b\\d{15,17}\\b"), "***************")
            .replace(Regex("\"(token|password|auth_token|imei)\"\\s*:\\s*\"[^\"]+\""), "\"$1\":\"***\"")
            .replace(Regex("Authorization: [^\\s]+"), "Authorization: [MASKED]")
    }

    private fun desensitize(input: String): String {
        if (input.isEmpty()) return ""
        if (input.length <= 6) return "****"
        return input.take(3) + "****" + input.takeLast(3)
    }

    // ==================== 内部工具 ====================

    private fun saveEntryToFile(entry: Entry) {
        contextRef?.get()?.let { ctx ->
            try {
                val file = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "app_debug.log")
                if (file.exists() && file.length() > 3 * 1024 * 1024) {
                    file.writeText("[Log truncated due to size]\n")
                }
                file.appendText(entry.formatted() + "\n")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save log to file: ${e.message}")
            }
        }
    }

    private fun formatMem(bytes: Long): String = when {
        bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun getProcessName(ctx: Context): String {
        return try {
            val pid = Process.myPid()
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.runningAppProcesses?.find { it.pid == pid }?.processName ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }
}
